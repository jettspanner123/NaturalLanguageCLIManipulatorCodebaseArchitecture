# Amy: Real Agentic Loop + File-Operation Tools

## Intent

Amy's Core process currently proves the IPC plumbing works (`CoreEntryPoint.scala` fires three hardcoded demo prompts — Confirmation, Access Request, Clarification — regardless of what the user would have typed). No code calls an LLM anywhere. This design replaces that hardcoded demo with the first real, working slice of Amy: a natural-language command gets turned into actual file operations on the user's machine, via Gemini's native function-calling over a small catalog of real Tools.

The motivating scenario (not a special case to hardcode, just the first real test): *"Amy, create a folder named X in Screenshots and put all the images taken yesterday evening into it."*

## Goals

- Replace the hardcoded demo flow in `CoreEntryPoint` with: read a real command → call Gemini with function-calling enabled over a Tool catalog → execute the resulting plan for real, after one combined user confirmation.
- Ship five general-purpose, reusable file-operation Tools (not a one-off screenshot script): `CreateFolder`, `ListFiles`, `MoveFiles`, `RenameFiles`, `ConvertImage`, `CompressFiles`.
- Prove it end-to-end against a sandbox folder with synthetic files before ever touching the user's real OneDrive Screenshots folder.

## Non-Goals (this pass)

