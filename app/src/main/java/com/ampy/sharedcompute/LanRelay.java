package com.ampy.sharedcompute;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Exposes only a pinned TLS + random token tunnel, never the raw RPC port. */
final class LanRelay implements AutoCloseable {
 static final int PORT=50552;
 static final String ALIAS="sharedcompute-lan-v1";
 private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
 private final ExecutorService pool=Executors.newFixedThreadPool(8);
 private final Semaphore slots=new Semaphore(8);
 private volatile boolean closed;
 private volatile SSLServerSocket server;
 volatile String pairing="Preparing secure LAN...";
 private final String token;
 LanRelay(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);token=hex(b);}
 private static String hex(byte[] data){StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
 private static boolean lan(InetAddress a){return a instanceof Inet4Address&&(a.isSiteLocalAddress()||a.isLinkLocalAddress());}
 private static List<String> addresses() throws SocketException {
  List<String> out=new ArrayList<>();
  for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces())){
   if(!n.isUp()||n.isLoopback())continue;
   for(InetAddress a:Collections.list(n.getInetAddresses()))if(lan(a))out.add(a.getHostAddress());
  }return out;
 }
 void run() throws Exception {
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
  if(!ks.containsAlias(ALIAS)){
   KeyPairGenerator gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
   gen.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_DECRYPT)
    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1).build());gen.generateKeyPair();
  }
  Certificate cert=ks.getCertificate(ALIAS);
  String pin=hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
  KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(ks,null);
  SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(km.getKeyManagers(),null,new SecureRandom());
  SSLServerSocket ss=(SSLServerSocket)ctx.getServerSocketFactory().createServerSocket();
  server=ss;
  if(closed){ss.close();return;}
  ss.setReuseAddress(true);ss.bind(new InetSocketAddress("0.0.0.0",PORT));
  ss.setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.3"});
  List<String> ips=addresses();
  StringBuilder lines=new StringBuilder("Copy ONE line to Windows LAN Host:\n");
  for(String ip:ips)lines.append(ip).append(" | ").append(pin).append(" | ").append(token).append('\n');
  if(ips.isEmpty())lines.append("No private IPv4 address found. Connect to Wi-Fi and restart node.");
  pairing=lines.toString().trim();
  NodeService.add("TLS LAN ready on port "+PORT+"; raw RPC stays on localhost.");
  while(!closed){
   SSLSocket client=(SSLSocket)ss.accept();
   if(!lan(client.getInetAddress())||!slots.tryAcquire()){client.close();continue;}
   sockets.add(client);
   try{pool.execute(()->{try{serve(client);}finally{slots.release();}});}
   catch(RejectedExecutionException e){slots.release();sockets.remove(client);client.close();}
  }
 }
 private void serve(SSLSocket c){
  Socket rpc=null;
  try{
   c.setSoTimeout(10000);c.startHandshake();
   InputStream in=c.getInputStream();OutputStream out=c.getOutputStream();
   byte[] supplied=new byte[64];
   int off=0;while(off<supplied.length){int n=in.read(supplied,off,supplied.length-off);if(n<0)return;off+=n;}
   if(!MessageDigest.isEqual(supplied,token.getBytes(StandardCharsets.US_ASCII)))return;
   rpc=new Socket();sockets.add(rpc);rpc.connect(new InetSocketAddress("127.0.0.1",50052),3000);
   out.write(1);out.flush();c.setSoTimeout(0);
   final Socket target=rpc;
   Thread back=new Thread(()->{try{copy(target.getInputStream(),out);}catch(IOException ignored){}finally{quiet(c);quiet(target);}},"lan-rpc-back");back.setDaemon(true);back.start();
   try{copy(in,rpc.getOutputStream());}finally{quiet(c);quiet(rpc);}
  }catch(Exception ignored){}finally{sockets.remove(c);quiet(c);if(rpc!=null){sockets.remove(rpc);quiet(rpc);}}
 }
 private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){out.write(b,0,n);out.flush();}}
 private static void quiet(Socket s){try{s.close();}catch(IOException ignored){}}
 @Override public void close(){closed=true;pairing="OFFLINE";SSLServerSocket s=server;if(s!=null)try{s.close();}catch(IOException ignored){}for(Socket x:sockets)quiet(x);pool.shutdownNow();}
}
