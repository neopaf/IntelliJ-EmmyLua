-- luaunit runner used by the IDE's "LuaUnit" run configuration.
--
-- One process per test file (a "suite"), started by the Java side with:
--
--   lua emmylua_luaunit.lua --file=tests/ss7/test_ss7_common.lua [--pattern=...]
--   lua emmylua_luaunit.lua --discover=tests/ss7/test_ss7_common.lua
--
-- Everything the runner reports goes through small marker sections, each on its
-- own line; the IDE splits stdout by them:
--
--   @@EMLUA_LUAUNIT_CASE@@      <case name>    a case is about to run
--   @@EMLUA_LUAUNIT_FAIL@@      <json>         the current case failed
--   @@EMLUA_LUAUNIT_DONE@@      <json>         suite summary
--   @@EMLUA_LUAUNIT_FILE@@      <path>:<line>  fixture opened for reading
--   @@EMLUA_LUAUNIT_EXPECTED@@  <dump>         expected/actual values of a failure
--   @@EMLUA_LUAUNIT_ACTUAL@@    <dump>
--
-- Everything printed between the markers belongs to the current case and is
-- shown as that case's output.
--
-- "--discover" does not run anything: it reports the case names and their
-- declaration lines so the IDE tree can be built before running.

local MARK = '@@EMLUA_LUAUNIT_'

-- tests/ and the project root go on package.path: test files are addressed by
-- a project-relative name and the HDA modules live in ./.
package.path = './tests/?.lua;' .. package.path .. ';./?.lua'

-- Lua 5.1 / LuaJIT compatibility: table.pack/table.unpack are 5.2+.
if not table.pack then
    table.pack = function(...)
        return { n = select('#', ...), ... }
    end
end
if not table.unpack then
    table.unpack = unpack
end

local EXPECTED_MARK = '@@EMLUA_LUAUNIT_EXPECTED@@'
local ACTUAL_MARK = '@@EMLUA_LUAUNIT_ACTUAL@@'

local realStdout = io.stdout
local realOpen = io.open
local realRequire = require

local function emit(line)
    realStdout:write(line, '\n')
    realStdout:flush()
end

local function emitMark(kind, payload)
    emit(MARK .. kind .. '@@' .. tostring(payload or ''))
end

-- ------------------------------------------------------------------ options

