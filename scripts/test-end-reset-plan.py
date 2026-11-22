#!/usr/bin/env python3
"""Behavioral checks for read-only, gateway-safe End reset planning."""
import gzip
import importlib.util
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

spec = importlib.util.spec_from_file_location('end_reset', Path(__file__).with_name('plan-end-reset.py'))
assert spec and spec.loader
planner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(planner)


def string(value):
    data = value.encode()
    return struct.pack('>H', len(data)) + data


def tag(kind, name, payload):
    return bytes([kind]) + string(name) + payload


def integer(name, value):
    return tag(3, name, struct.pack('>i', value))


def compound_root(payload):
    return b'\x0a\x00\x00' + payload + b'\x00'


def gateway_chunk(x, z):
    destination = b''.join(integer(name, value) for name, value in [('X', x), ('Y', 80), ('Z', z)])
    entity = (tag(8, 'id', string('minecraft:end_gateway')) + integer('x', 0)
              + integer('y', 75) + integer('z', 0) + tag(10, 'ExitPortal', destination + b'\x00') + b'\x00')
    return compound_root(tag(9, 'block_entities', b'\x0a' + struct.pack('>i', 1) + entity))


def region(path, payload=None):
    if payload is None:
        path.write_bytes(bytes(8192))
        return
    compressed = zlib.compress(payload)
    record = struct.pack('>I', len(compressed) + 1) + b'\x02' + compressed
    sectors = (len(record) + 4095) // 4096
    header = ((2 << 8) | sectors).to_bytes(4, 'big') + bytes(8188)
    path.write_bytes(header + record + bytes(sectors * 4096 - len(record)))


class EndResetPlanning(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'])
        self.addCleanup(self.temporary.cleanup)
        self.snapshot = Path(self.temporary.name) / '20261121T120242Z'
        self.root = self.snapshot / 'server'
        (self.snapshot / 'runtime').mkdir(parents=True)
        (self.root / 'world/playerdata').mkdir(parents=True)
        (self.root / 'world/level.dat').write_bytes(b'unchanged dragon fight fixture')
        (self.root / '.last-clean-stop').write_text(json.dumps({'command': 'stop', 'invocation': 'fixture', 'pid': 123}))
        for name in ['region', 'entities', 'poi', 'data']:
            (self.root / 'world/DIM1' / name).mkdir(parents=True)
        (self.root / 'world/DIM1/data/unchanged.dat').write_bytes(b'preserve dimension state')

    def test_gateway_boundary_negative_cells_and_all_stores_are_preserved_without_writes(self):
        terrain = self.root / 'world/DIM1/region'
        region(terrain / 'r.0.0.mca', gateway_chunk(-1537, 1536))
        region(terrain / 'r.-4.3.mca')
        region(terrain / 'r.9.9.mca')
        # A zero-byte placeholder is not a generated terrain region.
        (terrain / 'r.-1.-1.mca').touch()
        for name in ['entities', 'poi']:
            folder = self.root / 'world/DIM1' / name
            region(folder / 'r.-4.3.mca')
            region(folder / 'r.9.9.mca')
        player = compound_root(tag(8, 'Dimension', string('minecraft:the_end')))
        (self.root / 'world/playerdata/example.dat').write_bytes(gzip.compress(player))
        before = {p.relative_to(self.snapshot).as_posix(): p.read_bytes() for p in self.snapshot.rglob('*') if p.is_file()}
        result = planner.plan(self.snapshot)
        kept = {row['path'] for row in result['preserve']}
        reset = {row['path'] for row in result['resetCandidates']}
        for name in ['region', 'entities', 'poi']:
            self.assertIn(f'world/DIM1/{name}/r.-4.3.mca', kept)
            self.assertIn(f'world/DIM1/{name}/r.9.9.mca', reset)
        self.assertIn('world/DIM1/region/r.0.0.mca', kept)
        self.assertEqual(result['playersSavedInEnd'], ['example.dat'])
        self.assertEqual(result['emptyTerrainRegionFiles'], 1)
        self.assertFalse(any('level.dat' in p or '/data/' in p or 'playerdata' in p for p in reset))
        after = {p.relative_to(self.snapshot).as_posix(): p.read_bytes() for p in self.snapshot.rglob('*') if p.is_file()}
        self.assertEqual(before, after, 'Planning changed the snapshot')
        self.assertEqual(planner.cells(-513, -512, 0, 0), {(-2, 0), (-1, 0)})

    def test_malformed_and_external_chunks_fail_closed(self):
        path = self.root / 'world/DIM1/region/r.0.0.mca'
        path.write_bytes(b'bad')
        with self.assertRaisesRegex(ValueError, 'Invalid region size'):
            planner.plan(self.snapshot)
        region(path, gateway_chunk(2000, 2000))
        raw = bytearray(path.read_bytes())
        raw[8196] = 130
        path.write_bytes(raw)
        with self.assertRaisesRegex(ValueError, 'External chunk'):
            planner.plan(self.snapshot)
        with self.assertRaisesRegex(ValueError, 'Truncated NBT'):
            planner.NbtReader(b'\x0a\x00').root()

    def test_live_partial_and_symlink_paths_are_not_reset_plans(self):
        with self.assertRaisesRegex(ValueError, 'completed timestamped snapshot'):
            planner.plan(self.root)
        path = self.root / 'world/DIM1/region/r.0.0.mca'
        path.symlink_to(self.root / 'world/level.dat')
        with self.assertRaisesRegex(ValueError, 'Symlinked terrain'):
            planner.plan(self.snapshot)


if __name__ == '__main__':
    unittest.main()
