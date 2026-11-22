#!/usr/bin/env python3
"""Build a deterministic, code-free Fabric server resource mod (stdlib only)."""
from pathlib import Path
import hashlib
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
TARGET = ROOT / 'assets/mods/grxm-end-city-density-1.0.0.jar'
TARGET.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(TARGET, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted((HERE / 'resources').rglob('*')):
        if path.is_file():
            info = zipfile.ZipInfo(path.relative_to(HERE / 'resources').as_posix(), (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes(), compresslevel=9)
print(TARGET)
print('sha256', hashlib.sha256(TARGET.read_bytes()).hexdigest())
print('sha512', hashlib.sha512(TARGET.read_bytes()).hexdigest())
