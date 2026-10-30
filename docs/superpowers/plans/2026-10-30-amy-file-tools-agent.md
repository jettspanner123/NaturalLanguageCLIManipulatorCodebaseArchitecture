# Amy File-Tools Agentic Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `CoreEntryPoint`'s hardcoded three-flow demo with a real agentic loop: a natural-language command drives Gemini via manual function-calling over six Structured Tools (`ListFiles`, `CreateFolder`, `MoveFiles`, `RenameFiles`, `ConvertImage`, `CompressFiles`), with every mutating action held for one combined user Confirmation before anything real executes.

**Architecture:** Core disables the SDK's Automatic Function Calling and drives the Gemini conversation itself, turn by turn: read-only tool calls (`ListFiles`) execute immediately and feed results back; mutating tool calls are accumulated into a pending plan instead of executed. Once Gemini's turn produces a final answer with no more calls, Core sends the whole pending plan as one `ConfirmationRequest` over the existing IPC channel, then executes the held calls only on approval.

**Tech Stack:** Scala 3.3.4 (Core), `com.google.genai:google-genai` 1.76.x (Gemini SDK, Java interop), `gemini-3.5-flash-lite`, Java's built-in `ImageIO` (image conversion) and `java.util.zip` (compression) — no new runtime dependencies beyond the Gemini SDK itself.

**Spec:** `docs/superpowers/specs/2026-10-30-amy-file-tools-agent-design.md` — the plan argues from the spec; executors should read both.

## Global Constraints

- Model: `gemini-3.5-flash-lite`. SDK: `com.google.genai:google-genai`, version pinned (not a floating range) in `build.sbt` — AFC is **disabled** (`AutomaticFunctionCallingConfig.builder().disable(true)`); every tool is declared schema-only.
- Credential: read from `NaturalLanguageCLIManipulatorCoreMSC/.env` (already present, gitignored, verified working). This is a deliberate, temporary deviation from `ARCHITECTURE.md`'s documented `~/.Amy/Configurations/.env` — Task 11 updates the doc to record this.
- Collision policy (every mutating tool): fail that one item, skip it, continue the rest, report it — never silently overwrite.
- Batch policy (every tool taking multiple items): process each item independently; one failure never blocks or rolls back the others.
- `ConvertImage` is image-formats-only (`ImageIO`'s built-in readers/writers: PNG/JPG/BMP/GIF/WBMP). `CompressFiles` is `.zip`-only (`java.util.zip`).
- No date-math vocabulary, fixed time-of-day boundaries, or pre-filtering of file lists in code anywhere — `ListFiles` always returns the complete, unfiltered file list with parsed timestamps; which files match a natural-language criterion is Gemini's judgment alone, grounded by the real current date/time Core includes as plain text context.
- Naming/placement already fixed by the existing docs, not re-decided here: tool files live in `Source/Apparatuses/Structured/<Name>Tool.scala`; the reasoning loop lives in `Source/AgenticReasoning/`.

## Checkpoints Flagged for Human Review

These are judgment calls made during planning, not pre-settled by the spec or existing docs — confirm or correct before/while executing:

1. **Test framework**: no Scala test library exists in `build.sbt` yet. Plan adds `org.scalameta %% munit % 1.0.0` (MUnit — zero-ceremony, Scala Center-maintained, idiomatic for sbt). Swap for ScalaTest if preferred; only Task 1 changes.
2. **Request delivery**: `CoreEntryPoint` currently takes no arguments. This plan changes it to `@main def CoreEntryPoint(request: String): Unit`, reading the command as a CLI argument — matching the `amy "<command>"` usage pattern already shown in `README.md`/`ARCHITECTURE.md`, not stdin.
3. **Confirmation message shape**: `ConfirmationRequest` only has `command`/`reason` string fields (no structured list). This plan formats the whole pending plan (folder name, each file to be touched) as multi-line text in `command`, rather than extending `AmyIpc.proto`. Extending the proto is a bigger, separate change if a structured confirmation is wanted later.
4. **Package naming**: no written convention exists beyond the one precedent (`IPC/` folder → `package amy.ipc`). This plan follows that precedent: `AgenticReasoning/` → `package amy.agenticreasoning`, `Apparatuses/` → `package amy.apparatuses`, `Apparatuses/Structured/` → `package amy.apparatuses.structured`.
5. **`CODING-RULES.md` tension, not silently resolved**: that file states Risk Judgment/Safety Net and Permission Grant checks have "no code path that skips this," for every Tool Call. This plan's scope (per the approved design's explicit Non-Goals) ships none of that yet — every mutating Tool Call in this plan is gated only by the one Confirmation, not Risk Judgment or Permissions. Flagging this plainly rather than building around it quietly; it's a known, approved gap, not an oversight.

