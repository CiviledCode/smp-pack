#!/usr/bin/env python3
"""Verify whole-bank corruption/missing-file guards on copies of a completed isolated fixture."""
import hashlib,json,os,pathlib,shutil,subprocess,sys
source=pathlib.Path(sys.argv[1]).resolve();root=pathlib.Path(sys.argv[2]).resolve()
assert (source/'grxmteams-integration-ALLOW').is_file()
assert not root.exists() and root.is_relative_to(pathlib.Path(os.environ.get('TMPDIR','/home/civ/.hermes/cache/scratch')).resolve())
java=os.environ.get('JAVA17','/usr/lib/jvm/java-17-openjdk-amd64/bin/java')
root.mkdir();results=[]
for case in ('truncated','missing','native-load-fallback','legacy-native-load-fallback'):
    out=root/case;shutil.copytree(source,out,symlinks=True)
    bank=out/'integration-world/data/numismatics_bank.dat';guard=bank.parent/'grxmteams-initialized.guard';assert bank.is_file() and guard.is_file()
    if case in ('native-load-fallback','legacy-native-load-fallback'):
        with (out/'damage-native.log').open('w') as log:
            damage=subprocess.run([java,'-Xms512M','-Xmx3G','-Dgrxmteams.integration=true','-Dgrxmteams.integration.phase=damage-native','-Dgrxmteams.integration.damageLegacy='+str(case.startswith('legacy-')).lower(),'-jar','fabric-server.jar','nogui'],cwd=out,stdout=log,stderr=subprocess.STDOUT,timeout=300)
        assert damage.returncode==23 and (out/'grxmteams-integration-damage-native-CUT.txt').exists(), 'native corruption fixture failed'
    elif case=='truncated':bank.write_bytes(b'\x1f\x8b\x08\x00TRUNCATED_BANK_EVIDENCE')
    elif case=='missing':bank.unlink()
    before=bank.read_bytes() if bank.exists() else None
    with (out/'guard-test.log').open('w') as log:
        result=subprocess.run([java,'-Xms512M','-Xmx3G','-Dgrxmteams.integration=true','-Dgrxmteams.integration.phase=corrupt','-jar','fabric-server.jar','nogui'],cwd=out,stdout=log,stderr=subprocess.STDOUT,timeout=300)
    text=(out/'guard-test.log').read_text();after=bank.read_bytes() if bank.is_file() else None
    refusal='Bank preflight refused'
    assert refusal in text and before==after,'guard did not refuse/preserve '+str(out)
    proof={'case':case,'exit':result.returncode,'preserved':True,'sha256':hashlib.sha256(after).hexdigest() if after else None}
    results.append(proof);print(json.dumps(proof),flush=True)
(root/'results.json').write_text(json.dumps(results,indent=2))
