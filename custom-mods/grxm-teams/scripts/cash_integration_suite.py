#!/usr/bin/env python3
"""Disposable cash integration. Run happy first, then cut groups separately.
Usage: cash_integration_suite.py MARKED_TEMPLATE NEW_SCRATCH_OUTPUT [happy|deposit|withdraw|fault]
Cuts require CASH_SEED=/absolute/path/to/prior/happy/output/seed.
"""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile
from fixture_runtime import verify_template_runtime, runtime_is_pinned

source=pathlib.Path(sys.argv[1]).resolve()
out=pathlib.Path(sys.argv[2]).resolve()
group=sys.argv[3] if len(sys.argv)>3 else 'happy'
assert group in ('happy','deposit','withdraw','fault')
assert (source/'grxmteams-integration-ALLOW').is_file(), 'isolated template marker required'
verify_template_runtime(source)
assert not out.exists(), 'fresh output required'
assert out.is_relative_to(pathlib.Path(os.environ['TMPDIR']).resolve()), 'scratch only'
mod=pathlib.Path(__file__).resolve().parents[1]
port=int(os.environ.get('GRXM_CASH_TEST_PORT','25961'))
assert 25961<=port<=64000
out.mkdir(parents=True)
results=[]

def fixture(name,seed=None):
    target=out/name
    if seed:
        assert (seed/'seed-PASS.txt').is_file() and (seed/'grxmteams-integration-ALLOW').is_file()
        shutil.copytree(seed,target,symlinks=True)
        # Mutable launcher caches must never be shared across independent processes.
    else:
        target.mkdir()
        for folder in ('libraries','versions','config','defaultconfigs'):
            if (source/folder).exists():shutil.copytree(source/folder,target/folder)
        (target/'config/grxmteams.json').unlink(missing_ok=True)
        for file in ('fabric-server.jar','eula.txt'):shutil.copy2(source/file,target/file)
        (target/'mods').mkdir()
        for jar in (source/'mods').glob('*.jar'):
            if 'grxm-teams' not in jar.name:(target/'mods'/jar.name).symlink_to(jar.resolve())
        (target/'grxmteams-integration-ALLOW').touch()
    for prior in (target/'mods').glob('grxm-teams-*.jar'):prior.unlink()
    shutil.copy2(mod/'build/libs/grxm-teams-1.1.0.jar',target/'mods/grxm-teams-1.1.0.jar')
    with zipfile.ZipFile(mod/'build/libs/grxm-teams-1.1.0-integration-test.jar') as src:
        with zipfile.ZipFile(target/'mods/grxm-teams-cash-test.jar','w') as dst:
            for entry in src.infolist():
                data=src.read(entry.filename)
                if entry.filename=='fabric.mod.json':
                    manifest=json.loads(data);manifest['entrypoints']['main']=['dev.grxm.teams.integration.CashIntegration'];data=json.dumps(manifest).encode()
                dst.writestr(entry,data)
    overrides={'level-name':'cash-test-world','server-ip':'127.0.0.1','server-port':str(port),'online-mode':'true','enable-rcon':'false'}
    lines=[s for s in (source/'server.properties').read_text().splitlines() if s.split('=',1)[0] not in overrides]
    (target/'server.properties').write_text('\n'.join(lines+[k+'='+v for k,v in overrides.items()])+'\n')
    voice=target/'config/voicechat/voicechat-server.properties'
    if voice.exists():voice.write_text('\n'.join('port='+str(port+100) if s.startswith('port=') else s for s in voice.read_text().splitlines())+'\n')
    return target

def run(target,phase,cut=False):
    with (target/(phase+'.log')).open('w') as log:
        p=subprocess.run([os.environ.get('JAVA17','/usr/lib/jvm/java-17-openjdk-amd64/bin/java'),'-Xms512M','-Xmx3G','-Dgrxmteams.cashIntegration=true','-Dgrxmteams.cash.phase='+phase,'-jar','fabric-server.jar','nogui'],cwd=target,stdout=log,stderr=subprocess.STDOUT,timeout=240)
    success=(p.returncode==23 and (target/'cash-CUT.txt').read_text()==phase) if cut else (p.returncode==0 and (target/(phase+'-PASS.txt')).exists())
    pinned=runtime_is_pinned(target/(phase+'.log'))
    success=success and not list(target.glob('*-FAIL.txt')) and pinned
    result={'phase':phase,'exit':p.returncode,'pass':success,'path':str(target),'runtime_pinned':pinned}
    detail=target/('recovery-details.txt' if phase=='recover' else 'fault-details.txt')
    if detail.exists():result['proof']=detail.read_text()
    results.append(result);(out/'results.json').write_text(json.dumps(results,indent=2));print(json.dumps(result),flush=True)
    assert success, 'cash phase failed; see '+str(target/(phase+'.log'))

if group=='happy':
    seed=fixture('seed');run(seed,'seed')
    happy=fixture('happy',seed);run(happy,'happy');run(happy,'verify')
    cross=fixture('cross',seed);run(cross,'cross')
else:
    seed=pathlib.Path(os.environ['CASH_SEED']).resolve()
    phases=[group+'-'+cut for cut in ('PREPARED','PLAYER_SAVED','COMMITTED')] if group!='fault' else ['deposit-FAULT','withdraw-FAULT']
    for phase in phases:
        target=fixture(phase,seed);run(target,phase,group!='fault')
        if group!='fault':run(target,'recover')
