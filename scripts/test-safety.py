#!/usr/bin/env python3
"""Repeatable filesystem and shutdown-proof regressions; no VPS access needed."""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def load(name):
    loader=importlib.machinery.SourceFileLoader(name.replace('-','_'),str(ROOT/'server'/name))
    spec=importlib.util.spec_from_loader(loader.name,loader)
    assert spec is not None
    module=importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


quiesce=load('smp-quiesce')
retire=load('smp-retire-config')
cache=load('smp-clean-cache')


class SafetyTests(unittest.TestCase):
    def test_forced_or_stale_shutdown_cannot_authorize_snapshot(self):
        before={'ActiveState':'active','InvocationID':'current','MainPID':'123'}
        clean={'ActiveState':'inactive','Result':'success','ExecMainStatus':'0','ExecMainPID':'123'}
        proof={'command':'stop','invocation':'current','pid':123}
        quiesce.verify(before,clean,proof)
        for result,status in [('timeout','0'),('exit-code','1'),('success','143')]:
            with self.assertRaises(ValueError):
                quiesce.verify(before,dict(clean,Result=result,ExecMainStatus=status),proof)
        with self.assertRaises(ValueError):
            quiesce.verify(before,clean,dict(proof,invocation='stale'))
        # PID reuse/restored proof must never authorize an already-inactive unit.
        for invocation in ['current','stale']:
            with self.assertRaises(ValueError):
                quiesce.verify(dict(before,ActiveState='inactive'),clean,dict(proof,invocation=invocation))

    def test_retired_config_moves_but_unmanaged_data_survives(self):
        with tempfile.TemporaryDirectory() as td:
            base=Path(td);live=base/'live';stage=base/'stage';archive=base/'retired'
            for p in [live/'config',live/'mods',stage/'config']:p.mkdir(parents=True)
            for name in ['old.conf','keep.conf','generated.conf']:(live/'config'/name).write_text(name)
            def manifest(names):return {'cachedFiles':{name:{'cachedLocation':name} for name in names}}
            (live/'packwiz.json').write_text(json.dumps(manifest(['config/old.conf','config/keep.conf'])))
            (stage/'packwiz.json').write_text(json.dumps(manifest(['config/keep.conf'])))
            retire.retire(live,stage,archive)
            self.assertFalse((live/'config/old.conf').exists())
            self.assertEqual((archive/'old.conf').read_text(),'old.conf')
            self.assertTrue((live/'config/keep.conf').exists())
            self.assertEqual((live/'config/generated.conf').read_text(),'generated.conf')
            (live/'config/escape').symlink_to(base)
            with self.assertRaises(ValueError):retire.retire(live,stage,base/'another')

    def test_cache_bound_never_touches_backups_or_unrecognized_paths(self):
        with tempfile.TemporaryDirectory() as td:
            base=Path(td);(base/'staging').mkdir();(base/'packs').mkdir()
            for i in range(1,7):
                revision=str(i)*40
                for kind in ['staging','packs']:(base/kind/revision).mkdir()
                if i<=4:(base/'staging'/revision/'.deployment-complete').touch()
                os.utime(base/'staging'/revision,(i,i))
            untouched=base/'snapshots/pending';untouched.mkdir(parents=True)
            unknown=base/'staging/operator-notes';unknown.mkdir()
            cache.cleanup(base,'4'*40)
            self.assertEqual({p.name for p in (base/'staging').iterdir()},{'3'*40,'4'*40,'6'*40,'operator-notes'})
            self.assertTrue(untouched.exists())


if __name__=='__main__':unittest.main()
