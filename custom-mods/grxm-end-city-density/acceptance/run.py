#!/usr/bin/env python3
"""Run disposable same-seed full server-side pack A/B, never reuse a world.
Usage: python3 run.py SCRATCH_ROOT [baseline|density|negative]
Before use build ../build.py and acceptance's Gradle build; download pinned
Fabric server launcher to SCRATCH_ROOT/fabric-server.jar (see README).
"""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, sys, tomllib, urllib.request, zipfile
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2]
ROOT=Path(sys.argv[1]).resolve()
MODE=sys.argv[2] if len(sys.argv)>2 else 'baseline'
assert MODE in ('baseline','density','negative')
assert ROOT.is_relative_to(Path(os.environ['TMPDIR']).resolve())
OUT=ROOT/MODE
assert not OUT.exists(), 'fresh fixture only'
OUT.mkdir(parents=True);(OUT/'mods').mkdir()
with zipfile.ZipFile(ROOT/'fabric-server.jar') as z:
    props=z.read('install.properties').decode()
assert 'game-version=1.20.1' in props and 'fabric-loader-version=0.18.3' in props
shutil.copy2(ROOT/'fabric-server.jar',OUT/'fabric-server.jar')
shutil.copytree(REPO/'config',OUT/'config')
(OUT/'density-fixture-ALLOW').touch()
(OUT/'eula.txt').write_text('eula=true\n')
(OUT/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25647\nonline-mode=true\nenable-rcon=false\nlevel-name=fixture-world\nlevel-seed=8675309\nview-distance=2\nsimulation-distance=2\nmax-tick-time=-1\nspawn-protection=0\n')
voice=OUT/'config/voicechat/voicechat-server.properties'
if voice.exists():
    voice.write_text('\n'.join('port=25648' if l.startswith('port=') else l for l in voice.read_text().splitlines())+'\n')
CACHE=Path('/home/civ/.hermes/cache/scratch/smp-1.6.1-public-client/launcher/.minecraft/mods')
ACQUIRED=ROOT/'acquired';ACQUIRED.mkdir(exist_ok=True)
inventory=[]
for manifest in sorted((REPO/'mods').glob('*.pw.toml')):
    m=tomllib.loads(manifest.read_text()); name=m['filename']
    if m.get('side')=='client':continue
    if name.startswith('grxm-end-city-density') and MODE!='density':continue
    sources=[CACHE/name, REPO/'assets/mods'/name, ACQUIRED/name]
    src=next((p for p in sources if p.exists()),None)
    if src is None:
        src=ACQUIRED/name
        urllib.request.urlretrieve(m['download']['url'],src)
    digest=hashlib.new(m['download']['hash-format'],src.read_bytes()).hexdigest()
    assert digest==m['download']['hash'],name
    with zipfile.ZipFile(src) as z:
        metadata=json.loads(z.read('fabric.mod.json'),strict=False)
    # Loader also filters environment; avoid placing client-only artifacts at all.
    if metadata.get('environment')=='client':continue
    shutil.copy2(src,OUT/'mods'/name)
    inventory.append({'file':name,'sha256':hashlib.sha256(src.read_bytes()).hexdigest()})
shutil.copy2(HERE/'build/libs/grxm-density-acceptance-1.0.0.jar',OUT/'mods/grxm-density-acceptance-1.0.0.jar')
(OUT/'inventory.json').write_text(json.dumps(inventory,indent=2))
expected=14 if MODE in ('density','negative') else 20
cmd=['/usr/lib/jvm/java-17-openjdk-amd64/bin/java','-Xms512M','-Xmx4G','-Ddensity.expected='+str(expected),'-jar','fabric-server.jar','nogui']
(OUT/'command.json').write_text(json.dumps(cmd))
with (OUT/'console.log').open('w') as log:
    proc=subprocess.run(cmd,cwd=OUT,stdout=log,stderr=subprocess.STDOUT,timeout=600)
text=(OUT/'console.log').read_text(errors='replace')
assert 'Loading Minecraft 1.20.1 with Fabric Loader 0.18.3' in text
if MODE=='negative':
    assert (OUT/'density-FAIL.txt').read_text().startswith('java.lang.AssertionError: Wrong active placement: 20')
    assert not (OUT/'density-results.json').exists()
    print('PASS negative: missing density module rejected by active registry assertion')
else:
    assert proc.returncode==0 and not (OUT/'density-FAIL.txt').exists(),str(OUT/'console.log')
    result=json.loads((OUT/'density-results.json').read_text())
    assert result['valid_starts']>0
    assert sum(r['valid'] for r in result['rows'])==result['valid_starts']
    print(json.dumps({k:v for k,v in result.items() if k not in ('rows','allowed_biomes','terrain_control')},indent=2))
