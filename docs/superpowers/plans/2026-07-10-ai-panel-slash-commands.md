# AI Panel Slash Commands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add slash commands to the Camel JBang TUI AI panel while preserving compact chat behavior for normal input.

**Architecture:** Add a package-private slash command registry beside `AiPanel`; the registry owns descriptors, aliases, placeholders, parsing, and help text. `AiPanel` keeps rendering, input editing, LLM lifecycle, and provider popup ownership, while a small callback context exposes close, full TUI exit, provider switching, model switching, and Camel CLI-backed execution. CLI-backed commands run through a TUI-safe adapter with captured output and cancellation.

**Tech Stack:** Java 17, JUnit 5, picocli already present in Camel JBang, Tamboui TUI widgets, existing Camel JBang command classes.

## Global Constraints

- Do not add new dependencies.
- Do not turn the AI panel into a full embedded shell.
- Do not implement inline `@file` expansion inside arbitrary `/send` body text.
- Do not persist slash command history or new command settings in this first pass.
- Keep the current compact input and conversation layout.
- The command registry is the single source of truth for command lookup, aliases, help text, and parameter placeholders.
- Errors render into the conversation as error entries, not through the TUI event loop.
- New test code must not introduce `Thread.sleep()`.
- Use imports instead of fully qualified class names in Java code.

---

## File Structure

- Create `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiSlashCommandRegistry.java`
  - Owns command descriptors, stable ordering, alias lookup, `/help` rendering, placeholder lookup, and `/send` argument parsing.
- Create `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiSlashCommandContext.java`
  - Package-private callback interface used by descriptors and `AiPanel`.
- Create `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiCliCommandExecutor.java`
  - TUI-safe adapter for `camel run`, `camel infra`, and `camel cmd send`.
- Modify `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanel.java`
  - Detect slash input, delegate to registry, render command output/errors, wire busy/cancellation, and render inline placeholders.
- Modify `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/CamelMonitor.java`
  - Provide full TUI exit callback and CLI executor context to `AiPanel`.
- Test `dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiSlashCommandRegistryTest.java`
  - Registry order, aliases, help text, placeholders, unknown command messages, and `/send` parsing.
- Test `dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiCliCommandExecutorTest.java`
  - Adapter command mapping, output capture, non-zero exit handling, and cancellation with fakes.
- Modify `dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanelTest.java`
  - Dispatch, local commands, busy behavior, callback wiring, and placeholder rendering.
- Modify `dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/CamelMonitorTest.java`
  - Verifies the AI panel can request full TUI exit through `CamelMonitor`.
- Modify `docs/user-manual/modules/ROOT/pages/camel-jbang-tui.adoc`
  - Document supported AI panel slash commands and `/send @file` semantics.

## Task 1: Slash Command Registry And Parser

**Files:**
- Create: `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiSlashCommandRegistry.java`
- Create: `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiSlashCommandContext.java`
- Test: `dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiSlashCommandRegistryTest.java`

**Interfaces:**
- Produces: `AiSlashCommandRegistry.defaults()`, `lookup(String)`, `parse(String)`, `helpText()`, `placeholderFor(String)`, `parseSend(String)`.
- Produces: `AiSlashCommandContext` callbacks used by registry executors and `AiPanel`.

- [ ] **Step 1: Write failing registry tests**

Add `AiSlashCommandRegistryTest` with these initial tests:

