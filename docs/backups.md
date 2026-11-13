# Backups and recovery

Minecraft is snapshotted every day at 12:00 UTC with up to five minutes of jitter. A backup sends the normal `stop` command, waits for all dimensions to save, and requires a successful process exit plus a clean-stop proof bound to that exact systemd invocation. A timeout, signal termination, or stale proof rejects the snapshot. An already-stopped server is rejected too because its prior invocation cannot be independently established; start Minecraft before requesting a consistent snapshot. It copies the complete server plus the small runtime recovery payload (launcher, bootstrap, and current pinned source), then restarts Minecraft before any cloud traffic. Reproducible deployment caches are excluded. This creates a short maintenance interruption rather than risking an inconsistent live copy. `minecraft-backup.timer` is persistent, so a missed schedule runs after boot.

Local snapshots live at `/var/backups/minecraft/snapshots/<UTC timestamp>/server` and `runtime`. The original pre-first-boot world is also retained separately as `/var/backups/minecraft/original-world-before-first-boot.tar.gz`. These are root-readable and not publicly served. The original local `backups/7/world` is untouched.

Restic encrypts and deduplicates off-site snapshots in a private Backblaze B2 bucket. Successful uploads are read back before the local copy is marked uploaded. Failed or unconfigured uploads leave snapshots pending, and `minecraft-offsite.timer` retries hourly without stopping Minecraft. Keep the newest seven local snapshots and every unuploaded snapshot; the script refuses to create another copy if there is insufficient disk space. Pending copies can therefore fill disk during a long outage: monitor failed units and free space rather than assuming retries are alerts.

Remote retention keeps 14 daily, 8 weekly, and 6 monthly snapshots, using original UTC capture times even when uploads are delayed by an outage. Retention only runs after successful connectivity/upload validation. Incomplete `.partial` copies are never uploaded; at most one previous incomplete copy is retained for diagnosis, separate from completed pending snapshots. The repository encryption password is generated independently from the B2 application key and stored at `/etc/minecraft/restic-password`. Copy this password to your password manager using your own secure terminal. Losing the VPS and this password makes encrypted backups unrecoverable, even if the B2 account survives. Neither key nor password belongs in Git or chat.

## Activate Backblaze B2

Create a private bucket and a bucket-scoped application key. Restic needs bucket metadata and file list/read/write/delete access. Do not apply a B2 lifecycle policy that deletes restic objects independently: deleting shared data chunks can corrupt multiple snapshots. B2 billing and repository storage are the operator's responsibility.

On the VPS:

```sh
install -m 600 /opt/minecraft/backup.env.example /etc/minecraft/backup.env
nano /etc/minecraft/backup.env
```

Set `RESTIC_REPOSITORY=b2:YOUR_BUCKET:grxm-smp`, `B2_ACCOUNT_ID` to the application key ID, and `B2_ACCOUNT_KEY` to the application key. Keep `RESTIC_PASSWORD_FILE=/etc/minecraft/restic-password`. Use a simple bucket name/path without shell metacharacters; the file is sourced as root-only shell configuration. Then initialize the new repository once and trigger upload:

```sh
set -a
. /etc/minecraft/backup.env
set +a
restic init
systemctl start minecraft-offsite.service
journalctl -u minecraft-offsite.service --no-pager
restic snapshots
restic check
```

If `restic init` reports an existing repository, inspect it and confirm the password instead of deleting or recreating anything. The uploader never auto-initializes after an authentication/network error. Until these steps succeed, only local backups are active; a configured timer is not proof of off-site protection.

Review B2 **Caps & Alerts** for download bandwidth and Class B transaction limits. Full restore tests can reach a low account cap even when uploads succeeded. `download_cap_exceeded` blocks repository reads/checks and therefore further uploads until the cap resets or the operator raises it; retries preserve local snapshots but cannot override provider limits. Choose limits and billing exposure yourself. Idle hourly retries make no remote requests when nothing is pending.

## Verify and restore

```sh
systemctl list-timers minecraft-backup.timer minecraft-offsite.timer
systemctl --failed
journalctl -u minecraft-backup.service -u minecraft-offsite.service --since yesterday
smp-backup --local-only
```

After activating B2, restore a snapshot to a separate directory and compare representative world files before trusting it:

```sh
set -a; . /etc/minecraft/backup.env; set +a
restic snapshots
restic restore SNAPSHOT_ID --target /var/backups/minecraft/restore-test
restic check --read-data-subset=10%
```

Restic stores absolute source paths beneath the restore target. Find the restored `server/world/level.dat`, region files, playerdata, and `world/camerapture` beneath `var/backups/minecraft/snapshots/<timestamp>/`. Compare hashes against the corresponding immutable local snapshot; never compare against the changing live world. Metadata-only `restic check` does not prove every remote byte can be restored.

For a local rollback, choose a completed snapshot, stop Minecraft, preserve the failed live directory, copy the snapshot's `server` back to `/srv/minecraft`, restore ownership, and start:

```sh
systemctl stop minecraft
mv /srv/minecraft /srv/minecraft.failed-$(date -u +%Y%m%dT%H%M%SZ)
cp -a /var/backups/minecraft/snapshots/TIMESTAMP/server /srv/minecraft
chown -R minecraft:minecraft /srv/minecraft
systemctl reset-failed minecraft
systemctl start minecraft
smp-status 127.0.0.1
```

For cloud recovery, substitute the separately restored `server` directory. Restore `runtime` to `/opt/minecraft` only when the matching Fabric/bootstrap/runtime is needed; service files and OS packages are maintained separately from the world. Keep the failed directory until an authenticated client confirms inventories, builds, dimensions, graves, photos, and banks. Restore rolls back all play since the chosen snapshot.
