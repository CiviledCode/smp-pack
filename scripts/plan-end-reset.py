#!/usr/bin/env python3
"""Read-only outer-End reset inventory from a completed SMP snapshot.

This command never edits a world. It preserves central terrain and linked gateway
landing areas at whole-region granularity, and inventories matching entity/POI files.
"""
import argparse
from collections import deque
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import re
import struct
import zlib

LIMIT = 64 * 1024 * 1024
REGION = re.compile(r'r\.(-?\d+)\.(-?\d+)\.mca')


class NbtReader:
    def __init__(self, data):
        if len(data) > LIMIT:
            raise ValueError('NBT exceeds size bound')
        self.stream = io.BytesIO(data)
        self.tags = 0

    def read(self, count):
        if count < 0 or count > LIMIT:
            raise ValueError('Invalid NBT length')
        data = self.stream.read(count)
        if len(data) != count:
            raise ValueError('Truncated NBT')
        return data

    def number(self, fmt):
        return struct.unpack('>' + fmt, self.read(struct.calcsize(fmt)))[0]

    def string(self):
        # Relevant vanilla tag keys/dimension IDs are ASCII. Unrelated Java
        # modified-UTF8 string values are not used for preservation decisions.
        return self.read(self.number('H')).decode('utf-8', errors='replace')

    def payload(self, kind, depth=0):
        self.tags += 1
        if self.tags > 1_000_000 or depth > 64:
            raise ValueError('NBT complexity bound exceeded')
        if kind in {1, 2, 3, 4, 5, 6}:
            return self.number({1: 'b', 2: 'h', 3: 'i', 4: 'q', 5: 'f', 6: 'd'}[kind])
        if kind == 7:
            return self.read(self.number('i'))
        if kind == 8:
            return self.string()
        if kind == 9:
            child, count = self.number('B'), self.number('i')
            if not 0 <= count <= 1_000_000 or (child == 0 and count):
                raise ValueError('Invalid NBT list')
            return [self.payload(child, depth + 1) for _ in range(count)]
        if kind == 10:
            result = {}
            while True:
                child = self.number('B')
                if child == 0:
                    return result
                name = self.string()
                if name in result:
                    raise ValueError('Duplicate NBT key')
                result[name] = self.payload(child, depth + 1)
        if kind in {11, 12}:
            count = self.number('i')
            if not 0 <= count <= 1_000_000:
                raise ValueError('Invalid NBT array')
            fmt = 'i' if kind == 11 else 'q'
            return [self.number(fmt) for _ in range(count)]
        raise ValueError(f'Unsupported NBT tag {kind}')

    def root(self):
        if self.number('B') != 10:
            raise ValueError('NBT root must be a compound')
        self.string()
        result = self.payload(10)
        if not isinstance(result, dict):
            raise ValueError('NBT root must decode to a compound')
        if self.stream.read(1):
            raise ValueError('Trailing NBT bytes')
        return result


def decompress(data, kind):
    if kind == 3:
        return data
    if kind not in {1, 2}:
        raise ValueError(f'Unsupported region compression {kind}')
    decoder = zlib.decompressobj(31 if kind == 1 else zlib.MAX_WBITS)
    result = decoder.decompress(data, LIMIT + 1)
    if len(result) > LIMIT or decoder.unconsumed_tail:
        raise ValueError('Inflated chunk exceeds bound')
    if not decoder.eof or decoder.unused_data:
        raise ValueError('Invalid compressed chunk')
    return result


def chunks(path):
    size = path.stat().st_size
    # Minecraft can leave zero-length region placeholders after read-only lookup.
    if size == 0:
        return
    if size < 8192 or size % 4096:
        raise ValueError(f'Invalid region size: {path}')
    with path.open('rb') as stream:
        header = stream.read(4096)
        occupied = set()
        for slot in range(1024):
            location = int.from_bytes(header[slot * 4:slot * 4 + 4], 'big')
            offset, sectors = location >> 8, location & 255
            if not offset and not sectors:
                continue
            if offset < 2 or not sectors or (offset + sectors) * 4096 > size:
                raise ValueError(f'Invalid chunk allocation: {path}, slot {slot}')
            allocation = set(range(offset, offset + sectors))
            if occupied.intersection(allocation):
                raise ValueError(f'Overlapping chunk allocations: {path}')
            occupied.update(allocation)
            stream.seek(offset * 4096)
            length = struct.unpack('>I', stream.read(4))[0]
            if not 1 <= length <= sectors * 4096 - 4:
                raise ValueError(f'Invalid chunk record length: {path}')
            record = stream.read(length)
            kind = record[0]
            if kind & 128:
                raise ValueError(f'External chunk needs explicit review: {path}, slot {slot}')
            yield NbtReader(decompress(record[1:], kind)).root()


def cells(min_x, max_x, min_z, max_z):
    return {(x, z) for x in range(math.floor(min_x / 512), math.floor(max_x / 512) + 1)
            for z in range(math.floor(min_z / 512), math.floor(max_z / 512) + 1)}


def file_record(path, root):
    if path.is_symlink() or not path.is_file():
        raise ValueError(f'Expected regular file: {path}')
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return {'path': path.relative_to(root).as_posix(), 'bytes': path.stat().st_size,
            'sha256': digest.hexdigest()}


