# GRXM End Radar 1.0.0

Standalone Fabric mod for Minecraft **1.20.1**, built against Loader **0.18.3** and Fabric API **0.92.12+1.20.1**. Install on **both client and server**. No Teams, economy, mixins, permissions plugin, or custom network protocol is required. The integration-test JAR is a separate, guarded development artifact and must never ship.

## Gameplay contract

Craft **one** `grxm_end_radar:radar` using an Eye of Ender in the center and eight diamonds around it. Maximum stack size is **one**. Ordinary Eyes of Ender remain vanilla items.

- First successful use **in the End** permanently binds this radar to one globally unclaimed End City. Binding **does not consume** the radar. A cancelled, busy, timed-out, work-limit, or no-result search does not reserve a target or consume an item; the same unbound radar can retry. Storage errors fail closed and require investigation rather than blind retries (see recovery below).
- Subsequent uses report the **same city's current horizontal distance**, rounded to the nearest block, plus **Warmer / Colder / Unchanged** relative to that radar identity's last successful reading. The comparison uses unrounded distance. Bound readings do not run a search or allocate another city.
- Distances refer to the **center of the structure-start chunk**, not the city edge, ship, treasure, or nearest block. First binding selects and reports from the position where that search began. Stay within 16 blocks on each horizontal axis while it searches.
- All messages are literal text: no coordinates, compass, directional arrows, clickable targets, hover coordinates, or dimension links. This is intentionally an exploratory warmer/colder design, not navigation.
- Transferring the same radar retains its identity, target, and previous reading. Losing/discarding it **never releases** its city. A different newly crafted radar cannot claim that city.
- **Automatic completion:** while a radar is carried in a normal inventory/offhand slot, entering its saved valid `StructureStart.getBoundingBox()` in **all three axes** marks it completed. Use also checks this immediately. This is the city's aggregate structure bounding box (including its vertical extent and spaces between pieces), **not** a 64-block radius or an individual-piece collision test. The target remains reserved forever. The radar remains as a completed memento and **cannot rebind**. Placing it in a chest or a nested backpack is not carrying it in a scanned slot.
- A copied/spawned NBT clone with the same radar UUID is an **alias of the same radar**, not a second allocation; aliases share target, previous reading, and completion. This friends-server implementation deliberately does not attempt physical-item anti-duplication or protection against an operator editing NBT/files.

## Bounded search and nearest ordering

The search covers a **1,024 horizontal-block circle**, measured to start-chunk centers. It does **not** claim to find the globally nearest city outside that bound. An exhaustive completed search with no eligible city explicitly says none was found **within 1,024**. Timeout/work-limit/error messages are distinct from exhaustive no-result.

1. Enumerate and sort chunk-center points in that circle by exact squared horizontal distance, then chunk X/Z for deterministic ties. The enclosing grid is bounded by 129 × 129 points; this is arithmetic/memory work only, not synchronous world generation.
2. Process batches of **16** points in that exact order. For each point, asynchronously inspect just the saved End City start ID through `chunkScanner().scanChunk` and `CollectFields`, and inspect an already-ticketed/live `STRUCTURE_STARTS` future with `create=false`. The saved/live checks allow pre-existing cities from an earlier spacing layout to remain eligible.
3. For a saved/live valid start, or a candidate admitted by the world's **actual** `getPlacementsForStructure` / `isStructureChunk` rules, request `getChunkFuture(..., STRUCTURE_STARTS, true)` and poll it on later ticks. **Both true/false chunk-future requests enter through the API's off-thread branch**, which marshals scheduling onto Minecraft's main-thread processor; the server-thread branch misleadingly calls `managedBlock` despite returning a future. No world inspection/mutation is performed by our worker, and no future is joined until it is complete. Validate the actual `StructureStart.isValid()`; terrain/biome rejection and BetterEnd generation hooks therefore remain authoritative. Vanilla `skipKnownStructures`/exploration references are **not** used as radar history.
4. The first valid globally unreserved start is nearest because every preceding point has been resolved and every following point has at least its distance. A farther asynchronous result is never accepted before a closer one. There is no ring-edge approximation or guessed lower bound.

Only **one unbound search globally** is admitted, with no pending queue; overlapping/repeated uses get a busy response. There is at most one generating structure-start request in flight and **512** such requests per search, with 16 disk/live inspections in flight. Existing world/player chunk activity is not counted as new radar generation. The timeout is **2,400 server ticks or 120 wall-clock seconds**, whichever is noticed first. Timed-out/cancelled futures drain before another search can enter, preventing abandoned work from accumulating. A drain that never finishes leaves admission busy rather than spawning more work. Arrival scanning examines at most **64 vanilla inventory slots per tick** and commits at most one automatic completion per tick.

The requester must remain the current connected, living, non-spectator player in the same End world, with the exact same stack object/content/count, selected slot, and hand, and no other inventory menu open. Death, disconnect/replacement, End departure, excessive movement, stack/slot swaps, or changed NBT/count cancels before reservation. Checks repeat immediately at commit. Bound readings do not need to stay near the binding origin.

