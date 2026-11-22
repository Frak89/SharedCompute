"""CrossCompute policy and honest performance measurement for local model APIs."""
import concurrent.futures as cf
import ctypes
import ipaddress
import json
import re
import time
import urllib.parse
from dataclasses import dataclass

MODES=("AUTO","SPEED","QUALITY","PARALLEL","LOW RAM","HYBRID")
BENCH_PROMPT="Output the integers from 1 through 80, separated by commas. No commentary."
@dataclass(frozen=True)
class Measurement:
    tokens: int | None
    seconds: float
    @property
    def rate(self):
        return self.tokens/self.seconds if self.tokens is not None and self.seconds>0 else None

def normalize_device(model):
    host=urllib.parse.urlsplit(model.url).hostname
    if host=="localhost":
        return "local-pc"
    try:
        if ipaddress.ip_address(host).is_loopback:
            return "local-pc"
    except (ValueError,TypeError):
        pass
    # Group all endpoints on the same address, not on a user-editable display name.
    return host or model.device

def free_ram_mib():
    class Memory(ctypes.Structure):
        _fields_=[("length",ctypes.c_ulong),("load",ctypes.c_ulong)]+[(x,ctypes.c_ulonglong) for x in ("total","avail","page","availpage","virtual","availvirtual","extended")]
    try:
        m=Memory();m.length=ctypes.sizeof(m)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m)):
            return m.avail//1048576
    except (AttributeError,OSError):
        pass
    return None

def measure(model,request,stop,emit):
    if stop.is_set(): raise InterruptedError("Stopped")
    start=time.perf_counter()
    payload={"model":model.name,"messages":[{"role":"user","content":BENCH_PROMPT}],"max_tokens":64,"temperature":0,"stream":False}
    with request(model.url+"/chat/completions",payload,timeout=120) as response:
        obj=json.load(response)
    if stop.is_set(): raise InterruptedError("Stopped")
    if not obj.get("choices"): raise RuntimeError("No benchmark completion")
    seconds=time.perf_counter()-start
    n=(obj.get("usage") or {}).get("completion_tokens")
    if not isinstance(n,int) or isinstance(n,bool) or n<=0: n=None
    m=Measurement(n,seconds)
    emit("BENCH",model.label+(" / %.2f tokens/s (end-to-end)"%m.rate if m.rate is not None else " / token count unavailable")+" / %.2fs"%seconds)
    return m

def benchmark(models,request,stop,emit):
    out={}
    # Always benchmark sequentially. Do not cause additional simultaneous model loads.
    for m in models:
        if stop.is_set(): raise InterruptedError("Stopped")
        ram=free_ram_mib()
        if normalize_device(m)=="local-pc" and ram is not None and ram<1024:
            emit("BENCH","Skipped "+m.label+": less than 1024 MiB RAM currently available")
            continue
        try: out[m.label]=measure(m,request,stop,emit)
        except InterruptedError: raise
        except Exception as e: emit("ERROR",m.label+": "+str(e))
    return out

def rank(models,measurements):
    def key(m):
        x=measurements.get(m.label)
        return -x.rate if x is not None and x.rate is not None else float("inf")
    return sorted(models,key=key)

def select(models,master,mode,measurements,manual=False):
    if mode=="LOW RAM" or manual: return master
    ranked=rank(models,measurements)
    x=measurements.get(ranked[0].label)
    return ranked[0] if x is not None and x.rate is not None else master

def split_tasks(prompt):
    return [t.strip() for t in re.split(r"(?m)^\s*---\s*$",prompt) if t.strip()]

def parallel(models,tasks,master,measurements,generate,emit,stop,allow_same_device=False):
    if not tasks: raise ValueError("No tasks")
    if len(tasks)>16: raise ValueError("Limit: 16 independent tasks per run")
    ranked=rank(models,measurements)
    if allow_same_device:
        workers=ranked
    else:
        groups={}
        for m in ranked: groups.setdefault(normalize_device(m),m)
        workers=list(groups.values())
    if len(workers)==1:
        emit("SCHEDULER","Single hardware group: tasks run sequentially. No claimed speed multiplication.")
    buckets=[[] for _ in workers]
    for i,task in enumerate(tasks): buckets[i%len(workers)].append((i,task))
    def worker(model,bucket):
        results=[]
        for i,task in bucket:
            if stop.is_set(): raise InterruptedError("Stopped")
            try:
                answer=generate(model,"Independent task %d:\n%s"%(i+1,task),emit,stop,"PARALLEL",1024)
                results.append((i,model.label,answer))
            except InterruptedError: raise
            except Exception as e:
                emit("ERROR",model.label+": "+str(e))
                results.append((i,model.label,"ERROR: "+str(e)))
        return results
    start=time.perf_counter()
    with cf.ThreadPoolExecutor(max_workers=min(8,len(workers))) as pool:
        futures=[pool.submit(worker,m,b) for m,b in zip(workers,buckets) if b]
        results=sorted(x for future in futures for x in future.result())
    emit("SCHEDULER","Completed %d tasks in %.2fs; token rates are not inferred from text length."%(len(results),time.perf_counter()-start))
    return "\n\n".join("TASK %d — %s\n%s"%(i+1,label,answer) for i,label,answer in results)
