"""SharedCompute Mesh: symmetric discovery, approval and small file transfer.
Control plane only; not a llama.cpp/GGUF scheduler.
"""
import os, sys, json, ssl, socket, struct, secrets, hashlib, uuid, queue, threading, time
from pathlib import Path

try:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    import datetime
except ImportError as ex:
    raise SystemExit("Missing cryptography dependency: " + str(ex))

BASE=Path(sys.executable if getattr(sys,"frozen",False) else __file__).resolve().parent
DATA=BASE/"mesh-data"; DATA.mkdir(exist_ok=True)
CFG=DATA/"identity.json"; CRT=DATA/"cert.pem"; KEY=DATA/"private.pem"
PORT=50554; DISC=50555; MULTICAST="239.255.50.55"; PROTO="sharedcompute-mesh-v1"
MAX_FILE=2*1024*1024; MAX_FRAME=3*1024*1024

def identity():
    if CFG.is_file():
        try:
            d=json.loads(CFG.read_text("utf-8"))
            if "device_id" in d and isinstance(d.get("paired"),dict):return d
        except (OSError,ValueError):pass
    return {"device_id":str(uuid.uuid4()),"paired":{}}

def save(obj):
    tmp=CFG.with_suffix(".tmp"); tmp.write_text(json.dumps(obj,indent=2),encoding="utf-8"); tmp.replace(CFG)

