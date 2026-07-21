# Camel CLI Launcher: Self-Update, Doctor, and Homebrew Distribution

Date: 2026-07-21 (revised 2026-07-22: added the Homebrew `apache-camel` rename/distribution scope)
Status: Draft (approved for planning)

## Problem

The `camel` launcher installed via `install.sh`/`install.ps1` (see
`docs/user-manual/modules/ROOT/pages/camel-jbang-launcher-install.adoc`) has no way to update
itself. The documented upgrade path today is "re-run the installer" — the user has to remember to
do that, there is no notice that a newer release exists, and there is no way to see whether a
machine has ended up with more than one Camel CLI installation (e.g. both Homebrew and the web
installer) fighting over which `camel` binary is actually on `PATH`.

This is a distinct problem from the existing, unrelated `camel update` command
(`org.apache.camel.dsl.jbang.core.commands.update`), which updates a *user's Camel project*
dependencies/code via OpenRewrite recipes. That name is taken.

Related but out of scope: CAMEL-21425 ("camel-jbang - Add command to easily upgrade or downgrade
the CLI version") asks for the same kind of explicit-update control for the JBang-based
`camel` CLI, motivated by not wanting JBang's own silent auto-update checker calling the internet.
This design applies the same "explicit, operator-controlled" philosophy to the native launcher
specifically, using the manifest infrastructure CAMEL-23703 already built.

A second, related problem folded into this design: the launcher's Homebrew formula is currently
named `camel` and generated as a custom, project-specific template published to a destination the
project has never actually decided on ("own tap vs. homebrew-core" is an open `KNOWN GAP` in
`jreleaser.yml` today). Apache Spark and Apache Flink both solve this by shipping their CLI as an
`apache-<project>`-named formula directly in `homebrew-core` itself (`apache-spark`, `apache-flink`),
gaining Homebrew's own bottle-building CI and audit process instead of maintaining a project-owned
tap. This design adopts that same path for Camel: rename the formula to `apache-camel` and target
`homebrew-core` as the real publish destination, closing the `KNOWN GAP` rather than leaving it
open.

## Goals

- Let users know when a newer launcher release is available, without any silent/automatic install.
- Let users install that update with one command.
- Never interfere with an installation managed by a package manager (Homebrew, Chocolatey, WinGet,
  Scoop, SDKMAN) or by JBang — those have their own update mechanisms and their own bookkeeping.
- Let users detect when they have more than one Camel CLI installation on the same machine.
- Rename the Homebrew formula `camel` → `apache-camel` and submit it to `homebrew-core`, following
  the `apache-spark`/`apache-flink` conventions, resolving the long-open "own tap vs. homebrew-core"
  gap.
- Keep formula generation inside the existing JReleaser pipeline (`jreleaser.yml` +
  `camel-package.sh`) rather than forking off a separate, hand-maintained process — the same single
  pipeline that already generates the Scoop/Chocolatey/WinGet/SDKMAN artifacts.

## Non-goals

- No fully automatic/silent update. Every install action is user-initiated.
- No *active* environment health check (PATH-correctness validation, network connectivity probing,
  pass/fail judgments) beyond install-location conflict detection. `doctor` is scoped to "how many
  camel installs exist and which one wins," not a general troubleshooting tool. This does **not**
  exclude the static, inert Java/OS/locale banner described below — reporting `java.version` or
  `os.arch` is not a health check any more than `mvn --version` printing the same fields is; there's
  no judgment being made, just information a user would otherwise have to gather by hand for a bug
  report.
- No changes to `install.sh`/`install.ps1` themselves — they already have their own update paths
  and are unaffected by this feature.
- No renaming of the Chocolatey/WinGet/Scoop/SDKMAN package identities (`camel`/`camel-cli`) — only
  the Homebrew formula is renamed. Those package managers aren't adopting a new upstream convention
  the way Homebrew's `apache-<project>` naming is, and renaming them isn't something this design
  has a reason to do.
- No versioned/LTS formula work beyond the single `apache-camel@X.Y` line described below — no
  general multi-version support matrix.

## Scope

Both new commands live in `dsl/camel-jbang/camel-launcher` only, registered through the existing
`Plugin` SPI (`org.apache.camel.dsl.jbang.core.common.Plugin`) the same way `ValidatePlugin`,
`TestPlugin`, `GeneratePlugin`, `KubernetesPlugin`, and `TuiPlugin` already are, in
`CamelLauncherMain.postAddCommands()`. This is deliberate: the version-directory/manifest
mechanism these commands depend on only exists for web-installer installs. Plain
`camel-jbang-core` (used by the JBang-based CLI) is untouched.

The Homebrew rename/distribution work touches a different, non-overlapping set of files: the
JReleaser packaging config and templates (`jreleaser.yml`,
`src/jreleaser/distributions/camel-cli/**`), the release scripts
(`src/jreleaser/bin/camel-package.sh`), and (as an external, separate-timeline deliverable) a PR
against `homebrew/homebrew-core`. It doesn't touch `install.sh`/`install.ps1` (the web installer is
unaffected) and doesn't touch the `SelfUpdateCommand`/`DoctorCommand` Java code beyond the small
text changes noted under "Interaction with self-update and doctor" below.

