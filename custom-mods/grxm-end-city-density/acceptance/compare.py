#!/usr/bin/env python3
"""Check real A/B outcomes, write compact provenance/evidence (no mock inputs)."""
from pathlib import Path
import hashlib, json, sys, zipfile
root=Path(sys.argv[1]).resolve()
reports={m:json.loads((root/m/'density-results.json').read_text()) for m in ('baseline','density')}
a,b=reports.values()
assert (a['spacing'],b['spacing'])==(20,14)
assert a['resource_source']=='vanilla' and b['resource_source']=='fabric'
assert a['seed']==b['seed']==8675309
assert a['allowed_biomes']==b['allowed_biomes']
assert a['terrain_control']==b['terrain_control']
assert a['generator']==b['generator'] and a['biome_source']==b['biome_source']
assert a['fabric_required_after_empty_selection'] and b['fabric_required_after_empty_selection']
assert b['valid_starts']>a['valid_starts']>0
for d in reports.values():
    assert len(d['rows'])==d['candidates']
    assert sum(r['valid'] for r in d['rows'])==d['valid_starts']
    assert all(r.get('purpur_blocks_in_start_chunk',0)>0 for r in d['rows'] if r['valid'])
inv={m:{r['file']:r['sha256'] for r in json.loads((root/m/'inventory.json').read_text())} for m in reports}
extra=set(inv['density'])-set(inv['baseline'])
assert extra=={'grxm-end-city-density-1.0.0.jar'}
assert {k:v for k,v in inv['density'].items() if k not in extra}==inv['baseline']
assert (root/'negative/density-FAIL.txt').read_text().startswith('java.lang.AssertionError: Wrong active placement: 20')
repo=Path(__file__).resolve().parents[3]
jar=repo/'assets/mods/grxm-end-city-density-1.0.0.jar'
with zipfile.ZipFile(jar) as z:
    assert set(z.namelist())=={'fabric.mod.json','data/minecraft/worldgen/structure_set/end_cities.json'}
    assert json.loads(z.read('fabric.mod.json'))['environment']=='server'
config=repo/'config/betterend/generator.json'
summary={
    'artifact_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
    'artifact_sha512':hashlib.sha512(jar.read_bytes()).hexdigest(),
    'runtime':{'minecraft':'1.20.1','fabric_loader':'0.18.3','java':'17','fabric_api':'0.92.12+1.20.1'},
    'fixture_root':str(root),
    'chunk_bounds':{'min_inclusive':[80,80],'max_exclusive':[200,200]},
    'biome_and_height_control_points_equal':len(a['terrain_control']),
    'generator_config_sha256':hashlib.sha256(config.read_bytes()).hexdigest(),
    'negative_control':'module absent with expected spacing 14: actual 20 correctly rejected',
    'shared_server_mod_inventory':inv['baseline'],
    'baseline':a,'density':b,
    'scope':'Finite single-seed sample, not an estimate of universal actual-city multiplier. FULL generation for each valid start chunk; not every chunk of every complete city bounding box.'
}
for d in (a,b):
    del d['terrain_control']
    d['rows']=[r for r in d['rows'] if r['valid']]
out=Path(__file__).with_name('evidence.json')
out.write_text(json.dumps(summary,indent=2)+'\n')
print(f"PASS candidates {a['candidates']} -> {b['candidates']}; valid cities {a['valid_starts']} -> {b['valid_starts']}; terrain controls equal: {summary['biome_and_height_control_points_equal']}")
print(out)
