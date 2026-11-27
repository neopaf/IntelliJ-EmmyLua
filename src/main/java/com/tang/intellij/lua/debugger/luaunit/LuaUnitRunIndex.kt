/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.debugger.luaunit

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil

/**
 * What the last LuaUnit runs taught the IDE about the test files and the data
 * files they read.
 *
 * <p>Nothing here is discovered statically: a fixture is a file a case really
 * opened during its run, and a case's result is what the run reported. The map
 * therefore appears only after a run — but it is stored in the project's
 * workspace file, so the editor integrations (the code vision of a test, the
 * panel of a fixture, the colours of the project view) keep working after an
 * IDE restart instead of flashing for one session.
 *
 * <p>A partial run (one case, one file) updates the cases it ran and leaves the
 * others as the previous runs left them: a page that was not part of this run
 * did not suddenly lose its fixtures.
 */
@Service(Service.Level.PROJECT)
@State(name = "LuaUnitRunIndex", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class LuaUnitRunIndex(private val project: Project) :
    PersistentStateComponent<LuaUnitRunIndex.State>, com.intellij.openapi.Disposable {

    /** How a test file (or a fixture, through the tests using it) ended. */
    enum class Status { PASSED, FAILED }

    /** One case of some run: where it is declared, its result and its fixtures. */
    class CaseResult {
        var testFile: String = ""
        var caseName: String = ""
        var displayName: String = ""
        var line: Int = 1
        /** Where the case is declared: the suite file, or the data module of a
         *  generated case (`id = "..."` inside a table from an include). */
        var declarationFile: String = ""
        var declarationLine: Int = 1
        /** Built in a loop from a data row: its declaration is the row itself. */
        var generated: Boolean = false
        var failed: Boolean = false
        /**
         * The run that produced this record, as a counter of [LuaUnitRunIndex].
         * A partial run updates only the cases it executed; a record of an
         * older run next to a fresh one means the line was not covered whole,
         * and the gutter shows it (see [LuaUnitLineCases.partial]).
         */
        var runId: Long = 0
        /**
         * The failure carried an expected/actual pair: the tree paints such a
         * case with the "failed" cross, every other failure with the error
         * circle. The gutter and the inlay must repeat that icon exactly.
         */
        var comparison: Boolean = false
        /**
         * The record stands for a table's `setUp`, not for a case: its
         * fixtures are shown above the method, and no gutter mark of a test
         * result belongs on it.
         */
        var setup: Boolean = false
        /**
         * The record stands for the file itself, not for a case: a plain
         * script the runner executes while loading. It has no declaration of
         * its own — its fixtures and a load failure belong to the first line
         * of the file.
         */
        var fileLevel: Boolean = false
        var fixtures: MutableList<String> = ArrayList()

        /** Where the run failed: the assertion line of the trace and its message. */
        var errorFile: String = ""
        var errorLine: Int = 0
        /**
         * The text of the assertion line, trimmed. A line number goes stale on
         * the first edit above it; the hint of the failure follows the text, so
         * an `Enter` above an `error()` does not move the mark to another line.
         */
        var errorText: String = ""
        /**
         * The text of the declaration line as the run saw it, trimmed. It ties
         * the record to the very line the case was declared on: the lines above
         * may shift, and the record is moved with them; a line the user rewrote
         * is forgotten, and a record that meets a different text is not shown.
         */
        var declarationText: String = ""
        var message: String = ""
        var expected: String? = null
        var actual: String? = null

        fun key(): String = testFile + "\u0000" + caseName
    }

    class State {
        var cases: MutableList<CaseResult> = ArrayList()
        var lastRun: Long = 0
        /** The number of the last published run: [CaseResult.runId] of its cases. */
        var runCounter: Long = 0
    }

    private var state = State()

    /**
     * Statuses of the directories above the known files, built on demand: the
     * project view asks for a node's colour on every repaint, and walking the
     * cases per node would be the wrong side of the cost.
     */
    @Volatile
    private var directoryCache: Map<String, Status>? = null

    /**
     * The declared cases grouped by declaration line, per file. The gutter asks
     * for the cases of a line while the daemon paints every leaf of a file, and
     * a linear walk over every case of the project per leaf is the wrong side
     * of that cost. Dropped whenever the state changes.
     */
    @Volatile
    private var lineCache: Map<String, Map<Int, List<CaseResult>>> = emptyMap()

    override fun getState(): State = state

    override fun loadState(state: State) {
        // The record of a whole file used to be named with a NUL-prefixed
        // string the platform's serialiser drops: the stored copy came back as
        // the bare "file", the next run added a record under the real key, and
        // a plain script's failure was shown twice. Put every stored copy on
        // the current name and keep one record per key.
        for (case in state.cases) {
            if (case.fileLevel && case.caseName != FILE_RECORD) {
                case.caseName = FILE_RECORD
            }
        }
        // A suite used to get a file-level record too, carrying the files its
        // load read: they showed at the first line of the file as the test's
        // fixtures. Such a record is not a test — the cases of the file are —
        // and a plain script (which has no case record at all) keeps its own.
        val suiteFiles = state.cases.filter { !it.fileLevel }.mapTo(HashSet()) { it.testFile }
        state.cases.removeIf { it.fileLevel && it.testFile in suiteFiles }
        val merged = LinkedHashMap<String, CaseResult>()
        for (case in state.cases) {
            merged[case.key()] = case
        }
        this.state = State().also {
            it.cases = ArrayList(merged.values)
            it.lastRun = state.lastRun
            it.runCounter = state.runCounter
        }
        directoryCache = null
        lineCache = emptyMap()
    }

    /**
     * Merges the cases of a finished run into the map: a case that ran again
     * replaces its previous record (the newest result wins), the rest stay.
     */
    fun publish(results: Collection<CaseResult>) {
        if (results.isEmpty()) {
            return
        }
        // One number for the whole run: every case it executed carries it, and
        // the cases of the previous runs keep theirs — a line covered by some
        // of its cases only is told apart from a fully covered one.
        state.runCounter += 1
        val runId = state.runCounter
        val merged = LinkedHashMap<String, CaseResult>()
        for (case in state.cases) {
            merged[case.key()] = case
        }
        for (case in results) {
            case.runId = runId
            if (case.fileLevel) {
                // A file has one record: a copy stored under a retired name
                // (the old NUL-prefixed one) must not survive the publish and
                // show the same failure twice.
                merged.values.removeIf {
                    it.fileLevel && it.testFile == case.testFile && it.key() != case.key()
                }
            }
            merged[case.key()] = case
        }
        // A file-level record left next to the file's own cases (an older run,
        // a restore of its state) is not a test: the fixtures of a suite's load
        // must not show at the first line.
        val suiteFiles = merged.values.filter { !it.fileLevel }.mapTo(HashSet()) { it.testFile }
        merged.values.removeIf { it.fileLevel && it.testFile in suiteFiles }
        state.cases = ArrayList(merged.values)
        state.lastRun = System.currentTimeMillis()
        directoryCache = null
        lineCache = emptyMap()
        // The workspace is saved by the platform on its own schedule; saving
        // here meant serialising hundreds of case records (with their messages)
        // right in the middle of the run's completion.
    }

    /** True when the file declares cases of some run (nothing to show otherwise). */
    fun hasResultsFor(file: String): Boolean {
        val path = canonical(file)
        return state.cases.any { it.testFile == path || it.declarationFile == path || it.errorFile == path }
    }

    /**
     * Applies an edit of [file] to the records of the cases declared in it.
     *
     * <p>The cases declared on the lines the edit touched are forgotten: the
     * code the run saw is gone, and the new text cannot tell whether it means
     * the same (a removed `--` brings a commented-out case back). The
     * declarations below the edit move by [delta] lines — their own lines did
     * not change, and their results stay. The rest of the file keeps its
     * state: editing one case is no reason to forget the others.
     */
    fun applyEdit(file: String, firstLine: Int, lastLine: Int, delta: Int) {
        if (firstLine < 1 || lastLine < firstLine) {
            return
        }
        val path = canonical(file)
        var changed = false
        val iterator = state.cases.iterator()
        while (iterator.hasNext()) {
            val case = iterator.next()
            // The file record stands for the whole file: it has no declaration
            // to move or to drop.
            if (case.fileLevel) {
                continue
            }
            val declaredIn = case.declarationFile.ifEmpty { case.testFile }
            if (canonical(declaredIn) != path) {
                continue
            }
            when {
                case.declarationLine in firstLine..lastLine -> {
                    iterator.remove()
                    changed = true
                }
                delta != 0 && case.declarationLine > lastLine -> {
                    case.declarationLine += delta
                    changed = true
                }
            }
        }
        if (changed) {
            directoryCache = null
            lineCache = emptyMap()
        }
    }

    /**
     * Subscribes the index to the edits of the project's documents. Called once
     * per project from [LuaUnitProjectActivity].
     */
    fun watchEdits() {
        com.intellij.openapi.editor.EditorFactory.getInstance().getEventMulticaster()
            .addDocumentListener(editListener, project)
    }

    override fun dispose() {
        com.intellij.openapi.editor.EditorFactory.getInstance().getEventMulticaster()
            .removeDocumentListener(editListener)
    }

    /** The lines an edit touched, read before the document changed. */
    private class PendingEdit(val first: Int, val last: Int)

    private val pendingEdits =
        java.util.concurrent.ConcurrentHashMap<com.intellij.openapi.editor.Document, PendingEdit>()

    /**
     * One edit moves or forgets the records of the file it belongs to. The
     * lines of the edit are read before the change (the document still has the
     * old text) and after it (the new one); their difference is how far the
     * declarations below the edit shift.
     */
    private val editListener = object : com.intellij.openapi.editor.event.DocumentListener {
        override fun beforeDocumentChange(event: com.intellij.openapi.editor.event.DocumentEvent) {
            val document = event.document
            val length = document.textLength
            val first = document.getLineNumber(event.offset.coerceIn(0, length)) + 1
            val last = document.getLineNumber((event.offset + event.oldLength).coerceIn(0, length)) + 1
            pendingEdits[document] = PendingEdit(first, last)
            // The text caches read files the edited one may feed (a data module
            // a suite requires): their stamps do not move with this document.
            LuaUnitTextEdits.touch()
        }

        override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
            val document = event.document
            val before = pendingEdits.remove(document) ?: return
            val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                .getFile(document) ?: return
            val length = document.textLength
            val afterFirst = document.getLineNumber(event.offset.coerceIn(0, length)) + 1
            val afterLast = document.getLineNumber((event.offset + event.newLength).coerceIn(0, length)) + 1
            val delta = (afterLast - afterFirst) - (before.last - before.first)
            applyEdit(file.path, before.first, before.last, delta)
        }
    }

    /** The record of one case, or null when it did not run in any known run. */
    fun caseOf(testFile: String, caseName: String): CaseResult? {
        val file = canonical(testFile)
        return state.cases.firstOrNull { it.testFile == file && it.caseName == caseName }
    }

    /**
     * The record of a case as seen from [editorFile]: a suite declares its own
     * cases, a data module declares the generated ones, and both are the file
     * the user looks at when the row or the function is in front of them.
     */
    fun caseAt(editorFile: String, caseName: String): CaseResult? {
        val file = canonical(editorFile)
        return state.cases.firstOrNull {
            it.caseName == caseName && (it.testFile == file || it.declarationFile == file)
        }
    }

    /** The file and line a click on the case should open. */
    fun declarationOf(case: CaseResult): Pair<String, Int> {
        val file = case.declarationFile.ifEmpty { case.testFile }
        return file to case.declarationLine.coerceAtLeast(1)
    }

    /** The files the case opened during its last run, in access order. */
    fun fixturesFor(testFile: String, caseName: String): List<String> =
        caseOf(testFile, caseName)?.fixtures ?: emptyList()

    /** The cases that opened [fixture] during their last run. */
    fun testsUsing(fixture: String): List<CaseResult> {
        val file = canonical(fixture)
        return state.cases.filter { case -> case.fixtures.any { it == file } }
    }

    /** The failed cases whose assertion line is inside [file]. */
    fun failuresIn(file: String): List<CaseResult> {
        val path = canonical(file)
        return state.cases.filter { it.failed && it.errorLine > 0 && it.errorFile == path }
    }

    /** The cases declared in [file]: a suite's functions or a data module's rows. */
    fun casesDeclaredIn(file: String): List<CaseResult> {
        val path = canonical(file)
        return state.cases.filter {
            it.declarationFile == path || (it.declarationFile.isEmpty() && it.testFile == path)
        }
    }

    /**
     * The cases declared in [file], grouped by their declaration line. The
     * gutter asks about one line at a time; grouping once per file keeps the
     * cost from growing with the number of cases of the project.
     *
     * <p>A `setUp` and the record of a whole file are not tests of a line: they
     * are left out, so the caller never has to filter them again.
     */
    fun casesByLine(file: String): Map<Int, List<CaseResult>> {
        val path = canonical(file)
        lineCache[path]?.let { return it }
        val grouped = casesDeclaredIn(path)
            .filter { !it.setup && !it.fileLevel && it.declarationLine > 0 }
            .groupBy { it.declarationLine }
        lineCache = lineCache + (path to grouped)
        return grouped
    }

    /**
     * The failed cases declared in [file] but broken elsewhere: a generated
     * case lives in the data module (`id = "..."`), while its assertion is a
     * line of the generator. Such a failure is shown at the row, not at the
     * assertion every row shares.
     */
    fun failuresDeclaredIn(file: String): List<CaseResult> {
        val path = canonical(file)
        return state.cases.filter {
            it.failed && it.declarationFile == path && it.declarationLine > 0
        }
    }

    /**
     * The cases that decide the state of [file]: its own cases (declared in it
     * or run from it), or, for a fixture, the cases that read it. Empty when
     * no run has touched the file.
     */
    fun casesBehind(file: String): List<CaseResult> {
        val path = canonical(file)
        val own = state.cases.filter { it.testFile == path || it.declarationFile == path }
        return if (own.isNotEmpty()) own else testsUsing(path)
    }

    /**
     * The state of a file in the project view: a test file follows its own
     * cases, a fixture follows the cases that read it. Null when the file is
     * not known to any run — such a file keeps the usual colour.
     */
    fun aggregateOf(file: String): Status? {
        val own = casesBehind(file)
        if (own.isEmpty()) {
            return null
        }
        return if (own.any { it.failed }) Status.FAILED else Status.PASSED
    }

    /**
     * The state of a directory: red when something inside it failed, green when
     * the known files inside it passed. A directory with no known files (and
     * the project root itself, which would always be painted) has no colour.
     */
    fun aggregateUnder(directory: String): Status? {
        val dir = canonical(directory).trimEnd('/')
        val base = project.basePath?.let { canonical(it).trimEnd('/') }
        if (base != null && dir == base) {
            return null
        }
        return buildDirectoryCache()[dir]
    }

    /** One pass over the cases; every ancestor of a known file gets the status. */
    private fun buildDirectoryCache(): Map<String, Status> {
        directoryCache?.let { return it }
        val failed = HashSet<String>()
        val passed = HashSet<String>()

        fun addAncestors(path: String, isFailed: Boolean) {
            var dir = path.substringBeforeLast('/', "")
            while (dir.isNotEmpty() && dir != "/") {
                if (isFailed) failed.add(dir) else passed.add(dir)
                dir = dir.substringBeforeLast('/', "")
            }
        }

        for (case in state.cases) {
            addAncestors(case.testFile, case.failed)
            if (case.declarationFile.isNotEmpty()) {
                addAncestors(case.declarationFile, case.failed)
            }
            for (fixture in case.fixtures) {
                addAncestors(fixture, case.failed)
            }
        }
        val result = HashMap<String, Status>()
        for (dir in failed) {
            result[dir] = Status.FAILED
        }
        for (dir in passed) {
            if (!failed.contains(dir)) {
                result[dir] = Status.PASSED
            }
        }
        directoryCache = result
        return result
    }

    companion object {
        /**
         * The case name of the record that stands for the whole file (a plain
         * script, a file that failed to load). No LuaUnit case can carry it:
         * a real name never contains a NUL.
         */
        /**
         * The name of the record that stands for a whole file. It must not
         * carry control characters: the platform's serialiser drops them, and
         * the stored copy then comes back under a different key — which made
         * the next run add a second record for the same file.
         */
        const val FILE_RECORD: String = "file"

        fun getInstance(project: Project): LuaUnitRunIndex =
            project.getService(LuaUnitRunIndex::class.java)

        /**
         * The one form paths are stored and compared in: the runner reports
         * fixtures relative to the working directory, the IDE hands absolute
         * ones, and both have to meet.
         */
        fun canonical(path: String): String {
            var text = path.trim()
            // A record restored from the workspace file keeps the project
            // macro instead of the absolute path: expand it before comparing,
            // otherwise a case of the previous session is never matched.
            if (text.contains("\$PROJECT_DIR\$")) {
                val base = com.intellij.openapi.application.PathMacros.getInstance()
                    .getValue("PROJECT_DIR")
                if (!base.isNullOrBlank()) {
                    text = text.replace("\$PROJECT_DIR\$", base)
                }
            }
            return FileUtil.toCanonicalPath(text)
        }
    }
}
