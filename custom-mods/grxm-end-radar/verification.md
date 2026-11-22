# Local verification — GRXM End Radar 1.0.0

**Evidence scope:** these pre-release local tests verify the updated **reusable, permanently one-city-bound radar**, not the superseded consumed-on-locate design. They performed no live connection, reset or deployment. Publication and production maintenance are separate from this behavioral evidence; their operating contracts are in the root README and module manual.

## Artifact

- Production: `assets/mods/grxm-end-radar-1.0.0.jar` (repository-relative), **26,644 bytes**.
- SHA-256: `69e42f4654d9e8e77fe0c2b5673ac09ed95fc47afa8fa08875050533992383c5`.
- Build output, distributed asset, and the JAR actually loaded by the disposable server have the same hash.
- A forced rebuild (`--rerun-tasks build remapIntegrationJar`) reproduced the production JAR byte-for-byte. Production class version is **61 / Java 17**. No integration classes/resources are packaged in it.
- `mods/grxm-end-radar.pw.toml` has matching SHA-256, `side = "both"`, and an explicitly unpublished future immutable `v1.7.0` URL.

## Build and focused algorithm/storage tests

Executed with JDK 21 running Gradle 8.12 / Loom 1.9.2, compilation `--release 17`:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ../grxm-teams/gradlew --no-daemon --rerun-tasks build remapIntegrationJar
```

Result: **BUILD SUCCESSFUL**; all three focused JUnit tests passed:

- Exact negative-coordinate distance ordering and radius inclusion against an independent exhaustive oracle; distance-only output grammar.
- Durable global uniqueness, persisted previous reading and completion, duplicate rejection, and corrupt/truncated history refusal without resetting the file.
- Real closed-file-channel write failure does not publish a reservation.

Report: `custom-mods/grxm-end-radar/build/reports/tests/test/index.html`; XML: `build/test-results/test/TEST-dev.grxm.radar.RadarContractsTest.xml` within the module.

## Actual Fabric dedicated-server integration

Disposable fixture: `/home/civ/.hermes/cache/scratch/grxm-radar-fixture`.

Launcher embedded `install.properties` was inspected before launch: **Minecraft 1.20.1 / Loader 0.18.3**. All three accepted runs independently logged that same pair, **Fabric API 0.92.12+1.20.1**, Java 17, and radar 1.0.0. Server bound only to loopback `127.0.0.1:25678`, retained online-mode, and had no real players. Only Fabric API, the production radar, and the guarded integration driver were installed. The Overworld was flat to reduce fixture startup cost; the End used real vanilla 1.20.1 generation and seed 12345.

The Java command used `-Xms512M -Xmx2G -Dgrxm.radar.integration=true -Dgrxm.radar.phase=<phase> -jar fabric-server.jar nogui`, with the required filesystem safety marker. Runs exited **0**, with PASS files and all-dimension save completion. A successful exit alone was not treated as test success.

| Phase | Verified behavior |
| --- | --- |
| `seed` | Real chunk-future admission returns before main-thread processing; real loaded recipe gives one max-stack-one radar and rejects wrong center; ordinary Eye remains vanilla. Independently enumerated two natural valid negative-coordinate city starts. First radar binds nearest even when it has a vanilla exploration reference; second radar binds the next nearest. Repeated/overlapping uses never create duplicate claims. Neither binding consumes an item. Plain literal output has no siblings, hover/click actions, coordinates, or direction. Same-target readings produce unchanged/warmer/colder and survive transfer without another allocation. Actual admitted searches cancel on slot/stack replacement, death, disconnect, dimension departure, movement, and timeout. Forced work-budget exhaustion returns a distinct limit message without assignment. A real closed-channel reservation failure preserves the radar and both original claims and disables the service. |
| `restart` | A new JVM reloads both exact global city claims and the radar UUID from the real serialized vanilla player inventory; previous reading and original target remain unchanged. Carrying that radar into its saved real natural-city structure bounding box automatically completes it without further use. Completed radar is a non-rebinding memento; reservation remains. Exhaustive central-island search reports no result within 1024 blocks without consuming or binding the new radar. |
| `completed` | A second actual JVM restart retains the completed memento and refuses rebind. A fresh radar binds a saved valid old-layout start at a chunk that is not a current placement candidate, rather than either previously reserved/completed city. The old-layout fixture was explicitly installed using real structure pieces into a full chunk and persisted; it is not represented as a naturally generated third city. |

Exact raw evidence:

```text
/home/civ/.hermes/cache/scratch/grxm-radar-fixture/seed-console.log
/home/civ/.hermes/cache/scratch/grxm-radar-fixture/restart-console.log
/home/civ/.hermes/cache/scratch/grxm-radar-fixture/completed-console.log
/home/civ/.hermes/cache/scratch/grxm-radar-fixture/radar-seed-PASS.txt
/home/civ/.hermes/cache/scratch/grxm-radar-fixture/radar-restart-PASS.txt
/home/civ/.hermes/cache/scratch/grxm-radar-fixture/radar-completed-PASS.txt
/home/civ/.hermes/cache/scratch/grxm-radar-fixture/verification-summary.json
/home/civ/.hermes/cache/scratch/grxm-radar-fixture/radar-world/data/grxm-end-radar.history
/home/civ/.hermes/cache/scratch/grxm-radar-fixture/radar-world/playerdata/
```

Readback parsed **three distinct permanent city bindings and one completed binding**, with no duplicate city keys. Final accepted runs have no FAIL marker. The seed log's `ClosedChannelException` is the deliberately injected persistence-failure assertion, not an unhandled server failure.

## Combined full-pack BetterEnd + density + radar acceptance

A separate **fresh-world** dedicated server at `/home/civ/.hermes/cache/scratch/grxm-radar-combined-fixture` exercised the missing combined boundary. Its 53 top-level JARs were the density fixture's **51 production server mods unchanged byte-for-byte**, plus the exact production radar above and the guarded integration driver. The density fixture's separate `grxm-density-acceptance` test driver was excluded, not a production mod. Both prelaunch launcher metadata and actual startup confirm **Minecraft 1.20.1 / Fabric Loader 0.18.3**; the runtime logs Java 17, Fabric API 0.92.12+1.20.1, BetterEnd 4.0.11, BCLib 3.0.18, density 1.0.0, and radar 1.0.0. Online-mode remained enabled; Minecraft listened on `127.0.0.1:25679`, with no real players.

The opt-in `combined` integration phase uses seed **8675309**, origin **(2000, 2000)**, and a **1024-block** circle. It asserts the actual `BCLChunkGenerator` class and active random-spread placement **14 / 7**. An independent exhaustive grid/radius/sort implementation (not production `DistanceOrder.within`) found **12,892** in-radius chunk centers, **69** active placement candidates, and **5 naturally valid StructureStarts**. No city was injected or copied from the earlier fixture. The oracle resolves actual starts before radar use; this proves target correctness against natural structures, not cold-world locator latency.

Verified through real production item use and the loaded RecipeManager:

- Eight diamonds plus a central Eye produce one max-stack-one radar; wrong center is rejected and ordinary Eyes remain vanilla.
- First allocation is nearest oracle start **(143,127)**; same-target unchanged/warmer/colder readings allocate nothing and retain the item.
- Second radar excludes that active reservation and binds **(115,145)**.
- Persisted target bounds exactly match the real natural StructureStart. Use and automatic inventory scanning **above its vertical bound do not complete**; entering the actual three-axis bounds automatically completes it. The retained completed memento cannot rebind.
- A later fresh radar binds **(102,129)**, the third nearest eligible natural city, excluding both the completed city and the still-active reservation.

The run produced `radar-combined-PASS.txt`, **exit 0**, and completed all-dimension saves. Post-exit readback validated every ledger CRC, three distinct permanent bindings in the oracle order, and exactly one completion. All 51 original production fixture mod hashes and the radar hash were checked again after exit. Production source and JAR were unchanged; earlier minimal-fixture restart/fault evidence above therefore remains applicable and was not rerun.

Evidence under `/home/civ/.hermes/cache/scratch/grxm-radar-combined-fixture/`:

```text
prelaunch-audit.json
combined-console.log
combined-natural-cities.csv
radar-combined-PASS.txt
exit-code.txt
verification-summary.json
combined-world/data/grxm-end-radar.history
```

Reproduction: build only `remapIntegrationJar`, install its separate integration-test JAR alongside the unchanged production JAR in a disposable server with those pinned 51 mods/configs, a fresh world using the seed above, and `RADAR-TEST-ALLOW`. Launch Java 17 with `-Xms512M -Xmx4G -Dgrxm.radar.integration=true -Dgrxm.radar.phase=combined -jar fabric-server.jar nogui`. Require the PASS marker **and** exit status **and** save completion; a failed assertion also halts the server and exit status alone is insufficient.

Log inspection found **no new radar/density behavior errors**. The existing pack errors for `betterend:guide_book`, `farmersdelightrefabricated:emptying/beetroot_soup`, and empty `terraform:boat` registry also occur in the accepted density fixture log. The only differing warning/error messages were startup/datapack timing values; existing optional-class/refmap/mixin warnings remain. This is not a claim that the entire pack's unrelated warnings are fixed.

## Source/API inspection and limits

Mapped 1.20.1 methods were inspected through `javap` and CFR-decompiled source, including `ServerChunkCache`, `ChunkGenerator`, `StructurePlacement`, and `ChunkSerializer`. Local audit output: `/home/civ/.hermes/cache/scratch/grxm-radar-source-audit/sources`.

A consequential API trap is covered by a real server regression: **`getChunkFuture` blocks/pumps when invoked on the server thread**. Production now enters only its thread-aware off-thread branch and polls returned futures on later ticks; only Minecraft's own branch schedules the actual chunk operation on the server thread. No blocking locate/map-structure scan is used.

Limits: dedicated-server integration uses instrumented server-player fixtures, including the actual combined full server mod set with BetterEnd and density. It is **not** a graphical client, real connected-player packet/interaction test, populated-server performance benchmark, cold-world locator latency guarantee, or power-loss/process-kill crash proof. The combined run covers one fresh seed and finite circle; restart/fault paths remain backed by the same production JAR's prior minimal-fixture evidence, not claimed as combined-pack reruns. Durability was exercised with actual disk writes, forced write failure, and two clean JVM restarts. Normal Minecraft inventory transfers retain vanilla crash semantics; matching backups and explicit history/epoch reconciliation are required before a future End reset. No live reset occurred.
