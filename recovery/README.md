# Recovery

| Tool | What it does |
|---|---|
| `./undo.sh <box ip>` | Restores packages, stock Home and DNS, and removes the launcher |
| `tools/backup.py` | Saves a manifest before each install. `install.sh` runs it for you |
| `tools/recovery.py` | Keeps the undo record that `undo.sh` reads |
| `tools/check.py` | Reports what a firmware update changed, and can re-apply a list |

Backups stay on your computer in `~/.tvboost`, never in this repository.

**What a backup holds:** package states, settings, the Home app and DNS values. This is a configuration backup only. It
holds no app data, no APKs and no firmware, so it cannot bring back anything that was deleted.

The full guide is in [docs/RECOVERY_GUIDE.md](../docs/RECOVERY_GUIDE.md).
