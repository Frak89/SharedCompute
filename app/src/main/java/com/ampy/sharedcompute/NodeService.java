package com.ampy.sharedcompute;
import android.app.*;
import android.content.*;
import android.os.*;
import java.lang.Process;
import java.io.*;

public class NodeService extends Service {
 public static volatile String status="OFFLINE";
 private static final StringBuilder LOG=new StringBuilder();
 private volatile long workerPid=-1; private volatile Process worker;private volatile boolean running;private PowerManager.WakeLock lock;
 private final Handler main=new Handler(Looper.getMainLooper());
 private int budget;private boolean chargingOnly;
 private volatile LanRelay relay;
 static volatile String pairing="OFFLINE";
 static synchronized void add(String s){LOG.append(s).append('\n');if(LOG.length()>18000)LOG.delete(0,LOG.length()-18000);}
 static synchronized String getLog(){return LOG.toString();}
 String getPairing(){LanRelay r=relay;return r==null?"OFFLINE":r.pairing;}
 static volatile NodeService instance;
 @Override public void onCreate(){super.onCreate();instance=this;}
 @Override public IBinder onBind(Intent i){return null;}
 private ActivityManager.MemoryInfo memory(){ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();getSystemService(ActivityManager.class).getMemoryInfo(m);return m;}
 private boolean charging(){Intent b=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));int s=b==null?0:b.getIntExtra(BatteryManager.EXTRA_STATUS,0);return s==BatteryManager.BATTERY_STATUS_CHARGING||s==BatteryManager.BATTERY_STATUS_FULL;}
 private long rssMiB(){Process p=worker;if(p==null||!p.isAlive()||workerPid<=0)return -1;try(BufferedReader r=new BufferedReader(new FileReader("/proc/"+workerPid+"/status"))){String line;while((line=r.readLine())!=null){if(line.startsWith("VmRSS:"))return Long.parseLong(line.trim().split("\\s+")[1])/1024;}}catch(Exception ignored){}return -1;}
 private String unsafe(){if(getSystemService(PowerManager.class).getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_SEVERE)return "Thermal protection";ActivityManager.MemoryInfo m=memory();if(m.lowMemory||m.availMem<Math.max(m.threshold,384L*1048576))return "Low system memory";if(chargingOnly&&!charging())return "Charger disconnected";long rss=rssMiB();if(rss>budget)return "Worker RSS exceeds configured budget ("+rss+" MiB)";return null;}
 private final Runnable guard=new Runnable(){public void run(){if(!running)return;String problem=unsafe();if(problem!=null){add("STOP: "+problem);stopSelf();return;}main.postDelayed(this,1000);}};
 @Override public int onStartCommand(Intent i,int flags,int id){
 if(running)return START_NOT_STICKY;
 NotificationManager n=getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel("node","SharedCompute node",NotificationManager.IMPORTANCE_LOW));
 PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
 startForeground(52,new Notification.Builder(this,"node").setContentTitle("SharedCompute active").setContentText("Local RPC worker • open app to stop").setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(open).setOngoing(true).build());
 if(i==null){stopSelf();return START_NOT_STICKY;}
 budget=i.getIntExtra("mem",1024);int threads=i.getIntExtra("threads",4);chargingOnly=i.getBooleanExtra("charging",true);
 ActivityManager.MemoryInfo m=memory();if(budget<128||threads<1||threads>Runtime.getRuntime().availableProcessors()||(long)budget*1048576>m.availMem-Math.max(m.threshold,512L*1048576)||unsafe()!=null){add("Start refused: check memory, charging, thermal status and threads");stopSelf();return START_NOT_STICKY;}
 File exe=new File(getApplicationInfo().nativeLibraryDir,"libsharedcompute_rpc.so");if(!exe.canExecute()){add("RPC executable missing: use the CI-built APK");stopSelf();return START_NOT_STICKY;}
 lock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SharedCompute:RPC");lock.acquire();running=true;status="STARTING // RPC";main.post(guard);
 LanRelay activeRelay=new LanRelay();relay=activeRelay;pairing="Preparing secure LAN...";
 new Thread(()->{try{activeRelay.run();}catch(Exception e){if(running&&relay==activeRelay){add("LAN TLS: "+e);main.post(()->{if(relay==activeRelay)stopSelf();});}}},"lan-tls").start();
 new Thread(()->{try{
 Process p=new ProcessBuilder("/system/bin/sh","-c","echo SC_PID=$$; exec \"$@\"" ,"sharedcompute",exe.getAbsolutePath(),"--host","127.0.0.1","--port","50052","--device","CPU","--threads",String.valueOf(threads)).directory(getFilesDir()).redirectErrorStream(true).start();worker=p;if(!running){p.destroyForcibly();return;}
 try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()))){String line;while((line=r.readLine())!=null){if(line.startsWith("SC_PID=")){try{workerPid=Long.parseLong(line.substring(7));}catch(NumberFormatException ignored){}continue;}add(line);if(line.contains("127.0.0.1:50052"))status="RPC LISTENING // 50052";}}
 add("RPC exit: "+p.waitFor());
 }catch(Exception e){add("RPC: "+e);}finally{main.post(()->{if(running)stopSelf();});}},"rpc-worker").start();
 return START_NOT_STICKY;
 }
 @Override public void onDestroy(){running=false;LanRelay r=relay;relay=null;if(r!=null)r.close();pairing="OFFLINE";workerPid=-1;main.removeCallbacks(guard);Process p=worker;if(p!=null)p.destroyForcibly();if(lock!=null&&lock.isHeld())lock.release();status="OFFLINE";if(instance==this)instance=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}