## Review Focus

- A command whose criterion matches **zero files** — Gemini should say so in its final answer and request no `CreateFolder`/`MoveFiles` calls, not create an empty folder or error.
- **`CreateFolder` collision** (target already exists) — must fail that one call and report it, not throw past the reasoning loop or silently proceed as if it succeeded.
- **User declines the Confirmation** — nothing in the pending plan executes; Core reports the decline cleanly (same pattern as the existing Confirmation flow).
- **Partial batch failure during real execution** (not planning) — e.g. `MoveFiles` given 5 paths where one no longer exists or is locked by another process by execution time — the other 4 must still complete, with the failure reported per-item.
- **`ConvertImage`/`CompressFiles` given a source path that doesn't exist or isn't a supported format** — must return a clear per-item failure through the same reporting path as any other tool failure, not an unhandled exception bubbling out of the reasoning loop.

---

### Task 1: Project setup — test framework + Gemini SDK dependency

**Files:**
- Modify: `NaturalLanguageCLIManipulatorCoreMSC/build.sbt`

**Interfaces:**
- Produces: `com.google.genai` classes available on the classpath; `munit.FunctionSuite`-based tests runnable via `sbt test`.

- [ ] **Step 1: Add dependencies to `build.sbt`**

```scala
libraryDependencies += "com.google.genai" % "google-genai" % "1.76.0"
libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
testFrameworks += new TestFramework("munit.Framework")
```

- [ ] **Step 2: Verify the build picks up both**

Run: `sbt compile`
Expected: `[success]`, no dependency resolution errors.

- [ ] **Step 3: Write one throwaway test to prove the test framework runs, then delete it**

```scala
// Test/SmokeTest.scala
class SmokeTest extends munit.FunSuite:
  test("munit runs"):
    assert(true)
```

Run: `sbt test`
Expected: 1 test passes. Delete `SmokeTest.scala` after confirming (Task 2 adds real tests).

- [ ] **Step 4: Commit**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/build.sbt
git commit -m "Added: Gemini SDK And MUnit Dependencies To Core"
```

---

### Task 2: `StructuredTool` trait + `ToolRegistry`

**Files:**
- Create: `NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/StructuredTool.scala`
- Create: `NaturalLanguageCLIManipulatorCoreMSC/Source/AgenticReasoning/ToolRegistry.scala`
- Test: `NaturalLanguageCLIManipulatorCoreMSC/Test/AgenticReasoning/ToolRegistrySpec.scala`

**Interfaces:**
- Produces:
  ```scala
  package amy.apparatuses

  enum ToolExecutionOutcome:
    case Success(summary: String)
    case Failure(reason: String)

  trait StructuredTool:
    def name: String
    def isMutating: Boolean
    def functionDeclaration: com.google.genai.types.FunctionDeclaration
    def execute(args: Map[String, Any]): ToolExecutionOutcome
  ```
  ```scala
  package amy.agenticreasoning

  final class ToolRegistry(tools: Seq[amy.apparatuses.StructuredTool]):
    def declarations: Seq[com.google.genai.types.FunctionDeclaration]
    def find(name: String): Option[amy.apparatuses.StructuredTool]
  ```
- Consumes: nothing yet (this is the foundation every later task's tool builds on).

- [ ] **Step 1: Write failing tests for `ToolRegistry`**

```scala
package amy.agenticreasoning

