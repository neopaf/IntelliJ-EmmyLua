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
  actions. A data row several suites build their cases from is more than one
  test: the icon's menu asks which one to run — a labelled Run/Debug pair
  (plus Modify Run Configuration) per generator file.
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
  printed under the result, as `at fixture(path:line)` frames.
- Framework frames (`luaunit.lua`, the runner itself, `[C]`) are stripped from
  failure traces; the remaining `file.lua:line` references are clickable.
- Plain test output (`print`, `dbg`) goes to the case node it belongs to.

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
