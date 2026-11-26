---
name: issues
description: Use whenever the user says «хорошо» — that word after a report of finished work means the whole issue cycle: create the tracking issue, open it on a branch, commit, push, merge and close it. Also use when creating, moving, closing or commenting on GitHub issues for the IntelliJ-EmmyLua plugin — which repository is ours, what to do with an issue filed in a foreign repository by mistake, how foreign repositories may be used at all.
---

# GitHub issues for this project

`gh` is logged in as `neopaf`. `origin` is `neopaf/IntelliJ-EmmyLua` (our fork,
the only place we own issues); `upstream` is `EmmyLua/IntelliJ-EmmyLua` (read
only); `EmmyLua/Intellij-EmmyLua2` is a different, unrelated project (read only).

## Where issues are created

- Issues are created **only in `origin`** — `neopaf/IntelliJ-EmmyLua`. Always
  pass `--repo neopaf/IntelliJ-EmmyLua` explicitly: `gh` defaults to
  `upstream` for this working copy, and to the current branch's remote.
- Foreign repositories — `EmmyLua/IntelliJ-EmmyLua` (upstream),
  `EmmyLua/Intellij-EmmyLua2`, `pkulchenko/MobDebug` and any other — are **read
  only**: never create issues, comments or PRs there without an explicit
  request from the user.
- Before creating, search the fork for an existing issue (`gh issue list
  --repo neopaf/IntelliJ-EmmyLua --state all --search "<words>"`) and reuse it
  instead of filing a duplicate.

## An issue was filed in a foreign repository by mistake

GitHub **cannot move** an issue between different owners (`gh issue transfer`
works only inside one owner/organisation), so "move" means copy + close:

1. Create the issue in `neopaf/IntelliJ-EmmyLua` with the original title and
   body, and append a line saying where it was moved from, e.g.
   `Moved from EmmyLua/IntelliJ-EmmyLua#632, where it was filed by mistake.`
   Keep already-known fix notes (commit hashes, branch names) in the body or in
   a comment, and close the fork's copy only if the work is really done.
2. In the foreign repository: reopen the issue if it was closed as `completed`,
   add an apology comment in **English** that admits the author is an AI agent
   and links the correct issue, then close it as `not planned`:

   ```bash
   gh issue reopen <n> --repo <foreign/repo>
   gh issue comment <n> --repo <foreign/repo> --body-file /tmp/apology.md
   gh issue close <n> --repo <foreign/repo> --reason "not planned"
   ```

   Apology text (adapt the link and the reason):

   > Sorry about this. This issue was filed here by mistake by an AI coding
   > agent — I am an autonomous AI assistant working on a fork of this plugin,
   > and I picked the upstream repository instead of the fork. The tracking
   > issue is https://github.com/neopaf/IntelliJ-EmmyLua/issues/<N>, where it
   > has been moved in full.
   >
   > Closing here as not planned; please disregard it in this repository.
3. Reopen/close **only** when there is something to fix (a wrong `completed`
   state or missing link); an issue that is already closed with a correct
   apology and link is left alone.

## «Хорошо» = весь цикл задачи сразу

Одобрение работы словом «хорошо» — это не только подтверждение результата:
агент сам проходит весь цикл, без отдельных «открой задачу» и «закрой задачу».

1. **Создать задачу** в `neopaf/IntelliJ-EmmyLua` (сначала поиск дубликата, см.
   выше): что сделано, что проверено в sandbox.
2. **Открыть задачу**: перевести рабочее пространство на ветку с номером
   задачи (создать от актуального `master`, если её ещё нет), перенести туда
   правки этой задачи. Правки другой задачи с собой не тащить — спросить, что
   с ними (commit / stash / оставить).
3. **Закрыть задачу**: спросить про commit и push, закоммитить, смержить
   ветку в `master` (рабочее пространство остаётся на `master`), отписать в
   задаче и закрыть её.

Версию при этом не поднимаем — это делает релиз.

## Local rules that stay in force

- «Открой задачу N» → switch the workspace to branch `N`; «Закрой задачу» →
  ask about commit and push, write the issue off, merge the branch into
  `master`. Those are in `AGENTS.md`; this skill only covers the issue tracker.
- Commit messages, issue comments in foreign repositories and any text sent
  outside the fork are in English.
