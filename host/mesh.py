"""SharedCompute Mesh: discover, approve, and send small files on a trusted LAN.
Independent from llama.cpp / RPC; mesh membership is not model orchestration.
"""
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import secrets
import socket
import ssl
import struct
import sys
import threading
import time
import uuid

MCAST = "239.255.50.55"
DPORT = 50555
TPORT = 50554
PROTO = "sharedcompute-mesh-v1"
MAX_FILE = 2 * 1024 * 1024
MAX_FRAME = 3 * 1024 * 1024
DATA = Path(sys.executable if getattr(sys, "frozen", False) else __file__).resolve().parent / "mesh-data"
DATA.mkdir(parents=True, exist_ok=True)
CONFIG = DATA / "identity.json"
CERT = DATA / "mesh-cert.pem"
KEY = DATA / "mesh-key.pem"


def load_cfg():
    try:
        obj = json.loads(CONFIG.read_text(encoding="utf-8"))
        if isinstance(obj.get("device_id"), str) and isinstance(obj.get("paired"), dict):
            return obj
    except (OSError, ValueError, TypeError):
        pass
    return {"device_id": str(uuid.uuid4()), "paired": {}}


def save_cfg(cfg):
    temp = CONFIG.with_suffix(".tmp")
    temp.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    temp.replace(CONFIG)


