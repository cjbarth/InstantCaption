#!/usr/bin/env python3
"""Generic offline helper: propose aligned EPUB paragraphs using shared IDs.

Matching identifiers do not establish translation equivalence. Review each row.
No site access or scraping. No publication content is included.
"""
import argparse
import collections
import csv
import posixpath
import re
import sys
import zipfile
from urllib.parse import unquote
from xml.etree import ElementTree as ET

MAX_XML = 4 * 1024 * 1024
MAX_TOTAL = 32 * 1024 * 1024


def paragraphs(path):
    with zipfile.ZipFile(path) as z:
        total = 0
        def xml(name):
            nonlocal total
            info = z.getinfo(name)
            total += info.file_size
            if info.file_size > MAX_XML or total > MAX_TOTAL:
                raise ValueError('EPUB XML exceeds safety limit')
            return ET.fromstring(z.read(info))
        container = xml('META-INF/container.xml')
        opf = container.find('.//{*}rootfile').attrib['full-path']
        package = xml(opf)
        manifest = {e.attrib['id']: e.attrib['href'] for e in package.findall('.//{*}manifest/{*}item') if e.attrib.get('media-type') == 'application/xhtml+xml'}
        rows = collections.defaultdict(list)
        for ref in package.findall('.//{*}spine/{*}itemref'):
            if ref.attrib.get('idref') not in manifest:
                continue
            chapter = posixpath.normpath(posixpath.join(posixpath.dirname(opf), unquote(manifest[ref.attrib['idref']].split('#',1)[0])))
            root = xml(chapter)
            for p in root.findall('.//{*}p'):
                identifier = p.attrib.get('id')
                if not identifier:
                    continue
                text = re.sub(r'\s+', ' ', ''.join(p.itertext())).strip()
                if text and len(text.encode('utf-16-le')) // 2 <= 360:
                    rows[(posixpath.basename(chapter), identifier)].append(text)
        return {key: values[0] for key, values in rows.items() if len(values) == 1}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('source_epub'); p.add_argument('target_epub')
    p.add_argument('source_lang'); p.add_argument('target_lang')
    p.add_argument('output', help='Candidate TSV, not yet reviewed')
    a = p.parse_args()
    if not all(re.fullmatch('[a-z]{2,3}', x) for x in [a.source_lang,a.target_lang]) or a.source_lang == a.target_lang:
        p.error('Provide different 2–3 letter lowercase language codes')
    source, target = paragraphs(a.source_epub), paragraphs(a.target_epub)
    keys = [key for key in source if key in target]
    if not keys:
        raise SystemExit('No shared paragraph identifiers found. Align passages manually; no positional guessing was performed.')
    with open(a.output,'x',encoding='utf-8',newline='') as f:
        f.write('# UNREVIEWED candidates: verify that each pair is equivalent before import.\n')
        writer = csv.writer(f,delimiter='\t',quoting=csv.QUOTE_NONE,lineterminator='\n',quotechar=None)
        for key in keys:
            writer.writerow(['passage',a.source_lang,a.target_lang,source[key],target[key]])
    print(f'{len(keys)} candidates written from {len(source)} source and {len(target)} target paragraphs. Review manually; app import is limited to 1 MiB / 5000 rows.',file=sys.stderr)

if __name__=='__main__': main()
