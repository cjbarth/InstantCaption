#!/usr/bin/env python3
"""Experimental public EPUB download preparation; no content ships with this tool."""
import argparse
import datetime as dt
import hashlib
import json
import os
import re
import tempfile
import time
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass, asdict
from pathlib import Path

ENDPOINT = 'https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS'
LANGUAGES = {'en': 'E', 'id': 'IN'}
SERIES = ('ijwbq', 'ijwyp', 'ijwcl', 'ijwfq', 'mrt')
BOOKS = ('nwt', 'lff', 'od', 'it', 'scl')
ISSUED = ('w', 'mwb', 'g', 'wp')
MAX_METADATA = 2 * 1024 * 1024
MAX_FILE = 150 * 1024 * 1024

@dataclass(frozen=True)
class Publication:
    symbol: str
    issue: str = ''
    def __post_init__(self):
        if self.symbol not in SERIES + BOOKS + ISSUED:
            raise ValueError('Unknown publication symbol')
        if self.symbol in ISSUED:
            if not re.fullmatch(r'[0-9]{6}(?:[0-9]{2})?', self.issue):
                raise ValueError('Issued publications require explicit 6- or 8-digit issue ID')
        elif self.issue:
            raise ValueError('Unexpected issue ID')
    @property
    def key(self):
        return self.symbol + ('-' + self.issue if self.issue else '')

@dataclass(frozen=True)
class Remote:
    url: str
    checksum: str
    modified: str
    size: int

def month(date, delta):
    n = date.year * 12 + date.month - 1 + delta
    return f'{n//12:04d}{n%12+1:02d}'

def plan(today, extra=()):
    # Candidate issue IDs; not a verified meeting calendar.
    w = [Publication('w', month(today, i)) for i in range(-4, 1)]
    start = -((today.month-1) % 2)
    mwb = [Publication('mwb', month(today, i)) for i in (start, start+2)]
    return list(dict.fromkeys(w + mwb + [Publication(p) for p in SERIES+BOOKS] + list(extra)))

def request_url(pub, language):
    params = {'booknum': '0', 'output': 'json', 'pub': pub.symbol,
              'fileformat': 'epub', 'alllangs': '0', 'langwritten': language}
    if pub.issue:
        params['issue'] = pub.issue
    return ENDPOINT + '?' + urllib.parse.urlencode(params)

def allowed(url):
    p = urllib.parse.urlsplit(url)
    host = p.hostname or ''
    if (p.scheme != 'https' or p.username or p.password or p.port not in (None,443)
            or not (host in ('jw.org','jw-cdn.org') or host.endswith(('.jw.org','.jw-cdn.org')))):
        raise ValueError('Download must use HTTPS on a publisher domain')
    return url

class Redirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return super().redirect_request(req, fp, code, msg, headers, allowed(newurl))

class Transport:
    def __init__(self):
        self.opener = urllib.request.build_opener(Redirects())
        self.last = 0.0
    def open(self, url):
        time.sleep(max(0, 1 - (time.monotonic() - self.last)))
        self.last = time.monotonic()
        return self.opener.open(urllib.request.Request(allowed(url), headers={
            'User-Agent': 'PersonalCorpusPrototype/0.1', 'Accept-Encoding': 'identity'}), timeout=30)
    def metadata(self, pub, language):
        with self.open(request_url(pub, language)) as f:
            raw = f.read(MAX_METADATA+1)
        if len(raw)>MAX_METADATA: raise ValueError('Metadata too large')
        return parse_metadata(json.loads(raw), pub, language)
    def download(self, remote):
        return self.open(remote.url)

def parse_metadata(payload, pub, lang):
    if not isinstance(payload, dict): raise ValueError('Invalid metadata')
    if payload.get('pub') not in (None, '', pub.symbol): raise ValueError('Mismatched publication')
    if pub.issue and payload.get('issue') not in (None, '', pub.issue):
        raise ValueError('Mismatched issue')
    candidates = payload.get('files', {}).get(lang, {}).get('EPUB', [])
    if not isinstance(candidates, list) or len(candidates)!=1:
        raise ValueError('Expected one unambiguous EPUB; unavailable or multiple editions')
    row = candidates[0]; file = row['file']; size = row.get('filesize', row.get('fileSize', 0))
    if not isinstance(size, int) or isinstance(size, bool) or not 0 < size <= MAX_FILE:
        raise ValueError('Missing or out-of-range file size')
    if row.get('pub') not in (None, '', pub.symbol): raise ValueError('Mismatched file publication')
    return Remote(allowed(file['url']), str(file.get('checksum') or ''),
                  str(file.get('modifiedDatetime') or file.get('modifiedDateTime') or ''), size)

def digest_file(path, algorithm='sha256'):
    h = hashlib.new(algorithm)
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(65536), b''): h.update(chunk)
    return h.hexdigest()