def make_cert():
    if CERT.exists() and KEY.exists():
        return
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "SharedCompute Mesh")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
            .public_key(private.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .sign(private, hashes.SHA256()))
    KEY.write_bytes(private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    CERT.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    try:
        os.chmod(KEY, 0o600)
    except OSError:
        pass


def get_pin():
    pem = CERT.read_text(encoding="ascii")
    return hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest()


def recv_exact(sock, count):
    buf = bytearray()
    while len(buf) < count:
        data = sock.recv(count - len(buf))
        if not data:
            raise ConnectionError("Peer disconnected")
        buf.extend(data)
    return bytes(buf)


def recv_frame(sock):
    size = struct.unpack("!I", recv_exact(sock, 4))[0]
    if size == 0 or size > MAX_FRAME:
        raise ValueError("Invalid frame size")
    obj = json.loads(recv_exact(sock, size))
    if not isinstance(obj, dict):
        raise ValueError("Invalid frame")
    return obj


def send_frame(sock, obj):
    data = json.dumps(obj, separators=(",", ":")).encode("utf-8")
    if len(data) > MAX_FRAME:
        raise ValueError("Message exceeds size limit")
    sock.sendall(struct.pack("!I", len(data)) + data)


def safe_name(name):
    name = "".join(c for c in str(name) if c.isalnum() or c in "-_ .")
    return name.strip(" .")[:100] or "sharedcompute.bin"


class Mesh:
    def __init__(self, on_event):
        make_cert()
        self.cfg = load_cfg()
        save_cfg(self.cfg)
        self.pin = get_pin()
        self.name = socket.gethostname()[:48]
        self.mode = "AUTO"
        self.on_event = on_event
        self.stop = threading.Event()
        self.peers = {}
        self.attempts = {}
        self.pending = {}
        self.lock = threading.RLock()
        self.listener = None
        self.udp = None
        self.inbox = DATA / "inbox"
        self.inbox.mkdir(exist_ok=True)
        self.rpc_file = DATA / "rpc_nodes.json"

    def emit(self, message):
        self.on_event("log", message)

    def offer(self):
        return {"protocol": PROTO, "type": "offer", "name": self.name,
                "device_id": self.cfg["device_id"], "role": self.mode,
                "pin": self.pin, "port": TPORT, "platform": "Windows"}

    def start(self):
        threading.Thread(target=self.serve, daemon=True).start()
        threading.Thread(target=self.discover, daemon=True).start()
        self.emit("Mesh active · TCP 50554 / UDP 50555")

    def serve(self):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(str(CERT), str(KEY))
        try:
            with socket.socket() as listener:
                self.listener = listener
                listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                listener.bind(("0.0.0.0", TPORT))
                listener.listen(12)
                listener.settimeout(1)
                while not self.stop.is_set():
                    try:
                        raw, address = listener.accept()
                    except socket.timeout:
                        continue
                    threading.Thread(target=self.handle, args=(ctx, raw, address), daemon=True).start()
        except OSError as exc:
            self.emit("Mesh TCP server unavailable: " + str(exc))

    def handle(self, ctx, raw, addr):
        try:
            with ctx.wrap_socket(raw, server_side=True) as conn:
                conn.settimeout(90)
                req = recv_frame(conn)
                device = req.get("device_id", "")
                token = req.get("token", "")
                if not isinstance(device, str) or len(device) > 100:
                    raise ValueError("Invalid device")
                with self.lock:
                    known = self.cfg["paired"].get(device, {})
                    authenticated = bool(token and secrets.compare_digest(token, known.get("token", "")))
                if req.get("op") == "pair":
                    if authenticated:
                        send_frame(conn, {"status": "ok", "trusted": True})
                    elif self.mode == "WORKER" or known.get("token"):
                        send_frame(conn, {"status": "denied", "detail": "Not a master or existing identity needs revocation"})
                    else:
                        request_id = secrets.token_hex(8)
                        event = threading.Event()
                        item = {"id": request_id, "device_id": device,
                                "name": safe_name(req.get("name", "Unknown")),
                                "event": event, "allow": False, "token": ""}
                        with self.lock:
                            self.pending[request_id] = item
                        self.on_event("pair", request_id)
                        event.wait(60)
                        with self.lock:
                            self.pending.pop(request_id, None)
                        send_frame(conn, {"status": "ok", "token": item["token"]} if item["allow"] else {"status": "denied"})
                elif req.get("op") == "rpc_info":
                    if not authenticated or self.mode == "WORKER":
                        send_frame(conn, {"status": "denied"})
                        return
                    details = req.get("rpc", "")
                    parts = [p.strip() for p in details.split("|")]
                    if len(parts) != 3 or not all(re.fullmatch(r"[0-9a-fA-F]{64}", x) for x in parts[1:]):
                        send_frame(conn, {"status": "error", "detail": "Invalid RPC credentials"})
                        return
                    with self.lock:
                        try:
                            nodes = json.loads(self.rpc_file.read_text(encoding="utf-8"))
                        except (OSError, ValueError):
                            nodes = {}
                        # Use authenticated connection source IP rather than trusting an advertised address.
                        nodes[device] = {"ip": addr[0], "pin": parts[1].lower(),
                                         "token": parts[2].lower(), "updated": time.time()}
                        tmp = self.rpc_file.with_suffix(".tmp")
                        tmp.write_text(json.dumps(nodes, indent=2), encoding="utf-8")
                        tmp.replace(self.rpc_file)
                    self.emit("Authenticated RPC node registered: " + addr[0])
                    send_frame(conn, {"status": "ok"})
                elif req.get("op") == "send":
                    if not authenticated:
                        send_frame(conn, {"status": "denied"})
                        return
                    encoded = req.get("data", "")
                    if not isinstance(encoded, str) or len(encoded) > 2800000:
                        raise ValueError("File too large")
                    payload = base64.b64decode(encoded, validate=True)
                    if len(payload) > MAX_FILE:
                        raise ValueError("File too large")
                    filename = safe_name(req.get("filename", "file.bin"))
                    destination = self.inbox / (secrets.token_hex(4) + "-" + filename)
                    destination.write_bytes(payload)
                    self.emit("Received " + filename + " (" + str(len(payload)) + " bytes) from " + addr[0])
                    send_frame(conn, {"status": "ok", "saved": destination.name})
                else:
                    send_frame(conn, {"status": "error", "detail": "Unknown operation"})
        except Exception as exc:
            self.emit("Incoming " + addr[0] + ": " + str(exc))

    def approve(self, request_id, allowed):
        with self.lock:
            item = self.pending.get(request_id)
            if item is None:
                return
            if allowed:
                token = secrets.token_hex(32)
                self.cfg["paired"][item["device_id"]] = {"name": item["name"], "token": token}
                save_cfg(self.cfg)
                item["token"] = token
                item["allow"] = True
            item["event"].set()

    def discover(self):
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as udp:
                self.udp = udp
                udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                udp.bind(("0.0.0.0", DPORT))
                group = struct.pack("=4s4s", socket.inet_aton(MCAST), socket.inet_aton("0.0.0.0"))
                try:
                    udp.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, group)
                except OSError as exc:
                    self.emit("Multicast receive restricted: " + str(exc))
                udp.settimeout(1)
                last = 0
                while not self.stop.is_set():
                    if time.monotonic() - last > 4:
                        last = time.monotonic()
                        body = json.dumps({**self.offer(), "type": "discover"}).encode("utf-8")
                        for destination in ((MCAST, DPORT), ("255.255.255.255", DPORT)):
                            try:
                                udp.sendto(body, destination)
                            except OSError:
                                pass
                    try:
                        packet, addr = udp.recvfrom(1500)
                        obj = json.loads(packet.decode("utf-8"))
                    except socket.timeout:
                        continue
                    except (ValueError, UnicodeError, OSError):
                        continue
                    if obj.get("protocol") != PROTO or obj.get("device_id") == self.cfg["device_id"]:
                        continue
                    if (obj.get("type") not in ("offer", "discover") or
                        obj.get("role") not in ("MASTER", "WORKER", "AUTO") or
                        not isinstance(obj.get("pin"), str) or len(obj["pin"]) != 64):
                        continue
                    obj["ip"] = addr[0]
                    obj["seen"] = time.monotonic()
                    with self.lock:
                        self.peers[obj["device_id"]] = obj
                    self.on_event("peers", "")
                    remote_role = obj.get("role")
                    auto_join = (self.mode == "WORKER" and remote_role in ("MASTER", "AUTO")) or (
                        self.mode == "AUTO" and (
                            remote_role == "MASTER" or (
                                remote_role == "AUTO" and self.cfg["device_id"] > obj["device_id"])))
                    if auto_join:
                        now = time.monotonic()
                        with self.lock:
                            last_attempt = self.attempts.get(obj["device_id"], -1e9)
                            due = now - last_attempt > 120
                            if due:
                                self.attempts[obj["device_id"]] = now
                        if due:
                            threading.Thread(target=self._auto_pair, args=(obj.copy(),), daemon=True).start()
                    if obj.get("type") == "discover":
                        try:
                            udp.sendto(json.dumps(self.offer()).encode("utf-8"), addr)
                        except OSError:
                            pass
        except OSError as exc:
            self.emit("Mesh discovery unavailable: " + str(exc))

    def connect(self, peer, request):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        with socket.create_connection((peer["ip"], int(peer["port"])), timeout=8) as raw:
            with ctx.wrap_socket(raw, server_hostname=peer["ip"]) as conn:
                conn.settimeout(75)
                actual = hashlib.sha256(conn.getpeercert(binary_form=True)).hexdigest()
                if not secrets.compare_digest(actual, peer["pin"]):
                    raise ssl.SSLError("Peer TLS identity mismatch")
                stored = self.cfg["paired"].get(peer["device_id"], {})
                if stored.get("pin") and not secrets.compare_digest(stored["pin"], actual):
                    raise ssl.SSLError("Saved peer certificate has changed")
                send_frame(conn, {**request, "device_id": self.cfg["device_id"],
                                  "name": self.name, "token": stored.get("token", "")})
                return recv_frame(conn), actual

    def _auto_pair(self, peer):
        try:
            self.emit("Auto pairing request to " + peer.get("name", peer["ip"]))
            self.pair(peer)
        except Exception as exc:
            self.emit("Auto pairing failed: " + str(exc))

    def pair(self, peer):
        result, pin = self.connect(peer, {"op": "pair"})
        if result.get("status") == "ok":
            with self.lock:
                current = self.cfg["paired"].get(peer["device_id"], {})
                current.update({"name": peer["name"], "pin": pin})
                if result.get("token"):
                    current["token"] = result["token"]
                self.cfg["paired"][peer["device_id"]] = current
                save_cfg(self.cfg)
            self.emit("Paired with " + peer["name"])
        else:
            self.emit("Pairing denied or timed out by " + peer["name"])

    def send_file(self, peer, filepath):
        payload = Path(filepath).read_bytes()
        if len(payload) > MAX_FILE:
            raise ValueError("File must be 2 MiB or smaller")
        result, _ = self.connect(peer, {"op": "send", "filename": Path(filepath).name,
                                         "data": base64.b64encode(payload).decode("ascii")})
        if result.get("status") != "ok":
            raise RuntimeError("Peer rejected file: " + str(result))
        self.emit("Sent " + Path(filepath).name + " to " + peer["name"])

    def close(self):
        self.stop.set()
        if self.listener:
            self.listener.close()
        if self.udp:
            self.udp.close()