class ToolRegistrySpec extends munit.FunSuite:
  test("find returns the matching tool by name"):
    val fake = FakeTool("Ping", isMutating = false)
    val registry = ToolRegistry(Seq(fake))
    assertEquals(registry.find("Ping"), Some(fake))

  test("find returns None for an unknown name"):
    val registry = ToolRegistry(Seq.empty)
    assertEquals(registry.find("Nonexistent"), None)

  test("declarations returns one FunctionDeclaration per registered tool"):
    val registry = ToolRegistry(Seq(FakeTool("A", false), FakeTool("B", true)))
    assertEquals(registry.declarations.map(_.name().get()).toSet, Set("A", "B"))
```

`FakeTool` is a minimal test-only `StructuredTool` implementation local to the test file, returning a trivial `FunctionDeclaration` built with the given name and no parameters, and a hardcoded `ToolExecutionOutcome.Success("fake")` from `execute`.

- [ ] **Step 2: Run to verify failure**

Run: `sbt test`
Expected: FAIL — `ToolRegistry`/`StructuredTool` not defined.

- [ ] **Step 3: Implement `StructuredTool` and `ToolRegistry`**

One line on the approach: `ToolRegistry` is a plain lookup — no Gemini-specific logic lives here, only dispatch by name and declaration aggregation.

- [ ] **Step 4: Run to verify pass**

Run: `sbt test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/StructuredTool.scala NaturalLanguageCLIManipulatorCoreMSC/Source/AgenticReasoning/ToolRegistry.scala NaturalLanguageCLIManipulatorCoreMSC/Test/AgenticReasoning/ToolRegistrySpec.scala
git commit -m "Added: StructuredTool Trait And ToolRegistry"
```

---

### Task 3: `ListFiles` tool

**Files:**
- Create: `NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/ListFilesTool.scala`
- Test: `NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/ListFilesToolSpec.scala`

**Interfaces:**
- Consumes: `amy.apparatuses.StructuredTool`, `amy.apparatuses.ToolExecutionOutcome` (Task 2).
- Produces: `ListFilesTool.execute(Map("folderPath" -> path))` returning `ToolExecutionOutcome.Success(jsonSummary)` where `jsonSummary` lists each filename with its resolved ISO-8601 timestamp; `Failure` if the folder doesn't exist.

- [ ] **Step 1: Write failing tests**

```scala
class ListFilesToolSpec extends munit.FunSuite:
  test("parses the Screenshot filename convention over mtime"):
    // in a temp dir, create a file named "Screenshot 2026-10-29 124407.png"
    // with a deliberately different mtime, assert the reported timestamp
    // matches the filename, not the mtime

  test("falls back to mtime for a filename that doesn't match the convention"):
    // a file named "vacation.jpg" - assert its reported timestamp is its mtime

  test("returns every file in the folder, unfiltered"):
    // 3 files with different timestamps - assert all 3 appear in the result

  test("fails when the folder does not exist"):
    assert(ListFilesTool.execute(Map("folderPath" -> "Z:/does/not/exist")).isInstanceOf[ToolExecutionOutcome.Failure])
```

- [ ] **Step 2: Run to verify failure**

Run: `sbt test`
Expected: FAIL — `ListFilesTool` not defined.

- [ ] **Step 3: Implement `ListFilesTool`**

Filename timestamp parsing: regex `Screenshot (\d{4}-\d{2}-\d{2}) (\d{6})\.\w+`, parsed as `LocalDateTime` (`yyyy-MM-dd HHmmss`); any file not matching uses `Files.getLastModifiedTime`.

- [ ] **Step 4: Run to verify pass**

Run: `sbt test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/ListFilesTool.scala NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/ListFilesToolSpec.scala
git commit -m "Added: ListFilesTool | Filename-Convention Timestamp Parsing With mtime Fallback"
```

---

### Task 4: `CreateFolder` tool

**Files:**
- Create: `NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/CreateFolderTool.scala`
- Test: `NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/CreateFolderToolSpec.scala`

**Interfaces:**
- Consumes: Task 2's trait.
- Produces: `CreateFolderTool.execute(Map("path" -> path))`.

- [ ] **Step 1: Write failing tests**

```scala
class CreateFolderToolSpec extends munit.FunSuite:
  test("creates a new folder and returns Success"):
  test("fails loudly when the target already exists, does not touch it"):
