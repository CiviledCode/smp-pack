#!/usr/bin/env python3
"""Package canonical badge assets as a deterministic, code-free client mod."""
import hashlib
import json
from pathlib import Path
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCE = ROOT / 'assets/team-badges'
OUTPUT = ROOT / 'assets/mods/grxm-team-badges-1.0.0.jar'


def build():
    metadata = json.loads((HERE / 'fabric.mod.json').read_text())
    assert metadata['environment'] == 'client' and not metadata.get('entrypoints')
    entries = {p.relative_to(SOURCE).as_posix(): p.read_bytes()
               for p in SOURCE.rglob('*') if p.is_file()}
    with zipfile.ZipFile(ROOT / 'assets/grxm-team-badges-1.0.0.zip') as canonical:
        assert entries == {n: canonical.read(n) for n in canonical.namelist()}, 'Canonical asset mismatch'
    assert json.loads(entries['pack.mcmeta'])['pack']['pack_format'] == 15
    font = json.loads(entries['assets/grxmteams/font/badges.json'])
    glyphs = []
    for provider in font['providers']:
        namespace, path = provider['file'].split(':', 1)
        image = entries[f'assets/{namespace}/textures/{path}']
        assert image.startswith(b'\x89PNG\r\n\x1a\n')
        glyphs.extend(''.join(provider['chars']))
    assert len(glyphs) == len(set(glyphs)) == 8
    entries['fabric.mod.json'] = (HERE / 'fabric.mod.json').read_bytes()
    entries['LICENSE'] = (HERE / 'LICENSE').read_bytes()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUTPUT, 'w', compression=zipfile.ZIP_DEFLATED) as jar:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            jar.writestr(info, data)
    with zipfile.ZipFile(OUTPUT) as jar:
        assert jar.testzip() is None
        assert not any(n.endswith('.class') for n in jar.namelist())
    print(OUTPUT)
    print('SHA256:', hashlib.sha256(OUTPUT.read_bytes()).hexdigest())
    print('Verified: canonical assets unchanged; eight unique glyphs; no executable classes')


if __name__ == '__main__':
    build()
