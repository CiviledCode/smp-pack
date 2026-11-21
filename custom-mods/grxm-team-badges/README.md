# GRXM Team Badges — client resource mod

A code-free Fabric client mod bundles the existing eight badge glyphs and PNGs
from `assets/team-badges` unchanged. The `grxmteams:badges` font and texture
identifiers are unchanged, so the server's existing badge components still work.
It contains no Java classes, entrypoints, mixins, nested dependencies, networking,
or join callback. The GRXM Teams server JAR is unchanged.

## Why resources are always enabled

The installed Fabric API 0.92.12+1.20.1 includes resource loader
0.11.12+fb82e9d777. Its `ModResourcePackUtil.appendModResourcePacks` discovers each
mod's root `assets/` with `ALWAYS_ENABLED`; `ModResourcePackCreator` exposes them
through the required **Fabric Mods** resource profile. Vanilla reinserts required
profiles even when the saved resource-pack selection is empty. Thus no
`options.txt`, optional pack selection, third-party loader, or client initializer
is necessary. Resources are available in the normal startup reload, not added
when joining. Like other mod resources, a deliberately higher-priority resource
pack can override individual files; this is forced activation, not tamper-proofing.
Removing/disabling the mod itself is outside that guarantee.

The only production dependency is the existing resource-loader module. Minecraft
1.20.1, Fabric Loader 0.18.3 and Java 17 remain pinned. No existing mod was updated.
Fabric's implementation was inspected in the cached exact-version source JAR;
it is Apache-2.0, and none of its code is copied into this artifact. No new license
grant is inferred for the existing artwork; see [LICENSE](LICENSE).

## Build and verify

From the repository root, with Python 3.11+, the existing Gradle wrapper's supported
JDK (JDK 21 verified), and an installed JDK 17 for the test JVM:

```sh
python3 custom-mods/grxm-team-badges/build.py
(cd custom-mods/grxm-team-badges && ../grxm-teams/gradlew test --no-daemon)
packwiz refresh
python3 scripts/verify-pack.py
python3 scripts/build-client.py
python3 custom-mods/grxm-team-badges/test-distribution.py "$TMPDIR/grxm-badge-install-NEW"
```

The deterministic output is `assets/mods/grxm-team-badges-1.0.0.jar`, distributed
by `mods/grxm-team-badges.pw.toml` on **clients only**. The builder compares every
canonical source entry with the existing legacy ZIP, checks eight unique glyphs
and referenced PNGs, and packages identical bytes. An asset change requires a
reviewed new version and metadata hash; do not replace an already released
version's content. The legacy ZIP is retained for fallback clients.

The Gradle project is verification-only: do not publish its empty Loom JAR.
The Java 17 test loads the actual produced resource JAR into a real Fabric loader
container, then calls the exact Fabric resource provider and vanilla
`PackRepository`. It asserts the real profile is required, survives empty selection
and repository reload, and reads every font/texture byte through selected packs.
Its negative control removes the installed mod and requires the profile to be
absent. It also parses real client/server environment metadata. This is a non-GUI
resource-discovery/selection check, not a Minecraft startup, font rendering,
authenticated join, or full-pack compatibility test. Reports live under
`build/reports/tests/test/`.

The distribution harness uses the shipped hash-verified bootstrap and a minimal
scratch pack containing the new production metadata, changing only its artifact
URL to a temporary loopback HTTP mirror of the built JAR. It checks exact installed
bytes on the first and repeat client runs and absence on a server install. The
bootstrap may download its installer update; the artifact never uses an unpublished
public URL. Installer v0.5.14 accepts `file:` manifests but requires HTTP(S) artifact
URLs. Logs and `results.json` stay in the fresh output directory under `$TMPDIR`.
This focused check is not an installation audit of every other pack download.

### Graphical acceptance boundary

A bounded disposable **minimal** client (Minecraft 1.20.1, Loader 0.18.3,
Fabric API 0.92.12+1.20.1 and the badge JAR) was exercised with Java 17, a 2 GiB
heap and Xephyr/Mesa llvmpipe. It started with `resourcePacks:[]`, reached the
visible title menu, showed **Fabric Mods** selected with no removal control,
and retained it after an attempted deselection. Saving that screen completed an
actual resource reload with `grxm_team_badges` still listed, then returned to the
title menu and exited normally through Quit Game (exit 0). This verifies startup,
forced selection and reload in that minimal client, not the full gameplay pack.
No production endpoint or world was present in the disposable instance.

To repeat an isolated manual run after building, with an existing isolated X11
display and JDK 17 installed:

```sh
cd custom-mods/grxm-team-badges
DISPLAY=:93 LIBGL_ALWAYS_SOFTWARE=1 __GLX_VENDOR_LIBRARY_NAME=mesa ALSOFT_DRIVERS=null \
  ../grxm-teams/gradlew runClient -PbadgeRunDir="$TMPDIR/grxm-badge-menu-NEW" --no-daemon
```

Use only a fresh scratch run directory, never a normal player's instance. Check
the resource-packs screen and logs, then quit normally; this command does not
install the full modpack or authenticate multiplayer.

## Rollout status and server requirement

Release **1.6.1** distributes this artifact from the immutable `v1.6.1` tag.
Publish that tag before advancing the friends' moving `master` update branch.
Local build/test success is not installation in friends' running clients.
Existing Prism instances receive it through packwiz at their next launch after
publication; no reimport or launcher ZIP replacement is required for this mod-only
change.

After updated clients are distributed, the operator must separately set
`resourcePack.enabled` to **false** in the server's existing
`config/grxmteams.json`, preserving all other operator settings, then reload Teams
configuration with `/smpteam admin reload` (or perform a coordinated clean restart).
The existing server default/request code is intentionally unchanged. Do not
merely set `required=false`: that still sends the resource-pack request and can
cause a connection-time reload. Check that `server.properties` does not send a
separate resource pack, too. Older clients without the bundled mod will lose
badge artwork when the server request is disabled, though vanilla menus still work.

Until that operator rollout, a server can still request the old ZIP and cause its own reload even
though the same badge resources are bundled. A real updated-client join must
verify visible badges and the absence of a server-pack download/reload after the
request is disabled. Badge glyph rendering, a full-pack launch and live join remain
unverified; the minimal title-menu check above does not claim them.
