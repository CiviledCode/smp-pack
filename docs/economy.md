# Teams and the weekly bazaar

The custom server-only mod adds team treasuries, paid chunk ticking, and rotating item-buyback shops. Clients use ordinary Minecraft menus and display entities; only the small graphical badge pack is downloaded when joining. Minecraft stays on 1.20.1.

This page is the operator/player entry point for the 1.5 feature set, not evidence that billing or shops are enabled on the live server. The [mod manual](../custom-mods/grxm-teams/README.md) is authoritative for every configuration field, persistence/recovery, public APIs, and repeatable integration commands. The accepted [team design](teams-design.md) and [bazaar design](bazaar-design.md) explain the decisions.

## Place and bind the three counters

Once the mod is installed, place three supported blocks in the bazaar. Vanilla barrels are a straightforward choice; lecterns, smooth stone, stone, and oak planks also work. Looking directly at each block within six blocks, run the corresponding operator command:

```text
/smpteam admin bazaar bind 1
/smpteam admin bazaar bind 2
/smpteam admin bazaar bind 3
```

Each block becomes the counter for that numbered weekly offer. Binding does not place a block or destroy its contents. Prefer empty barrels so their existing storage is not hidden by the shop interface. Right-click opens a read-only shop menu with Sell 1 / 16 / 64 / All actions. A floating item and text above the counter show the offered item, unit price, your team's remaining allowance, and time until reset. Other teams see their own allowance, not yours. Leave room for the text: roughly six blocks between counters works with the default wording. For a compact stall layout, shorten `bazaar.displayText`; adjacent long hologram labels otherwise overlap.

The same three offers and prices apply to all teams. On reset, the counter positions stay put while their offers change. A broken/replaced counter must be rebound. To move a shop, unbind its slot and bind the replacement block:

```text
/smpteam admin bazaar unbind 1
/smpteam admin bazaar status
```

The old unlimited creative shulker-shell vendor is NOT automatically changed. Before activating the new economy, retire that mint separately after a consistent backup; otherwise it remains an uncapped source of money. Do not bind a native Numismatics vendor or leave its creative-vendor item available for replacement. Other player-owned shops remain outside this migration.

## Configure the economy before activation

The server generates `/srv/minecraft/config/grxmteams.json`. Both `billing.enabled` and `bazaar.enabled` initially remain false. Edit the runtime file, not a pack-managed default, then validate/reload with `/smpteam admin reload`. Invalid reloads retain the previous settings. No restart is needed for ordinary configuration reloads.

Every `bazaar.pool` entry has these fields:

| Field | Meaning |
| --- | --- |
| `itemId` | Exact registry ID, such as `minecraft:shulker_shell` |
| `minItemCount`, `maxItemCount` | Inclusive range for the week's maximum number of individual items EACH TEAM may sell |
| `minItemPrice`, `maxItemPrice` | Inclusive price range in whole spurs per individual item |
| `rarityWeight` | Relative selection weight; larger is more likely, zero excludes the item |

Select prices and quotas deliberately. Generated values are inactive examples, not a recommended balance policy. All money calculations use native Numismatics spurs; changing a display label does not convert coins. Three distinct items are selected without replacement by default. Their weekly unit prices and allowances are rolled once and persisted. Changing the pool does not reroll an active week; it applies at the next reset. The default reset is Monday 00:00 UTC.

Shop payouts go to the selling team's native Numismatics treasury by default. `bazaar.payPersonal=true` instead pays the seller's native personal bank, while still consuming that team's shared allowance. Everyone on a team shares its quota. Per-player limits and anti-hopping locks are deliberately absent: switching/recreating teams can gain another team's allowance.

Chunk rent is the total bill `selected chunks × scalingFactor × teamMemberScaling × team members`, rounded up once to a whole spur. Set positive rates and explicitly enable `billing.enabled` when ready. A funded team prepays its term; an unfunded expiry stops its loading tickets but retains selections. There is no downtime debt. Mid-term additions use the prepaid rate/member snapshot and charge only the remaining-term increase. This is chunk ticking, not land protection.

## Player path

```text
/smpteam create Builders
/smpteam invite FRIEND
/smpteam accept TEAM_UUID
/smpteam style aqua gear Builders
/smpteam treasury balance
/smpteam treasury deposit 64
/smpteam chunks add
/smpteam chunks status
```

Invitation acceptance uses the UUID shown in the invitation. `chunks add` selects the chunk where you stand; `/smpteam selector` supplies a marked compass for right-click selection and sneak-right-click removal. By default owners/officers manage chunks; members can contribute but officers cannot withdraw unless configured. Treasury transfers require billing or bazaar economy activation, and personal transfers require an existing native personal Numismatics account.

Teams retain paid chunk ticking without players in that dimension; vanilla player-distance spawning rules still apply. Selected centers are capped globally at 64 by default, including inactive reservations. Neighboring chunks also load around ticketed centers, so this is not a promise of only 64 resident chunks.

## Recovery and operating limits

Sales use a bounded durable journal to reconcile item removal with bank payment. An interrupted or uncertain transaction quarantines the economy rather than guessing a refund or paying twice. Preserve the bank, sticky initialization guard, player files, logs, and temporary bank evidence together; restore a consistent whole-world backup or follow the [mod recovery procedure](../custom-mods/grxm-teams/README.md#persistence-and-recovery--read-before-enabling). Do not delete guards or journal entries to make an error disappear.

The default journal retains 2,048 sale proofs. There is no automatic compaction; new sales are rejected safely when capacity or the aggregate size bound is reached. Monitor this capacity through `admin bazaar status`; increasing the configured capacity within supported limits is not a substitute for a future verified checkpoint/compaction procedure. File readback and forcing are not a tested guarantee against power loss or mixed-file restores.