```

- [ ] **Step 2: Run to verify failure**
Run: `sbt test` — Expected: FAIL.

- [ ] **Step 3: Implement `CreateFolderTool`** (`java.nio.file.Files.createDirectory`; catch `FileAlreadyExistsException` → `Failure`).

- [ ] **Step 4: Run to verify pass** — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/CreateFolderTool.scala NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/CreateFolderToolSpec.scala
git commit -m "Added: CreateFolderTool"
```

---

### Task 5: `MoveFiles` tool

**Files:**
- Create: `NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/MoveFilesTool.scala`
- Test: `NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/MoveFilesToolSpec.scala`

**Interfaces:**
- Produces: `MoveFilesTool.execute(Map("sourcePaths" -> List[String], "destinationFolder" -> String))` → `Success` summarizing per-file outcomes even when some failed (this tool never returns a bare `Failure` for a partial batch — only when the whole call is malformed, e.g. destination folder missing).

- [ ] **Step 1: Write failing tests**

```scala
class MoveFilesToolSpec extends munit.FunSuite:
  test("moves a single file"):
  test("moves a batch of files, all succeeding"):
  test("one missing source file fails only that item, others still move"):
  test("a destination-name collision fails only that item, others still move"):
```

- [ ] **Step 2: Run to verify failure** — Expected: FAIL.

- [ ] **Step 3: Implement `MoveFilesTool`** (`Files.move` per item with `ATOMIC_MOVE` where supported, no `REPLACE_EXISTING` — a pre-existing destination name must fail that item, per the collision policy).

- [ ] **Step 4: Run to verify pass** — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/MoveFilesTool.scala NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/MoveFilesToolSpec.scala
git commit -m "Added: MoveFilesTool | Per-Item Batch Results, No Silent Overwrite"
```

---

### Task 6: `RenameFiles` tool

**Files:**
- Create: `NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/RenameFilesTool.scala`
- Test: `NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/RenameFilesToolSpec.scala`

**Interfaces:**
- Produces: `RenameFilesTool.execute(Map("mapping" -> List[(oldPath: String, newName: String)]))`, same per-item-result shape as Task 5.

- [ ] **Step 1: Write failing tests** (single rename succeeds; batch succeeds; one missing source fails only that item; one new-name collision fails only that item).
- [ ] **Step 2: Run to verify failure** — Expected: FAIL.
- [ ] **Step 3: Implement `RenameFilesTool`** (`Files.move` to the same parent directory under the new name; same no-overwrite rule as Task 5).
- [ ] **Step 4: Run to verify pass** — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/RenameFilesTool.scala NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/RenameFilesToolSpec.scala
git commit -m "Added: RenameFilesTool | Per-Item Batch Results, No Silent Overwrite"
```

---

### Task 7: `ConvertImage` tool

**Files:**
- Create: `NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/ConvertImageTool.scala`
- Test: `NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/ConvertImageToolSpec.scala`

**Interfaces:**
- Produces: `ConvertImageTool.execute(Map("sourcePath" -> String, "targetFormat" -> String))` → `Success(newFilePath)` or `Failure`.

- [ ] **Step 1: Write failing tests**

```scala
class ConvertImageToolSpec extends munit.FunSuite:
  test("converts a PNG to JPG and the output is readable as that format"):
  test("fails for a source file that does not exist"):
  test("fails for an unsupported target format"):
```