- No document/video/audio conversion — `ConvertImage` is image-formats-only (`PNG`/`JPG`/`BMP`/`GIF`, whatever Java's built-in `ImageIO` supports out of the box).
- No archive formats besides `.zip`.
- No `~/.Amy/Configurations/` persistent config system — the Gemini key lives in a project-local `.env` for now; the home-directory config system is a separate future feature.
- No Safety Net destructive-command classification system, no Permissions persistence, no Memory/Recall — all still roadmap, untouched by this work.
- No per-action confirmation — one combined confirmation for the whole planned batch.

## Anti-Goals

- **Revised after the first design review** (the original version of this rule was wrong and got reversed): do not hardcode a fixed vocabulary or fixed clock boundaries for time-of-day phrases ("evening" = 5–9pm, etc.) — that breaks the moment the user phrases a request slightly differently, which defeats the point of using an LLM at all. Classifying which files match a natural-language time criterion, given their exact parsed timestamps, is legitimate LLM judgment, not LLM arithmetic, and is explicitly in scope for Gemini to do itself.
- What Gemini should *not* do is invent "right now" from nothing — it has no reliable built-in sense of the real current date/time, so Core includes the actual current timestamp as plain context in every request, giving the model a correct anchor for relative terms ("yesterday", "last week") before it reasons over the file list.
- Do not silently execute anything real without the user seeing and confirming the full plan first.
- Do not expand scope into Tools beyond the five agreed here (Rename/Convert/Compress are explicitly in scope now, per the user's explicit request — not deferred, unlike the original narrower recommendation).
- Do not silently overwrite an existing file/folder on a naming collision, and do not let one failed item in a batch silently swallow the rest of the batch's results.

## Constraints / Confirmed Facts

- **Model**: `gemini-3.5-flash-lite` — confirmed free-tier, ~500 requests/day (vs. ~20/day for the more capable `gemini-3.8-flash`), chosen for iteration headroom during development. Verified live against the real API with the user's key (HTTP 200, real response).
- **SDK**: `com.google.genai:google-genai` (Maven Central, GA, pure Java artifact — add via `libraryDependencies += "com.google.genai" % "google-genai" % "<pinned version>"`, no `%%` cross-versioning). Supports Automatic Function Calling directly off `Models.generateContent`. Google has flagged a future major-version move to a `Chats`-based API as a breaking change — **pin the major version in `build.sbt`** so a routine `sbt update` doesn't pull it in silently.
- **Credential**: `NaturalLanguageCLIManipulatorCoreMSC/.env` holding `GEMINI_API_KEY=...` — already created, confirmed covered by the existing `.gitignore` (`*.env` pattern), never committed.
- **Real target folder** (post-sandbox): `C:\Users\UddeshyaSingh\OneDrive - We.PLM\Pictures\Screenshots` — note this is OneDrive-redirected, not the plain `Pictures\Screenshots` path; don't hardcode the plain path.
- **Screenshot filename convention on this machine**: `Screenshot YYYY-MM-DD HHMMSS.png`, 24-hour clock, capture time embedded directly in the filename — reliable to parse directly, more trustworthy than file-system mtime (which OneDrive sync can drift).
- **Existing reusable pieces**: the loopback socket + protobuf framing (`UserInterfaceChannel`), and all three IPC flows (Confirmation/Access Request/Clarification) with their UI-side prompts in `Promptomates/` — already built and committed, to be reused for the real confirmation step (not the canned demo text currently in `CoreEntryPoint`).
- **AFC mixed-registration is undocumented, so don't build on it**: a builder-check pass confirmed `com.google.genai`'s `Tool.Builder` can structurally hold both reflection-backed (auto-invoked) and schema-only (manual) function declarations side by side, but the runtime behavior when the model calls a declaration with no backing method is undocumented anywhere reachable (not in javadoc, source, GitHub issues, or release notes) — it might auto-run what it can and pause on the rest, or it might silently disable AFC for the whole turn. Rather than spike this uncertain path, the design uses the SDK's **confirmed-supported fully-manual mode** instead (`AutomaticFunctionCallingConfig.builder().disable(true)`, every tool declared schema-only, Core reads each `functionCall` part and decides itself whether to auto-run it or pause for confirmation). This also happens to be the only mode that can deliver the "one combined confirmation before executing" requirement at all, regardless of how AFC's mixed mode actually behaves.
- **Collision policy** (`CreateFolder` target exists, a `MoveFiles`/`RenameFiles` destination filename collides, a `CompressFiles` zip path exists): fail loudly for that one item, skip it, and report it in the final summary. Never silently overwrite.
- **Partial-batch failure policy**: process each file in a batch independently; a failure on one file doesn't block or roll back the others. Report per-file success/failure in the final result.
- **Test coverage scope for this pass**: only the "organize screenshots by time" scenario gets a full natural-language end-to-end proof. `ConvertImage`, `CompressFiles`, and `RenameFiles` are implemented and unit-tested in isolation this pass, not exercised by a real natural-language command yet — a dedicated proof scenario for each is a fast, cheap follow-up once this lands.

## Decided Architecture

1. **Entry point**: `CoreEntryPoint` reads one real natural-language command (source of the command string — stdin line vs. a CLI arg — is left to the builder; see below) instead of firing the three canned demo prompts.
2. **Gemini call loop (manual, not AFC)**: Core sends the command plus the real current date/time as plain text context, with every tool declared schema-only (AFC disabled). Core reads the response: if it contains a `functionCall` part, Core either executes it immediately and feeds the result back (read-only tools — see below), or, for a mutating tool, holds it as part of the pending plan instead of executing yet, feeds back a placeholder/ack, and continues the loop until Gemini stops requesting calls and produces its final answer. This repeats across however many turns it takes.
3. **Tool catalog** (all implemented now, each a small, independently reusable unit — not screenshot-specific):
   - `ListFiles(folderPath)` → **read-only, auto-run** — every filename in the folder plus its exact parsed timestamp (from the filename convention where it matches, mtime as fallback), **raw and complete, no pre-filtering by Scala**. Gemini itself decides which files match whatever natural-language criterion the user gave (e.g. "evening"), using the real current-date context from step 2 — there is no fixed vocabulary or hardcoded time-of-day boundaries anywhere in this pipeline.
   - `CreateFolder(path)` → **mutating, held for confirmation**.
   - `MoveFiles(sourcePaths: List[Path], destinationFolder: Path)` → **mutating, held for confirmation** — handles single or batch uniformly.
   - `RenameFiles(mapping: List[(oldPath, newName)])` → **mutating, held for confirmation** — handles single or batch uniformly.
   - `ConvertImage(sourcePath, targetFormat)` → **mutating, held for confirmation** — via `ImageIO`.
   - `CompressFiles(sourcePaths: List[Path], destinationZipPath: Path)` → **mutating, held for confirmation** — via `java.util.zip`.
   - All mutating tools: on a naming collision, fail just that item, skip it, continue; on a multi-item batch, process each item independently and report per-item success/failure rather than all-or-nothing.
4. **Confirmation**: once Gemini's turn produces its final answer (no more tool calls requested), Core has accumulated the full list of mutating calls it asked for along the way. Core sends ONE `ConfirmationRequest` over the existing IPC channel describing the whole batch (e.g. "Move these 7 files into new folder 'X'?", naming the specific files Gemini picked), and only executes the held calls for real after the user approves.
5. **Testing**: a sandbox folder (location left to the builder — e.g. a scratch directory with synthetic files named per the real `Screenshot YYYY-MM-DD HHMMSS.png` convention) is used to prove the full loop end-to-end before ever pointing `ListFiles`/`MoveFiles` at the real OneDrive path. Only the move+create-folder scenario gets this full proof; `ConvertImage`/`CompressFiles`/`RenameFiles` get isolated unit tests this pass.

## What's Left to the Builder

- Exact package/file/class names for the new Tool implementations and the Gemini-calling code (will be proposed at the point each file is created, per this project's ask-before-naming convention — not pre-decided here).
- How the Core process reads the one real command for this pass (a stdin prompt, a CLI argument, etc.) — functionally minor, doesn't change the architecture above.
- Exact JSON/function-declaration schema wiring details for each Tool (mechanical SDK usage), and the exact mechanism for marking a declared tool as "read-only, auto-run" vs "mutating, hold for confirmation" in code (a type/trait distinction, a registry flag, etc. — functionally the behavior is fixed, the code shape isn't).
- Sandbox test folder's exact location and synthetic file contents.
