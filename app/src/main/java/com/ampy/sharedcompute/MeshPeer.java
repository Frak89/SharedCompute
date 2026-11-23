package com.ampy.sharedcompute;

import android.app.Service;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** SharedCompute Mesh: LAN discovery, approved pairing, secure small transfers.
 *  MASTER here coordinates pairing / transfers, not native Android LLM inference.
 */
final class MeshPeer implements AutoCloseable {
 static final int DISCOVERY=50555,TLS_PORT=50554,FILE_LIMIT=2*1024*1024,FRAME_LIMIT=3*1024*1024;
 static final String GROUP="239.255.50.55",PROTOCOL="sharedcompute-mesh-v1",ALIAS="sharedcompute-mesh-v1";
 final Service service;final String mode,deviceId,name;final SharedPreferences prefs;
 final ConcurrentHashMap<String,JSONObject> peers=new ConcurrentHashMap<>();
 final ConcurrentHashMap<String,Pending> pending=new ConcurrentHashMap<>();
 final ConcurrentHashMap<String,Long> autoAttempts=new ConcurrentHashMap<>();
 final ConcurrentHashMap<String,String> rpcAnnounced=new ConcurrentHashMap<>();
 final ExecutorService pool=Executors.newFixedThreadPool(8);
 volatile boolean closed;volatile SSLServerSocket tls;volatile MulticastSocket udp;volatile String fingerprint="";
 WifiManager.MulticastLock wifiLock;
 static class Pending {String id,device,name;CountDownLatch latch=new CountDownLatch(1);volatile boolean allow;volatile String token="";}
 MeshPeer(Service service,String mode){
  this.service=service;this.mode=mode;prefs=service.getSharedPreferences("sharedcompute-mesh",0);
  String id=prefs.getString("device_id",null);if(id==null){id=UUID.randomUUID().toString();prefs.edit().putString("device_id",id).apply();}
  deviceId=id;name=(Build.MANUFACTURER+" "+Build.MODEL).trim();
 }
 void log(String s){NodeService.add("MESH: "+s);}
 void start(){new Thread(this::serve,"mesh-tls").start();new Thread(this::discover,"mesh-discover").start();}
 private static String hex(byte[] data){StringBuilder b=new StringBuilder();for(byte value:data)b.append(String.format(Locale.ROOT,"%02x",value&255));return b.toString();}
 private static byte[] hash(byte[] bytes)throws Exception{return MessageDigest.getInstance("SHA-256").digest(bytes);}
 private SSLContext serverContext()throws Exception{
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
  if(!ks.containsAlias(ALIAS)){
   KeyPairGenerator gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
   gen.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_DECRYPT)
    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1).build());gen.generateKeyPair();
  }
  Certificate cert=ks.getCertificate(ALIAS);fingerprint=hex(hash(cert.getEncoded()));
  KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(ks,null);
  SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(km.getKeyManagers(),null,new SecureRandom());return ctx;
 }
 private void serve(){
  try{
   SSLContext context=serverContext();
   SSLServerSocket ss=(SSLServerSocket)context.getServerSocketFactory().createServerSocket();tls=ss;
   ss.setReuseAddress(true);ss.bind(new InetSocketAddress("0.0.0.0",TLS_PORT));ss.setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.3"});
   log("Ready on TLS "+TLS_PORT+" as "+mode);
   while(!closed){SSLSocket s=(SSLSocket)ss.accept();pool.execute(()->serveOne(s));}
  }catch(Exception e){if(!closed)log("TLS server: "+e.getClass().getSimpleName()+" "+e.getMessage());}
 }
 static JSONObject readFrame(InputStream in)throws Exception{
  DataInputStream dis=new DataInputStream(in);int len=dis.readInt();if(len<1||len>FRAME_LIMIT)throw new IOException("Bad mesh frame size");
  byte[] data=new byte[len];dis.readFully(data);return new JSONObject(new String(data,StandardCharsets.UTF_8));
 }
 static void writeFrame(OutputStream out,JSONObject j)throws Exception{
  byte[] bytes=j.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>FRAME_LIMIT)throw new IOException("Oversized mesh frame");
  DataOutputStream dos=new DataOutputStream(out);dos.writeInt(bytes.length);dos.write(bytes);dos.flush();
 }
 static String safe(String raw){if(raw==null)return "file.bin";String v=raw.replaceAll("[^\\p{L}\\p{N}._ -]","_").replaceAll("^\\.+","");return v.isEmpty()?"file.bin":v.substring(0,Math.min(100,v.length()));}
 void serveOne(SSLSocket s){
  try(SSLSocket c=s){
   c.setSoTimeout(90000);c.startHandshake();JSONObject req=readFrame(c.getInputStream());
   String other=req.optString("device_id","");if(other.length()<1||other.length()>100)throw new IOException("Invalid peer identity");
   String supplied=req.optString("token",""),known=prefs.getString("trust_"+other,"");
   boolean auth=!known.isEmpty()&&MessageDigest.isEqual(known.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII));
   String op=req.optString("op","");
   if(op.equals("pair")){
    if(auth){writeFrame(c.getOutputStream(),new JSONObject().put("status","ok").put("trusted",true));return;}
    if(mode.equals("WORKER")||!known.isEmpty()){writeFrame(c.getOutputStream(),new JSONObject().put("status","denied"));return;}
    Pending p=new Pending();p.id=UUID.randomUUID().toString();p.device=other;p.name=safe(req.optString("name","Unknown"));pending.put(p.id,p);
    log("Approval required from "+p.name);
    p.latch.await(60,TimeUnit.SECONDS);pending.remove(p.id);
    JSONObject reply=new JSONObject().put("status",p.allow?"ok":"denied");if(p.allow)reply.put("token",p.token);
    writeFrame(c.getOutputStream(),reply);
   }else if(op.equals("rpc_info")){
    if(!auth||mode.equals("WORKER")){writeFrame(c.getOutputStream(),new JSONObject().put("status","denied"));return;}
    String rpc=req.optString("rpc","");String[] pieces=rpc.split("\\|");
    if(pieces.length!=3||!pieces[1].trim().matches("[0-9a-fA-F]{64}")||!pieces[2].trim().matches("[0-9a-fA-F]{64}")){
     writeFrame(c.getOutputStream(),new JSONObject().put("status","error"));return;
    }
    prefs.edit().putString("rpc_"+other,rpc).apply();log("Trusted RPC worker connected: "+other);
    writeFrame(c.getOutputStream(),new JSONObject().put("status","ok"));
   }else if(op.equals("send")){
    if(!auth){writeFrame(c.getOutputStream(),new JSONObject().put("status","denied"));return;}
    String encoded=req.optString("data","");if(encoded.length()>2800000)throw new IOException("File too big");
    byte[] raw=Base64.decode(encoded,Base64.DEFAULT);if(raw.length>FILE_LIMIT)throw new IOException("File exceeds 2 MiB");
    File inbox=new File(service.getFilesDir(),"mesh-inbox");if(!inbox.exists()&&!inbox.mkdirs())throw new IOException("Cannot create inbox");
    String filename=UUID.randomUUID().toString().substring(0,8)+"-"+safe(req.optString("filename","file.bin"));
    try(FileOutputStream out=new FileOutputStream(new File(inbox,filename))){out.write(raw);}
    log("Received "+filename+" ("+raw.length+" bytes)");
    writeFrame(c.getOutputStream(),new JSONObject().put("status","ok"));
   }else writeFrame(c.getOutputStream(),new JSONObject().put("status","error"));
  }catch(Exception e){if(!closed)log("Incoming TLS: "+e.getClass().getSimpleName()+" "+e.getMessage());}
 }
 void approve(String id,boolean accept){Pending p=pending.get(id);if(p==null)return;
  if(accept){p.token=hex(random(32));p.allow=true;prefs.edit().putString("trust_"+p.device,p.token).apply();}
  p.latch.countDown();
 }
 private static byte[] random(int size){byte[] b=new byte[size];new SecureRandom().nextBytes(b);return b;}
 JSONObject offer()throws Exception{return new JSONObject().put("protocol",PROTOCOL).put("type","offer")
  .put("name",name).put("device_id",deviceId).put("role",mode).put("port",TLS_PORT).put("pin",fingerprint).put("platform","Android");}
 private void discover(){
  try{
   WifiManager wm=(WifiManager)service.getApplicationContext().getSystemService(Service.WIFI_SERVICE);
   if(wm!=null){wifiLock=wm.createMulticastLock("SharedComputeMesh");wifiLock.setReferenceCounted(false);wifiLock.acquire();}
   MulticastSocket ms=new MulticastSocket(null);udp=ms;ms.setReuseAddress(true);ms.bind(new InetSocketAddress("0.0.0.0",DISCOVERY));
   ms.joinGroup(InetAddress.getByName(GROUP));ms.setBroadcast(true);ms.setSoTimeout(1000);
   long last=0;byte[] recv=new byte[2048];
   while(!closed){
    if(!fingerprint.isEmpty()&&SystemClock.elapsedRealtime()-last>4000){
     last=SystemClock.elapsedRealtime();byte[] packet=offer().put("type","discover").toString().getBytes(StandardCharsets.UTF_8);
     for(String addr:new String[]{GROUP,"255.255.255.255"})try{ms.send(new DatagramPacket(packet,packet.length,InetAddress.getByName(addr),DISCOVERY));}catch(Exception ignored){}
    }
    DatagramPacket p=new DatagramPacket(recv,recv.length);
    try{ms.receive(p);}catch(SocketTimeoutException e){continue;}
    try{
     JSONObject other=new JSONObject(new String(p.getData(),p.getOffset(),p.getLength(),StandardCharsets.UTF_8));
     if(!PROTOCOL.equals(other.optString("protocol"))||deviceId.equals(other.optString("device_id")))continue;
     if(!Arrays.asList("MASTER","WORKER","AUTO").contains(other.optString("role")))continue;
     if(!other.optString("pin").matches("[0-9a-fA-F]{64}"))continue;
     if(!Arrays.asList("offer","discover").contains(other.optString("type")))continue;
     other.put("ip",p.getAddress().getHostAddress());other.put("seen",SystemClock.elapsedRealtime());peers.put(other.getString("device_id"),other);
     String remoteRole=other.optString("role"),remoteId=other.optString("device_id");
     boolean autoJoin=(mode.equals("WORKER")&&(remoteRole.equals("MASTER")||remoteRole.equals("AUTO")))||
      (mode.equals("AUTO")&&(remoteRole.equals("MASTER")||(remoteRole.equals("AUTO")&&deviceId.compareTo(remoteId)>0)));
     if(autoJoin){
      long now=SystemClock.elapsedRealtime();Long lastAttempt=autoAttempts.get(remoteId);
      if(lastAttempt==null||now-lastAttempt>120000){autoAttempts.put(remoteId,now);log("Auto pairing: "+other.optString("name"));pair(other);}
      advertiseRpc(other);
     }
     if(other.getString("type").equals("discover")&&!fingerprint.isEmpty()){
      byte[] reply=offer().toString().getBytes(StandardCharsets.UTF_8);ms.send(new DatagramPacket(reply,reply.length,p.getAddress(),p.getPort()));
     }
    }catch(Exception e){log("Discovery: "+e.getClass().getSimpleName());}
   }
  }catch(Exception e){if(!closed)log("Discovery unavailable: "+e.getClass().getSimpleName()+" "+e.getMessage());}
  finally{if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();}
 }
 JSONObject connect(JSONObject peer,JSONObject request)throws Exception{
  String peerId=peer.getString("device_id"),pin=peer.getString("pin"),known=prefs.getString("pin_"+peerId,"");
  if(!known.isEmpty()&&!known.equalsIgnoreCase(pin))throw new SecurityException("Saved peer identity changed; re-pair manually");
  TrustManager[] trust=new TrustManager[]{new X509TrustManager(){public java.security.cert.X509Certificate[] getAcceptedIssuers(){return new java.security.cert.X509Certificate[0];}
   public void checkClientTrusted(java.security.cert.X509Certificate[] chain,String auth){}
   public void checkServerTrusted(java.security.cert.X509Certificate[] chain,String auth){}}};
  SSLContext context=SSLContext.getInstance("TLS");context.init(null,trust,new SecureRandom());
  try(SSLSocket c=(SSLSocket)context.getSocketFactory().createSocket()){
   c.connect(new InetSocketAddress(peer.getString("ip"),peer.optInt("port",TLS_PORT)),8000);c.setSoTimeout(75000);c.startHandshake();
   Certificate actual=c.getSession().getPeerCertificates()[0];
   if(!MessageDigest.isEqual(hash(actual.getEncoded()),hexDecode(pin)))throw new SecurityException("TLS fingerprint mismatch");
   String token=prefs.getString("trust_"+peerId,"");request.put("device_id",deviceId).put("name",name).put("token",token);
   writeFrame(c.getOutputStream(),request);JSONObject response=readFrame(c.getInputStream());
   if(response.optString("status").equals("ok"))prefs.edit().putString("pin_"+peerId,pin).apply();
   return response;
  }
 }
 private static byte[] hexDecode(String x){if(!x.matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("Bad fingerprint");byte[] b=new byte[32];for(int i=0;i<32;i++)b[i]=(byte)Integer.parseInt(x.substring(i*2,i*2+2),16);return b;}
 void advertiseRpc(JSONObject peer){
  if(mode.equals("MASTER"))return;
  NodeService svc=NodeService.instance;if(svc==null)return;
  String all=svc.getPairing(),line=null;
  for(String candidate:all.split("\\n"))if(candidate.contains(" | ")){
   String[] parts=candidate.split("\\|");
   if(parts.length==3&&parts[1].trim().matches("[0-9a-fA-F]{64}")&&parts[2].trim().matches("[0-9a-fA-F]{64}")){line=candidate.trim();break;}
  }
  if(line==null)return;
  String peerId=peer.optString("device_id");
  if(prefs.getString("trust_"+peerId,"").isEmpty())return;
  if(line.equals(rpcAnnounced.get(peerId)))return;
  final String sent=line;rpcAnnounced.put(peerId,line);
  new Thread(()->{try{
   JSONObject res=connect(peer,new JSONObject().put("op","rpc_info").put("rpc",sent));
   if(!res.optString("status").equals("ok"))rpcAnnounced.remove(peerId,sent);
   else log("RPC details securely advertised to "+peer.optString("name"));
  }catch(Exception e){rpcAnnounced.remove(peerId,sent);log("RPC registration: "+e.getMessage());}},"mesh-rpc-advertise").start();
 }
 void pair(JSONObject peer){new Thread(()->{try{
  JSONObject result=connect(peer,new JSONObject().put("op","pair"));
  if(result.optString("status").equals("ok")){
   String token=result.optString("token","");if(!token.isEmpty())prefs.edit().putString("trust_"+peer.optString("device_id"),token).apply();
   log("Paired with "+peer.optString("name"));advertiseRpc(peer);
  }else log("Pairing rejected or timed out by "+peer.optString("name"));
 }catch(Exception e){log("Pair failed: "+e.getMessage());}},"mesh-pair").start();}
 void send(JSONObject peer,android.net.Uri uri){new Thread(()->{try{
  byte[] bytes;
  try(InputStream input=service.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
   if(input==null)throw new IOException("Cannot open selected file");byte[] buffer=new byte[8192];int n;
   while((n=input.read(buffer))!=-1){if(out.size()+n>FILE_LIMIT)throw new IOException("File limit is 2 MiB");out.write(buffer,0,n);}
   bytes=out.toByteArray();
  }
  String filename=uri.getLastPathSegment();if(filename==null)filename="file.bin";
  JSONObject req=new JSONObject().put("op","send").put("filename",safe(filename))
   .put("data",Base64.encodeToString(bytes,Base64.NO_WRAP));
  JSONObject response=connect(peer,req);
  log(response.optString("status").equals("ok")?"File sent to "+peer.optString("name"):"Transfer rejected: "+response.toString());
 }catch(Exception e){log("Transfer failed: "+e.getMessage());}},"mesh-send").start();}
 @Override public void close(){closed=true;SSLServerSocket s=tls;if(s!=null)try{s.close();}catch(IOException ignored){}MulticastSocket m=udp;if(m!=null)m.close();pool.shutdownNow();if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();}
}