```java
@Test
void descriptorsKeepStableOrder() {
    AiSlashCommandRegistry registry = AiSlashCommandRegistry.defaults();

    assertEquals(List.of("help", "provider", "model", "clear", "close", "exit", "quit", "run", "infra", "send"),
            registry.descriptors().stream().map(AiSlashCommandRegistry.Descriptor::name).toList());
}

@Test
void lookupIsCaseInsensitiveAndResolvesAliases() {
    AiSlashCommandRegistry registry = AiSlashCommandRegistry.defaults();

    assertEquals("help", registry.lookup("HELP").orElseThrow().name());
    assertEquals("provider", registry.lookup("p").orElseThrow().name());
    assertEquals("model", registry.lookup("m").orElseThrow().name());
    assertEquals("quit", registry.lookup("q").orElseThrow().name());
}

@Test
void helpTextComesFromDescriptors() {
    String help = AiSlashCommandRegistry.defaults().helpText();

    assertTrue(help.contains("/run <camel run args>"));
    assertTrue(help.contains("/send <endpoint> <message text | @file>"));
}

@Test
void placeholderUsesRegistryDescriptor() {
    AiSlashCommandRegistry registry = AiSlashCommandRegistry.defaults();

    assertEquals("<files...> [--dev] [--port=8080] [...]", registry.placeholderFor("/run ").orElseThrow());
    assertEquals("<endpoint> <message text | @file>", registry.placeholderFor("/send ").orElseThrow());
    assertTrue(registry.placeholderFor("/run route.yaml").isEmpty());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui -Dtest=AiSlashCommandRegistryTest test
```

Expected: compile failure because `AiSlashCommandRegistry` does not exist.

- [ ] **Step 3: Add context and registry implementation**

Create `AiSlashCommandContext`:

```java
package org.apache.camel.dsl.jbang.core.commands.tui;

import java.util.List;
import java.util.concurrent.CompletableFuture;

interface AiSlashCommandContext {
    void closePanel();

    void requestExit();

    void openProviderSwitch();

    String currentModel();

    List<String> availableModels();

    void switchModel(String model);

    String selectedProcessName();

    CompletableFuture<AiCliCommandExecutor.Result> executeCli(AiCliCommandExecutor.Request request);
}
```

Create `AiSlashCommandRegistry` with these public package-private shapes:

```java
final class AiSlashCommandRegistry {
    record Descriptor(String name, List<String> aliases, String description, String placeholder,
            boolean asynchronous, Executor executor) {
        String usage() {
            return placeholder == null || placeholder.isBlank() ? "/" + name : "/" + name + " " + placeholder;
        }
    }

    record ParsedCommand(Descriptor descriptor, String rawName, String arguments) {
    }

    record SendCommand(String endpoint, String body, boolean fileBody) {
    }

    interface Executor {
        CommandResult execute(AiSlashCommandContext context, String arguments);
    }

    record CommandResult(String role, String text, AiCliCommandExecutor.Request cliRequest) {
        static CommandResult system(String text) {
            return new CommandResult("system", text, null);
        }

        static CommandResult error(String text) {
            return new CommandResult("error", text, null);
        }

        static CommandResult async(AiCliCommandExecutor.Request request) {
            return new CommandResult("system", "Running " + request.displayText(), request);
        }
    }
}
```

Register commands in the exact order from the test. Implement lookup with `Locale.ROOT`, and make `parse("/")` resolve to `/help`.

- [ ] **Step 4: Add `/send` parser tests**

Extend `AiSlashCommandRegistryTest`:

```java
@Test
void sendParserHandlesLiteralBody() {
    AiSlashCommandRegistry.SendCommand command = AiSlashCommandRegistry.parseSend("direct:foo hello world");

    assertEquals("direct:foo", command.endpoint());
    assertEquals("hello world", command.body());
    assertFalse(command.fileBody());
}

@Test
void sendParserHandlesWholeFileBody() {
    AiSlashCommandRegistry.SendCommand command = AiSlashCommandRegistry.parseSend("direct:foo @payload.json");

    assertEquals("direct:foo", command.endpoint());
    assertEquals("payload.json", command.body());
    assertTrue(command.fileBody());
}

@Test
void sendParserDoesNotExpandInlineFileReference() {
    AiSlashCommandRegistry.SendCommand command = AiSlashCommandRegistry.parseSend("direct:foo prefix @payload.json suffix");

    assertEquals("prefix @payload.json suffix", command.body());
    assertFalse(command.fileBody());
}

@Test
void sendParserRejectsMissingEndpointAndBody() {
    assertEquals("Missing endpoint. Usage: /send <endpoint> <message text | @file>",
            assertThrows(IllegalArgumentException.class, () -> AiSlashCommandRegistry.parseSend("")).getMessage());
    assertEquals("Missing body. Usage: /send <endpoint> <message text | @file>",
            assertThrows(IllegalArgumentException.class, () -> AiSlashCommandRegistry.parseSend("direct:foo")).getMessage());
}
```