def plan(snapshot, radius=1024, gateway_buffer=64):
    snapshot = snapshot.resolve()
    if not re.fullmatch(r'20\d{6}T\d{6}Z', snapshot.name):
        raise ValueError('Use a completed timestamped snapshot, never a live/partial world')
    root = snapshot / 'server'
    if not (snapshot / 'runtime').is_dir() or not (root / 'world/level.dat').is_file():
        raise ValueError('Incomplete SMP snapshot layout')
    proof = json.loads((root / '.last-clean-stop').read_text())
    if proof.get('command') != 'stop' or not proof.get('invocation') or not proof.get('pid'):
        raise ValueError('Snapshot lacks graceful-stop evidence')
    if not 512 <= radius <= 8192 or radius % 512:
        raise ValueError('Central square radius must be a multiple of 512, from 512 to 8192')
    if not 16 <= gateway_buffer <= 512:
        raise ValueError('Gateway landing buffer must be from 16 to 512 blocks')
    end = root / 'world/DIM1'
    if not end.is_dir() or end.is_symlink():
        raise ValueError('Expected vanilla End directory world/DIM1')
    terrain = {}
    for path in sorted((end / 'region').iterdir()):
        match = REGION.fullmatch(path.name)
        if not match:
            raise ValueError(f'Unrecognized terrain entry requires review: {path.name}')
        if path.is_symlink():
            raise ValueError('Symlinked terrain region')
        terrain[tuple(map(int, match.groups()))] = path
    kept = cells(-radius, radius - 1, -radius, radius - 1)
    central = set(kept)
    queue = deque(sorted(kept))
    scanned = set()
    gateways = []
    while queue:
        cell = queue.popleft()
        if cell in scanned or cell not in terrain:
            continue
        scanned.add(cell)
        for chunk in chunks(terrain[cell]):
            for entity in chunk.get('block_entities', []):
                if entity.get('id') != 'minecraft:end_gateway' or 'ExitPortal' not in entity:
                    continue
                target = entity['ExitPortal']
                if not isinstance(target, dict) or any(type(target.get(k)) is not int for k in ['X', 'Y', 'Z']):
                    raise ValueError('Malformed gateway destination')
                x, z = target['X'], target['Z']
                extra = cells(x - gateway_buffer, x + gateway_buffer, z - gateway_buffer, z + gateway_buffer)
                queue.extend(sorted(extra - kept))
                kept.update(extra)
                gateways.append({'source': [entity.get(k) for k in ['x', 'y', 'z']],
                                 'destination': [x, target['Y'], z]})
    preserve, reset = [], []
    for directory in ['region', 'entities', 'poi']:
        folder = end / directory
        if not folder.exists():
            continue
        if folder.is_symlink():
            raise ValueError(f'Symlinked {directory} directory')
        for path in sorted(folder.iterdir()):
            match = REGION.fullmatch(path.name)
            if not match:
                raise ValueError(f'Unrecognized {directory} entry requires review: {path.name}')
            cell = tuple(map(int, match.groups()))
            (preserve if cell in kept else reset).append(file_record(path, root))
    end_players = []
    for path in sorted((root / 'world/playerdata').glob('*.dat')):
        if path.is_symlink():
            raise ValueError('Symlinked player data')
        with gzip.open(path, 'rb') as stream:
            data = stream.read(LIMIT + 1)
        player = NbtReader(data).root()
        if player.get('Dimension') == 'minecraft:the_end':
            end_players.append(path.name)
    return {'mode': 'READ_ONLY_PLAN', 'snapshot': str(snapshot),
            'centralSquare': {'minX': -radius, 'maxX': radius - 1, 'minZ': -radius, 'maxZ': radius - 1},
            'gatewayLandingBufferBlocks': gateway_buffer, 'linkedGateways': gateways,
            'centralRegionCells': len(central), 'preservedTerrainRegionFiles': sum(c in kept for c in terrain),
            'emptyTerrainRegionFiles': sum(p.stat().st_size == 0 for p in terrain.values()),
            'preserve': preserve, 'resetCandidates': reset,
            'resetCandidateBytes': sum(row['bytes'] for row in reset),
            'playersSavedInEnd': end_players,
            'untouched': ['world/level.dat (including dragon fight)', 'world/DIM1/data',
                          'all playerdata', 'Overworld', 'Nether', 'other dimensions', 'team/bank data'],
            'warnings': ['This tool does not execute a reset or prove a snapshot is immutable.',
                         'Use a NEW consistent snapshot and re-inventory immediately before an approved reset.',
                         'Evacuate online AND offline players saved in the End before capture/reset.',
                         'Confirm no builds, dropped items or graves need recovery in reset areas.',
                         'Gateway landing regions are conservatively retained; some outer terrain remains.',
                         'Deploy and verify new placement rules before regenerating outer chunks.',
                         'Clear only stale End client-map data if necessary; never delete all player maps.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot', type=Path)
    parser.add_argument('--central-radius', type=int, default=1024)
    parser.add_argument('--gateway-buffer', type=int, default=64)
    parser.add_argument('--output', type=Path, help='New inventory file; refuses to overwrite')
    args = parser.parse_args()
    result = plan(args.snapshot, args.central_radius, args.gateway_buffer)
    text = json.dumps(result, indent=2) + '\n'
    if args.output:
        with args.output.open('x') as stream:
            stream.write(text)
        print(json.dumps({k: result[k] for k in ['mode', 'centralRegionCells', 'preservedTerrainRegionFiles', 'resetCandidateBytes', 'playersSavedInEnd']}, indent=2))
        print('Plan:', args.output.resolve())
    else:
        print(text, end='')


if __name__ == '__main__':
    main()