- [ ] **Step 2: Run to verify failure** — Expected: FAIL.
- [ ] **Step 3: Implement `ConvertImageTool`** (`ImageIO.read`/`ImageIO.write`; check `ImageIO.getImageWritersByFormatName(targetFormat)` is non-empty before attempting, to produce a clean `Failure` instead of an exception for an unsupported format).
- [ ] **Step 4: Run to verify pass** — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/ConvertImageTool.scala NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/ConvertImageToolSpec.scala
git commit -m "Added: ConvertImageTool | ImageIO-Based, Image Formats Only"
```

---

### Task 8: `CompressFiles` tool

**Files:**
- Create: `NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/CompressFilesTool.scala`
- Test: `NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/CompressFilesToolSpec.scala`

**Interfaces:**
- Produces: `CompressFilesTool.execute(Map("sourcePaths" -> List[String], "destinationZipPath" -> String))`.

- [ ] **Step 1: Write failing tests**

```scala
class CompressFilesToolSpec extends munit.FunSuite:
  test("zips multiple files, archive contains exactly those entries"):
  test("fails when the destination zip path already exists"):
  test("one missing source file fails only that item, the rest are still zipped"):
```

- [ ] **Step 2: Run to verify failure** — Expected: FAIL.
- [ ] **Step 3: Implement `CompressFilesTool`** (`java.util.zip.ZipOutputStream`; check destination doesn't already exist up front per collision policy).
- [ ] **Step 4: Run to verify pass** — Expected: PASS.
- [ ] **Step 5: Commit**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/Source/Apparatuses/Structured/CompressFilesTool.scala NaturalLanguageCLIManipulatorCoreMSC/Test/Apparatuses/Structured/CompressFilesToolSpec.scala
git commit -m "Added: CompressFilesTool | java.util.zip-Based, .zip Only"
```

---

### Task 9: `GeminiClient` — thin SDK wrapper

**Files:**
- Create: `NaturalLanguageCLIManipulatorCoreMSC/Source/AgenticReasoning/GeminiClient.scala`
- Test: `NaturalLanguageCLIManipulatorCoreMSC/Test/AgenticReasoning/GeminiClientSpec.scala`

**Interfaces:**
- Produces:
  ```scala
  package amy.agenticreasoning

  enum ModelTurn:
    case FunctionCalls(calls: Seq[(name: String, args: Map[String, Any])])
    case FinalAnswer(text: String)

  trait GeminiClient:
    def sendTurn(history: Seq[ConversationEntry], declarations: Seq[com.google.genai.types.FunctionDeclaration]): ModelTurn
  ```
  `ConversationEntry` is this task's own small ADT wrapping a user message, a function-call request, or a function-response — the shape `ReasoningLoop` (Task 10) will build up turn by turn.
- Consumes: `ToolRegistry.declarations` (Task 2) as the `declarations` parameter shape.

- [ ] **Step 1: Write a failing test proving the response-parsing logic (NOT a live API call) — e.g. given a hand-built SDK `GenerateContentResponse` containing one function-call part, `parseTurn` returns `ModelTurn.FunctionCalls` with the right name/args; given one with only a text part, returns `ModelTurn.FinalAnswer`.** Split the parsing logic into a standalone function for this — it's the only part of this task that's unit-testable without the network.

- [ ] **Step 2: Run to verify failure** — Expected: FAIL.

- [ ] **Step 3: Implement `GeminiClient`** — real implementation builds a `com.google.genai.Client` reading `GEMINI_API_KEY` from `NaturalLanguageCLIManipulatorCoreMSC/.env` (a tiny manual `.env` line-parser is fine here — no library needed for one `KEY=value` line), disables AFC, calls `models.generateContent("gemini-3.5-flash-lite", ...)`, and the parsing logic from Step 1 turns the raw response into `ModelTurn`.

- [ ] **Step 4: Run to verify pass** — Expected: PASS (for the parsing test; this task has no automated live-API test — see Step 5).

- [ ] **Step 5: Manual verification (not part of the automated suite — avoids burning the ~500/day quota on every test run)**

Run a small ad-hoc script/REPL call: `GeminiClient.sendTurn` with one trivial tool declaration and a prompt that should trigger it. Confirm a real `ModelTurn.FunctionCalls` comes back with the expected name.