- [ ] **Step 5: Implement `/send` parser**

Implement `parseSend(String arguments)` by trimming the argument string, splitting once on whitespace for endpoint, and treating the body as a file only when it is one token that starts with `@` and has at least one following character.

- [ ] **Step 6: Run registry tests**

Run:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui -Dtest=AiSlashCommandRegistryTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiSlashCommandContext.java \
        dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiSlashCommandRegistry.java \
        dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiSlashCommandRegistryTest.java
git commit -m "CAMEL-23978: Add AI panel slash command registry"
```

## Task 2: TUI-Safe CLI Command Executor

**Files:**
- Create: `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiCliCommandExecutor.java`
- Test: `dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiCliCommandExecutorTest.java`

**Interfaces:**
- Consumes: `AiSlashCommandRegistry.SendCommand`.
- Produces: `AiCliCommandExecutor.Request`, `Result`, `executeAsync(Request)`, `cancel()`.

- [ ] **Step 1: Write failing executor mapping tests**

Create tests with a fake invoker seam:

```java
@Test
void runRequestPreservesRawTail() {
    AiCliCommandExecutor.Request request = AiCliCommandExecutor.Request.run("route.yaml --dev");

    assertEquals(List.of("run", "route.yaml", "--dev"), request.argv());
    assertEquals("camel run route.yaml --dev", request.displayText());
}

@Test
void infraRequestPreservesRawTail() {
    AiCliCommandExecutor.Request request = AiCliCommandExecutor.Request.infra("run kafka");

    assertEquals(List.of("infra", "run", "kafka"), request.argv());
    assertEquals("camel infra run kafka", request.displayText());
}

@Test
void sendRequestMapsLiteralBodyToCmdSend() {
    AiCliCommandExecutor.Request request = AiCliCommandExecutor.Request.send("myApp",
            new AiSlashCommandRegistry.SendCommand("direct:foo", "hello world", false));

    assertEquals(List.of("cmd", "send", "myApp", "--endpoint=direct:foo", "--body=hello world"), request.argv());
}

