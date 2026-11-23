"""SharedCompute LAN host: pinned TLS relay and local llama-server launcher."""
import hmac
import hashlib
import ipaddress
import os
import queue
import re
import select
import socket
import ssl
import subprocess
import sys
import threading
from pathlib import Path

def parse_pairings(text):
    rows=[]
    for line in text.splitlines():
        if "|" not in line: continue
        parts=[s.strip() for s in line.split("|")]
        if len(parts)!=3: raise ValueError("Expected IP | certificate fingerprint | token")
        ip,pin,token=parts
        addr=ipaddress.IPv4Address(ip)
        if not addr.is_private or addr.is_loopback or addr.is_unspecified or addr.is_multicast: raise ValueError("Use a private IPv4 address from Android.")
        if not re.fullmatch("[0-9a-fA-F]{64}",pin) or not re.fullmatch("[0-9a-fA-F]{64}",token): raise ValueError("Fingerprint and token must be 64 hex characters each.")
        row=(ip,pin.lower(),token.lower())
        if row not in rows: rows.append(row)
    if not rows or len(rows)>8: raise ValueError("Paste pairing details for 1–8 nodes.")
    if len({r[0] for r in rows})!=len(rows): raise ValueError("Use only one line per Android device.")
    return rows

def connect_node(row, port=50552):
    ip,pin,token=row
    try:
        raw=socket.create_connection((ip,port),timeout=8)
    except OSError as exc:
        raise ConnectionError("Android node "+ip+" port "+str(port)+" unreachable: "+str(exc)) from exc
    tls=None
    phase="TLS handshake"
    try:
        ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.minimum_version=ssl.TLSVersion.TLSv1_2
        ctx.check_hostname=False
        ctx.verify_mode=ssl.CERT_NONE
        tls=ctx.wrap_socket(raw,server_hostname=ip)
        actual=hashlib.sha256(tls.getpeercert(binary_form=True)).hexdigest()
        if not hmac.compare_digest(actual,pin): raise ssl.SSLError("Android certificate does not match pasted fingerprint.")
        phase="pairing authentication"
        tls.sendall(token.encode("ascii"))
        try:
            reply=tls.recv(1)
        except (ssl.SSLError, OSError) as exc:
            raise ConnectionError("Android closed the tunnel after pairing. Check Android NODE LOG for RPC worker errors: "+str(exc)) from exc
        if reply!=b"\x01":
            raise ConnectionError("Android did not acknowledge pairing or local RPC is not ready. Check Android NODE LOG; reactivate and recopy pairing details.")
        tls.settimeout(None)
        return tls
    except ssl.SSLError as exc:
        (tls or raw).close()
        raise ConnectionError("Android "+phase+" failed: "+str(exc)) from exc
    except BaseException:
        (tls or raw).close()
        raise

def pump(a,b,stop):
    """One thread owns TLS I/O; bounded queues avoid concurrent SSLSocket calls."""
    a.setblocking(False);b.setblocking(False)
    ab=bytearray();ba=bytearray();limit=2*1024*1024
    retry=(BlockingIOError,ssl.SSLWantReadError,ssl.SSLWantWriteError)
    while not stop.is_set():
        progress=False
        for src,buf in ((a,ab),(b,ba)):
            if len(buf)>=limit: continue
            try:
                data=src.recv(min(65536,limit-len(buf)))
                if not data: return
                buf.extend(data);progress=True
            except retry: pass
        for dst,buf in ((b,ab),(a,ba)):
            if not buf: continue
            try:
                n=dst.send(buf)
                if n: del buf[:n];progress=True
            except retry: pass
        if not progress:
            select.select([a,b],[],[],0.02)

