# GRXM Teams — Fabric server-only, Minecraft 1.20.1

Teams, graphical vanilla-scoreboard badges, prepaid Numismatics chunk ticking, and a shared weekly item-buyback bazaar. No additional client Java mod is required. The badge resource pack is independent of the vanilla shop/display protocols.

## Build

```sh
./gradlew clean build remapIntegrationJar --no-daemon
# Optional offline/local binary; must match the pinned SHA-512:
./gradlew build -PnumismaticsJar=/absolute/path/CreateNumismatics-1.0.15+fabric-mc1.20.1.jar
```

Gradle 8.12 wrapper, Loom 1.9.2, Java **17 bytecode**. Build with a Gradle-supported JDK (verified with JDK 21); run this Minecraft pack with **Java 17**. The portable default resolves exact Modrinth `maven.modrinth:Jdbbtt0i:SJpLT0Bq`, Numismatics 1.0.15. `verifyNumismatics` checks the raw dependency SHA-512 `942e864837be870ea9584fd95a1bf43e05445f45286ca41f9b2774c89a9248067c3b3be9ba1a15641ea59ae870307f07670331b8473d743b0479f3d7ef294ed1`, including local overrides. No replacement economy/dependency is bundled.

- Production: `build/libs/grxm-teams-1.0.0.jar`.
- Sources: `build/libs/grxm-teams-1.0.0-sources.jar`.
- **Isolated test only, never distribute:** `build/libs/grxm-teams-1.0.0-integration-test.jar`.
- Unit report: `build/reports/tests/test/index.html`.

## Commands

`/smpteam` (alias `/teams`) leaves vanilla `/team` untouched.

- `create <name>`, `invite <online-name|canonical UUID>`, `accept <team UUID>`, `leave`
- `role <player> <officer|member>`, `transfer <player>`, `disband confirm`
- `style <color> <badge> <quoted-name>`, `rename <name>`, `badge <badge>`, `color <color>`
- `treasury balance`, `treasury deposit <positive spurs>`, `treasury withdraw <positive spurs>`
- `chunks add`, `chunks remove` — current chunk
- `chunks remove <dimension> <x> <z>`, `chunks list`, `chunks cost`, `chunks status`, `status`
- `selector` — marked vanilla compass; right-click selects, sneak-right-click removes. No protection/land claims.
- `admin reload`, `admin billingstatus`, `admin remove <team UUID> <dimension> <x> <z>`
- **`admin bazaar bind <1|2|3>`** — look directly at the placed counter within `bazaar.bindReach` (default 6, maximum 8 blocks). Ray trace rejects air, walls and intervening pickable entities. No coordinates required.
- `admin bazaar unbind <1|2|3>`, `admin bazaar status`

Admin commands require the configured operator level (default 2). Allowed counters: vanilla **barrel, lectern, smooth stone, stone, oak planks**. Binding changes no block state or contents. Each slot opens **only its corresponding global offer**, not the entire market. Three active offers are the default; configuration may select two, leaving counter 3 visibly inactive. The user places and binds the blocks; this mod does not place counters or modify the old creative vendor.

Native Numismatics vendors are deliberately not bindable. **Retire the old unlimited creative mint separately before enabling the bazaar**, with a consistent backup and explicit operator confirmation. Intercepting its GUI alone does not retire automation/native minting. No production-world migration is performed by this code.

All members share their team's allowance. Owner-only: ownership/roles/disband. Default officers can invite/style/manage chunks but cannot withdraw. Members can contribute and inspect their team's treasury. Disband refunds a funded treasury to the owner's native personal account, with overflow validation; the original treasury account and all sale proofs remain retained. New teams get fresh IDs and allowances. **Team hopping/recreation is intentionally permitted; there are no player caps or membership locks.**

## Operator configuration

AutoConfig generates **`config/grxmteams.json` at runtime**. It is not a pack-managed default that overwrites operator rates. Both `billing.enabled` and `bazaar.enabled` default **false**. Configure chosen rates/quotas before explicitly activating either. Treasury transfers require at least one of these economy flags enabled; bazaar-only operation does not force activation of rent.

