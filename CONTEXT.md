# Amy

Amy is a natural-language command-line agent: the user states what they want in plain English in their terminal, and Amy figures out and carries out the concrete steps needed to accomplish it using the tools available to it.

## Language

### Execution

**Request**:
The natural-language instruction the user gives Amy for a single invocation (e.g. "commit and push all the latest changes").
_Avoid_: Command, Prompt, Query

**Session**:
One complete run of Amy from Request to final result, including every Step, Tool Call, and Clarification exchanged along the way.
_Avoid_: Run, Invocation, Conversation

**Step**:
A single discrete action Amy takes while fulfilling a Request, produced by breaking the Request down into an ordered plan.
_Avoid_: Task

**Tool Call**:
An invocation of a Tool with specific arguments, made by Amy while carrying out a Step.
_Avoid_: Action, Operation

**Project**:
The directory tree Amy was invoked from, used as the grouping key for its Command Execution Logs and Session Summaries so Recall stays scoped to relevant history.
_Avoid_: Workspace, Directory

### Tools

**Tool**:
A named capability Amy can invoke to affect the user's system or gather information — the vocabulary Amy is allowed to act through.
_Avoid_: Function, Command, Action

**Structured Tool**:
A Tool with a fixed, well-defined set of parameters built specifically for a known kind of operation (e.g. committing to git, compressing a folder).
_Avoid_: Native tool, Built-in tool

**Shell Fallback**:
The general-purpose Tool Amy reaches for when no Structured Tool covers what the Request needs — it runs an arbitrary command line on the user's behalf.
_Avoid_: Raw command, Generic tool, Escape hatch

**Clarification**:
A pause in a Session where Amy asks the user a direct question — multiple-choice or free text — because it lacks enough information to safely continue, resuming the Session once answered.
_Avoid_: Prompt, Interruption

### Safety

**Risk Judgment**:
Amy's own determination, made while about to carry out a Step, of whether that Step is destructive or otherwise risky enough to require the user's Confirmation.
_Avoid_: Danger check

**Safety Net**:
The fixed, hardcoded set of patterns Amy checks a command against regardless of its own Risk Judgment, to catch destructive actions the Risk Judgment might miss.
_Avoid_: Denylist, Blocklist

**Confirmation**:
The user's explicit go-ahead, given in response to a flagged Step, before Amy proceeds with it.
_Avoid_: Approval, Permission

**Command Echo**:
The exact text of the command Amy is about to run, always shown to the user before execution regardless of its Risk Judgment.
_Avoid_: Preview, Plan

### Permissions

**Permission Grant**:
A recorded decision, tied to a specific directory, stating how much filesystem access Amy may use there without asking again.
_Avoid_: Access, Scope

**Access Request**:
A prompt Amy raises mid-Session when a Step needs to touch a directory it doesn't already hold a Permission Grant for.
_Avoid_: Permission prompt, Escalation

### Memory

**Command Execution Log**:
The complete, unabridged record of everything that happened during a Session — every command run, its output, and every Confirmation or Access Request decision made.
_Avoid_: History, Audit log

**Session Summary**:
A condensed record of a finished Session that Amy writes for its own later use, enabling a future Session to recall relevant past context.
_Avoid_: Recap, History entry

**Recall**:
The act of a Session retrieving relevant Session Summaries from prior Sessions to inform how it approaches the current Request.
_Avoid_: Memory lookup, Search

### Onboarding

**Setup**:
The one-time (or user-repeated) flow where the user tells Amy what filesystem access it may use and provides the credentials it needs to operate.
_Avoid_: Onboarding, Configuration, Init
