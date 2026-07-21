# Camel CLI Launcher: Self-Update via Script Delegation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `camel self-update` and extend `camel doctor`, per
`docs/superpowers/specs/2026-07-22-launcher-self-update-script-delegation.md` — the alternative
design that has `camel self-update` shell out to the already-existing, already-tested
`install.sh`/`install.ps1` for the actual fetch/verify/extract/activate work, instead of
re-implementing that pipeline a third time in Java.

## Relationship to the baseline plan

This document **replaces only Tasks 3–8** of
`docs/superpowers/plans/2026-07-22-launcher-self-update.md` (the "baseline plan", built against
`docs/superpowers/specs/2026-07-21-launcher-self-update-design.md`, the "baseline design"). It does
not repeat or modify:

- **Task 1** (`InstallDetector`) and **Task 2** (extend `camel doctor`) — apply these two tasks
  verbatim from the baseline plan (lines 112–683) before starting Task 3 below. Nothing about
  install-location detection or conflict reporting changes with this alternative; only *how
  `self-update` performs the update* changes.
- **Tasks 9–15** (Homebrew `apache-camel` rename/distribution, lines 2415–3478 of the baseline
  plan) — entirely unrelated subsystem, apply as-is, after this document's Task 9, continuing that
  plan's own task numbering (treat them as this document's Tasks 10–16).

Execution order: baseline plan Tasks 1–2, then this document's Tasks 3–9, then baseline plan Tasks
9–15.

**Architecture:** `SelfUpdateCommand` still does its own manifest fetch/parse and version compare
in Java (`ManifestFetcher`, carried over from the baseline almost unchanged) — that pre-check is
the one thing neither script has (both download unconditionally every run), and it's what makes
`--check` and "already on the latest version, do nothing" work without a network round-trip for the
archive. Once an update is actually due, `SelfUpdateCommand` downloads and checksum-verifies the
platform-appropriate installer script itself (`InstallScriptFetcher`, new) against a small new
`install.sha256` companion file published alongside `install.sh`/`install.ps1`, then runs it via
`ProcessBuilder`, **always pinning the exact resolved version** (`--version`/`-Version`) so the
script can never independently re-resolve a different "latest" than the one `SelfUpdateCommand`
already decided on. The script's own combined stdout/stderr is captured and written through this
command's `Printer`, and its exit code becomes `camel self-update`'s exit code. No archive
download, checksum verification, entry validation, extraction, smoke-test, or atomic activation
logic exists in Java anymore — `install.sh`/`install.ps1` already do all of that safely today, and
this plan does not touch either script.

**Tech Stack:** Java 17, picocli, `java.net.http.HttpClient` (manifest + install-script + checksum
fetches only — no archive download), `java.security.MessageDigest` (SHA-256 of the downloaded
script), `ProcessBuilder` (invoking the downloaded, verified script), JUnit 5 + AssertJ + the
existing `WebsiteInstallerFixture` test fixture. **No `commons-compress` scope change** — unlike
the baseline plan's Task 4, this alternative never parses a zip archive in Java, so
`commons-compress` stays exactly where it already is (`test` scope, used only by
`WebsiteInstallerFixture`'s archive builders). This plan introduces **zero new dependencies and
zero dependency-scope changes**.

## Global Constraints

Same as the baseline plan's Global Constraints section (Java 17 bytecode target, AssertJ for new
test methods, no `Thread.sleep()`, no `public` on new test classes/methods, no FQCNs, no new
dependencies without approval). One addition specific to this alternative:

- `SelfUpdateCommand` spawns child processes (`sh`/`powershell`). New test code that exercises this
  path uses `WebsiteInstallerFixture`'s existing `run(List<String>, Map<String,String>)` /
  `environment(Path)` helpers for environment isolation (`pb.environment().clear()` +
  explicit `PATH`/`HOME`/`CAMEL_INSTALL_*` overrides) — the same pattern `WebsiteInstallTest`
  already uses to spawn `install.sh`/`install.ps1` in isolation from the real machine's `$HOME`.
  `SelfUpdateCommand` itself gets an equivalent, narrower seam (Task 6) rather than reinventing
  process isolation from scratch.

### Decisions carried forward from the spec's open items

