#!/usr/bin/env python3
"""Run real vanilla menu packet regressions in a NEW marked isolated server.
Usage: menu_integration_suite.py MARKED_TEMPLATE NEW_SCRATCH_OUTPUT
Build first: ./gradlew build remapIntegrationJar --no-daemon
"""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile
from fixture_runtime import verify_template_runtime, runtime_is_pinned

source = pathlib.Path(sys.argv[1]).resolve()
out = pathlib.Path(sys.argv[2]).resolve()
assert (source / 'grxmteams-integration-ALLOW').is_file(), 'not an isolated template'
verify_template_runtime(source)
assert not out.exists(), 'output must be fresh'
assert out.is_relative_to(pathlib.Path(os.environ['TMPDIR']).resolve()), 'scratch only'
mod = pathlib.Path(__file__).resolve().parents[1]
port = int(os.environ.get('GRXM_MENU_TEST_PORT', '25591'))
assert 1024 <= port <= 65000
out.mkdir(parents=True)
for name in ('libraries', 'versions', 'config', 'defaultconfigs'):
    if (source / name).exists():
        shutil.copytree(source / name, out / name)
(out / 'config/grxmteams.json').unlink(missing_ok=True)
for name in ('fabric-server.jar', 'eula.txt'):
    shutil.copy2(source / name, out / name)
overrides = {'level-name': 'menu-test-world', 'server-ip': '127.0.0.1',
             'server-port': str(port), 'online-mode': 'true', 'enable-rcon': 'false'}
props = [line for line in (source / 'server.properties').read_text().splitlines()
         if line.split('=', 1)[0] not in overrides]
(out / 'server.properties').write_text('\n'.join(props + [k + '=' + v for k, v in overrides.items()]) + '\n')
voice = out / 'config/voicechat/voicechat-server.properties'
if voice.exists():
    voice.write_text('\n'.join('port=' + str(port + 100) if line.startswith('port=') else line
                               for line in voice.read_text().splitlines()) + '\n')
(out / 'grxmteams-integration-ALLOW').touch()
(out / 'mods').mkdir()
for jar in (source / 'mods').glob('*.jar'):
    if 'grxm-teams' not in jar.name:
        (out / 'mods' / jar.name).symlink_to(jar.resolve())
shutil.copy2(mod / 'build/libs/grxm-teams-1.1.0.jar', out / 'mods/grxm-teams-1.1.0.jar')
# Reuse remapped test classes, replacing ONLY the isolated test entrypoint in a scratch JAR.
with zipfile.ZipFile(mod / 'build/libs/grxm-teams-1.1.0-integration-test.jar') as src:
    with zipfile.ZipFile(out / 'mods/grxm-teams-menu-test.jar', 'w') as dest:
        for entry in src.infolist():
            data = src.read(entry.filename)
            if entry.filename == 'fabric.mod.json':
                manifest = json.loads(data)
                manifest['entrypoints']['main'] = ['dev.grxm.teams.integration.MenuIntegration']
                data = json.dumps(manifest).encode()
            dest.writestr(entry, data)
with (out / 'menu-test.log').open('w') as log:
    result = subprocess.run([os.environ.get('JAVA17', '/usr/lib/jvm/java-17-openjdk-amd64/bin/java'),
                             '-Xms512M', '-Xmx3G', '-Dgrxmteams.menuIntegration=true',
                             '-jar', 'fabric-server.jar', 'nogui'], cwd=out, stdout=log,
                            stderr=subprocess.STDOUT, timeout=300)
proof = out / 'grxmteams-menu-PASS.txt'
failed = out / 'grxmteams-menu-FAIL.txt'
summary = {'exit': result.returncode, 'pass': proof.read_text() if proof.exists() else None,
           'failure': failed.read_text() if failed.exists() else None, 'log': str(out / 'menu-test.log'),
           'runtime_pinned': runtime_is_pinned(out / 'menu-test.log')}
(out / 'results.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary, indent=2))
assert result.returncode == 0 and proof.exists() and not failed.exists() and summary['runtime_pinned'], 'Menu regression failed'
