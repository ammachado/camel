# AI Panel Slash Commands Design

## Context

The Camel JBang TUI AI panel currently treats every non-empty input as an LLM question. It already has session-only provider switching through `Ctrl+P`, usage stats through `Ctrl+U`, a compact one-row input, and a tight conversation layout. Slash commands should add local control and Camel CLI actions without expanding the panel into a separate shell.

The implementation should follow the command registry pattern already used in the TUI and AI tooling code. The registry must be the single source of truth for command lookup, aliases, help text, and parameter placeholders.

## Goals

- Add slash commands to the AI panel with an extensible command registry.
- Preserve normal AI chat behavior for inputs that do not start with `/`.
- Run `/run`, `/infra`, and `/send` through Camel CLI semantics.
- Keep panel-local commands fast and deterministic.
- Show command parameter placeholders inline before the user starts typing parameters.
- Keep the current compact input and conversation layout.

## Non-Goals

- Do not turn the AI panel into a full embedded shell.
- Do not implement inline `@file` expansion inside arbitrary `/send` body text.
- Do not persist slash command history or new command settings in this first pass.
- Do not add new dependencies.

## Command Registry

Add an `AiSlashCommandRegistry` in the TUI package. It registers command descriptors in a stable order and supports lookup by command name or alias.

Each descriptor should include:

- command name, without the leading slash
- aliases
- short description
- parameter placeholder
- whether the command runs asynchronously
- executor callback

The registry should be the source for:

- `/help` output
- input placeholder text
- command alias resolution
- unknown command validation

`AiPanel` should only decide whether an input is a slash command. If the trimmed input starts with `/`, it delegates to the registry. Otherwise, it submits the text as an LLM question.

## Command Set

Initial commands:

```text
/help
/provider
/model
/clear
/close
/exit
/quit
/run <camel run args>
/infra <camel infra args>
/send <endpoint> <message text | @file>
```

Command behavior:

- `/help`: render a compact list of commands from registry descriptors.
- `/provider`: open the existing provider switch popup, same as `Ctrl+P`.
- `/model`: with no arguments, show the current model and available model choices if they can be listed. With an argument, switch the session model to that value.
- `/clear`: clear conversation state, activity log if appropriate, input buffer, scroll state, and usage counters. It must not reset provider or model settings.
- `/close`: close the AI panel, same as `F8`.
- `/exit` and `/quit`: close the full TUI.
- `/run`: pass arguments directly to `camel run`.
- `/infra`: pass arguments directly to `camel infra`.
- `/send`: map to `camel cmd send`.

## Callbacks And Ownership

`AiPanel` owns input editing, conversation rendering, provider popup state, and LLM lifecycle. TUI-level actions must be exposed through callbacks instead of being hard-coded into the panel.

The slash command context should expose callbacks for:

- close the AI panel
- request full TUI exit
- open the provider switch popup
- switch the session model
- execute Camel CLI-backed commands

`CamelMonitor` should provide the full TUI exit callback. `/close` remains panel-local.

## CLI Command Execution

CLI-backed commands should use a TUI-safe adapter rather than feeding text into the embedded shell.

The adapter should:

1. Invoke Camel CLI command classes through picocli or a narrowly scoped equivalent for `run`, `infra`, and `cmd send`.
2. Capture printer output so command output can be added to the AI panel conversation.
3. Prevent normal `CamelJBangMain.quit()` behavior from calling `System.exit`.
4. Run in the background so the TUI draw loop is not blocked.
5. Return command display text, exit code, captured output, elapsed time, and interruption status.

Long-running `/run` behavior must be explicit. If `camel run` stays attached, the panel should show the command as running and allow `Esc` or `Ctrl+C` to attempt interruption. If it returns after startup or exits, the panel should render the captured result.

## `/send` Syntax

`/send` uses custom parsing:

```text
/send <endpoint> @payload.json
/send <endpoint> hello world
```

The first token after `/send` is the endpoint. The remainder is the body.

If the body is exactly one `@file` token, resolve the path and pass it to Camel CLI as a file body. This maps to the existing CLI behavior that accepts `--body=file:<path>`.

If there is no `@file` body reference, send the remainder as a literal string body.

Inline expansion is out of scope:

```text
/send direct:foo prefix @payload.json suffix
```

The example above must be treated as a literal string body, not as a file expansion.

When possible, `/send` should use the currently selected integration if the user does not provide a process target, matching the TUI context.

## Input Placeholder UX

The AI panel input remains one row.

When the input is empty, render the normal prompt. When the user types a command name plus a trailing space and no parameters, render a dimmed inline placeholder after the typed command.

Examples:

```text
❯ /run   <files...> [--dev] [--port=8080] [...]
❯ /infra <list|run|stop|restart|log|ps|get> [...]
❯ /send  <endpoint> <message text | @file>
❯ /model <model-name>
```

The placeholder comes from the command descriptor. It disappears when the user starts typing parameters.

## Parsing Rules

- The command name is the first token after `/`.
- Command lookup is case-insensitive.
- Aliases resolve through the registry.
- Empty slash input, such as `/`, behaves like `/help`.
- Unknown commands render `Unknown command: /name. Type /help for available commands.`
- CLI-backed commands should preserve the raw argument tail as much as possible, including quoted values.
- `/send` uses custom endpoint and body parsing because the body may contain spaces and supports whole-body `@file`.

## Busy State And Cancellation

Panel-local commands execute immediately when the panel is not busy.

CLI-backed commands run asynchronously and should use the same busy behavior as an in-flight AI request:

- show a status entry or busy indicator
- ignore normal input while busy
- allow `Esc` or `Ctrl+C` to attempt interruption
- add a system entry when a command is cancelled

`/provider` and `/model` should not interrupt a running AI or CLI command. If the panel is busy, they should be ignored or render a concise message that the command must wait until the current operation finishes.

## Error Handling

Errors should be rendered into the conversation as error entries, not thrown through the TUI event loop.

Expected error cases:

- unknown command
- missing required `/send` endpoint
- missing `/send` body
- file reference does not exist or is not a regular file
- CLI command exits non-zero
- CLI adapter cannot capture output or cannot prevent exit
- command cancellation times out

Error messages should include the command and a short actionable hint when possible.

## Testing

Add focused unit tests for:

- registry lookup, aliases, descriptor ordering, help text, and placeholders
- `AiPanel` dispatch, normal text still goes to LLM and slash input does not
- `/close`, `/exit`, `/quit`, `/clear`, `/provider`, and `/model` behavior through callbacks and fakes
- `/send` translation for literal body and whole-body `@file`
- unknown and malformed command messages
- CLI adapter output capture, non-zero exit handling, and cancellation using fakes

Keep existing focused tests passing:

```text
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui -Dtest=AiPanelTest,AiProviderSwitchPopupTest test
```

If CLI adapter work touches core command execution, add or run focused core tests for the affected helper classes.

## Documentation

Update the TUI AI documentation to mention slash commands, supported commands, and `/send @file` semantics.

If command metadata or generated JBang docs are affected by any core command description changes, regenerate and commit the generated files. The current design should avoid generated metadata changes unless the implementation changes CLI command annotations.