Relevant sections:

- `billing`: exact plain-decimal `scalingFactor`, `teamMemberScaling`, `periodSeconds` (604800), `checkEveryTicks`, `maximumBill`. `denomination` is a display label, **not** currency conversion; all arithmetic uses native smallest units/spurs.
- `limits`: teams, members, chunks/team, selected centers globally (64), invitations/expiry, dimensions, names, admissions/tick, selector.
- `permissions`: individual officer capabilities, admin level.
- `presentation`: badge glyph map/font/default, colors, prefix delimiters. Unrelated vanilla scoreboard teams are not overwritten. Signed player chat is not replaced with system messages.
- `resourcePack`: enabled/required/URL/SHA-1/prompt. Default asset `assets/grxm-team-badges-1.0.0.zip`, SHA-1 `bc02f256708eb0d6928705c5f661418b3c98fb74`, repository master URL. Publish the asset before activation.
- `messages`: success/errorPrefix/suspended/renewed/invite (`{team}`, `{id}`). Specific validation errors remain diagnostic.
- `bazaar`: settings below.

### Bazaar fields and inactive sample rates

```json
{
  "enabled": false,
  "payPersonal": false,
  "offerCount": 3,
  "periodSeconds": 604800,
  "epochSeconds": 345600,
  "denomination": "spurs",
  "title": "Weekly bazaar",
  "journalCapacity": 2048,
  "bindReach": 6,
  "pool": [
    {"itemId":"minecraft:nether_star","minItemCount":64,"maxItemCount":128,"minItemPrice":1,"maxItemPrice":4,"rarityWeight":1},
    {"itemId":"minecraft:end_rod","minItemCount":64,"maxItemCount":128,"minItemPrice":1,"maxItemPrice":4,"rarityWeight":1},
    {"itemId":"minecraft:shulker_shell","minItemCount":64,"maxItemCount":128,"minItemPrice":1,"maxItemPrice":4,"rarityWeight":1}
  ]
}
```

This is a **bazaar section**, not a complete config; generated defaults also include wither skeleton skull, ancient debris, diamond, emerald, bone block, iron block. Numbers are **inactive examples**, not activated production policy. Each pool entry has exactly these six fields:

- `itemId`: unique actual server-registry item; air/unknown IDs rejected.
- `minItemCount`, `maxItemCount`: inclusive range for the fixed weekly maximum **individual items each team may sell**, not stacks or bundles.
- `minItemPrice`, `maxItemPrice`: inclusive whole **spurs paid per one item**. `denomination` must remain `spurs`; cosmetic coin labels cannot imply conversion.
- `rarityWeight`: finite nonnegative weight; zero excludes an item, larger values increase selection probability. Enough unique positive-weight entries are required.

Weighted sampling is **without replacement**, removing each chosen item before the next draw. The shared weekly quota and unit price are independently rolled within inclusive bounds and persisted. Default reset is Monday 00:00 UTC (`epochSeconds` is the Monday offset from Unix epoch), independent of each team's rent anniversary. The current generation/end/offers survive reload/restart. Pool, count, range and timing edits apply at the **next** boundary, not midweek. Long downtime generates the current period once; clock rollback cannot decrease generation or reroll an already persisted period. There is no manual reroll command.

Each registered counter opens a vanilla 9x3 chest screen showing its item, unit price, allowance left, week end, payout destination, and **Sell 1 / 16 / 64 / All** actions with total payouts. Display and player slots are read-only in this menu; shift/drag/swap/double-click paths cannot transfer icons. Every sale revalidates generation, membership, quota, ordinary main inventory slots 0–35, exact quantity and native balance headroom. No nested/container/offhand/armor inventories are searched. Payout defaults to the selling team's treasury; `payPersonal=true` still consumes that team's quota. A menu opened before destination-policy changes is rejected.

### Floating displays

Packet-only native `ItemDisplay` + `TextDisplay`, **two entities per nearby viewer/counter**. They are not inserted into levels, persisted, ticked, dropped or picked up. Text shows **that viewer's team's** remaining allowance, unit spurs price and reset countdown. Nonmembers see a join-team message. Closed/inactive counters are labeled.

