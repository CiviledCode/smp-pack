# GRXM SMP

![GRXM SMP icon](assets/grxmsmp.png)

A single-server Fabric 1.20.1 survival pack. The existing world is preserved; there is no Velocity proxy, cross-server inventory service, or experimental world conversion. Minecraft and Fabric Loader stay pinned to 1.20.1 and 0.18.3. This repository is the authoritative client/server mod manifest; world data, player lists, credentials, and backups stay off GitHub.

## Play

Install [Prism Launcher](https://prismlauncher.org/download/), sign in with your Microsoft Minecraft account, then download [GRXM-SMP-Prism.zip](https://github.com/CiviledCode/smp-pack/raw/refs/heads/master/launcher/GRXM-SMP-Prism.zip). In Prism choose **Add Instance → Import → From ZIP**, select the ZIP, and launch. Use Java 17 for Minecraft 1.20.1; Prism can manage the Java installation. The instance allows up to 6 GiB RAM, so a machine with at least 8 GiB total is recommended.

The server address, `mc.civiled.net`, is already in Multiplayer. Access is restricted to the whitelist. Existing players from the migrated world are included; ask the operator to add new friends.

Before every launch, packwiz downloads the latest pinned mods and managed configuration from this repository's `master` branch. Subsequent launches normally download only changes. Internet access is required. A failed pre-launch update stops launch rather than deliberately running an out-of-date pack. Use a fresh imported instance instead of copying an old `mods` folder: unmanaged manually added mods are not guaranteed to be removed by packwiz.

Bliss 2.1.2 and Complementary Unbound r5.8.1 are bundled as optional client-side shader choices, alongside the original Particle Rain 3.0.6 cosmetic mod. Shaders remain disabled by default so friends can opt in under **Options → Video Settings → Shader Packs** in Iris according to their GPU. Particle Rain and shader packs are never installed on the dedicated server. Distant Horizons, Euphoria Patches, the other rain/leaf replacements, and the old multi-server integrations remain excluded. Sodium, Lithium, FerriteCore, ModernFix, Entity Culling, and ImmediatelyFast provide conservative performance improvements; spark is available for diagnosis. Content mods such as Create, Backpacked, BetterEnd, BetterNether, Camerapture, and Farmer's Delight Refabricated are retained because the world depends on them. Do not remove world-content mods as a performance shortcut.

The supplied 64×64 GRXM SMP artwork is the server-list icon, Prism instance icon, and repository pack branding. Existing instances receive shader/particle updates on next launch. For Prism's launcher-level icon, change the icon manually using the artwork, or import the updated ZIP as a new instance and keep your original until you have transferred any local settings.

## Maintain the pack

Install [packwiz](https://packwiz.infra.link/installation/) and Python 3.11+. Edit metadata through packwiz or adjust files under `config/`, then refresh and verify:

```sh
packwiz modrinth install <project-slug>
packwiz refresh
python3 scripts/verify-pack.py --download
python3 scripts/build-client.py
```

Review each mod's supported Minecraft version, dependencies, and `side` before publishing. Do not run blanket updates without testing: Create and its addons can require exact paired versions. All downloads are pinned by hash, use direct HTTPS URLs, and do not require friends to provide a CurseForge API key. Some original artifacts remain on their publishers' ForgeCDN URLs; most use exact Modrinth artifacts.

The ZIP builder downloads the official packwiz bootstrap v0.0.3 and verifies its pinned SHA256. It writes `dist/GRXM-SMP-Prism.zip`; copy a rebuilt ZIP to `launcher/GRXM-SMP-Prism.zip` when changing launcher defaults or Minecraft/Fabric versions. Ordinary mod/config updates do not require friends to reimport the ZIP. The bootstrap updates packwiz-installer itself; the mod versions remain controlled by `pack.toml` and `index.toml`. [Official installation behavior](https://packwiz.infra.link/tutorials/installing/packwiz-installer/) and [Prism's pre-launch exit handling](https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/launch/steps/LaunchCommand.cpp) explain the integration.

Use a candidate branch and test client plus server together before merging to `master`. For a coordinated update, stop announcing availability, deploy the reviewed full commit SHA to the server, then fast-forward `master` to that same commit. Friends receive it on next launch. Avoid publishing an incompatible client manifest before the server is ready. Minecraft/Fabric version changes require additional launcher/server-runtime migration review, not only a mod update.

[Server operations](docs/server.md) covers deployment, console access, and rollback. [Backups and recovery](docs/backups.md) covers daily local snapshots, encrypted Backblaze B2 uploads, retention, and restore verification.