Only vanilla-compatible random-spread End City placements (at most eight placement entries) are supported for new generation. Unknown placement types fail admission rather than reporting a false exhaustive result. Existing 1.20.1 chunk NBT layout is assumed; migrate older-format worlds through Minecraft before relying on exhaustive radar searches.

**Performance caveat:** this is bounded, not free. A sparse/fully claimed area can inspect roughly thirteen thousand chunk headers over tens of seconds. Structure-start discovery may create protochunks and structure pieces; other structures sharing those chunks can also be evaluated. It does not request full terrain generation, but vanilla/modded work and disk fsync latency cannot be preempted by a wall-clock tick guarantee. Completion/readings use saved target bounds and do not load target chunks.

## Persistence and recovery

Canonical bindings, permanent claimed-city keys, previous readings, and completed flags are in:

```text
<world>/data/grxm-end-radar.history
```

This is **outside `DIM1`**. Each physical radar's NBT contains only `grxmRadarId` (a UUID); no target coordinates are put on the item. The canonical ledger maps that identity to its target. Before first binding, the complete current player NBT is written using Minecraft serialization to a temporary file in `<world>/playerdata`, forced to disk, atomically renamed over `<UUID>.dat`, and its directory forced. The prior player file is preserved as `<UUID>.dat_old`. Only then is the binding appended to the checksummed global ledger and forced before success is exposed. Thus a crash before the ledger append leaves an unbound delivered identity; after a successful append the matching item identity is already durable. No item removal occurs at either point.

Reads and completion also append and force before changing canonical memory/announcing success. Interrupted or uncertain writes disable further use; malformed, duplicate, or truncated history **fails closed**, never silently resets. An ambiguous append may have reached disk even if the operation reported an error: preserve the file and investigate before restart. Reservations are conservative and are not automatically released. The ledger is capped at 100,000 bindings and 64 MB including reading/completion events; reaching capacity fails closed and requires an explicit maintenance/migration decision, not deletion. There is no automatic compaction or rollback reconciliation.

Back up/restore **world data, playerdata, and this ledger together**. Routine Minecraft item movement/death saves retain vanilla crash semantics; this mod guarantees identity delivery at first binding, not a cross-file transaction for every later inventory transfer. Editing/deleting the ledger, restoring mismatched backups, or stripping item UUIDs defeats those guarantees. UUID aliases are intentionally treated as the same radar.

### Future End resets

A terrain/entities/POI reset must explicitly reconcile **the radar ledger and the world-generation epoch**. The current implementation deliberately has no automatic epoch rollover and retains old coordinate claims across End regeneration. Existing targets and saved arrival boxes can become stale after a reset. **Never silently clear the history** merely because `DIM1` was reset: that would allow previously tracked/completed cities to be allocated again. Decide and authorize an explicit migration/retirement policy for existing radars and coordinate claims, and back up the entire matched state before any reset. This module performs **no reset**.

## Build and verification

From this directory, using the existing Teams wrapper only as a Gradle launcher:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ../grxm-teams/gradlew --no-daemon build remapIntegrationJar
```

Loom **1.9.2**, Gradle **8.12** runtime on JDK 21; Java compilation uses `--release 17`. Production output is `build/libs/grxm-end-radar-1.0.0.jar`; the parent pack's copy belongs at `assets/mods/grxm-end-radar-1.0.0.jar`. Archive entry order/timestamps are reproducible. Source-adjacent tests protect exact negative-coordinate distance ordering, durable uniqueness/reading/completion replay, corrupt-history refusal, and real write failure.

The separate integration driver requires **both** `-Dgrxm.radar.integration=true` and a `RADAR-TEST-ALLOW` marker in an isolated server directory. Never install it on a live server. Its original minimal-Fabric phases exercise real item use, RecipeManager, natural structure starts, player inventory serialization, global claims, warmer/colder/transfer, cancellation, write failure, actual restart, bounds completion, and bounded no-result. The additional `-Dgrxm.radar.phase=combined` phase passed on a **fresh full-pack server with BetterEnd, BCLib, and density 14/7 active**, using all 51 density-fixture production server mods unchanged plus radar and the test driver. An independent exhaustive 1024-block oracle at seed 8675309/origin (2000,2000) verified nearest natural-city allocation, reusable readings, reserved-city exclusion, real three-axis bounds completion, and later exclusion of both completed and still-active claims. The production JAR is unchanged, so prior minimal-fixture restart/fault evidence is reused rather than represented as full-pack reruns.

Exact evidence and acceptance limits are in `verification.md`. This is dedicated-server integration with instrumented fixture players, **not** graphical client/authenticated multiplayer acceptance, a populated-server performance benchmark, or cold-world search-latency proof: the combined oracle resolves natural starts before item use. The combined sample is one fresh seed and finite circle, not a universal city-density claim.

The packwiz file pins the production artifact to the immutable **`v1.7.0` tag**. Publish that tag before staging the server, then advance the public client manifest only after server readiness. Building this module alone neither deploys it nor authorizes an End reset. The initial release resets approved outer regions before any production radar history exists; subsequent resets require the explicit history policy above.
