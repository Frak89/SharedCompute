package com.ampy.sharedcompute;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;
import java.io.*;

/** SharedCompute Mesh UI — each Android device may be coordinator or worker. */
public class MainActivity extends Activity {
 final int teal=0xff43ffda,bg=0xff041619,panel=0xff0c2d31;
 final Handler h=new Handler(Looper.getMainLooper());
 TextView status,ram,log,meshState,localState,localChat; EditText mem,threads,localPrompt; CheckBox charging;
 Spinner role;ListView devices;ArrayAdapter<String> adapter;
 ArrayList<String> visibleIds=new ArrayList<>();String selectedId=null,pendingId=null;
 File pendingExport=null;
 final Runnable refresh=new Runnable(){public void run(){
  ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();
  getSystemService(ActivityManager.class).getMemoryInfo(info);
  ram.setText("RAM "+info.availMem/1048576+" / "+info.totalMem/1048576+
   " MiB   CPU "+Runtime.getRuntime().availableProcessors()+" threads   THERMAL "+
   getSystemService(PowerManager.class).getCurrentThermalStatus());
  status.setText(NodeService.status);
  NodeService srv=NodeService.instance;
  MeshPeer mesh=srv==null?null:srv.mesh;
  meshState.setText(mesh==null?"MESH OFFLINE":"MESH ONLINE   UDP 50555 / TLS 50554   "+mesh.mode);
  if(mesh==null){
   if(!visibleIds.isEmpty()){visibleIds.clear();adapter.clear();}
  }else{
   ArrayList<JSONObject> found=new ArrayList<>(mesh.peers.values());
   found.sort(Comparator.comparing(p->p.optString("device_id","")));
   ArrayList<String> labels=new ArrayList<>(),ids=new ArrayList<>();
   for(JSONObject peer:found){
    if(SystemClock.elapsedRealtime()-peer.optLong("seen",0)>20000)continue;
    String id=peer.optString("device_id","");
    ids.add(id);
    boolean paired=!mesh.prefs.getString("trust_"+id,"").isEmpty();
    labels.add(peer.optString("name","device")+"  /  "+peer.optString("role","?")+
      "  /  "+peer.optString("ip","?")+"  /  "+(paired?"PAIRED":"NEW"));
   }
   if(!visibleIds.equals(ids)||adapter.getCount()!=labels.size()){
    visibleIds.clear();visibleIds.addAll(ids);adapter.clear();adapter.addAll(labels);
   }else{
    for(int j=0;j<labels.size();j++){
     if(!labels.get(j).equals(adapter.getItem(j))){adapter.clear();adapter.addAll(labels);break;}
    }
   }
   for(MeshPeer.Pending pending:mesh.pending.values()){
    if(pending.id.equals(pendingId))continue;
    pendingId=pending.id;
    new AlertDialog.Builder(MainActivity.this)
     .setTitle("SharedCompute  /  New pairing")
     .setMessage("Allow device \""+pending.name+"\" to join this Master?\n\nOnly approve a device you recognize. Discovery names can be spoofed.")
     .setPositiveButton("APPROVE",(d,w)->{mesh.approve(pending.id,true);pendingId=null;})
     .setNegativeButton("DENY",(d,w)->{mesh.approve(pending.id,false);pendingId=null;})
     .setOnCancelListener(d->{mesh.approve(pending.id,false);pendingId=null;}).show();
    break;
   }
  }
  LocalAI ai=srv==null?null:srv.localAI;
  if(localState!=null)localState.setText(ai==null?"LOCAL AI OFF":ai.modelName+"\n"+ai.state);
  if(localChat!=null&&ai!=null&&!ai.output.equals(localChat.getText().toString()))localChat.setText(ai.output);
  log.setText(NodeService.getLog());h.postDelayed(this,1000);
 }};
 int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 TextView label(String s,int size){
  TextView v=new TextView(this);v.setText(s);v.setTextColor(teal);v.setTextSize(size);
  v.setPadding(0,dp(8),0,dp(8));return v;
 }
 Button button(String title){
  Button b=new Button(this);b.setText(title);b.setTextColor(teal);
  GradientDrawable d=new GradientDrawable();d.setColor(panel);d.setCornerRadius(dp(8));d.setStroke(dp(1),teal);b.setBackground(d);return b;
 }
 EditText field(String text){
  EditText e=new EditText(this);e.setTextColor(teal);e.setInputType(2);e.setText(text);return e;
 }
 MeshPeer mesh(){NodeService n=NodeService.instance;return n==null?null:n.mesh;}
 JSONObject chosen(){MeshPeer n=mesh();return n==null||selectedId==null?null:n.peers.get(selectedId);}
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(bg);
  LinearLayout col=new LinearLayout(this);col.setOrientation(1);col.setPadding(dp(16),dp(16),dp(16),dp(16));scroll.addView(col);setContentView(scroll);
  scroll.setOnApplyWindowInsetsListener((v,insets)->{
   android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
   v.setPadding(i.left,i.top,i.right,i.bottom);return insets;
  });scroll.requestApplyInsets();
  col.addView(label("SHARED  //  COMPUTE  MESH",25));
  status=label("OFFLINE",18);col.addView(status);
  ram=label("",13);col.addView(ram);
  col.addView(label("ROLE  /  MASTER  ·  WORKER  ·  AUTO",13));
  role=new Spinner(this);
  String[] choices={"AUTO","MASTER","WORKER"};
  ArrayAdapter<String> roleItems=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices);
  role.setAdapter(roleItems);
  int ix=Arrays.asList(choices).indexOf(getPreferences(0).getString("role","AUTO"));
  role.setSelection(Math.max(0,ix));col.addView(role);
  col.addView(label("RPC MEMORY LIMIT MiB (worker only)",12));
  mem=field(getPreferences(0).getString("mem","1024"));col.addView(mem);
  col.addView(label("RPC CPU THREADS (worker only)",12));
  threads=field(getPreferences(0).getString("threads","4"));col.addView(threads);
  charging=new CheckBox(this);charging.setText("Only compute while charging");charging.setTextColor(teal);
  charging.setChecked(getPreferences(0).getBoolean("charging",true));col.addView(charging);
  Button start=button("START MESH / DISCOVER");col.addView(start);
  start.setOnClickListener(v->{
   try{
    int memory=Integer.parseInt(mem.getText().toString()),cpu=Integer.parseInt(threads.getText().toString());
    if(memory<128||cpu<1||cpu>Runtime.getRuntime().availableProcessors())throw new IllegalArgumentException("Invalid limits");
    String mode=role.getSelectedItem().toString();
    getPreferences(0).edit().putString("mem",""+memory).putString("threads",""+cpu)
      .putString("role",mode).putBoolean("charging",charging.isChecked()).apply();
    Intent intent=new Intent(this,NodeService.class);
    intent.putExtra("mem",memory).putExtra("threads",cpu).putExtra("charging",charging.isChecked()).putExtra("mesh_role",mode);
    startForegroundService(intent);
   }catch(Exception ex){Toast.makeText(this,"Cannot start: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
  });
  Button stop=button("STOP MESH");col.addView(stop);
  stop.setOnClickListener(v->stopService(new Intent(this,NodeService.class)));
  meshState=label("MESH OFFLINE",13);col.addView(meshState);
  col.addView(label("DEVICES ON YOUR WI-FI",15));
  devices=new ListView(this);adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_activated_1,new ArrayList<>());
  devices.setAdapter(adapter);devices.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
  col.addView(devices,new LinearLayout.LayoutParams(-1,dp(170)));
  devices.setOnItemClickListener((parent,view,index,id)->{if(index<visibleIds.size())selectedId=visibleIds.get(index);});
  Button pair=button("PAIR SELECTED  /  AUTO-JOIN ACTIVE");col.addView(pair);
  pair.setOnClickListener(v->{MeshPeer n=mesh();JSONObject p=chosen();if(n==null||p==null){Toast.makeText(this,"Select a device",Toast.LENGTH_SHORT).show();return;}n.pair(p);});
  Button send=button("SEND FILE TO MASTER");col.addView(send);
  send.setOnClickListener(v->{
   JSONObject p=chosen();
   if(p==null||!Arrays.asList("MASTER","AUTO").contains(p.optString("role",""))){
    Toast.makeText(this,"Select a master device",Toast.LENGTH_SHORT).show();return;
   }
   Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.setType("*/*");
   picker.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(picker,41);
  });
  Button inbox=button("RECEIVED FILES / SAVE TO PHONE");col.addView(inbox);
  inbox.setOnClickListener(v->{
   File folder=new File(getFilesDir(),"mesh-inbox");
   File[] entries=folder.listFiles(file->file.isFile());
   if(entries==null||entries.length==0){
    Toast.makeText(this,"No received files yet",Toast.LENGTH_SHORT).show();return;
   }
   Arrays.sort(entries,Comparator.comparingLong(File::lastModified).reversed());
   String[] names=new String[entries.length];
   for(int i=0;i<entries.length;i++)names[i]=entries[i].getName()+"  /  "+(entries[i].length()/1024)+" KiB";
   new AlertDialog.Builder(this).setTitle("Received files")
    .setItems(names,(dialog,index)->{
     pendingExport=entries[index];
     Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);
     save.addCategory(Intent.CATEGORY_OPENABLE);
     save.setType("application/octet-stream");
     save.putExtra(Intent.EXTRA_TITLE,pendingExport.getName());
     startActivityForResult(save,42);
    }).show();
  });
  Button rpc=button("COPY LEGACY RPC PAIRING (OPTIONAL)");col.addView(rpc);
  rpc.setOnClickListener(v->{
   NodeService srv=NodeService.instance;String line=srv==null?"":srv.getPairing();
   if(!line.contains(" | ")){Toast.makeText(this,"RPC offline",Toast.LENGTH_SHORT).show();return;}
   getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Private RPC pairing",line));
   Toast.makeText(this,"RPC details copied; keep the token private",Toast.LENGTH_LONG).show();
  });
  col.addView(label("LOCAL QWEN / TEXT CHAT / OFFLINE",16));
  localState=label("Select a local GGUF model; original file is kept.",12);col.addView(localState);
  Button importBtn=button("CHOOSE EXISTING GGUF (one-time import)");col.addView(importBtn);
  importBtn.setOnClickListener(v->{
   if(NodeService.instance==null){Toast.makeText(this,"Start Mesh first (MASTER recommended).",Toast.LENGTH_LONG).show();return;}
   Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
   pick.setType("*/*");pick.addCategory(Intent.CATEGORY_OPENABLE);
   startActivityForResult(pick,43);
  });
  Button startLocal=button("START CACHED LOCAL QWEN");col.addView(startLocal);
  startLocal.setOnClickListener(v->{
   NodeService n=NodeService.instance;
   if(n!=null&&n.localAI!=null)n.localAI.startCached();
   else Toast.makeText(this,"Start Mesh first",Toast.LENGTH_SHORT).show();
  });
  localChat=label("",13);localChat.setTextColor(0xffe5f5f2);
  localChat.setTextIsSelectable(true);col.addView(localChat);
  localPrompt=new EditText(this);localPrompt.setTextColor(0xffffffff);
  localPrompt.setHint("Ask Qwen a question...");localPrompt.setHintTextColor(0xff92bbb7);
  localPrompt.setSingleLine(false);localPrompt.setMinLines(2);col.addView(localPrompt);
  Button sendLocal=button("SEND TO LOCAL QWEN");col.addView(sendLocal);
  sendLocal.setOnClickListener(v->{
   NodeService n=NodeService.instance;
   if(n!=null&&n.localAI!=null){
    n.localAI.ask(localPrompt.getText().toString());
    localPrompt.setText("");
   }else Toast.makeText(this,"Start Mesh first",Toast.LENGTH_SHORT).show();
  });
  Button stopLocal=button("STOP LOCAL QWEN (release RAM)");col.addView(stopLocal);
  stopLocal.setOnClickListener(v->{
   NodeService n=NodeService.instance;
   if(n!=null&&n.localAI!=null)n.localAI.stop();
  });
  col.addView(label("ACTIVITY / ERRORS",14));log=label("",11);log.setTextIsSelectable(true);col.addView(log);
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
   requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1);
  }
 }
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);
  if(request==41&&result==RESULT_OK&&data!=null){
   MeshPeer n=mesh();JSONObject peer=chosen();Uri uri=data.getData();
   if(n!=null&&peer!=null&&uri!=null)n.send(peer,uri);
  }else if(request==43&&result==RESULT_OK&&data!=null&&data.getData()!=null){
   NodeService n=NodeService.instance;
   if(n!=null&&n.localAI!=null)n.localAI.importModel(data.getData());
  }else if(request==42){
   File source=pendingExport;pendingExport=null;
   if(result==RESULT_OK&&data!=null&&source!=null&&source.isFile()&&data.getData()!=null){
    try(InputStream input=new FileInputStream(source);
        OutputStream output=getContentResolver().openOutputStream(data.getData())){
     if(output==null)throw new IOException("Cannot open destination");
     byte[] bytes=new byte[8192];int count;
     while((count=input.read(bytes))!=-1)output.write(bytes,0,count);
     Toast.makeText(this,"File saved successfully",Toast.LENGTH_SHORT).show();
    }catch(Exception ex){Toast.makeText(this,"Save error: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
   }
  }
 }
 @Override protected void onStart(){super.onStart();h.post(refresh);}
 @Override protected void onStop(){h.removeCallbacks(refresh);super.onStop();}
}