- `displaysEnabled=true`, `displayRadius=40` (8–64), `displayRefreshTicks=20` (10–100).
- `itemHeight=1.3`, `textHeight=2.2`, relative to block Y, bounded.
- `displayText`: newline-separated format with `{item}`, `{remaining}`, `{quota}`, `{price}`, `{denomination}`, `{reset}`.
- `noTeamText`, `closedText`, `inactiveText` configurable plain text.

Updates reflect membership/sales/reset/config within the refresh interval. Departure, dimension/range changes, unbind and shutdown remove virtual entities. A changed block fingerprint suppresses GUI/displays immediately when accessed and retires its registration on the next loaded-counter check (one second). Normal player breaks immediately unbind; replacing it requires a new bind. Unloaded chunks are not force-loaded to inspect counters.

## Rent semantics

One ceiling-rounded total bill: `chunks * scalingFactor * teamMemberScaling * members`. Membership, rate factors, cap and term duration are snapshotted when a term is prepaid. Additions use **that stored basis even after reload**, paying only the remaining-term increase. A paid-capacity high-water mark permits replacing removed centers within purchased capacity without double charging. Disabling billing does not erase a paid anniversary: extending capacity during that active term is rejected until billing is enabled again; replacing within existing paid capacity is allowed. Removal has no refund.

Expire once from current wall time: renew if funded, otherwise suspend immediately with selections retained. Funding resumes without arrears. Expiry/failure ticket removals are unthrottled, admission bounded. Dedicated distance-2 tickets reach level 31/entity ticking and never change vanilla `/forceload`. At **START_SERVER_TICK**, eligible installed tickets reset the dimension's empty-time counter; without this, vanilla stops entities AND block entities after 300 player-free ticks. Keepalive stops when the last eligible ticket is gone. Neighbors also load; selected centers do not bound resident chunks. Player-distance spawning mechanics stay vanilla.

## Persistence and recovery — read before enabling

Native balances and team/lease/market/quota/journal state share the actual Numismatics `numismatics_bank.dat` root, with a versioned UTF-8 byte-array/checksum attachment (8 MB aggregate bound). Schema 2 requires every market aggregate field. Legacy schema 1 upgrades once without inventing past rent rates: an old active receipt without a rate basis retains its end but cannot purchase additions until a newly snapshotted term. Missing/invalid native treasuries are never recreated.

Vanilla `SavedData.save(File)` is **not trusted**. A scoped mixin intercepts only actual `BankSavedData` instances. Bank writes use a same-directory temporary compressed NBT image, file force, complete NBT readback, **ATOMIC_MOVE + REPLACE_EXISTING**, complete target readback and directory force. No non-atomic fallback. Failures leave available temporary evidence and retain dirty state; success is not inferred from vanilla clearing dirty flags. Every custom mutation verifies the complete shared bank image synchronously before returning.

`data/grxmteams-initialized.guard` is a sticky initialization marker. After initialization, a missing bank or missing attachment refuses bank loading **before Numismatics can silently initialize empty accounts**. Whole-file unreadable/corrupt NBT is refused even without a marker. An intact attachment on disk also sets an expected-state guard: if vanilla swallows a malformed native-account load and returns a fresh bank object, both service admission and all bank writers refuse that replacement before it can overwrite the original. Bad originals are not deleted/replaced by a fresh bank. Invalid attachment data preserves its original tag on native serialization. Include the guard, bank, player files and journal in consistent world backups. Deleting the guard to bypass recovery is unsafe.

### Sale protocol

