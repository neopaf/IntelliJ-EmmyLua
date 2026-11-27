# LuaUnit Integration

EmmyLua ships a JUnit-style test runner for [LuaUnit]-driven test suites.
A run configuration discovers the cases, runs them with your Lua interpreter,
and shows the results in the standard IntelliJ test tree: green/red nodes,
per-case output, failure diffs, ignored cases, rerun-failed, and debugging.

## The layout it expects

```
<project root>/
├── run_tests.lua           # or tests/run_tests.lua — the manifest
├── tests/
│   ├── run_tests.lua       # the manifest (either place works)
│   ├── ss7/
│   │   ├── test_decode_ss7.lua   # a suite file (listed in the manifest)
│   │   ├── test_ss7_data.lua     # a data module the suites generate cases from
│   │   └── ...
│   └── ...
└── src/                    # the code under test (require()'d by the tests)
```

- The **manifest** (`run_tests.lua`) is a Lua array of file paths, relative to
  the project root. A whole-line `--` comment above a run of entries is a
  **section title** and becomes a group node in the test tree. A commented-out
  entry is hidden from the run.
- The **project root** is where `require('tests/...')` and the manifest paths
  resolve from — normally the directory opened in the IDE. It is also the
  default working directory of every run configuration.

## Declared cases

A case is either a plain function or a table method, both recognized:

```lua
function test_checksum() ... end          -- TestSuite.test_checksum
function TestSuite:test_marker() ... end  -- TestSuite.test_marker
```

## Generated cases (data-driven)

The common shape of this project: a data table of rows, and a loop that builds
one case per row. Both a separate data module and a local table work:

```lua
-- data in another file (tests/ss7/test_ss7_data.lua)
local tests = require('tests/ss7/test_ss7_data')

-- data in the same file
local testData = {
    { id = 'CCR_5G', hexData = '0100...' },
    { id = 'SY_STR', desc = 'session termination' },
}

for _, v in pairs(testData) do
    TestSuite['test_' .. v.id] = function() ... end
end
```

Rules the discovery follows:

- the case name is `<table>.<prefix><row name>`, where the row name is the
  row's `desc` when it has one, otherwise its `id`;
- each row of the table is one case, sorted by the row's line in the file;
- a commented-out row (`-- id = 'x'`) is still discovered, but shown as
  **ignored** in the tree (it never runs);
- the tree node of a generated case points at the row's `id = "..."` line, so
  a double-click opens the data, not the loop.

## Files without cases

A file listed in the manifest may declare no test function at all: it is a
plain script, and loading it *is* the run. Such a file keeps its node in the
tree — the same file node a suite gets:

- a double-click on the node opens the file at its **first line** (a node
  whose file has cases toggles instead, as before);
- the node's context menu runs the file — `Run 'file.lua'`, `Debug 'file.lua'`
  and `Modify Run Configuration...` come from the node's location URL and
  start the whole script as one process (the FILE scope of the manifest's
  entry line);
- a failure of the load (a missing module, a syntax error, an assertion in
  the script) is shown on the file's node and as a failure inlay on the right
  of the first line — the same `-- ❌ ...` hint a case gets, with the diff when
  the failure carried expected/actual values;
- the files the script opened while loading are its fixtures: they are shown
  in a block above the first line of the file;
- the file is tinted in the project view by the result of its load — green
  when the script finished, red when it failed.

## Ignoring a case

Comment the case out — it stays in the tree as **ignored** and never runs.
A data row can be commented line by line:

```lua
--{
-- id = 'CCR_HDAS-52', hexData = '0100...',
--}
```

A function inside a block comment is ignored the same way:

```lua
--[[ function TestSuite:test_slow() ... end ]]
```

A commented-out field inside a live row (`-- desc = '...'`) only disables
that field: the generator still reads the row.

A case can also opt out at run time:

```lua
function TestSuite:test_shard()
    luaunit.skip("Feature not implemented yet")
    ...
end
```

