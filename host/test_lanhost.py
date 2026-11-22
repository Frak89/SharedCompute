import unittest
from pathlib import Path
from lanhost import parse_pairings,server_args
class LanTests(unittest.TestCase):
    def test_pairing(self):
        line="192.168.1.8 | "+"a"*64+" | "+"b"*64
        self.assertEqual(parse_pairings("header\n"+line),[("192.168.1.8","a"*64,"b"*64)])
        for ip in ("8.8.8.8","127.0.0.1"):
            with self.assertRaises(ValueError):parse_pairings(line.replace("192.168.1.8",ip))
        with self.assertRaises(ValueError):parse_pairings("192.168.1.8 | bad | bad")
    def test_local_only(self):
        args=server_args(Path("llama-server.exe"),Path("model.gguf"),[12345,23456],4,1024,8081)
        self.assertEqual(args[args.index("--rpc")+1],"127.0.0.1:12345,127.0.0.1:23456")
        self.assertEqual(args[args.index("--host")+1],"127.0.0.1")
if __name__=="__main__":unittest.main()