## Components

### `InstallDetector`

Central piece both commands depend on.

- **`locate()`** — determines where the *currently running* launcher lives, by resolving the path
  of the JAR/executable backing this JVM process. Compares it against the web installer's own
  version directory:
  - POSIX: `${XDG_DATA_HOME:-$HOME/.local/share}/camel-cli/versions/<version>`
  - Windows: `%LOCALAPPDATA%\Apache Camel\cli\versions\<version>`

  If the running path is under one of those, the install method is `WEB_INSTALLER`. Otherwise,
  best-effort path-substring matching identifies the likely manager:

  | Manager    | Marker substring              |
  |------------|--------------------------------|
  | Homebrew   | `/Cellar/`                     |
  | Chocolatey | `\chocolatey\`                 |
  | WinGet     | `\WinGet\Packages\`            |
  | Scoop      | `\scoop\apps\`                 |
  | SDKMAN     | `/.sdkman/candidates/`         |
  | JBang      | `/.jbang/`                     |

  An unrecognized path returns `UNKNOWN`.

- **`scanKnownLocations()`** — for `doctor`. Independently probes every location in the table
  above (plus the web installer's own versions directory) for the presence of a `camel`
  executable (`bin/camel.sh`/`camel.bat`/`camel.cmd`, or the WinGet `camel.exe` bootstraps),
  regardless of which one the current process happens to be running from. Returns the list of all
  installations found, not just the active one.

- **`resolveActiveOnPath()`** — walks the `PATH` environment variable the same way the shell
  would, returning the first `camel` executable it finds. Used by `doctor` to report which of the
  installations `scanKnownLocations()` found is actually the one that wins.

### `UpdateChecker` (background notice)

Runs unconditionally from `postAddCommands()`, skipped when the invoked command is itself
`self-update` (no point checking for an update while installing one).

1. Read the cache file (same root the installer already uses: `camel-cli/update-check.properties`
   next to the versions directory).
2. If the cache is missing or its `last_checked` timestamp is 24h+ old, spawn a daemon thread that
   fetches `latest.properties` with a short timeout (~3s connect, ~3s read) and rewrites the cache
   with `{last_checked, latest_version}`. This never blocks the command the user actually ran —
   if the JVM exits before the thread finishes, the check is simply retried on a later invocation.
3. If the *existing* cache (from a prior run) already shows a version newer than
   `DefaultCamelCatalog().getCatalogVersion()` (the same value `camel version get` already
   reports — `camel-launcher` releases in lockstep with the rest of the Camel reactor, so this is
   already the correct "installed version" with no new version-stamping needed), print one line to
   stderr before the command executes:

   ```
   camel: a new version is available (4.22.0 → 4.23.0). Run 'camel self-update' to install it.
   ```

4. Any network/timeout/parse failure during the check is swallowed silently; the cache timestamp
   still updates, so a persistent failure doesn't retry on every single invocation.
5. Opt-out: `CAMEL_SELF_UPDATE_CHECK=false` disables the background check entirely, matching the
   installer's existing `CAMEL_INSTALL_*` env-var naming convention.
6. Detection is skipped (no check, no notice) when `InstallDetector.locate()` returns anything
   other than `WEB_INSTALLER` — there's nothing useful to say if this isn't a web-installer
   install.

### `ManifestFetcher`

New Java code — no existing manifest-reading logic exists to reuse. `WebsiteManifestGenerator`
(`camel-launcher/src/jreleaser/java/`) only *writes* manifests at release-build time, and is
excluded from the runtime classpath by design (default-package, JDK-only build tool). The only
existing *readers* are the shell parsers inside `install.sh`/`install.ps1`.

`ManifestFetcher` fetches `<manifest_base_url>/latest.properties` or `<manifest_base_url>/X.Y.Z.properties`
over HTTPS (`java.net.http.HttpClient`) and strictly parses it, mirroring the shell parser's
validation: exactly four `key=value` lines (`format`, `version`, `tar_sha256`, `zip_sha256`),
tolerating `#`-prefixed comment/license-header lines and CRLF line endings, no duplicate keys, no
unknown keys, `format` must equal `1`, `version` must match `X.Y.Z`, both checksums must be
64-character lowercase hex.