@Test
void sendRequestMapsFileBodyToCmdSendFileOption() {
    AiCliCommandExecutor.Request request = AiCliCommandExecutor.Request.send(null,
            new AiSlashCommandRegistry.SendCommand("direct:foo", "/tmp/payload.json", true));

    assertEquals(List.of("cmd", "send", "--endpoint=direct:foo", "--body=file:/tmp/payload.json"), request.argv());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui -Dtest=AiCliCommandExecutorTest test
```

Expected: compile failure because `AiCliCommandExecutor` does not exist.

- [ ] **Step 3: Implement request and result types**

Create `AiCliCommandExecutor` with:

```java
final class AiCliCommandExecutor {
    record Request(List<String> argv, String displayText) {
        static Request run(String rawTail) {
            List<String> args = new ArrayList<>();
            args.add("run");
            args.addAll(splitRawTail(rawTail));
            return new Request(args, "camel " + String.join(" ", args));
        }

        static Request infra(String rawTail) {
            List<String> args = new ArrayList<>();
            args.add("infra");
            args.addAll(splitRawTail(rawTail));
            return new Request(args, "camel " + String.join(" ", args));
        }

        static Request send(String selectedProcessName, AiSlashCommandRegistry.SendCommand send) {
            List<String> args = new ArrayList<>();
            args.add("cmd");
            args.add("send");
            if (selectedProcessName != null && !selectedProcessName.isBlank()) {
                args.add(selectedProcessName);
            }
            args.add("--endpoint=" + send.endpoint());
            args.add("--body=" + (send.fileBody() ? "file:" + send.body() : send.body()));
            return new Request(args, "camel " + String.join(" ", args));
        }
    }

    record Result(String displayText, int exitCode, String output, long elapsedMs, boolean interrupted) {
    }
}
```

Implement `splitRawTail(String)` with picocli's existing argument splitter if available in this module; otherwise use the same minimal quote-aware helper already used by nearby TUI code if present. If neither exists, use `CommandLine.translateCommandline(rawTail)` from picocli and add a test for quoted values:

```java
assertEquals(List.of("run", "route.yaml", "--property=foo=hello world"),
        AiCliCommandExecutor.Request.run("route.yaml --property=\"foo=hello world\"").argv());
```

- [ ] **Step 4: Write output and cancellation tests with a fake invoker**

Add a package-private constructor that accepts an invoker:

```java
interface Invoker {
    int execute(List<String> argv, Printer printer) throws Exception;
}
```

Then test:

```java
@Test
void executorCapturesOutputAndExitCode() throws Exception {
    AiCliCommandExecutor executor = new AiCliCommandExecutor((argv, printer) -> {
        printer.println("created route");
        return 0;
    });

    AiCliCommandExecutor.Result result = executor.executeAsync(AiCliCommandExecutor.Request.run("route.yaml")).get();

    assertEquals(0, result.exitCode());
    assertEquals("created route\n", result.output());
    assertFalse(result.interrupted());
}

@Test
void executorReportsNonZeroExit() throws Exception {
    AiCliCommandExecutor executor = new AiCliCommandExecutor((argv, printer) -> {
        printer.println("bad args");
        return 2;
    });

    AiCliCommandExecutor.Result result = executor.executeAsync(AiCliCommandExecutor.Request.infra("nope")).get();

    assertEquals(2, result.exitCode());
    assertTrue(result.output().contains("bad args"));
}
```

- [ ] **Step 5: Implement async execution**

Use a single daemon worker per command and capture `Printer` output in a `StringBuilder`. The production invoker should:

1. Get `CamelJBangMain main = (CamelJBangMain) CamelJBangMain.getCommandLine().getCommand()`.
2. Save `Printer originalPrinter = main.getOut()`.
3. Set a capture printer with `main.setOut(capturePrinter)`.
4. Set `EnvironmentHelper.setSelectedProcess(selectedProcessName)` only when a selected process exists.
5. Execute `CamelJBangMain.getCommandLine().execute(request.argv().toArray(String[]::new))`.
6. Restore printer and selected process in `finally`.

Do not call `CamelJBangMain.quit()`. If a command path tries to quit, the adapter must surface it as a failed result instead of letting the TUI exit.

- [ ] **Step 6: Add cancellation test and implementation**

Test:

```java
@Test
void cancelInterruptsRunningCommand() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    AiCliCommandExecutor executor = new AiCliCommandExecutor((argv, printer) -> {
        started.countDown();
        while (!Thread.currentThread().isInterrupted()) {
            Thread.onSpinWait();
        }
        return 130;
    });

    CompletableFuture<AiCliCommandExecutor.Result> future = executor.executeAsync(AiCliCommandExecutor.Request.run("route.yaml"));
    assertTrue(started.await(5, TimeUnit.SECONDS));
    executor.cancel();

    AiCliCommandExecutor.Result result = future.get(35, TimeUnit.SECONDS);
    assertTrue(result.interrupted());
}
```

Implementation: `cancel()` interrupts the active command thread and waits up to 30 seconds. If the thread does not stop, return a result with `interrupted=true`, `exitCode=130`, and output containing `Command cancellation timed out`.

- [ ] **Step 7: Run executor tests**

Run:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui -Dtest=AiCliCommandExecutorTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiCliCommandExecutor.java \
        dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiCliCommandExecutorTest.java
git commit -m "CAMEL-23978: Add TUI AI CLI command executor"
```

## Task 3: AiPanel Local Slash Commands

