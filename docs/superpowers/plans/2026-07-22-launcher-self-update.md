# Camel CLI Launcher: Self-Update, Doctor, and Homebrew Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `camel self-update` (fetch and install a newer launcher release, or a specific version, via the existing web-installer manifest/Maven-Central infrastructure) and extend the existing `camel doctor` command to detect conflicting Camel CLI installations across package managers; and separately, rename the launcher's Homebrew formula `camel` → `apache-camel` and retarget its publish destination from an undecided own-tap to `homebrew-core`, closing the long-open `KNOWN GAP` in `jreleaser.yml` — per the design in `docs/superpowers/specs/2026-07-21-launcher-self-update-design.md`.

**Architecture:** A new `InstallDetector` utility lives in `camel-jbang-core` (not `camel-launcher`) because the existing `Doctor` command that needs it lives in `camel-jbang-core`, which `camel-launcher` depends on — not the reverse. `camel self-update` is new, launcher-only code (a `Plugin` SPI implementation, same mechanism as `KubernetesPlugin`/`TuiPlugin`) that fetches a manifest over HTTPS, downloads a `.zip` archive from Maven Central, verifies its checksum, validates its entries, extracts it to a new `versions/<version>` directory, smoke-tests it, and atomically activates it (POSIX symlink swap or Windows `.cmd` shim rewrite) — mirroring `install.sh`/`install.ps1` exactly, in Java. A background `UpdateChecker` runs once per `camel` invocation (except `self-update` itself) to print a one-line stderr-style notice when a cached check shows a newer release. Separately, the Homebrew rename/distribution work (Tasks 9-14) touches only the existing JReleaser packaging pipeline (`jreleaser.yml`, `camel-package.sh`, `formula.rb.tpl`) plus two new shell scripts (`camel-validate.sh`, `camel-publish.sh`) ported forward from a prior, more complete iteration of this same branch (preserved at `../backup-CAMEL-23703` — a sibling checkout, not part of this repo) and adapted for a real `homebrew-core` PR workflow instead of that iteration's project-owned-tap assumption; it does not touch the self-update/doctor Java code beyond the two one-line fixes already applied above (Task 1's `scanHomebrew()`, Task 5's `refusalMessage()`).

**Tech Stack:** Java 17, picocli (existing `CamelCommand`/`Plugin` conventions), `java.net.http.HttpClient` (already used elsewhere in `camel-jbang-core`), `java.util.zip` for extraction, Apache Commons Compress (`commons-compress`, promoted to compile scope — see Global Constraints) for Unix-mode symlink detection during zip entry validation, JUnit 5 + AssertJ + the existing `WebsiteInstallerFixture` test fixture. For the Homebrew distribution tasks: POSIX `sh`, JReleaser Maven plugin `1.25.0` (pinned in `pom.xml`, confirmed — see Task 9), `brew` (Homebrew itself, for local formula validation), `gh` CLI.

## Global Constraints

- Java 17 bytecode target (root `pom.xml` `jdk.version`), Camel version `4.22.0-SNAPSHOT` (milestone `4.22.0`).
- New test code MUST NOT use `Thread.sleep()` — use Awaitility or `MockEndpoint` timed assertions where a wait is genuinely needed (this feature has none — all waits are on local subprocess/file I/O with explicit timeouts already modeled by `WebsiteInstallerFixture`).
- New test code SHOULD use AssertJ (`assertThat(...)`) rather than JUnit assertions. `DoctorTest.java` currently uses JUnit assertions (`Assertions.assertEquals`/`assertTrue`) throughout — new test **methods** added to it in this plan use AssertJ; existing methods are left untouched (do not sweep the file).
- New test classes/methods MUST NOT use the `public` modifier (package-private). `DoctorTest.java`'s existing methods are `public` (pre-existing, untouched); new methods added to that file in this plan are **not** `public`.
- No fully qualified class names in Java code — always add an `import` and use the simple name.
- No new git dependencies without approval — this plan changes one existing dependency's *scope* (`commons-compress` from `test` to default/compile in `camel-launcher/pom.xml`, see below); it does not add a new library.
- Every new `@UriParam`-style user-facing option, changed default, or new command must be documented in the upgrade guide — see Tasks 8 and 15.
- `homebrew/homebrew-core`'s default branch is `main`; `microsoft/winget-pkgs`'s default branch is `master` — confirmed directly via `gh api repos/homebrew/homebrew-core --jq .default_branch` and `gh api repos/microsoft/winget-pkgs --jq .default_branch` during this plan's research (not assumed), matching what Task 13 below clones.
- JReleaser Homebrew packager facts confirmed directly against JReleaser's own docs during this plan's research (both were open questions the design spec flagged as "verify during implementation" — see Task 9): (1) for a `JAVA_BINARY` distribution, JReleaser auto-generates a `depends_on "openjdk@<major>"` line in the rendered formula from `project.languages.java.version`, so a template must **not** also hand-write its own `depends_on` line; (2) a packager's `livecheck:` field is a raw list of Homebrew-Ruby source lines (`livecheck: ['url "..."', 'regex(/.../)']`), not a set of structured `url`/`regex` sub-keys — this is exactly what `formula.rb.tpl`'s existing `{{#brewLivecheck}}{{.}}{{/brewLivecheck}}` Mustache loop already expects.

### Deviations from the design spec, deliberately made and why (read before implementing)

The design spec (`docs/superpowers/specs/2026-07-21-launcher-self-update-design.md`) was written before this plan's investigation into the actual codebase. Three points in it don't hold up against the real code, and the user has confirmed how to resolve each:

1. **`camel doctor` already exists.** `Doctor.java` (`dsl/camel-jbang/camel-jbang-core/src/main/java/org/apache/camel/dsl/jbang/core/commands/Doctor.java`) is a real, shipped command, registered in `CamelJBangMain.execute()` (line 151) *before* `postAddCommands()` runs (line 242). A `Plugin` cannot register a second top-level `"doctor"` subcommand — picocli throws `DuplicateNameException`. **Resolution (user-approved): extend `Doctor.java` in place** with an additional check, gated so it only runs when launched via the native launcher (see Task 2). This means Task 2 touches `camel-jbang-core`, contradicting the spec's "both new commands live in `camel-launcher` only" framing — that framing is wrong for `doctor` specifically; `self-update` (an actually-new subcommand name) is unaffected and stays entirely in `camel-launcher`.
2. **`camel-jbang-launcher-install.adoc` does not exist**, nor do the "Where the CLI is installed" / "Upgrading and switching versions" sections the spec describes editing. The real, existing doc is `docs/user-manual/modules/ROOT/pages/camel-jbang-launcher.adoc` (53 lines, different structure entirely). **Resolution (user-approved): create `camel-jbang-launcher-install.adoc` as a new file** (Task 8), linked from the existing `camel-jbang-launcher.adoc`.
3. **`java.util.zip` cannot detect symlink entries.** The design spec says archive-entry validation (including symlink/hardlink rejection, mirroring `install.sh`'s `validate_tar` and `install.ps1`'s `Test-ArchiveEntry`) uses `java.util.zip` with "zero new dependencies." `java.util.zip.ZipEntry` (the JDK API) does not expose the Unix external-file-attributes field a ZIP central directory entry carries, so there is no way to detect a symlink entry through it. `commons-compress` (`org.apache.commons.compress.archivers.zip.ZipArchiveEntry.getUnixMode()`) does expose this, and is already a dependency of `camel-launcher` — but scoped `test` only (used today by `WebsiteInstallerFixture` to *build* malicious symlink test archives). **Resolution (user-approved): promote `commons-compress` from `test` scope to default (compile) scope** in `dsl/camel-jbang/camel-launcher/pom.xml` and use `ZipArchiveEntry.getUnixMode()` for real symlink detection in production code (Task 4). This is not a new dependency — it is a scope widening of one already present and already used by this module's own tests — but it is documented here because it changes what the spec described.