Test seams mirror the installer's existing ones: `CAMEL_SELF_UPDATE_MANIFEST_BASE_URL` and
`CAMEL_SELF_UPDATE_MAVEN_BASE_URL` override the defaults (`https://camel.apache.org/camel-cli/releases`
and `https://repo1.maven.org/maven2/org/apache/camel/camel-launcher`), exactly like
`CAMEL_INSTALL_MANIFEST_BASE_URL`/`CAMEL_INSTALL_MAVEN_BASE_URL` do for `install.sh`. Production
installs never set these.

### `SelfUpdateCommand` (`camel self-update`)

- `camel self-update` — install the latest version, if newer than what's running.
- `camel self-update --version X.Y.Z` — install a specific version (upgrade or downgrade).
- `camel self-update --check` — report only ("a newer version is available" / "already on the
  latest version"), install nothing.

Refuses immediately, with a message naming the detected manager and its own upgrade command (e.g.
"this install is managed by Homebrew — run `brew upgrade apache-camel`"), if
`InstallDetector.locate()` is not `WEB_INSTALLER`. An `UNKNOWN` result gets a generic message
pointing at the install docs instead of a specific command.

Update sequence (always fetches the **`.zip`** archive, even on Linux/macOS —
`java.util.zip` handles it with zero new dependencies, avoiding a from-scratch tar.gz parser or
shelling out to an external `tar` binary):

1. Fetch and parse the manifest via `ManifestFetcher`.
2. Compare `manifest.version` against the running version (`VersionHelper.compare`, already used
   elsewhere in `camel-jbang-core`). If not newer and `--version` wasn't given, print "already on
   the latest version" and exit 0.
3. Download the `.zip` archive from Maven Central to a staging directory.
4. Verify its SHA-256 against `manifest.zip_sha256`.
5. Validate archive entries before extracting: reject absolute paths, `../` traversal, symlink or
   hardlink entries, and more than one top-level directory — the same protections
   `install.sh`'s `validate_tar` already applies to the tar.gz.
6. Extract to a **new** `versions/<version>` directory (existing version directories are left
   untouched, matching the currently-documented behavior — "must be removed manually").
7. Smoke-test the staged launcher (run its `version` command) before activating, matching
   `install.sh`'s `verify_staged` safety guarantee.
8. Atomically activate: swap the POSIX symlink, or rewrite the Windows `.cmd` shim, to point at the
   new version.

Because the new version is staged in its own directory and only the symlink/shim is swapped, the
currently-running process (reading from the old version's still-intact files) is never touched
mid-update — this is safe on both POSIX and Windows, no locked-file concerns.

Failures (network error, checksum mismatch, archive validation failure, non-web-installer
detection) are loud: clear message, non-zero exit, and the active version is left unchanged.

### `DoctorCommand` (`camel doctor`)

Scoped to multi-install conflict detection, plus a static environment banner — not a general
health-check tool (see the Non-goals note on the distinction).

1. Print a `mvn --version`-style environment banner (see below) — always, regardless of whether a
   conflict is found.
2. `InstallDetector.scanKnownLocations()` — find every Camel CLI installation on the machine
   across all known managers plus the web installer.
3. `InstallDetector.resolveActiveOnPath()` — determine which one `PATH` actually resolves to.
4. Print every installation found, marking the active one.
5. If more than one installation is found, print a warning for each non-active one (which manager
   owns it) and exit non-zero (matching the convention of tools like `brew doctor`/`flutter
   doctor`, so this is scriptable in CI). If exactly one installation is found, exit 0 with a
   simple confirmation.

**Environment banner**: static, deterministic fields read directly from JVM system properties —
no probing, no judgment, nothing that can be "wrong" the way a health check's pass/fail can be.
Mirrors the fields `mvn --version` reports, using the equivalent Camel identity in place of
Maven's:

```
Apache Camel CLI 4.22.0
Camel home: /opt/homebrew/Cellar/apache-camel/4.22.0/libexec
Java version: 21.0.4, vendor: Eclipse Adoptium, runtime: /opt/homebrew/opt/openjdk@21/...
Default locale: en_US, platform encoding: UTF-8
OS name: "mac os x", version: "14.6.1", arch: "aarch64", family: "mac"
```

- "Apache Camel CLI \<version>" — same version `camel version get`/`DefaultCamelCatalog().getCatalogVersion()` already report.
- "Camel home" — the path `InstallDetector.locate()` already resolves for the running process, so
  no new resolution logic is needed.
- Java/locale/OS lines — `java.version`/`java.vendor`/`java.home`, `Locale.getDefault()` +
  `file.encoding`, `os.name`/`os.version`/`os.arch` system properties, plus a simple `family`
  derivation (`mac`/`windows`/`unix`) matching Maven's own `os.name`-based classification.

Example output with a conflict (banner always first, then the install report):

```
$ camel doctor
Apache Camel CLI 4.22.0
Camel home: /opt/homebrew/Cellar/apache-camel/4.22.0/libexec
Java version: 21.0.4, vendor: Eclipse Adoptium, runtime: /opt/homebrew/opt/openjdk@21/...
Default locale: en_US, platform encoding: UTF-8
OS name: "mac os x", version: "14.6.1", arch: "aarch64", family: "mac"

Found 2 Camel CLI installations:
  * ~/.local/share/camel-cli/versions/4.22.0  (web installer)  <- active (on PATH)
    /opt/homebrew/Cellar/apache-camel/4.21.0  (Homebrew)

Warning: more than one Camel CLI installation was found. The one marked active is the one your
shell currently runs; the others are unused but still present. See
<install docs xref> for how each installation method is normally removed.
```

## Data flow summary

```
Every `camel` invocation (except `self-update` itself)
  -> UpdateChecker: read cache, maybe kick off background fetch, maybe print notice
  -> (command executes as normal)

camel self-update [--version X.Y.Z | --check]
  -> InstallDetector.locate() -- refuse if not WEB_INSTALLER
  -> ManifestFetcher.fetch()
  -> compare versions -- stop here if --check or already current
  -> download .zip -> verify sha256 -> validate entries -> extract -> smoke-test -> activate

camel doctor
  -> InstallDetector.scanKnownLocations()
  -> InstallDetector.resolveActiveOnPath()
  -> print report, exit non-zero if >1 installation found
```

## Error handling

| Situation | Behavior |
|---|---|
| Background check: network/timeout/parse failure | Silent, cache timestamp still updates |
| Background check: non-`WEB_INSTALLER` install | Check skipped entirely, no notice ever |
| `self-update`: non-`WEB_INSTALLER` install | Refuse with manager-specific (or generic) guidance, exit non-zero |
| `self-update`: checksum mismatch | Fail loudly, exit non-zero, active version unchanged |
| `self-update`: archive validation failure (traversal/symlink/multi-root) | Fail loudly, exit non-zero, active version unchanged |
| `self-update`: staged smoke-test fails | Fail loudly, exit non-zero, activation skipped |
| `doctor`: multiple installations found | Report all, exit non-zero |
| `doctor`: single installation found | Report it, exit 0 |

## Homebrew Distribution: Rename to `apache-camel`

### Formula shape (unversioned `apache-camel`)

Following `apache-spark.rb`/`apache-flink.rb`'s structure, adapted where Camel's own situation
differs from theirs:

- Class `ApacheCamel`, formula file `apache-camel.rb`. The installed executable stays **`camel`**
  (Flink does the same thing: formula `apache-flink`, command `flink`).
- `url` stays the Maven Central `search.maven.org/remotecontent?filepath=...` redirector form the
  current template already uses — **not** the ASF dist-mirror/`closer.lua` pattern Spark/Flink use.
  Their formulas point at the ASF mirror network because that's *their own* project's canonical
  distribution channel; Camel's canonical channel for `camel-launcher`'s binary is already Maven
  Central (same place `install.sh`, `ManifestFetcher`, and today's custom template all fetch from).
  `FormulaAudit::Urls` already requires the `search.maven.org` form for Maven Central artifacts, so
  this is a recognized, accepted source type in `homebrew-core`, not a workaround. No `mirror` field
  is needed — Maven Central is a stable global CDN, unlike the ASF mirror network Spark/Flink's
  `mirror` line exists to work around. No changes to `release-distro.sh` or any new ASF
  dist-publishing step are required.
- `license "Apache-2.0"`, a `livecheck` block that regexes either the download page or Maven
  metadata, the way `apache-flink.rb`/`apache-flink@1.rb` do.
- **No `bottle do ... end` block in the submitted PR.** Homebrew's own CI (BrewTestBot) computes and
  commits real bottle SHA-256s automatically after the PR's tests pass; writing placeholder hashes
  ourselves would be incorrect and would get flagged in review.
- `depends_on "openjdk@21"` — pinned, matching Camel's actual minimum JDK (21, not the general
  project README's "17+") and matching Spark/Flink's own pinned-major-version dependency style.
  Obtained by bumping `project.languages.java.version` to `21` in `jreleaser.yml` rather than
  hand-writing the `depends_on` line into the template's Ruby: JReleaser's `JAVA_BINARY`
  distribution type auto-derives `depends_on "openjdk@<N>"` from that field (noted in
  now-superseded comments from an earlier iteration of this same `jreleaser.yml`, which observed the
  generated formula included `depends_on "openjdk@17"` when the field was `17`). This keeps the JDK
  version a single source of truth instead of duplicating it into template text, consistent with
  this design's "single generation pipeline" goal. Confirm the auto-derivation still holds for the
  actually-pinned JReleaser version before relying on it in the implementation plan.
- `install` drops the current template's custom `CAMEL_FALLBACK_JAVA`/`write_env_script` wrapper
  logic, adopting Homebrew's own `Language::Java.overridable_java_home_env("21")` helper (same as
  `apache-spark.rb`). This still lets a user's own `JAVA_HOME`/`JAVACMD` win via the launcher's
  existing internal resolution order — it just supplies Homebrew's pinned JDK as the default instead
  of a project-specific fallback env var.
- No custom `caveats` method — Homebrew auto-generates the standard keg-only PATH guidance for free
  on any `keg_only` formula; `apache-flink@1.rb` doesn't define one either. The current template's
  hand-written PATH caveat text is dropped.
- `test do` block: keep the existing `shell_output("#{bin}/camel --version")` /
  `assert_match version, output` check — no need for Spark/Flink's heavier PTY/cluster-lifecycle
  tests, since Camel CLI has no cluster to start.

### Versioned formula (`apache-camel@X.Y`)

Finishes the `KNOWN GAP` the current `jreleaser.yml` leaves open for LTS lines:

- Naming matches the existing `BREW_LTS_FORMULA="camel@$LTS_LINE"` logic's dotted-version style,
  renamed: formula `apache-camel@4.20`, class `ApacheCamelAT420` (dots stripped, matching the
  verified real-world `ApacheFlinkAT1` class-naming convention).
- `keg_only :versioned_formula` plus Homebrew's own automatic keg-only caveat (no custom one, as
  above).
- `depends_on` pins whatever JDK that specific LTS line's own floor requires, not necessarily `21` —
  mirroring `apache-flink@1.rb` depending on `openjdk@11` (Flink 1.x's real floor) while
  `apache-flink.rb` (2.x) depends on `openjdk@21`.
- `deprecate!`/`disable!` dates are set from that LTS line's own documented Camel EOL dates (read
  from `supported-lts.yml`'s `supportEnds` field for that line), not invented — same lifecycle
  `apache-flink@1.rb` uses to retire itself.
- Both formulas source from Maven Central, same reasoning as the unversioned formula above — just
  different `camel-launcher` version coordinates.

### JReleaser generation stays a single pipeline

The formula is **not** hand-authored as a one-off, externally-maintained file. `formula.rb.tpl` is
edited in place, and `camel-package.sh`'s existing `BREW_FORMULA`/`BREW_CLASS`/`BREW_LTS_FORMULA`
variables are renamed (`"camel"`/`"Camel"` → `"apache-camel"`/`"ApacheCamel"`, and the LTS branch
correspondingly) rather than replaced with new machinery — the same single process that already
generates Scoop/Chocolatey/WinGet/SDKMAN artifacts keeps generating this one too. The `KNOWN GAP`
comment in `jreleaser.yml` about "own tap vs. homebrew-core" is resolved in place (homebrew-core) —
not deleted as dead code, since the decision it was blocking is now made.

### Publish workflow

`camel-package.sh publish` is currently an intentional stub ("not yet implemented; awaiting a PMC
decision"). A prior iteration of this work (found under a sibling `backup-CAMEL-23703` checkout)
already built a resumable, idempotent `camel-publish.sh` orchestrator — ordered destinations
(JReleaser package → Homebrew → website → WinGet → Scoop → SDKMAN → Chocolatey), a flat-file
`publish-state.json` recording per-destination status with secret-key redaction, best-effort
continuation past a failed destination, and `gh api /user`-based operator attribution. That
architecture is sound and worth reusing, but its Homebrew destination assumes a **project-owned
tap** (`brew create --force` a fresh formula file, `git push` to a fork, immediately open a
self-mergeable PR) — the same shape as its Website/WinGet/Scoop destinations. That doesn't fit
`homebrew-core`, which isn't ours to push branches into or merge on our own schedule. This design
adapts just that one destination:

- **First release**: `brew create` scaffolds the initial file locally (as the prior version does),
  validated via the local-tap trick below, then opened as a PR against a fork of
  `homebrew/homebrew-core`. `state_mark homebrew done` means "PR opened," not "merged" — merge
  timing belongs to Homebrew's own maintainers and BrewTestBot's bottle-building CI, not to us.
- **Subsequent releases**: use Homebrew's own `brew bump-formula-pr` (fed the newly computed
  version/url/sha256) against the already-merged formula, instead of re-scaffolding with
  `brew create --force` every time.
- Everything else in that orchestrator (state file, ordered destinations for the other packagers,
  redacted dumps, attribution) carries over unchanged.
- **Neither the current `camel-package.sh` nor the backup's `camel-publish.sh` actually clones
  `homebrew/homebrew-core` or `microsoft/winget-pkgs` today** — their existing Homebrew/WinGet
  destinations operate git commands against whatever the current working tree happens to be, not a
  real checkout of either external repo. This design adds that missing clone step explicitly: a
  **shallow, single-branch clone** of each repo's own default branch (`homebrew/homebrew-core` →
  `main`, `microsoft/winget-pkgs` → `master` — confirmed via `gh api repos/<owner>/<repo>
  --jq .default_branch`, not assumed), i.e. `git clone --depth 1 --branch <default-branch>
  <fork-url>`. A version-bump PR only ever touches one file; there's no reason to pay for either
  repo's full history (both are large, long-lived repos with tens of thousands of commits).

### Local validation hardening

The same prior iteration's `camel-validate.sh` already solves "how do you test a Homebrew formula
without publishing it": recent Homebrew refuses `brew install`/`brew audit` on a bare file path, so
it creates a throwaway local tap (`camel-cli-validators/formulae`), copies the generated formula in,
runs `brew style --fix` + `brew audit` against the tap-qualified name, then does a real
`brew install`/`brew test`/`camel init` round-trip, with `brew untap` cleanup in an EXIT trap. This
design carries that mechanism forward, with one correction: replace its `brew audit --strict` with
**`brew audit --new --strict`** — `--new` enables the additional checks Homebrew only enforces for a
first-time formula submission, which is exactly the bar `apache-camel` needs to clear before
`homebrew-core` will accept it; plain `--strict` alone is the bar for an already-accepted formula
being updated, and understates what's needed here.

### Package-template fixes carried over incidentally

While touching these templates for the rename, two pre-existing gaps between the current branch and
the prior iteration are worth fixing at the same time (unrelated to the rename itself, but in the
same files):

- **Scoop** (`manifest.json.tpl`): restore the `post_install` step that removes the
  `camel-x64.exe`/`camel-arm64.exe` native bootstrap files the zip ships but Scoop never uses —
  currently missing, leaving stray files after `scoop install`.
- **Chocolatey** (`chocolateyinstall.ps1.tpl`): restore the equivalent post-install cleanup of those
  same two native exes (Chocolatey has no per-architecture exe selection; see
  chocolatey/choco#1803), also currently missing.

### Interaction with self-update and doctor

- `InstallDetector`'s Homebrew marker stays the generic `/Cellar/` substring — it doesn't encode a
  formula name, so no code change is needed there regardless of the rename.
- `SelfUpdateCommand`'s refusal message for a Homebrew-managed install (and this design doc's own
  example text) must say `brew upgrade apache-camel`, not a placeholder `camel-cli` package name
  that was never real.
- `DoctorCommand`'s `scanKnownLocations()` default-root probing for Homebrew (documented in the
  implementation plan's Task 1) needs its literal path segment updated from
  `camel-cli`/`camel` to `apache-camel` (e.g. `$(brew --cellar)/apache-camel/`).

### Open items to verify during implementation (not settled by this design)

- Confirm `project.languages.java.version: 21` still auto-derives `depends_on "openjdk@21"` on the
  actually-pinned JReleaser version before relying on it (see "Formula shape" above).
- Whether `supported-lts.yml`'s missing `4.18` line (present in the prior iteration, absent from the
  current branch) was a deliberate retirement or an accidental drop — confirm before relying on the
  file's current contents for the versioned formula's `deprecate!`/`disable!` dates.