def certificate():
    if CRT.exists() and KEY.exists():return
    private=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    subject=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,"SharedCompute Mesh")])
    now=datetime.datetime.now(datetime.timezone.utc)
    cert=(x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
          .public_key(private.public_key()).serial_number(x509.random_serial_number())
          .not_valid_before(now-datetime.timedelta(days=1))
          .not_valid_after(now+datetime.timedelta(days=3650)).sign(private,hashes.SHA256()))
    KEY.write_bytes(private.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
    CRT.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    try:os.chmod(KEY,0o600)
    except OSError:pass

def recv_exact(sock,n):
    data=bytearray()
    while len(data)<n:
        part=sock.recv(n-len(data))
        if not part:raise ConnectionError("Peer disconnected")
        data.extend(part)
    return bytes(data)

def frame_recv(sock):
    n=struct.unpack("!I",recv_exact(sock,4))[0]
    if n<1 or n>MAX_FRAME:raise ValueError("Frame size rejected")
    obj=json.loads(recv_exact(sock,n))
    if not isinstance(obj,dict):raise ValueError("Invalid JSON")
    return obj

def frame_send(sock,obj):
    raw=json.dumps(obj,separators=(",",":")).encode("utf-8")
    if len(raw)>MAX_FRAME:raise ValueError("Frame too large")
    sock.sendall(struct.pack("!I",len(raw))+raw)

def filename(name):
    return ("".join(c for c in str(name) if c.isalnum() or c in " ._-").strip(" .")[:90] or "file.bin")

class Mesh:
    def __init__(self,emit):
        certificate();self.cfg=identity();save(self.cfg);self.emit=emit
        self.pin=hashlib.sha256(ssl.PEM_cert_to_DER_cert(CRT.read_text())).hexdigest()
        self.name=socket.gethostname()[:48];self.mode="AUTO";self.peers={};self.pending={}
        self.attempts={};self.lock=threading.RLock();self.stop=threading.Event()
        self.inbox=DATA/"inbox";self.inbox.mkdir(exist_ok=True)
        self.tcp=None;self.udp=None
    def offer(self,typ="offer"):
        return {"protocol":PROTO,"type":typ,"name":self.name,"device_id":self.cfg["device_id"],
                "role":self.mode,"pin":self.pin,"port":PORT,"platform":"Windows"}
    def start(self):
        threading.Thread(target=self.serve,daemon=True).start()
        threading.Thread(target=self.discover,daemon=True).start()
        self.emit("log","Discovery UDP 50555 / authenticated TLS 50554 started")
    def serve(self):
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(str(CRT),str(KEY))
        try:
            with socket.socket() as server:
                self.tcp=server;server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
                server.bind(("0.0.0.0",PORT));server.listen(10);server.settimeout(1)
                while not self.stop.is_set():
                    try:client,addr=server.accept()
                    except socket.timeout:continue
                    except OSError:break
                    threading.Thread(target=self.handle,args=(context,client,addr),daemon=True).start()
        except OSError as ex:self.emit("log","TCP listener error: "+str(ex))
    def handle(self,context,raw,addr):
        try:
            with context.wrap_socket(raw,server_side=True) as sock:
                sock.settimeout(75);req=frame_recv(sock)
                peerid=req.get("device_id","");token=req.get("token","")
                if not isinstance(peerid,str) or not (1<=len(peerid)<=100):raise ValueError("Invalid identity")
                with self.lock:stored=self.cfg["paired"].get(peerid,{})
                trusted=bool(token and secrets.compare_digest(token,stored.get("token","")))
                op=req.get("op")
                if op=="pair":
                    if trusted:frame_send(sock,{"status":"ok","trusted":True});return
                    if self.mode=="WORKER" or stored.get("token"):frame_send(sock,{"status":"denied"});return
                    nonce=secrets.token_hex(12);signal=threading.Event()
                    entry={"name":filename(req.get("name","Unknown")),"device_id":peerid,"event":signal,"allow":False,"token":""}
                    with self.lock:self.pending[nonce]=entry
                    self.emit("approval",nonce);signal.wait(60)
                    with self.lock:self.pending.pop(nonce,None)
                    frame_send(sock,{"status":"ok","token":entry["token"]} if entry["allow"] else {"status":"denied"})
                elif op=="send":
                    if not trusted or self.mode=="WORKER":frame_send(sock,{"status":"denied"});return
                    import base64
                    b64=req.get("data","")
                    if not isinstance(b64,str) or len(b64)>2800000:raise ValueError("Payload too large")
                    rawfile=base64.b64decode(b64,validate=True)
                    if len(rawfile)>MAX_FILE:raise ValueError("File exceeds 2 MiB")
                    path=self.inbox/(secrets.token_hex(5)+"-"+filename(req.get("filename","file.bin")))
                    path.write_bytes(rawfile);frame_send(sock,{"status":"ok"})
                    self.emit("log",f"File received: {path.name} ({len(rawfile)} bytes)")
                else:frame_send(sock,{"status":"error"})
        except (OSError,ValueError,ssl.SSLError) as ex:self.emit("log",f"Incoming {addr[0]}: {ex}")
    def approve(self,nonce,accept):
        with self.lock:
            item=self.pending.get(nonce)
            if not item:return
            if accept:
                token=secrets.token_hex(32);item["token"]=token;item["allow"]=True
                self.cfg["paired"][item["device_id"]]={"name":item["name"],"token":token}
                save(self.cfg)
            item["event"].set()
    def should_join(self,p):
        remote=p["role"]
        if self.mode=="MASTER" or remote=="WORKER":return False
        if self.mode=="WORKER":return True
        return remote=="MASTER" or (remote=="AUTO" and self.cfg["device_id"]>p["device_id"])
    def auto_join(self,p):
        if not self.should_join(p):return
        pid=p["device_id"]
        with self.lock:
            if self.cfg["paired"].get(pid,{}).get("token"):return
            now=time.monotonic()
            if now-self.attempts.get(pid,-99999)<90:return
            self.attempts[pid]=now
        self.emit("log","Requesting pairing with "+p.get("name","device"))
        threading.Thread(target=lambda:self.safe_pair(p),daemon=True).start()
    def discover(self):
        try:
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM,socket.IPPROTO_UDP) as udp:
                self.udp=udp;udp.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
                udp.setsockopt(socket.SOL_SOCKET,socket.SO_BROADCAST,1);udp.bind(("0.0.0.0",DISC))
                try:udp.setsockopt(socket.IPPROTO_IP,socket.IP_ADD_MEMBERSHIP,struct.pack("=4s4s",socket.inet_aton(MULTICAST),socket.inet_aton("0.0.0.0")))
                except OSError:pass
                udp.settimeout(1);last=0
                while not self.stop.is_set():
                    if time.monotonic()-last>=4:
                        last=time.monotonic();body=json.dumps(self.offer("discover")).encode("utf-8")
                        for ip in (MULTICAST,"255.255.255.255"):
                            try:udp.sendto(body,(ip,DISC))
                            except OSError:pass
                    try:packet,addr=udp.recvfrom(2048);p=json.loads(packet)
                    except socket.timeout:continue
                    except (OSError,ValueError,UnicodeError):continue
                    if p.get("protocol")!=PROTO or p.get("device_id")==self.cfg["device_id"]:continue
                    if (p.get("type") not in ("offer","discover") or p.get("role") not in ("MASTER","WORKER","AUTO") or
                        not isinstance(p.get("pin"),str) or len(p["pin"])!=64):continue
                    p["ip"]=addr[0];p["seen"]=time.monotonic()
                    with self.lock:self.peers[p["device_id"]]=p
                    self.emit("peers","");self.auto_join(p)
                    if p["type"]=="discover":
                        try:udp.sendto(json.dumps(self.offer()).encode("utf-8"),addr)
                        except OSError:pass
        except OSError as ex:self.emit("log","Discovery error: "+str(ex))
    def connect(self,p,request):
        ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.minimum_version=ssl.TLSVersion.TLSv1_2
        ctx.maximum_version=ssl.TLSVersion.TLSv1_2
        ctx.check_hostname=False;ctx.verify_mode=ssl.CERT_NONE
        with socket.create_connection((p["ip"],int(p["port"])),timeout=8) as raw:
            try:
                sock=ctx.wrap_socket(raw,server_hostname=p["ip"])
            except ssl.SSLError as ex:
                raise ConnectionError(
                    "TLS handshake with "+p.get("name","Android")+" failed ("+str(ex)+"). "+
                    "Check the Android ACTIVITY / ERRORS log for 'Incoming TLS handshake failed' "+
                    "and install matching Mesh APK 1.3.1."
                ) from ex
            with sock:
                sock.settimeout(75);actual=hashlib.sha256(sock.getpeercert(binary_form=True)).hexdigest()
                known=self.cfg["paired"].get(p["device_id"],{})
                if not secrets.compare_digest(actual,p["pin"]) or (known.get("pin") and not secrets.compare_digest(actual,known["pin"])):
                    raise ssl.SSLError("Device TLS identity changed")
                frame_send(sock,{**request,"device_id":self.cfg["device_id"],"name":self.name,"token":known.get("token","")})
                return frame_recv(sock),actual
    def safe_pair(self,p):
        try:
            result,pin=self.connect(p,{"op":"pair"})
            if result.get("status")=="ok":
                with self.lock:
                    prior=self.cfg["paired"].get(p["device_id"],{})
                    prior.update({"name":p["name"],"pin":pin})
                    if result.get("token"):prior["token"]=result["token"]
                    self.cfg["paired"][p["device_id"]]=prior;save(self.cfg)
                self.emit("log","Paired: "+p.get("name","device"))
            else:self.emit("log","Pairing denied or expired: "+p.get("name","device"))
        except Exception as ex:self.emit("log","Pairing error: "+str(ex))
    def send(self,p,file):
        import base64
        if p["role"] not in ("MASTER","AUTO"):raise ValueError("Destination must be MASTER/AUTO")
        contents=Path(file).read_bytes()
        if len(contents)>MAX_FILE:raise ValueError("Maximum file size is 2 MiB")
        result,_=self.connect(p,{"op":"send","filename":Path(file).name,"data":base64.b64encode(contents).decode()})
        if result.get("status")!="ok":raise RuntimeError("Transfer rejected")
        self.emit("log","Sent "+Path(file).name+" to "+p.get("name","device"))
    def close(self):
        self.stop.set()
        if self.tcp:self.tcp.close()
        if self.udp:self.udp.close()

