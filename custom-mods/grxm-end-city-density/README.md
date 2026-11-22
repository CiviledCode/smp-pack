# GRXM End City Density 1.0.0

Code-free, **dedicated-server-only** Fabric resource mod for Minecraft 1.20.1,
Fabric Loader 0.18.3, Java 17 and the pack's existing Fabric API
0.92.12+1.20.1. No initializer, mixin, nested JAR, new runtime dependency,
biome/terrain registration or unrelated version change.

## Contract

The JAR contains only `fabric.mod.json` and
`data/minecraft/worldgen/structure_set/end_cities.json`. Compared with pinned
vanilla data, only spacing **20 → 14** and separation **11 → 7** change.
Salt `10387313`, random-spread placement, triangular spread, structure
`minecraft:end_city` and weight `1` remain unchanged. Packwiz metadata is
server-only and pins the production artifact to the immutable **v1.7.0** tag.
Publish that tag before staging installation.

Fabric API's pinned `ModResourcePackCreator` adds root mod data to the required
`fabric` server-data profile above vanilla. No separately enabled world
datapack or built-in-pack registration is necessary. The real dedicated-server
test confirmed the selected JSON source and decoded worldgen registry, and
confirmed `fabric` remains selected after an empty pack selection. Higher
priority user datapacks can still override this resource; removing the mod
also removes its effect. Clients do not need this JAR.

**New generation only.** Existing generated chunks/city starts do not change.
Changing grid spacing moves candidates, not a strict superset of old locations.
This module performs no reset, deletion, migration or preservation operation;
any outer-island reset needs separate review and authorization.

BetterEnd 4.0.11's inspected `EndCityFeatureMixin` optionally rejects when
`random.nextInt(endCityFailChance) != 0`. The pack already sets that integer to
`1`, so there is no additional rejection to remove; zero/nonpositive values
also bypass that rejection, rather than increasing density further.

All existing suitability checks stay in place. Vanilla `EndCityStructure`
chooses a rotation, samples the four corner heights of its offset 5×5 box
using `WORLD_SURFACE_WG`, and rejects when the minimum height is below 60.
`Structure.isValidBiome` then queries the active generator's biome source at
the generation point (quart coordinates) and tests the structure's biome set.
The pinned BCLib generator/source are `BCLChunkGenerator` and
`BCLibEndBiomeSource`; no BCLib-specific suitability bypass is introduced.
BetterEnd's shipped `has_structure/end_city` tag adds amber land, chorus forest,
dry shrubland, foggy mushroomland, glowing grasslands, lantern woods, neon
oasis, shadow forest and umbrella jungle to vanilla end highlands/midlands.
The actual loaded structure's biome set was identical in both fixtures.

## Actual local acceptance

Disposable full server-side pack, same seed **8675309**, fresh worlds, matched
finite square **chunks [80,200) × [80,200)** (blocks [1280,3200) each axis).
Shared 50 server mod JAR hashes were identical; density added just this JAR.
Client-only manifests and embedded client-only mod environments were excluded.
Server-only pinned artifacts were acquired from their existing metadata, not
upgraded. Runtime logs confirmed MC 1.20.1 / Loader 0.18.3 under Java 17.

| Configuration | JSON source | Candidates | Valid End City starts |
|---|---|---:|---:|
| Baseline 20/11 | vanilla | 36 | 1 |
| Density 14/7 | fabric | 78 | 6 |

Each counted city was generated through the real server chunk pipeline, had a
valid `minecraft:end_city` structure start with template pieces, and its start
chunk was completed to `FULL` with actual purpur blocks read back: baseline
1,591 blocks; density 1,010 / 1,139 / 868 / 1,301 / 785 / 1,313. The six density
start chunks were (87,89), (102,129), (115,145), (127,159), (143,127), (185,158).
This checks actual generation, not merely candidate arithmetic. It does not
fully generate every chunk in every city's bounding box.

225 matched base-height/biome controls were identical. A fresh negative
control with the density mod absent but expected spacing 14 correctly failed
on active spacing 20. All three servers saved and exited. Production JAR
rebuilding reproduced the same hash. The JAR's decoded resource was also
compared with the exact pinned vanilla JSON: no other field changed.

**Limits:** one finite seed/area, not a universal city multiplier. The
asymptotic candidate multiplier is approximately **2.0408**, not a promise of
2× actual cities (the observed 1→6 sample is small). No live server contact,
world edits, publication, deployment or root pack/index integration occurred.
Existing nonfatal pack recipe errors (`betterend:guide_book`, beetroot soup)
and empty `terraform:boat` registry errors occurred in both variants; the
minimal test helper also causes a harmless BCLib missing-display-name warning.
No GUI launch was needed or performed.

## Reproduce

From the repository root, with Python 3.11+ and the existing Gradle 8.12:

```sh
python3 custom-mods/grxm-end-city-density/build.py
gradle -p custom-mods/grxm-end-city-density/acceptance --no-daemon build
# Choose a NEW directory under $TMPDIR; never point at an installed server.
mkdir "$TMPDIR/grxm-density-reproduce"
curl -fL 'https://meta.fabricmc.net/v2/versions/loader/1.20.1/0.18.3/1.1.1/server/jar' \
  -o "$TMPDIR/grxm-density-reproduce/fabric-server.jar"
python3 custom-mods/grxm-end-city-density/acceptance/run.py "$TMPDIR/grxm-density-reproduce" baseline
python3 custom-mods/grxm-end-city-density/acceptance/run.py "$TMPDIR/grxm-density-reproduce" density
python3 custom-mods/grxm-end-city-density/acceptance/run.py "$TMPDIR/grxm-density-reproduce" negative
python3 custom-mods/grxm-end-city-density/acceptance/compare.py "$TMPDIR/grxm-density-reproduce"
```

`run.py` reuses the specified cached public-client JAR source, copies only the
server-side set, verifies artifact hashes, copies repository config, and fetches
missing server artifacts through their pinned metadata. It binds Minecraft and
voice chat only to isolated fixture ports (Minecraft loopback 25647; voice
25648), keeps online authentication enabled and disables RCON. Run variants
sequentially. `density-fixture-ALLOW` guards the separate **test-only** mod;
never ship that mod. Fresh fixture directories are mandatory.

Checked-in `acceptance/evidence.json` records artifact hashes, inventory,
selected placements, valid starts/pieces/block counts and controls. Full raw
candidate rows, terrain controls, logs and worlds remain in
`$TMPDIR/grxm-density-final-fixtures/{baseline,density,negative}/`.

SHA-256: `6ef777611f07786e076fd23c98f4514056b56a228efd636c5ebe194116b6771e`

SHA-512: `f20fed0a0bd732ef9fc4882b008159b0e17cf9324f8176c5285aa4de006de8d983061ba1cc1dc0d0527074e5e4ea143e777baa8c820562f32aecffcfdf8c192a`
