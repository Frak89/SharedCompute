import threading
import unittest
from unittest.mock import patch
import multiagent as m

class TeamTests(unittest.TestCase):
    def test_endpoints(self):
        self.assertEqual(m.parse_endpoints("PC | http://127.0.0.1:11434"),[("PC","http://127.0.0.1:11434/v1")])
        with self.assertRaises(ValueError): m.parse_endpoints("Internet | https://8.8.8.8")
    def test_master(self):
        q=m.Model("Phone","http://127.0.0.1:1234/v1","qwen")
        c=m.Model("PC","http://127.0.0.1:11434/v1","cyber-tiel")
        self.assertEqual(m.choose_master([q,c],"Auto"),c)
        self.assertEqual(m.choose_master([q,c],q.label),q)
    def test_options(self):
        self.assertEqual(m.parse_options('text [{"title":"A","description":"a"},{"title":"B","description":"b"}]',3),[("A","a"),("B","b")])
        self.assertEqual(m.parse_options("invalid",3),[])
    def test_plan_and_final_are_separate(self):
        a=m.Model("PC","http://127.0.0.1:11434/v1","a")
        b=m.Model("Phone","http://127.0.0.1:1234/v1","b")
        calls=[]
        def fake(model,task,emit,stop,stage,tokens=1024):
            calls.append((stage,task))
            return '[{"title":"A","description":"a"},{"title":"B","description":"b"}]' if stage=="MASTER OPTIONS" else "answer"
        with patch.object(m,"generate",fake):
            options,drafts,reviews=m.plan([a,b],a,"original",2,lambda *x:None,threading.Event())
            self.assertEqual(len(options),2)
            self.assertFalse(any(x[0]=="MASTER FINAL" for x in calls))
            m.finalize(a,"original","B","feedback",drafts,reviews,lambda *x:None,threading.Event())
            self.assertIn("USER APPROVED OPTION:\nB",calls[-1][1])
    def test_cancel(self):
        stop=threading.Event();stop.set()
        with self.assertRaises(InterruptedError):
            m.team_round([m.Model("PC","http://127.0.0.1/v1","a")],lambda x:"x",lambda *x:None,stop,"DRAFT")

if __name__=="__main__": unittest.main()