- [ ] **Step 6: Commit**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/Source/AgenticReasoning/GeminiClient.scala NaturalLanguageCLIManipulatorCoreMSC/Test/AgenticReasoning/GeminiClientSpec.scala
git commit -m "Added: GeminiClient | Manual Function-Calling, AFC Disabled"
```

---

### Task 10: `ReasoningLoop`

**Files:**
- Create: `NaturalLanguageCLIManipulatorCoreMSC/Source/AgenticReasoning/ReasoningLoop.scala`
- Test: `NaturalLanguageCLIManipulatorCoreMSC/Test/AgenticReasoning/ReasoningLoopSpec.scala`

**Interfaces:**
- Consumes: `GeminiClient` (Task 9, as a trait — tests use a fake implementation scripted to return a fixed sequence of `ModelTurn`s), `ToolRegistry`/`StructuredTool` (Task 2).
- Produces:
  ```scala
  package amy.agenticreasoning

  final case class PendingToolCall(toolName: String, args: Map[String, Any], description: String)
  final case class ReasoningResult(finalAnswer: String, pendingPlan: Seq[PendingToolCall])

  final class ReasoningLoop(client: GeminiClient, registry: ToolRegistry):
    def run(request: String, currentDateTime: java.time.LocalDateTime): ReasoningResult
  ```

- [ ] **Step 1: Write failing tests using a fake `GeminiClient`**

```scala
class ReasoningLoopSpec extends munit.FunSuite:
  test("a read-only tool call is executed immediately and fed back, loop continues"):
    // fake client: turn 1 -> FunctionCalls(ListFiles), turn 2 -> FinalAnswer("done")
    // assert the real ListFilesTool's execute was actually invoked (use a temp dir)
    // assert result.pendingPlan is empty

  test("a mutating tool call is held, not executed, and appears in pendingPlan"):
    // fake client: turn 1 -> FunctionCalls(CreateFolder), turn 2 -> FinalAnswer("done")
    // assert the real folder was NOT created
    // assert result.pendingPlan contains exactly that one call

  test("multiple turns of mixed read-only and mutating calls accumulate correctly"):

  test("the current date/time is included as plain text context in the first turn sent to the client"):
    // fake client captures what it was called with; assert the formatted
    // currentDateTime string appears in the history/context passed to sendTurn
```

- [ ] **Step 2: Run to verify failure** — Expected: FAIL.

- [ ] **Step 3: Implement `ReasoningLoop.run`** — loop: send turn → on `FunctionCalls`, for each call look up the tool via `registry.find`, if `!tool.isMutating` execute now and append a function-response to history, else append to `pendingPlan` with a short natural-language `description` built from the tool name + args (used later for the Confirmation text) and still append a function-response acknowledging receipt so the model can continue; on `FinalAnswer`, return `ReasoningResult`.

- [ ] **Step 4: Run to verify pass** — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/Source/AgenticReasoning/ReasoningLoop.scala NaturalLanguageCLIManipulatorCoreMSC/Test/AgenticReasoning/ReasoningLoopSpec.scala
git commit -m "Added: ReasoningLoop | Manual Turn-By-Turn Dispatch, Mutating Calls Held For Confirmation"
```

---

### Task 11: Rewire `CoreEntryPoint`

**Files:**
- Modify: `NaturalLanguageCLIManipulatorCoreMSC/Source/CoreEntryPoint.scala`
- Modify: `ARCHITECTURE.md` (Tech Stack table: `Gemini 2.5 Flash` → `gemini-3.5-flash-lite`; Runtime Data Directory note: record the temporary project-local `.env` deviation; Status section: describe the new working reasoning loop, replacing the "sends one hardcoded `ConfirmationRequest`" line)

**Interfaces:**
- Consumes: `ReasoningLoop.run` (Task 10), `UserInterfaceChannel` (existing), `ConfirmationRequest`/`ConfirmationResponse` (existing, from `AmyIpc.proto`).

- [ ] **Step 1: Write failing tests for two standalone, testable functions — the plan formatter and the confirmation-result executor (neither touches the IPC channel directly, so both are unit-testable without a real UI process)**

```scala
class PendingPlanFormattingSpec extends munit.FunSuite:
  test("formats zero pending calls as a plan requiring no confirmation"):
  test("formats one CreateFolder and one MoveFiles call into a readable, multi-line description"):

class ConfirmationExecutionSpec extends munit.FunSuite:
  test("approved = true executes every pending call via the registry and reports a per-item result"):
    // fake StructuredTool records whether execute() was called; assert it was, for each pending call
  test("approved = false executes nothing and reports a clean decline, with no tool's execute called"):
```

