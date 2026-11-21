-- Visual-diff capture for luaunit failures.
--
-- LuaUnit only formats a failed assertEquals into human-readable text; the two
-- compared values themselves are lost. The wrappers installed by this module
-- re-raise the failure with the values attached, and the runner sends them to
-- the IDE as separate fields, which are rendered as the click-to-diff pair of
-- the failed test.
--
-- LuaUnit 3.4 calls assertEquals(actual, expected) — actual first! The wrappers
-- below keep that order and swap the two when reporting, so "expected" in the
-- diff is the value the test meant to see.
--
-- The values are pretty-printed with sorted keys: a JSON fixture and a decoded
-- event then differ exactly in the fields that really differ.

local M = {}

local EXPECTED_MARK = '@@EMLUA_LUAUNIT_EXPECTED@@'
local ACTUAL_MARK = '@@EMLUA_LUAUNIT_ACTUAL@@'

-- ------------------------------------------------------------- pretty print

local function quoteString(s)
    local out = {}
    for i = 1, #s do
        local b = s:byte(i)
        local ch = s:sub(i, i)
        if ch == '"' or ch == '\\' then
            out[#out + 1] = '\\' .. ch
        elseif ch == '\n' then
            out[#out + 1] = '\\n'
        elseif ch == '\r' then
            out[#out + 1] = '\\r'
        elseif ch == '\t' then
            out[#out + 1] = '\\t'
        elseif b < 32 or b == 127 then
            out[#out + 1] = string.format('\\u%04x', b)
        else
            out[#out + 1] = ch
        end
    end
    return '"' .. table.concat(out) .. '"'
end

local function formatValue(value, depth)
    depth = depth or 0
    if value == nil then
        return 'null'
    end
    local kind = type(value)
    if kind == 'string' then
        return quoteString(value)
    end
    if kind == 'number' or kind == 'boolean' then
        return tostring(value)
    end
    if kind ~= 'table' then
        return tostring(value)
    end

    local pad = string.rep('  ', depth)
    local padIn = string.rep('  ', depth + 1)
    local parts = {}
    local seen = {}

    for i = 1, #value do
        seen[i] = true
        parts[#parts + 1] = padIn .. formatValue(value[i], depth + 1)
    end

    local keys = {}
    for key in pairs(value) do
        if not seen[key] then
            keys[#keys + 1] = key
        end
    end
    table.sort(keys, function(a, b)
        return tostring(a) < tostring(b)
    end)
    for _, key in ipairs(keys) do
        local name = type(key) == 'string' and key or '[' .. tostring(key) .. ']'
        parts[#parts + 1] = padIn .. name .. ': ' .. formatValue(value[key], depth + 1)
    end

    if #parts == 0 then
        return '{}'
    end
    return '{\n' .. table.concat(parts, ',\n') .. '\n' .. pad .. '}'
end

M.format = formatValue

-- ------------------------------------------------------------------ markers

--- Appends the compared values to a failure text as marker sections.
function M.attach(err, expected, actual)
    local expectedText = formatValue(expected)
    local actualText = formatValue(actual)
    return tostring(err)
        .. '\n' .. EXPECTED_MARK .. '\n' .. expectedText:gsub('%s+$', '')
        .. '\n' .. ACTUAL_MARK .. '\n' .. actualText:gsub('%s+$', '')
end

--- Strips LuaUnit's own report wrapper from an assertion message.
local function cleanMessage(text)
    local result = text:gsub('%s+$', '')
    -- Drop the "LuaUnit test FAILURE: " / "LuaUnit test ERROR: " prefix.
    result = result:gsub('^LuaUnit test %u+:%s*', '')
    -- Drop LuaUnit's own "expected: X, actual: Y" line (the values are carried
    -- by the markers). Lua patterns have no escape for a newline, so the line
    -- prefix is matched with %c via a real newline in the pattern.
    result = result:gsub('\nexpected:%s*[^\n]-,[ ]*actual:%s*[^\n]*$', '')
    -- A message that is only "expected: X, actual: Y" on one line.
    result = result:gsub('^expected:%s*[^\n]-,[ ]*actual:%s*[^\n]*$', '')
    return result:gsub('%s+$', '')
end

--- Inverse of attach(): message (markers stripped), expected, actual.
--- LuaUnit prefixes its own report ("LuaUnit test FAILURE: ..." and its own
--- "expected: X, actual: Y" line); both are stripped so the message is the
--- bare assertion text and the values come only from the markers.
function M.extract(text)
    text = tostring(text or '')
    local at = text:find(EXPECTED_MARK, 1, true)
    local message
    local expected, actual
    if at == nil then
        message = text
    else
        local from = text:find(ACTUAL_MARK, at, true)
        if from == nil then
            message = text
        else
            message = text:sub(1, at - 1)
            expected = text:sub(at + #EXPECTED_MARK, from - 1):gsub('^%s+', ''):gsub('%s+$', '')
            actual = text:sub(from + #ACTUAL_MARK):gsub('^%s+', ''):gsub('%s+$', '')
        end
    end
    message = cleanMessage(message)
    return message, expected, actual
end

-- ----------------------------------------------------------------- wrappers

-- Lua 5.4 does not allow fields on functions, so the wrappers are tracked in
-- two tables: `wrapped` maps an original to its wrapper, `isWrapper` holds every
-- wrapper ever made (a repeated install over a wrapped global is a no-op).
local wrapped = {}
local isWrapper = {}

local function wrap(original, swap)
    local wrapper = function(...)
        local results = table.pack(pcall(original, ...))
        if results[1] then
            return table.unpack(results, 2, results.n)
        end
        -- An error that already carries the pair (a wrapped assert called
        -- from inside another one) goes up untouched: attaching the markers
        -- again would duplicate them and mix the values into one blob.
        if type(results[2]) == 'string' and results[2]:find(EXPECTED_MARK, 1, true) then
            error(results[2], 0)
        end
        local args = table.pack(...)
        local expected, actual
        if swap then
            -- luaunit: assertEquals(actual, expected, ...)
            actual, expected = args[1], args[2]
        else
            -- test_common_core: assertEqualsEx(id, actual, expected)
            actual, expected = args[2], args[3]
        end
        error(M.attach(results[2], expected, actual), 0)
    end
    wrapped[original] = wrapper
    isWrapper[wrapper] = true
    return wrapper
end

--- Wraps the comparison helpers the loaded test file will call. Call it after
--- the file (or tests/test_common) is loaded and before any case runs: the
--- wrappers must see the globals the file itself resolves to. Idempotent.
function M.install(env, luaunit)
    -- The plain assertEquals is kept aside: the assertEqualsEx delegate
    -- below compares through it, so its error carries no markers of its own.
    local plainEquals = env.assertEquals
    if type(plainEquals) == 'function' and not isWrapper[plainEquals] then
        env.assertEquals = wrap(plainEquals, true)
    end
    if type(env.assertEqualsEx) == 'function' and not isWrapper[env.assertEqualsEx] then
        -- The project's assertEqualsEx (tests/test_common_core) builds its own
        -- report — "Test failed: <id>\nexpected:\n<JSON>\nbut was:\n<JSON>" —
        -- and re-raises a hand-made error, which hides the compared pair from
        -- the IDE (no diff viewer). Under the IDE the same report is built
        -- here, with the pair attached through the markers: the comparison
        -- itself stays luaunit's, a plain `lua tests/run_tests.lua` run does
        -- not load this module and keeps the original untouched.
        local delegate = function(id, actual, expected)
            local ok = pcall(plainEquals, actual, expected)
            if ok then
                return
            end
            -- The report text keeps the console format (a JSON dump for
            -- tables, tostring for scalars); attach() formats the pair for
            -- the markers on its own, the way it does for every other assert.
            local message = 'Test failed: ' .. tostring(id)
                .. '\nexpected:\n' .. (type(expected) == 'table' and formatValue(expected) or tostring(expected))
                .. '\nbut was:\n' .. (type(actual) == 'table' and formatValue(actual) or tostring(actual))
            error(M.attach(message, expected, actual), 0)
        end
        wrapped[env.assertEqualsEx] = delegate
        isWrapper[delegate] = true
        env.assertEqualsEx = delegate
    end
    if luaunit ~= nil and type(luaunit.assertEquals) == 'function'
        and not isWrapper[luaunit.assertEquals] then
        luaunit.assertEquals = wrap(luaunit.assertEquals, true)
    end
end

return M