1. Resolve current period; validate inventory/quota/arithmetic/destination. Freeze transaction/player/team/account/generation/offer/item/count/unit price/payout/original balance and complete exact-slot before/after inventory NBT evidence. One unresolved sale per player.
2. Reserve team allowance and write/readback **PREPARED** in the shared bank root **before consuming items**.
3. Apply prevalidated inventory removal. Attach the same transaction UUID + frozen-evidence digest to the player's receipt map. Vanilla player persistence serializes the receipt and inventory into the **same NBT image**; force-save, force file, and read back both receipt and exact expected inventory. A swallowed player-save error is not proof.
4. Credit the frozen native account, mark **COMMITTED**, retain quota and receipt, and atomically persist/readback the entire bank root before reporting success. Single server-thread execution prevents another operation observing credit before verification.

On uncertain durability, freeze **native BankAccount deposit/setBalance/deduct paths as well as this mod's operations**, remove tickets, and request immediate server shutdown before yielding. An uncertain RAM credit cannot be spent through native ATMs/vendors during shutdown. This does not govern unrelated physical coin barter or malicious mods directly altering private memory.

**Recovery is deliberately conservative:** any PREPARED record quarantines the economy on restart, even with a matching player receipt. No automatic guessed refund/payout. COMMITTED is never replayed; its durable player receipt must match, or the economy is quarantined. Receipts survive later inventory changes and respawn. Frozen obligations survive team rename/transfer/disband and period rollover. Journal evidence is not erased when bazaar is disabled.

The journal is bounded (default 2048, configurable 1–4096). Committed proofs are retained; **there is no unsafe automatic compaction**. A full journal visibly rejects new sales. The 8 MB aggregate cap also applies. Raising capacity within bounds may buy time; durable checkpoint/compaction requires a separately verified protocol, not deletion of proof.

**Operator recovery:** stop the server, preserve original bank/guard/player files, logs and `.grxm-bank-*.nbt` evidence, and make a working copy. Inspect frozen transactions against the matching player receipt images and native bank state. Restore a consistent whole-world backup or perform an explicitly audited offline reconciliation on the copy before replacing live state. Do not delete PREPARED records, edit balances from current item counts, delete the guard, or rerun payouts blindly. No production money-grant/timewarp/recovery command is exposed.

Readback plus file/directory force improves process-visible durability; it is **not a tested guarantee against power loss, broken filesystem/storage hardware, or independently restored/inconsistently backed-up files**. Cross-file hard-crash atomicity is not claimed. Interrupted/ambiguous boundaries fail closed instead.

## Public server-thread API

After SERVER_STARTED: `TeamsMod.service()`.

- Detached `configuration()`, `snapshot()`, `teamOf(actor)`; `reload(candidate)` validates atomically.
- `create`, `invite`, `accept`, `leave`, `role`, `transfer`, `disband`, `style`.
- `deposit`, `withdraw`, `balance`, `addChunk`, `removeChunk`, `cost`, `status`.
- `activeTickets()`, `billingStatus()`, `failure()`.
- `bazaar().snapshot()` includes complete journal; `displaySnapshot()` omits the journal while returning detached display/usage state.
- `bazaar().remaining(actor, zeroBasedOfferIndex)`.
- `bazaar().sell(ServerPlayer, generation, expectedTeamUUID, zeroBasedOfferIndex, positiveCount) -> transaction UUID`. Trusted embedding API, not remotely exposed; the actual vanilla menu also validates physical counter access.
- `bazaar().bindLook(ServerPlayer, slot1to3)`, `unbind(slot)`, `open(player,counter)`. Trusted admin embedding API; command wrapper checks operator permissions.
- `boundCounter(dimension,BlockPos)` can identify bound clicks even when finance is quarantined, so handlers still prevent native block GUI bypass.
- `reconcile(nowMillis)` and `TeamsMod.installTimeSource(LongSupplier)` support trusted isolated testing, never commands/client-supplied time.
- `bazaar().installObserver(Consumer<Cut>)` exposes synchronous PREPARED / PLAYER_SAVED / COMMITTED boundaries for the separate test mod; no production configuration enables fault injection.

## Repeatable isolated acceptance

Never run the test JAR in production. It requires **both** `-Dgrxmteams.integration=true` and `grxmteams-integration-ALLOW` in the isolated server directory; normal fixture phases require no logged-in players. Test-only headless players are constructed internally without opening a network authentication bypass. Native GUI packets can be captured internally without changing server online mode.