**Files:**
- Modify: `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanel.java`
- Modify: `dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanelTest.java`

**Interfaces:**
- Consumes: `AiSlashCommandRegistry.defaults()` and `AiSlashCommandContext`.
- Produces: `AiPanel.setSlashCommandContextForTesting(AiSlashCommandContext)` and `AiPanel.slashCommandRegistryForTesting()`.

- [ ] **Step 1: Write failing dispatch tests**

Add tests:

```java
@Test
void normalTextStillGoesToLlm() {
    AiPanel panel = new AiPanel();
    RecordingLlmClient client = new RecordingLlmClient("ok");
    panel.setClientForTesting(client);
    panel.open();

    type(panel, "what routes are running?");
    panel.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.NONE));

    assertTrue(panel.conversationForTesting().stream().anyMatch(entry -> "user".equals(entry.role())));
    assertEquals("what routes are running?", client.lastQuestion());
}

@Test
void slashInputDoesNotGoToLlm() {
    AiPanel panel = new AiPanel();
    RecordingLlmClient client = new RecordingLlmClient("ok");
    panel.setClientForTesting(client);
    panel.open();

    type(panel, "/help");
    panel.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.NONE));

    assertNull(client.lastQuestion());
    assertTrue(panel.conversationForTesting().stream()
            .anyMatch(entry -> "system".equals(entry.role()) && entry.text().contains("/run <camel run args>")));
}
```

Add helper:

```java
private static void type(AiPanel panel, String text) {
    for (char ch : text.toCharArray()) {
        panel.handleKeyEvent(KeyEvent.ofChar(ch));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui -Dtest=AiPanelTest test
```

Expected: `/help` is submitted as an LLM question.

- [ ] **Step 3: Add registry and slash dispatch to `AiPanel`**

Add fields:

```java
private final AiSlashCommandRegistry slashCommands = AiSlashCommandRegistry.defaults();
private AiSlashCommandContext slashCommandContext = new PanelSlashCommandContext();
private final AiCliCommandExecutor cliCommandExecutor = new AiCliCommandExecutor();
```

Rename `submitQuestion()` to `submitInput()` and route:

```java
String input = inputBuffer.toString().trim();
inputBuffer.setLength(0);
cursorPos = 0;
scrollOffset = 0;
if (input.startsWith("/")) {
    executeSlashCommand(input);
} else {
    submitQuestion(input);
}
```

Move the existing LLM body into `submitQuestion(String question)`.

Implement:

```java
private void executeSlashCommand(String input) {
    AiSlashCommandRegistry.CommandResult result = slashCommands.execute(input, slashCommandContext);
    if (result.text() != null && !result.text().isBlank()) {
        conversation.add(new ConversationEntry(result.role(), result.text()));
    }
}
```

- [ ] **Step 4: Write and implement local command callback tests**

Add a fake context and tests for `/clear`, `/close`, `/exit`, `/quit`, `/provider`, and `/model`:

```java
@Test
void clearResetsConversationAndUsageButKeepsProvider() {
    AiPanel panel = new AiPanel();
    panel.setClientForTesting(LlmClient.create());
    panel.open();
    type(panel, "/help");
    panel.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.NONE));

    type(panel, "/clear");
    panel.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.NONE));

    assertTrue(panel.conversationForTesting().isEmpty());
    assertEquals(0, panel.sessionTotalTokensForTesting());
}

@Test
void closeCommandClosesPanel() {
    AiPanel panel = new AiPanel();
    panel.setClientForTesting(LlmClient.create());
    panel.open();

    type(panel, "/close");
    panel.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.NONE));

    assertFalse(panel.isOpen());
}

@Test
void exitAndQuitRequestFullTuiExit() {
    AiPanel panel = new AiPanel();
    FakeSlashContext context = new FakeSlashContext();
    panel.setSlashCommandContextForTesting(context);
    panel.open();

    type(panel, "/quit");
    panel.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.NONE));

    assertTrue(context.exitRequested);
}
```

