# Amy — Architecture

> This is a planning document, written before any code exists. It captures the agreed shape of the system so implementation can start from a shared design rather than improvising structure along the way. Terms like Tool, Session, Risk Judgment, Permission Grant, etc. are defined in `CONTEXT.md` — this document assumes that vocabulary and focuses on *how* those concepts are realized technically. Treat this as a draft: flag anything that should change before code is written against it.

## 1. Process Architecture

Amy ships as a single CLI install (`npm install -g`), but internally runs as **two cooperating processes** per invocation, talking over stdio:

```
┌─────────────────────────────┐        stdio        ┌──────────────────────────────┐
│   Core (Scala, JVM,         │ <──────────────────> │   User Interface (TypeScript, │
│   GraalVM native-image)     │   IPC messages        │   Node.js)                    │
│                              │                       │                               │
│  • Receives the Request      │                       │  • Renders everything the     │
│  • Runs the reasoning loop   │                       │    user sees                  │
│  • Decides & executes Steps  │                       │  • Collects user input         │
│    via Tool Calls            │                       │    (Confirmation, Access       │
│  • Risk Judgment / Safety    │                       │    Request answers,            │
│    Net / Permission checks   │                       │    Clarification answers)      │
│  • Writes Command Execution  │                       │  • Inline rendering by          │
│    Log + Session Summary     │                       │    default; full-screen for     │
│  • Calls Gemini (reasoning)  │                       │    large diffs / pickers         │
│    and Ollama (embeddings)   │                       │                               │
└─────────────────────────────┘                       └──────────────────────────────┘
```

**Why two processes instead of one runtime:** Scala (JVM, compiled to a native binary via GraalVM native-image) was chosen specifically — not Scala.js — for the agent engine. TypeScript/Node was chosen specifically for the terminal rendering layer, for its CLI UI ecosystem. Since these are two genuinely different runtimes, they run as separate OS processes and communicate over stdio rather than sharing memory or source.

**What crosses the boundary:** Core never writes directly to the terminal and never reads stdin directly — it sends the UI process structured messages ("show this Confirmation prompt with these options", "render this line of output", "ask this Clarification question"), and the UI process sends back structured responses ("user chose option 2", "user typed: ..."). The shared message schema for this protocol lives in `…SharedNMSC/`, implemented independently on each side (no shared compiled code is possible across a JVM/Node boundary).

**Launch**: the `amy` command is a thin launcher. It starts the Core process, which in turn spawns the UI process and attaches to it over stdio pipes; the UI process does not run standalone. On first run (no config under `~/.Amy/`), Core routes straight into Setup before processing any Request.

## 2. Tech Stack

| Concern | Choice |
|---|---|
| Agent engine language | Scala, compiled via GraalVM native-image |
| Terminal UI language | TypeScript, Node.js |
| LLM (reasoning / tool-calling) | Gemini 2.5 Flash |
| Embeddings (for Recall) | Local Ollama, `nomic-embed-text` model |
| Vector storage / similarity | Flat local files, cosine similarity computed in-process (no vector DB, no server) |
| Config format | YAML (human-editable: permissions, settings) |
| Log / history format | XML (machine-generated: Command Execution Logs, Session Summaries) |
| Distribution | `npm install -g` |
| Platforms | Windows, macOS, Linux |

## 3. Repository Structure

```
NaturalLanguageCLIManipulatorCodebaseArchitecture/            (repo root)
├── CONTEXT.md
├── ARCHITECTURE.md
├── CODING-RULES.md
├── README.md
├── LICENSE                                                    (MIT)
├── .gitignore
│
├── NaturalLanguageCLIManipulatorCoreMSC/                      (Scala — agent engine)
│   ├── Source/
│   │   ├── Agent/            — Request → Step planning; the reasoning loop driving Gemini
│   │   ├── Tools/
│   │   │   ├── Structured/   — one file per Structured Tool (e.g. GitCommitTool.scala,
│   │   │   │                   CompressFolderTool.scala, SummarizeFileTool.scala,
│   │   │   │                   AskQuestionTool.scala)
│   │   │   └── ShellFallbackTool.scala
│   │   ├── Safety/           — Risk Judgment, the Safety Net pattern list, Command Echo
│   │   ├── Permissions/      — Permission Grant store (reads/writes DirectoryPermissions.yaml),
│   │   │                       Access Request flow
│   │   ├── Logging/          — Command Execution Log writer (XML)
│   │   ├── Memory/           — Session Summary writer, Ollama embedding client,
│   │   │                       cosine-similarity Recall
│   │   ├── Setup/            — first-run + `amy setup` onboarding wizard logic
│   │   ├── Ipc/              — stdio protocol implementation, Core side
│   │   └── Main.scala
│   ├── Test/
│   └── build.sbt, project/   — sbt build files (repoints default source dirs to Source/ and Test/)
│
├── NaturalLanguageCLIManipulatorUserInterfaceMSC/             (TypeScript — terminal UI)
│   ├── Source/
│   │   ├── Prompts/          — ConfirmationPrompt.ts, AccessRequestPrompt.ts,
│   │   │                       ClarificationPrompt.ts (arrow-key + vim-key navigation)
│   │   ├── Rendering/        — InlineRenderer.ts, FullScreenRenderer.ts
│   │   ├── Ipc/              — stdio protocol implementation, UI side
│   │   └── Main.ts
│   ├── Test/
│   └── package.json, tsconfig.json
│
├── NaturalLanguageCLIManipulatorSharedNMSC/                   (cross-process contract)
│   └── IpcProtocol/          — the message schema both processes implement independently
│                               (message types/shapes, not shared compiled code)
│
└── NaturalLanguageCLIManipulatorAgentDocumentationNMSC/       (docs, PRDs, reference material)
    ├── ReferenceCodingRules.md   — verbatim copy of the original Assetsphere CODING-RULES.md
    └── Adr/                      — created lazily, first time a decision needs recording
```

