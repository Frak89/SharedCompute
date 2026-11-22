import io,json,threading,unittest
from unittest.mock import patch
from multiagent import Model
import crosscompute as c

class CrossTests(unittest.TestCase):
    def setUp(self):
        self.a=Model("PC","http://127.0.0.1:11434/v1","ollama")
        self.b=Model("Other label","http://localhost:9931/v1","gguf")
        self.p=Model("Phone","http://192.168.1.5:8080/v1","remote")
        self.stop=threading.Event()
    def test_grouping(self):
        self.assertEqual(c.normalize_device(self.a),c.normalize_device(self.b))
        self.assertNotEqual(c.normalize_device(self.a),c.normalize_device(self.p))
    def test_benchmark_real_token_count(self):
        data={"choices":[{"message":{"content":"hello"}}],"usage":{"completion_tokens":20}}
        r=c.measure(self.a,lambda *a,**kw:io.BytesIO(json.dumps(data).encode()),self.stop,lambda *x:None)
        self.assertEqual(r.tokens,20);self.assertGreater(r.rate,0)
        del data["usage"]
        r=c.measure(self.a,lambda *a,**kw:io.BytesIO(json.dumps(data).encode()),self.stop,lambda *x:None)
        self.assertIsNone(r.rate)
    def test_auto_and_manual(self):
        v={self.a.label:c.Measurement(10,2),self.b.label:c.Measurement(30,2)}
        self.assertEqual(c.select([self.a,self.b],self.a,"AUTO",v),self.b)
        self.assertEqual(c.select([self.a,self.b],self.a,"LOW RAM",v),self.a)
        self.assertEqual(c.select([self.a,self.b],self.a,"SPEED",v,True),self.a)
    def test_tasks_and_default_serialization(self):
        self.assertEqual(c.split_tasks("A\n---\nB"),["A","B"])
        called=[]
        def fake(m,p,emit,stop,stage,tokens):
            called.append(m.label);return p
        r=c.parallel([self.a,self.b],["a","b"],self.a,{},fake,lambda *x:None,self.stop)
        self.assertEqual(called,[self.a.label,self.a.label])
        self.assertIn("TASK 2",r)
    def test_multiple_devices(self):
        called=[]
        def fake(m,p,*args):
            called.append(m.label);return p
        c.parallel([self.a,self.p],["a","b"],self.a,{},fake,lambda *x:None,self.stop)
        self.assertEqual(set(called),{self.a.label,self.p.label})
    def test_memory_guard(self):
        with patch.object(c,"free_ram_mib",return_value=500):
            self.assertEqual(c.benchmark([self.a],None,self.stop,lambda *x:None),{})

if __name__=="__main__": unittest.main()