A fourth, smaller correction: the spec's `ManifestFetcher` section says the parser should tolerate "`#`-prefixed comment/license-header lines." Neither the actual manifest format (`WebsiteManifestGenerator.renderManifest`, which emits exactly 4 `key=value` lines, no comments) nor `install.sh`'s real `parse_manifest` (which has no comment-handling branch — any line that isn't a recognized `key=value` pair fails) do this. `ManifestFetcher` (Task 3) mirrors the *actual* parsers exactly: exactly 4 lines, no blank lines, no comments, CRLF-tolerant (the real parsers do strip a trailing `\r`), no duplicate/unknown keys.

A fifth point, this one resolving two items the design spec explicitly left open rather than correcting a wrong claim: the spec's "Open items to verify during implementation" section flagged (a) whether `project.languages.java.version: 21` really auto-derives `depends_on "openjdk@21"` on the pinned JReleaser version, and (b) whether `supported-lts.yml`'s missing `4.18` line was deliberate. This plan's own research (done directly against JReleaser's docs and this repo's git history while writing these tasks, not assumed) resolved (a) — confirmed in Global Constraints above — and Task 10 resolves (b) via `git log -p -- '**/supported-lts.yml'`. Both are folded into Tasks 9-10 below rather than left as reminders for whoever implements them.

A sixth, structural point: the design spec frames the self-update/doctor commands (Tasks 1-8) and the Homebrew rename (Tasks 9-15) as touching "a different, non-overlapping set of files" — true for the bulk of both, but not entirely: the design's own "Interaction with self-update and doctor" subsection requires `InstallDetector`'s Homebrew-probing default root (Task 1) and `SelfUpdateCommand`'s Homebrew refusal message (Task 5) to say `apache-camel`, not the placeholder `camel-cli` name the spec's earlier sections used before the rename subsection was added. Rather than having Task 14 patch Tasks 1/5 after the fact — which would leave a stale, wrong package name sitting in the plan for eight tasks — those two spots were corrected in place above (Task 1's `scanHomebrew()`, Task 5's `refusalMessage()`) as part of this update; Task 14 only needs to verify they're consistent with the newly-authored Homebrew tasks, not fix them.

---

## File structure

```
dsl/camel-jbang/camel-jbang-core/src/main/java/org/apache/camel/dsl/jbang/core/common/
  InstallDetector.java                     [NEW]  install-location detection, shared by self-update and doctor
dsl/camel-jbang/camel-jbang-core/src/main/java/org/apache/camel/dsl/jbang/core/commands/
  Doctor.java                              [MODIFY]  add gated install-location conflict check
dsl/camel-jbang/camel-jbang-core/src/test/java/org/apache/camel/dsl/jbang/core/common/
  InstallDetectorTest.java                 [NEW]
dsl/camel-jbang/camel-jbang-core/src/test/java/org/apache/camel/dsl/jbang/core/commands/
  DoctorTest.java                          [MODIFY]  add 2 new test methods
dsl/camel-jbang/camel-launcher/pom.xml     [MODIFY]  commons-compress: test -> default scope
dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/
  CamelLauncherMain.java                   [MODIFY]  register SelfUpdatePlugin, override preExecute()
dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/
  ManifestFetcher.java                     [NEW]
  SelfUpdateException.java                 [NEW]
  ZipArchiveValidator.java                 [NEW]
  SelfUpdateCommand.java                   [NEW]
  SelfUpdatePlugin.java                    [NEW]
  UpdateChecker.java                       [NEW]
dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/
  ManifestFetcherTest.java                 [NEW]
  ZipArchiveValidatorTest.java             [NEW]
  UpdateCheckerTest.java                   [NEW]
dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/
  SelfUpdateIntegrationTest.java           [NEW]  reuses package-private WebsiteInstallerFixture
docs/user-manual/modules/ROOT/pages/
  camel-jbang-launcher-install.adoc        [NEW in Task 8, MODIFY in Task 15]
  camel-jbang-launcher.adoc                [MODIFY]  link to the new page
  camel-4x-upgrade-guide-4_22.adoc         [MODIFY in Task 8, MODIFY again in Task 15]
  release-guide.adoc                       [MODIFY in Task 15]  homebrew-core submission process

# Homebrew Distribution (Tasks 9-15) - independent subsystem, no overlap with the files above
# except the three doc files, which Task 15 extends after Task 8 creates/edits them first.
dsl/camel-jbang/camel-launcher/
  jreleaser.yml                            [MODIFY]  java.version 17->21; brew.livecheck; extraProperties
  pom.xml                                  [unchanged by this section - already covered by Task 4]
  src/jreleaser/bin/
    camel-package.sh                       [MODIFY]  BREW_FORMULA/BREW_CLASS rename; livecheck regex;
                                                       deprecate!/disable! dates; publish stub removed
    camel-validate.sh                      [NEW]  ported from ../backup-CAMEL-23703, apache-camel rename,
                                                    --new --strict
    camel-publish.sh                       [NEW]  ported from ../backup-CAMEL-23703, homebrew-core PR flow
    lib/assert-camel-cli.sh                [NEW]  ported unchanged
    lib/publish-state.sh                   [NEW]  ported unchanged
  src/jreleaser/distributions/camel-cli/
    brew/formula.rb.tpl                    [MODIFY]  depends_on/caveats removed; deprecate!/disable! added
    scoop/manifest.json.tpl                [MODIFY]  post_install native-exe cleanup
    chocolatey/tools/chocolateyinstall.ps1.tpl [MODIFY]  native-exe cleanup
  src/test/resources/validate/
    expected-init-route.txt                [NEW]  ported unchanged
```

**Interfaces summary** (exact names/types every task after Task 1 depends on):

- `InstallDetector.InstallMethod` — enum: `WEB_INSTALLER, HOMEBREW, CHOCOLATEY, WINGET, SCOOP, SDKMAN, JBANG, UNKNOWN`.
- `InstallDetector.InstallInfo` — record: `(Path location, InstallMethod method)`.
- `InstallDetector.locate()` — `static InstallInfo locate()`.
- `InstallDetector.scanKnownLocations()` — `static List<InstallInfo> scanKnownLocations()`.
- `InstallDetector.resolveActiveOnPath()` — `static Optional<Path> resolveActiveOnPath()`.
- `InstallDetector.webInstallerVersionsRoot()` — `static Path webInstallerVersionsRoot()`.
- `ManifestFetcher.Manifest` — record: `(String version, String tarSha256, String zipSha256)`.
- `ManifestFetcher.fromEnvironment()` — `static ManifestFetcher fromEnvironment()`.
- `new ManifestFetcher(String manifestBaseUrl, String mavenBaseUrl)` — explicit constructor, used directly by tests.
- `ManifestFetcher.fetchLatest()` / `.fetch(String version)` — `Manifest fetchLatest() throws SelfUpdateException` / `Manifest fetch(String version) throws SelfUpdateException`.
- `ManifestFetcher.downloadArchive(String version, Path target)` — `void downloadArchive(String version, Path target) throws SelfUpdateException`.
- `ZipArchiveValidator.validate(Path archive, String expectedVersion)` — `static void validate(Path archive, String expectedVersion) throws SelfUpdateException`.
- `SelfUpdateException` — `RuntimeException` subclass with a plain message (mirrors `install.sh`'s `fail()`).
- `UpdateChecker.maybeNotify(String[] args)` — `static void maybeNotify(String[] args)`, writes directly to `System.err`.

---

### Task 1: `InstallDetector` (shared install-location detection)

**Files:**
- Create: `dsl/camel-jbang/camel-jbang-core/src/main/java/org/apache/camel/dsl/jbang/core/common/InstallDetector.java`
- Test: `dsl/camel-jbang/camel-jbang-core/src/test/java/org/apache/camel/dsl/jbang/core/common/InstallDetectorTest.java`

**Interfaces:**
- Produces: `InstallDetector.InstallMethod`, `InstallDetector.InstallInfo`, `InstallDetector.locate()`, `InstallDetector.scanKnownLocations()`, `InstallDetector.resolveActiveOnPath()`, `InstallDetector.webInstallerVersionsRoot()` — all listed above, consumed by Task 2 (`Doctor.java`) and Task 5 (`SelfUpdateCommand`).
- Consumes: `org.apache.camel.util.FileUtil.isWindows()` (existing house convention for OS branching, already used by `LauncherHelper.java` in the same module family) and the system property `camel.launcher.jar`, which `CamelLauncher.main()` (`dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/CamelLauncher.java:38-44`) already sets unconditionally to the path of the currently-running launcher JAR.

Every location-scanning method below is **best-effort**, matching the design's own "best-effort path-substring matching" language for `locate()`'s classification of an already-known path. `scanKnownLocations()` additionally needs *concrete* default install roots per package manager to probe (the design spec's table only gives marker substrings for classifying a given path, not roots to scan from nothing); the roots below are read from this repo's own JReleaser distribution templates (`dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/**`) where possible: Homebrew's `formula.rb.tpl` installs into `libexec` under the formula's Cellar keg; Scoop's `manifest.json.tpl` sets `extract_dir: camel-launcher-$version` under `scoop/apps/camel-cli/<version>`; Chocolatey's `chocolateyinstall.ps1.tpl` uses `Install-ChocolateyZipPackage`, which Chocolatey extracts to `C:\ProgramData\chocolatey\lib\<package>\tools\`. WinGet's actual install folder name includes a publisher hash Chocolatey/Scoop don't have, making it unenumerable in general — `scanKnownLocations()` intentionally does not attempt to probe it and instead documents that WinGet installs are only detected when they happen to be the *active* one (via `resolveActiveOnPath()`'s substring match against `\WinGet\Packages\`), consistent with "doctor is scoped to... not a general troubleshooting tool."

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
package org.apache.camel.dsl.jbang.core.common;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.util.FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class InstallDetectorTest {

    @Test
    void classifiesWebInstallerPath(@TempDir Path temp) throws Exception {
        Path versionDir = temp.resolve("camel-cli/versions/4.22.0");
        Files.createDirectories(versionDir);

        InstallDetector.InstallMethod method = InstallDetector.classify(versionDir, temp);

        assertThat(method).isEqualTo(InstallDetector.InstallMethod.WEB_INSTALLER);
    }

    @Test
    void classifiesHomebrewPath() {
        Path path = Path.of("/opt/homebrew/Cellar/apache-camel/4.21.0/libexec");

        InstallDetector.InstallMethod method = InstallDetector.classify(path, Path.of("/unrelated"));

        assertThat(method).isEqualTo(InstallDetector.InstallMethod.HOMEBREW);
    }

    @Test
    void classifiesSdkmanPath() {
        Path path = Path.of(System.getProperty("user.home"), ".sdkman/candidates/camel/4.21.0");

        InstallDetector.InstallMethod method = InstallDetector.classify(path, Path.of("/unrelated"));

        assertThat(method).isEqualTo(InstallDetector.InstallMethod.SDKMAN);
    }

    @Test
    void classifiesJBangPath() {
        Path path = Path.of(System.getProperty("user.home"), ".jbang/cache/apps/camel");

        InstallDetector.InstallMethod method = InstallDetector.classify(path, Path.of("/unrelated"));

        assertThat(method).isEqualTo(InstallDetector.InstallMethod.JBANG);
    }

    @Test
    void classifiesChocolateyPath() {
        Path path = Path.of("C:\\ProgramData\\chocolatey\\lib\\camel-cli\\tools\\camel.bat");

        InstallDetector.InstallMethod method = InstallDetector.classify(path, Path.of("C:\\unrelated"));

        assertThat(method).isEqualTo(InstallDetector.InstallMethod.CHOCOLATEY);
    }

    @Test
    void classifiesWinGetPath() {
        Path path = Path.of("C:\\Users\\me\\AppData\\Local\\Microsoft\\WinGet\\Packages\\ApacheCamel.CLI_abc123\\camel.exe");

        InstallDetector.InstallMethod method = InstallDetector.classify(path, Path.of("C:\\unrelated"));

        assertThat(method).isEqualTo(InstallDetector.InstallMethod.WINGET);
    }

    @Test
    void classifiesScoopPath() {
        Path path = Path.of("C:\\Users\\me\\scoop\\apps\\camel-cli\\4.21.0\\bin\\camel.bat");

        InstallDetector.InstallMethod method = InstallDetector.classify(path, Path.of("C:\\unrelated"));

        assertThat(method).isEqualTo(InstallDetector.InstallMethod.SCOOP);
    }

    @Test
    void classifiesUnknownPath() {
        Path path = Path.of("/some/random/place/camel");

        InstallDetector.InstallMethod method = InstallDetector.classify(path, Path.of("/unrelated"));

        assertThat(method).isEqualTo(InstallDetector.InstallMethod.UNKNOWN);
    }

    @Test
    void resolveActiveOnPathFindsExecutableOnPath(@TempDir Path temp) throws Exception {
        String exeName = FileUtil.isWindows() ? "camel.cmd" : "camel";
        Path binDir = Files.createDirectories(temp.resolve("bin"));
        Path exe = Files.createFile(binDir.resolve(exeName));
        exe.toFile().setExecutable(true);

        var result = InstallDetector.resolveActiveOnPath(binDir + File.pathSeparator + "/nonexistent");

        assertThat(result).contains(exe);
    }

    @Test
    void resolveActiveOnPathReturnsEmptyWhenNotFound() {
        var result = InstallDetector.resolveActiveOnPath("/nonexistent/dir-a" + File.pathSeparator + "/nonexistent/dir-b");

        assertThat(result).isEmpty();
    }
}
```

Note: `classify(Path, Path)` and `resolveActiveOnPath(String)` are package-private overloads that take the web-installer versions root / `PATH` value explicitly, so the test never touches the real `$HOME`/`PATH`. The public, zero-arg `locate()`/`scanKnownLocations()`/`resolveActiveOnPath()` API calls these with real environment values.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnd test -pl dsl/camel-jbang/camel-jbang-core -Dtest=InstallDetectorTest -Dci.env.name=local`
Expected: FAIL — compilation error, `InstallDetector` does not exist.

- [ ] **Step 3: Write the implementation**

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
package org.apache.camel.dsl.jbang.core.common;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import org.apache.camel.util.FileUtil;

/**
 * Detects where the currently running Camel CLI launcher lives, and discovers other Camel CLI installations on the
 * same machine, across the web installer and the package managers the launcher is also distributed through
 * (Homebrew, Chocolatey, WinGet, Scoop, SDKMAN) or JBang. Used by {@code camel self-update} to refuse acting on a
 * non-web-installer install, and by {@code camel doctor} to report conflicting installations.
 */
public final class InstallDetector {

    public enum InstallMethod {
        WEB_INSTALLER,
        HOMEBREW,
        CHOCOLATEY,
        WINGET,
        SCOOP,
        SDKMAN,
        JBANG,
        UNKNOWN
    }

    public record InstallInfo(Path location, InstallMethod method) {
    }

    private InstallDetector() {
    }

    /**
     * Determines where the currently running launcher lives, by resolving the path of the JAR backing this JVM
     * process (set as the {@code camel.launcher.jar} system property by {@code CamelLauncher.main()}).
     */
    public static InstallInfo locate() {
        String jarPath = System.getProperty("camel.launcher.jar");
        if (jarPath == null || jarPath.isBlank()) {
            return new InstallInfo(null, InstallMethod.UNKNOWN);
        }
        Path path = Paths.get(jarPath);
        return new InstallInfo(path, classify(path, webInstallerVersionsRoot()));
    }

    // Package-private overload: takes the web-installer versions root explicitly so tests never touch the real
    // $HOME/$LOCALAPPDATA.
    static InstallMethod classify(Path path, Path webInstallerVersionsRoot) {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.startsWith(webInstallerVersionsRoot.toAbsolutePath().normalize())) {
            return InstallMethod.WEB_INSTALLER;
        }
        String normalized = absolute.toString().replace('\\', '/');
        if (normalized.contains("/Cellar/")) {
            return InstallMethod.HOMEBREW;
        }
        if (normalized.toLowerCase().contains("/chocolatey/")) {
            return InstallMethod.CHOCOLATEY;
        }
        if (normalized.contains("/WinGet/Packages/")) {
            return InstallMethod.WINGET;
        }
        if (normalized.toLowerCase().contains("/scoop/apps/")) {
            return InstallMethod.SCOOP;
        }
        if (normalized.contains("/.sdkman/candidates/")) {
            return InstallMethod.SDKMAN;
        }
        if (normalized.contains("/.jbang/")) {
            return InstallMethod.JBANG;
        }
        return InstallMethod.UNKNOWN;
    }

    /**
     * Root directory the web installer extracts version directories into:
     * {@code ${XDG_DATA_HOME:-$HOME/.local/share}/camel-cli/versions} on POSIX,
     * {@code %LOCALAPPDATA%\Apache Camel\cli\versions} on Windows.
     */
    public static Path webInstallerVersionsRoot() {
        if (FileUtil.isWindows()) {
            return Paths.get(System.getenv("LOCALAPPDATA"), "Apache Camel", "cli", "versions");
        }
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        Path dataHome = xdgDataHome != null && !xdgDataHome.isBlank()
                ? Paths.get(xdgDataHome) : Paths.get(System.getProperty("user.home"), ".local", "share");
        return dataHome.resolve("camel-cli").resolve("versions");
    }

    /**
     * Probes every known install location for the presence of a {@code camel} executable, regardless of which one
     * the current process happens to be running from. Best-effort: an install method whose root cannot be
     * determined deterministically (WinGet, whose folder name embeds a publisher hash) is not scanned here and is
     * only ever reported via {@link #resolveActiveOnPath()}.
     */
    public static List<InstallInfo> scanKnownLocations() {
        List<InstallInfo> found = new ArrayList<>();
        scanWebInstaller(found);
        if (FileUtil.isWindows()) {
            scanChocolatey(found);
            scanScoopWindows(found);
        } else {
            scanHomebrew(found);
            scanSdkman(found);
            scanJBang(found);
        }
        return found;
    }

    private static void scanWebInstaller(List<InstallInfo> found) {
        Path root = webInstallerVersionsRoot();
        listVersionDirs(root, dir -> Files.exists(dir.resolve("bin").resolve(FileUtil.isWindows() ? "camel.bat" : "camel.sh")))
                .forEach(dir -> found.add(new InstallInfo(dir, InstallMethod.WEB_INSTALLER)));
    }

    private static void scanHomebrew(List<InstallInfo> found) {
        // "apache-camel", not "camel-cli": the Homebrew formula is renamed as part of this same
        // branch of work (see the Homebrew Distribution tasks later in this plan) - the installed
        // Cellar keg directory is always named after the formula, never the JReleaser distribution id.
        for (String prefix : List.of("/opt/homebrew", "/usr/local", "/home/linuxbrew/.linuxbrew")) {
            Path cellar = Paths.get(prefix, "Cellar", "apache-camel");
            listVersionDirs(cellar, dir -> Files.exists(dir.resolve("libexec").resolve("bin").resolve("camel.sh")))
                    .forEach(dir -> found.add(new InstallInfo(dir.resolve("libexec"), InstallMethod.HOMEBREW)));
        }
    }

    private static void scanSdkman(List<InstallInfo> found) {
        Path candidates = Paths.get(System.getProperty("user.home"), ".sdkman", "candidates", "camel");
        listVersionDirs(candidates, dir -> Files.exists(dir.resolve("bin").resolve("camel.sh")))
                .forEach(dir -> found.add(new InstallInfo(dir, InstallMethod.SDKMAN)));
    }

    private static void scanJBang(List<InstallInfo> found) {
        Path shim = Paths.get(System.getProperty("user.home"), ".jbang", "bin", "camel");
        if (Files.exists(shim)) {
            found.add(new InstallInfo(shim, InstallMethod.JBANG));
        }
    }

    private static void scanChocolatey(List<InstallInfo> found) {
        Path tools = Paths.get("C:\\ProgramData\\chocolatey\\lib\\camel-cli\\tools");
        if (Files.exists(tools.resolve("camel.bat"))) {
            found.add(new InstallInfo(tools, InstallMethod.CHOCOLATEY));
        }
    }

    private static void scanScoopWindows(List<InstallInfo> found) {
        String scoopHome = System.getenv("SCOOP");
        Path apps = Paths.get(scoopHome != null && !scoopHome.isBlank() ? scoopHome
                : Paths.get(System.getProperty("user.home"), "scoop").toString(), "apps", "camel-cli");
        listVersionDirs(apps, dir -> Files.exists(dir.resolve("bin").resolve("camel.bat")))
                .forEach(dir -> found.add(new InstallInfo(dir, InstallMethod.SCOOP)));
    }

    private static List<Path> listVersionDirs(Path root, Predicate<Path> hasLauncher) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory).filter(hasLauncher).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Walks {@code PATH} the way a shell would and returns the first {@code camel} executable found.
     */
    public static Optional<Path> resolveActiveOnPath() {
        String path = System.getenv("PATH");
        return resolveActiveOnPath(path);
    }

    static Optional<Path> resolveActiveOnPath(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String exeName = FileUtil.isWindows() ? "camel.cmd" : "camel";
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Paths.get(dir, exeName);
            if (Files.exists(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvnd test -pl dsl/camel-jbang/camel-jbang-core -Dtest=InstallDetectorTest -Dci.env.name=local`
Expected: PASS — all 9 tests green.

- [ ] **Step 5: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-jbang-core -Dci.env.name=local
git add dsl/camel-jbang/camel-jbang-core/src/main/java/org/apache/camel/dsl/jbang/core/common/InstallDetector.java \
        dsl/camel-jbang/camel-jbang-core/src/test/java/org/apache/camel/dsl/jbang/core/common/InstallDetectorTest.java
git commit -m "CAMEL-23703: Add InstallDetector for locating Camel CLI installations"
```

---

### Task 2: Extend `camel doctor` with install-location conflict detection

**Files:**
- Modify: `dsl/camel-jbang/camel-jbang-core/src/main/java/org/apache/camel/dsl/jbang/core/commands/Doctor.java`
- Modify: `dsl/camel-jbang/camel-jbang-core/src/test/java/org/apache/camel/dsl/jbang/core/commands/DoctorTest.java`

**Interfaces:**
- Consumes: `InstallDetector.scanKnownLocations()`, `InstallDetector.resolveActiveOnPath()`, `InstallDetector.InstallInfo` (Task 1). Gate: `"true".equals(System.getProperty("camel.launcher"))`, set unconditionally by `CamelLauncher.main()` — this means the check is silently skipped when `camel doctor` is invoked from the plain JBang-based CLI (`camel-jbang-core` without the native launcher), where there is no web-installer version-directory concept to conflict against.
- Produces: `Doctor.doCall()` now returns `1` (not always `0`) when more than one installation is found, matching the "multiple installations found -> exit non-zero" contract from the design and the `brew doctor`/`flutter doctor` convention it's modeled on. Also produces a package-visible `Doctor.checkInstallLocations(List<InstallDetector.InstallInfo>, Optional<Path>)` overload, used directly by this task's own new tests to exercise the conflict-detection contract against controlled data instead of the real disk.

- [ ] **Step 1: Write the failing tests**

Add these two methods to the end of `DoctorTest.java` (inside the existing `class DoctorTest extends CamelCommandBaseTestSupport { ... }` body, before the closing brace), leaving every existing method untouched:

```java
    @Test
    void skipsInstallCheckOutsideLauncher() throws Exception {
        System.clearProperty("camel.launcher");
        Doctor command = new Doctor(new CamelJBangMain().withPrinter(printer));

        int exit = command.doCall();

        assertThat(exit).isZero();
        assertThat(printer.getOutput()).doesNotContain("Camel CLI installations");
    }

    @Test
    void reportsSingleInstallAsOkUnderLauncher() throws Exception {
        System.setProperty("camel.launcher", "true");
        try {
            Doctor command = new Doctor(new CamelJBangMain().withPrinter(printer));

            int exit = command.doCall();

            assertThat(printer.getOutput()).contains("Installs:");
        } finally {
            System.clearProperty("camel.launcher");
        }
    }

    @Test
    void exitsNonZeroWhenMultipleInstallsFound() {
        Path locationA = Path.of("/home/user/.local/share/camel-cli/versions/4.22.0");
        Path locationB = Path.of("/opt/homebrew/Cellar/apache-camel/4.21.0/libexec");
        List<InstallDetector.InstallInfo> installs = List.of(
                new InstallDetector.InstallInfo(locationA, InstallDetector.InstallMethod.WEB_INSTALLER),
                new InstallDetector.InstallInfo(locationB, InstallDetector.InstallMethod.HOMEBREW));
        Doctor command = new Doctor(new CamelJBangMain().withPrinter(printer));

        boolean conflict = command.checkInstallLocations(installs, Optional.of(locationA.resolve("bin/camel.sh")));

        assertThat(conflict).isTrue();
        assertThat(printer.getOutput()).contains("Found 2 Camel CLI installations")
                .contains("Warning: more than one Camel CLI installation was found");
    }

    @Test
    void reportsNoConflictWhenSingleInstallFound() {
        Path location = Path.of("/home/user/.local/share/camel-cli/versions/4.22.0");
        List<InstallDetector.InstallInfo> installs = List.of(
                new InstallDetector.InstallInfo(location, InstallDetector.InstallMethod.WEB_INSTALLER));
        Doctor command = new Doctor(new CamelJBangMain().withPrinter(printer));

        boolean conflict = command.checkInstallLocations(installs, Optional.of(location.resolve("bin/camel.sh")));

        assertThat(conflict).isFalse();
        assertThat(printer.getOutput()).contains("Found 1 Camel CLI installation")
                .doesNotContain("Warning: more than one");
    }
```

Add these imports at the top of the file, next to the existing `org.junit.jupiter.api.Assertions`/`org.junit.jupiter.api.Test`/`java.util.List` imports:

```java
import java.nio.file.Path;
import java.util.Optional;

import org.apache.camel.dsl.jbang.core.common.InstallDetector;

import static org.assertj.core.api.Assertions.assertThat;
```

(`reportsSingleInstallAsOkUnderLauncher` deliberately does not assert on `exit` — on the machine actually running this test suite, `InstallDetector.scanKnownLocations()` may legitimately find zero, one, or more installations depending on what's on disk, so the only universally-true assertion is that the new "Installs:" section header is printed at all when the gate is open. `exitsNonZeroWhenMultipleInstallsFound`/`reportsNoConflictWhenSingleInstallFound` are where the actual "more than one found -> exit 1" / "one found -> exit 0" contract from the design is exercised, against fully controlled data via the new package-visible overload added in Step 3 below — the real disk scan is deliberately not used for that assertion.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnd test -pl dsl/camel-jbang/camel-jbang-core -Dtest=DoctorTest -Dci.env.name=local`
Expected: FAIL — `reportsSingleInstallAsOkUnderLauncher` fails because `"Installs:"` is never printed yet.

- [ ] **Step 3: Implement the check in `Doctor.java`**

In `Doctor.java`, add these imports next to the existing ones (`java.io.File`, `java.util.List`, `java.util.Set` are already there):

```java
import java.nio.file.Path;
import java.util.Optional;

import org.apache.camel.dsl.jbang.core.common.InstallDetector;
```

Change `doCall()` from:

```java
    @Override
    public Integer doCall() throws Exception {
        printer().println("Camel CLI Doctor");
        printer().println("==================");
        printer().println();

        checkCamelVersion();
        checkJava();
        checkJBang();
        checkMavenRepository();
        checkContainerRuntime();
        checkCommonPorts();
        checkDiskSpace();

        return 0;
    }
```

to:

```java
    @Override
    public Integer doCall() throws Exception {
        printer().println("Camel CLI Doctor");
        printer().println("==================");
        printer().println();

        checkCamelVersion();
        checkJava();
        checkJBang();
        checkMavenRepository();
        checkContainerRuntime();
        checkCommonPorts();
        checkDiskSpace();

        boolean conflict = false;
        if ("true".equals(System.getProperty("camel.launcher"))) {
            conflict = checkInstallLocations();
        }

        return conflict ? 1 : 0;
    }
```

Add the new check method, placed after `checkDiskSpace()`:

```java
    // Only meaningful when running via the native launcher (camel.launcher=true) — the web-installer
    // version-directory layout InstallDetector understands doesn't exist for the plain JBang-based CLI.
    private boolean checkInstallLocations() {
        return checkInstallLocations(InstallDetector.scanKnownLocations(), InstallDetector.resolveActiveOnPath());
    }

    // Package-visible overload taking the scan result directly: lets DoctorTest exercise the
    // "more than one installation found -> conflict" contract against fully controlled data, rather than
    // whatever InstallDetector.scanKnownLocations() happens to find on the machine running the test suite.
    boolean checkInstallLocations(List<InstallDetector.InstallInfo> installs, Optional<Path> active) {
        printer().println();
        printer().printf("Installs:    Found %d Camel CLI installation%s%n", installs.size(), installs.size() == 1 ? "" : "s");
        for (InstallDetector.InstallInfo install : installs) {
            boolean isActive = active.isPresent() && install.location() != null
                    && active.get().toAbsolutePath().normalize().startsWith(install.location().toAbsolutePath().normalize());
            printer().printf("             %s (%s)%s%n", install.location(), install.method(), isActive ? " <- active" : "");
        }

        if (installs.size() > 1) {
            printer().println(
                    "Warning: more than one Camel CLI installation was found. The one marked active is the one your "
                                + "shell currently runs; the others are unused but still present. See "
                                + "camel-jbang-launcher-install.adoc for how each installation method is normally removed.");
            return true;
        }
        return false;
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvnd test -pl dsl/camel-jbang/camel-jbang-core -Dtest=DoctorTest -Dci.env.name=local`
Expected: PASS — all `DoctorTest` methods (existing + 2 new) green.

- [ ] **Step 5: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-jbang-core -Dci.env.name=local
git add dsl/camel-jbang/camel-jbang-core/src/main/java/org/apache/camel/dsl/jbang/core/commands/Doctor.java \
        dsl/camel-jbang/camel-jbang-core/src/test/java/org/apache/camel/dsl/jbang/core/commands/DoctorTest.java
git commit -m "CAMEL-23703: camel doctor reports conflicting Camel CLI installations"
```

---

### Task 3: `ManifestFetcher` (manifest fetch, parse, validate)

**Files:**
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ManifestFetcher.java`
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdateException.java`
- Test: `dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ManifestFetcherTest.java`

**Interfaces:**
- Produces: `ManifestFetcher.Manifest` (record `(String version, String tarSha256, String zipSha256)`), `ManifestFetcher.fromEnvironment()`, `new ManifestFetcher(String manifestBaseUrl, String mavenBaseUrl)`, `.fetchLatest()`, `.fetch(String version)`, `.downloadArchive(String version, Path target)`. Consumed by Task 5 (`SelfUpdateCommand`) and Task 6 (`UpdateChecker`, which only calls `fetchLatest()`).
- Consumes: nothing from earlier tasks (first new class in `camel-launcher`).

Mirrors `install.sh`'s `parse_manifest` (lines 100-156) exactly, per the "Global Constraints" correction above: exactly 4 lines, each a non-blank `key=value` pair, no duplicate keys, no unknown keys, all 4 required keys present, `format` must equal `"1"`, `version` must match `X.Y.Z` (three dot-separated all-digit components), both SHA-256 fields must be exactly 64 lowercase-hex characters. CRLF line endings are tolerated (trailing `\r` stripped per line) because `install.ps1`'s `Read-Manifest` produces/accepts them and a manifest may be re-served through either path.

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
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fetches and parses the website-installer manifest ({@code latest.properties}/{@code X.Y.Z.properties}), and
 * downloads the release archive from Maven Central. Mirrors {@code install.sh}'s {@code parse_manifest} and
 * {@code fetch} functions exactly, in Java.
 */
public final class ManifestFetcher {

    private static final String DEFAULT_MANIFEST_BASE_URL = "https://camel.apache.org/camel-cli/releases";
    private static final String DEFAULT_MAVEN_BASE_URL = "https://repo1.maven.org/maven2/org/apache/camel/camel-launcher";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

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

    public void downloadArchive(String version, Path target) {
        String url = mavenBaseUrl + "/" + version + "/camel-launcher-" + version + "-bin.zip";
        try {
            HttpClient client = HttpClient.newBuilder().proxy(ProxySelector.getDefault()).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(READ_TIMEOUT).build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
            if (response.statusCode() != 200) {
                throw new SelfUpdateException("failed to download archive from " + url + " (HTTP " + response.statusCode() + ")");
            }
        } catch (SelfUpdateException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw new SelfUpdateException("failed to download archive from " + url, e);
        }
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
        // A trailing newline produces one empty trailing element; drop it before the line-count check.
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
Expected: PASS — all 19 parameterized + fixed cases green.

- [ ] **Step 6: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local
git add dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ManifestFetcher.java \
        dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdateException.java \
        dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ManifestFetcherTest.java
git commit -m "CAMEL-23703: Add ManifestFetcher for camel self-update"
```

---

### Task 4: Promote `commons-compress` scope; `ZipArchiveValidator`

**Files:**
- Modify: `dsl/camel-jbang/camel-launcher/pom.xml`
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ZipArchiveValidator.java`
- Test: `dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ZipArchiveValidatorTest.java`

**Interfaces:**
- Produces: `ZipArchiveValidator.validate(Path archive, String expectedVersion)` — `static void validate(...) throws SelfUpdateException` (Task 3's exception type). Consumed by Task 5 (`SelfUpdateCommand`).
- Consumes: `org.apache.commons.compress.archivers.zip.ZipFile`/`ZipArchiveEntry` (now compile-scope, see Global Constraints item 3), and the package-private `WebsiteInstallerFixture` (Task 7's test package) for its malicious-archive builders — but this task's own test builds its own archives inline (see Step 1) so it has no dependency on that fixture and can run standalone.

Mirrors `install.ps1`'s `Test-ArchiveEntry` (lines 132-177 of `install.ps1`) — the *zip*-specific validator, since `install.sh`'s `validate_tar` only ever validates the `.tar.gz`, and `SelfUpdateCommand` (Task 5) always fetches the `.zip` regardless of OS, per the design's explicit "always fetches the `.zip` archive, even on Linux/macOS" decision (`java.util.zip`/Commons Compress handle it with no new external process). Rejects: absolute paths, `../` traversal segments, symlink entries (Unix external-attribute mode `0xA000`, read via `ZipArchiveEntry.getUnixMode()`), more than one top-level directory, and a missing expected launcher entry (`bin/camel.sh`, since the archive layout is identical regardless of which OS the update is running on — only the *installed* activation step, Task 5, branches on OS).

- [ ] **Step 1: Promote `commons-compress` scope**

In `dsl/camel-jbang/camel-launcher/pom.xml`, change:

```xml
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-compress</artifactId>
            <version>${commons-compress-version}</version>
            <scope>test</scope>
        </dependency>
```

to (delete the `<scope>test</scope>` line — default scope is `compile`):

```xml
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-compress</artifactId>
            <version>${commons-compress-version}</version>
        </dependency>
```

- [ ] **Step 2: Write the failing test**

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
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipArchiveValidatorTest {

    private static final String VERSION = "9.9.9";
    private static final String ROOT = "camel-launcher-" + VERSION;

    private void addFile(ZipArchiveOutputStream zaos, String entryName, byte[] content) throws Exception {
        ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
        zaos.putArchiveEntry(entry);
        zaos.write(content);
        zaos.closeArchiveEntry();
    }

    private void addSymlink(ZipArchiveOutputStream zaos, String entryName, String target) throws Exception {
        ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
        entry.setUnixMode(0120777);
        zaos.putArchiveEntry(entry);
        zaos.write(target.getBytes(StandardCharsets.UTF_8));
        zaos.closeArchiveEntry();
    }

    private Path safeArchive(Path temp) throws Exception {
        Path archive = temp.resolve("safe.zip");
        try (var out = Files.newOutputStream(archive); var zaos = new ZipArchiveOutputStream(out)) {
            addFile(zaos, ROOT + "/bin/camel.sh", "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8));
            addFile(zaos, ROOT + "/bin/camel.bat", "@echo off\r\n".getBytes(StandardCharsets.UTF_8));
        }
        return archive;
    }

    @Test
    void acceptsSafeArchive(@TempDir Path temp) throws Exception {
        Path archive = safeArchive(temp);

        assertThatCode(() -> ZipArchiveValidator.validate(archive, VERSION)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAbsolutePathEntry(@TempDir Path temp) throws Exception {
        Path archive = temp.resolve("malicious.zip");
        try (var out = Files.newOutputStream(archive); var zaos = new ZipArchiveOutputStream(out)) {
            addFile(zaos, ROOT + "/bin/camel.sh", "x".getBytes(StandardCharsets.UTF_8));
            addFile(zaos, "/etc/passwd", "x".getBytes(StandardCharsets.UTF_8));
        }

        assertThatThrownBy(() -> ZipArchiveValidator.validate(archive, VERSION)).isInstanceOf(SelfUpdateException.class);
    }

    @Test
    void rejectsPathTraversalEntry(@TempDir Path temp) throws Exception {
        Path archive = temp.resolve("malicious.zip");
        try (var out = Files.newOutputStream(archive); var zaos = new ZipArchiveOutputStream(out)) {
            addFile(zaos, ROOT + "/bin/camel.sh", "x".getBytes(StandardCharsets.UTF_8));
            addFile(zaos, ROOT + "/../../etc/passwd", "x".getBytes(StandardCharsets.UTF_8));
        }

        assertThatThrownBy(() -> ZipArchiveValidator.validate(archive, VERSION)).isInstanceOf(SelfUpdateException.class);
    }

    @Test
    void rejectsSymlinkEntry(@TempDir Path temp) throws Exception {
        Path archive = temp.resolve("malicious.zip");
        try (var out = Files.newOutputStream(archive); var zaos = new ZipArchiveOutputStream(out)) {
            addFile(zaos, ROOT + "/bin/camel.sh", "x".getBytes(StandardCharsets.UTF_8));
            addSymlink(zaos, ROOT + "/evil-link", "/etc/passwd");
        }

        assertThatThrownBy(() -> ZipArchiveValidator.validate(archive, VERSION)).isInstanceOf(SelfUpdateException.class);
    }

    @Test
    void rejectsMultipleTopLevelDirectories(@TempDir Path temp) throws Exception {
        Path archive = temp.resolve("malicious.zip");
        try (var out = Files.newOutputStream(archive); var zaos = new ZipArchiveOutputStream(out)) {
            addFile(zaos, ROOT + "/bin/camel.sh", "x".getBytes(StandardCharsets.UTF_8));
            addFile(zaos, "some-other-root/file.txt", "x".getBytes(StandardCharsets.UTF_8));
        }

        assertThatThrownBy(() -> ZipArchiveValidator.validate(archive, VERSION)).isInstanceOf(SelfUpdateException.class);
    }

    @Test
    void rejectsMissingCamelSh(@TempDir Path temp) throws Exception {
        Path archive = temp.resolve("malicious.zip");
        try (var out = Files.newOutputStream(archive); var zaos = new ZipArchiveOutputStream(out)) {
            addFile(zaos, ROOT + "/bin/camel.bat", "x".getBytes(StandardCharsets.UTF_8));
        }

        assertThatThrownBy(() -> ZipArchiveValidator.validate(archive, VERSION)).isInstanceOf(SelfUpdateException.class);
    }

    @Test
    void rejectsWrongVersionRoot(@TempDir Path temp) throws Exception {
        Path archive = temp.resolve("malicious.zip");
        try (var out = Files.newOutputStream(archive); var zaos = new ZipArchiveOutputStream(out)) {
            addFile(zaos, "camel-launcher-1.0.0/bin/camel.sh", "x".getBytes(StandardCharsets.UTF_8));
        }

        assertThatThrownBy(() -> ZipArchiveValidator.validate(archive, VERSION)).isInstanceOf(SelfUpdateException.class);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=ZipArchiveValidatorTest -Dci.env.name=local`
Expected: FAIL — compilation error, `ZipArchiveValidator` does not exist. (If `commons-compress` scope wasn't actually widened in Step 1, this step instead fails to compile the *test* module with a `test`-scope-only classpath error confirming Step 1 is required — either way, confirm Step 1 was applied before moving on.)

- [ ] **Step 4: Write `ZipArchiveValidator`**

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

import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipFileBuilder;

/**
 * Validates a downloaded {@code camel-launcher-<version>-bin.zip} archive's entries before anything is extracted.
 * Mirrors {@code install.ps1}'s {@code Test-ArchiveEntry}: rejects absolute paths, {@code ../} traversal, symlink
 * entries (detected via the Unix external-attributes mode bit, which plain {@code java.util.zip} cannot read —
 * see the design deviations note in this plan), more than one top-level directory, and a missing launcher entry.
 */
public final class ZipArchiveValidator {

    private static final int UNIX_MODE_MASK = 0xF000;
    private static final int UNIX_MODE_SYMLINK = 0xA000;

    private ZipArchiveValidator() {
    }

    public static void validate(Path archive, String expectedVersion) {
        String expectedRoot = "camel-launcher-" + expectedVersion;
        Set<String> roots = new HashSet<>();
        boolean foundLauncher = false;

        try (ZipFile zip = ZipFileBuilder.builder().setPath(archive).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.isEmpty()) {
                    continue;
                }
                if (name.startsWith("/") || name.startsWith("\\") || (name.length() >= 2 && name.charAt(1) == ':')) {
                    throw new SelfUpdateException("archive contains an absolute path entry: " + name);
                }
                String normalized = name.replace('\\', '/');
                String[] segments = normalized.split("/");
                for (String segment : segments) {
                    if ("..".equals(segment)) {
                        throw new SelfUpdateException("archive contains a path traversal entry: " + name);
                    }
                }
                if ((entry.getUnixMode() & UNIX_MODE_MASK) == UNIX_MODE_SYMLINK) {
                    throw new SelfUpdateException("archive contains a symbolic link entry, which is not allowed: " + name);
                }
                roots.add(segments[0]);
                if (normalized.equals(expectedRoot + "/bin/camel.sh")) {
                    foundLauncher = true;
                }
            }
        } catch (SelfUpdateException e) {
            throw e;
        } catch (Exception e) {
            throw new SelfUpdateException("archive is not a valid zip file", e);
        }

        if (roots.size() != 1) {
            throw new SelfUpdateException("archive must contain exactly one top-level directory");
        }
        if (!roots.contains(expectedRoot)) {
            throw new SelfUpdateException("archive top-level directory does not match expected version: " + roots);
        }
        if (!foundLauncher) {
            throw new SelfUpdateException("archive is missing bin/camel.sh");
        }
    }
}
```

Note: `ZipFileBuilder`/`ZipFile.getEntries()` is the current Commons Compress API for reading an on-disk zip with random access (needed to enumerate `ZipArchiveEntry` and its Unix mode, which the streaming `ZipArchiveInputStream` also exposes but `ZipFile` is the simpler read of a fully-downloaded local file). Confirm the exact builder class name against the `commons-compress-version` property pinned in the root `pom.xml` when implementing this step — Commons Compress has changed this construction API across major versions before (the older `new ZipFile(File)` constructor was deprecated in favor of a builder); use whichever of the two the pinned version actually ships, matching what `WebsiteInstallerFixture.java`'s existing imports already use for the *write* side (`ZipArchiveOutputStream`/`ZipArchiveEntry`) as a version-compatibility cross-check.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=ZipArchiveValidatorTest -Dci.env.name=local`
Expected: PASS — all 7 tests green.

- [ ] **Step 6: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local
git add dsl/camel-jbang/camel-launcher/pom.xml \
        dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ZipArchiveValidator.java \
        dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/ZipArchiveValidatorTest.java
git commit -m "CAMEL-23703: Add ZipArchiveValidator; promote commons-compress to compile scope"
```

---

### Task 5: `SelfUpdateCommand` and `SelfUpdatePlugin`

**Files:**
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdateCommand.java`
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdatePlugin.java`
- Modify: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/CamelLauncherMain.java`

**Interfaces:**
- Consumes: `InstallDetector.locate()`/`InstallDetector.webInstallerVersionsRoot()` (Task 1), `ManifestFetcher.fromEnvironment()`/`.fetch()`/`.fetchLatest()`/`.downloadArchive()`/`Manifest` (Task 3), `ZipArchiveValidator.validate()` (Task 4), `VersionHelper.compare()` (existing, `org.apache.camel.dsl.jbang.core.common.VersionHelper`), `DefaultCamelCatalog().getCatalogVersion()` (existing), `CamelCommand` (existing base class, `doCall()`/`printer()` contract), `Plugin`/`CamelJBangMain` (existing SPI).
- Produces: the `camel self-update` subcommand, registered by `SelfUpdatePlugin.customize(...)`.

The full update sequence, mirroring `install.sh`'s bottom-of-file flow (lines 223-291) and `activate()` (lines 207-221) / `verify_staged()` (lines 199-205), in Java:

- [ ] **Step 1: Write `SelfUpdateCommand`**

(No isolated unit test for this class here — it orchestrates network I/O, file extraction, and OS-level activation, none of which is meaningfully unit-testable in isolation from the pieces already covered in Tasks 1/3/4. Task 7 covers it end-to-end against a local HTTPS fixture, which is the appropriate test level for this class, matching how `WebsiteInstallTest` covers `install.sh`/`install.ps1` end-to-end rather than unit-testing their internal functions individually.)

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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    public SelfUpdateCommand(CamelJBangMain main) {
        super(main);
    }

    @Override
    public Integer doCall() throws Exception {
        InstallDetector.InstallInfo info = InstallDetector.locate();
        if (info.method() != InstallDetector.InstallMethod.WEB_INSTALLER) {
            printer().printErr(refusalMessage(info.method()));
            return 1;
        }

        ManifestFetcher fetcher = ManifestFetcher.fromEnvironment();
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

        return installVersion(fetcher, manifest);
    }

    private Integer installVersion(ManifestFetcher fetcher, ManifestFetcher.Manifest manifest) throws Exception {
        Path stagingDir = Files.createTempDirectory("camel-self-update-");
        try {
            Path archive = stagingDir.resolve("camel-launcher-" + manifest.version() + "-bin.zip");
            fetcher.downloadArchive(manifest.version(), archive);

            String actualSha256 = sha256Hex(archive);
            if (!actualSha256.equals(manifest.zipSha256())) {
                printer().printErr("checksum mismatch for downloaded archive");
                return 1;
            }

            ZipArchiveValidator.validate(archive, manifest.version());

            Path extractDir = stagingDir.resolve("extract");
            Files.createDirectories(extractDir);
            unzip(archive, extractDir);

            Path stagedRoot = extractDir.resolve("camel-launcher-" + manifest.version());
            Path stagedLauncher = stagedRoot.resolve("bin").resolve(FileUtil.isWindows() ? "camel.bat" : "camel.sh");
            verifyStaged(stagedLauncher);

            activate(manifest.version(), stagedRoot);

            printer().println("Installed Camel CLI " + manifest.version() + " to "
                               + InstallDetector.webInstallerVersionsRoot().resolve(manifest.version()));
            return 0;
        } catch (SelfUpdateException e) {
            printer().printErr(e.getMessage());
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

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void unzip(Path archive, Path targetDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
        makeShellScriptsExecutable(targetDir);
    }

    private static void makeShellScriptsExecutable(Path targetDir) throws Exception {
        if (FileUtil.isWindows()) {
            return;
        }
        try (var stream = Files.walk(targetDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".sh"))
                    .forEach(p -> p.toFile().setExecutable(true));
        }
    }

    // Runs the freshly staged launcher; a nonzero exit (e.g. no Java 17+ available) aborts the update and leaves
    // the previously active installation untouched — mirrors install.sh's verify_staged().
    private static void verifyStaged(Path stagedLauncher) throws Exception {
        if (!FileUtil.isWindows()) {
            stagedLauncher.toFile().setExecutable(true);
        }
        ProcessBuilder pb = FileUtil.isWindows()
                ? new ProcessBuilder(stagedLauncher.toString(), "version")
                : new ProcessBuilder("/bin/sh", stagedLauncher.toString(), "version");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new SelfUpdateException("staged launcher failed verification (Java 17+ required)");
        }
    }

    // Mirrors install.sh's activate(): move the staged root into place, then atomically swap the
    // symlink (POSIX) or rewrite the .cmd shim (Windows). The currently-running process, reading from the old
    // version's still-intact files, is never touched mid-update.
    private static void activate(String version, Path stagedRoot) throws Exception {
        Path versionsRoot = InstallDetector.webInstallerVersionsRoot();
        Files.createDirectories(versionsRoot);
        Path targetDir = versionsRoot.resolve(version);
        deleteRecursively(targetDir);
        Files.move(stagedRoot, targetDir);

        if (FileUtil.isWindows()) {
            activateWindows(targetDir);
        } else {
            activatePosix(targetDir);
        }
    }

    private static void activatePosix(Path targetDir) throws Exception {
        Path binDir = Path.of(System.getProperty("user.home"), ".local", "bin");
        Files.createDirectories(binDir);
        Path tmpLink = binDir.resolve(".camel.tmp." + ProcessHandle.current().pid());
        Files.deleteIfExists(tmpLink);
        Files.createSymbolicLink(tmpLink, targetDir.resolve("bin").resolve("camel.sh"));
        Files.move(tmpLink, binDir.resolve("camel"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void activateWindows(Path targetDir) throws Exception {
        Path binDir = Path.of(System.getenv("LOCALAPPDATA"), "Apache Camel", "bin");
        Files.createDirectories(binDir);
        Path launcherPath = targetDir.resolve("bin").resolve("camel.bat");
        String shimContent = "@echo off\r\ncall \"" + launcherPath + "\" %*\r\nexit /b %ERRORLEVEL%\r\n";
        Path tempShim = binDir.resolve(".camel." + ProcessHandle.current().pid() + ".tmp.cmd");
        Files.writeString(tempShim, shimContent, StandardCharsets.UTF_8);
        Files.move(tempShim, binDir.resolve("camel.cmd"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
```

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

(No `@CamelJBangPlugin` annotation: that annotation marks a command as installable via `camel plugin add` for the *plain* JBang-based CLI's optional-plugin mechanism — `KubernetesPlugin`/`TuiPlugin` use it because those plugins are also distributed as separate `camel-jbang-plugin-*` Maven modules a JBang user can opt into. `self-update` only exists in the `camel-launcher` module and is embedded unconditionally, same treatment as `GeneratePlugin`/`ValidatePlugin`/`TestPlugin` already registered in `CamelLauncherMain`.)

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
Expected: picocli usage help for `self-update` printed, showing `--version` and `--check` options — confirms the command registers without a `DuplicateNameException` and without colliding with the pre-existing `doctor` registration (there is no `self-update` name collision, but this also exercises that `postAddCommands()` itself still runs cleanly end-to-end after the Task 2 changes).

- [ ] **Step 5: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local
git add dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdateCommand.java \
        dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdatePlugin.java \
        dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/CamelLauncherMain.java
git commit -m "CAMEL-23703: Add camel self-update command"
```

---

### Task 6: `UpdateChecker` (background notice)

**Files:**
- Create: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/UpdateChecker.java`
- Test: `dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/UpdateCheckerTest.java`
- Modify: `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/CamelLauncherMain.java`

**Interfaces:**
- Consumes: `InstallDetector.locate()` (Task 1), `ManifestFetcher.fromEnvironment().fetchLatest()` (Task 3), `VersionHelper.compare()`, `DefaultCamelCatalog().getCatalogVersion()`.
- Produces: `UpdateChecker.maybeNotify(String[] args)`, called from a new `CamelLauncherMain.preExecute(CommandLine, String[])` override.

`Printer.printErr(...)` was ruled out for the notice: its default implementation (`Printer.java`) prepends a hardcoded `"ERROR: "` prefix and, in this codebase's `SystemOutPrinter`, actually writes through `System.out`, not a real separate stderr stream — neither matches the plain, non-error, genuinely-stderr notice line the design specifies (`camel: a new version is available (...)`) or is worth extending the shared `Printer` interface for a single caller. `UpdateChecker` writes directly to `System.err`.

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

import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateCheckerTest {

    @Test
    void formatsNoticeWhenNewerVersionCached() {
        String notice = UpdateChecker.formatNotice("4.22.0", "4.23.0");

        assertThat(notice).isEqualTo("camel: a new version is available (4.22.0 -> 4.23.0). Run 'camel self-update' to install it.");
    }

    @Test
    void shouldNotifyReturnsTrueWhenCachedVersionIsNewer() {
        Properties cache = new Properties();
        cache.setProperty("latest_version", "4.23.0");

        assertThat(UpdateChecker.shouldNotify(cache, "4.22.0")).isTrue();
    }

    @Test
    void shouldNotifyReturnsFalseWhenCachedVersionIsSameOrOlder() {
        Properties cache = new Properties();
        cache.setProperty("latest_version", "4.22.0");

        assertThat(UpdateChecker.shouldNotify(cache, "4.22.0")).isFalse();
    }

    @Test
    void shouldNotifyReturnsFalseWhenCacheEmpty() {
        assertThat(UpdateChecker.shouldNotify(new Properties(), "4.22.0")).isFalse();
    }

    @Test
    void isStaleReturnsTrueWhenNoTimestampCached() {
        assertThat(UpdateChecker.isStale(new Properties(), System.currentTimeMillis())).isTrue();
    }

    @Test
    void isStaleReturnsFalseWithinTwentyFourHours() {
        Properties cache = new Properties();
        long now = 1_800_000_000_000L;
        cache.setProperty("last_checked", Long.toString(now - Duration.ofHours(1).toMillis()));

        assertThat(UpdateChecker.isStale(cache, now)).isFalse();
    }

    @Test
    void isStaleReturnsTrueAfterTwentyFourHours() {
        Properties cache = new Properties();
        long now = 1_800_000_000_000L;
        cache.setProperty("last_checked", Long.toString(now - Duration.ofHours(25).toMillis()));

        assertThat(UpdateChecker.isStale(cache, now)).isTrue();
    }

    @Test
    void skipsSelfUpdateInvocation() {
        assertThat(UpdateChecker.isEligible(new String[] { "self-update" }, "false")).isFalse();
    }

    @Test
    void skipsWhenOptedOut() {
        assertThat(UpdateChecker.isEligible(new String[] { "version", "get" }, "false")).isFalse();
    }

    @Test
    void eligibleForOrdinaryInvocation() {
        assertThat(UpdateChecker.isEligible(new String[] { "version", "get" }, null)).isTrue();
    }

    @Test
    void eligibleForNoArgsInvocation() {
        assertThat(UpdateChecker.isEligible(new String[0], null)).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=UpdateCheckerTest -Dci.env.name=local`
Expected: FAIL — compilation error, `UpdateChecker` does not exist.

- [ ] **Step 3: Write `UpdateChecker`**

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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.dsl.jbang.core.common.InstallDetector;
import org.apache.camel.dsl.jbang.core.common.VersionHelper;

/**
 * Prints a one-line stderr notice when a background-cached check shows a newer launcher release than the one
 * currently running. Never blocks the command the user actually invoked: the network fetch that refreshes the
 * cache runs on a daemon thread and is simply retried on a later invocation if the JVM exits first.
 */
public final class UpdateChecker {

    private static final Duration STALE_AFTER = Duration.ofHours(24);

    private UpdateChecker() {
    }

    public static void maybeNotify(String[] args) {
        if (!isEligible(args, System.getenv("CAMEL_SELF_UPDATE_CHECK"))) {
            return;
        }
        if (InstallDetector.locate().method() != InstallDetector.InstallMethod.WEB_INSTALLER) {
            return;
        }

        Path cacheFile = cacheFile();
        Properties cache = readCache(cacheFile);

        if (isStale(cache, System.currentTimeMillis())) {
            Thread refresh = new Thread(() -> refreshCache(cacheFile), "camel-self-update-check");
            refresh.setDaemon(true);
            refresh.start();
        }

        CamelCatalog catalog = new DefaultCamelCatalog();
        String running = catalog.getCatalogVersion();
        if (shouldNotify(cache, running)) {
            System.err.println(formatNotice(running, cache.getProperty("latest_version")));
        }
    }

    // Package-visible pure helpers, unit tested directly in UpdateCheckerTest without any file/network I/O.

    static boolean isEligible(String[] args, String checkEnvVar) {
        if (args.length > 0 && "self-update".equals(args[0])) {
            return false;
        }
        return !"false".equalsIgnoreCase(checkEnvVar);
    }

    static boolean isStale(Properties cache, long now) {
        String lastChecked = cache.getProperty("last_checked");
        if (lastChecked == null) {
            return true;
        }
        try {
            return now - Long.parseLong(lastChecked) > STALE_AFTER.toMillis();
        } catch (NumberFormatException e) {
            return true;
        }
    }

    static boolean shouldNotify(Properties cache, String running) {
        String latest = cache.getProperty("latest_version");
        return latest != null && VersionHelper.compare(latest, running) > 0;
    }

    static String formatNotice(String running, String latest) {
        return "camel: a new version is available (" + running + " -> " + latest + "). Run 'camel self-update' to install it.";
    }

    private static Path cacheFile() {
        return InstallDetector.webInstallerVersionsRoot().getParent().resolve("update-check.properties");
    }

    private static Properties readCache(Path cacheFile) {
        Properties props = new Properties();
        if (Files.exists(cacheFile)) {
            try (InputStream in = Files.newInputStream(cacheFile)) {
                props.load(in);
            } catch (IOException e) {
                // Corrupt/unreadable cache: treated the same as "no cache", refreshed on the next stale check.
            }
        }
        return props;
    }

    // Runs on a daemon thread; any failure (network, timeout, parse) is swallowed, but last_checked is still
    // updated so a persistent failure doesn't retry on every single invocation.
    private static void refreshCache(Path cacheFile) {
        Properties props = readCache(cacheFile);
        props.setProperty("last_checked", Long.toString(System.currentTimeMillis()));
        try {
            ManifestFetcher.Manifest manifest = ManifestFetcher.fromEnvironment().fetchLatest();
            props.setProperty("latest_version", manifest.version());
        } catch (Exception e) {
            // Network/timeout/parse failure: keep whatever latest_version (if any) was already cached.
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            try (OutputStream out = Files.newOutputStream(cacheFile)) {
                props.store(out, null);
            }
        } catch (IOException e) {
            // Best-effort cache write; a failure here just means the next invocation re-checks immediately.
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=UpdateCheckerTest -Dci.env.name=local`
Expected: PASS — all 10 tests green.

- [ ] **Step 5: Wire into `CamelLauncherMain.preExecute()`**

Add to `CamelLauncherMain.java` (new import plus the override, placed after the existing `postAddCommands` method):

```java
import org.apache.camel.dsl.jbang.launcher.selfupdate.UpdateChecker;
```

```java
    @Override
    public void preExecute(CommandLine commandLine, String[] args) {
        UpdateChecker.maybeNotify(args);
    }
```

- [ ] **Step 6: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local
git add dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/UpdateChecker.java \
        dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/selfupdate/UpdateCheckerTest.java \
        dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/CamelLauncherMain.java
git commit -m "CAMEL-23703: Add background update-available notice"
```

---

### Task 7: End-to-end integration test for `camel self-update`

**Files:**
- Create: `dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/SelfUpdateIntegrationTest.java`

**Interfaces:**
- Consumes: the package-private `WebsiteInstallerFixture` (`dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/WebsiteInstallerFixture.java`) — this new test class MUST live in the exact same package (`org.apache.camel.dsl.jbang.launcher`) to see it, since the fixture is declared `final class` with no modifier (package-private). `SelfUpdateCommand`/`ManifestFetcher` (Tasks 3/5), constructed directly (bypassing environment variables entirely — see the Global Constraints note on in-process testability).

Unlike `WebsiteInstallTest` (which shells out to `install.sh`/`install.ps1` as external processes, because those genuinely are shell scripts), `SelfUpdateCommand` is Java code that already runs in-process inside this same test JVM — so this test follows `CamelLauncherTest`'s existing in-process style (construct the command directly, capture its `Printer` output) rather than spawning a subprocess. Since `ManifestFetcher.fromEnvironment()` reads real process environment variables that cannot be safely mutated for one test in a shared JVM, tests construct `SelfUpdateCommand`'s dependencies through the fixture's URLs directly.

Because `SelfUpdateCommand` currently calls `ManifestFetcher.fromEnvironment()` internally (Task 5) rather than accepting a `ManifestFetcher` via constructor injection, this integration test needs one small seam added to `SelfUpdateCommand`: a package-visible constructor overload. Add this to `SelfUpdateCommand.java` (alongside the existing public constructor, does not change any behavior for production callers, which all use the existing public one-arg constructor via `SelfUpdatePlugin`):

```java
    private ManifestFetcher fetcherOverride;

    // Visible for testing: lets SelfUpdateIntegrationTest point the fetcher at a local fixture instead of
    // reading CAMEL_SELF_UPDATE_MANIFEST_BASE_URL/CAMEL_SELF_UPDATE_MAVEN_BASE_URL from the process environment,
    // which cannot be safely overridden for a single test within a shared JVM.
    SelfUpdateCommand(CamelJBangMain main, ManifestFetcher fetcherOverride) {
        super(main);
        this.fetcherOverride = fetcherOverride;
    }
```

And change the one line in `doCall()` that constructs the production fetcher:

```java
        ManifestFetcher fetcher = fetcherOverride != null ? fetcherOverride : ManifestFetcher.fromEnvironment();
```

Also, since `InstallDetector.locate()`/`webInstallerVersionsRoot()` read real environment variables (`XDG_DATA_HOME`/`HOME`/`LOCALAPPDATA`) and system properties (`camel.launcher.jar`) that a test cannot safely mutate process-wide either, this test sets `camel.launcher.jar` and `XDG_DATA_HOME` (or `LOCALAPPDATA` on Windows) as actual system properties/env before each test and clears them after — the same trade-off `DoctorTest`'s new tests in Task 2 already accept (`System.setProperty`/`clearProperty` around a single test body). A cleaner long-term fix (injecting these as constructor parameters too) is out of scope for this plan; flag it as a follow-up if a reviewer pushes back on the property-mutation pattern repeating across two test files.

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.dsl.jbang.core.common.StringPrinter;
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

    private StringPrinter printer;

    @BeforeEach
    void setup() {
        printer = new StringPrinter();
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("camel.launcher.jar");
    }

    private void markRunningAsWebInstaller(Path xdgDataHome, String runningVersion) throws Exception {
        Path versionDir = xdgDataHome.resolve("camel-cli/versions/" + runningVersion);
        Files.createDirectories(versionDir.resolve("bin"));
        System.setProperty("camel.launcher.jar", versionDir.resolve("camel-launcher.jar").toString());
    }

    // Builds the command with a ManifestFetcher pointed at the local fixture, then drives it through picocli's
    // own CommandLine.execute(args) — not direct field assignment. SelfUpdateCommand's `version`/`checkOnly`
    // fields are package-private in org.apache.camel.dsl.jbang.launcher.selfupdate; this test class lives in
    // org.apache.camel.dsl.jbang.launcher (it must, to see the package-private WebsiteInstallerFixture), so it
    // has no visibility into those fields and must go through picocli's reflection-based option parsing instead
    // — which also happens to exercise the real @CommandLine.Option wiring rather than bypassing it.
    private int run(WebsiteInstallerFixture fixture, String... args) {
        ManifestFetcher fetcher = new ManifestFetcher(fixture.baseUrl() + "/camel-cli/releases", fixture.mavenUrl());
        SelfUpdateCommand cmd = new SelfUpdateCommand(new CamelJBangMain().withPrinter(printer), fetcher);
        return new CommandLine(cmd).execute(args);
    }

    @Test
    void installsLatestVersion(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path xdgDataHome = Files.createDirectories(temp.resolve("xdg"));
            markRunningAsWebInstaller(xdgDataHome, "1.0.0");
            System.setProperty("XDG_DATA_HOME", xdgDataHome.toString());
            try {
                WebsiteInstallTest.publishLatest(fixture, "2.0.0");

                int exit = run(fixture);

                assertThat(exit).isZero();
                assertThat(printer.getOutput()).contains("Installed Camel CLI 2.0.0");
                assertThat(Files.isDirectory(xdgDataHome.resolve("camel-cli/versions/2.0.0"))).isTrue();
            } finally {
                System.clearProperty("XDG_DATA_HOME");
            }
        }
    }

    @Test
    void alreadyOnLatestVersionInstallsNothing(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path xdgDataHome = Files.createDirectories(temp.resolve("xdg"));
            markRunningAsWebInstaller(xdgDataHome, "2.0.0");
            System.setProperty("XDG_DATA_HOME", xdgDataHome.toString());
            try {
                WebsiteInstallTest.publishLatest(fixture, "2.0.0");

                int exit = run(fixture);

                assertThat(exit).isZero();
                assertThat(printer.getOutput()).contains("already on the latest version");
                // Only the currently-running version directory should exist; no re-download/re-extract happened.
                try (var listing = Files.list(xdgDataHome.resolve("camel-cli/versions"))) {
                    assertThat(listing.count()).isEqualTo(1);
                }
            } finally {
                System.clearProperty("XDG_DATA_HOME");
            }
        }
    }

    @Test
    void checksOnlyWithoutInstalling(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path xdgDataHome = Files.createDirectories(temp.resolve("xdg"));
            markRunningAsWebInstaller(xdgDataHome, "1.0.0");
            System.setProperty("XDG_DATA_HOME", xdgDataHome.toString());
            try {
                WebsiteInstallTest.publishLatest(fixture, "2.0.0");

                int exit = run(fixture, "--check");

                assertThat(exit).isZero();
                assertThat(printer.getOutput()).contains("A new version is available");
                assertThat(Files.isDirectory(xdgDataHome.resolve("camel-cli/versions/2.0.0"))).isFalse();
            } finally {
                System.clearProperty("XDG_DATA_HOME");
            }
        }
    }

    @Test
    void refusesChecksumMismatch(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path xdgDataHome = Files.createDirectories(temp.resolve("xdg"));
            markRunningAsWebInstaller(xdgDataHome, "1.0.0");
            System.setProperty("XDG_DATA_HOME", xdgDataHome.toString());
            try {
                Path tar = fixture.safeTar("2.0.0");
                Path zip = fixture.safeZip("2.0.0");
                WebsiteInstallTest.publishRelease(fixture, "2.0.0", tar, zip);
                // Publish a manifest whose recorded zip_sha256 doesn't match the real archive bytes.
                String badManifest = "format=1\nversion=2.0.0\ntar_sha256=" + "a".repeat(64) + "\nzip_sha256=" + "b".repeat(64) + "\n";
                fixture.publish("/camel-cli/releases/latest.properties", badManifest.getBytes(StandardCharsets.UTF_8));

                int exit = run(fixture);

                assertThat(exit).isEqualTo(1);
                assertThat(Files.isDirectory(xdgDataHome.resolve("camel-cli/versions/2.0.0"))).isFalse();
            } finally {
                System.clearProperty("XDG_DATA_HOME");
            }
        }
    }

    @Test
    void refusesMaliciousArchive(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path xdgDataHome = Files.createDirectories(temp.resolve("xdg"));
            markRunningAsWebInstaller(xdgDataHome, "1.0.0");
            System.setProperty("XDG_DATA_HOME", xdgDataHome.toString());
            try {
                Path tar = fixture.safeTar("2.0.0");
                Path zip = fixture.maliciousZip("../../etc/passwd");
                WebsiteInstallTest.publishRelease(fixture, "2.0.0", tar, zip);
                fixture.publishManifest("/camel-cli/releases/latest.properties", "2.0.0", tar, zip);

                int exit = run(fixture);

                assertThat(exit).isEqualTo(1);
                assertThat(Files.isDirectory(xdgDataHome.resolve("camel-cli/versions/2.0.0"))).isFalse();
            } finally {
                System.clearProperty("XDG_DATA_HOME");
            }
        }
    }

    @Test
    void downgradesToRequestedVersion(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            Path xdgDataHome = Files.createDirectories(temp.resolve("xdg"));
            markRunningAsWebInstaller(xdgDataHome, "2.0.0");
            System.setProperty("XDG_DATA_HOME", xdgDataHome.toString());
            try {
                WebsiteInstallTest.publishVersion(fixture, "1.0.0");

                int exit = run(fixture, "--version", "1.0.0");

                assertThat(exit).isZero();
                assertThat(Files.isDirectory(xdgDataHome.resolve("camel-cli/versions/1.0.0"))).isTrue();
            } finally {
                System.clearProperty("XDG_DATA_HOME");
            }
        }
    }

    @Test
    void refusesNonWebInstallerInstall(@TempDir Path temp) throws Exception {
        try (WebsiteInstallerFixture fixture = WebsiteInstallerFixture.start(temp.resolve("fixture"))) {
            System.setProperty("camel.launcher.jar", "/opt/homebrew/Cellar/apache-camel/1.0.0/libexec/camel-launcher.jar");
            WebsiteInstallTest.publishLatest(fixture, "2.0.0");

            int exit = run(fixture);

            assertThat(exit).isEqualTo(1);
            assertThat(printer.getOutput()).contains("Homebrew");
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=SelfUpdateIntegrationTest -Dci.env.name=local`
Expected: FAIL to compile — the package-visible `SelfUpdateCommand(CamelJBangMain, ManifestFetcher)` constructor and `fetcherOverride` field from this task's own setup instructions above don't exist yet.

- [ ] **Step 3: Add the test seam to `SelfUpdateCommand`**

Apply the constructor overload and `doCall()` one-line change shown in this task's **Interfaces** section above to `dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdateCommand.java`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dtest=SelfUpdateIntegrationTest,ManifestFetcherTest,ZipArchiveValidatorTest,UpdateCheckerTest -Dci.env.name=local`
Expected: PASS — all 7 `SelfUpdateIntegrationTest` scenarios plus the earlier unit suites green. If `installsLatestVersion`/`downgradesToRequestedVersion` fail on POSIX file-permission grounds, verify `unzip()`'s `makeShellScriptsExecutable` (Task 5, Step 1) actually ran before `verifyStaged()` — the extracted `bin/camel.sh` must be `chmod +x` before it can be run as `/bin/sh <path> version` (the `ProcessBuilder` in `verifyStaged` invokes it via `/bin/sh` explicitly, which does not require the execute bit — but sh scripts sourced elsewhere in this test that check for real execute permission (there are none in this task) would need it; keep the `chmod +x` regardless, since the eventual *activated* `camel` symlink runs the script directly rather than via `/bin/sh`, at which point the execute bit is load-bearing).

- [ ] **Step 5: Run the full `camel-launcher` test module once more before committing**

Run: `mvnd test -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local`
Expected: BUILD SUCCESS — confirms Tasks 1-7's changes don't regress `WebsiteInstallTest`, `CamelLauncherTest`, or any other existing test in the module.

- [ ] **Step 6: Format and commit**

```bash
mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-launcher -Dci.env.name=local
git add dsl/camel-jbang/camel-launcher/src/test/java/org/apache/camel/dsl/jbang/launcher/SelfUpdateIntegrationTest.java \
        dsl/camel-jbang/camel-launcher/src/main/java/org/apache/camel/dsl/jbang/launcher/selfupdate/SelfUpdateCommand.java
git commit -m "CAMEL-23703: Add end-to-end integration test for camel self-update"
```

---

### Task 8: Documentation

**Files:**
- Create: `docs/user-manual/modules/ROOT/pages/camel-jbang-launcher-install.adoc`
- Modify: `docs/user-manual/modules/ROOT/pages/camel-jbang-launcher.adoc`
- Modify: `docs/user-manual/modules/ROOT/pages/camel-4x-upgrade-guide-4_22.adoc`

**Interfaces:** none (documentation only). No test step — `.adoc` build validation happens through the normal antora/asciidoctor doc build, not part of this Maven module's test suite; a manual read-through is the verification step below.

- [ ] **Step 1: Create `camel-jbang-launcher-install.adoc`**

```adoc
= Installing the Camel CLI Launcher

*Available as of Camel 4.22*

Two canonical installer scripts install the xref:camel-jbang-launcher.adoc[Camel CLI Launcher]
without a package manager:

[source,bash]
----
curl -fsSL https://camel.apache.org/install.sh | sh
----

[source,powershell]
----
irm https://camel.apache.org/install.ps1 | iex
----

With no arguments, both installers resolve and install the latest published release. An exact
version can be requested instead with `--version X.Y.Z` (`install.sh`) or `-Version X.Y.Z`
(`install.ps1`).

Both installers download the release archive from Maven Central, verify it against a SHA-256
recorded in a signed-path manifest before extracting it, and reject archives containing absolute
paths, `../` traversal, escaping symlinks/reparse points, or more than one top-level directory. The
staged launcher is run once to confirm a Java 17+ runtime can be discovered; if that check fails,
the previously active installation, if any, is left untouched and the installer exits nonzero.

== Where the CLI is installed

Installation is always per-user and never requires elevation or `sudo`:

* POSIX (`install.sh`) installs under
  `${XDG_DATA_HOME:-$HOME/.local/share}/camel-cli/versions/<version>` and activates it via a
  symlink at `$HOME/.local/bin/camel`. The installer never writes to shell profile files
  (`.bashrc`, `.profile`, etc.); if `$HOME/.local/bin` is not already on `PATH`, it prints guidance
  instead.
* Windows (`install.ps1`) installs under `%LOCALAPPDATA%\Apache Camel\cli\versions\<version>` and
  activates it via a `camel.cmd` shim at `%LOCALAPPDATA%\Apache Camel\bin\camel.cmd` that delegates
  to the staged `camel.bat`. The bin directory is added once, case-insensitively, to the current
  user's `PATH`; the machine `PATH` is never modified.

Previously installed version directories are left in place after an upgrade or downgrade and must
be removed manually. Reinstalling the same version replaces that version directory.

This is also the layout the `camel self-update` and `camel doctor` commands below understand —
neither one touches an installation made through Homebrew, Chocolatey, WinGet, Scoop, SDKMAN, or
JBang, each of which manages its own install location and has its own upgrade command.

== Upgrading and switching versions

Once installed through `install.sh`/`install.ps1`, run:

[source,bash]
----
camel self-update                 # install the latest release, if newer than what's running
camel self-update --version 4.23.0  # install a specific version (upgrade or downgrade)
camel self-update --check         # report only, install nothing
----

`camel self-update` refuses to run, with a message naming the detected package manager and its own
upgrade command (e.g. `brew upgrade camel-cli`), when the currently running installation was not
made by `install.sh`/`install.ps1` — installations managed by a package manager must be upgraded
through that manager.

Every `camel` invocation (except `camel self-update` itself) checks, at most once every 24 hours,
whether a newer release has been published, and prints a one-line notice if so:

[source]
----
camel: a new version is available (4.22.0 -> 4.23.0). Run 'camel self-update' to install it.
----

The check runs on a background thread and never blocks or fails the command actually being run. Set
`CAMEL_SELF_UPDATE_CHECK=false` to disable it entirely.

== Detecting conflicting installations

If a machine has more than one Camel CLI installation (for example, both `install.sh` and Homebrew),
whichever one is first on `PATH` is the one that actually runs — the others are silently unused.
`camel doctor` reports every installation it finds and marks the active one:

[source,bash]
----
$ camel doctor
...
Installs:    Found 2 Camel CLI installations
             ~/.local/share/camel-cli/versions/4.22.0 (WEB_INSTALLER) <- active
             /opt/homebrew/Cellar/apache-camel/4.21.0/libexec (HOMEBREW)

Warning: more than one Camel CLI installation was found. The one marked active is the one your
shell currently runs; the others are unused but still present.
----

`camel doctor` exits non-zero when more than one installation is found, so the check is scriptable
in CI.
```

- [ ] **Step 2: Link the new page from `camel-jbang-launcher.adoc`**

In `docs/user-manual/modules/ROOT/pages/camel-jbang-launcher.adoc`, change:

```adoc
== More Information

See the general xref:camel-jbang.adoc[Camel CLI] documentation.
```

to:

```adoc
== More Information

See xref:camel-jbang-launcher-install.adoc[Installing the Camel CLI Launcher] for the website
installer scripts, `camel self-update`, and `camel doctor`'s multi-install detection, and the
general xref:camel-jbang.adoc[Camel CLI] documentation.
```

- [ ] **Step 3: Add the upgrade guide entry**

In `docs/user-manual/modules/ROOT/pages/camel-4x-upgrade-guide-4_22.adoc`, insert a new `====` subsection immediately after the existing "Website installers for the Camel CLI" subsection (which currently ends right before `=== camel-langchain4j-agent` — insert before that `===` line, i.e. after the "Previously installed version directories..." paragraph):

```adoc
==== camel self-update and camel doctor

Two new commands are available in the xref:camel-jbang-launcher.adoc[Camel CLI Launcher]:

* `camel self-update` checks for and installs newer launcher releases, using the same manifest
  infrastructure the website installers use (see "Website installers for the Camel CLI" above).
  Refuses to act on an installation managed by a package manager (Homebrew, Chocolatey, WinGet,
  Scoop, SDKMAN) or by JBang, naming that manager's own upgrade command instead. Every `camel`
  invocation (except `self-update` itself) also prints a one-line notice, at most once every 24
  hours, when a newer release is available; set `CAMEL_SELF_UPDATE_CHECK=false` to disable it.
* `camel doctor` now additionally reports every Camel CLI installation found on the machine across
  the web installer and all supported package managers, marking which one is actually active on
  `PATH`, and exits non-zero when more than one is found.

See xref:camel-jbang-launcher-install.adoc[Installing the Camel CLI Launcher] for full details.
```

- [ ] **Step 4: Verify manually**

Read back the modified sections of all three files to confirm every `xref:` target resolves to a real page in this same module (`camel-jbang-launcher.adoc`, `camel-jbang-launcher-install.adoc`, `camel-jbang.adoc` — all under `docs/user-manual/modules/ROOT/pages/`), per the "Documentation Conventions" rule in this repo's `CLAUDE.md` (internal links must use `xref:`, never a `https://camel.apache.org/...` URL).

- [ ] **Step 5: Commit**

```bash
git add docs/user-manual/modules/ROOT/pages/camel-jbang-launcher-install.adoc \
        docs/user-manual/modules/ROOT/pages/camel-jbang-launcher.adoc \
        docs/user-manual/modules/ROOT/pages/camel-4x-upgrade-guide-4_22.adoc
git commit -m "CAMEL-23703: Document camel self-update and camel doctor"
```

---

## Homebrew Distribution: rename to `apache-camel`

Tasks 9-15 implement the design's other half: renaming the Homebrew formula `camel` → `apache-camel`
and submitting it to `homebrew-core`, closing the `KNOWN GAP` `jreleaser.yml`'s own comments have
carried since CAMEL-23703's first PR (#24915, already merged — confirmed via
`git log --oneline -- '**/supported-lts.yml'`, the only commit that ever touched the packaging
directory). This work touches JReleaser packaging config/templates and two new shell scripts; it
does not touch the Java code from Tasks 1-8 beyond the two fixes already folded into Task 1
(`scanHomebrew()`) and Task 5 (`refusalMessage()`) above.

**Files consumed as copy sources** (a sibling checkout at `../backup-CAMEL-23703`, i.e.
`/Users/admachad/Opensource/RH/camel/backup-CAMEL-23703` relative to this repo's parent directory —
**not** part of this git repository and **not** a branch or remote of it; treat it purely as a
read-only reference of a prior, more complete iteration of this same JIRA that was never merged).
Confirmed present at that path during this plan's research:
- `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-validate.sh` (POSIX, 476 lines)
- `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/assert-camel-cli.sh` (POSIX, 143 lines, package-name-agnostic — no `camel`/`apache-camel` string anywhere in it)
- `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-publish.sh` (POSIX-ish, 518 lines)
- `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/publish-state.sh` (POSIX, 191 lines)
- `dsl/camel-jbang/camel-launcher/src/test/resources/validate/expected-init-route.txt` (13 lines, package-name-agnostic)

### Task 9: Rename Homebrew formula generation to `apache-camel`; pin JDK to 21; add livecheck

**Files:**
- Modify: `dsl/camel-jbang/camel-launcher/jreleaser.yml`
- Modify: `dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/brew/formula.rb.tpl`
- Modify: `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh`

**Interfaces:**
- Consumes: nothing from Tasks 1-8 (independent subsystem). Consumes the pinned
  `jreleaser-plugin-version` (`1.25.0`, `dsl/camel-jbang/camel-launcher/pom.xml:58`) — this task's
  claims about JReleaser's Homebrew packager behavior were checked directly against JReleaser's own
  docs during this plan's research (see Global Constraints), not assumed from the design spec.
- Produces: `camel-package.sh --print-plan`'s `BREW_FORMULA=apache-camel` / `BREW_CLASS=ApacheCamel`
  (stable channel) and `BREW_FORMULA=apache-camel@<LTS_LINE>` /
  `BREW_CLASS=ApacheCamelAT<LTS_LINE-no-dots>` (lts channel), and the new
  `CAMEL_PKG_BREW_LIVECHECK_REGEX` exported env var, consumed by Task 10 (which adds the
  `deprecate!`/`disable!` dates) and Task 12 (which renders and audits the real formula content).

`project.languages.java.version` (currently `17`) drives two things simultaneously, both already
confirmed against JReleaser's docs (Global Constraints): (1) JReleaser auto-generates a
`depends_on "openjdk@<major>"` line in every rendered Homebrew formula for a `JAVA_BINARY`
distribution — so the template's own hand-written `depends_on "openjdk"` line (unpinned) must be
removed, or the rendered formula would carry two conflicting `depends_on` lines; (2) it is the
*only* place this project pins a JDK floor for Homebrew's dependency, and it is a single
project-wide value — there is no per-distribution or per-channel override. Bumping it to `21`
matches the design's explicit ask ("`depends_on "openjdk@21"`... matching Camel's actual minimum
JDK (21, not the general project README's "17+")").

- [ ] **Step 1: Bump the pinned JDK and add a Maven-metadata livecheck to `jreleaser.yml`**

In `dsl/camel-jbang/camel-launcher/jreleaser.yml`, change:

```yaml
  languages:
    java:
      groupId: org.apache.camel
      version: 17
```

to:

```yaml
  languages:
    java:
      groupId: org.apache.camel
      version: 21
```

Also update the existing `# KNOWN GAP:` comment block (immediately below the `NOTE:` block): its
text currently frames "own tap vs. `homebrew-core`" as an open decision blocking the
one-release-produces-both-formulae mechanical limitation. That destination decision is now made
(this task + Task 13 commit to `homebrew-core`); only the mechanical limitation itself (one `brew`
packager per distribution, so `--channel stable --lts-line X.Y` still can't emit both formulae from
a single `mvn jreleaser:...` run) remains open. Change the comment's second sentence from "Producing
both formulae from one release depends on the same publish-destination decision `publish` itself is
waiting on (see `camel-package.sh`) - whether the versioned formula goes to this project's own tap
or `homebrew-core` affects how it can be produced alongside the unversioned one." to "Producing both
formulae from one release would require a second `brew` packager block on the same distribution,
which JReleaser's schema doesn't support - unrelated to *where* they publish (both now go to
`homebrew-core`, decided; see Tasks 9-13 of the launcher self-update/doctor/Homebrew-distribution
implementation plan)." — so the comment stops describing a decision that's actually already made.

Add a bullet to the existing top-of-file `NOTE:` comment block (after the existing `- type:
JAVA_BINARY...` bullet, before the `- SDKMAN has no boolean...` bullet), documenting the one
non-obvious fact this task's own research turned up that isn't visible from reading the YAML alone:

```yaml
#   - A packager's `livecheck:` field (used below under `brew:`) is a raw list of Homebrew-Ruby
#     source lines, not a set of structured url/regex sub-keys - confirmed against JReleaser's own
#     docs. Each list entry becomes one line inside the generated `livecheck do ... end` block, which
#     is exactly what formula.rb.tpl's existing `{{#brewLivecheck}}{{.}}{{/brewLivecheck}}` Mustache
#     loop already expects (this field has never actually been populated until this task, so the
#     loop itself was unexercised code before now - see the trace-log check in Step 4 below).
```

Then, under `distributions: camel-cli: brew:`, add the `livecheck` field (after the existing
`extraProperties:` block, same indentation as `formulaName`/`downloadUrl`):

```yaml
      livecheck:
        - 'url "https://repo1.maven.org/maven2/org/apache/camel/camel-launcher/maven-metadata.xml"'
        - 'regex(/{{ Env.CAMEL_PKG_BREW_LIVECHECK_REGEX }}/i)'
```

(Reuses the exact same `repo1.maven.org` Maven-metadata URL the Scoop template's own `checkver`
already polls for the identical purpose — see `scoop/manifest.json.tpl`'s `checkver.url` — so this
introduces no new URL the project doesn't already treat as a trusted version source.)

- [ ] **Step 2: Remove the hand-written `depends_on` and custom `caveats` from `formula.rb.tpl`**

In `dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/brew/formula.rb.tpl`,
change:

```ruby
  depends_on "openjdk"

  def install
    libexec.install Dir["*"]
    (bin/"{{distributionExecutableName}}").write_env_script libexec/"bin/camel.sh",
      CAMEL_FALLBACK_JAVA: "#{formula_opt_bin("openjdk")}/java"
  end

  def caveats
    <<~EOS
      Apache Camel CLI installs its own Homebrew OpenJDK dependency even if a
      compatible Java 17+ is already present. The launcher selects a Java runtime in
      this order: JAVACMD, JAVA_HOME/bin/java, the first java on PATH, then
      CAMEL_FALLBACK_JAVA (which this formula points at the Homebrew OpenJDK).

      Run "camel version" to verify the install.
{{#brewVersionedFormula}}

      #{name} is keg-only: it was not symlinked into #{HOMEBREW_PREFIX} because
      another version of this formula may also be installed. To use this version's
      "camel" first in your PATH, run:
        echo 'export PATH="#{opt_bin}:$PATH"' >> ~/.zshrc
{{/brewVersionedFormula}}
    EOS
  end

  test do
```

to:

```ruby
  def install
    libexec.install Dir["*"]
    (bin/"{{distributionExecutableName}}").write_env_script libexec/"bin/camel.sh",
      Language::Java.overridable_java_home_env("21")
  end

  test do
```

Two things this drops deliberately, per the design: the hand-written `depends_on` (now
auto-generated by JReleaser from `project.languages.java.version`, Step 1) and the entire custom
`caveats` method (Homebrew auto-generates the standard keg-only PATH guidance for a `keg_only`
formula for free; neither `apache-flink.rb` nor `apache-flink@1.rb` — both read directly from
`homebrew-core` during this plan's research — define a custom `caveats` method either).

One behavioral nuance worth flagging in the eventual PR description, not a blocker: today's
`CAMEL_FALLBACK_JAVA` is consulted *last* in the launcher's own resolution order (after `JAVACMD`,
`JAVA_HOME`, and any `java` already on `PATH`). `Language::Java.overridable_java_home_env("21")`
sets `JAVA_HOME` itself (conditionally — only when the invoking shell doesn't already export one),
which the launcher checks *second*, ahead of `PATH`. Net effect: a user with no `JAVACMD`/`JAVA_HOME`
set who happens to have some other `java` earlier on `PATH` will now get Homebrew's pinned JDK 21
instead of that `PATH` entry, whereas today they'd get the `PATH` one and Homebrew's JDK would only
ever be the last resort. This is the design's own explicit direction ("adopting Homebrew's own...
helper (same as `apache-spark.rb`)"), so this task implements it as specified — just noting the
precedence shift for the record rather than letting it pass silently.

- [ ] **Step 3: Rename `BREW_FORMULA`/`BREW_CLASS` and export the livecheck regex in `camel-package.sh`**

In `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh`, change the `--- Channel ->
packaging plan ---` block from:

```sh
if [ "$CHANNEL" = "stable" ]; then
  PACKAGERS="brew,sdkman,winget,scoop,chocolatey"
  BREW_FORMULA="camel"
  BREW_CLASS="Camel"
  SDKMAN_DEFAULT="true"
  BREW_LTS_FORMULA=""
  WEBSITE_LATEST="true"
  [ -n "$LTS_LINE" ] && BREW_LTS_FORMULA="camel@$LTS_LINE"
else
  # Homebrew's own versioned-formula convention names the *file* "camel@X.Y.rb" but the
  # Ruby *class* "CamelATxy" (dot removed) - e.g. real homebrew-core "python@3.11.rb"
  # contains "class PythonAT311". As of the pinned JReleaser plugin version in pom.xml,
  # JReleaser does not apply this convention itself:
  # a literal formulaName "camel@4.20" renders invalid Ruby (`class Camel@4.20 < Formula`)
  # and a wrong output filename ("20.rb"). So BREW_CLASS below is
  # passed as formulaName (giving valid Ruby), and JReleaser's output file (itself
  # kebab-cased from that class name, e.g. "camel-at-420.rb")
  # is renamed to the real "camel@X.Y.rb" after packaging - see the rename step below the
  # mvn invocation.
  PACKAGERS="brew,sdkman,winget,chocolatey"
  BREW_FORMULA="camel@$LTS_LINE"
  BREW_CLASS="CamelAT$(echo "$LTS_LINE" | tr -d '.')"
  SDKMAN_DEFAULT="false"
  BREW_LTS_FORMULA=""
  WEBSITE_LATEST="false"
fi
```

to:

```sh
if [ "$CHANNEL" = "stable" ]; then
  PACKAGERS="brew,sdkman,winget,scoop,chocolatey"
  BREW_FORMULA="apache-camel"
  BREW_CLASS="ApacheCamel"
  SDKMAN_DEFAULT="true"
  BREW_LTS_FORMULA=""
  WEBSITE_LATEST="true"
  # The maven-metadata.xml <release> tag always reflects the newest published release across every
  # line, which is exactly "latest stable" for this formula - no line-prefix filtering needed.
  CAMEL_PKG_BREW_LIVECHECK_REGEX='<release>([0-9]+\.[0-9]+\.[0-9]+)<\/release>'
  [ -n "$LTS_LINE" ] && BREW_LTS_FORMULA="apache-camel@$LTS_LINE"
else
  # Homebrew's own versioned-formula convention names the *file* "apache-camel@X.Y.rb" but the
  # Ruby *class* "ApacheCamelATxy" (dot removed) - e.g. real homebrew-core "python@3.11.rb"
  # contains "class PythonAT311", and the real homebrew-core "apache-flink@1.rb" contains
  # "class ApacheFlinkAT1" (confirmed by reading both directly from homebrew-core during this
  # plan's research). As of the pinned JReleaser plugin version in pom.xml, JReleaser does not
  # apply this convention itself: a literal formulaName "apache-camel@4.20" renders invalid Ruby
  # (`class ApacheCamel@4.20 < Formula`) and a wrong output filename ("20.rb"). So BREW_CLASS below
  # is passed as formulaName (giving valid Ruby), and JReleaser's output file (itself kebab-cased
  # from that class name, e.g. "apache-camel-at-420.rb") is renamed to the real
  # "apache-camel@X.Y.rb" after packaging - see the rename step below the mvn invocation.
  PACKAGERS="brew,sdkman,winget,chocolatey"
  BREW_FORMULA="apache-camel@$LTS_LINE"
  BREW_CLASS="ApacheCamelAT$(echo "$LTS_LINE" | tr -d '.')"
  SDKMAN_DEFAULT="false"
  BREW_LTS_FORMULA=""
  WEBSITE_LATEST="false"
  # maven-metadata.xml lists every published version, not just the newest, so the LTS formula's
  # livecheck must filter to this LTS line's own X.Y prefix (escaping the dot so it matches
  # literally, not "any character") or it would report the project's overall latest release
  # instead of this line's own latest patch.
  lts_line_escaped=$(echo "$LTS_LINE" | sed 's/\./\\./g')
  CAMEL_PKG_BREW_LIVECHECK_REGEX="<version>(${lts_line_escaped}\.[0-9]+)<\/version>"
fi
export CAMEL_PKG_BREW_LIVECHECK_REGEX
```

Then update the two other places in the same file that already reference `$BREW_FORMULA`'s old
`camel`-prefixed shape only in comments/log text, not logic (the logic already reads `$BREW_FORMULA`
generically): the `if [ "$PRINT_PLAN" -eq 1 ]` block and the post-`mvn` LTS-rename block need no
further code changes — both already operate on `$BREW_FORMULA`/`$BREW_CLASS` as opaque variables.

- [ ] **Step 4: Verify the rename and the plan's own confirmation via `--print-plan`**

Run: `CAMEL_PACKAGE_TEST_MODE=true CAMEL_PACKAGE_TEST_VERSION=9.9.9 dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh prepare --channel stable --print-plan`

Expected: output includes `BREW_FORMULA=apache-camel` and `BREW_CLASS=ApacheCamel` (no `BREW_LTS_FORMULA` line, since no `--lts-line` was given).

Run: `CAMEL_PACKAGE_TEST_MODE=true CAMEL_PACKAGE_TEST_VERSION=9.9.9 dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh prepare --channel lts --lts-line 4.22 --print-plan`

Expected: output includes `BREW_FORMULA=apache-camel@4.22` and `BREW_CLASS=ApacheCamelAT422`.

(Full formula-content rendering — including confirming the new `livecheck` block actually produces
valid Ruby via the `{{#brewLivecheck}}` Mustache loop, which has never been exercised before this
task — happens in Task 12 once real release artifacts exist for `camel-package.sh prepare` to
package; `--print-plan` only proves the shell-variable rename itself, which is what this step
verifies.)

- [ ] **Step 5: Format and commit**

```bash
git add dsl/camel-jbang/camel-launcher/jreleaser.yml \
        dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/brew/formula.rb.tpl \
        dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh
git commit -m "CAMEL-23703: Rename Homebrew formula to apache-camel; pin JDK to 21"
```

---

### Task 10: Versioned formula lifecycle (`apache-camel@X.Y` `deprecate!`/`disable!` dates)

**Files:**
- Modify: `dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/brew/formula.rb.tpl`
- Modify: `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh`

**Interfaces:**
- Consumes: Task 9's `BREW_CLASS`/`CAMEL_PKG_BREW_VERSIONED` variables (unchanged shape, just
  renamed values); `supported-lts.yml`'s existing `supportEnds` field (already read by the existing
  `--lts-line` validation logic earlier in the same file — this task adds a *second* read of the same
  file for a different purpose, described below).
- Produces: `{{brewDeprecateDate}}`/`{{brewDisableDate}}` template variables the formula uses in a
  new `{{#brewVersionedFormula}}deprecate!...{{/brewVersionedFormula}}` block.

Before writing code: `supported-lts.yml` today lists only one line (`4.22`, `supportEnds:
"2027-09-30"`) — confirmed via `git log --oneline -- '**/supported-lts.yml'`, the only commit that
ever touched this file, which added it with exactly one entry. The design spec's own "Open items to
verify" flagged a missing `4.18` line as possibly "an accidental drop" versus "deliberate
retirement." Reading the same file from the unmerged `../backup-CAMEL-23703` checkout shows it
**does** carry a `4.18` line (`supportEnds: "2026-12-31"`) there. Since no commit in *this* repo's
history ever added, then removed, a `4.18` line — it was simply never carried over from that prior
iteration into what actually got merged — "accidental drop" doesn't apply (nothing was dropped) and
"deliberate retirement" doesn't either (retirement implies it was once supported, then formally
ended; there's no evidence of that decision having been made here). This is a genuine open question
for the operator/PMC, not something this task should resolve by silently copying the backup's date
back in — that `2026-12-31` value was authored in a different iteration and was never itself
verified against Camel's actual LTS policy for this plan. **Flag this to the user before starting
this task**: if `4.18` is still an actively supported Camel LTS line as of today, `supported-lts.yml`
is currently missing it (independent of this rename), and someone with the authoritative EOL date
should add it before its own versioned formula can be built via `--lts-line 4.18`.

A second thing worth surfacing before writing code, not resolving: JReleaser's `depends_on`
auto-derivation (Task 9) reads a single **project-wide** `project.languages.java.version` — there is
no per-channel or per-LTS-line override. The design's own text ("depends_on pins whatever JDK that
specific LTS line's own floor requires, not necessarily 21") is written by analogy to
`apache-flink@1.rb` depending on `openjdk@11` while `apache-flink.rb` depends on `openjdk@21` — but
Flink's 1.x/2.x split is a real JDK-floor difference between two *major* Flink versions, whereas
every currently-supported Camel LTS line (`supported-lts.yml`, both today's `4.22` and any `4.18`
line eventually added) shares the same Java 17+ (now 21-pinned) floor as the rest of the 4.x series
— there is no currently-known Camel LTS line that needs an older JDK than `apache-camel` itself. This
task does **not** add a per-LTS-line `depends_on` override, because there is nothing to override
against with real data; if a future LTS line ever needs one, it would require a manual `depends_on`
line in the template gated on `LTS_LINE`, which is a mechanism this task does not build speculatively
(Rule 2 — simplicity first, no abstractions for a case that doesn't exist yet).

- [ ] **Step 1: Add `deprecate!`/`disable!` to `formula.rb.tpl`**

In `dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/brew/formula.rb.tpl`,
change:

```ruby
{{#brewVersionedFormula}}

  keg_only :versioned_formula
{{/brewVersionedFormula}}
```

to:

```ruby
{{#brewVersionedFormula}}

  keg_only :versioned_formula

  deprecate! date: "{{brewDeprecateDate}}", because: :unsupported
  disable! date: "{{brewDisableDate}}", because: :unsupported
{{/brewVersionedFormula}}
```

(Matches `apache-flink@1.rb`'s exact syntax, confirmed by reading it directly from `homebrew-core`
during this plan's research: `deprecate! date: "2026-05-02", because: :unsupported` /
`disable! date: "2027-05-02", because: :unsupported`.)

- [ ] **Step 2: Compute and export `brewDeprecateDate`/`brewDisableDate` in `camel-package.sh`**

In the `else` (LTS) branch added/modified in Task 9, after the `CAMEL_PKG_BREW_LIVECHECK_REGEX`
line, add:

```sh
  # deprecate! fires 6 months before the line's own documented support end; disable! fires on the
  # support-end date itself. supported_ends was already resolved and validated (non-empty, a real
  # entry in supported-lts.yml) by the --lts-line validation earlier in this script - re-used here
  # rather than re-parsed.
  CAMEL_PKG_BREW_DEPRECATE_DATE=$(date -u -d "$supported_ends -6 months" +%F 2>/dev/null \
    || date -u -j -v-6m -f %Y-%m-%d "$supported_ends" +%F)
  CAMEL_PKG_BREW_DISABLE_DATE="$supported_ends"
```

(The `date -d ... || date -j -v...` fallback pair matches the two real date-arithmetic dialects this
project's own `camel-package.sh` already has to support: GNU `date` on Linux CI runners, BSD `date`
on macOS developer machines — the same portability constraint the file's existing `today=$(date
+%F)` / `expr "$today" \> "$supported_ends"` comparison earlier in the file was written under.)

Export both alongside the existing `CAMEL_PKG_BREW_LIVECHECK_REGEX` export:

```sh
export CAMEL_PKG_BREW_LIVECHECK_REGEX
```

becomes:

```sh
export CAMEL_PKG_BREW_LIVECHECK_REGEX
if [ "$CHANNEL" = "lts" ]; then
  export CAMEL_PKG_BREW_DEPRECATE_DATE
  export CAMEL_PKG_BREW_DISABLE_DATE
fi
```

Then wire the two new env vars into the `mvn jreleaser:...` invocation's environment the same way
`CAMEL_PKG_BREW_FORMULA`/`CAMEL_PKG_BREW_VERSIONED` already are (both are already plain `export`s
consumed by JReleaser's `{{ Env.X }}` Mustache resolution — no extra `-D` flags needed, matching the
existing pattern for every other `CAMEL_PKG_*` variable in this file).

- [ ] **Step 3: Wire the two new env vars into `jreleaser.yml`**

In `dsl/camel-jbang/camel-launcher/jreleaser.yml`, under `distributions: camel-cli: brew:
extraProperties:`, add two more entries alongside the existing `versionedFormula`:

```yaml
      extraProperties:
        # Non-empty only for versioned formulae (e.g. "apache-camel@4.20"); used by
        # formula.rb.tpl as a Mustache truthiness check (empty string == falsy)
        # to add `keg_only :versioned_formula` and its PATH caveat.
        versionedFormula: "{{ Env.CAMEL_PKG_BREW_VERSIONED }}"
        deprecateDate: "{{ Env.CAMEL_PKG_BREW_DEPRECATE_DATE }}"
        disableDate: "{{ Env.CAMEL_PKG_BREW_DISABLE_DATE }}"
```

(JReleaser exposes each `extraProperties` entry as `{{brew<CapitalizedKey>}}` in the template — this
is the same mechanism `versionedFormula` → `{{brewVersionedFormula}}` already uses, confirmed
working in the existing, already-merged template.)

- [ ] **Step 4: Verify via `--print-plan` and a dry-run trace**

Run: `CAMEL_PACKAGE_TEST_MODE=true CAMEL_PACKAGE_TEST_VERSION=9.9.9 dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh prepare --channel lts --lts-line 4.22 --print-plan`

Expected: exit 0 (LTS line `4.22` is still in `supported-lts.yml` and its `supportEnds` is in the
future relative to today).

Run (this one does NOT use `--print-plan`, so it actually resolves and exports the new date env vars
— add a temporary `echo "$CAMEL_PKG_BREW_DEPRECATE_DATE / $CAMEL_PKG_BREW_DISABLE_DATE"` right after
the export lines from Step 2, run once, confirm the two dates are `2027-03-30` and `2027-09-30`
respectively (six months before / exactly on `supported-lts.yml`'s current `4.22` `supportEnds:
"2027-09-30"`), then remove the temporary echo before committing):

Run: `CAMEL_PACKAGE_TEST_MODE=true CAMEL_PACKAGE_TEST_VERSION=9.9.9 dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh prepare --channel lts --lts-line 4.22`

Expected: prints `2027-03-30 / 2027-09-30`, then proceeds into the real `mvn jreleaser:...` dry run
(which will fail here for unrelated reasons — no real release artifacts exist for version `9.9.9` —
that failure is expected and not what this step is checking).

- [ ] **Step 5: Format and commit**

```bash
git add dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/brew/formula.rb.tpl \
        dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh \
        dsl/camel-jbang/camel-launcher/jreleaser.yml
git commit -m "CAMEL-23703: Add deprecate!/disable! lifecycle to apache-camel@X.Y formula"
```

---

### Task 11: Scoop/Chocolatey native-exe post-install cleanup

**Files:**
- Modify: `dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/scoop/manifest.json.tpl`
- Modify: `dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/chocolatey/tools/chocolateyinstall.ps1.tpl`

**Interfaces:** none (template-only; independent of every other task in this plan). Both templates
render from the same `camel-cli` distribution's `.zip` artifact (`jreleaser.yml:111`), which — unlike
the dedicated `camel-cli-winget` distribution's ZIP — is not supposed to ship the native
`camel-x64.exe`/`camel-arm64.exe` WinGet bootstraps at all once extracted by Scoop/Chocolatey; both
package managers invoke `camel.bat` directly and never touch those two files.

- [ ] **Step 1: Add Scoop's `post_install` cleanup**

In `dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/scoop/manifest.json.tpl`,
change:

```json
    "extract_dir": "{{distributionArtifactRootEntryName}}",
    "bin": "bin\\{{distributionExecutableWindows}}",
```

to:

```json
    "extract_dir": "{{distributionArtifactRootEntryName}}",
    "bin": "bin\\{{distributionExecutableWindows}}",
    "post_install": [
        "Remove-Item \"$dir\\bin\\camel-x64.exe\" -ErrorAction SilentlyContinue",
        "Remove-Item \"$dir\\bin\\camel-arm64.exe\" -ErrorAction SilentlyContinue"
    ],
```

(`$dir` is Scoop's own manifest-script variable for "this app's installed directory" — standard
Scoop manifest convention, not a JReleaser/Mustache substitution; `-ErrorAction SilentlyContinue`
means a future release that stops shipping these two files entirely doesn't turn this into a hard
install failure.)

- [ ] **Step 2: Add Chocolatey's equivalent cleanup**

In
`dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/chocolatey/tools/chocolateyinstall.ps1.tpl`,
change:

```powershell
Install-ChocolateyZipPackage `
    -PackageName '{{chocolateyPackageName}}' `
    -Url '{{{distributionUrl}}}' `
    -Checksum '{{distributionChecksumSha256}}' `
    -ChecksumType 'sha256' `
    -UnzipLocation $package

Install-BinFile -Name '{{distributionExecutableName}}' -Path $app_exe
```

to:

```powershell
Install-ChocolateyZipPackage `
    -PackageName '{{chocolateyPackageName}}' `
    -Url '{{{distributionUrl}}}' `
    -Checksum '{{distributionChecksumSha256}}' `
    -ChecksumType 'sha256' `
    -UnzipLocation $package

# Chocolatey has no per-architecture exe selection (chocolatey/choco#1803); these two WinGet-only
# native bootstraps are never invoked by a Chocolatey install and would otherwise linger unused.
Remove-Item (Join-Path $app_home 'bin\camel-x64.exe') -ErrorAction SilentlyContinue
Remove-Item (Join-Path $app_home 'bin\camel-arm64.exe') -ErrorAction SilentlyContinue

Install-BinFile -Name '{{distributionExecutableName}}' -Path $app_exe
```

- [ ] **Step 3: Regression check — render both templates and assert the two exes are gone**

There is no existing shell/PowerShell test harness for these templates in this module (confirmed —
`find dsl/camel-jbang/camel-launcher -iname "*test*"` under `src/test` turns up only Java tests), so
this step is a manual Mustache-substitution smoke check rather than a new automated test class (Rule
13 — no new test framework dependency for two five-line template diffs):

Run:
```bash
sed -e 's/{{distributionArtifactRootEntryName}}/camel-launcher-9.9.9/g' \
    -e 's/{{distributionExecutableWindows}}/camel.bat/g' \
    dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/scoop/manifest.json.tpl \
  | python3 -c "import json,sys; json.load(sys.stdin)" \
  && echo "scoop manifest.json.tpl: valid JSON with post_install present"
```
Expected: `scoop manifest.json.tpl: valid JSON with post_install present` (the `python3 -c` call
itself fails loudly with a JSON parse error if the added `post_install` array has a syntax mistake,
e.g. a missing comma after the preceding `"bin"` line).

Run: `grep -c "camel-x64.exe\|camel-arm64.exe" dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/chocolatey/tools/chocolateyinstall.ps1.tpl`

Expected: `2` (exactly the two `Remove-Item` lines just added — confirms the cleanup lines are
present without needing a full PowerShell parse, which isn't available in this environment).

- [ ] **Step 4: Commit**

```bash
git add dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/scoop/manifest.json.tpl \
        dsl/camel-jbang/camel-launcher/src/jreleaser/distributions/camel-cli/chocolatey/tools/chocolateyinstall.ps1.tpl
git commit -m "CAMEL-23703: Clean up WinGet-only native exes after Scoop/Chocolatey install"
```

---

### Task 12: Port `camel-validate.sh` — local Homebrew formula validation hardening

**Files:**
- Create: `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-validate.sh` (ported from
  `../backup-CAMEL-23703`, same relative path, with the diff below applied)
- Create: `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/assert-camel-cli.sh` (ported
  unchanged — verbatim copy, confirmed package-name-agnostic during this plan's research)
- Create: `dsl/camel-jbang/camel-launcher/src/test/resources/validate/expected-init-route.txt`
  (ported unchanged — 13 lines, confirmed package-name-agnostic)

**Interfaces:**
- Consumes: Task 9's renamed formula output (`target/jreleaser/package/camel-cli/brew/Formula/apache-camel.rb`
  or `.../apache-camel@X.Y.rb`), `camel-package.sh`'s existing `CAMEL_PACKAGE_TEST_MODE`/
  `CAMEL_PACKAGE_TEST_VERSION` test seams (unchanged from before this plan).
- Produces: `camel-validate.sh <all|local|homebrew|sdkman|help> [--channel stable|lts] [--lts-line
  X.Y] [--project-version X.Y.Z]`, used manually pre-PR per the design's Testing section and wired
  into `release-guide.adoc` by Task 15.

- [ ] **Step 1: Copy the three ported files from the backup checkout**

```bash
mkdir -p dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib \
         dsl/camel-jbang/camel-launcher/src/test/resources/validate

cp /Users/admachad/Opensource/RH/camel/backup-CAMEL-23703/dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-validate.sh \
   dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-validate.sh

cp /Users/admachad/Opensource/RH/camel/backup-CAMEL-23703/dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/assert-camel-cli.sh \
   dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/assert-camel-cli.sh

cp /Users/admachad/Opensource/RH/camel/backup-CAMEL-23703/dsl/camel-jbang/camel-launcher/src/test/resources/validate/expected-init-route.txt \
   dsl/camel-jbang/camel-launcher/src/test/resources/validate/expected-init-route.txt

chmod +x dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-validate.sh
```

- [ ] **Step 2: Apply the `apache-camel` rename and the `--new --strict` fix to `camel-validate.sh`**

In the freshly-copied `camel-validate.sh`, change the `formula_name()` helper from:

```sh
formula_name() {
    case "$CHANNEL" in
        stable) echo "camel" ;;
        lts)    echo "camel@$LTS_LINE" ;;
    esac
}
```

to:

```sh
formula_name() {
    case "$CHANNEL" in
        stable) echo "apache-camel" ;;
        lts)    echo "apache-camel@$LTS_LINE" ;;
    esac
}
```

And change the two `brew audit` invocations inside `validate_homebrew()` from:

```sh
    audit_output=$(HOMEBREW_NO_AUTO_UPDATE=1 brew audit --strict "$tap_name/$fmla" 2>&1 || true)
```

to:

```sh
    # --new (not just --strict): homebrew-core enforces additional checks specific to a first-time
    # formula submission - the bar apache-camel needs to clear before homebrew-core will accept it.
    # Plain --strict is the bar for an *already-accepted* formula being updated, which understates
    # what's needed for this PR.
    audit_output=$(HOMEBREW_NO_AUTO_UPDATE=1 brew audit --new --strict "$tap_name/$fmla" 2>&1 || true)
```

No other changes to this file — the local-tap creation (`brew tap-new`), install/uninstall
round-trip, and version/init assertions (via the now-ported `assert-camel-cli.sh`) all already
operate on `$fmla` generically and need no rename-specific changes.

- [ ] **Step 3: Render a real formula and run the validator against it**

This requires real release artifacts, so it needs an actual (not `--print-plan`) `prepare` run
first. Using the project's real current `SNAPSHOT` version is blocked by `camel-package.sh`'s own
snapshot guard, so use the test-mode seam with a fabricated version, matching how the file's own
comments describe this exact scenario (`camel-validate.sh`'s "test-mode-only hack" note references
this workflow):

```bash
mvnd package -pl dsl/camel-jbang/camel-launcher -am -DskipTests -Dci.env.name=local
CAMEL_PACKAGE_TEST_MODE=true CAMEL_PACKAGE_TEST_VERSION=9.9.9 \
  dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh prepare --channel stable
```

Expected: BUILD SUCCESS on both, and
`dsl/camel-jbang/camel-launcher/target/jreleaser/package/camel-cli/brew/Formula/apache-camel.rb`
exists.

Run: `grep -n "^class \|depends_on\|livecheck\|caveats\|bottle do" dsl/camel-jbang/camel-launcher/target/jreleaser/package/camel-cli/brew/Formula/apache-camel.rb`

Expected: `class ApacheCamel < Formula`, exactly one `depends_on "openjdk@21"` line (confirms Task
9's JReleaser-auto-derivation bet paid off — if this instead shows `depends_on "openjdk"`
unpinned or is missing entirely, Task 9's Step 2 removal of the hand-written `depends_on` was
premature and needs to be reverted before proceeding), a `livecheck` line, and **no** `caveats` or
`bottle do` line (the design requires the submitted PR to carry no bottle block at all — Homebrew's
own BrewTestBot computes and commits real bottle SHA-256s after the PR's tests pass; a placeholder
one authored here would be wrong and would get flagged in `homebrew-core` review).

Run (requires Homebrew installed — if unavailable, this step is `SKIP`ped by the script itself, not
a hard failure): `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-validate.sh homebrew --channel stable --project-version 9.9.9`

Expected: `PASS: homebrew style/audit passed` (or a `WARN:` block listing any `brew audit --new
--strict` findings to fix before this formula is ready for a real `homebrew-core` PR — `--new`
surfaces first-submission-only checks that a plain `--strict` run would not have caught). The
subsequent install/init/uninstall steps will `SKIP` (no real Maven Central artifact exists for
version `9.9.9`), which is expected per the script's own documented behavior.

- [ ] **Step 4: Commit**

```bash
git add dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-validate.sh \
        dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/assert-camel-cli.sh \
        dsl/camel-jbang/camel-launcher/src/test/resources/validate/expected-init-route.txt
git commit -m "CAMEL-23703: Port camel-validate.sh local Homebrew validation hardening"
```

---

### Task 13: Port `camel-publish.sh` — homebrew-core PR workflow, replacing the `publish` stub

**Files:**
- Create: `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-publish.sh` (ported from
  `../backup-CAMEL-23703` with the Homebrew-destination rewrite and clone-step addition below)
- Create: `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/publish-state.sh` (ported unchanged)
- Modify: `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh` (remove the `publish`
  stub, since `camel-publish.sh` now supersedes it)

**Interfaces:**
- Consumes: Task 9's `BREW_FORMULA` values (`apache-camel`/`apache-camel@X.Y`), the shallow-clone
  default branches confirmed in Global Constraints (`homebrew-core` → `main`, `winget-pkgs` →
  `master`).
- Produces: `camel-publish.sh <version> --channel <stable|lts> [--lts-line X.Y]`, documented by
  Task 15's `release-guide.adoc` update.

The prior iteration's `camel-publish.sh` (read in full during this plan's research) is a genuinely
useful skeleton — ordered destinations, a resumable flat-file state store with secret redaction
(`lib/publish-state.sh`, verbatim-portable, no rename-sensitive content), `gh api /user`-based
attribution — but as the design spec itself flags, its Homebrew destination assumes a
**project-owned tap**: `brew create --force` a fresh formula file, `git push` straight to
`"${FORK_REMOTE:-upstream}"`, immediately open a self-mergeable PR against `main` of *this same
repo's own remote*. That shape is wrong for `homebrew-core`, which this project doesn't own, can't
merge into on its own schedule, and must not treat as just another fork-and-PR destination
indistinguishable from the Website/WinGet/Scoop destinations sitting right next to it in the same
file. This task rewrites only `__dest_homebrew()` and adds one new shared helper
(`__shallow_clone_or_reuse()`) used by both the Homebrew and WinGet destinations, consistent with the
design's explicit note that neither destination today actually clones the external repo it claims to
publish into.

- [ ] **Step 1: Copy the two files from the backup checkout**

```bash
cp /Users/admachad/Opensource/RH/camel/backup-CAMEL-23703/dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-publish.sh \
   dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-publish.sh

cp /Users/admachad/Opensource/RH/camel/backup-CAMEL-23703/dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/publish-state.sh \
   dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/publish-state.sh

chmod +x dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-publish.sh
```

- [ ] **Step 2: Add the shared shallow-clone helper**

In the freshly-copied `camel-publish.sh`, add this new function immediately after the existing
`resolve_operator()`/`_get_operator()` block (before the `# ── phase 1: preparation` comment):

```sh
# ── shared: shallow clone of an external repo we publish PRs into ──────────────────────────────
# Neither the Homebrew nor the WinGet destination previously cloned the real external repo at all -
# both operated git commands against whatever this script's own working tree happened to be, which
# only ever worked by accident against a project-owned tap. A version-bump PR only ever touches one
# file, so this clones shallow and single-branch rather than paying for either repo's full history
# (both are large, long-lived repos with tens of thousands of commits).
#
# Args: $1 = owner/repo (e.g. "homebrew/homebrew-core"), $2 = destination dir under target/jreleaser
# Sets (via echo, caller captures with $(...)): the destination dir path, or empty + nonzero exit
# on failure. Skips re-cloning if the destination dir already exists (resume-safe, matching the
# rest of this script's idempotent-by-state-file design).
__shallow_clone_or_reuse() {
  _repo_slug="$1"; _dest_name="$2"
  _dest_dir="$MODULE_DIR/target/jreleaser/$_dest_name"

  if [ -d "$_dest_dir/.git" ]; then
    echo "$_dest_dir"
    return 0
  fi

  _default_branch=$(gh api "repos/$_repo_slug" --jq .default_branch 2>/dev/null) || {
    _error "  could not resolve default branch for $_repo_slug via gh api" >&2
    return 1
  }
  [ -n "$_default_branch" ] || { _error "  empty default branch for $_repo_slug" >&2; return 1; }

  _fork_slug="${CAMEL_PUB_FORK_OWNER:-$(gh api /user --jq '.login' 2>/dev/null)}/$(echo "$_repo_slug" | cut -d/ -f2)"
  if ! git clone --depth 1 --branch "$_default_branch" \
      "https://github.com/$_fork_slug.git" "$_dest_dir" >&2; then
    _error "  shallow clone of $_fork_slug failed (does the fork exist yet? 'gh repo fork $_repo_slug --clone=false')" >&2
    return 1
  fi
  git -C "$_dest_dir" remote add upstream-repo "https://github.com/$_repo_slug.git" >&2 || true

  echo "$_dest_dir"
}
```

- [ ] **Step 3: Rewrite `__dest_homebrew()` for the real `homebrew-core` PR flow**

Replace the entire existing `__dest_homebrew()` function body with:

```sh
__dest_homebrew() {
  [ "$_failed" -eq 1 ] && return 0

  if [ "$(state_current_status homebrew)" = "done" ]; then
    _log "Destination 2 (Homebrew): already done (PR opened; merge timing is homebrew-core's own maintainers' call, not ours)."; return 0
  fi

  _log "Destination 2: homebrew-core..."

  FORMULA_SRC="$MODULE_DIR/target/jreleaser/package/camel-cli/brew/Formula/${BREW_FORMULA}.rb"
  if [ ! -f "$FORMULA_SRC" ]; then
    _error "  Rendered formula not found: $FORMULA_SRC (did the JReleaser destination run first?)"; state_mark homebrew failed; return 1
  fi

  core_dir=$(__shallow_clone_or_reuse "homebrew/homebrew-core" "homebrew-core") || {
    state_mark homebrew failed; return 1
  }

  is_first_release=1
  [ -f "$core_dir/Formula/a/${BREW_FORMULA}.rb" ] && is_first_release=0

  BRANCH="camel-publish-$VERSION-brew"
  ( cd "$core_dir" \
    && git fetch upstream-repo "$(git symbolic-ref --short HEAD)" >&2 \
    && git checkout -B "$BRANCH" "upstream-repo/$(git symbolic-ref --short HEAD)" >&2 ) || {
    _error "  could not branch homebrew-core checkout"; state_mark homebrew failed; return 1
  }

  if [ "$is_first_release" -eq 1 ]; then
    _log "  First release of $BREW_FORMULA: scaffolding via 'brew create --force'..."
    ( cd "$core_dir" && HOMEBREW_NO_AUTO_UPDATE=1 brew create --force --set-name "$BREW_FORMULA" \
        "$(sed -n 's/^[[:space:]]*url "\(.*\)"/\1/p' "$FORMULA_SRC" | head -n1)" >&2 ) || {
      state_mark homebrew failed; return 1
    }
    cp -p "$FORMULA_SRC" "$core_dir/Formula/a/${BREW_FORMULA}.rb"
    ( cd "$core_dir" && git add "Formula/a/${BREW_FORMULA}.rb" \
        && git commit -m "$BREW_FORMULA $VERSION (new formula)" >&2 )
  else
    _log "  Subsequent release of $BREW_FORMULA: 'brew bump-formula-pr' locally, then push+PR ourselves (bump-formula-pr's own --no-pull-request bypasses its interactive/network PR step, which this script's own gh pr create below replaces)..."
    new_url="$(sed -n 's/^[[:space:]]*url "\(.*\)"/\1/p' "$FORMULA_SRC" | head -n1)"
    new_sha256="$(sed -n 's/^[[:space:]]*sha256 "\(.*\)"/\1/p' "$FORMULA_SRC" | head -n1)"
    ( cd "$core_dir" && HOMEBREW_NO_AUTO_UPDATE=1 brew bump-formula-pr --no-browse --no-pull-request \
        --url="$new_url" --sha256="$new_sha256" "$BREW_FORMULA" >&2 ) || {
      state_mark homebrew failed; return 1
    }
  fi

  ( cd "$core_dir" && git push "${CAMEL_PUB_FORK_REMOTE:-origin}" "$BRANCH" 2>&1 | tail -5 ) || {
    state_mark homebrew failed; return 1
  }

  ( cd "$core_dir" && gh pr create \
      --repo homebrew/homebrew-core \
      --base "$(git symbolic-ref --short HEAD | sed 's/^.*\///')" \
      --head "$(gh api /user --jq '.login'):$BRANCH" \
      --title "$BREW_FORMULA $VERSION" \
      --body "_Published by camel-publish.sh on behalf of $_operator_$(if [ -n "$_attribution_line" ]; then printf '\n\n%s' "$_attribution_line"; fi)_" \
      2>&1 | tail -3 ) || true  # PR creation best-effort, matching every other destination in this file

  # state_mark homebrew done means "PR opened," never "merged" - merge timing and BrewTestBot's
  # bottle-building CI belong to homebrew-core's own maintainers, not to this script.
  state_mark homebrew done
  _log "  Homebrew: PR opened against homebrew-core (not yet merged)."
}
```

- [ ] **Step 4: Add the same shallow-clone call to `__dest_winget()`**

In the existing `__dest_winget()` function, replace the placeholder body (`: >
"$MODULE_DIR/target/jreleaser/winget-manifest.yaml"`) with a real clone call at the top, keeping
everything else in that function as-is (its actual manifest-generation logic is unrelated to this
plan's scope and stays a placeholder until whoever implements WinGet publishing fills it in — this
step only fixes the missing-clone gap the design explicitly calls out, not the whole destination):

```sh
  winget_dir=$(__shallow_clone_or_reuse "microsoft/winget-pkgs" "winget-pkgs") || {
    state_mark winget failed; return 1
  }
  BRANCH="camel-publish-$VERSION-winget"
  ( cd "$winget_dir" && git checkout -B "$BRANCH" ) 2>/dev/null || true
```

- [ ] **Step 5: Remove the now-superseded `publish` stub from `camel-package.sh`**

In `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh`, remove:

```sh
if [ "$SUBCOMMAND" = "publish" ]; then
  # Publication is intentionally unimplemented: where each packager's artifacts get
  # pushed (e.g. Homebrew to the project's own tap vs. homebrew-core) is a decision
  # for the Apache Camel PMC, not something this script should default on its own.
  # This also covers the Homebrew dual-formula gap noted in jreleaser.yml above -
  # both are blocked on that same publish-destination decision.
  echo "Error: 'publish' is not yet implemented; awaiting a PMC decision on publish destinations." 1>&2
  exit 2
fi
```

and its `usage` line's `<prepare|publish>` (change to `<prepare>`, since `publish` moves entirely to
the new `camel-publish.sh` — this matches the two scripts already being separate top-level entry
points, e.g. `camel-validate.sh`, rather than `publish` staying a dead branch of `camel-package.sh`
that nothing calls). Also remove `publish` from the `case "$SUBCOMMAND" in prepare|publish) ;; ...`
line, changing it to `prepare) ;;`.

- [ ] **Step 6: Verify the new script parses and its help/usage path works**

Run: `sh -n dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-publish.sh`

Expected: no output, exit 0 (POSIX `sh` syntax check — catches the kind of bash-only syntax, e.g.
unquoted indirect `${!var}` expansion, that the *prior* iteration's file actually had in its now-
deleted `__run_hook` helper; this task's rewrite avoids introducing any).

Run: `dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-publish.sh 2>&1 | head -3`

Expected: `Usage: camel-publish.sh <version> --channel <stable|lts> [--lts-line X.Y]` printed to
stderr, exit 2 (no version argument given — confirms the script's own argument parsing still works
after the edits above).

- [ ] **Step 7: Commit**

```bash
git add dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-publish.sh \
        dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/publish-state.sh \
        dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh
git commit -m "CAMEL-23703: Add camel-publish.sh with a real homebrew-core PR workflow"
```

---

### Task 14: Cross-check self-update/doctor against the Homebrew rename

**Files:** none created or modified — this task is pure verification that Tasks 1-8 (already
authored with `apache-camel` baked in, per the "Deviations" section's sixth point above) and Tasks
9-13 (the actual rename) agree with each other.

**Interfaces:** none.

- [ ] **Step 1: Confirm no stray `camel-cli`/`"camel"`-as-formula-name references remain**

Run: `grep -rn "brew upgrade camel-cli\|Cellar.*camel-cli\|Cellar/camel/" dsl/camel-jbang/`

Expected: no output. (This should already be clean — Task 1's `scanHomebrew()` and Task 5's
`refusalMessage()` were both fixed in place earlier in this plan, before Task 9 existed as a
separate section, specifically to avoid a stale `camel-cli` reference sitting in the plan for eight
tasks. This step exists to catch a regression if those two fixes get reverted or re-diverge during
implementation, not because they're expected to still be wrong.)

- [ ] **Step 2: Run the full `camel-jbang-core` and `camel-launcher` test suites once more**

Run: `mvnd test -pl dsl/camel-jbang/camel-jbang-core,dsl/camel-jbang/camel-launcher -Dci.env.name=local`

Expected: BUILD SUCCESS — confirms Tasks 9-13's shell/template/YAML-only changes (no Java touched)
didn't regress anything from Tasks 1-8, and that Task 1/5's `apache-camel` fixes are still in place
and passing.

- [ ] **Step 3: No commit** (nothing changed in this task; it's a pure checkpoint)

---

### Task 15: Documentation for the Homebrew rename

**Files:**
- Modify: `docs/user-manual/modules/ROOT/pages/camel-jbang-launcher-install.adoc` (created by Task 8
  — this task adds a section that didn't exist when Task 8 was written)
- Modify: `docs/user-manual/modules/ROOT/pages/camel-4x-upgrade-guide-4_22.adoc`
- Modify: `docs/user-manual/modules/ROOT/pages/release-guide.adoc`

**Interfaces:** none (documentation only).

- [ ] **Step 1: Add a package-manager install section to `camel-jbang-launcher-install.adoc`**

Task 8's version of this file only documents the `install.sh`/`install.ps1` web installer — it never
mentions Homebrew/Chocolatey/WinGet/Scoop/SDKMAN at all, so there is no existing "Homebrew install
instructions" line to update in place; this step adds the section fresh. Insert it after the
existing `== Where the CLI is installed` section (before `== Upgrading and switching versions`):

```adoc
== Installing via a package manager

The Camel CLI Launcher is also published through several package managers, each with its own
install location and its own upgrade command — `camel self-update` (above) refuses to run against
any of these, naming the right command instead:

[cols="1,2,1"]
|===
|Manager |Install |Upgrade

|Homebrew (macOS/Linux)
|`brew install apache-camel`
|`brew upgrade apache-camel`

|Chocolatey (Windows)
|`choco install camel-cli`
|`choco upgrade camel-cli`

|WinGet (Windows)
|`winget install Apache.CamelCLI`
|`winget upgrade Apache.CamelCLI`

|Scoop (Windows)
|`scoop install camel-cli`
|`scoop update camel-cli`

|SDKMAN (macOS/Linux)
|`sdk install camel`
|`sdk upgrade camel`
|===

Only the Homebrew formula uses the `apache-<project>` naming convention (`apache-camel`, following
`apache-spark`/`apache-flink`'s own homebrew-core formulas) — Chocolatey, WinGet, Scoop, and SDKMAN
keep their existing `camel`/`camel-cli` package identities.
```

- [ ] **Step 2: Add the Homebrew rename entry to the upgrade guide**

In `docs/user-manual/modules/ROOT/pages/camel-4x-upgrade-guide-4_22.adoc`, add a second `====`
subsection immediately after the `==== camel self-update and camel doctor` subsection Task 8 already
added:

```adoc
==== Homebrew formula renamed to apache-camel

The Homebrew formula for the xref:camel-jbang-launcher.adoc[Camel CLI Launcher] is renamed from
`camel` to `apache-camel` and now lives in `homebrew-core` directly (`brew install apache-camel`)
instead of a project-owned tap, following the same `apache-<project>` convention Apache Spark and
Apache Flink already use for their own CLI formulas. This is a breaking change to the install
command for existing Homebrew users: `brew install camel` no longer resolves to this project once
the rename lands. The installed executable name is unaffected — it is still `camel`. No other
package manager's package identity changes.
```

- [ ] **Step 3: Document the `homebrew-core` submission process in `release-guide.adoc`**

In `docs/user-manual/modules/ROOT/pages/release-guide.adoc`, add a new `== Publishing to Homebrew`
section immediately after the existing `== Publishing the Release` section (before `== Publish xsd
schemas`):

```adoc
== Publishing to Homebrew

Run from the `dsl/camel-jbang/camel-launcher` module, after `== Publishing the Release` above has
completed and the release artifacts exist in Maven Central:

[source,bash]
----
src/jreleaser/bin/camel-publish.sh <Camel version> --channel stable
----

* **First release of a formula** (`apache-camel` itself, or a new `apache-camel@X.Y` LTS line):
  `camel-publish.sh` runs `brew create --force` against a shallow clone of your own
  `homebrew/homebrew-core` fork, commits the generated formula, and opens a PR against
  `homebrew-core`'s `main` branch. Before running it, validate the formula locally with
  `src/jreleaser/bin/camel-validate.sh homebrew --channel stable` (see below) — this is the bar
  `homebrew-core` maintainers and BrewTestBot's own CI will hold the PR to.
* **Every subsequent release** of an already-merged formula: `camel-publish.sh` uses Homebrew's own
  `brew bump-formula-pr` against the new version's URL/SHA-256, then pushes and opens the PR itself.
* Either way, `state_mark homebrew done` in `target/jreleaser/publish-state.json` means "PR opened,"
  never "merged" — merge timing and bottle-building are `homebrew-core`'s own maintainers' and
  BrewTestBot's call, not this project's.

=== Local validation before submitting

[source,bash]
----
src/jreleaser/bin/camel-validate.sh homebrew --channel stable
----

Creates a throwaway local Homebrew tap, runs `brew style --fix` and `brew audit --new --strict`
against the rendered formula (`--new` specifically — the additional checks `homebrew-core` enforces
only for a first-time formula submission), then does a real `brew install`/`camel init`/`brew
uninstall` round-trip. Requires Homebrew installed locally; the script `SKIP`s (not fails) if it
isn't.
----
```

- [ ] **Step 4: Verify manually**

Read back all three modified files to confirm every `xref:` target resolves to a real page in this
same module, per this repo's `CLAUDE.md` Documentation Conventions rule (internal links use `xref:`,
never a bare `https://camel.apache.org/...` URL) — `camel-jbang-launcher-install.adoc`'s new table
has no `xref:` targets to check (it's plain package-manager command names), but the upgrade-guide
entry's `xref:camel-jbang-launcher.adoc[...]` does.

- [ ] **Step 5: Commit**

```bash
git add docs/user-manual/modules/ROOT/pages/camel-jbang-launcher-install.adoc \
        docs/user-manual/modules/ROOT/pages/camel-4x-upgrade-guide-4_22.adoc \
        docs/user-manual/modules/ROOT/pages/release-guide.adoc
git commit -m "CAMEL-23703: Document the apache-camel Homebrew rename and its release process"
```

---

## Final verification

- [ ] Run the full module test suite once more end-to-end: `mvnd test -pl dsl/camel-jbang/camel-jbang-core,dsl/camel-jbang/camel-launcher -Dci.env.name=local` — expect BUILD SUCCESS.
- [ ] Run `mvnd formatter:format impsort:sort -pl dsl/camel-jbang/camel-jbang-core,dsl/camel-jbang/camel-launcher -Dci.env.name=local` once more and confirm `git status` shows no further changes (CI fails on uncommitted formatting diffs).
- [ ] Confirm every shell script this plan touched or added still parses cleanly: `for f in dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-package.sh dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-validate.sh dsl/camel-jbang/camel-launcher/src/jreleaser/bin/camel-publish.sh dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/assert-camel-cli.sh dsl/camel-jbang/camel-launcher/src/jreleaser/bin/lib/publish-state.sh; do sh -n "$f" || echo "SYNTAX ERROR: $f"; done` — expect no `SYNTAX ERROR` lines.
- [ ] Render both `apache-camel` formula variants once more via `camel-package.sh prepare` (stable and `--channel lts --lts-line 4.22`) and run `camel-validate.sh homebrew` against each — expect `PASS: homebrew style/audit passed` (or a fully-explained `WARN:` list, not a `FAIL:`) on a machine with Homebrew installed.
- [ ] This work continues the already-assigned `CAMEL-23703` (this plan's own branch, `CAMEL-23703-launcher-self-update`, and its already-merged prerequisite PR #24915 both confirm the ticket exists and is in progress) — no new JIRA ticket is needed; just keep `fixVersions`/status current per this repo's `CLAUDE.md` JIRA rules once implementation actually starts.