Add a package-private `clearConversation()` method in `AiPanel` that clears `conversation`, `activityLog`, `inputBuffer`, `scrollOffset`, `usageHistory`, `statsScrollOffset`, and `sessionTotalTokens`. Do not reset `sessionProviderChoice`.

- [ ] **Step 5: Implement model command behavior**

For `/model` with no arguments, render:

```text
Current model: <model>
Available models:
- <model-1>
- <model-2>
```

If available model listing returns empty, render only `Current model: <model>`. With an argument, call `switchModel(model)` and render `Switched model to <model>`.

- [ ] **Step 6: Run panel local command tests**

Run:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui -Dtest=AiPanelTest,AiSlashCommandRegistryTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanel.java \
        dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanelTest.java
git commit -m "CAMEL-23978: Wire local AI panel slash commands"
```

## Task 4: Async CLI Slash Commands And Cancellation

**Files:**
- Modify: `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanel.java`
- Modify: `dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanelTest.java`

**Interfaces:**
- Consumes: `AiCliCommandExecutor.Request` and `AiCliCommandExecutor.Result`.
- Produces: shared busy handling for AI requests and CLI commands.

- [ ] **Step 1: Write failing CLI dispatch tests**

Add:

```java
@Test
void runCommandExecutesThroughContextAndRendersOutput() throws Exception {
    AiPanel panel = new AiPanel();
    FakeSlashContext context = new FakeSlashContext();
    context.cliResult = new AiCliCommandExecutor.Result("camel run route.yaml", 0, "Started\n", 12, false);
    panel.setSlashCommandContextForTesting(context);
    panel.open();

    type(panel, "/run route.yaml");
    panel.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.NONE));
    context.completeCli();

    assertEquals(List.of("run", "route.yaml"), context.cliRequest.argv());
    assertTrue(panel.conversationForTesting().stream()
            .anyMatch(entry -> "system".equals(entry.role()) && entry.text().contains("Started")));
}

@Test
void nonZeroCliExitRendersError() throws Exception {
    AiPanel panel = new AiPanel();
    FakeSlashContext context = new FakeSlashContext();
    context.cliResult = new AiCliCommandExecutor.Result("camel infra nope", 2, "Unknown infra\n", 9, false);
    panel.setSlashCommandContextForTesting(context);
    panel.open();

    type(panel, "/infra nope");
    panel.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.NONE));
    context.completeCli();

    assertTrue(panel.conversationForTesting().stream()
            .anyMatch(entry -> "error".equals(entry.role()) && entry.text().contains("exit code 2")));
}
```

- [ ] **Step 2: Implement CLI command result handling**

When `CommandResult.cliRequest()` is non-null:

1. Add a system entry with `Running <displayText>`.
2. Set the same busy flag used by AI requests, but set `thinkingVerb` to `Running command`.
3. Attach a completion callback to the returned future.
4. On success exit code `0`, append a system entry:

```text
<displayText> completed in <elapsedMs> ms

<captured output>
```

5. On non-zero exit, append an error entry:

```text
<displayText> exited with code <exitCode>

<captured output>
```

6. On exception, append `Failed to run <displayText>: <message>`.
7. Clear busy state only if the completed command is still the active command.

- [ ] **Step 3: Write cancellation test**

Add:

```java
@Test
void escapeCancelsRunningCliCommand() {
    AiPanel panel = new AiPanel();
    FakeSlashContext context = new FakeSlashContext();
    panel.setSlashCommandContextForTesting(context);
    panel.open();

    type(panel, "/run route.yaml --dev");
    panel.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.NONE));
    assertTrue(panel.isThinkingForTesting());

    panel.handleKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE, KeyModifiers.NONE));

    assertTrue(context.cancelRequested);
    assertTrue(panel.conversationForTesting().stream()
            .anyMatch(entry -> "system".equals(entry.role()) && entry.text().contains("cancelled")));
}
```

- [ ] **Step 4: Implement CLI cancellation**

Split `interruptThinking()` into:

```java
private void interruptBusyOperation() {
    if (activeCliCommand != null) {
        slashCommandContext.cancelCli();
        conversation.add(new ConversationEntry("system", "(command cancelled)"));
        thinking.set(false);
        activeCliCommand = null;
    } else {
        stopAgentThread();
        conversation.add(new ConversationEntry("system", "(cancelled)"));
    }
}
```

Add `void cancelCli()` to `AiSlashCommandContext`; the panel context delegates to `AiCliCommandExecutor.cancel()`.

- [ ] **Step 5: Run async command tests**

Run:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui -Dtest=AiPanelTest,AiCliCommandExecutorTest,AiSlashCommandRegistryTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanel.java \
        dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiSlashCommandContext.java \
        dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanelTest.java
git commit -m "CAMEL-23978: Run AI panel CLI slash commands asynchronously"
```

