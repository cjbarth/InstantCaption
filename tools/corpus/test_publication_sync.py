import datetime as dt
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from publication_sync import *


def epub(label):
    buf=io.BytesIO()
    with zipfile.ZipFile(buf,'w') as z:
        z.writestr('mimetype','application/epub+zip')
        z.writestr('META-INF/container.xml','<container/>')
        z.writestr('synthetic.txt',label)
    return buf.getvalue()

class Fake:
    def __init__(self):
        self.files={'IN':epub('synthetic one'),'E':epub('synthetic two')}; self.calls=[]; self.fail=None
    def metadata(self,pub,lang):
        data=self.files[lang]
        url='https://example.jw-cdn.org/'+lang
        return parse_metadata({'pub':pub.symbol,'files':{lang:{'EPUB':[{'file':{'url':url,'checksum':hashlib.sha256(data).hexdigest()},'filesize':len(data)}]}}},pub,lang)
    def download(self,remote):
        lang=remote.url.rsplit('/',1)[-1];self.calls.append(lang)
        if self.fail==lang: return io.BytesIO(b'truncated')
        return io.BytesIO(self.files[lang])

class SyncTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.root=Path(self.tmp.name); self.fake=Fake(); self.pub=Publication('ijwbq')
    def tearDown(self): self.tmp.cleanup()
    def run_sync(self): return synchronize(self.root,[self.pub],['IN','E'],self.fake)[0][1]
    def manifest(self): return (self.root/'manifest.json').read_bytes()
    def test_first_sync_and_skip(self):
        self.assertEqual(self.run_sync(),'updated'); self.assertEqual(self.fake.calls,['IN','E']); self.fake.calls.clear()
        self.assertEqual(self.run_sync(),'unchanged'); self.assertEqual(self.fake.calls,[])
    def test_one_language_changes(self):
        self.run_sync(); self.fake.calls.clear(); self.fake.files['E']=epub('new synthetic')
        self.assertEqual(self.run_sync(),'updated'); self.assertEqual(self.fake.calls,['E'])
    def test_failed_update_preserves_pair(self):
        self.run_sync(); before=self.manifest(); self.fake.files={'IN':epub('new in'),'E':epub('new en')};self.fake.fail='E'
        self.assertTrue(self.run_sync().startswith('failed:')); self.assertEqual(self.manifest(),before)
        self.fake.fail=None; self.assertEqual(self.run_sync(),'updated')
    def test_local_corruption_repaired(self):
        self.run_sync(); state=json.loads(self.manifest()); name=state['pairs']['ijwbq-IN-E']['IN']['file']
        (self.root/name).write_bytes(b'bad'); self.fake.calls.clear(); self.assertEqual(self.run_sync(),'updated');self.assertEqual(self.fake.calls,['IN'])
    def test_checksum_mismatch_rejected(self):
        data=self.fake.files['IN']; remote=Remote('https://example.jw-cdn.org/IN','0'*64,'',len(data))
        with self.assertRaisesRegex(ValueError,'checksum'): download(self.root,self.fake,remote)
        self.assertEqual(list(self.root.iterdir()),[])
    def test_invalid_hosts(self):
        for url in ['http://jw.org/x','https://jw.org.evil.test/x','https://jw.org@evil.test/x','https://jw.org:444/x']:
            with self.assertRaises(ValueError): allowed(url)
    def test_ambiguous_response_rejected(self):
        with self.assertRaises(ValueError): parse_metadata({'files':{'E':{'EPUB':[{},{}]}}},self.pub,'E')
    def test_month_rollover(self):
        keys=[x.key for x in plan(dt.date(2026,1,1))];self.assertIn('w-202509',keys);self.assertIn('mwb-202603',keys)
    def test_issue_ids(self):
        with self.assertRaises(ValueError): Publication('w')
        self.assertIn('issue=202611',request_url(Publication('mwb','202611'),'IN'))

if __name__=='__main__': unittest.main()
