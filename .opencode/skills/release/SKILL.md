---
name: release
description: Use when the user says «релиз», «выпусти», «release», or asks to tag/publish a version of the IntelliJ-EmmyLua plugin — building both artifacts (IDEA2025.3 and IDEA2026.2), bumping gradle.properties, creating the annotated tag and the GitHub release with the two zip files.
---

# Releasing the IntelliJ-EmmyLua plugin (`/Users/paf/Documents/IntelliJ-EmmyLua`)

A release = bump the version → build **both** artifacts → tag → GitHub release with two zips.

## 0. Before building

- The working tree is `master`, clean, `origin/master` pulled.
- Ask for the version when it is not named. The scheme is `1.4.26-pafN` in
  `gradle.properties`; the next one is the next `pafN`. If the file already has
  a bumped but unreleased version (no tag/release), offer to release that one
  instead of bumping again.
- Bump: `sed -i '' 's/^version = .*/version = <ver>/' gradle.properties` and
  commit `chore: bump version to <ver>` (English, like the whole repository).
- **Stop the sandbox before building**: `pkill -f "com.intellij.idea.Main"`,
  otherwise `runIde` holds `build/` and the build fights it. Start the sandbox
  again after the release (project rule: launch it after a build; the user
  closes it themselves).

## 1. Build both artifacts

```bash
cd /Users/paf/Documents/IntelliJ-EmmyLua
./gradlew buildPlugin -DIDEA_VER=2025.3 -x bunch -x buildSearchableOptions --offline
./gradlew buildPlugin -DIDEA_VER=2026.2 -x bunch -x buildSearchableOptions --offline
```

Result: `build/distributions/IntelliJ-EmmyLua-<ver>-IDEA2025.3.zip` and
`...-IDEA2026.2.zip`.

Key details:

- **`-x bunch` is mandatory**: `bunch` runs `git reset --hard` and
  `git clean -d -f` — it would wipe uncommitted work.
- **Build 2026.2 WITHOUT `-PlocalIde`**. Gradle takes the SDK from its own cache
  (`~/.gradle/caches/8.14/transforms/*/transformed/ideaIU-2026.2-aarch64`,
  build `IU-262.8665.258`), and every dependency (`java-compiler-ant-tasks`,
  `java-gui-forms-*`, `util-jdom`) for that build is cached in
  `~/.gradle/caches/modules-2`. `-PlocalIde="/Applications/IntelliJ IDEA.app"`
  points at the locally installed IDE (for example `262.10968.63`), whose
  artifacts are usually not cached → an offline build fails.
- `--offline` is the norm: `download.jetbrains.com` answers 451 (VPN needed).
- `instrumentCode: New files were found` / `Failed to delete` — that is
  `.DS_Store` inside `build/instrumented`: `rm -rf build/instrumented` and retry.
- If the needed SDK build/dependencies are still missing and there is no
  network: create a local file Maven repository out of the cached `262.8665.258`
  artifacts renamed to the required version (plus `maven-metadata.xml`) and pass
  `-I init.gradle` with
  `allprojects { repositories { maven { url = uri("<dir>") } } }`. This is a
  local-machine workaround; never commit it.

Check the artifacts:

```bash
ls -la build/distributions/*<ver>*.zip
unzip -l build/distributions/IntelliJ-EmmyLua-<ver>-IDEA2025.3.zip | head
```

It is worth confirming the fresh classes/scripts are inside (`unzip -l` on the
jar under `EmmyLua/lib/`).

## 2. Tag

```bash
git tag -a v<ver> -m "<KEY> <summary>"
git push origin v<ver>
```

The tag message follows the repository style: `LUAUNIT #NN <short summary>`
(see `git for-each-ref --format='%(contents:subject)' refs/tags | tail`).

## 3. GitHub release

The repository is `neopaf/IntelliJ-EmmyLua` (pass `--repo` explicitly: `gh`
defaults to the `upstream` fork `EmmyLua/IntelliJ-EmmyLua`, where we only have
read access).

```bash
gh release create v<ver> --repo neopaf/IntelliJ-EmmyLua \
  --title "v<ver> — <summary>" \
  --notes "<paragraph: what changed, issue links #N>

Artifacts (the same source, two platform targets):
- IntelliJ-EmmyLua-<ver>-IDEA2025.3.zip — IntelliJ IDEA 2025.3 (build 253.*)
- IntelliJ-EmmyLua-<ver>-IDEA2026.2.zip — IntelliJ IDEA 2026.2 (build 262.*, depends on the bundled intellij.testRunner.plugin)" \
  build/distributions/IntelliJ-EmmyLua-<ver>-IDEA2025.3.zip \
  build/distributions/IntelliJ-EmmyLua-<ver>-IDEA2026.2.zip
```

Verify: `gh release view v<ver> --repo neopaf/IntelliJ-EmmyLua --json
tagName,assets` — both zips present, the release is Latest.

## 4. After the release

- Launch the sandbox: `./gradlew runIde -DIDEA_VER=2025.3 --offline` (in the
  background, leave it running; the user closes it themselves).
- Push nothing else "while at it"; Jira versions and similar only when asked.
- A short report: version, release link, asset list, commits.