## Task 5: Inline Placeholder Rendering

**Files:**
- Modify: `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanel.java`
- Modify: `dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanelTest.java`

**Interfaces:**
- Consumes: `AiSlashCommandRegistry.placeholderFor(String input)`.
- Produces: one-row input rendering that appends dimmed placeholders only when parameters are empty.

- [ ] **Step 1: Write failing render tests**

Add:

```java
@Test
void commandPlaceholderRendersAfterTrailingSpace() {
    AiPanel panel = new AiPanel();
    panel.open();
    type(panel, "/send ");

    Rect area = new Rect(0, 0, 80, 6);
    Buffer buffer = Buffer.empty(area);
    panel.render(Frame.forTesting(buffer), area);

    String rendered = TuiTestHelper.bufferToString(buffer);
    assertTrue(rendered.contains("/send"));
    assertTrue(rendered.contains("<endpoint> <message text | @file>"));
}

@Test
void commandPlaceholderDisappearsWhenParametersStart() {
    AiPanel panel = new AiPanel();
    panel.open();
    type(panel, "/send direct:foo");

    Rect area = new Rect(0, 0, 80, 6);
    Buffer buffer = Buffer.empty(area);
    panel.render(Frame.forTesting(buffer), area);

    assertFalse(TuiTestHelper.bufferToString(buffer).contains("<endpoint> <message text | @file>"));
}
```

- [ ] **Step 2: Implement placeholder rendering**

In `renderInput`, after rendering the visible input text and cursor, calculate:

```java
Optional<String> placeholder = slashCommands.placeholderFor(text);
```

If present and the cursor is at the end of the input, append one dimmed span:

```java
spans.add(Span.styled(" " + placeholder.get(), Style.EMPTY.dim()));
```

Keep the same `maxWidth` windowing; the placeholder must not increase input height.

- [ ] **Step 3: Run placeholder tests**

Run:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui -Dtest=AiPanelTest,AiSlashCommandRegistryTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanel.java \
        dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/AiPanelTest.java
git commit -m "CAMEL-23978: Show AI slash command placeholders"
```

## Task 6: CamelMonitor Wiring, Documentation, And Verification

**Files:**
- Modify: `dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/CamelMonitor.java`
- Modify: `dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/CamelMonitorTest.java`
- Modify: `docs/user-manual/modules/ROOT/pages/camel-jbang-tui.adoc`

**Interfaces:**
- Consumes: `AiPanel.setContext(MonitorContext)` and new full-exit callback.
- Produces: `/exit` and `/quit` close the full TUI through `CamelMonitor`.

- [ ] **Step 1: Write failing CamelMonitor callback test**

Add a focused test in `CamelMonitorTest` using the existing test harness. The assertion should show that an AI panel exit request invokes the monitor runner quit path:

```java
@Test
void aiPanelQuitCommandRequestsTuiQuit() {
    CamelMonitor monitor = new CamelMonitor(CamelJBangMain.getCommandLine().getCommand(), getClass().getClassLoader());
    TestRunner runner = new TestRunner();
    monitor.setRunnerForTesting(runner);

    AiPanel panel = monitor.aiPanelForTesting();
    panel.open();
    type(panel, "/quit");
    panel.handleKeyEvent(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.NONE));

    assertTrue(runner.quitRequested());
}
```

If the current test harness uses a different fake runner shape, adapt the fake only enough to expose `quitRequested()`.

- [ ] **Step 2: Wire monitor callback**

In `CamelMonitor`, after constructing `aiPanel`, set a callback context or call a new package-private method:

```java
aiPanel.setExitCallbackForTestingOrRuntime(() -> tui.quit());
```

Prefer a package-private runtime setter over exposing public API. Keep `/close` panel-local and leave F8 behavior unchanged.

- [ ] **Step 3: Add documentation section**

In `docs/user-manual/modules/ROOT/pages/camel-jbang-tui.adoc`, add a compact subsection near the AI/MCP section:

```adoc
=== AI panel slash commands