local options = {}
do
    -- Started as a script (`lua emmylua_luaunit.lua --file=...`, all arguments
    -- in arg) or as a chunk (loadfile(...)(...) -- arguments come in '...').
    local args = {}
    local n = select('#', ...)
    if n > 0 then
        for i = 1, n do
            args[#args + 1] = (select(i, ...))
        end
    elseif arg then
        for i = 1, #arg do
            args[#args + 1] = arg[i]
        end
    end
    for i = 1, #args do
        local item = tostring(args[i])
        if item:sub(1, 2) == '--' then
            local eq = item:find('=', 3, true)
            if eq then
                options[item:sub(3, eq - 1)] = item:sub(eq + 1)
            else
                options[item:sub(3)] = 'true'
            end
        end
    end
end

local testFile = options.file or options.discover or os.getenv('EMLUA_FILE')
local discover = options.discover ~= nil or os.getenv('EMLUA_DISCOVER') ~= nil
-- A Lua pattern that selects one case of the file (LuaUnit's --pattern
-- semantics); every other case is still discovered but does not run.
local casePattern = options.pattern or os.getenv('EMLUA_PATTERN')

-- --------------------------------------------------------------- json

-- JSON is written by hand: string.format("%q") dies on bytes above 127 and the
-- dumps here are full of them, and requiring the luajson rock in every user
-- project is not an option. Values are escaped byte-safely instead.
local function quote(value)
    local s = tostring(value == nil and '' or value)
    local out = { '"' }
    for i = 1, #s do
        local b = s:byte(i)
        local ch = s:sub(i, i)
        if ch == '"' then
            out[#out + 1] = '\\"'
        elseif ch == '\\' then
            out[#out + 1] = '\\\\'
        elseif ch == '\n' then
            out[#out + 1] = '\\n'
        elseif ch == '\r' then
            out[#out + 1] = '\\r'
        elseif ch == '\t' then
            out[#out + 1] = '\\t'
        elseif b < 32 then
            out[#out + 1] = string.format('\\u%04x', b)
        else
            out[#out + 1] = ch
        end
    end
    out[#out + 1] = '"'
    return table.concat(out)
end

local function nilable(value)
    return value == nil and 'null' or quote(value)
end

-- ---------------------------------------------------------------- state

-- The runner needs luaunit and the diff capture before anything else: without
-- luaunit there is nothing to run, and the diff module is packaged next to this
-- file.
local here = (arg and arg[0] or ''):match('^(.*[/\\])') or './'
package.path = here .. '?.lua;' .. package.path

local okDiff, diff = pcall(require, 'emmylua_diff')
if not okDiff then
    diff = { install = function() end, extract = function(text) return text, nil, nil end }
end

local okLuaUnit, lu = pcall(require, 'luaunit')
if not okLuaUnit then
    emitMark('DONE', '{"name": ' .. quote(testFile or 'lua') .. ', "error": ' .. quote('luaunit is not installed: ' .. tostring(lu))
        .. ', "cases": [], "failed": []}')
    os.exit(2)
end

-- ------------------------------------------------------ luaunit internals

-- LuaUnit 3.4's adjust_err_msg_with_iter is quadratic on large messages: it
-- retries a greedy `.*` prefixed match at every position when the expected
-- prefix is absent, and our failures are big protobuf dumps without it
-- (measured: ~56 s for one 66 KB assertion). The replacement keeps the public
-- behaviour (same statuses, same message text) with a literal pre-check and a
-- backwards scan, so it is linear. It is used from execOneFunction only, so
-- replacing the field is enough; if the internals do not look like 3.4, the
-- original is left in place instead of silently degrading.
local function patchLuaUnit(module)
    if module.__emmylua_patched then
        return
    end
    local original = module.adjust_err_msg_with_iter
    local successPrefix, skipPrefix, failurePrefix =
        module.SUCCESS_PREFIX, module.SKIP_PREFIX, module.FAILURE_PREFIX
    local nodeStatus = module.NodeStatus
    if type(original) ~= 'function' or type(successPrefix) ~= 'string'
        or type(skipPrefix) ~= 'string' or type(failurePrefix) ~= 'string'
        or type(nodeStatus) ~= 'table' then
        return
    end
    module.adjust_err_msg_with_iter = function(errMsg, iterMsg)
        if type(errMsg) ~= 'string' or iterMsg ~= nil then
            return original(errMsg, iterMsg)
        end
        if errMsg:find(successPrefix, 1, true) or errMsg:find(skipPrefix, 1, true)
            or errMsg:find(failurePrefix, 1, true) then
            return original(errMsg, iterMsg)
        end
        return errMsg, nodeStatus.ERROR
    end
    module.__emmylua_patched = true
end

pcall(patchLuaUnit, lu)

-- ------------------------------------------------------- sources of cases

-- Text lines read from a file, with a small cache: the data-entry search below
-- reads the same files several times.
local lineCache = {}

local function lines(path)
    local cached = lineCache[path]
    if cached ~= nil then
        return cached
    end
    local file = realOpen(path, 'r')
    if file == nil then
        lineCache[path] = false
        return false
    end
    local result = {}
    for line in file:lines() do
        result[#result + 1] = line
    end
    file:close()
    lineCache[path] = result
    return result
end

local function escapePattern(text)
    return (text:gsub('%p', '%%%0'))
end

-- Files where a case's data entry may live: the test file itself and the
-- modules it requires (test data is often kept in a *_data.lua module).
local function dataFilesOf(path)
    local files = { path }
    local text = lines(path)
    if text then
        for _, line in ipairs(text) do
            local module = line:match("require%s*%(?%s*['\"]([^'\"]+)['\"]%s*%)?")
            if module then
                local candidate = module:gsub('%.', '/') .. '.lua'
                if realOpen(candidate, 'r') then
                    files[#files + 1] = candidate
                end
            end
        end
    end
    return files
end

-- Generated cases (built in a loop like TestSuite['test_' .. id]) all share the
-- loop line, which does not mention the case's own name. What the reader wants
-- is where the case's data came from, so for such a case the source points at
-- the `id = "..."` (or `desc = "..."`) entry of the data instead. Every
-- left-truncation of the name at '_' is a candidate, longest first.
local function dataLineOf(name, files)
    local candidates = {}
    local rest = name
    while true do
        candidates[#candidates + 1] = rest
        local shorter = rest:match('^.-_(.*)$')
        if shorter == nil or shorter == '' then
            break
        end
        rest = shorter
    end

    for _, candidate in ipairs(candidates) do
        local escaped = escapePattern(candidate)
        local dotted = escaped:gsub('%%_', '[._]')
        local patterns = {
            'id%s*=%s*["\']' .. escaped .. '["\']',
            'desc%s*=%s*["\']' .. escaped .. '["\']',
        }
        if dotted ~= escaped then
            patterns[#patterns + 1] = 'id%s*=%s*["\']' .. dotted .. '["\']'
            patterns[#patterns + 1] = 'desc%s*=%s*["\']' .. dotted .. '["\']'
        end
        for _, file in ipairs(files) do
            local text = lines(file)
            if text then
                for number, line in ipairs(text) do
                    for _, pattern in ipairs(patterns) do
                        if line:find(pattern) then
                            return file, number
                        end
                    end
                end
            end
        end
    end
    return nil
end

--- Where every case is declared, as "file:line" or nil.
local function sourceOf(caseName, byClass)
    local className = caseName:match('^([^%.]+)%.') or caseName
    local methodName = caseName:match('^[^%.]+%.(.+)$')
    local class = byClass[className]
    if class == nil then
        return nil
    end
    local func
    if methodName == nil or lu.LuaUnit.asFunction(class) then
        func = class
    else
        func = class[methodName]
    end
    if type(func) ~= 'function' then
        return nil
    end
    local info = debug.getinfo(func, 'S')
    if info == nil or type(info.source) ~= 'string' or info.source:sub(1, 1) ~= '@'
        or type(info.linedefined) ~= 'number' then
        return nil
    end
    local file, line = info.source:sub(2), info.linedefined
    -- A static declaration names the method in its own line; a generated one is
    -- a loop line that does not, and the data entry wins over it.
    local text = lines(file)
    local declared = text and text[line]
    local method = methodName or caseName
    if declared == nil or not declared:find(method, 1, true) then
        local dataFile, dataLine = dataLineOf(method, dataFilesOf(file))
        if dataFile and tonumber(dataLine) then
            return dataFile .. ':' .. tonumber(dataLine)
        end
    end
    return file .. ':' .. tonumber(line)
end

-- ------------------------------------------------------------ discovery

local currentCase = nil
local caseFilesSeen = {}

-- Fixture tracking: every file a case opens for reading, once per case, in
-- access order. Script-style files run while loading, and the load belongs to
-- the file's own node, so tracking starts before the load.
io.open = function(filename, mode)
    local handle = realOpen(filename, mode)
    local reading = mode == nil or tostring(mode):sub(1, 1) == 'r'
    if handle ~= nil and reading and currentCase ~= nil then
        local path = tostring(filename)
        if not caseFilesSeen[path] then
            caseFilesSeen[path] = true
            emitMark('FILE', path .. ':1')
        end
    end
    return handle
end

-- The test file's own trailing `os.exit(luaunit.LuaUnit.run(...))` must neither
-- exit nor run the suite here: cases are walked one by one below.
local loadExit = os.exit
os.exit = function(code)
    error({ marker = 'os.exit', code = code }, 0)
end
local luaUnitRun = lu.LuaUnit.run
lu.LuaUnit.run = function()
    return 0
end

-- Comparators defined by a required module (`tests/test_common_core` defines
-- assertEqualsEx) must be wrapped before a script-style file's load starts
-- comparing — for such a file the load IS the run.
local function requireHook(name, ...)
    local results = table.pack(realRequire(name, ...))
    diff.install(_G, package.loaded['luaunit'])
    return table.unpack(results, 1, results.n)
end
require = requireHook

currentCase = testFile
local okLoad, loadErr = xpcall(function()
    assert(loadfile(testFile))()
end, function(err)
    if type(err) == 'table' and err.marker == 'os.exit' then
        return err
    end
    return err
end)
require = realRequire
os.exit = loadExit
lu.LuaUnit.run = luaUnitRun

if type(loadErr) == 'table' and loadErr.marker == 'os.exit' then
    okLoad = true
elseif not okLoad then
    local message, expected, actual = diff.extract(tostring(loadErr))
    emitMark('FAIL', '{"name": ' .. quote(testFile) .. ', "status": "ERROR", "message": ' .. quote(message)
        .. ', "expected": ' .. nilable(expected) .. ', "actual": ' .. nilable(actual) .. '}')
    emitMark('DONE', '{"name": ' .. quote(testFile) .. ', "cases": [' .. quote(testFile) .. '], "failed": 1}')
    os.exit(1)
end

-- Cases the loaded file declares: "Class.test_method" or a plain testFunction.
local cases = {}
do
    local seenClasses = {}
    for name, value in pairs(_G) do
        if name:lower():sub(1, 4) == 'test' and name ~= 'TEST_ENV' then
            if type(value) == 'table' then
                for methodName, method in pairs(value) do
                    if type(methodName) == 'string' and type(method) == 'function'
                        and methodName:lower():sub(1, 4) == 'test' then
                        cases[#cases + 1] = name .. '.' .. methodName
                    end
                end
            elseif type(value) == 'function' then
                cases[#cases + 1] = name
            end
        end
    end
end

if #cases == 0 then
    -- Not a luaunit suite: a plain script, already executed by the load above.
    emitMark('DONE', '{"name": ' .. quote(testFile) .. ', "cases": [' .. quote(testFile) .. '], "failed": 0}')
    os.exit(0)
end

-- Declaration lines of the cases; also the order the tree and the run use.
local byClass = {}
for _, caseName in ipairs(cases) do
    local className = caseName:match('^([^%.]+)')
    if byClass[className] == nil then
        byClass[className] = _G[className]
    end
end

local sources = {}
for _, caseName in ipairs(cases) do
    sources[caseName] = sourceOf(caseName, byClass)
end
table.sort(cases, function(a, b)
    local sa, sb = sources[a], sources[b]
    if sa and sb and sa ~= sb then
        local fa, la = sa:match('^(.*):(%d+)$')
        local fb, lb = sb:match('^(.*):(%d+)$')
        if fa == fb and la and lb then
            return tonumber(la) < tonumber(lb)
        end
        return sa < sb
    end
    return a < b
end)

-- LuaUnit --pattern wording, applied to the discovered names: a case that does
-- not match the pattern does not run. A pattern that matches nothing is an
-- error, not a silently empty suite.
-- The filter is a plain pattern, not anchored: the IDE generates it from a
-- case's name, and an anchored pattern ($) would silently miss a name with a
-- trailing character. A pattern that matches several cases runs all of them,
-- which is the same as LuaUnit's own --pattern semantics.
local selected = cases
if casePattern ~= nil and casePattern ~= '' then
    local kept = {}
    for _, caseName in ipairs(cases) do
        -- A plain find first: a case name is a literal ("Frame 958-1"), and
        -- '-', '[' or '!' in it would otherwise be read as pattern magic.
        -- The pattern find is kept as a fallback so a real Lua pattern
        -- ("test_hda_send.*") still selects a whole run of cases.
        local plain = caseName:find(casePattern, 1, true) ~= nil
        -- A pattern with a bad capture ('%1', an unbalanced '[') must not abort
        -- the whole run: it matches nothing and the plain hit decides.
        local asPattern = false
        if not plain then
            local ok, found = pcall(string.find, caseName, casePattern, 1, false)
            asPattern = ok and found ~= nil
        end
        if plain or asPattern then
            kept[#kept + 1] = caseName
        end
    end
    if #kept == 0 then
        emitMark('FAIL', '{"name": ' .. quote(testFile) .. ', "status": "ERROR", "message": '
            .. quote('No case matches pattern "' .. casePattern .. '" (candidates: '
                .. table.concat(cases, ', ') .. ')') .. '}')
        emitMark('DONE', '{"name": ' .. quote(testFile) .. ', "cases": [' .. quote(testFile) .. '], "failed": 1}')
        os.exit(1)
    end
    selected = kept
end

if discover then
    local parts = {}
    for _, caseName in ipairs(cases) do
        parts[#parts + 1] = '{"name": ' .. quote(caseName) .. ', "source": '
            .. nilable(sources[caseName]) .. '}'
    end
    emitMark('DONE', '{"name": ' .. quote(testFile) .. ', "discover": true, "cases": [' .. table.concat(parts, ', ') .. ']}')
    os.exit(0)
end

-- ---------------------------------------------------------------- execution

-- The Lua stack at the moment a case fails. LuaUnit pcall's the case and unwinds
-- the stack before it reports the failure, so the case function itself is
-- wrapped: the handler snapshots the traceback while the stack is alive and
-- re-raises the same error value untouched.
local caseTraceback = nil

-- The traceback is written through realStdout: LuaUnit's own output wrapper may
-- already own io.write, and the trace must never depend on it.
local function traced(func)
    return function(...)
        local args = table.pack(...)
        local results = table.pack(xpcall(func, function(err)
            caseTraceback = debug.traceback(tostring(err), 2)
            return err
        end, table.unpack(args, 1, args.n)))
        if results[1] then
            return table.unpack(results, 2, results.n)
        end
        error(results[2], 0)
    end
end

local failed = 0

-- The trace must not contain the EXPECTED/ACTUAL marker sections of the failure
-- text: the IDE splits markers by line, and the message already carries them.
-- Framework frames (LuaUnit itself, the runner, the diff module) and raw C
-- frames are dropped too: the user wants the frames of the test they wrote.
local function isFrameworkFrame(line)
    return line:find('/luaunit%.lua:', 1) ~= nil
        or line:find('emmylua_luaunit%.lua:', 1) ~= nil
        or line:find('emmylua_diff%.lua:', 1) ~= nil
        or line:find('%[C%]:', 1) ~= nil
        or line:find('in main chunk', 1, true) ~= nil
end

local function stripSections(text)
    local at = text:find(EXPECTED_MARK, 1, true)
    if at ~= nil then
        text = text:sub(1, at - 1)
    end
    at = text:find(ACTUAL_MARK, 1, true)
    if at ~= nil then
        text = text:sub(1, at - 1)
    end
    local kept = {}
    for line in (text .. '\n'):gmatch('(.-)\n') do
        local stripped = line:gsub('^%s+', '')
        -- Keep the "stack traceback:" header and the user frames only.
        if line:find('^stack traceback:', 1) or
            (line:find('^\t', 1) and not isFrameworkFrame(stripped)) then
            kept[#kept + 1] = line
        end
    end
    return (table.concat(kept, '\n'):gsub('%s+$', ''))
end

local function startCase(name)
    currentCase = name
    caseFilesSeen = {}
    caseTraceback = nil
    emitMark('CASE', name)
end

local function recordFailure(status, err)
    local message, expected, actual = diff.extract(err)
    failed = failed + 1
    local trace = caseTraceback and stripSections(caseTraceback) or ''
    -- A traceback with no user frames (only "stack traceback:") adds nothing.
    if trace == 'stack traceback:' or trace == '' then
        trace = nil
    end
    emitMark('FAIL', '{"name": ' .. quote(currentCase or testFile)
        .. ', "status": ' .. quote(status)
        .. ', "message": ' .. quote(message)
        .. ', "expected": ' .. nilable(expected)
        .. ', "actual": ' .. nilable(actual)
        .. ', "trace": ' .. nilable(trace) .. '}')
end

local okRun, runErr = xpcall(function()
    local runner = lu.LuaUnit.new()
    runner.initFromArguments = function() end
    runner.verbosity = lu.VERBOSITY_QUIET
    runner.quitOnError = false
    runner.quitOnFailure = false
    runner.felixColorize = false
    runner.printVerbosity = false
    runner.instances = {}
    -- Silence LuaUnit's own printer: it would print a TAP summary for the whole
    -- suite while the markers are what the IDE consumes.
    if runner.setOutputType then
        pcall(runner.setOutputType, runner, 'nil')
    end

    -- LuaUnit 3.4 calls updateStatus(status) with one argument: the table
    -- protectedCall() built ({status, msg, trace}). The case a failure belongs to
    -- is the one the last CASE marker announced (a setUp/tearDown failure lands
    -- on the same case, as in LuaUnit), and LuaUnit still sees the status
    -- untouched, so its own counters stay authoritative.
    runner.updateStatus = function(self, status)
        if type(status) == 'table' and status.status ~= nil then
            if status.status == 'SKIP' then
                -- luaunit.skip("reason"): the case is ignored, not failed.
                emitMark('SKIP', tostring(status.msg or ''))
            elseif status.status ~= 'SUCCESS' then
                recordFailure(status.status == 'ERROR' and 'ERROR' or 'FAIL', status.msg)
            end
        end
        return lu.LuaUnit.updateStatus(self, status)
    end

    -- The classes are walked in the discovered order: expandClasses() would
    -- re-scan and re-sort the methods by itself.
    local instances, seen = {}, {}
    for _, caseName in ipairs(selected) do
        local className = caseName:match('^([^%.]+)')
        if not seen[className] then
            seen[className] = true
            local instance = byClass[className]
            if instance ~= nil then
                instances[#instances + 1] = { className, instance }
            end
        end
    end

    runner:startSuite(#selected, 0)
    runner:setupSuite(instances)

    for _, caseName in ipairs(selected) do
        local className = caseName:match('^([^%.]+)')
        local methodName = caseName:match('^[^%.]+%.(.+)$')
        local instance = byClass[className]
        startCase(caseName)
        if methodName == nil then
            runner:execOneFunction(nil, caseName, nil, traced(instance))
        else
            runner:execOneFunction(className, methodName, instance, traced(instance[methodName]))
        end
        if runner.result.aborted then
            break
        end
    end

    runner:teardownSuite(instances)
    if runner.lastClassName ~= nil then
        runner:endClass()
    end
    runner:endSuite()
end, function(err)
    return err
end)

currentCase = nil

if not okRun then
    local message, expected, actual = diff.extract(tostring(runErr))
    failed = failed + 1
    emitMark('FAIL', '{"name": ' .. quote(testFile) .. ', "status": "ERROR", "message": ' .. quote(message)
        .. ', "expected": ' .. nilable(expected) .. ', "actual": ' .. nilable(actual) .. '}')
end

-- The cases actually run, not the whole file: a one-case run must report one
-- case, and the IDE's tree of that run follows this list.
local names = {}
for _, caseName in ipairs(selected) do
    names[#names + 1] = quote(caseName)
end
emitMark('DONE', '{"name": ' .. quote(testFile) .. ', "cases": [' .. table.concat(names, ', ') .. '], "failed": ' .. failed .. '}')

os.exit(failed == 0 and 0 or 1)
