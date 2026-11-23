package com.ampy.sharedcompute;
import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import android.net.Uri;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.*;

public class MainActivity extends Activity {
 final int teal=0xff43ffda,bg=0xff041619,panel=0xff0c2d31;
 final Handler h=new Handler(Looper.getMainLooper());
 TextView ram,status,log,pairing; EditText mem,threads; CheckBox charging;
 Spinner role;ListView devices;ArrayAdapter<String> deviceAdapter;
 final ArrayList<String> deviceIds=new ArrayList<>();
 String selectedDevice=null,shownRequest=null;
 final Runnable refresh=new Runnable(){public void run(){
 ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();getSystemService(ActivityManager.class).getMemoryInfo(m);
 ram.setText("RAM AVAILABLE   "+m.availMem/1048576+" / "+m.totalMem/1048576+" MiB\nCPU THREADS     "+Runtime.getRuntime().availableProcessors()+"\nTHERMAL STATUS  "+getSystemService(PowerManager.class).getCurrentThermalStatus());
 status.setText(NodeService.status);NodeService svc=NodeService.instance;
 MeshPeer mesh=svc==null?null:svc.mesh;
 String p=mesh==null?"MESH OFFLINE":"MESH "+mesh.mode+" / UDP 50555 / TLS 50554";
 if(!p.contentEquals(pairing.getText()))pairing.setText(p);
 if(mesh!=null&&deviceAdapter!=null){
  ArrayList<String> ids=new ArrayList<>(),names=new ArrayList<>();
  for(JSONObject peer:mesh.peers.values()){
   if(SystemClock.elapsedRealtime()-peer.optLong("seen")>20000)continue;
   String id=peer.optString("device_id");ids.add(id);
   boolean trusted=!mesh.prefs.getString("trust_"+id,"").isEmpty();
   names.add(peer.optString("name")+" · "+peer.optString("role")+" · "+peer.optString("ip")+(trusted?" · PAIRED":" · NEW"));
  }
  if(!ids.equals(deviceIds)||names.size()!=deviceAdapter.getCount()||!adapterMatches(names)){
   deviceIds.clear();deviceIds.addAll(ids);deviceAdapter.clear();deviceAdapter.addAll(names);deviceAdapter.notifyDataSetChanged();
  }
  for(MeshPeer.Pending pending:mesh.pending.values()){
   if(pending.id.equals(shownRequest))continue;
   shownRequest=pending.id;
   new AlertDialog.Builder(MainActivity.this).setTitle("SharedCompute · Pairing request")
    .setMessage("Allow "+pending.name+" to pair with this device? Accept only a device you own.")
    .setPositiveButton("ALLOW",(d,w)->{mesh.approve(pending.id,true);shownRequest=null;})
    .setNegativeButton("DENY",(d,w)->{mesh.approve(pending.id,false);shownRequest=null;})
    .setOnCancelListener(d->{mesh.approve(pending.id,false);shownRequest=null;}).show();break;
  }
 }else if(deviceAdapter!=null&&deviceAdapter.getCount()>0){deviceIds.clear();deviceAdapter.clear();deviceAdapter.notifyDataSetChanged();}
 log.setText(NodeService.getLog());h.postDelayed(this,1000);
 }};
 boolean adapterMatches(ArrayList<String> names){for(int i=0;i<names.size();i++)if(!names.get(i).equals(deviceAdapter.getItem(i)))return false;return true;}
 MeshPeer mesh(){NodeService svc=NodeService.instance;return svc==null?null:svc.mesh;}
 JSONObject selected(){MeshPeer m=mesh();return m==null||selectedDevice==null?null:m.peers.get(selectedDevice);}
 int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
 TextView label(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextColor(teal);v.setTextSize(size);v.setTypeface(Typeface.MONOSPACE);v.setPadding(0,dp(8),0,dp(8));return v;}
 Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(teal);GradientDrawable d=new GradientDrawable();d.setColor(panel);d.setCornerRadius(dp(8));d.setStroke(dp(1),teal);b.setBackground(d);return b;}
 EditText field(String s){EditText e=new EditText(this);e.setTextColor(teal);e.setInputType(2);e.setText(s);return e;}
 @Override public void onCreate(Bundle b){super.onCreate(b);
 ScrollView page=new ScrollView(this);page.setFillViewport(true);page.setBackgroundColor(bg);
 LinearLayout col=new LinearLayout(this);col.setOrientation(1);col.setPadding(dp(16),dp(16),dp(16),dp(16));page.addView(col);setContentView(page);
 page.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(i.left,i.top,i.right,i.bottom);return insets;});page.requestApplyInsets();
 col.addView(label("SHARED // COMPUTE",26));col.addView(label("CYBER TIEL • ARM64 NODE",13));
 status=label("OFFLINE",19);col.addView(status);ram=label("",15);col.addView(ram);
 col.addView(label("DEVICE ROLE (MASTER / WORKER / AUTO)",13));
 role=new Spinner(this);
 String[] roles={"AUTO","MASTER","WORKER"};
 ArrayAdapter<String> roleAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,roles);
 role.setAdapter(roleAdapter);role.setSelection(Math.max(0,Arrays.asList(roles).indexOf(getPreferences(0).getString("role","AUTO"))));col.addView(role);
 col.addView(label("MEMORY BUDGET [MiB] / guard, not reservation",12));mem=field(getPreferences(0).getString("mem","1024"));col.addView(mem);
 col.addView(label("CPU THREADS",12));threads=field(getPreferences(0).getString("threads","4"));col.addView(threads);
 charging=new CheckBox(this);charging.setText("Only while charging");charging.setTextColor(teal);charging.setChecked(getPreferences(0).getBoolean("charging",true));col.addView(charging);
 Button start=button("ACTIVATE NODE");col.addView(start);start.setOnClickListener(v->{try{int m=Integer.parseInt(mem.getText().toString()),t=Integer.parseInt(threads.getText().toString());if(m<128||t<1||t>Runtime.getRuntime().availableProcessors())throw new IllegalArgumentException();String roleName=role.getSelectedItem().toString();getPreferences(0).edit().putString("mem",""+m).putString("threads",""+t).putBoolean("charging",charging.isChecked()).putString("role",roleName).apply();startForegroundService(new Intent(this,NodeService.class).putExtra("mem",m).putExtra("threads",t).putExtra("charging",charging.isChecked()).putExtra("mesh_role",roleName));}catch(Exception e){Toast.makeText(this,"Invalid budget or threads: "+e.getMessage(),Toast.LENGTH_LONG).show();}});
 Button stop=button("STOP NODE");col.addView(stop);stop.setOnClickListener(v->stopService(new Intent(this,NodeService.class)));
 col.addView(label("SECURE LAN // TLS 50552 • no ADB",13));
 pairing=label("OFFLINE",12);pairing.setTextIsSelectable(true);col.addView(pairing);
 Button copy=button("COPY PAIRING DETAILS");col.addView(copy);copy.setOnClickListener(v->{String x=pairing.getText().toString();if(x.contains(" | ")){getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("SharedCompute private pairing",x));Toast.makeText(this,"Copy a single IP line to your own PC only",Toast.LENGTH_LONG).show();}});
 col.addView(label("NEARBY SHARED COMPUTE DEVICES",14));
 devices=new ListView(this);deviceAdapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_activated_1,new ArrayList<>());
 devices.setAdapter(deviceAdapter);devices.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
 col.addView(devices,new LinearLayout.LayoutParams(-1,dp(160)));
 devices.setOnItemClickListener((parent,view,position,id)->{if(position<deviceIds.size())selectedDevice=deviceIds.get(position);});
 Button pair=button("PAIR SELECTED");col.addView(pair);pair.setOnClickListener(v->{MeshPeer m=mesh();JSONObject peer=selected();if(m!=null&&peer!=null)m.pair(peer);else Toast.makeText(this,"Select a device",Toast.LENGTH_SHORT).show();});
 Button send=button("SEND FILE TO MASTER");col.addView(send);send.setOnClickListener(v->{JSONObject peer=selected();if(peer==null){Toast.makeText(this,"Select a master",Toast.LENGTH_SHORT).show();return;}
  Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.setType("*/*");picker.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(picker,41);
 });
 col.addView(label("NODE LOG",14));log=label("",11);log.setTextIsSelectable(true);col.addView(log);
 if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1);
 }
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
  if(request==41&&result==RESULT_OK&&data!=null){MeshPeer m=mesh();JSONObject peer=selected();Uri uri=data.getData();if(m!=null&&peer!=null&&uri!=null)m.send(peer,uri);}
 }
 @Override protected void onStart(){super.onStart();h.post(refresh);}
 @Override protected void onStop(){h.removeCallbacks(refresh);super.onStop();}
}