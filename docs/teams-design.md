# Teams, badges, and rented chunk ticking

This design adds a server-only Fabric 1.20.1 mod integrated with the pack's exact Create: Numismatics 1.0.15. A small server resource pack supplies graphical chat badges; friends do not need another Java mod. The implementation belongs under `custom-mods/grxm-teams`; the existing pack remains the distribution authority.

## Accepted behavior

Teams have an owner, officers, and members. Players belong to at most one team, join by invitation, and can choose a validated team name, chat color, and one of the configured badges. Vanilla scoreboard team formatting supplies the badge/team prefix and player-name color in chat and name displays. Existing unrelated scoreboard teams must not be silently overwritten.

Each team has a real Numismatics treasury account. Members can contribute from their personal Numismatics balance; only authorized team roles may withdraw or manage loaded chunks. This is the existing currency, not a second economy. Account ownership and permissions are checked on every operation.

The weekly bill is the TOTAL team bill, not a per-chunk rate:

    TEAM_CHUNK_COUNT * SCALING_FACTOR * TEAM_MEMBER_SCALING * TEAM_MEMBER_COUNT

Rates, denomination, period, caps, permissions, badges, messages, and presentation are configurable using Cloth Config AutoConfig, already installed in this pack. Decimal rates use exact arithmetic and round up once to a whole smallest Numismatics unit, with overflow and negative amounts rejected. Billing starts disabled until the operator explicitly chooses rates. The default period is seven real-life days, independent of game ticks or restarts.

A funded first lease is prepaid. Chunk additions during an active lease pay the prorated increase for the remaining term, rather than receiving a free week or resetting renewal for the whole team. Removing chunks does not refund the prepaid term; the next renewal reflects the smaller set. Team membership is priced at renewal, with the member-count snapshot fixed during the prepaid term so role/invitation operations do not unexpectedly transfer money.

On expiry, renew once from the current real time if the treasury covers the current bill. Otherwise remove this mod's loading tickets immediately, retain chunk selections, and resume after funding. Do not accumulate downtime debt or replay every missed week after a long shutdown. Never delete builds or chunks for nonpayment.

## Selecting and ticking chunks

A `/smpteam` command selects the current chunk, with list/remove/status/cost commands; it does not overwrite Minecraft's existing `/team` command. A selector can use an existing vanilla item with a server-owned NBT marker, avoiding a custom item registry or client mod. Ownership is by dimension plus chunk coordinates; duplicate selection is idempotent, and another team's selected chunk cannot be taken over.

The default server-wide cap is 64 selected chunk centers, with separate configurable per-team limits and allowed dimensions. Inactive selections still reserve their coordinates and count toward selection limits; the operator can remove abandoned selections. This is chunk-loading ownership, not a block-protection/land-claim system.

Use a dedicated ticket type that reaches entity-ticking status, so Create machinery actually runs without players. Remove only this mod's tickets, never vanilla `/forceload` entries or another mod's tickets. Reconcile tickets after startup, expiry, configuration changes, and disbanding, with bounded per-tick admission to avoid a startup spike. Ticket propagation also loads neighboring chunks, so 64 selected centers is not a promise of only 64 resident chunks. Natural mob spawning and other player-distance mechanics retain vanilla rules.

In exact Minecraft 1.20.1, radius 2 produces ticket level 31, but a dimension without players or vanilla forced chunks stops entity AND block-entity ticking after its empty-time counter reaches 300 ticks. Reset that dimension's idle counter while eligible team tickets are actually installed, and stop the keepalive when the last eligible ticket is removed. Do not manufacture vanilla forced chunks to work around this gate. Throttle additions, not expiry/failure removals; revalidate queued additions before installing them. An integration check must run past 300 ticks with no player in the dimension and observe real Create block-entity and entity progress.

## Persistence and payment safety

Team state, lease receipts, and native bank balances must share the Numismatics bank saved-data logical consistency boundary. A tightly scoped, version-pinned mixin can attach the team's serialized state to the same `numismatics_bank` NBT root. Money and lease state are serialized together, not in independently ordered files. Prove that hook against the actual production JAR before relying on it. If integration is unavailable or state validation fails, disable financial actions and chunk tickets rather than creating a replacement account or loading for free.

This is not a claim of atomic or durable disk transactions. Exact vanilla 1.20.1 `SavedData.save(File)` writes directly to the existing compressed file, catches I/O errors, and clears its dirty flag even after a failed write. A returned save or cleared dirty flag is not commit proof. Validate actual persisted data where financial admission depends on it; use a verified temporary-write/replacement strategy for this bank or treat interrupted/corrupt writes as operator-recovery conditions. Missing/corrupt previously initialized state must not silently become a fresh treasury. Readback establishes process-visible persistence, not an untested fsync/power-loss guarantee.

All mutations run on the server thread. Validate authorization, limits, arithmetic, and affordability before changing accounts or selections. Bank mutation and team-state update complete in the same server-thread operation, then mark the shared bank saved data dirty. Configuration reload validates a complete candidate before replacing the live configuration; invalid reloads preserve the old settings.

Native Numismatics balances are signed Java integers and its deposit method uses unchecked addition. Check the destination's existing balance PLUS the complete deposit/payout before calling it. Preserve each prepaid rent term's rate, period, and member-count basis across reloads; adding chunks must use that stored term, not a newly configured price or a reset anniversary.

The existing daily world backups include the bank saved-data file. No external database or separate billing daemon is needed. Server shutdown does not provide offline simulation; wall-clock leases still expire while the server is off, with no arrears when it comes back.

## Acceptance boundary

Verify exact price arithmetic, proration, insufficient-funds suspension, restart/long-downtime renewal, authorization denial, chunk ownership and caps, and configuration validation with focused repeatable tests. Exercise the real Numismatics save/load hook and balance/lease round trip in an isolated server using the production modpack. Verify actual Create-relevant ticking with no nearby player and confirm ticket removal on expiry. Inspect graphical badges in a real client using the resource pack; preserve signed-chat handling rather than replacing player messages with fake system chat. A successful compile alone does not establish any of these integration contracts.

Do not test economic mutations or artificial clock changes against the production world. Live deployment requires a backup and passing isolated acceptance; prices remain disabled until the operator configures them.
