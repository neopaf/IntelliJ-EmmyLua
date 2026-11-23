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
- Releasing (version bump, both artifacts, tag, GitHub release) is described in
  the `release` skill.
- After building the plugin, **always launch the sandbox** so the change can be
  tried by hand: `./gradlew runIde -DIDEA_VER=2025.3 --offline` (a long-running
  GUI, start it in the background and leave it running). The user closes the
  sandbox themselves when they are done testing: never restart it afterwards,
  and never treat a closed sandbox as a failure. The sandbox IDE log is under
  `build/2025.3/idea-sandbox/IU-2025.3/log/idea.log`.