The case is reported as ignored with the given reason; the reason is also
shown next to the node in the tree.

## Case descriptions

The description of a case — active or ignored alike — is the free `-- ...`
line above its declaration, above the `{` of its data row, or above the `--[[`
opener of a block (shared by every case the block hides). A row's trailing
comment wins over the lines above it:

```lua
-- HDAS-93: disabled
{ id = 'CCR_HDAS-52', hexData = '0100...' },     -- from the lines above

{ id = 'SY_STR', desc = 'session termination' }, -- the trailing comment wins
```

The tree shows the description in parentheses next to the case name. The
structural lines of a commented-out table (`--{`, `--}`, `--[[`) are code,
not prose — they never become part of a description.

## Running

- **Gutter icons** — in `run_tests.lua` the first line runs the whole suite,
  a section title line (`--SS7`) runs that group, and an entry line runs that
  single test file. In the suite files, every case declaration
  (`function TestSuite:test_x()`) and every data row (`id = "..."`, in both
  suite files and data modules) gets the same marker with **Run** and **Debug**
  actions. A plain click on a marker that names one test runs it at once; the
  Run/Debug question is asked by a right click, which always opens the menu.
  A data row several suites build their cases from declares one case per
  generator. Its marker runs all of them in one tab on a plain click; the
  right-click menu lists **Run all N cases** and **Debug all N cases** first,
  then a labelled Run/Debug pair (plus Modify Run Configuration) per generator
  file.
- **A file node with cases in the tests tree** — its context menu carries Run,
  Debug and Modify Run Configuration for the whole file: the node itself has no
  location URL (a double-click toggles it), so the platform builds no entries
  there and the plugin supplies them. A file node without cases keeps its URL
  and the platform's own entries (see *Files without cases*).
- **A run configuration** — type *LuaUnit*. Its settings are the interpreter,
  the working directory (the project root by default), read-only test manifest,
  interpreter arguments and environment variables. Create it from the gutter,
  from the editor context menu, or manually.
- The scope of a run is one case, one file, one manifest section, or the whole
  manifest; the tree of a one-case run shows that single case.
- **Rerun failed tests** works, as well as the usual debug: breakpoints are set
  by absolute paths, the debugger connects through the bundled mobdebug and
  runs the suite processes one by one.

## The console

- The tree groups are the manifest sections; the root shows the totals
  (`Lua tests — N passed, M failed, K ignored`, each block only when present).
- A passed case prints a green `PASSED` line.
- A failed case shows the assertion message and, for `assertEquals`-style
  failures, an **expected/actual diff** viewer.
- The files a case opened during its run (fixtures, `.json` resources) are
  recorded by the run and shown by the editor — above the case and in the
  fixture's own panel. The console output no longer prints them as
  `at fixture(path:line)` frames (the block is commented out in
  `LuaUnitTestConsole.applyCaseFinished` and can be restored).
- Framework frames (`luaunit.lua`, the runner itself, `[C]`) are stripped from
  failure traces; the remaining `file.lua:line` references are clickable.
- Plain test output (`print`, `dbg`) goes to the case node it belongs to.
- **Copy Test Name** in the tree's context menu copies the name LuaUnit itself
  accepts: `test_ccri_cps_fail` for a declared function, the row's own
  `ccri_int` for a generated case. The description the tree shows in
  parentheses is never copied.

## In the editor

- The fixtures of a case are shown in a block above its declaration
  (`function TestSuite:test_x()`) by file name; every file is a link of its own
  (the directory is kept for colliding base names), and a folder whose files
  are all among the fixtures is one link (`cca/*`) that opens a popup with its
  files. The entries come from the last run — a case that has never run shows
  none. The block is an editor inlay, not code vision: a code vision provider
  keeps one entry per line, so a list of links cannot be built that way.