## 4. Runtime Data Directory (on the end user's machine)

Not part of the repository — created on the machine `amy` is installed on, shared across every Project:

```
~/.Amy/
├── Configurations/
│   ├── DirectoryPermissions.yaml      — global Permission Grant store
│   └── .env                            — Gemini API key (never project-local)
│
├── CommandExecutionLogs/
│   └── <ProjectName>/
│       └── <SessionId>.xml             — full detail: every command, output, decision
│
└── CommandExecutionLogHistory/
    └── <ProjectName>/
        └── <SessionId>-Summary.xml     — condensed Session Summary + its embedding vector,
                                           used for Recall
```

`<ProjectName>` is derived from the directory Amy was invoked from (the Project, per `CONTEXT.md`). `<SessionId>` identifies one Session.

## 5. Key Mechanisms

**Tool dispatch (hybrid model)**: the Agent reasoning loop asks Gemini, for each Step, which Tool to call. Structured Tools are preferred when one exists for the operation; the Shell Fallback Tool is used only when no Structured Tool covers what's needed. Both paths produce a Tool Call that goes through the same Safety and Permissions checks before executing — there is no shortcut that skips them for either kind of Tool.

**Safety**: every Tool Call is evaluated by Risk Judgment (Gemini's own assessment of whether it's destructive) and checked against the Safety Net (a hardcoded pattern list) independently — either one flagging it is enough to require Confirmation. The Command Echo (literal command text) is always shown before a shell command runs, regardless of risk.

**Permissions**: before any filesystem-touching Tool Call executes, Core checks the directory it needs against the current Permission Grants. If not covered, it raises an Access Request through the UI (Allow once / Always allow this folder / Always allow this folder + subfolders / Deny) and persists an "always allow" answer to `DirectoryPermissions.yaml`. This can repeat multiple times within one Session as different directories come up.

**Memory & Recall**: at the end of a Session, Core writes a Session Summary and asks Ollama (`nomic-embed-text`) to embed it, storing the vector alongside the summary. When a new Session starts, Core can query past Session Summaries for the current Project by computing cosine similarity against the new Request's embedding, pulling in relevant prior context. If Ollama is not installed or not running, Core stops and guides the user through getting it set up rather than silently falling back to no-memory behavior.

**Clarification**: at any point in the reasoning loop, the Agent can call the `ask_question` Tool instead of a Step-executing Tool. This sends a Clarification message to the UI, which blocks on an interactive prompt (MCQ or free text, arrow keys or vim keys) and returns the answer, after which the reasoning loop continues — the overall `amy` invocation is still one Session from the user's point of view.

**Onboarding**: on launch, Core checks for `~/.Amy/Configurations/`. If absent, it runs Setup (grant permissions, provide/validate the Gemini key, check for Ollama) before processing the Request. `amy setup` re-enters this flow explicitly at any time.

## 6. Open Implementation Details (to settle during build, not blocking this document)

- Exact IPC wire format (e.g. newline-delimited JSON over stdio) — functionally decided (stdio, structured messages) but the precise framing/schema will be nailed down in `…SharedNMSC/IpcProtocol/` as the first thing built.
- Exact Safety Net pattern list (which commands/flags are hardcoded as always-confirm).
- Exact sbt configuration repointing `Compile / scalaSource` and `Test / scalaSource` to `Source/` and `Test/`.