def main():
    import tkinter as tk
    from tkinter import ttk,messagebox,filedialog
    root=tk.Tk();root.title("SharedCompute Mesh");root.geometry("780x595");root.configure(bg="#071820")
    events=queue.Queue();mesh=Mesh(lambda k,v:events.put((k,v)))
    tk.Label(root,text="SHARED  //  COMPUTE  MESH",bg="#071820",fg="#57eed2",font=("Segoe UI",19,"bold")).pack(pady=12)
    tk.Label(root,text="Auto discovery · Master / Worker / Auto · Approval required on first pairing",bg="#071820",fg="#b0d5d7").pack()
    role=tk.StringVar(value="AUTO")
    choices=tk.Frame(root,bg="#071820");choices.pack(pady=10)
    for mode in ("MASTER","WORKER","AUTO"):
        tk.Radiobutton(choices,text=mode,value=mode,variable=role,command=lambda:setattr(mesh,"mode",role.get()),
           bg="#071820",fg="#57eed2",selectcolor="#153441").pack(side="left",padx=18)
    table=ttk.Treeview(root,columns=("name","role","address","trust"),show="headings",height=8)
    for col,width in (("name",210),("role",95),("address",130),("trust",120)):
        table.heading(col,text=col.upper());table.column(col,width=width)
    table.pack(fill="x",padx=20,pady=8)
    log=tk.Text(root,bg="#0d2d35",fg="#b6f5e5",height=9);log.pack(fill="both",expand=True,padx=20,pady=10)
    def selected():
        ids=table.selection()
        if not ids:messagebox.showinfo("SharedCompute","Select a device first");return
        return mesh.peers.get(ids[0])
    def worker(fn):
        def run():
            try:fn()
            except Exception as ex:events.put(("log","ERROR: "+str(ex)))
        threading.Thread(target=run,daemon=True).start()
    def pair():
        p=selected()
        if p:worker(lambda:mesh.safe_pair(p))
    def send():
        p=selected()
        if not p:return
        path=filedialog.askopenfilename()
        if path:worker(lambda:mesh.send(p,path))
    bar=tk.Frame(root,bg="#071820");bar.pack(pady=8)
    for title,action in (("PAIR",pair),("SEND FILE",send),("OPEN INBOX",lambda:os.startfile(str(mesh.inbox)))):
        tk.Button(bar,text=title,command=action).pack(side="left",padx=8)
    def refresh():
        for _ in range(120):
            try:kind,value=events.get_nowait()
            except queue.Empty:break
            if kind=="log":log.insert("end",value+"\n");log.see("end")
            elif kind=="approval":
                item=mesh.pending.get(value)
                if item:
                    allowed=messagebox.askyesno("Pair SharedCompute",f"Allow device {item['name']} to pair?\nApprove only devices you own.")
                    mesh.approve(value,allowed)
        with mesh.lock:peers=list(mesh.peers.values())
        online={p["device_id"]:p for p in peers if time.monotonic()-p["seen"]<20}
        for iid in table.get_children():
            if iid not in online:table.delete(iid)
        for id,p in online.items():
            val=(p.get("name"),p.get("role"),p.get("ip"),"Paired" if mesh.cfg["paired"].get(id,{}).get("token") else "New")
            if table.exists(id):table.item(id,values=val)
            else:table.insert("", "end",iid=id,values=val)
        root.after(350,refresh)
    root.protocol("WM_DELETE_WINDOW",lambda:(mesh.close(),root.destroy()))
    mesh.start();refresh();root.mainloop()
if __name__=="__main__":main()
