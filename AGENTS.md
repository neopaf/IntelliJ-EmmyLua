# AGENTS.md

## Language

Everything in this repository is done in **English**:

- code, comments, and doc comments — English;
- commit messages — English;
- documentation (`README.md`, `LUAUNIT.md`, and any new docs) — English;
- pull request titles and descriptions — English.

## Project notes

- This is a fork of [EmmyLua/IntelliJ-EmmyLua](https://github.com/EmmyLua/IntelliJ-EmmyLua);
  upstream is merged in periodically, local commits sit on top.
- The LuaUnit integration (JUnit-style test runner) is documented in
  [LUAUNIT.md](LUAUNIT.md); its sources live under
  `src/main/java/com/tang/intellij/lua/debugger/luaunit/` and
  `src/main/resources/luaunit/`.
- Building the plugin (both targets):
  `./gradlew buildPlugin -DIDEA_VER=2025.3 -x bunch -x buildSearchableOptions --offline`
  and the same with `-DIDEA_VER=2026.2`. Do **not** pass `-PlocalIde` for the
  2026.2 build: the SDK comes from the Gradle cache (`IU-262.8665.258`) together
  with its dependencies; a locally installed IDE has a different build and its
  artifacts are usually not cached. `-x bunch` is mandatory — `bunch` resets the
  working tree.
- When both targets are built, build **2026.2 first and 2025.3 last**: the
  build outputs (classes, jars, patched `plugin.xml`) are shared between the
  versions, and the last build decides what `runIde` / `build/runit` sees as
  up to date. Ending on 2025.3 keeps the sandbox start from recompiling; the
  reverse order makes `build/runit` rebuild before every start.
- Releasing (version bump, both artifacts, tag, GitHub release) is described in
  the `release` skill.
- After building the plugin for a piece of work, **launch the sandbox** so the
  change can be tried by hand: `./gradlew runIde -DIDEA_VER=2025.3 --offline`
  (a long-running GUI, start it in the background and leave it running). The
  user closes the sandbox themselves when they are done testing: never restart
  it afterwards, and never treat a closed sandbox as a failure. The sandbox IDE
  log is under `build/2025.3/idea-sandbox/IU-2025.3/log/idea.log`.
- **After a release, do not launch the sandbox at all** — the release is the
  end of the work, the user starts the IDE when they want to check it.

## Sandbox

- Do **not** check the built plugin archive with `unzip -t` (or any other zip
  validation) before launching the sandbox. A `ZipException` / `invalid stored
  block lengths` in the sandbox log is not a corrupted jar: it means the plugin
  was rebuilt while the sandbox was running and the running JVM read the jar
  mid-write. The fix is a restart, not an archive check.
- The order is: stop the running sandbox, build
  (`./gradlew buildPlugin -DIDEA_VER=2025.3 -x bunch -x buildSearchableOptions
  --offline`), then start it (`./gradlew runIde ...`). Never rebuild into the
  sandbox while the IDE is up.
- **Before starting the sandbox, check whether any other IDEA is already
  running** (a regular installed IntelliJ IDEA, or a leftover sandbox JVM) with
  `pgrep -fl 'idea|idea-sandbox'` or `ps aux | grep -i idea`. There is not
  enough memory on this machine for two IDEs at once. If another IDEA is up, do
  **not** start the sandbox: ask the user whether it may be killed, and only
  proceed (kill it, then start the sandbox) after an explicit yes.
- After every sandbox start (a rebuild followed by `runIde`), publish a
  numbered table of the user's standing requests with their status: done and
  waiting for a check, in progress, not done. Drop a row once the user confirms
  it with "OK".

## Diff viewers

- In every diff viewer the plugin opens, **expected is always on the left and
  actual on the right**: `SimpleDiffRequest(title, expected, actual, "Expected",
  "Actual")`. This applies to the LuaUnit failure inlay, the console, and any
  new comparison UI.