class Relay:
    def __init__(self,row,emit):
        self.row=row;self.emit=emit;self.stop=threading.Event();self.sockets=set();self.lock=threading.Lock()
        self.listener=socket.socket()
        self.listener.bind(("127.0.0.1",0));self.listener.listen(8);self.listener.settimeout(.5)
        self.port=self.listener.getsockname()[1]
        self.slots=threading.BoundedSemaphore(8)
        threading.Thread(target=self.accept,daemon=True).start()
    def accept(self):
        while not self.stop.is_set():
            try: a,_=self.listener.accept()
            except socket.timeout: continue
            except OSError: break
            if not self.slots.acquire(False):a.close();continue
            with self.lock:self.sockets.add(a)
            threading.Thread(target=self.worker,args=(a,),daemon=True).start()
    def worker(self,a):
        b=None
        try:
            b=connect_node(self.row)
            with self.lock:
                if self.stop.is_set():return
                self.sockets.add(b)
            self.emit("Authenticated TLS node "+self.row[0])
            pump(a,b,self.stop)
        except Exception as exc:
            if not self.stop.is_set(): self.emit("Node "+self.row[0]+": "+str(exc))
        finally:
            with self.lock:
                for s in (a,b):
                    if s is not None:
                        self.sockets.discard(s);s.close()
            self.slots.release()
    def close(self):
        self.stop.set();self.listener.close()
        with self.lock:
            for s in list(self.sockets):
                try:s.shutdown(socket.SHUT_RDWR)
                except OSError:pass
                s.close()

def server_args(server,model,ports,layers,context,apiport):
    return [str(server),"-m",str(model),"--rpc",",".join("127.0.0.1:"+str(p) for p in ports),"-ngl",str(layers),"--ctx-size",str(context),"--parallel","1","--host","127.0.0.1","--port",str(apiport),"--alias","sharedcompute"]

