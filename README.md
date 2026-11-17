# GRXM SMP

![GRXM SMP icon](assets/grxmsmp.png)

A single-server Fabric 1.20.1 survival pack. The existing world is preserved; there is no Velocity proxy, cross-server inventory service, or experimental world conversion. Minecraft and Fabric Loader stay pinned to 1.20.1 and 0.18.3. This repository is the authoritative client/server mod manifest; world data, player lists, credentials, and backups stay off GitHub.

## Play

Install [Prism Launcher](https://prismlauncher.org/download/), sign in with your Microsoft Minecraft account, then download [GRXM-SMP-Prism.zip](https://github.com/CiviledCode/smp-pack/raw/refs/heads/master/launcher/GRXM-SMP-Prism.zip). In Prism choose **Add Instance → Import → From ZIP**, select the ZIP, and launch. Use Java 17 for Minecraft 1.20.1; Prism can manage the Java installation. The instance allows up to 6 GiB RAM, so a machine with at least 8 GiB total is recommended.

The server address, `mc.civiled.net`, is already in Multiplayer. Access is restricted to the whitelist. Existing players from the migrated world are included; ask the operator to add new friends.

Before every launch, packwiz downloads the latest pinned mods and managed configuration from this repository's `master` branch. Subsequent launches normally download only changes. Internet access is required. A failed pre-launch update stops launch rather than deliberately running an out-of-date pack. Use a fresh imported instance instead of copying an old `mods` folder: unmanaged manually added mods are not guaranteed to be removed by packwiz.

Bliss 2.1.2 and Complementary Unbound r5.8.1 are bundled as optional client-side shader choices, alongside the original Particle Rain 3.0.6 cosmetic mod. Shaders remain disabled by default so friends can opt in under **Options → Video Settings → Shader Packs** in Iris according to their GPU. Particle Rain and shader packs are never installed on the dedicated server. Distant Horizons and the old multi-server integrations remain excluded. Euphoria Patches 1.9.3 adds an optional patched variant of Complementary r5.8.1; shaders remain disabled by default. Sodium, Lithium, FerriteCore, ModernFix, Entity Culling, and ImmediatelyFast provide conservative performance improvements; spark is available for diagnosis. Content mods such as Create, Backpacked, BetterEnd, BetterNether, Camerapture, and Farmer's Delight Refabricated are retained because the world depends on them. Do not remove world-content mods as a performance shortcut.

The supplied 64×64 GRXM SMP artwork is the server-list icon, Prism instance icon, and repository pack branding. Existing instances receive pack updates on next launch. For Prism's launcher-level icon, change the icon manually using the artwork, or import the updated ZIP as a new instance and keep your original until you have transferred any local settings.

## Release 1.5.2

Adds Icy's Better Horses 2.1.8, Wakes 0.4.0+1.20.1, Grassier Grass 1.4.5, Cool Rain 1.3.1, Dark Loading Screen 1.6.14, Windy 1.1.1, and Euphoria Patches 1.9.3 for the existing Complementary Unbound r5.8.1. Particle Rain 3.0.6 is already installed and remains unchanged. GeckoLib 4.8.4 is required by Better Horses on both sides; YACL 3.6.6 is required by Windy on clients only. The other new mods are client-only. Wakes is an explicitly approved beta exception; no other prerelease is selected. Existing mod pins, Minecraft/Fabric versions, configs and Create artifacts are preserved, apart from a new Euphoria config disabling its upstream update checker. Cool Rain 1.4.0 is not selected because it requires a newer Fabric Loader. Voxy is not included.

Download/index and side-specific installation checks are not gameplay acceptance. Existing horses, equipment/bonding and persistence across restart need player verification. Check Wakes with optional shaders and Euphoria's first-launch shader generation in-game; no rendering compatibility or connection-root-cause fix is claimed. Dark Loading Screen changes appearance, not resource-loading speed. Normal Prism instances receive the release on next launch without reimporting.

## Release 1.5

Adds Handcrafted 3.0.6, Rechiseled 1.2.6, their required ResourcefulLib/Config Lib dependencies, and client-only Visuality 0.7.1+1.20. The server-only GRXM Teams mod supplies styled teams and eight graphical badges, native Numismatics treasuries, prepaid chunk ticking, and three physically bound rotating weekly buyback counters. No additional client Java mod is needed for teams or shops; accept the small server badge resource pack when joining.

See the [GRXM Teams manual](custom-mods/grxm-teams/README.md#commands) for player commands, placing/binding counters, and operator configuration. Both rent billing and bazaar payouts ship **disabled**: the operator must choose prices/quotas/rent and separately retire the old unlimited creative mint before activation. Existing world, balances, whitelist, and player-owned shops are preserved. All rates use native Numismatics spurs; team-only sale limits intentionally permit team hopping/recreation.

## Maintain the pack

**Release 1.5.1** updates BCLib 3.0.14 → 3.0.18, targeting its EMI hammer-recipe/global-registry error and related BCLib fixes, and adds server-only TimeOutOut 1.0.3 for 120-second network read/keepalive timeouts and a 2,400-tick login timeout. All other existing mod pins, including every Create artifact, remain unchanged. Client settings suppress BCLib/BetterX update checks and update screens plus Xaero World Map update notifications; curated packwiz updates remain enabled. The operator explicitly accepted deployment without staging gameplay validation; startup/status verification is not saved-world, automation, or multiplayer acceptance. The timeout configuration is pinned in `config/timeoutout.json`; it changes network waits, not the server watchdog or authentication. Existing normal Prism instances receive the update through packwiz on next launch; the separate offline staging ZIP does not auto-update.

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

Custom-mod source and build instructions live in [the GRXM Teams manual](custom-mods/grxm-teams/README.md). Its production JAR is published at `assets/mods/grxm-teams-1.0.0.jar`; `mods/grxm-teams.pw.toml` downloads the hash-pinned artifact from the `v1.5` tag. Publish that candidate/tag before staging the server; never move an existing release tag or distribute the integration-test JAR. Keep the JAR version, asset path, metadata hash, and tested bytes aligned when releasing a later mod revision.

Operator guides remain in the local, Git-ignored `docs/` directory and are not published to GitHub. The `server/` directory contains the deployment, clean-stop, backup, status and console tooling. Before an update, complete a consistent full snapshot using `smp-backup --local-only`; never copy a live world as a rollback backup. For a narrow mod update, preserve operator/generated production configs instead of overwriting them with repository defaults. After an updated world has been saved, rollback restores the matching complete world/config/mod/runtime snapshot, not just an old JAR. Preserve the failed copy, and coordinate a reviewed rollback of the public packwiz manifest as well. Economy activation remains an explicit operator choice.
