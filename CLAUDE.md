# CrossSync — notes for Claude

Android app that syncs Garmin Connect daily data to Health Connect. The owner talks in Chinese; reply in Chinese.
Version history and feature lists (v1.0, v1.1, v1.2): `docs/VERSIONS.md`.
Snapshots: `baseline-v1.0` (Release 1.0.8), `baseline-v1.1` (Release 1.1.13), `baseline-v1.2` (Release 1.2.16, final 1.2). To roll back to 1.1 or 1.2, use the same steps as below with that branch and `printf '1.x\n' > VERSION`.
Product overview, working agreements and the open backlog live in the claude.ai Project "CrossSync"
(docs `claude/CrossSync-guide.md` and `claude/status-and-backlog.md`); update the backlog doc when work finishes.
Personal health data (the Takeout export and `crosssync-history.json`) must never be committed: the repo is public.
Modules: Sync (`core/`, `garmin/`, `healthconnect/`, `sync/`, the Sync screen in `ui/MainActivity.kt`) and Fitness (`fitness/`, read-only from Health Connect). Keep them independent.

## Build and release
- Every push to `main` builds a signed APK in GitHub Actions and publishes Release `v<VERSION>.<run_number>`. The owner installs and updates through Obtainium.
- Versioning: the `VERSION` file holds the feature version (`1.0`, `1.1`, ...). Bump it when a release adds features; the last number is the CI run number (only grows, also used as versionCode).
- This session type cannot read Actions logs through the API. The workflow writes the log to the `ci-logs` branch instead: `git fetch origin ci-logs && git show FETCH_HEAD:ci-status.txt` (and `build.log`).
- Never print the signing password (no `set -x` in the signing step). Keystore: `signing/release.p12`, secret `SIGNING_PASSWORD`.
- Commits that only change docs: put `[skip ci]` in the message so no release is built.
- Tags and release edits are blocked for Claude here; snapshots are branches.

## Rolling back to v1.0 ("回滚到 1.0")
v1.0 is frozen on branch `baseline-v1.0` (commit `bad9e99`, same code as Release v1.0.8).

Android will not install a lower versionCode over a higher one, so do NOT force-push main back.
Instead, make a new commit on `main` whose app files equal v1.0; CI then builds it with a new, higher
version number and Obtainium updates normally, keeping the user's login and data:

```bash
git fetch origin baseline-v1.0
git restore --source=FETCH_HEAD --staged --worktree -- app build.gradle.kts settings.gradle.kts gradle.properties .github branding
printf '1.0\n' > VERSION   # the baseline predates this file; its build files hardcode 1.0 anyway
git add VERSION
git commit -m "Roll back app to v1.0 baseline"
git push origin main
```

Keep `docs/`, `CLAUDE.md` and `signing/` as they are on main. Then wait for `ci-logs` to show the new run
succeeded, tell the user the new release number (it will read 1.0.<run>), and ask them to update in Obtainium.
Rolling back from 1.1 removes the Fitness tab; its cache file `fitness.json` stays on the phone unused, and the extra
Health Connect read permissions stay granted until the user removes them. Data compatibility: v1.0 reads the ledger JSON with `optString`, so a ledger written by a later version still loads;
if a later version added new Health Connect data types, records it wrote stay in Health Connect.
