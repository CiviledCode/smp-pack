#!/usr/bin/env python3
"""Build the small Prism/MultiMC import ZIP; mods download at launch."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import tomllib
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BOOTSTRAP_URL = 'https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar'
BOOTSTRAP_SHA256 = 'a8fbb24dc604278e97f4688e82d3d91a318b98efc08d5dbfcbcbcab6443d116c'
DEFAULT_PACK_URL = 'https://raw.githubusercontent.com/CiviledCode/smp-pack/master/pack.toml'


def nbt_string(value):
    data = value.encode('utf-8')
    return struct.pack('>H', len(data)) + data


def server_list():
    # Uncompressed NBT: root compound -> servers list -> one compound.
    string = lambda key, value: b'\x08' + nbt_string(key) + nbt_string(value)
    server = string('name', 'GRXM SMP') + string('ip', 'mc.civiled.net') + b'\x00'
    return b'\x0a\x00\x00\x09' + nbt_string('servers') + b'\x0a' + struct.pack('>i', 1) + server + b'\x00'


def build(output, pack_url, bootstrap_file=None):
    if not pack_url.startswith(('https://', 'http://127.0.0.1:', 'http://localhost:')) or any(c in pack_url for c in '\r\n" '):
        raise ValueError('Pack URL must be HTTPS (or loopback HTTP for testing), without whitespace or quotes')
    pack = tomllib.loads((ROOT / 'pack.toml').read_text())
    if bootstrap_file:
        bootstrap = Path(bootstrap_file).read_bytes()
    else:
        with urllib.request.urlopen(BOOTSTRAP_URL, timeout=60) as response:
            bootstrap = response.read()
    if hashlib.sha256(bootstrap).hexdigest() != BOOTSTRAP_SHA256:
        raise ValueError('Bootstrap SHA256 mismatch; refusing to distribute')
    icon = (ROOT / 'assets' / 'grxmsmp.png').read_bytes()
    if icon[:8] != b'\x89PNG\r\n\x1a\n' or struct.unpack('>II', icon[16:24]) != (64, 64):
        raise ValueError('Pack/server icon must be a 64x64 PNG')
    cfg = '\n'.join([
        '[General]', 'InstanceType=OneSix', 'name=GRXM SMP', 'iconKey=grxmsmp',
        'OverrideCommands=true',
        f'PreLaunchCommand="$INST_JAVA" -jar packwiz-installer-bootstrap.jar -g {pack_url}',
        'OverrideMemory=true', 'MinMemAlloc=1024', 'MaxMemAlloc=6144',
        'OverrideJavaLocation=false', 'OverrideJavaArgs=false', '',
    ])
    components = {'formatVersion': 1, 'components': [
        {'uid': 'net.minecraft', 'version': pack['versions']['minecraft'], 'important': True},
        {'uid': 'net.fabricmc.fabric-loader', 'version': pack['versions']['fabric']},
    ]}
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('instance.cfg', cfg)
        archive.writestr('icon.png', icon)
        archive.writestr('mmc-pack.json', json.dumps(components, indent=2) + '\n')
        archive.writestr('.minecraft/packwiz-installer-bootstrap.jar', bootstrap)
        archive.writestr('.minecraft/servers.dat', server_list())
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise ValueError('ZIP integrity check failed')
        assert hashlib.sha256(archive.read('.minecraft/packwiz-installer-bootstrap.jar')).hexdigest() == BOOTSTRAP_SHA256
    print(f'{output.resolve()} ({output.stat().st_size} bytes)')
    print('SHA256:', hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'dist' / 'GRXM-SMP-Prism.zip')
    parser.add_argument('--pack-url', default=DEFAULT_PACK_URL)
    parser.add_argument('--bootstrap-file', type=Path, help='Use a local bootstrap; the same pinned SHA256 is enforced')
    args = parser.parse_args()
    build(args.output, args.pack_url, args.bootstrap_file)