From this mod directory, after building, use an existing **marked isolated full-pack template**, not an original/live world:

```sh
python3 scripts/integration_suite.py \
  /home/civ/.hermes/cache/scratch/smp-teams-integration/server \
  /home/civ/.hermes/cache/scratch/grxm-acceptance-NEW all
```

The full suite launches multiple real pack servers and can take several minutes; run it as a tracked foreground/background job with sufficient timeout, or use the phase-group modes.

Output must be fresh and under `$TMPDIR`/Hermes scratch. The harness clones config/mods/libraries into fresh worlds and installs immutable copies of the built artifacts; it does not copy the template world or modify template files. `JAVA17=/path/to/java17` overrides the default Java 17 location. Server binds loopback port 25589 (override `GRXM_TEST_PORT`; the cloned voice-chat UDP port is test port + 100), online mode remains true. Modes: `seed`, `bazaar`, `cuts`, `failure` (only real replacement failure + recovery), `all`.

For manual launches in an already prepared isolated fixture:

```sh
/path/to/java17 -Xms512M -Xmx3G \
  -Dgrxmteams.integration=true -Dgrxmteams.integration.phase=seed \
  -jar fabric-server.jar nogui
```

Phases:

- `seed` → `verify` → `expire`, same fresh world: exact native transfers, role/ownership/cap/overflow denials, rate-reload proration snapshot, persisted bank+lease, no arrears/nonpayment suspension. In the **player-free End with zero vanilla forced chunks**, an actual Create creative motor drives an assembled mechanical-bearing stone contraption. Compare real bearing angle at ticks **400 and 437**, observe an invulnerable no-gravity armor stand beyond 350 ticks, and require a furnace ingot. This deliberately exceeds the dimension's 300-empty-tick gate.
- `bazaar` → `bazaar-verify`, a separate fresh world: actual ordinary inventory removals/native payouts/player receipts, GUI Sell 1, icon/movement denial, shared member exhaustion, target-balance overflow, fixed prices across reload, stale generation denial, monotonic rollback behavior, complete bank/journal/quota restart. POV binding and per-viewer display packets are exercised internally; client visual inspection remains separate.
- `cut-prepared`, `cut-player`, `cut-committed`, **each its own fresh world**: at the real verified boundary, call `Runtime.halt(23)` so no shutdown save can make the test falsely pass. Require the phase-specific `-CUT.txt` and exit 23. Then `recover-cut` on the same world checks PREPARED quarantine or COMMITTED no replay, exact bank credit and matching player proof.
- `cut-failed-commit`: after verified player removal, replace the bank target with a directory to force a real atomic replacement failure. Require uncertain RAM credit to remain unspendable through native debit/credit/setBalance and shutdown to be requested. The harness restores the preserved PREPARED bank image only after process exit, then checks restart quarantine.
- `corrupt`: on a deliberately damaged **copy**, requires unavailable service/no tickets and preserves invalid attachment through native serialization. Whole-bank corruption/missing-file preflight can stop before the tick driver runs: check its refusal log and unchanged bad file, not a driver PASS.

Whole-bank guard verification on copies of a completed fixture:

```sh
python3 scripts/verify_bank_guards.py \
  /home/civ/.hermes/cache/scratch/grxm-acceptance-NEW/bazaar \
  /home/civ/.hermes/cache/scratch/grxm-bank-guards-NEW
```

This intentionally truncates/removes only cloned bank files, then separately uses the doubly guarded `damage-native` phase to write a malformed native account under an intact team envelope and halt. It requires the relevant preflight/fallback refusal log and verifies the bad bytes remain unchanged or the missing file remains absent.

Each normal phase requires its exact `grxmteams-integration-<phase>-PASS.txt` and no `grxmteams-integration-FAIL.txt`; exit 0 alone is not acceptance. The harness records `results.json` plus all full server logs. Actual client shop/hover text, floating item/text appearance, signed chat/badge/resource-pack display, and deliberate production old-mint retirement remain separate release gates. Unit tests do not claim to exercise server mixins.
