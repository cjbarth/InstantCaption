import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from publication_sync import Publication, Remote, synchronize, parse_metadata, allowed

def epub(value):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w') as z:
        z.writestr('mimetype', 'application/epub+zip')
        z.writestr('META-INF/container.xml', value)
    return buf.getvalue()

class Transport:
    def __init__(self):
        self.data = {'E': epub('first'), 'IN': epub('second')}
        self.reads = []
        self.fail = None
    def metadata(self, pub, lang):
        data = self.data[lang]
        return Remote('https://example.jw.org/'+lang, hashlib.sha256(data).hexdigest(), '', len(data))
    def download(self, remote):
        lang = remote.url.rsplit('/', 1)[1]
        self.reads.append(lang)
        if lang == self.fail:
            return io.BytesIO(b'bad')
        return io.BytesIO(self.data[lang])

class SyncTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.transport = Transport()
        self.pub = Publication('ijwbq')
    def sync(self):
        return synchronize(self.root, [self.pub], ['E','IN'], self.transport)[0][1]
    def test_incremental(self):
        self.assertEqual(self.sync(), 'updated')
        self.assertEqual(self.sync(), 'unchanged')
        self.assertEqual(self.transport.reads, ['E','IN'])
        self.transport.data['IN'] = epub('revised')
        self.assertEqual(self.sync(), 'updated')
        self.assertEqual(self.transport.reads, ['E','IN','IN'])
    def test_failed_pair_preserves_manifest(self):
        self.assertEqual(self.sync(), 'updated')
        old = (self.root/'manifest.json').read_bytes()
        self.transport.data = {'E': epub('third'), 'IN': epub('fourth')}
        self.transport.fail = 'IN'
        self.assertTrue(self.sync().startswith('failed:'))
        self.assertEqual((self.root/'manifest.json').read_bytes(), old)
    def test_checksum_mismatch(self):
        old = self.transport.metadata
        self.transport.metadata = lambda p,l: Remote(old(p,l).url, '0'*64, '', old(p,l).size)
        self.assertTrue(self.sync().startswith('failed:'))
        self.assertFalse((self.root/'manifest.json').exists())
    def test_metadata(self):
        row = {'file': {'url':'https://download.jw-cdn.org/file', 'checksum':'abc'}, 'filesize':123}
        payload = {'pub':'ijwbq','files':{'E':{'EPUB':[row]}}}
        self.assertEqual(parse_metadata(payload,self.pub,'E').size,123)
        payload['files']['E']['EPUB'].append(row)
        with self.assertRaises(ValueError):
            parse_metadata(payload,self.pub,'E')
    def test_url_validation(self):
        for url in ['http://jw.org/a','https://jw.org.evil.example/a','https://jw.org@evil.example/a']:
            with self.assertRaises(ValueError):
                allowed(url)

if __name__ == '__main__':
    unittest.main()
