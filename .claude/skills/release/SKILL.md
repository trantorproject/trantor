---
name: release
description: Release a new version of Trantor. Writes the CHANGELOG from what changed since the last release tag, proposes the next version, and after Nico approves, commits, tags release-X.Y.Z (which publishes to Maven Central), and creates the GitHub Release. Use when Nico asks to release, publish or cut a version of Trantor.
---

# Release Trantor

A release is a commit with `CHANGELOG.md`, `VERSION` and the version in `README.md`, and a tag `release-X.Y.Z` on it.
Pushing the tag is what publishes: the `publish` job of `.github/workflows/main.yml` runs on every tag that starts with
`release-` and sends every module to Maven Central. **A version published to Maven Central cannot be deleted or
replaced**, so nothing is pushed until Nico approves the draft.

`AGENTS.md` says never to commit. Nico invoking this skill and approving the draft in step 4 is his explicit
permission to commit, tag and push this release, and nothing else. Answer Nico in Spanish; write the CHANGELOG in
English, like the rest of the repository.

## 1. Check the starting point

Stop and tell Nico if any of these fails:

- The branch is `main`, the tree has no changes (`git status --porcelain` is empty), and after `git fetch origin
  --tags` it is the same as `origin/main`.
- Every test passes: `./gradlew allTests`. The CI only runs `build`, which leaves the tests tagged `slow` out, so
  this is the only place they run before a release. A successful run prints no summary; the exit code is what
  counts.

## 2. Find what changed

The last release is the newest tag reachable from `main`:

```bash
git describe --tags --abbrev=0 --match 'release-*'
```

Read everything after it:

```bash
git log <last-tag>..HEAD --format='%h %ad%n%B' --date=short
git diff --stat <last-tag>..HEAD
```

The commit messages are detailed, but they are not enough: read the diff of the public API of each module
(`git diff <last-tag>..HEAD -- '<module>/src'`) to confirm what a message says and to find what it left out,
like a renamed class or a removed parameter. Verify against the code, never from memory.

If there is nothing after the tag but release commits, there is nothing to release: tell Nico and stop.

## 3. Check that `docs/` kept up

`docs/` is the documentation agents read. For each module whose public API changed, check whether its doc
(`docs/<module>.md`, and `docs/trantor-ai/` for trantor-ai) changed in the same range. List the changes to the
public API that have no change in the docs, and ask Nico whether to release anyway or update the docs first. Do
not update the docs here: that is a separate job.

## 4. Draft the CHANGELOG and the version, and wait

### The version

`VERSION` holds the current version. Propose the next one from what the release has:

| The release has | While the version is `0.x` | From `1.0.0` on |
|---|---|---|
| A change that breaks the public API | minor: `0.9.0` → `0.10.0` | major: `1.4.2` → `2.0.0` |
| Something added, nothing broken | minor: `0.9.0` → `0.10.0` | minor: `1.4.2` → `1.5.0` |
| Only fixes | patch: `0.9.0` → `0.9.1` | patch: `1.4.2` → `1.4.3` |

Under `0.x` the minor plays the part of the major, as SemVer allows for major version zero. A version with a
pre-release suffix (`0.8.1-beta13`) goes to the next minor without a suffix: `0.9.0`. Moving to `1.0.0` is
Nico's decision, never proposed by the size of a change.

Something breaks the public API when code that compiled against the last release no longer compiles or behaves
differently: a public class, function, parameter or property renamed, moved to another package, removed, or
given another meaning; a default changed; a configuration key renamed.

### The CHANGELOG

`CHANGELOG.md` at the root follows [Keep a Changelog](https://keepachangelog.com), newest version first. Create
it if it does not exist:

```markdown
# Changelog

All notable changes to Trantor. Versions before 0.9.0 are in the git history.

## [0.9.0] - 2026-10-02
...
```

A version has these sections, in this order, and only the ones that have something:

- **Breaking**: what code that worked against the last release has to change. First, because it is what someone
  upgrading needs.
- **Added**: new features.
- **Changed**: changes in behaviour that do not break the API.
- **Deprecated**, **Removed**: what is on its way out, and what went without breaking anything public.
- **Fixed**: bugs.
- **Security**: fixed vulnerabilities.

Each entry:

- **Starts with the module**: `- **trantor-ai:** ...`. Someone upgrading looks for the modules they use.
- **Is written for a person using Trantor**, not as a list of commits. Several commits can be one entry, and one
  commit can be several.
- **In Breaking, says what replaces what**, so the migration is mechanical: "`ServiceTiers` is now
  `OpenAIServiceTiers` or `AnthropicServiceTiers`".
- **Leaves out what a user never sees**: refactors, tests, fixtures, build changes, `docs/`, `AGENTS.md`.
- **Is not repeated across sections**: a breaking change is in Breaking only.

The date is the day of the release.

### Wait

Show Nico the CHANGELOG section and the proposed version, with one line on why that version (which change makes
it a minor and not a patch, for example). Say plainly that approving it commits, tags, pushes and publishes to
Maven Central, which cannot be undone. **Do nothing else until Nico approves.** Apply what he corrects and show it
again.

## 5. Commit, tag and push

`VERSION` has the version on its only line; the build reads it trimmed, so the line break at its end does not
matter.

The installation of `README.md` names the version of the BOM
(`dev.botta.trantor:trantor-bom:<version>`): change it to the new one too.

```bash
echo "X.Y.Z" > VERSION
git add CHANGELOG.md VERSION README.md
git commit -m "release X.Y.Z"
git tag release-X.Y.Z
git push origin main
git push origin release-X.Y.Z
```

## 6. Follow the publish

The tag starts a run of `Build & Publish`: its `test` job, then `publish`. Find it and watch it:

```bash
gh run list --workflow main.yml --branch release-X.Y.Z --limit 1 --json databaseId,status
gh run watch <databaseId> --exit-status
```

If it fails, stop and show Nico the log (`gh run view <databaseId> --log-failed`). Do not move, delete or reuse
the tag, and do not publish again: some modules may already be in Maven Central. Nico decides what follows.

Once `publish` passed, Maven Central still takes up to about 30 minutes to serve the version.

## 7. Create the GitHub Release

With the CHANGELOG section of this version as its notes, without the `## [X.Y.Z] - date` line:

```bash
gh release create release-X.Y.Z --verify-tag --title "X.Y.Z" --notes-file <file with the section>
```

## 8. Tell Nico how it went

- The version, the tag and the link of the GitHub Release.
- That Maven Central can take up to about 30 minutes to serve it.
- **The site.** `../trantor-site/examples/gradle.properties` holds the version of Trantor the site is written
  against (`trantorVersion`). If it is older than this release, say so and name the breaking changes that may
  touch its pages. Do not change the site: it is another repository, and its version moves with its docs.
