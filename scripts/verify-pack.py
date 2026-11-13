#!/usr/bin/env python3
"""Verify the packwiz trust chain and optionally download every pinned mod."""
import argparse
import concurrent.futures
import hashlib
from pathlib import Path, PurePosixPath
import tomllib
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def digest(data, algorithm):
    return hashlib.new(algorithm, data).hexdigest()


def verify(download=False):
    pack = tomllib.loads((ROOT / 'pack.toml').read_text())
    index_data = (ROOT / pack['index']['file']).read_bytes()
    assert digest(index_data, pack['index']['hash-format']) == pack['index']['hash'], 'Index hash mismatch'
    index = tomllib.loads(index_data.decode())
    seen, filenames, mods = set(), set(), []
    for entry in index['files']:
        relative = PurePosixPath(entry['file'])
        assert not relative.is_absolute() and '..' not in relative.parts, f'Unsafe index path: {relative}'
        assert entry['file'] not in seen, f'Duplicate index entry: {relative}'
        seen.add(entry['file'])
        data = (ROOT / str(relative)).read_bytes()
        assert digest(data, index['hash-format']) == entry['hash'], f'Payload hash mismatch: {relative}'
        assert relative.parts[0] in {'mods', 'config', 'resourcepacks'}, f'Non-game payload in index: {relative}'
        if entry.get('metafile'):
            meta = tomllib.loads(data.decode())
            assert meta.get('side') in {'client', 'server', 'both'}, f'Invalid side: {relative}'
            key = (relative.parent, meta['filename'])
            assert key not in filenames, f'Duplicate mod filename: {key}'
            filenames.add(key)
            assert meta['download']['url'].startswith('https://'), f'Non-HTTPS/manual download: {relative}'
            mods.append(meta)
    if download:
        def check(meta):
            request = urllib.request.Request(meta['download']['url'], headers={'User-Agent': 'CiviledCode/smp-pack verification'})
            with urllib.request.urlopen(request, timeout=120) as response:
                data = response.read()
            assert digest(data, meta['download']['hash-format']) == meta['download']['hash'], f'Download hash mismatch: {meta["name"]}'
            return meta['name']
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            for name in pool.map(check, mods):
                print('Verified download:', name)
    print(f'Verified pack {pack["version"]}: {len(seen)} indexed files, {len(mods)} pinned downloads')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true')
    verify(parser.parse_args().download)
