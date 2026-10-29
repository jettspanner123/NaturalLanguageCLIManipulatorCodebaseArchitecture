# Coding Rules

These rules are Amy-specific. A verbatim copy of the original coding rules this project was seeded from (written for a Playwright UI-automation codebase, kept for reference only — most of it does not apply here) lives at `NaturalLanguageCLIManipulatorAgentDocumentationNMSC/ReferenceCodingRules.md`.

Terms capitalized below (Tool, Structured Tool, Shell Fallback, Risk Judgment, Safety Net, Confirmation, Command Echo, Permission Grant, Session, Recall, ...) are defined in `CONTEXT.md` — read that first if a term here is unclear.

## General

- One primary class/object per file; the file name matches the class/object name exactly.
- Prefer the singleton pattern for stateful or shared-responsibility classes:
  ```scala
  object SomeService {
    def doSomething(): Unit = ???
  }
  ```
  ```typescript
  export default class SomeService {
      public static readonly current: SomeService = new SomeService();
      private constructor() {}

      public doSomething(): void {}
  }
  ```
- Everything must be fully typed. No untyped escapes (`Any` in Scala, `any` in TypeScript) without a comment explaining why one is unavoidable.
- Acronyms in identifiers are written in full caps, never Pascal-cased as if they were an ordinary word (`IPCMessage` not `IpcMessage`, `GitCLITool` not `GitCliTool`, `CLIArguments` not `CliArguments`). This does not apply to literal string/data values.
- Keep files small and single-responsibility. A file doing two unrelated things should be split.

## Naming & File Structure

- PascalCase for every folder and file in this repository, with the sole exceptions of filenames/foldernames mandated by an external tool/ecosystem (`package.json`, `tsconfig.json`, `build.sbt`, `project/` — sbt's own bootstrap folder, required verbatim so sbt can find `build.properties`/`plugins.sbt` before it even reads `build.sbt` — `.gitignore`, `.env`, `README.md`, `LICENSE`) and the `amy` command itself, which stays lowercase.
- The four top-level folders each own one responsibility. Cross the boundary between `…CoreMSC` and `…UserInterfaceMSC` only through the IPC contract defined in `…SharedNMSC`:
  - `NaturalLanguageCLIManipulatorCoreMSC/` — the Scala agent engine. No UI rendering code lives here.
  - `NaturalLanguageCLIManipulatorUserInterfaceMSC/` — the TypeScript terminal UI. No agent/reasoning logic lives here — it renders what Core sends it and forwards user input back.
  - `NaturalLanguageCLIManipulatorSharedNMSC/` — the IPC message contract (`IPCSharedProtocols/AmyIpc.proto`, Protocol Buffers) both processes generate typed code from independently. Core (JVM) and the UI (Node) cannot literally share compiled source, so this folder holds the single source-of-truth schema, not shared code.
  - `NaturalLanguageCLIManipulatorAgentDocumentationNMSC/` — documentation, PRDs, ADRs, reference material.
- Inside each process's `Source/` folder, group files by the concept they implement (`AgenticReasoning/`, `Apparatuses/`, `AISafetyLayer/`, `GuardRails/`, `Logging/`, `MemoryLane/`, `OnboardingInstallation/`, `IPC/` on the Core side; `Promptomates/`, `UIRenderers/`, `IPC/` on the UI side) — not by technical layer.

## Tools (Core)

- Every Tool — Structured or the Shell Fallback — lives under `Apparatuses/` and implements exactly one operation.
- A Structured Tool's parameters must be fully typed; never an untyped bag of strings.
- Only the Shell Fallback Tool is permitted to execute an arbitrary command line. Every Structured Tool must perform its operation through its own direct, typed implementation rather than shelling out.

## Safety

- Every Tool Call passes through Risk Judgment before execution. A positive judgment, or a Safety Net pattern match, must block execution until Confirmation is received — there is no code path that skips this.
- The Command Echo must be shown for every Tool Call that runs a shell command, regardless of its Risk Judgment outcome. No silent execution of a shell command, ever.

## Permissions

- All filesystem access by any Tool must be checked against the current Permission Grants before acting. There is no code path that bypasses this check, including the Shell Fallback Tool.

## Logging

- Every Tool Call, Risk Judgment outcome, Confirmation, and Access Request decision must be written to the Command Execution Log as it happens, not batched or best-effort.
