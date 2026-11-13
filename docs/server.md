# Server operations

The deployment is one Fabric JVM on `mc.civiled.net`, managed by `minecraft.service`. Game files and the complete migrated `backups/7/world` live in `/srv/minecraft`; the root-owned Fabric launcher and deployment tooling live in `/opt/minecraft` and `/usr/local/sbin`. The service runs as the unprivileged `minecraft` account with an 8 GiB maximum heap on the 12 GiB VPS. World data remains outside the repository.

Java 17, Fabric Loader 0.18.3, and Minecraft 1.20.1 are deliberately pinned. The root-owned launcher `/opt/minecraft/fabric-server.jar` comes from Fabric's official metadata API (`.../loader/1.20.1/0.18.3/1.1.1/server/jar`); its SHA256 is `1042bf7e3073b9d775e86e48a295587bc8b9bd61f9b55cae7a3bbd321560fc33`. Service startup never automatically changes the deployed modpack. Modpack updates are explicit reviewed deployments.

## Operate

```sh
ssh root@mc.civiled.net
systemctl status minecraft
journalctl -u minecraft -f
smp-status 127.0.0.1
smp-console list
smp-console whitelist list
smp-console whitelist add PlayerName
systemctl restart minecraft
```

`smp-console` writes one command to a local mode-0600 named pipe, not a network-admin port. Run it as root or `minecraft`. The service's stop command waits for Minecraft to finish saving all dimensions before systemd considers the process stopped. RCON and query are disabled. The firewall exposes SSH TCP 22, Minecraft TCP 25565, and Simple Voice Chat UDP 24454, for both IPv4 and IPv6.

The server uses authenticated online UUIDs, enforces the whitelist, and starts automatically after VPS reboot. The eleven existing world players were resolved through Mojang and added to the live whitelist. Whitelisting is not operator permission; use `smp-console op PlayerName` only when intentionally granting administrator access. Preserve any existing operator list rather than granting all whitelisted players operator status.

View distance is 8 and simulation distance is 6, a conservative starting point for three VPS CPU threads. Preserve simulation mechanics and content mods before considering more aggressive optimization. spark can measure actual load; an idle successful boot is not a populated-server performance benchmark.

## Deploy a reviewed update

Push a candidate commit to GitHub, then pass its full 40-character commit SHA, not a moving branch name:

```sh
ssh root@mc.civiled.net smp-update FULL_COMMIT_SHA
```

`smp-update` downloads that exact repository archive, uses packwiz to materialize its server-side payload in a fresh staging directory, and completes downloads before downtime. It takes a consistent local snapshot, stops Minecraft, synchronizes managed mods/config, records `/srv/minecraft/deployed-revision`, and restarts. A Minecraft status-protocol probe verifies readiness. A real authenticated modded-client join is still required before announcing an update.

Deployment and backup locks prevent overlapping mutation. The command intentionally refuses a previously staged revision instead of deleting an uncertain partial operation; inspect `/opt/minecraft/packs/<sha>` and `/opt/minecraft/staging/<sha>` before retrying. Successful deployments retain the current and one previous completed cache plus at most one incomplete cache; they never evict pending backup snapshots. Retired previously managed configuration is moved to `/var/backups/minecraft/retired-config/<sha>` rather than left active or removed with a blanket config deletion. Server-generated/unmanaged configuration is preserved; symlinked configuration paths are rejected before root-owned synchronization. If startup fails, the command reports failure rather than claiming success. There is no automatic world rollback: an operator should inspect logs and restore deliberately using the backup guide.

The updater does not overwrite `server.properties`, whitelist, operators, service units, bootstrap, or Fabric launcher. Runtime changes must be reviewed and installed explicitly from `server/`. The archive contains tooling for reproducibility, but tools are not part of the game pack index. Do not add world data, original prompts, credentials, or personal Xaero exploration caches to Git.

## Known compatibility limits

BCLib's server classes have client-only type references that Lithium cannot analyze. Lithium logs warnings and falls back to conservative behavior for those classes; the existing dimensions still load. ModernFix/Lithium also report an overlapping chunk-ticket optimization, with one overwrite skipped. Do not hide these warnings or call them proof that every optimization applies. Preserve BCLib/BetterEnd/BetterNether: the world uses their codecs and saved blocks.

The original export's duplicated WTHIT override was removed in favor of one managed version, and the obsolete duplicate Farmer's Delight implementation from the historical GitHub pack is not shipped. Experimental local Cobblemon/KubeJS additions were not imported. The original source ZIP and backup directory were left untouched.

A fresh client materialization reached the actual title screen under Java 21 with Mesa software rendering and exited cleanly; all 56 top-level client JARs matched their pins. ImmediatelyFast's `1.20.4` filename is not an incompatibility: its embedded manifest supports 1.20 through 1.20.4 and it initialized on 1.20.1. Delightful Creators reported nonfatal missing food-block model/blockstate variants; inspect those visuals in play. Neither the menu smoke nor the server status probe substitutes for an authenticated player joining and checking inventories, machines, graves, dimensions, and photographs.

## Safety regression checks

Run `python3 scripts/test-safety.py` for shutdown-proof rejection, retired-config preservation, symlink refusal, and bounded deployment caches. On a machine with restic and appropriate lock permissions, `sudo python3 scripts/test-restic-retention.py` uses a disposable local encrypted repository to verify failed-upload preservation, historical capture-date retention, and restore. It never reads production B2 credentials. The deployed timeout path was also exercised against a disposable systemd unit: systemctl returned a successful stop-job exit while the unit Result was `timeout`, and the snapshot authorization check rejected it.
