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

/** Secure Mesh membership and small-file control plane. NOT a GGUF inference host. */
final class MeshPeer implements AutoCloseable {
 static final int DISCOVERY=50555, TLS_PORT=50554, FILE_LIMIT=2*1024*1024, FRAME_LIMIT=3*1024*1024;
 static final String GROUP="239.255.50.55", PROTOCOL="sharedcompute-mesh-v1", ALIAS="sharedcompute-mesh-v1";
 final Service service; final String mode,deviceId,name; final SharedPreferences prefs;
 final ConcurrentHashMap<String,JSONObject> peers=new ConcurrentHashMap<>();
 final ConcurrentHashMap<String,Pending> pending=new ConcurrentHashMap<>();
 final ConcurrentHashMap<String,Long> joinAttempts=new ConcurrentHashMap<>();
 final ExecutorService pool=Executors.newFixedThreadPool(8);
 volatile boolean closed;volatile SSLServerSocket tls;volatile MulticastSocket udp;volatile String fingerprint="";
 WifiManager.MulticastLock multicastLock;
 static class Pending {
  String id,device,name; CountDownLatch latch=new CountDownLatch(1);
  volatile boolean allow;volatile String token="";
 }
 MeshPeer(Service service,String role){
  this.service=service;mode=role;prefs=service.getSharedPreferences("sharedcompute-mesh",0);
  String id=prefs.getString("device_id",null);
  if(id==null){id=UUID.randomUUID().toString();prefs.edit().putString("device_id",id).apply();}
  deviceId=id;name=(Build.MANUFACTURER+" "+Build.MODEL).trim();
 }
 void log(String msg){NodeService.add("MESH: "+msg);}
 void start(){new Thread(this::serve,"mesh-tls").start();new Thread(this::discover,"mesh-discovery").start();}
 private static String hex(byte[] b){StringBuilder out=new StringBuilder();for(byte c:b)out.append(String.format(Locale.ROOT,"%02x",c&255));return out.toString();}
 private static byte[] sha(byte[] b)throws Exception{return MessageDigest.getInstance("SHA-256").digest(b);}
 private static byte[] random(int n){byte[] b=new byte[n];new SecureRandom().nextBytes(b);return b;}
 private SSLContext serverContext()throws Exception{
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
  if(!ks.containsAlias(ALIAS)){
   KeyPairGenerator gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
   gen.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_DECRYPT)
    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1).build());
   gen.generateKeyPair();
  }
  Certificate cert=ks.getCertificate(ALIAS);
  if(cert==null)throw new GeneralSecurityException("AndroidKeyStore certificate unavailable");
  fingerprint=hex(sha(cert.getEncoded()));
  KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(ks,null);
  SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(km.getKeyManagers(),null,new SecureRandom());return ctx;
 }
 private void serve(){
  try{
   SSLContext ctx=serverContext();
   SSLServerSocket server=(SSLServerSocket)ctx.getServerSocketFactory().createServerSocket();tls=server;
   server.setReuseAddress(true);server.bind(new InetSocketAddress("0.0.0.0",TLS_PORT));
   server.setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.3"});
   log("TLS ready : "+TLS_PORT+" role="+mode);
   while(!closed){
    SSLSocket s=(SSLSocket)server.accept();
    try{pool.execute(()->serveOne(s));}catch(RejectedExecutionException ex){s.close();}
   }
  }catch(Exception ex){if(!closed)log("TLS listener: "+ex.getClass().getSimpleName()+" "+ex.getMessage());}
 }
 static JSONObject readFrame(InputStream input)throws Exception{
  DataInputStream in=new DataInputStream(input);int len=in.readInt();
  if(len<1||len>FRAME_LIMIT)throw new IOException("Frame size rejected");
  byte[] bytes=new byte[len];in.readFully(bytes);
  return new JSONObject(new String(bytes,StandardCharsets.UTF_8));
 }
 static void writeFrame(OutputStream output,JSONObject obj)throws Exception{
  byte[] bytes=obj.toString().getBytes(StandardCharsets.UTF_8);
  if(bytes.length>FRAME_LIMIT)throw new IOException("Frame too large");
  DataOutputStream out=new DataOutputStream(output);out.writeInt(bytes.length);out.write(bytes);out.flush();
 }
 static String safe(String raw){
  if(raw==null)return "file.bin";
  String v=raw.replaceAll("[^\\p{L}\\p{N}._ -]","_").replaceAll("^\\.+","");
  return v.isEmpty()?"file.bin":v.substring(0,Math.min(90,v.length()));
 }
 private void serveOne(SSLSocket socket){
  try(SSLSocket c=socket){
   c.setSoTimeout(75000);c.startHandshake();JSONObject req=readFrame(c.getInputStream());
   String id=req.optString("device_id","");
   if(id.length()<1||id.length()>100)throw new IOException("Invalid device identity");
   String stored=prefs.getString("trust_"+id,""),supplied=req.optString("token","");
   boolean trusted=!stored.isEmpty()&&MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII));
   String op=req.optString("op","");
   if(op.equals("pair")){
    if(trusted){writeFrame(c.getOutputStream(),new JSONObject().put("status","ok").put("trusted",true));return;}
    if(mode.equals("WORKER")||!stored.isEmpty()){writeFrame(c.getOutputStream(),new JSONObject().put("status","denied"));return;}
    Pending reqPair=new Pending();reqPair.id=UUID.randomUUID().toString();reqPair.device=id;
    reqPair.name=safe(req.optString("name","Device"));pending.put(reqPair.id,reqPair);
    log("Approval required: "+reqPair.name);
    reqPair.latch.await(60,TimeUnit.SECONDS);pending.remove(reqPair.id);
    JSONObject result=new JSONObject().put("status",reqPair.allow?"ok":"denied");
    if(reqPair.allow)result.put("token",reqPair.token);
    writeFrame(c.getOutputStream(),result);
   }else if(op.equals("send")){
    if(!trusted||mode.equals("WORKER")){writeFrame(c.getOutputStream(),new JSONObject().put("status","denied"));return;}
    String data=req.optString("data","");
    if(data.length()>2800000)throw new IOException("File too large");
    byte[] bytes=Base64.decode(data,Base64.DEFAULT);
    if(bytes.length>FILE_LIMIT)throw new IOException("File exceeds 2 MiB");
    File inbox=new File(service.getFilesDir(),"mesh-inbox");
    if(!inbox.exists()&&!inbox.mkdirs())throw new IOException("Cannot create inbox");
    String filename=UUID.randomUUID().toString().substring(0,8)+"-"+safe(req.optString("filename","file.bin"));
    try(FileOutputStream out=new FileOutputStream(new File(inbox,filename))){out.write(bytes);}
    log("Received "+filename+" ("+bytes.length+" bytes)");
    writeFrame(c.getOutputStream(),new JSONObject().put("status","ok"));
   }else writeFrame(c.getOutputStream(),new JSONObject().put("status","error"));
  }catch(Exception ex){if(!closed)log("Incoming TLS: "+ex.getClass().getSimpleName()+" "+ex.getMessage());}
 }
 void approve(String id,boolean accept){
  Pending p=pending.get(id);if(p==null)return;
  if(accept){
   p.token=hex(random(32));p.allow=true;
   prefs.edit().putString("trust_"+p.device,p.token).apply();
  }
  p.latch.countDown();
 }
 JSONObject offer()throws Exception{
  return new JSONObject().put("protocol",PROTOCOL).put("type","offer").put("name",name)
   .put("device_id",deviceId).put("role",mode).put("port",TLS_PORT)
   .put("pin",fingerprint).put("platform","Android");
 }
 private void discover(){
  try{
   WifiManager wifi=(WifiManager)service.getApplicationContext().getSystemService(Service.WIFI_SERVICE);
   if(wifi!=null){multicastLock=wifi.createMulticastLock("SharedComputeMesh");multicastLock.setReferenceCounted(false);multicastLock.acquire();}
   MulticastSocket sock=new MulticastSocket(null);udp=sock;sock.setReuseAddress(true);
   sock.bind(new InetSocketAddress("0.0.0.0",DISCOVERY));sock.joinGroup(InetAddress.getByName(GROUP));
   sock.setBroadcast(true);sock.setSoTimeout(1000);
   byte[] buffer=new byte[2048];long last=0;
   while(!closed){
    if(!fingerprint.isEmpty()&&SystemClock.elapsedRealtime()-last>=4000){
     last=SystemClock.elapsedRealtime();
     byte[] out=offer().put("type","discover").toString().getBytes(StandardCharsets.UTF_8);
     for(String dest:new String[]{GROUP,"255.255.255.255"}){
      try{sock.send(new DatagramPacket(out,out.length,InetAddress.getByName(dest),DISCOVERY));}catch(Exception ignored){}
     }
    }
    DatagramPacket packet=new DatagramPacket(buffer,buffer.length);
    try{sock.receive(packet);}catch(SocketTimeoutException timeout){continue;}
    try{
     JSONObject other=new JSONObject(new String(packet.getData(),packet.getOffset(),packet.getLength(),StandardCharsets.UTF_8));
     if(!PROTOCOL.equals(other.optString("protocol"))||deviceId.equals(other.optString("device_id")))continue;
     if(!Arrays.asList("MASTER","WORKER","AUTO").contains(other.optString("role")))continue;
     if(!Arrays.asList("offer","discover").contains(other.optString("type")))continue;
     if(!other.optString("pin").matches("[a-fA-F0-9]{64}"))continue;
     other.put("ip",packet.getAddress().getHostAddress()).put("seen",SystemClock.elapsedRealtime());
     peers.put(other.getString("device_id"),other);scheduleAutoJoin(other);
     if(other.optString("type").equals("discover")&&!fingerprint.isEmpty()){
      byte[] reply=offer().toString().getBytes(StandardCharsets.UTF_8);
      sock.send(new DatagramPacket(reply,reply.length,packet.getAddress(),packet.getPort()));
     }
    }catch(Exception ex){log("Discovery parse: "+ex.getClass().getSimpleName());}
   }
  }catch(Exception ex){if(!closed)log("Discovery unavailable: "+ex.getClass().getSimpleName()+" "+ex.getMessage());}
  finally{if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();}
 }
 private boolean joinTarget(JSONObject peer){
  if(mode.equals("MASTER")||peer.optString("role").equals("WORKER"))return false;
  if(mode.equals("WORKER"))return true;
  return peer.optString("role").equals("MASTER")||
   (peer.optString("role").equals("AUTO")&&deviceId.compareTo(peer.optString("device_id",""))>0);
 }
 private void scheduleAutoJoin(JSONObject peer){
  if(!joinTarget(peer))return;
  String id=peer.optString("device_id","");
  if(id.isEmpty()||!prefs.getString("trust_"+id,"").isEmpty())return;
  long now=SystemClock.elapsedRealtime();
  Long previous=joinAttempts.putIfAbsent(id,now);
  if(previous!=null){
   if(now-previous<90000)return;
   if(!joinAttempts.replace(id,previous,now))return;
  }
  log("Found master "+peer.optString("name","device")+"; requesting approval");
  pair(peer);
 }
 private static byte[] unhex(String s){
  if(!s.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Invalid fingerprint");
  byte[] out=new byte[32];for(int i=0;i<32;i++)out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
  return out;
 }
 JSONObject connect(JSONObject peer,JSONObject request)throws Exception{
  String id=peer.getString("device_id"),pin=peer.getString("pin"),known=prefs.getString("pin_"+id,"");
  if(!known.isEmpty()&&!known.equalsIgnoreCase(pin))throw new SecurityException("Previously trusted certificate changed");
  TrustManager[] trust={new X509TrustManager(){
   public java.security.cert.X509Certificate[] getAcceptedIssuers(){return new java.security.cert.X509Certificate[0];}
   public void checkClientTrusted(java.security.cert.X509Certificate[] chain,String auth){}
   public void checkServerTrusted(java.security.cert.X509Certificate[] chain,String auth){}
  }};
  SSLContext context=SSLContext.getInstance("TLS");context.init(null,trust,new SecureRandom());
  try(SSLSocket c=(SSLSocket)context.getSocketFactory().createSocket()){
   c.connect(new InetSocketAddress(peer.getString("ip"),peer.optInt("port",TLS_PORT)),8000);
   c.setSoTimeout(75000);c.startHandshake();
   Certificate cert=c.getSession().getPeerCertificates()[0];
   if(!MessageDigest.isEqual(sha(cert.getEncoded()),unhex(pin)))throw new SecurityException("TLS pin mismatch");
   request.put("device_id",deviceId).put("name",name).put("token",prefs.getString("trust_"+id,""));
   writeFrame(c.getOutputStream(),request);
   JSONObject result=readFrame(c.getInputStream());
   if(result.optString("status").equals("ok"))prefs.edit().putString("pin_"+id,pin).apply();
   return result;
  }
 }
 void pair(JSONObject peer){
  new Thread(()->{try{
   JSONObject result=connect(peer,new JSONObject().put("op","pair"));
   if(result.optString("status").equals("ok")){
    String token=result.optString("token","");
    if(!token.isEmpty())prefs.edit().putString("trust_"+peer.optString("device_id"),token).apply();
    log("Paired: "+peer.optString("name","device"));
   }else log("Pairing rejected or timed out: "+peer.optString("name","device"));
  }catch(Exception ex){log("Pairing error: "+ex.getMessage());}},"mesh-pair").start();
 }
 void send(JSONObject peer,android.net.Uri uri){
  new Thread(()->{try{
   if(!Arrays.asList("MASTER","AUTO").contains(peer.optString("role")))throw new SecurityException("Destination is not a master");
   ByteArrayOutputStream out=new ByteArrayOutputStream();
   try(InputStream in=service.getContentResolver().openInputStream(uri)){
    if(in==null)throw new IOException("Cannot read file");
    byte[] buffer=new byte[8192];int count;
    while((count=in.read(buffer))!=-1){if(out.size()+count>FILE_LIMIT)throw new IOException("Maximum 2 MiB");out.write(buffer,0,count);}
   }
   String file=uri.getLastPathSegment();if(file==null)file="file.bin";
   JSONObject req=new JSONObject().put("op","send").put("filename",safe(file))
    .put("data",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));
   JSONObject result=connect(peer,req);
   log(result.optString("status").equals("ok")?"File sent to "+peer.optString("name","device"):"Transfer rejected");
  }catch(Exception ex){log("Transfer error: "+ex.getMessage());}},"mesh-send").start();
 }
 public void close(){
  closed=true;SSLServerSocket server=tls;if(server!=null)try{server.close();}catch(IOException ignored){}
  MulticastSocket sock=udp;if(sock!=null)sock.close();
  pool.shutdownNow();if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();
 }
}