`2026-07-22-launcher-self-update-script-delegation.md` flagged three points as open; this plan
resolves all three concretely (code must pick one, a spec doesn't have to):

1. **Passthrough vs. capture.** `ProcessBuilder.inheritIO()` would bypass this command's `Printer`
   entirely (it writes straight to the real OS file descriptors), which breaks the existing
   `StringPrinter`-based test pattern every other command in this codebase uses. Task 6 instead
   redirects the child's stderr into its stdout, reads the combined output, and writes it through
   `printer().print(...)` — mirrors `install.sh`'s own `verify_staged()` pattern in the baseline
   plan (`redirectErrorStream(true)` + `readAllBytes()`), and keeps `camel self-update` testable
   the same way every other command in this module already is.
2. **Where `install.sha256` is generated.** `WebsiteManifestGenerator.java` (Task 4) — the same
   JDK-only tool that already computes `tar_sha256`/`zip_sha256` at release time, run at the same
   point `camel-package.sh` already copies `install.sh`/`install.ps1` to the website. Not a new
   script.
3. **Interpreter invocation.** `/bin/sh <script> --version X.Y.Z` (POSIX, absolute path) and
   `powershell -NoProfile -ExecutionPolicy Bypass -File <script> -Version X.Y.Z` (Windows) — both
   forms already appear verbatim in this module's own `WebsiteInstallTest.java`
   (`ProcessBuilder("/bin/sh", ...)` at line 144; `List.of("powershell", "-NoProfile",
   "-ExecutionPolicy", "Bypass", "-File", SCRIPT.toString(), "-Version", ...)` at lines 842/855),
   so Task 6 matches an already-established in-repo convention rather than inventing a new one.

## File structure

```
# This document's own files (Tasks 3-9)
dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/
  ManifestFetcher.java                      [NEW]  fetch/parse/compare only - no downloadArchive()
  SelfUpdateException.java                  [NEW]  unchanged from baseline Task 3
  InstallScriptFetcher.java                 [NEW]  fetch+verify install.sh/install.ps1
  SelfUpdateCommand.java                    [NEW]  refuse guard, version compare, delegate via ProcessBuilder
  SelfUpdatePlugin.java                     [NEW]  unchanged from baseline Task 5
  UpdateChecker.java                        [NEW]  unchanged from baseline Task 6, carried forward verbatim
dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/
  ManifestFetcherTest.java                  [NEW]  unchanged from baseline Task 3
  InstallScriptFetcherTest.java             [NEW]
  UpdateCheckerTest.java                    [NEW]  unchanged from baseline Task 6, carried forward verbatim
dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/
  SelfUpdateIntegrationTest.java            [NEW]  reuses WebsiteInstallerFixture; runs the REAL install.sh
dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/
  CamelLauncherMain.java                    [MODIFY]  register SelfUpdatePlugin, preExecute() -> UpdateChecker
dsl/camel-jbang/camel-launcher/src/jreleaser/java/
  WebsiteManifestGenerator.java             [MODIFY]  writes install.sha256 alongside tar/zip manifests
dsl/camel-jbang/camel-launcher/src/jreleaser/bin/
  camel-package.sh                          [MODIFY]  pass --install-sh/--install-ps1 to the generator
dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/
  WebsiteManifestGeneratorTest.java         [MODIFY]  2 new test methods
docs/user-manual/modules/ROOT/pages/
  camel-jbang-launcher-install.adoc         [NEW]     mentions delegation + install.sha256
  camel-jbang-launcher.adoc                 [MODIFY]  link to the new page
  camel-4x-upgrade-guide-4_22.adoc          [MODIFY]  new subsection

# NOT touched by this document (unlike the baseline plan's Task 4):
#   dsl/camel-jbang/camel-launcher/pom.xml                — commons-compress stays test-scope
#   .../selfupdate/ZipArchiveValidator.java + its test     — does not exist under this alternative

# Applied from the baseline plan, unchanged (see "Relationship to the baseline plan" above):
#   dsl/camel-jbang/camel-jbang-core/.../common/InstallDetector.java (+ test)          [Task 1]
#   dsl/camel-jbang/camel-jbang-core/.../commands/Doctor.java (+ test)                 [Task 2]
#   Homebrew Distribution: jreleaser.yml, camel-package.sh (rename bits), formula.rb.tpl,
#     camel-validate.sh, camel-publish.sh, lib/*, scoop/chocolatey templates            [Tasks 9-15]
```

**Interfaces summary** (exact names/types every task after Task 3 depends on):

- `ManifestFetcher.Manifest` — record: `(String version, String tarSha256, String zipSha256)`
  (fields `tarSha256`/`zipSha256` are parsed but no longer consumed by any Java code — kept because
  the manifest format itself is unchanged and both scripts still need both hashes internally).
- `ManifestFetcher.fromEnvironment()` / `new ManifestFetcher(String manifestBaseUrl, String
  mavenBaseUrl)` / `.fetchLatest()` / `.fetch(String version)` — same as baseline Task 3, minus
  `.downloadArchive(...)`.
- `InstallScriptFetcher.fromEnvironment()` / `new InstallScriptFetcher(String installBaseUrl)` /
  `.fetch(Path stagingDir)` — returns the verified, on-disk `Path` to the downloaded script.
- `SelfUpdateException` — unchanged from baseline Task 3.
- `UpdateChecker.maybeNotify(String[] args)` — unchanged from baseline Task 6.

---

### Task 3: `ManifestFetcher` (fetch/parse/compare only)

**Files:**
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ManifestFetcher.java`
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdateException.java`
- Test: `dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ManifestFetcherTest.java`

**Interfaces:**
- Produces: `ManifestFetcher.Manifest`, `.fromEnvironment()`, `new ManifestFetcher(String, String)`,
  `.fetchLatest()`, `.fetch(String)`. Consumed by Task 5 (`SelfUpdateCommand`) and Task 7
  (`UpdateChecker`, only `.fetchLatest()`).
- Consumes: nothing from earlier tasks.

Identical scope to the baseline plan's Task 3 **except**: no `.downloadArchive(String, Path)`
method, since the archive is never downloaded in Java under this alternative — the delegated
script downloads it. The parser itself is unchanged (mirrors `install.sh`'s `parse_manifest`
exactly: 4 required `key=value` lines, no duplicates/unknowns, `format=1`, `X.Y.Z` version,
64-char lowercase-hex checksums, CRLF-tolerant).

- [ ] **Step 1: Write the failing test**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.launcher.selfupdate;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestFetcherTest {

    private static final String HASH = "a".repeat(64);

    static Stream<Arguments> invalidManifests() {
        return Stream.of(
                Arguments.of("missing-key", "format=1\nversion=1.0.0\ntar_sha256=" + HASH + "\n"),
                Arguments.of("duplicate-key",
                        "format=1\nformat=1\nversion=1.0.0\ntar_sha256=" + HASH + "\nzip_sha256=" + HASH + "\n"),
                Arguments.of("blank-line",
                        "format=1\n\nversion=1.0.0\ntar_sha256=" + HASH + "\nzip_sha256=" + HASH + "\n"),
                Arguments.of("unknown-key",
                        "format=1\nversion=1.0.0\ntar_sha256=" + HASH + "\nzip_sha256=" + HASH + "\nextra=1\n"),
                Arguments.of("bad-format", "format=2\nversion=1.0.0\ntar_sha256=" + HASH + "\nzip_sha256=" + HASH + "\n"),
                Arguments.of("bad-version", "format=1\nversion=1.0\ntar_sha256=" + HASH + "\nzip_sha256=" + HASH + "\n"),
                Arguments.of("bad-tar-hash", "format=1\nversion=1.0.0\ntar_sha256=not-hex\nzip_sha256=" + HASH + "\n"),
                Arguments.of("bad-zip-hash", "format=1\nversion=1.0.0\ntar_sha256=" + HASH + "\nzip_sha256=short\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidManifests")
    void rejectsInvalidManifest(String name, String content) {
        assertThatThrownBy(() -> ManifestFetcher.parse(content.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SelfUpdateException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidManifests")
    void rejectsCrlfVariantOfInvalidManifest(String name, String content) {
        String crlf = content.replace("\n", "\r\n");
        assertThatThrownBy(() -> ManifestFetcher.parse(crlf.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SelfUpdateException.class);
    }

    @Test
    void parsesValidManifest() {
        String content = "format=1\nversion=4.22.0\ntar_sha256=" + HASH + "\nzip_sha256=" + HASH + "\n";

        ManifestFetcher.Manifest manifest = ManifestFetcher.parse(content.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.version()).isEqualTo("4.22.0");
        assertThat(manifest.tarSha256()).isEqualTo(HASH);
        assertThat(manifest.zipSha256()).isEqualTo(HASH);
    }

    @Test
    void parsesValidManifestWithCrlf() {
        String content = ("format=1\nversion=4.22.0\ntar_sha256=" + HASH + "\nzip_sha256=" + HASH + "\n").replace("\n", "\r\n");

        ManifestFetcher.Manifest manifest = ManifestFetcher.parse(content.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.version()).isEqualTo("4.22.0");
    }

    @Test
    void fromEnvironmentUsesProductionDefaultsWhenUnset() {
        ManifestFetcher fetcher = ManifestFetcher.fromEnvironment();

        assertThat(fetcher.manifestBaseUrl()).isEqualTo("https://camel.apache.org/camel-cli/releases");
        assertThat(fetcher.mavenBaseUrl()).isEqualTo("https://repo1.maven.org/maven2/org/apache/camel/camel-launcher");
    }
}
```

Note: `fetcher.mavenBaseUrl()`/`manifestBaseUrl()` accessors stay even though `mavenBaseUrl` is no
longer used to download an archive in Java — the field/accessor is kept because `fromEnvironment()`
still needs a place to read `CAMEL_SELF_UPDATE_MAVEN_BASE_URL` from for parity with the baseline's
existing test-seam naming, and dropping it would be a needless behavior change to an env var name
that may already be documented/expected. (If a reviewer prefers deleting the now-unused Maven base
URL entirely, that's a valid simplification to raise — flagged here rather than decided
unilaterally, since it touches a public env-var contract name.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=ManifestFetcherTest -Dci.env.name=local`
Expected: FAIL — compilation error, `ManifestFetcher`/`SelfUpdateException` do not exist.

- [ ] **Step 3: Write `SelfUpdateException`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.launcher.selfupdate;

/**
 * Thrown for any self-update failure that should be reported as a plain, non-stack-trace message and a non-zero
 * exit code — mirrors {@code install.sh}'s {@code fail()} convention.
 */
public class SelfUpdateException extends RuntimeException {

    public SelfUpdateException(String message) {
        super(message);
    }

    public SelfUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Write `ManifestFetcher`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.launcher.selfupdate;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fetches and parses the website-installer manifest ({@code latest.properties}/{@code X.Y.Z.properties}). Mirrors
 * {@code install.sh}'s {@code parse_manifest} exactly, in Java. Used only for the pre-flight version compare that
 * neither {@code install.sh} nor {@code install.ps1} perform on their own — the actual archive download, checksum
 * verification, and extraction happen inside whichever of those two scripts {@code SelfUpdateCommand} delegates to.
 */
public final class ManifestFetcher {

    private static final String DEFAULT_MANIFEST_BASE_URL = "https://camel.apache.org/camel-cli/releases";
    private static final String DEFAULT_MAVEN_BASE_URL = "https://repo1.maven.org/maven2/org/apache/camel/camel-launcher";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    public record Manifest(String version, String tarSha256, String zipSha256) {
    }

    private final String manifestBaseUrl;
    private final String mavenBaseUrl;

    public ManifestFetcher(String manifestBaseUrl, String mavenBaseUrl) {
        this.manifestBaseUrl = manifestBaseUrl;
        this.mavenBaseUrl = mavenBaseUrl;
    }

    /**
     * Production wiring: {@code CAMEL_SELF_UPDATE_MANIFEST_BASE_URL}/{@code CAMEL_SELF_UPDATE_MAVEN_BASE_URL}
     * override the defaults, mirroring {@code install.sh}'s {@code CAMEL_INSTALL_*} test seams under a distinct
     * name so overriding one never accidentally affects the other. Production installs never set either.
     */
    public static ManifestFetcher fromEnvironment() {
        String manifestBaseUrl = System.getenv().getOrDefault("CAMEL_SELF_UPDATE_MANIFEST_BASE_URL", DEFAULT_MANIFEST_BASE_URL);
        String mavenBaseUrl = System.getenv().getOrDefault("CAMEL_SELF_UPDATE_MAVEN_BASE_URL", DEFAULT_MAVEN_BASE_URL);
        return new ManifestFetcher(manifestBaseUrl, mavenBaseUrl);
    }

    public String manifestBaseUrl() {
        return manifestBaseUrl;
    }

    public String mavenBaseUrl() {
        return mavenBaseUrl;
    }

    public Manifest fetchLatest() {
        return fetchManifest(manifestBaseUrl + "/latest.properties");
    }

    public Manifest fetch(String version) {
        return fetchManifest(manifestBaseUrl + "/" + version + ".properties");
    }

    private Manifest fetchManifest(String url) {
        return parse(get(url));
    }

    private byte[] get(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().proxy(ProxySelector.getDefault()).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(CONNECT_TIMEOUT).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new SelfUpdateException("failed to download manifest from " + url + " (HTTP " + response.statusCode() + ")");
            }
            return response.body();
        } catch (SelfUpdateException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new SelfUpdateException("failed to download manifest from " + url, e);
        }
    }

    // Package-visible for direct unit testing without a network round-trip. Mirrors install.sh's parse_manifest:
    // exactly 4 non-blank key=value lines, no duplicate/unknown keys, format=1, X.Y.Z version, 64-char lowercase
    // hex hashes. CRLF-tolerant (a trailing '\r' is stripped per line) because install.ps1 may have produced or
    // re-served the same manifest.
    static Manifest parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        String[] rawLines = text.split("\n", -1);
        int lineCount = rawLines.length > 0 && rawLines[rawLines.length - 1].isEmpty() ? rawLines.length - 1 : rawLines.length;
        if (lineCount != 4) {
            throw new SelfUpdateException("manifest must contain exactly four lines");
        }

        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < lineCount; i++) {
            String line = rawLines[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || eq == line.length() - 1) {
                throw new SelfUpdateException("manifest contains a blank line");
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (!Set.of("format", "version", "tar_sha256", "zip_sha256").contains(key)) {
                throw new SelfUpdateException("manifest has unknown key: " + key);
            }
            if (values.containsKey(key)) {
                throw new SelfUpdateException("manifest has duplicate key: " + key);
            }
            values.put(key, value);
        }

        for (String required : List.of("format", "version", "tar_sha256", "zip_sha256")) {
            if (!values.containsKey(required)) {
                throw new SelfUpdateException("manifest is missing a required key");
            }
        }

        if (!"1".equals(values.get("format"))) {
            throw new SelfUpdateException("unsupported manifest format: " + values.get("format"));
        }
        String version = values.get("version");
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new SelfUpdateException("manifest version is not a valid X.Y.Z value");
        }
        validateSha256(values.get("tar_sha256"), "manifest tar_sha256");
        validateSha256(values.get("zip_sha256"), "manifest zip_sha256");

        return new Manifest(version, values.get("tar_sha256"), values.get("zip_sha256"));
    }

    private static void validateSha256(String value, String label) {
        if (!value.matches("[0-9a-f]{64}")) {
            throw new SelfUpdateException(label + " is not a 64-character lowercase hex value");
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=ManifestFetcherTest -Dci.env.name=local`
Expected: PASS.

- [ ] **Step 6: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local
git add dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ManifestFetcher.java \
        dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdateException.java \
        dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ManifestFetcherTest.java
git commit -m "CAMEL-23703: Add ManifestFetcher for camel self-update version compare"
```

---

### Task 4: Publish `install.sha256` alongside `install.sh`/`install.ps1`

**Files:**
- Modify: `dsl/camel-jbang/camel-launcher/src/jreleaser/java/WebsiteManifestGenerator.java`
- Modify: `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh`
- Modify: `dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/WebsiteManifestGeneratorTest.java`

**Interfaces:**
- Produces: `<website-root>/install.sha256` — two lines, `install_sh_sha256=<64-hex>` and
  `install_ps1_sha256=<64-hex>`, written atomically the same way `latest.properties` already is.
  Consumed by Task 5 (`InstallScriptFetcher`).
- Consumes: two new required CLI options on `WebsiteManifestGenerator`, `--install-sh <path>` /
  `--install-ps1 <path>`.

Confirmed directly against `camel-package.sh` before writing this task: `install.sh`/`install.ps1`
are copied to `$WEBSITE_DIR/install.sh` / `$WEBSITE_DIR/install.ps1` (site root — the same place
`camel-jbang-launcher-install.adoc`'s `curl .../install.sh | sh` one-liner already points at), and
`WebsiteManifestGenerator` is invoked with `--output "$WEBSITE_DIR/camel-cli"` (i.e. `--output`'s
**parent** is the website root). `install.sha256` is written to that same parent directory, so it
publishes as a sibling of `install.sh`/`install.ps1`, not under `camel-cli/`.

Not a change to the existing `latest.properties`/`X.Y.Z.properties` format or its `parse_manifest`
contract in either script — `install.sha256` is a wholly new, separate file.

- [ ] **Step 1: Write the failing tests**

Add these two methods to `WebsiteManifestGeneratorTest.java` (new test methods use AssertJ per this
repo's convention; existing methods in this file, which use JUnit assertions, are left untouched):

```java
    @Test
    void writesInstallChecksums(@TempDir Path temp) throws Exception {
        Path tar = writeFixture(temp, "camel-launcher-4.22.0-bin.tar.gz", "tar-content");
        Path zip = writeFixture(temp, "camel-launcher-4.22.0-bin.zip", "zip-content");
        Path installSh = writeFixture(temp, "install.sh", "#!/bin/sh\necho hi\n");
        Path installPs1 = writeFixture(temp, "install.ps1", "Write-Host 'hi'\n");
        Path websiteRoot = temp.resolve("website");
        Path output = websiteRoot.resolve("camel-cli");
        Files.createDirectories(output);

        Result r = run("--version", "4.22.0", "--tar", tar.toString(), "--zip", zip.toString(),
                "--install-sh", installSh.toString(), "--install-ps1", installPs1.toString(),
                "--output", output.toString(), "--latest", "true");

        assertThat(r.exit).isZero();
        Path checksumFile = websiteRoot.resolve("install.sha256");
        assertThat(checksumFile).exists();
        List<String> lines = Files.readAllLines(checksumFile);
        assertThat(lines).containsExactly(
                "install_sh_sha256=" + sha256Hex(installSh),
                "install_ps1_sha256=" + sha256Hex(installPs1));
    }

    @Test
    void failsWhenInstallShMissing(@TempDir Path temp) throws Exception {
        Path tar = writeFixture(temp, "camel-launcher-4.22.0-bin.tar.gz", "tar-content");
        Path zip = writeFixture(temp, "camel-launcher-4.22.0-bin.zip", "zip-content");
        Path output = temp.resolve("website/camel-cli");
        Files.createDirectories(output);

        Result r = run("--version", "4.22.0", "--tar", tar.toString(), "--zip", zip.toString(),
                "--install-sh", temp.resolve("missing-install.sh").toString(),
                "--install-ps1", temp.resolve("missing-install.ps1").toString(),
                "--output", output.toString(), "--latest", "true");

        assertThat(r.exit).isEqualTo(2);
        assertThat(r.stderr).contains("install.sh not found");
    }
```

Add the AssertJ static import next to the existing JUnit ones:

```java
import static org.assertj.core.api.Assertions.assertThat;
```

(`writeFixture`, `sha256Hex`, `Result`, and `run(String...)` are the existing private helpers
already defined in this file — reused unchanged, per its existing convention of running the
generator as a real `java WebsiteManifestGenerator.java <args>` subprocess via JEP 330 single-file
source launch.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=WebsiteManifestGeneratorTest -Dci.env.name=local`
Expected: FAIL — `writesInstallChecksums` fails because `--install-sh`/`--install-ps1` are rejected
as unknown options (`REQUIRED_OPTIONS` doesn't contain them yet); `failsWhenInstallShMissing` fails
for the same reason before it can exercise the "not found" branch.

- [ ] **Step 3: Extend `WebsiteManifestGenerator`**

In `WebsiteManifestGenerator.java`, change:

```java
    private static final Set<String> REQUIRED_OPTIONS
            = new LinkedHashSet<>(List.of("--version", "--tar", "--zip", "--output", "--latest"));
```

to:

```java
    private static final Set<String> REQUIRED_OPTIONS = new LinkedHashSet<>(
            List.of("--version", "--tar", "--zip", "--install-sh", "--install-ps1", "--output", "--latest"));
```

In `run(String[] args)`, after the existing `tar`/`zip` existence checks and before `String
latestValue = ...`, add:

```java
        Path installSh = Paths.get(options.get("--install-sh"));
        Path installPs1 = Paths.get(options.get("--install-ps1"));
        if (!Files.isRegularFile(installSh)) {
            throw new UsageException("install.sh not found: " + installSh);
        }
        if (!Files.isRegularFile(installPs1)) {
            throw new UsageException("install.ps1 not found: " + installPs1);
        }
```

After the existing `if (latest) { writeLatestManifest(...); }` block at the end of `run(...)`, add:

```java
        writeInstallChecksums(output.getParent(), installSh, installPs1);
```

Add the new method, placed after `writeLatestManifest`:

```java
    // install.sh/install.ps1 aren't versioned the way the release archive is - they always describe
    // "how to install whatever is currently latest," so unlike writeVersionManifest/writeLatestManifest
    // there's no immutability or monotonic-version invariant to enforce here: this file is simply
    // overwritten every release with the checksums of whatever install.sh/install.ps1 currently are.
    private static void writeInstallChecksums(Path websiteRoot, Path installSh, Path installPs1) throws IOException {
        String content = "install_sh_sha256=" + sha256Hex(installSh) + "\n"
                          + "install_ps1_sha256=" + sha256Hex(installPs1) + "\n";
        atomicWrite(websiteRoot.resolve("install.sha256"), content.getBytes(StandardCharsets.UTF_8));
    }
```

- [ ] **Step 4: Wire the new options into `camel-package.sh`**

In `camel-package.sh`, change the existing `WebsiteManifestGenerator` invocation:

```bash
if ! java "$MODULE_DIR/src/jreleaser/java/WebsiteManifestGenerator.java" \
    --version "$PROJECT_VERSION" --tar "$TAR" --zip "$ZIP" \
    --output "$WEBSITE_DIR/camel-cli" \
    --latest "$WEBSITE_LATEST"; then
```

to:

```bash
if ! java "$MODULE_DIR/src/jreleaser/java/WebsiteManifestGenerator.java" \
    --version "$PROJECT_VERSION" --tar "$TAR" --zip "$ZIP" \
    --install-sh "$WEBSITE_DIR/install.sh" --install-ps1 "$WEBSITE_DIR/install.ps1" \
    --output "$WEBSITE_DIR/camel-cli" \
    --latest "$WEBSITE_LATEST"; then
```

(Points at the already-copied, `cmp -s`-verified `$WEBSITE_DIR` copies from the preceding step, not
`$INSTALL_SH_SRC`/`$INSTALL_PS1_SRC` directly — so the checksums always describe exactly what gets
published, not merely what's in source control at build time. Since those are already asserted
byte-identical, this is a distinction without a difference today, but it's the more defensible
invariant to depend on going forward.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=WebsiteManifestGeneratorTest -Dci.env.name=local`
Expected: PASS — all existing tests plus the 2 new ones green.

- [ ] **Step 6: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local
git add dsl/camel-jbang/camel-launcher/src/jreleaser/java/WebsiteManifestGenerator.java \
        dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh \
        dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/WebsiteManifestGeneratorTest.java
git commit -m "CAMEL-23703: Publish install.sha256 alongside install.sh/install.ps1"
```

---

### Task 5: `InstallScriptFetcher`

**Files:**
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/InstallScriptFetcher.java`
- Test: `dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/InstallScriptFetcherTest.java`

**Interfaces:**
- Produces: `InstallScriptFetcher.fromEnvironment()`, `new InstallScriptFetcher(String
  installBaseUrl)`, `.installBaseUrl()`, `.fetch(Path stagingDir)` — downloads `install.sha256`,
  downloads the OS-appropriate script, verifies it, writes it into `stagingDir`, returns its `Path`.
  Consumed by Task 6 (`SelfUpdateCommand`).
- Consumes: `org.apache.camel.util.FileUtil.isWindows()`, `SelfUpdateException` (Task 3).

New env-var seam, following the exact naming convention `ManifestFetcher` already established:
`CAMEL_SELF_UPDATE_INSTALL_BASE_URL`, default `https://camel.apache.org` (the site root
`install.sh`/`install.ps1`/`install.sha256` all publish under — distinct from
`CAMEL_SELF_UPDATE_MANIFEST_BASE_URL`, whose default already includes the `/camel-cli/releases`
subpath).

- [ ] **Step 1: Write the failing test**

Only `parseChecksums` and the checksum-computation logic are unit-tested directly (no network I/O,
mirroring how `ManifestFetcher.parse` is unit-tested in Task 3); the full `.fetch(...)` network path
is covered end-to-end in Task 8.

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.launcher.selfupdate;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstallScriptFetcherTest {

    private static final String SH_HASH = "a".repeat(64);
    private static final String PS1_HASH = "b".repeat(64);

    @Test
    void parsesValidChecksumFile() {
        String content = "install_sh_sha256=" + SH_HASH + "\ninstall_ps1_sha256=" + PS1_HASH + "\n";

        var checksums = InstallScriptFetcher.parseChecksums(content.getBytes(StandardCharsets.UTF_8));

        assertThat(checksums).containsEntry("install_sh_sha256", SH_HASH).containsEntry("install_ps1_sha256", PS1_HASH);
    }

    @Test
    void rejectsWrongLineCount() {
        String content = "install_sh_sha256=" + SH_HASH + "\n";

        assertThatThrownBy(() -> InstallScriptFetcher.parseChecksums(content.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SelfUpdateException.class);
    }

    @Test
    void rejectsUnknownKey() {
        String content = "install_sh_sha256=" + SH_HASH + "\ninstall_exe_sha256=" + PS1_HASH + "\n";

        assertThatThrownBy(() -> InstallScriptFetcher.parseChecksums(content.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SelfUpdateException.class);
    }

    @Test
    void rejectsBadHexChecksum() {
        String content = "install_sh_sha256=not-hex\ninstall_ps1_sha256=" + PS1_HASH + "\n";

        assertThatThrownBy(() -> InstallScriptFetcher.parseChecksums(content.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SelfUpdateException.class);
    }

    @Test
    void fromEnvironmentUsesProductionDefaultWhenUnset() {
        InstallScriptFetcher fetcher = InstallScriptFetcher.fromEnvironment();

        assertThat(fetcher.installBaseUrl()).isEqualTo("https://camel.apache.org");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=InstallScriptFetcherTest -Dci.env.name=local`
Expected: FAIL — compilation error, `InstallScriptFetcher` does not exist.

- [ ] **Step 3: Write `InstallScriptFetcher`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.launcher.selfupdate;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import org.apache.camel.util.FileUtil;

/**
 * Downloads and checksum-verifies the platform-appropriate installer script ({@code install.sh} on POSIX,
 * {@code install.ps1} on Windows) that {@code SelfUpdateCommand} delegates the actual update to. Verified against
 * {@code install.sha256}, a small companion file published alongside the scripts (see
 * {@code WebsiteManifestGenerator}) - not part of the existing {@code latest.properties}/{@code X.Y.Z.properties}
 * manifest format, which neither script's own strict parser could tolerate being extended with new keys.
 */
public final class InstallScriptFetcher {

    private static final String DEFAULT_INSTALL_BASE_URL = "https://camel.apache.org";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String installBaseUrl;

    public InstallScriptFetcher(String installBaseUrl) {
        this.installBaseUrl = installBaseUrl;
    }

    public static InstallScriptFetcher fromEnvironment() {
        String installBaseUrl = System.getenv().getOrDefault("CAMEL_SELF_UPDATE_INSTALL_BASE_URL", DEFAULT_INSTALL_BASE_URL);
        return new InstallScriptFetcher(installBaseUrl);
    }

    public String installBaseUrl() {
        return installBaseUrl;
    }

    public Path fetch(Path stagingDir) {
        String scriptName = FileUtil.isWindows() ? "install.ps1" : "install.sh";
        String checksumKey = FileUtil.isWindows() ? "install_ps1_sha256" : "install_sh_sha256";

        Map<String, String> checksums = parseChecksums(get(installBaseUrl + "/install.sha256"));
        String expected = checksums.get(checksumKey);
        if (expected == null) {
            throw new SelfUpdateException("install.sha256 is missing " + checksumKey);
        }

        byte[] scriptBytes = get(installBaseUrl + "/" + scriptName);
        String actual = sha256Hex(scriptBytes);
        if (!actual.equals(expected)) {
            throw new SelfUpdateException("checksum mismatch for downloaded " + scriptName);
        }

        try {
            Path scriptPath = stagingDir.resolve(scriptName);
            Files.write(scriptPath, scriptBytes);
            return scriptPath;
        } catch (IOException e) {
            throw new SelfUpdateException("failed to stage " + scriptName, e);
        }
    }

    private byte[] get(String url) {
        try {
            HttpClient client = HttpClient.newBuilder().proxy(ProxySelector.getDefault()).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new SelfUpdateException("failed to download " + url + " (HTTP " + response.statusCode() + ")");
            }
            return response.body();
        } catch (SelfUpdateException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new SelfUpdateException("failed to download " + url, e);
        }
    }

    // Package-visible for direct unit testing without a network round-trip. Exactly 2 non-blank key=value lines,
    // no duplicate/unknown keys, both values 64-char lowercase hex - the same validation shape ManifestFetcher.parse
    // already applies to tar_sha256/zip_sha256.
    static Map<String, String> parseChecksums(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        String[] rawLines = text.split("\n", -1);
        int lineCount = rawLines.length > 0 && rawLines[rawLines.length - 1].isEmpty() ? rawLines.length - 1 : rawLines.length;
        if (lineCount != 2) {
            throw new SelfUpdateException("install.sha256 must contain exactly two lines");
        }

        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < lineCount; i++) {
            String line = rawLines[i];
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            int eq = line.indexOf('=');
            if (eq <= 0 || eq == line.length() - 1) {
                throw new SelfUpdateException("install.sha256 contains a blank line");
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            if (!Set.of("install_sh_sha256", "install_ps1_sha256").contains(key)) {
                throw new SelfUpdateException("install.sha256 has unknown key: " + key);
            }
            if (values.containsKey(key)) {
                throw new SelfUpdateException("install.sha256 has duplicate key: " + key);
            }
            if (!value.matches("[0-9a-f]{64}")) {
                throw new SelfUpdateException(key + " is not a 64-character lowercase hex value");
            }
            values.put(key, value);
        }
        return values;
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK and must always be available.", e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=InstallScriptFetcherTest -Dci.env.name=local`
Expected: PASS.

- [ ] **Step 5: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local
git add dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/InstallScriptFetcher.java \
        dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/InstallScriptFetcherTest.java
git commit -m "CAMEL-23703: Add InstallScriptFetcher for camel self-update delegation"
```

---

### Task 6: `SelfUpdateCommand` and `SelfUpdatePlugin`

**Files:**
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdateCommand.java`
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdatePlugin.java`
- Modify: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/CamelLauncherMain.java`

**Interfaces:**
- Consumes: `InstallDetector.locate()` (baseline Task 1), `ManifestFetcher` (Task 3),
  `InstallScriptFetcher` (Task 5), `VersionHelper.compare()` (existing), `DefaultCamelCatalog()
  .getCatalogVersion()` (existing), `CamelCommand`/`Plugin`/`CamelJBangMain` (existing).
- Produces: the `camel self-update` subcommand. Also a package-private constructor overload
  (Step 1 below) taking explicit `ManifestFetcher`/`InstallScriptFetcher`/environment-override
  values, used directly by Task 8's integration test — the same test-seam shape the baseline plan
  used for its own `SelfUpdateCommand`.

(No isolated unit test for this class: like the baseline plan's own Task 5, it orchestrates
network I/O and process execution that isn't meaningfully unit-testable apart from the pieces
already covered in Tasks 3/5. Task 8 covers it end-to-end.)

- [ ] **Step 1: Write `SelfUpdateCommand`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.launcher.selfupdate;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;

import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.dsl.jbang.core.commands.CamelCommand;
import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.dsl.jbang.core.common.InstallDetector;
import org.apache.camel.dsl.jbang.core.common.VersionHelper;
import org.apache.camel.util.FileUtil;
import picocli.CommandLine;

@CommandLine.Command(name = "self-update", description = "Checks for and installs updates to the Camel CLI launcher",
                     sortOptions = false, showDefaultValues = true,
                     footer = {
                             "%nExamples:",
                             "  camel self-update",
                             "  camel self-update --version 4.23.0",
                             "  camel self-update --check" })
public class SelfUpdateCommand extends CamelCommand {

    @CommandLine.Option(names = { "--version" }, description = "Install a specific version instead of the latest")
    String version;

    @CommandLine.Option(names = { "--check" }, description = "Only report whether a newer version is available")
    boolean checkOnly;

    private final ManifestFetcher fetcherOverride;
    private final InstallScriptFetcher scriptFetcherOverride;
    private final Map<String, String> installerEnvironmentOverride;

    public SelfUpdateCommand(CamelJBangMain main) {
        this(main, null, null, null);
    }

    // Visible for testing: SelfUpdateIntegrationTest (Task 8) points these at a local fixture instead of the real
    // network / real install.sh|install.ps1 default base URL, and pins the delegated script's own environment
    // (CAMEL_INSTALL_*, HOME/LOCALAPPDATA) to an isolated test HOME rather than the real machine's. Production
    // callers (SelfUpdatePlugin) only ever use the public one-arg constructor, which passes null for all three -
    // fromEnvironment() and the real process environment are used unmodified.
    SelfUpdateCommand(CamelJBangMain main, ManifestFetcher fetcherOverride, InstallScriptFetcher scriptFetcherOverride,
            Map<String, String> installerEnvironmentOverride) {
        super(main);
        this.fetcherOverride = fetcherOverride;
        this.scriptFetcherOverride = scriptFetcherOverride;
        this.installerEnvironmentOverride = installerEnvironmentOverride;
    }

    @Override
    public Integer doCall() throws Exception {
        InstallDetector.InstallInfo info = InstallDetector.locate();
        if (info.method() != InstallDetector.InstallMethod.WEB_INSTALLER) {
            printer().printErr(refusalMessage(info.method()));
            return 1;
        }

        ManifestFetcher fetcher = fetcherOverride != null ? fetcherOverride : ManifestFetcher.fromEnvironment();
        ManifestFetcher.Manifest manifest;
        try {
            manifest = version != null ? fetcher.fetch(version) : fetcher.fetchLatest();
        } catch (SelfUpdateException e) {
            printer().printErr(e.getMessage());
            return 1;
        }

        if (version != null && !version.equals(manifest.version())) {
            printer().printErr("manifest version (" + manifest.version() + ") does not match requested version (" + version + ")");
            return 1;
        }

        CamelCatalog catalog = new DefaultCamelCatalog();
        String running = catalog.getCatalogVersion();
        boolean newer = VersionHelper.compare(manifest.version(), running) > 0;

        if (checkOnly) {
            printer().println(newer
                    ? "A new version is available (" + running + " -> " + manifest.version() + ")"
                    : "Camel CLI is already on the latest version (" + running + ")");
            return 0;
        }

        if (!newer && version == null) {
            printer().println("Camel CLI is already on the latest version (" + running + ")");
            return 0;
        }

        // Always pin the exact resolved version, even for a bare `camel self-update` with no --version: without
        // this, a new release could land in the window between the version compare above and the delegated
        // script's own manifest fetch below, and the script would silently install a version this command never
        // actually decided on.
        return runInstaller(manifest.version());
    }

    private Integer runInstaller(String targetVersion) {
        Path stagingDir;
        try {
            stagingDir = Files.createTempDirectory("camel-self-update-");
        } catch (Exception e) {
            printer().printErr("failed to create a staging directory", e);
            return 1;
        }
        try {
            InstallScriptFetcher scriptFetcher = scriptFetcherOverride != null ? scriptFetcherOverride : InstallScriptFetcher.fromEnvironment();
            Path script = scriptFetcher.fetch(stagingDir);

            List<String> command = FileUtil.isWindows()
                    ? List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
                            "-Version", targetVersion)
                    : List.of("/bin/sh", script.toString(), "--version", targetVersion);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            if (installerEnvironmentOverride != null) {
                pb.environment().clear();
                pb.environment().put("PATH", System.getenv("PATH"));
                pb.environment().putAll(installerEnvironmentOverride);
            }
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            printer().print(output);
            return exit;
        } catch (SelfUpdateException e) {
            printer().printErr(e.getMessage());
            return 1;
        } catch (Exception e) {
            printer().printErr("failed to run the installer", e);
            return 1;
        } finally {
            deleteRecursively(stagingDir);
        }
    }

    private static String refusalMessage(InstallDetector.InstallMethod method) {
        return switch (method) {
            case HOMEBREW -> "this install is managed by Homebrew — run 'brew upgrade apache-camel'";
            case CHOCOLATEY -> "this install is managed by Chocolatey — run 'choco upgrade camel-cli'";
            case WINGET -> "this install is managed by WinGet — run 'winget upgrade ApacheCamel.CLI'";
            case SCOOP -> "this install is managed by Scoop — run 'scoop update camel-cli'";
            case SDKMAN -> "this install is managed by SDKMAN — run 'sdk upgrade camel'";
            case JBANG -> "this install is managed by JBang — run 'jbang app install --force camel@apache/camel'";
            default -> "unable to determine how the Camel CLI was installed — see the install docs for how to upgrade";
        };
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    silentDelete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, java.io.IOException exc) {
                    silentDelete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException e) {
            // Best-effort staging cleanup; a leftover temp directory doesn't affect correctness of the next run.
        }
    }

    private static void silentDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (java.io.IOException e) {
            // Best-effort.
        }
    }
}
```

Note: `runInstaller` does **not** call `ZipArchiveValidator`, does **not** touch
`InstallDetector.webInstallerVersionsRoot()` for activation, and does **not** implement its own
`verify_staged()`/`activate()` — all of that already happens inside whichever script it just ran.
The only thing this class still knows about the web-installer layout is via `InstallDetector`
itself (Task 1, unchanged), used purely for the refusal-guard classification.

- [ ] **Step 2: Write `SelfUpdatePlugin`**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.launcher.selfupdate;

import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.dsl.jbang.core.common.Plugin;
import picocli.CommandLine;

public class SelfUpdatePlugin implements Plugin {

    @Override
    public void customize(CommandLine commandLine, CamelJBangMain main) {
        commandLine.addSubcommand("self-update", new CommandLine(new SelfUpdateCommand(main)));
    }
}
```

- [ ] **Step 3: Register the plugin in `CamelLauncherMain`**

Add the import:

```java
import org.apache.camel.dsl.jbang.launcher.selfupdate.SelfUpdatePlugin;
```

Change the `plugins` list in `postAddCommands`:

```java
        List<Plugin> plugins = List.of(
                new GeneratePlugin(),
                new KubernetesPlugin(),
                new SelfUpdatePlugin(),
                new TuiPlugin(),
                new ValidatePlugin(),
                new TestPlugin());
```

- [ ] **Step 4: Compile and manually smoke-test**

Run: `mvnd compile -pl dsl/camel-jbang/camel-launcher -am -Dci.env.name=local`
Expected: BUILD SUCCESS.

Run: `java -cp dsl/camel-jbang/camel-launcher/target/classes:$(mvnd -q -pl dsl/camel-jbang/camel-launcher dependency:build-classpath -Dmdep.outputFile=/dev/stdout -Dci.env.name=local) org.apache.camel.dsl.jbang.launcher.CamelLauncher self-update --help`
Expected: picocli usage help for `self-update`, showing `--version` and `--check`.

- [ ] **Step 5: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local
git add dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdateCommand.java \
        dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdatePlugin.java \
        dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/CamelLauncherMain.java
git commit -m "CAMEL-23703: Add camel self-update command (script delegation)"
```

---

### Task 7: `UpdateChecker` (background notice) — unchanged from the baseline plan

**Files:**
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/UpdateChecker.java`
- Test: `dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/UpdateCheckerTest.java`
- Modify: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/CamelLauncherMain.java`

This task is **identical** to the baseline plan's Task 6 (lines 1680–1968): `UpdateChecker` only
ever calls `ManifestFetcher.fromEnvironment().fetchLatest()` (Task 3, above) — it never touches
archive download or activation, so nothing about the script-delegation alternative changes it.
Apply that task's TDD steps verbatim (failing `UpdateCheckerTest`, `UpdateChecker.java`
implementation, `CamelLauncherMain.preExecute()` wiring), against this document's `ManifestFetcher`
from Task 3 above. Commit message: `CAMEL-23703: Add background update-available notice`.

---

### Task 8: End-to-end integration test for `camel self-update`

**Files:**
- Create: `dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/SelfUpdateIntegrationTest.java`

**Interfaces:**
- Consumes: the package-private `WebsiteInstallerFixture` (same package,
  `org.apache.camel.dsl.jbang.launcher`) — specifically its already-existing `environment(Path
  home)` (returns a `Map<String,String>` with `CAMEL_INSTALL_MANIFEST_BASE_URL`/
  `CAMEL_INSTALL_MAVEN_BASE_URL`/`CAMEL_INSTALL_CA_CERT`/`HOME`/`USERPROFILE`/`LOCALAPPDATA`
  already wired to the fixture and an isolated `home`), `baseUrl()`, `mavenUrl()`, `publish(String,
  byte[])`, `publishManifest(...)`, `safeTar/safeZip(...)`. `SelfUpdateCommand` (Task 6),
  `ManifestFetcher`/`InstallScriptFetcher` (Tasks 3/5), constructed directly.

Unlike the baseline plan's Task 7 — which had to reimplement archive-serving fixtures because
`SelfUpdateCommand` itself extracted and activated the archive — this test's key property is that
it runs the **real, unmodified `install.sh`** (`@DisabledOnOs(OS.WINDOWS)`, same as the baseline's
own choice, since CI runs this suite on POSIX; a parallel Windows-only class exercising
`install.ps1` is a reasonable follow-up but out of scope here, matching how `WebsiteInstallTest`
itself already splits POSIX/Windows into separate nested classes). This means `SelfUpdateCommand`'s
own code is exercised together with the actual `src/install/install.sh` from this repo — a
stronger guarantee than testing a Java re-implementation of the same logic ever could give, and it
means archive-entry-validation edge cases (traversal, symlinks, multi-root) do **not** need
dedicated tests here: they're `install.sh`'s own concern, already covered by the existing
`WebsiteInstallTest` suite, unaffected by this plan.

- [ ] **Step 1: Write the test**

```java
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.dsl.jbang.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.dsl.jbang.core.common.StringPrinter;
import org.apache.camel.dsl.jbang.launcher.selfupdate.InstallScriptFetcher;
import org.apache.camel.dsl.jbang.launcher.selfupdate.ManifestFetcher;
import org.apache.camel.dsl.jbang.launcher.selfupdate.SelfUpdateCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

@DisabledOnOs(OS.WINDOWS)
class SelfUpdateIntegrationTest {

    private static final Path REAL_INSTALL_SH = Paths.get("src/install/install.sh").toAbsolutePath();

    private StringPrinter printer;

    @BeforeEach
    void setup() {
        printer = new StringPrinter();
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("camel.launcher.jar");
    }

    private String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    // Publishes the REAL install.sh from this repo (and an install.sha256 matching it) at the fixture's
    // site root, so InstallScriptFetcher's download+verify step and the eventual `/bin/sh <script>` delegation
    // both exercise the actual production script, not a stand-in.
    private void publishRealInstallSh(WebsiteInstallerFixture fixture) throws Exception {
        byte[] script = Files.readAllBytes(REAL_INSTALL_SH);
        fixture.publish("/install.sh", script);
        String checksums = "install_sh_sha256=" + sha256Hex(REAL_INSTALL_SH) + "\n"
                            + "install_ps1_sha256=" + "0".repeat(64) + "\n";
        fixture.publish("/install.sha256", checksums.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void markRunningAsWebInstaller(Path xdgDataHome, String runningVersion) throws Exception {
        Path versionDir = xdgDataHome.resolve("camel-cli/versions/" + runningVersion);
        Files.createDirectories(versionDir.resolve("bin"));
        System.setProperty("camel.launcher.jar", versionDir.resolve("camel-launcher.jar").toString());
    }

    private int run(WebsiteInstallerFixture fixture, Path xdgDataHome, String... args) {
        ManifestFetcher fetcher = new ManifestFetcher(fixture.baseUrl() + "/camel-cli/releases", fixture.mavenUrl());
        InstallScriptFetcher scriptFetcher = new InstallScriptFetcher(fixture.baseUrl());
        Map<String, String> installerEnv = fixture.environment(xdgDataHome.getParent());
        SelfUpdateCommand cmd = new SelfUpdateCommand(new CamelJBangMain().withPrinter(printer), fetcher, scriptFetcher, installerEnv);
        return new CommandLine(cmd).execute(args);
    }

    @Test
    void installsLatestVersionByDelegatingToRealInstallSh(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path home = Files.createDirectories(temp.resolve("home"));
            Path xdgDataHome = home.resolve(".local/share");
            markRunningAsWebInstaller(xdgDataHome, "1.0.0");
            publishRealInstallSh(fixture);
            WebsiteInstallTest.publishLatest(fixture, "2.0.0");

            int exit = run(fixture, xdgDataHome);

            assertThat(exit).isZero();
            assertThat(printer.getOutput()).contains("Installed Camel CLI 2.0.0");
            assertThat(Files.isDirectory(xdgDataHome.resolve("camel-cli/versions/2.0.0"))).isTrue();
            assertThat(Files.exists(home.resolve(".local/bin/camel"))).isTrue();
        }
    }

    @Test
    void alreadyOnLatestVersionSkipsDelegationEntirely(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path home = Files.createDirectories(temp.resolve("home"));
            Path xdgDataHome = home.resolve(".local/share");
            markRunningAsWebInstaller(xdgDataHome, "2.0.0");
            // Deliberately do NOT publish install.sh/install.sha256: if the "already latest" fast path
            // regressed into always delegating, this test would fail on a 404 rather than silently passing.
            WebsiteInstallTest.publishLatest(fixture, "2.0.0");

            int exit = run(fixture, xdgDataHome);

            assertThat(exit).isZero();
            assertThat(printer.getOutput()).contains("already on the latest version");
            try (var listing = Files.list(xdgDataHome.resolve("camel-cli/versions"))) {
                assertThat(listing.count()).isEqualTo(1);
            }
        }
    }

    @Test
    void checksOnlyWithoutDelegating(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path home = Files.createDirectories(temp.resolve("home"));
            Path xdgDataHome = home.resolve(".local/share");
            markRunningAsWebInstaller(xdgDataHome, "1.0.0");
            // Same deliberate omission as above: --check must never reach InstallScriptFetcher.
            WebsiteInstallTest.publishLatest(fixture, "2.0.0");

            int exit = run(fixture, xdgDataHome, "--check");

            assertThat(exit).isZero();
            assertThat(printer.getOutput()).contains("A new version is available");
            assertThat(Files.isDirectory(xdgDataHome.resolve("camel-cli/versions/2.0.0"))).isFalse();
        }
    }

    @Test
    void refusesInstallScriptChecksumMismatch(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path home = Files.createDirectories(temp.resolve("home"));
            Path xdgDataHome = home.resolve(".local/share");
            markRunningAsWebInstaller(xdgDataHome, "1.0.0");
            fixture.publish("/install.sh", Files.readAllBytes(REAL_INSTALL_SH));
            // install.sha256 doesn't match the published install.sh - InstallScriptFetcher must refuse before
            // ever invoking the script.
            String badChecksums = "install_sh_sha256=" + "a".repeat(64) + "\ninstall_ps1_sha256=" + "b".repeat(64) + "\n";
            fixture.publish("/install.sha256", badChecksums.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            WebsiteInstallTest.publishLatest(fixture, "2.0.0");

            int exit = run(fixture, xdgDataHome);

            assertThat(exit).isEqualTo(1);
            assertThat(Files.isDirectory(xdgDataHome.resolve("camel-cli/versions/2.0.0"))).isFalse();
        }
    }

    @Test
    void pinsToResolvedVersionEvenWithoutExplicitFlag(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path home = Files.createDirectories(temp.resolve("home"));
            Path xdgDataHome = home.resolve(".local/share");
            markRunningAsWebInstaller(xdgDataHome, "1.0.0");
            publishRealInstallSh(fixture);
            WebsiteInstallTest.publishLatest(fixture, "2.0.0");

            int exit = run(fixture, xdgDataHome);

            assertThat(exit).isZero();
            // install.sh received "--version 2.0.0" explicitly (not a bare invocation that would re-resolve
            // "latest" on its own) - asserted indirectly: the installed directory matches the version this
            // command's own manifest compare resolved, not merely "whatever install.sh's own default fetch found"
            // (which would be true either way against this fixture, but the explicit --version pin is what
            // prevents a TOCTOU race against a real, changing latest.properties in production).
            assertThat(Files.isDirectory(xdgDataHome.resolve("camel-cli/versions/2.0.0"))).isTrue();
        }
    }

    @Test
    void refusesNonWebInstallerInstall(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path home = Files.createDirectories(temp.resolve("home"));
            Path xdgDataHome = home.resolve(".local/share");
            System.setProperty("camel.launcher.jar", "/opt/homebrew/Cellar/apache-camel/1.0.0/libexec/camel-launcher.jar");
            WebsiteInstallTest.publishLatest(fixture, "2.0.0");

            int exit = run(fixture, xdgDataHome);

            assertThat(exit).isEqualTo(1);
            assertThat(printer.getOutput()).contains("Homebrew");
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=SelfUpdateIntegrationTest -Dci.env.name=local`
Expected: FAIL initially on the `SelfUpdateCommand(main, fetcher, scriptFetcher, env)` constructor
signature if Task 6 Step 1 wasn't applied exactly as written above; once Task 6 is in place, this
should compile and the first real failures (if any) come from fixture wiring — see Step 3.

- [ ] **Step 3: Reconcile fixture details against the real `WebsiteInstallerFixture`/`WebsiteInstallTest`**

Before trusting this test's PASS/FAIL result, confirm these exact points against the current
`WebsiteInstallerFixture.java`/`WebsiteInstallTest.java` (both already exist in this repo from
earlier, already-merged CAMEL-23703 work) — they were read directly while writing this task, but
re-verify since this file predates this task and may have evolved further:

- `WebsiteInstallerFixture.environment(Path home)` sets `HOME`/`USERPROFILE`/`LOCALAPPDATA` from
  its `home` parameter and the three `CAMEL_INSTALL_*` overrides — confirm the parameter really is
  the POSIX `$HOME` (not `$XDG_DATA_HOME` directly), since this test passes
  `xdgDataHome.getParent()` (i.e. `home`, so `install.sh`'s own `${XDG_DATA_HOME:-$HOME/.local/share}`
  fallback resolves to the same `xdgDataHome` this test already created and asserts against).
- `WebsiteInstallTest.publishLatest(WebsiteInstallerFixture, String)` — confirm this static helper
  exists with this exact signature (used already by the baseline plan's own Task 7); it publishes a
  `latest.properties` plus a safe archive pair for the given version.
- `fixture.publish(String urlPath, byte[] body)` — confirm the path is served relative to
  `fixture.baseUrl()` with no extra prefix, so `/install.sh` really is reachable at
  `fixture.baseUrl() + "/install.sh"`, matching `InstallScriptFetcher`'s
  `installBaseUrl + "/install.sh"` construction with `installBaseUrl = fixture.baseUrl()`.

If any of these differ from what's assumed above, adjust the test to match the fixture's actual
API rather than changing the fixture — it's shared with `WebsiteInstallTest`'s own, already-passing
suite.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=SelfUpdateIntegrationTest,ManifestFetcherTest,InstallScriptFetcherTest,UpdateCheckerTest,WebsiteManifestGeneratorTest -Dci.env.name=local`
Expected: PASS — all scenarios plus the earlier unit suites green.

- [ ] **Step 5: Run the full `camel-launcher` test module once more before committing**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local`
Expected: BUILD SUCCESS — confirms Tasks 3–8 don't regress `WebsiteInstallTest`,
`WebsiteManifestGeneratorTest`, `CamelLauncherTest`, or anything else in the module.

- [ ] **Step 6: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local
git add dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/SelfUpdateIntegrationTest.java
git commit -m "CAMEL-23703: Add end-to-end integration test for camel self-update delegation"
```

---

### Task 9: Documentation

**Files:**
- Create: `docs/user-manual/modules/ROOT/pages/camel-jbang-launcher-install.adoc`
- Modify: `docs/user-manual/modules/ROOT/pages/camel-jbang-launcher.adoc`
- Modify: `docs/user-manual/modules/ROOT/pages/camel-4x-upgrade-guide-4_22.adoc`

**Interfaces:** none (documentation only).

Same three files and same insertion points as the baseline plan's Task 8 (confirmed directly:
`camel-jbang-launcher-install.adoc` does not exist yet in this repo, and
`camel-4x-upgrade-guide-4_22.adoc` already has a "Website installers for the Camel CLI"
`====` subsection ending right before `=== camel-langchain4j-agent`, from earlier merged
CAMEL-23703 work — insert immediately before that line). Content differs only where the delegation
mechanism is actually user-visible.

- [ ] **Step 1: Create `camel-jbang-launcher-install.adoc`**

Same content as the baseline plan's Task 8 Step 1, with the "Upgrading and switching versions"
section's opening paragraph replaced:

```adoc
== Upgrading and switching versions

Once installed through `install.sh`/`install.ps1`, run:

[source,bash]
----
camel self-update                 # install the latest release, if newer than what's running
camel self-update --version 4.23.0  # install a specific version (upgrade or downgrade)
camel self-update --check         # report only, install nothing
----

`camel self-update` checks whether an update is actually needed first (no network cost for the
archive itself if you're already current), then re-runs the same, published `install.sh`/
`install.ps1` installer described above — pinned to the exact version it just resolved — rather
than re-implementing the download/verify/extract/activate steps a second time. The installer
script itself is downloaded fresh and verified against a SHA-256 published alongside it
(`install.sha256`) before it's run.

`camel self-update` refuses to run, with a message naming the detected package manager and its own
upgrade command (e.g. `brew upgrade apache-camel`), when the currently running installation was not
made by `install.sh`/`install.ps1` — installations managed by a package manager must be upgraded
through that manager.
```

The rest of the page (installer description, "Where the CLI is installed", the background notice
paragraph, "Detecting conflicting installations") is identical to the baseline plan's Task 8 Step 1
— apply that content unchanged.

- [ ] **Step 2: Link the new page from `camel-jbang-launcher.adoc`**

Identical to the baseline plan's Task 8 Step 2 — apply unchanged.

- [ ] **Step 3: Add the upgrade guide entry**

Same insertion point as the baseline plan's Task 8 Step 3 (immediately after the existing "Website
installers for the Camel CLI" `====` subsection, before `=== camel-langchain4j-agent`), with the
bullet point about `camel self-update` reworded:

```adoc
==== camel self-update and camel doctor

Two new commands are available in the xref:camel-jbang-launcher.adoc[Camel CLI Launcher]:

* `camel self-update` checks for and installs newer launcher releases, re-running the same
  published `install.sh`/`install.ps1` installer described above (see "Website installers for the
  Camel CLI" above) pinned to the resolved version, after checking whether an update is actually
  needed. Refuses to act on an installation managed by a package manager (Homebrew, Chocolatey,
  WinGet, Scoop, SDKMAN) or by JBang, naming that manager's own upgrade command instead. Every
  `camel` invocation (except `self-update` itself) also prints a one-line notice, at most once
  every 24 hours, when a newer release is available; set `CAMEL_SELF_UPDATE_CHECK=false` to
  disable it.
* `camel doctor` now additionally reports every Camel CLI installation found on the machine across
  the web installer and all supported package managers, marking which one is actually active on
  `PATH`, and exits non-zero when more than one is found.

See xref:camel-jbang-launcher-install.adoc[Installing the Camel CLI Launcher] for full details.
```

- [ ] **Step 4: Verify manually**

Read back all three modified/created files, confirming every `xref:` target resolves to a real page
under `docs/user-manual/modules/ROOT/pages/`, per this repo's `CLAUDE.md` documentation
conventions (internal links use `xref:`, never a bare `https://camel.apache.org/...` URL).

- [ ] **Step 5: Commit**

```bash
git add docs/user-manual/modules/ROOT/pages/camel-jbang-launcher-install.adoc \
        docs/user-manual/modules/ROOT/pages/camel-jbang-launcher.adoc \
        docs/user-manual/modules/ROOT/pages/camel-4x-upgrade-guide-4_22.adoc
git commit -m "CAMEL-23703: Document camel self-update (script delegation) and camel doctor"
```

---

## Continuing to the Homebrew Distribution work

After Task 9 above, continue with **Tasks 9–15 of the baseline plan**
(`docs/superpowers/plans/2026-07-22-launcher-self-update.md`, lines 2415–3478) unchanged — the
Homebrew `apache-camel` rename/distribution work never depended on how `SelfUpdateCommand`
performs an update, only on `InstallDetector`'s Homebrew marker (unaffected, baseline Task 1) and
`SelfUpdateCommand`'s refusal message text (unaffected — Task 6 above already says `brew upgrade
apache-camel`, matching what the baseline's own Task 14 cross-check expects).

## Final verification

- [ ] Run the full `camel-launcher` and `camel-jbang-core` test modules once more:
  `mvnd test -pl dsl/camel-jbang/camel-launcher,dsl/camel-jbang/camel-jbang-core -Dci.env.name=local`
  Expected: BUILD SUCCESS.
- [ ] Confirm `git log --oneline` shows one commit per task (3 through 9 here, plus 1–2 and 9–15
  from the baseline plan), each independently revertable.
- [ ] Confirm no `commons-compress` scope change and no `ZipArchiveValidator` class exist anywhere
  in the diff — the clearest sanity check that this alternative was actually followed rather than
  quietly drifting back toward the baseline's Java re-implementation.