def main():
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk
    root = tk.Tk()
    root.title("SharedCompute Mesh · LAN")
    root.geometry("780x570")
    root.configure(bg="#071820")
    events = queue.Queue()
    mesh = Mesh(lambda kind, value: events.put((kind, value)))
    role = tk.StringVar(value="AUTO")
    tk.Label(root, text="SHAREDCOMPUTE  /  MESH", bg="#071820", fg="#57eed2", font=("Segoe UI", 19, "bold")).pack(pady=14)
    tk.Label(root, text="Find peers · Approve first pair · Send to master · No cloud / ADB", bg="#071820", fg="#b0d5d7").pack()
    options = tk.Frame(root, bg="#071820")
    options.pack(pady=12)
    for value in ("MASTER", "WORKER", "AUTO"):
        tk.Radiobutton(options, text=value, variable=role, value=value, bg="#071820", fg="#57eed2", selectcolor="#153441", command=lambda: setattr(mesh, "mode", role.get())).pack(side="left", padx=16)
    tree = ttk.Treeview(root, columns=("name", "role", "ip", "trusted"), show="headings", height=9)
    for column, width in (("name", 200), ("role", 90), ("ip", 130), ("trusted", 130)):
        tree.heading(column, text=column.upper()); tree.column(column, width=width)
    tree.pack(fill="x", padx=24)
    log = tk.Text(root, bg="#0d2d35", fg="#b6f5e5", height=9, wrap="word")
    log.pack(fill="both", expand=True, padx=24, pady=12)
    def selected():
        items = tree.selection()
        if not items:
            messagebox.showinfo("SharedCompute", "Select a discovered device")
            return None
        return mesh.peers.get(items[0])
    def run_safe(fn):
        def wrapper():
            try:
                fn()
            except Exception as exc:
                events.put(("log", "ERROR: " + str(exc)))
        threading.Thread(target=wrapper, daemon=True).start()
    def pair():
        p = selected()
        if p:
            run_safe(lambda: mesh.pair(p))
    def send():
        p = selected()
        if not p:
            return
        path = filedialog.askopenfilename()
        if path:
            run_safe(lambda: mesh.send_file(p, path))
    buttons = tk.Frame(root, bg="#071820")
    buttons.pack(pady=10)
    tk.Button(buttons, text="PAIR SELECTED", command=pair).pack(side="left", padx=10)
    tk.Button(buttons, text="SEND FILE", command=send).pack(side="left", padx=10)
    tk.Button(buttons, text="OPEN INBOX", command=lambda: os.startfile(str(mesh.inbox))).pack(side="left", padx=10)
    def refresh():
        updates = False
        for _ in range(100):
            try:
                kind, value = events.get_nowait()
            except queue.Empty:
                break
            if kind == "log":
                log.insert("end", value + "\n"); log.see("end")
            elif kind == "peers":
                updates = True
            elif kind == "pair":
                pending = mesh.pending.get(value)
                if pending:
                    allow = messagebox.askyesno("SharedCompute pairing", "Allow " + pending["name"] + " to pair with this device?\n\nOnly accept your own device.")
                    mesh.approve(value, allow)
        if updates:
            with mesh.lock:
                peers = list(mesh.peers.values())
            active = {p["device_id"] for p in peers if time.monotonic() - p["seen"] < 20}
            for item in tree.get_children():
                if item not in active:
                    tree.delete(item)
            for peer in peers:
                ident = peer["device_id"]
                if ident not in active:
                    continue
                trusted = "Paired" if ident in mesh.cfg["paired"] else "Unpaired"
                vals = (peer.get("name", ""), peer["role"], peer["ip"], trusted)
                if tree.exists(ident):
                    tree.item(ident, values=vals)
                else:
                    tree.insert("", "end", iid=ident, values=vals)
        root.after(200, refresh)
    def close():
        mesh.close();root.destroy()
    root.protocol("WM_DELETE_WINDOW", close)
    mesh.start()
    refresh()
    root.mainloop()


if __name__ == "__main__":
    main()