- A data file a run saw a case open gets a notification panel:
  `Tests: ✔ TestSuite.test_a, ✖ TestSuite.test_b`. "Show tests..." opens the
  same list as a popup; a click there goes to the declaration of the case.
- The gutter shows the result of the last run next to the declaration of a
  case — the `function TestSuite:test_x()` line, or the `id = "..."` row of a
  generated case. The icons are the ones of the test tree: a green tick for a
  pass, a crossed circle for a failed assertion (an expected/actual pair), a
  red error circle for anything else. One line, one icon: the marker sits on
  the first non-blank character of the line, and the row is red as soon as any
  of its cases failed. A commented-out case (a `--[[ ... ]]` block or a
  `-- id = "..."` row) keeps its case in the tree but never runs, so from the
  start it carries the crossed circle — before any run, and inside a block the
  mark sits on the declaration line itself, not on the `--[[` opener.
- A line whose data row feeds several generators may be covered by only some
  of its cases: a run of one generator updates that case and leaves the others
  as an older run left them. The status icon then keeps its colour — the
  result of the cases the newest run of the line really executed — and gets a
  small white dot in its top-right corner. The dot disappears only when one
  run executed every case of the line; a line no run has touched keeps the
  bare run arrow, without the dot.
- The same fact is spelled out after the row, only for a row of two or more
  cases and only when they differ: `2 cases: decode_ss7 passed · encode_ss7
  not run`. The names are the generator files without `test_`; the statuses
  belong to the newest run of the row (a record of an older run reads
  `not run`). The hint sits before a trailing `--` comment, and its tooltip
  lists the full case names and their files. A row whose cases all passed, and
  a row no run has touched, shows no hint.
- A failed assertion is marked at the line a double-click on the case opens:
  the assertion's own line, even when it lives in another file (a helper such
  as `tests/test_common.lua`). The inlay after the code reads
  `-- ❌ Expected 200, got 403`, in the bright red of the palette. A failure
  with expected/actual values is a link — the scheme's hyperlink colour,
  underlined, a click opens the diff (expected on the left, actual on the
  right); an `error()` has no diff, so its text is plain red with no underline.
  A long message is shortened. A line that carries several failures shows one
  of them and `(+n errors)` for the rest. The hint goes before a trailing `--`
  comment, so the comment stays part of the line.
- Test files and the fixtures they read are tinted in the project view: green
  when every case that touched them passed, red when one of them failed. A
  directory is coloured by everything below it.
- Results and fixtures are runtime-only: they come from the last run, live in
  the project's workspace file and are refreshed when the next run finishes.
  The ignored mark is the exception — it comes from the source text and is
  there before any run.

## Under the hood

- `LuaTestManifest` — reads `run_tests.lua` (sections, commented-out entries).
- `LuaUnitTextDiscovery` — scans the suite files on the IDE side (no Lua
  process): declared cases, generator loops, data tables (local and required),
  `desc`/`id` row names, ignored (commented) rows, source lines.
- `LuaUnitCaseAtCaret` / `LuaUnitDataSources` — resolve the element under the
  caret (or a data row) to a case name for the gutter and the producer.
- `LuaUnitRunnerScript` — unpacks the bundled `luaunit/` scripts (they cannot
  be loaded from inside a jar) and builds the interpreter command line.
- `emmylua_luaunit.lua` — the bundled runner: loads the test file with plain
  LuaUnit semantics, emits line-based markers (`CASE`, `SKIP`, `FAIL`, `DONE`,
  `FILE`) that the IDE parses into the tree.
- `emmylua_diff.lua` — attaches expected/actual values to assertion messages
  so the IDE can offer the diff viewer.
- `LuaUnitProtocol` / `LuaUnitOutputConverter` / `LuaUnitTestConsole` — the
  marker protocol, per-case state machine and the SM test tree.
- `LuaUnitDebugSupport` — the mobdebug session of a Debug run.

[LuaUnit]: https://github.com/bluebird75/luaunit
