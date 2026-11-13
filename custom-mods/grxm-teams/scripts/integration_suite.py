#!/usr/bin/env python3
"""Clone a marked isolated pack template, never its world. Run Java 17 phases.
Usage: integration_suite.py TEMPLATE SCRATCH_ROOT [seed|bazaar|cuts|all]
TEMPLATE must contain grxmteams-integration-ALLOW. Output dirs must not exist.
"""
import pathlib, shutil, subprocess, sys, json, os
TEMPLATE=pathlib.Path(sys.argv[1]).resolve(); ROOT=pathlib.Path(sys.argv[2]).resolve()
MODE=sys.argv[3] if len(sys.argv)>3 else 'all'
PORT=int(os.environ.get('GRXM_TEST_PORT','25589'));assert 1024<=PORT<=65000
assert (TEMPLATE/'grxmteams-integration-ALLOW').is_file(), 'not an isolated template'
assert not ROOT.exists(), 'output must be a fresh directory'
assert MODE in ('seed','bazaar','cuts','failure','all'), 'unknown phase group'
assert ROOT.is_relative_to(pathlib.Path(os.environ.get('TMPDIR','/home/civ/.hermes/cache/scratch')).resolve()), 'output must be scratch'
MOD=pathlib.Path(__file__).resolve().parents[1]
JAVA=os.environ.get('JAVA17','/usr/lib/jvm/java-17-openjdk-amd64/bin/java')
ROOT.mkdir(parents=True)
def fixture(name):
    out=ROOT/name;out.mkdir()
    for n in ('libraries','versions'):
        shutil.copytree(TEMPLATE/n,out/n,symlinks=False)
    for n in ('config','defaultconfigs'):
        if (TEMPLATE/n).exists():shutil.copytree(TEMPLATE/n,out/n,symlinks=False)
    (out/'config/grxmteams.json').unlink(missing_ok=True)
    voice=out/'config/voicechat/voicechat-server.properties'
    if voice.exists():voice.write_text('\n'.join('port='+str(PORT+100) if line.startswith('port=') else line for line in voice.read_text().splitlines())+'\n')
    for n in ('fabric-server.jar','eula.txt'):
        shutil.copy2(TEMPLATE/n,out/n)
    props=(TEMPLATE/'server.properties').read_text().splitlines()
    overrides={'level-name':'integration-world','server-ip':'127.0.0.1','server-port':str(PORT),'enable-rcon':'false','online-mode':'true'}
    props=[line for line in props if line.split('=',1)[0] not in overrides]
    (out/'server.properties').write_text('\n'.join(props+[k+'='+v for k,v in overrides.items()])+'\n')
    (out/'grxmteams-integration-ALLOW').touch();(out/'mods').mkdir()
    for p in (TEMPLATE/'mods').glob('*.jar'):
        if 'grxm-teams' not in p.name:(out/'mods'/p.name).symlink_to(p.resolve())
    for n in ('grxm-teams-1.0.0.jar','grxm-teams-1.0.0-integration-test.jar'):
        shutil.copy2(MOD/'build/libs'/n,out/'mods'/n)
    return out
summary=[]
def run(out,phase,cut=False):
    log=out/(phase+'.log')
    with log.open('w') as f:
        p=subprocess.run([JAVA,'-Xms512M','-Xmx3G','-Dgrxmteams.integration=true','-Dgrxmteams.integration.phase='+phase,'-jar','fabric-server.jar','nogui'],cwd=out,stdout=f,stderr=subprocess.STDOUT,timeout=300)
    fail=out/'grxmteams-integration-FAIL.txt'
    proof=out/('grxmteams-integration-'+phase+('-CUT.txt' if cut else '-PASS.txt'))
    result={'directory':str(out),'phase':phase,'exit':p.returncode,'proof':proof.read_text() if proof.exists() else None,'fail':fail.read_text() if fail.exists() else None}
    summary.append(result);(ROOT/'results.json').write_text(json.dumps(summary,indent=2));print(json.dumps(result),flush=True)
    if p.returncode!=(23 if cut else 0) or not proof.exists() or fail.exists():raise SystemExit('FAILED: '+str(log))
if MODE in ('seed','all'):
    out=fixture('rent');run(out,'seed');run(out,'verify');run(out,'expire')
if MODE in ('bazaar','all'):
    out=fixture('bazaar');run(out,'bazaar');run(out,'bazaar-verify')
if MODE in ('cuts','failure','all'):
    for phase in (() if MODE=='failure' else ('cut-prepared','cut-player','cut-committed')):
        out=fixture(phase);run(out,phase,True);run(out,'recover-cut')
    out=fixture('cut-failed-commit');run(out,'cut-failed-commit')
    bank=out/'integration-world/data/numismatics_bank.dat'
    assert bank.is_dir(), 'fixture did not exercise atomic replacement refusal'
    bank.rmdir();(bank.parent/'numismatics_bank.prepared-evidence').rename(bank)
    run(out,'recover-cut')