## Testing

- Unit tests for `ManifestFetcher` parsing/validation (mirroring the existing shell-installer
  manifest test cases: malformed lines, duplicate/unknown keys, bad checksums, wrong line count).
- Unit tests for `InstallDetector`'s path-substring heuristics (one case per manager marker, plus
  the web-installer and `UNKNOWN` cases).
- Unit tests for `VersionHelper`-based version comparison driving the "already latest" / "newer
  available" / "`--version` downgrade" branches.
- An integration test following the existing `WebsiteInstallerFixture`/`WebsiteInstallTest`
  pattern (local `HttpsServer` serving a fake manifest + zip) driving `camel self-update`
  end-to-end: happy path, checksum mismatch, path-traversal/symlink rejection, `--version`
  downgrade, and refusal on a simulated non-web-installer path.
- An integration test for `camel doctor` against a temp directory tree simulating multiple
  installation layouts (single install / multiple installs / `PATH` pointing at a non-primary one).
- Unit test for the environment banner asserting each line's format (labels, quoting on the OS
  line) independent of the actual host's Java/OS values — assert structure, not literal values that
  would make the test environment-dependent.
- Render `formula.rb.tpl` (both the unversioned and versioned variants) via `camel-package.sh
  prepare`/`package` and run `brew style --fix` + `brew audit --new --strict` against the rendered
  output through the local-tap mechanism described above, plus a real `brew install`/`brew test`
  round-trip — this is the bar the initial `homebrew-core` PR must clear before submission.
  `--new` specifically (not just `--strict`) — see the rationale under "Local validation hardening."
