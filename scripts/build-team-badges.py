#!/usr/bin/env python3
"""Build the small Minecraft 1.20.1 graphical team-badge resource pack.

Editable PNGs and font mapping live in assets/team-badges. No client mod is needed.
"""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'assets/team-badges'
OUTPUT=ROOT/'assets/grxm-team-badges-1.0.0.zip'


def build():
    files=sorted(p for p in SOURCE.rglob('*') if p.is_file())
    if not files or not (SOURCE/'pack.mcmeta').exists():
        raise ValueError('Missing resource-pack source')
    with zipfile.ZipFile(OUTPUT,'w',compression=zipfile.ZIP_DEFLATED) as archive:
        for p in files:
            info=zipfile.ZipInfo(p.relative_to(SOURCE).as_posix(),date_time=(2020,1,1,0,0,0))
            info.compress_type=zipfile.ZIP_DEFLATED
            archive.writestr(info,p.read_bytes())
    with zipfile.ZipFile(OUTPUT) as archive:
        assert archive.testzip() is None
        assert json.loads(archive.read('pack.mcmeta'))['pack']['pack_format']==15
        font=json.loads(archive.read('assets/grxmteams/font/badges.json'))
        glyphs=[]
        for provider in font['providers']:
            glyphs.extend(''.join(provider['chars']))
            namespace,path=provider['file'].split(':',1)
            assert f'assets/{namespace}/textures/{path}' in archive.namelist()
        assert len(glyphs)==8 and len(set(glyphs))==8
    data=OUTPUT.read_bytes()
    print(OUTPUT)
    print('SHA1:',hashlib.sha1(data).hexdigest())
    print('SHA256:',hashlib.sha256(data).hexdigest())


if __name__=='__main__':build()