When the AI panel is open, input that starts with `/` runs a local panel command instead of sending a question to the configured AI provider.

[cols="1,3",options="header"]
|===
| Command | Description

| `/help`
| Show the available slash commands.

| `/provider`
| Open the provider switcher.

| `/model [model-name]`
| Show the current model, or switch the session model.

| `/clear`
| Clear the AI conversation and usage counters without changing the provider or model.

| `/close`
| Close the AI panel.

| `/exit`, `/quit`
| Exit the TUI.

| `/run <camel run args>`
| Run `camel run` with the provided arguments.

| `/infra <camel infra args>`
| Run `camel infra` with the provided arguments.

| `/send <endpoint> <message text \| @file>`
| Send a message through `camel cmd send`. A body that is exactly one `@file` token is sent as `file:<path>`; inline `@file` text is sent literally.
|===
```

- [ ] **Step 4: Run focused verification**

Run:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui -Dtest=AiSlashCommandRegistryTest,AiCliCommandExecutorTest,AiPanelTest,AiProviderSwitchPopupTest,CamelMonitorTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Run formatting checks for touched files**

Run:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui formatter:format impsort:sort
git diff --check
```

Expected: formatter and impsort complete successfully; `git diff --check` prints no whitespace errors.

- [ ] **Step 6: Commit**

```bash
git add dsl/camel-jbang/camel-jbang-plugin-tui/src/main/java/org/apache/camel/dsl/jbang/core/commands/tui/CamelMonitor.java \
        dsl/camel-jbang/camel-jbang-plugin-tui/src/test/java/org/apache/camel/dsl/jbang/core/commands/tui/CamelMonitorTest.java \
        docs/user-manual/modules/ROOT/pages/camel-jbang-tui.adoc
git commit -m "CAMEL-23978: Document AI panel slash commands"
```

## Final Verification

- [ ] Run the focused TUI test slice:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui -Dtest=AiSlashCommandRegistryTest,AiCliCommandExecutorTest,AiPanelTest,AiProviderSwitchPopupTest,CamelMonitorTest test
```

- [ ] If any production changes touched command execution beyond the TUI module, run the affected core tests:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-core -Dtest=CamelJBangMainTest,CamelSendActionTest,InfraTest,RunTest test
```

- [ ] Check formatting and whitespace:

```bash
mvn -pl dsl/camel-jbang/camel-jbang-plugin-tui formatter:format impsort:sort
git diff --check
```

- [ ] Inspect the final diff:

```bash
git status --short
git diff --stat
git diff -- docs/superpowers/plans/2026-07-10-ai-panel-slash-commands.md
```

## Self-Review Notes

- Spec coverage: registry, local commands, CLI-backed `/run`, `/infra`, `/send`, `/send @file`, placeholders, busy state, cancellation, error rendering, tests, and docs are each mapped to tasks above.
- Placeholder scan: no task uses `TBD`, deferred implementation, or unnamed edge handling.
- Type consistency: `AiSlashCommandRegistry.CommandResult`, `AiSlashCommandContext`, and `AiCliCommandExecutor.Request/Result` are defined before any later task consumes them.
