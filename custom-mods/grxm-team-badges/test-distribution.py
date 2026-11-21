#!/usr/bin/env python3
"""Exercise real packwiz client/repeat/server installation of the local badge JAR.

Uses a minimal scratch pack with the production metadata's hash, filename and side,
but a loopback HTTP artifact URL. No unpublished public URL or production server is used.
"""
import argparse
import hashlib
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import threading
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BOOTSTRAP_SHA256 = 'a8fbb24dc604278e97f4688e82d3d91a318b98efc08d5dbfcbcbcab6443d116c'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path, help='Fresh directory under the system TMPDIR/scratch')
    parser.add_argument('--java', default='/usr/lib/jvm/java-17-openjdk-amd64/bin/java')
    args = parser.parse_args()
    output = args.output.resolve()
    import os
    scratch = Path(os.environ['TMPDIR']).resolve()
    assert output.is_relative_to(scratch) and output != scratch, 'Output must be under TMPDIR'
    output.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(ROOT / 'launcher/GRXM-SMP-Prism.zip') as archive:
        bootstrap = archive.read('.minecraft/packwiz-installer-bootstrap.jar')
    assert hashlib.sha256(bootstrap).hexdigest() == BOOTSTRAP_SHA256
    bootstrap_path = output / 'packwiz-installer-bootstrap.jar'
    bootstrap_path.write_bytes(bootstrap)
    original = (ROOT / 'mods/grxm-team-badges.pw.toml').read_text()
    meta = tomllib.loads(original)
    artifact = ROOT / 'assets/mods' / meta['filename']
    assert hashlib.new(meta['download']['hash-format'], artifact.read_bytes()).hexdigest() == meta['download']['hash']
    mirror = output / 'mirror'
    (mirror / 'mods').mkdir(parents=True)
    (mirror / meta['filename']).write_bytes(artifact.read_bytes())
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(SimpleHTTPRequestHandler, directory=str(mirror)))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    import atexit
    atexit.register(server.server_close)
    atexit.register(server.shutdown)
    local_url = f'http://127.0.0.1:{server.server_port}/{meta["filename"]}'
    # Installer v0.5.14 accepts file: manifests but requires HTTP(S) artifact URLs.
    local_meta = original.replace(meta['download']['url'], local_url)
    (mirror / 'mods/grxm-team-badges.pw.toml').write_text(local_meta)
    index = ('hash-format = "sha256"\n\n[[files]]\nfile = "mods/grxm-team-badges.pw.toml"\n'
             f'hash = "{hashlib.sha256(local_meta.encode()).hexdigest()}"\nmetafile = true\n')
    (mirror / 'index.toml').write_text(index)
    pack = tomllib.loads((ROOT / 'pack.toml').read_text())
    (mirror / 'pack.toml').write_text(
        'name = "GRXM badge isolated distribution test"\npack-format = "packwiz:1.1.0"\nversion = "local-test"\n'
        '[index]\nfile = "index.toml"\nhash-format = "sha256"\n'
        f'hash = "{hashlib.sha256(index.encode()).hexdigest()}"\n[versions]\n'
        f'minecraft = "{pack["versions"]["minecraft"]}"\nfabric = "{pack["versions"]["fabric"]}"\n')
    results = []
    for side, attempt in [('client', 1), ('client', 2), ('server', 1)]:
        instance = output / side
        instance.mkdir(exist_ok=True)
        command = [args.java, '-jar', str(bootstrap_path), '-g', '-s', side, (mirror / 'pack.toml').as_uri()]
        result = subprocess.run(command, cwd=instance, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=180)
        (output / f'{side}-{attempt}.log').write_text(result.stdout)
        assert result.returncode == 0, f'{side} attempt {attempt}: exit {result.returncode}; see log'
        installed = instance / 'mods' / meta['filename']
        if side == 'client':
            assert installed.read_bytes() == artifact.read_bytes(), 'Installed JAR differs'
        else:
            assert not installed.exists(), 'Client-only JAR installed on server'
        results.append({'side': side, 'attempt': attempt, 'exit': result.returncode,
                        'badge_installed': installed.exists()})
        print('PASS:', results[-1])
    (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    print('Evidence:', output)


if __name__ == '__main__':
    main()
