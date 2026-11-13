#!/usr/bin/env python3
"""Exercise real restic failure/retry/retention/restore against a disposable local repo.

Requires restic and permission to use /run/lock/smp-offsite.lock (normally root).
Never reads the production backup environment or repository.
"""
from datetime import datetime
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
cache=Path.home()/'.cache/smp-tests';cache.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(dir=cache) as td:
    base=Path(td);snapshots=base/'snapshots';password=base/'password'
    password.write_text(secrets.token_hex(32));password.chmod(0o600)
    stamps=['20261110T010000Z','20261111T010000Z','20261112T010000Z']
    for stamp in stamps:
        p=snapshots/stamp
        (p/'server/world').mkdir(parents=True);(p/'runtime').mkdir()
        (p/'server/world/level.dat').write_bytes(stamp.encode())
    config=base/'env'
    config.write_text(f'RESTIC_REPOSITORY={base}/repository\nRESTIC_PASSWORD_FILE={password}\n')
    config.chmod(0o600)
    env=dict(os.environ,SMP_BACKUP_ENV=str(config),SMP_SNAPSHOT_ROOT=str(snapshots),RESTIC_REPOSITORY=str(base/'repository'),RESTIC_PASSWORD_FILE=str(password))
    uploader=['bash',str(ROOT/'server/smp-offsite')]
    failed=subprocess.run(uploader,env=env,capture_output=True,text=True)
    assert failed.returncode!=0,'Missing repository was incorrectly accepted'
    assert not any(snapshots.rglob('.uploaded')),'Failed upload marked completed'
    subprocess.run(['restic','init'],env=env,check=True,stdout=subprocess.DEVNULL)
    subprocess.run(uploader,env=env,check=True,stdout=subprocess.DEVNULL)
    rows=json.loads(subprocess.check_output(['restic','snapshots','--json'],env=env,text=True))
    assert len(rows)==3,rows
    assert {datetime.fromisoformat(x['time']).strftime('%Y%m%dT%H%M%SZ') for x in rows}==set(stamps)
    assert all((snapshots/s/'.uploaded').exists() for s in stamps)
    subprocess.run(['restic','restore',rows[-1]['id'],'--target',str(base/'restore')],env=env,check=True,stdout=subprocess.DEVNULL)
    restored=list((base/'restore').rglob('level.dat'))
    assert len(restored)==1 and restored[0].read_bytes() in {s.encode() for s in stamps}
    print('PASS: failed uploads remain pending; historical dates survive retention; encrypted restore succeeds')
