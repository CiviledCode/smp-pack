"""Require isolated acceptance to use the pack's actual game/loader pins."""
from pathlib import Path
import re
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[3]
VERSIONS = tomllib.loads((ROOT / 'pack.toml').read_text())['versions']
EXPECTED = (VERSIONS['minecraft'], VERSIONS['fabric'])


def verify_template_runtime(source):
    with zipfile.ZipFile(source / 'fabric-server.jar') as archive:
        text = archive.read('install.properties').decode()
    properties = dict(line.split('=', 1) for line in text.splitlines()
                      if '=' in line and not line.lstrip().startswith('#'))
    actual = (properties.get('game-version'), properties.get('fabric-loader-version'))
    assert actual == EXPECTED, f'Template runtime {actual} differs from pack pins {EXPECTED}'


def runtime_is_pinned(log):
    observed = re.findall(r'Loading Minecraft (\S+) with Fabric Loader (\S+)',
                          log.read_text(errors='replace'))
    return bool(observed) and all(pair == EXPECTED for pair in observed)
