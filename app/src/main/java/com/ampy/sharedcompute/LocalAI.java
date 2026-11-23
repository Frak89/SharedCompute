package com.ampy.sharedcompute;

import android.app.Service;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device text inference. Does not advertise the localhost inference API on the LAN.
 * The Storage Access Framework URI is imported once to app-private storage; never downloads a model.
 */
final class LocalAI implements AutoCloseable {
 static final int PORT=18082;
 final Service owner;
 final ExecutorService queue=Executors.newSingleThreadExecutor();
 volatile String state="LOCAL AI OFF";
 volatile String modelName="No model imported";
 volatile String output="";
 volatile boolean ready=false;
 private volatile Process server;
 private final JSONArray history=new JSONArray();
 private volatile boolean closed;
 LocalAI(Service context){owner=context;File existing=modelFile();
  if(existing.isFile()&&existing.length()>1048576)modelName="Qwen3-VL (cached, "+(existing.length()/1048576)+" MiB)";
 }
 File modelFile(){File dir=new File(owner.getFilesDir(),"local-model");if(!dir.exists())dir.mkdirs();return new File(dir,"qwen-local.gguf");}
 private void note(String text){NodeService.add("LOCAL AI: "+text);}
 private void set(String value){state=value;note(value);}
 void importModel(Uri uri){
  queue.execute(()->{
   if(closed)return;
   stopServer();
   File dest=modelFile(),tmp=new File(dest.getParentFile(),"qwen-local.gguf.part");
   try{
    ContentResolver resolver=owner.getContentResolver();
    long expected=-1;
    try(Cursor c=resolver.query(uri,new String[]{OpenableColumns.SIZE,OpenableColumns.DISPLAY_NAME},null,null,null)){
     if(c!=null&&c.moveToFirst()){
      int i=c.getColumnIndex(OpenableColumns.SIZE);
      if(i>=0&&!c.isNull(i))expected=c.getLong(i);
      int n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
      if(n>=0&&!c.isNull(n))modelName=c.getString(n);
     }
    }
    if(!modelName.toLowerCase(java.util.Locale.ROOT).endsWith(".gguf"))throw new IOException("Select a GGUF model file");
    if(expected>0&&expected>new StatFs(dest.getParent()).getAvailableBytes()-256L*1048576L)
      throw new IOException("Insufficient internal storage to import model. Keep at least 256 MiB free.");
    if(expected>1048576&&dest.isFile()&&dest.length()==expected){
     note("Reusing cached local GGUF; no copy or download");
    }else{
     set("IMPORTING GGUF / copying once to private storage");
     try(InputStream in=resolver.openInputStream(uri);OutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))){
      if(in==null)throw new IOException("Selected GGUF cannot be opened");
      byte[] buf=new byte[1048576];int n;long total=0;
      while((n=in.read(buf))!=-1){
       if(closed)throw new IOException("Import cancelled");
       out.write(buf,0,n);total+=n;
       if(total>8L*1024*1024*1024)throw new IOException("Model exceeds 8 GiB safety limit");
       if(total%(64L*1048576)<n)state="IMPORTING / "+(total/1048576)+" MiB";
      }
      if(total<1048576||(expected>0&&total!=expected))throw new IOException("Incomplete or invalid GGUF size");
     }
     if(dest.exists()&&!dest.delete())throw new IOException("Unable to replace previous model copy");
     if(!tmp.renameTo(dest))throw new IOException("Unable to commit imported GGUF");
     note("Imported "+(dest.length()/1048576)+" MiB. Original file unchanged.");
    }
    startServer(dest);
   }catch(Exception e){
    set("MODEL ERROR / "+e.getMessage());note("Import failed: "+e);
    if(tmp.exists())tmp.delete();
   }
  });
 }
 void startCached(){queue.execute(()->{
  File file=modelFile();
  if(!file.isFile()){set("No imported GGUF — choose file");return;}
  startServer(file);
 });}
 private void startServer(File gguf){
  if(closed)return;
  stopServer();
  File executable=new File(owner.getApplicationInfo().nativeLibraryDir,"libsharedcompute_llama.so");
  if(!executable.canExecute()){set("LOCAL AI ENGINE MISSING / APK must include llama-server");return;}
  try{
   int threads=Math.min(4,Math.max(1,Runtime.getRuntime().availableProcessors()-1));
   ProcessBuilder builder=new ProcessBuilder(
    executable.getAbsolutePath(),"-m",gguf.getAbsolutePath(),
    "--host","127.0.0.1","--port",Integer.toString(PORT),
    "--ctx-size","2048","--threads",Integer.toString(threads),
    "--batch-size","128","--ubatch-size","128",
    "--parallel","1","-ngl","0","--alias","local-qwen");
   builder.directory(owner.getFilesDir()).redirectErrorStream(true);
   set("LOADING QWEN / CPU "+threads+" threads");
   Process p=builder.start();server=p;
   Thread logs=new Thread(()->{
    try(BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
     String line;
     while((line=br.readLine())!=null){
      if(line.toLowerCase(java.util.Locale.ROOT).contains("error")||
         line.toLowerCase(java.util.Locale.ROOT).contains("failed")||
         line.contains("model loaded")||line.contains("listening"))note(line);
     }
    }catch(IOException e){if(!closed)note("Log stream: "+e.getMessage());}
   },"local-llama-log");
   logs.setDaemon(true);logs.start();
   for(int attempt=0;attempt<120;attempt++){
    if(closed||server!=p||!p.isAlive())break;
    try{
     HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+PORT+"/health").openConnection();
     c.setConnectTimeout(1000);c.setReadTimeout(1000);
     int code=c.getResponseCode();
     c.disconnect();
     if(code==200){ready=true;set("QWEN READY / text-only / port "+PORT);return;}
    }catch(Exception ignored){}
    Thread.sleep(1000);
   }
   set("LOAD FAILED / check ACTIVITY - ERRORS");
  }catch(Exception e){set("START FAILED / "+e.getMessage());}
 }
 void ask(String prompt){
  if(prompt==null||prompt.trim().isEmpty())return;
  queue.execute(()->{
   if(!ready){set("QWEN NOT READY / import or start model");return;}
   String question=prompt.trim();
   set("GENERATING RESPONSE");
   try{
    JSONArray messages=new JSONArray();
    messages.put(new JSONObject().put("role","system").put("content","You are a concise helpful assistant. Respond in the user's language."));
    for(int i=Math.max(0,history.length()-12);i<history.length();i++)messages.put(history.get(i));
    messages.put(new JSONObject().put("role","user").put("content",question));
    JSONObject body=new JSONObject().put("model","local-qwen").put("messages",messages)
     .put("max_tokens",128).put("stream",false)
     .put("chat_template_kwargs",new JSONObject().put("enable_thinking",false));
    HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+PORT+"/v1/chat/completions").openConnection();
    c.setRequestMethod("POST");c.setDoOutput(true);
    c.setConnectTimeout(3000);c.setReadTimeout(180000);
    c.setRequestProperty("Content-Type","application/json; charset=utf-8");
    byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
    try(OutputStream out=c.getOutputStream()){out.write(bytes);}
    int code=c.getResponseCode();
    try(InputStream in=code<400?c.getInputStream():c.getErrorStream()){
     ByteArrayOutputStream out=new ByteArrayOutputStream();
     byte[] b=new byte[8192];int n;
     while((n=in.read(b))!=-1)out.write(b,0,n);
     String raw=out.toString("UTF-8");
     if(code!=200)throw new IOException("API HTTP "+code+" / "+raw.substring(0,Math.min(300,raw.length())));
     JSONObject result=new JSONObject(raw);
     JSONObject message=result.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
     String reply=message.optString("content","").trim();
     if(reply.isEmpty())reply="[No text content emitted; check model/chat template]";
     history.put(new JSONObject().put("role","user").put("content",question));
     history.put(new JSONObject().put("role","assistant").put("content",reply));
     while(history.length()>12)history.remove(0);
     output="YOU: "+question+"\n\nQWEN: "+reply+"\n\n"+output;
     if(output.length()>20000)output=output.substring(0,20000);
     JSONObject usage=result.optJSONObject("usage");
     set("READY / "+(usage==null?"response complete":(usage.optInt("prompt_tokens")+" in / "+usage.optInt("completion_tokens")+" out")));
    }finally{c.disconnect();}
   }catch(Exception ex){set("CHAT ERROR / "+ex.getMessage());note("Chat error "+ex);}
  });
 }
 void stop(){queue.execute(()->{stopServer();set("LOCAL AI OFF");});}
 private void stopServer(){
  ready=false;
  Process p=server;server=null;
  if(p!=null&&p.isAlive()){p.destroy();try{p.waitFor(2,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
   if(p.isAlive())p.destroyForcibly();}
 }
 @Override public void close(){closed=true;stopServer();queue.shutdownNow();}
}
