"""Fetch the official relay source and a verified, portable Bun runtime on Windows."""
from pathlib import Path
import hashlib
import json
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1]
tools = root / '.tools'
upstream = root / 'relay' / 'upstream'
tools.mkdir(exist_ok=True)
upstream.mkdir(parents=True, exist_ok=True)
revision = 'bf6fdd1625d8a76eafc97c38c660c6a3fd71c1d1'


def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'CoyoteVision-build'}), timeout=90)


for name in ['v3-server.ts', 'LICENSE', 'README_CN.md']:
    target = upstream / name
    if not target.exists():
        target.write_bytes(fetch(f'https://raw.githubusercontent.com/dungeonlab-open/dglab-websocket-server/{revision}/{name}').read())
(upstream / 'UPSTREAM.txt').write_text(f'Source: https://github.com/dungeonlab-open/dglab-websocket-server\nCommit: {revision}\nLicense: GPL-3.0; see LICENSE.\nFiles preserved without modification.\n', encoding='utf-8')

exe = tools / 'bun-windows-x64-baseline' / 'bun.exe'
if not exe.exists():
    release = json.load(fetch('https://api.github.com/repos/oven-sh/bun/releases/latest'))
    asset = next(a for a in release['assets'] if a['name'] == 'bun-windows-x64-baseline.zip')
    expected = asset['digest'].removeprefix('sha256:')
    data = fetch(asset['browser_download_url']).read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError('Bun checksum mismatch')
    archive = tools / 'bun.zip'
    archive.write_bytes(data)
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(tools)
print('Official V3 relay and portable Bun ready', flush=True)