def main():
    import tkinter as tk
    from tkinter import ttk,filedialog,messagebox
    root=tk.Tk();root.title("SharedCompute // Secure LAN Host");root.geometry("820x760")
    bg="#041619";fg="#43ffda";panel="#0b3035"
    root.configure(bg=bg);style=ttk.Style(root);style.theme_use("clam")
    style.configure(".",background=bg,foreground=fg,fieldbackground=panel)
    style.configure("TButton",background=panel,foreground=fg,padding=6)
    top=ttk.Frame(root,padding=12);top.pack(fill="both",expand=True)
    base=Path(sys.executable if getattr(sys,"frozen",False) else __file__).resolve().parent
    default=next((p for p in (base/"llama-server.exe",base.parent/"llama-server.exe",Path(r"D:\CYBER_AI\SHAREDCOMPUTE\llama-server.exe"),Path(r"E:\CYBER_AI\LLAMA_CPP\llama-server.exe")) if p.is_file()),base/"llama-server.exe")
    server=tk.StringVar(value=str(default));model=tk.StringVar()
    def field(title,var,patterns=None):
        ttk.Label(top,text=title).pack(anchor="w")
        row=ttk.Frame(top);row.pack(fill="x")
        ttk.Entry(row,textvariable=var).pack(side="left",fill="x",expand=True)
        if patterns:
            def browse():
                p=filedialog.askopenfilename(initialdir=r"E:\CYBER_AI",filetypes=patterns)
                if p:var.set(p)
            ttk.Button(row,text="Browse",command=browse).pack(side="left")
    ttk.Label(top,text="SHAREDCOMPUTE // AUTHENTICATED WI-FI RPC").pack(anchor="w")
    field("llama-server.exe (same revision as Android APK)",server,[("Executable","*.exe")])
    field("Original GGUF — no copy",model,[("GGUF","*.gguf")])
    ttk.Label(top,text="Mesh finds and pairs nodes automatically; RPC model loading is separate.").pack(anchor="w")
    ttk.Label(top,text="Android: activate node and paste ONE private pairing line per device below. Keep tokens secret.").pack(anchor="w")
    pairs=tk.Text(top,height=5,bg=panel,fg=fg,insertbackground=fg,wrap="word");pairs.pack(fill="x")
    row=ttk.Frame(top);row.pack(fill="x")
    layers=tk.StringVar(value="4");context=tk.StringVar(value="1024");port=tk.StringVar(value="8081")
    for title,var in (("RPC layers",layers),("Context",context),("API port",port)):
        ttk.Label(row,text=title).pack(side="left");ttk.Entry(row,textvariable=var,width=7).pack(side="left")
    log=tk.Text(top,bg=panel,fg=fg,insertbackground=fg,wrap="word");log.pack(fill="both",expand=True)
    q=queue.Queue();cancel=threading.Event();state={"process":None,"busy":False}
    def emit(s):q.put(("log",s))
    def start():
        if state["busy"]:return
        try:
            rows=parse_pairings(pairs.get("1.0","end"))
            exe=Path(server.get()).resolve();gguf=Path(model.get()).resolve()
            if not exe.is_file() or exe.name.lower()!="llama-server.exe":raise ValueError("Select llama-server.exe")
            if not gguf.is_file() or gguf.suffix.lower()!=".gguf":raise ValueError("Select original GGUF")
            n,c,p=map(int,(layers.get(),context.get(),port.get()))
            if not (1<=n<=999 and 512<=c<=32768 and 1024<=p<=65535):raise ValueError("Invalid layers, context or port")
        except Exception as exc:messagebox.showerror("Configuration",str(exc));return
        state["busy"]=True;cancel.clear();startbtn.configure(state="disabled");stopbtn.configure(state="normal")
        def run():
            relays=[];proc=None
            try:
                # Authenticate and verify the Android RPC worker BEFORE loading any GGUF.
                for r in rows:
                    if cancel.is_set():return
                    emit("PREFLIGHT: testing TLS, certificate pin, pairing and RPC on "+r[0])
                    with connect_node(r):
                        pass
                    emit("PREFLIGHT OK: "+r[0]+" authenticated; Android RPC ready")
                for r in rows:
                    if cancel.is_set():return
                    relay=Relay(r,emit);relays.append(relay)
                if cancel.is_set():return
                args=server_args(exe,gguf,[r.port for r in relays],n,c,p)
                emit("Launching local API http://127.0.0.1:%d/v1"%p)
                proc=subprocess.Popen(args,cwd=exe.parent,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding="utf-8",errors="replace",creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
                state["process"]=proc
                if cancel.is_set():proc.terminate()
                for line in proc.stdout:emit(line.rstrip())
                emit("llama-server exit: "+str(proc.wait()))
            except Exception as exc:emit("ERROR: "+str(exc))
            finally:
                if proc is not None and proc.poll() is None:proc.terminate()
                for r in relays:r.close()
                state["process"]=None;q.put(("done",""))
        threading.Thread(target=run,daemon=True).start()
    def stop():
        cancel.set();p=state["process"]
        if p is not None and p.poll() is None:p.terminate()
    def poll():
        for _ in range(200):
            try:kind,msg=q.get_nowait()
            except queue.Empty:break
            if kind=="done":
                state["busy"]=False;startbtn.configure(state="normal");stopbtn.configure(state="disabled")
            else:log.insert("end",msg+"\n");log.see("end")
        root.after(80,poll)
    row=ttk.Frame(top);row.pack(fill="x")
    def open_mesh():
        mesh_exe=base/"SharedCompute-Mesh.exe"
        mesh_src=base/"mesh.py"
        if mesh_exe.is_file():
            subprocess.Popen([str(mesh_exe)],cwd=str(base),creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
        elif mesh_src.is_file():
            subprocess.Popen([sys.executable,str(mesh_src)],cwd=str(base))
        else:
            messagebox.showwarning("SharedCompute Mesh","Mesh application is missing; use the matching Windows LAN Host artifact.")
    ttk.Button(row,text="OPEN MESH NETWORK",command=open_mesh).pack(side="left")
    startbtn=ttk.Button(row,text="START DISTRIBUTED MODEL",command=start);startbtn.pack(side="left")
    stopbtn=ttk.Button(row,text="STOP",command=stop,state="disabled");stopbtn.pack(side="left")
    def close():
        stop()
        if state["busy"]:root.after(100,close)
        else:root.destroy()
    root.protocol("WM_DELETE_WINDOW",close);root.after(80,poll);root.mainloop()
if __name__=="__main__":main()