def validate_epub(path):
    with zipfile.ZipFile(path) as z:
        info = z.getinfo('mimetype')
        if info.file_size != 20 or z.read(info) != b'application/epub+zip':
            raise ValueError('Not an EPUB')
        if 'META-INF/container.xml' not in z.namelist(): raise ValueError('Incomplete EPUB')

def same(remote, previous, root):
    if not previous: return False
    old = previous['remote']
    if remote.checksum:
        match = remote.checksum == old.get('checksum') and remote.size == old.get('size')
    else:
        match = bool(remote.modified) and remote.modified == old.get('modified') and remote.size == old.get('size')
    if not match: return False
    filename = previous['file']
    if not re.fullmatch(r'[a-f0-9]{64}\.epub', filename):
        return False
    path = root / filename
    return path.is_file() and path.stat().st_size == remote.size and digest_file(path)==previous['sha256']

def download(root, transport, remote):
    temp = None
    try:
        with tempfile.NamedTemporaryFile(dir=root, delete=False) as f:
            temp=Path(f.name); n=0; h=hashlib.sha256()
            with transport.download(remote) as stream:
                while chunk := stream.read(65536):
                    n+=len(chunk)
                    if n>remote.size or n>MAX_FILE: raise ValueError('Download exceeds declared size')
                    h.update(chunk); f.write(chunk)
            if n!=remote.size: raise ValueError('Truncated download')
            f.flush(); os.fsync(f.fileno())
        checksum=remote.checksum.lower()
        alg={32:'md5',40:'sha1',64:'sha256'}.get(len(checksum)) if re.fullmatch('[0-9a-f]+', checksum) else None
        if alg and digest_file(temp,alg)!=checksum: raise ValueError('Publisher checksum mismatch')
        validate_epub(temp)
        name=h.hexdigest()+'.epub'; os.replace(temp,root/name); temp=None
        return {'remote':asdict(remote),'file':name,'sha256':h.hexdigest()}
    finally:
        if temp is not None: temp.unlink(missing_ok=True)

def atomic_json(path, value):
    name=None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, delete=False) as f:
            name=Path(f.name); json.dump(value,f,indent=2); f.write('\n'); f.flush(); os.fsync(f.fileno())
        os.replace(name,path); name=None
    finally:
        if name: name.unlink(missing_ok=True)

def synchronize(root, publications, languages, transport):
    root.mkdir(parents=True,exist_ok=True)
    statepath=root/'manifest.json'
    state=json.loads(statepath.read_text(encoding='utf-8')) if statepath.exists() else {'version':1,'pairs':{}}
    if state.get('version')!=1: raise ValueError('Unknown manifest version')
    results=[]
    for pub in publications:
        key=pub.key+'-'+ '-'.join(languages)
        before=state['pairs'].get(key,{})
        try:
            remotes={lang:transport.metadata(pub,lang) for lang in languages}
            after={}; changed=False
            for lang,remote in remotes.items():
                previous=before.get(lang)
                if same(remote,previous,root): after[lang]=previous
                else: after[lang]=download(root,transport,remote); changed=True
            if changed:
                next_state={'version':1,'pairs':{**state['pairs'],key:after}}
                atomic_json(statepath,next_state); state=next_state
            results.append((key,'updated' if changed else 'unchanged'))
        except Exception as exc:
            results.append((key,'failed: '+str(exc)))
    return results

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--languages',nargs=2,choices=LANGUAGES,default=['id','en'])
    p.add_argument('--issue',action='append',default=[],metavar='SYMBOL:ISSUE')
    p.add_argument('--only',action='append',help='Limit to a symbol or symbol-issue key')
    p.add_argument('--cache',type=Path,default=Path.home()/'.local'/'share'/'caption-corpus-prototype')
    p.add_argument('--sync',action='store_true',help='Explicitly permit network downloads; otherwise print plan')
    a=p.parse_args()
    if len(set(a.languages))!=2: p.error('Choose different languages')
    try: extra=[Publication(*x.split(':',1)) for x in a.issue]
    except (ValueError,TypeError) as e: p.error(str(e))
    publications=plan(dt.date.today(),extra)
    if a.only:
        publications=[x for x in publications if x.symbol in a.only or x.key in a.only]
        if not publications: p.error('No matching publications')
    langs=[LANGUAGES[x] for x in a.languages]
    if not a.sync:
        for pub in publications: print(pub.key, *langs)
        return 0
    root=a.cache.expanduser().resolve(); source=Path(__file__).resolve().parent
    if root==source or source in root.parents or any((x/'.git').exists() for x in (root,*root.parents)):
        p.error('Cache must be outside the source repository')
    results=synchronize(root,publications,langs,Transport())
    for key,result in results: print(key,result)
    return int(any(result.startswith('failed:') for _,result in results))

if __name__=='__main__': raise SystemExit(main())