- [ ] **Step 2: Run to verify failure** — Expected: FAIL.

- [ ] **Step 3: Implement the two functions, then rewire `CoreEntryPoint` to call them**

```scala
def formatPlanForConfirmation(plan: Seq[PendingToolCall]): String
def executeConfirmedPlan(approved: Boolean, plan: Seq[PendingToolCall], registry: ToolRegistry): String // returns the OutputLine text
```

`@main def CoreEntryPoint(request: String): Unit` — builds the real `ToolRegistry` with all six tools, runs `ReasoningLoop`, and: if `result.pendingPlan` is empty, sends `result.finalAnswer` directly as an `OutputLine` (nothing to confirm); otherwise sends `ConfirmationRequest(command = formatPlanForConfirmation(result.pendingPlan), reason = request)` over `UserInterfaceChannel`, awaits the `ConfirmationResponse`, and sends `executeConfirmedPlan(response.approved, result.pendingPlan, registry)` as the closing `OutputLine`.

- [ ] **Step 4: Run to verify pass** — Expected: PASS.

- [ ] **Step 5: Update `ARCHITECTURE.md`** per the Files note above.

- [ ] **Step 6: Commit**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/Source/CoreEntryPoint.scala ARCHITECTURE.md NaturalLanguageCLIManipulatorCoreMSC/Test/PendingPlanFormattingSpec.scala NaturalLanguageCLIManipulatorCoreMSC/Test/ConfirmationExecutionSpec.scala
git commit -m "Added: Real Agentic Loop Wired Into CoreEntryPoint | Replaces Hardcoded Three-Flow Demo"
```

---

### Task 12: Sandbox end-to-end proof

**Files:**
- Create: a sandbox folder under the Core project's scratch area (exact location is the builder's call — e.g. `NaturalLanguageCLIManipulatorCoreMSC/.sandbox/Screenshots/`, gitignored) with synthetic files named per the real convention (`Screenshot YYYY-MM-DD HHMMSS.png`), spanning several days and times of day, so some clearly qualify as "yesterday evening" (relative to whenever the test is run) and some clearly don't.

**Interfaces:**
- Consumes: everything from Tasks 1–11, against the real Gemini API (uses real quota — run this once deliberately, not repeatedly).

- [ ] **Step 1: Generate the synthetic sandbox files** (zero-byte or trivially tiny valid PNGs are fine — `ListFiles`/`MoveFiles` don't inspect image content, only `ConvertImage` needs a real decodable image, which already has its own isolated test in Task 7).

- [ ] **Step 2: Run the real command by hand, in a real terminal** (per this session's own hard-won lesson: piped/automated stdin into a process that then spawns a child via `inheritIO()` does not reliably work on Windows — this step is manual, not scripted):

```bash
cd NaturalLanguageCLIManipulatorCoreMSC
sbt "run \"create a folder named SandboxProof in .sandbox/Screenshots and move all the images taken yesterday evening into it\""
```

- [ ] **Step 3: Verify manually**: the Confirmation prompt lists exactly the files that should qualify given the sandbox's synthetic timestamps (not more, not fewer); approving it actually creates the folder and moves exactly those files; declining it leaves the sandbox untouched.

- [ ] **Step 4: Only after the sandbox proof passes — optionally, by hand, run the same command against the real folder** (`C:\Users\UddeshyaSingh\OneDrive - We.PLM\Pictures\Screenshots`) if you want to actually use the feature on your real screenshots. Not required to consider this plan complete.

- [ ] **Step 5: Commit the sandbox fixture generation script (not its generated output, if gitignored) and anything fixed as a result of this proof run**

```bash
git add NaturalLanguageCLIManipulatorCoreMSC/.sandbox/.gitkeep NaturalLanguageCLIManipulatorCoreMSC/.gitignore
git commit -m "Added: Sandbox End-To-End Proof For The Screenshot-Organizing Scenario"
```