- Regression check for the Scoop/Chocolatey native-exe cleanup fix: render both templates and
  assert neither `camel-x64.exe` nor `camel-arm64.exe` remains after the simulated install step.
- Unit/integration coverage for `camel-package.sh`'s renamed `BREW_FORMULA`/`BREW_CLASS`/
  `BREW_LTS_FORMULA` variables producing `apache-camel`/`ApacheCamel` and
  `apache-camel@X.Y`/`ApacheCamelATxy` respectively.

## Documentation

- `camel-jbang-launcher-install.adoc`: rewrite the "Upgrading and switching versions" section to
  describe `camel self-update` instead of "re-run the installer." Add a short subsection on the
  background notice and its `CAMEL_SELF_UPDATE_CHECK` opt-out. The existing "Where the CLI is
  installed" section is unchanged and becomes the shared source of truth that `InstallDetector`
  and the docs both point at.
- Document `camel doctor` in `camel-jbang-launcher-install.adoc` as well, next to the
  self-update section — both commands depend on the same install-location table that page already
  owns, so keeping them together avoids duplicating that table elsewhere.
- Upgrade guide (`camel-4x-upgrade-guide-4_22.adoc`): add an entry noting the two new commands are
  available as of this release (new user-visible functionality), plus a separate entry noting the
  Homebrew formula rename (`brew install camel` → `brew install apache-camel`) since that's a
  user-visible breaking change to the install command, not merely additive.
- `camel-jbang-launcher-install.adoc`'s Homebrew install instructions get updated to
  `brew install apache-camel` — no tap prefix needed once the formula lives in `homebrew-core`.
- `release-guide.adoc`: document the new `homebrew-core` submission step — first-release PR process
  (`brew create`, local-tap validation, PR against a `homebrew/homebrew-core` fork) versus
  subsequent-release process (`brew bump-formula-pr`) — next to the existing WinGet/SDKMAN/
  Chocolatey release steps it already documents.
