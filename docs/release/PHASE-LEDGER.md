# NEON MAZE — Phase Ledger / Anti-Forgetting Record

> این Ledger مرجع وضعیت فازهاست. هیچ عامل یا چت جدیدی نباید وضعیت را بر اساس حدس، حافظهٔ چت یا ادعای گزارش AI قبلی تغییر دهد.

## Project identity

- Project: NEON MAZE Android puzzle game
- Delivery target: private contest APK + reproducible GitHub Actions build
- Not a Google Play/public store release
- Primary priorities: complete implementation, correct real behavior, quality/performance/accessibility, reproducible APK output
- Active source baseline: Part01–Part08 reconstructed from the main accepted patches in order
- Current handoff: Phase03 Part A Build Foundation patch (owner instructed Phase03 to begin)
- Patch identity: `NEON-MAZE-PHASE02-INTEGRATION` (the uploaded source did not provide an authoritative original Git HEAD).
- Patch base: synthetic snapshot of the reconstructed, accepted Part01–Part08 tree.
- Synthetic baseline tree SHA: `93a1b6b00a73a4039906db32cf1d14a108ad0d5d` (tree identity only; not claimed as the original repository commit).

## Owner decisions (current)

- APPROVED: File count is not a quality constraint; add files as architecture requires.
- APPROVED: Google Play, public store distribution, account/backend and Play Asset Delivery are out of scope.
- APPROVED: APK must be fully playable offline from bundled essential resources.
- APPROVED: Optional online update remains disabled unless a real HTTPS manifest is configured.
- APPROVED: Do not redo already approved design or implementation; make only evidence-based fixes that block Build, installation, runtime or performance.
- APPROVED: Finish Part03-A, then handle first Build and automated QA (B+C) as one continuous workstream, then device validation (D).
- PROTECTED: AI-generated alternate/candidate patches remain separate for later review; do not merge them into the active source.

## Phase status

| Phase | Scope | State | Owner gate |
|---|---|---|---|
| 01 | Source/docs audit and release architecture | COMPLETE | Approved/superseded by later direction |
| 02 | Settings/Volume integration, startup Bootstrap, content installer/resolver, font/resources | IMPLEMENTED; static checks recorded | No Build/Runtime PASS claim |
| 03-A | Build Foundation, Wrapper bootstrap, .gitignore and Actions | FILES COMPLETE; official JAR fetched/verified at first CI run | No further design work required |
| 03-B + 03-C | First Android Build and focused automated QA | CORE LIMITED QA 66/66; true Gradle/Android Build blocked in current environment | GitHub Runner execution required |
| 03-D | Redmi Note 11 Pro installation and product validation | NOT RUN; device/APK not available in this environment | Requires APK and physical handset |
| 04 | Final archive/handoff packaging | NOT STARTED | Only after Build and device QA evidence |

## Phase02 evidence ledger

- Patch scope: 22 files changed; 2,856 insertions and 185 deletions (corrected against the actual patch diff; the earlier 2,804 figure in the previous report was a counting discrepancy).
- Patch application: `git apply --check` PASS on a clean synthetic baseline; applied output tree matched the final working tree recursively.
- Java parser: 34 source files, zero syntax parse errors (`-source 17`, JDK 21 parser).
- Android XML: 5 files parsed successfully.
- Locales: 308 English + 308 Persian string keys; placeholders agree.
- Java string references: 281 distinct names; no missing name found.
- Python Content Pack builder: py_compile passed.
- Valid PNG pack: generated; output archive hash matched manifest.
- Negative checks: wrong extension/signature rejected; HTTP URL rejected.
- Actual Android Gradle Build: NOT RUN.
- Android runtime/device QA: NOT RUN.

## Open / unverified items

- A real public HTTPS Content Manifest endpoint is not configured; do not invent one.
- No actual `ui/home-hero.webp` artwork is supplied; resolver/consumer are optional and fallback safely.
- Content Pack v1 has no detached signature; private contest + non-executable image content only. Revisit before any untrusted/public distribution.
- Real Android Gradle Build, AAPT/resource linking, Lint, shrinking, installation and device QA remain Phase03 tasks.
- Gradle Wrapper scripts, checksum-pinned properties and bootstrap scripts are in the working tree; the official JAR is fetched and verified during CI bootstrap, and is not included in the current ZIP because it could not be downloaded in this environment.
- Final two ZIPs are not yet created; Phase04.

## Phase03 execution board

### Part03-A — Build Foundation (finalized at file/configuration level)

- [x] Verify AGP 9.4.0 / Gradle 9.6.0 / JDK 17 compatibility against official documentation.
- [x] Add `.gitignore`, wrapper properties, POSIX/Windows launchers and checksum-verifying bootstrap scripts.
- [x] Add Wrapper JAR SHA sidecar and fixed Gradle distribution SHA.
- [x] Add GitHub Actions workflow for `:core:test`, `:app:lintDebug`, `:app:assembleDebug` and APK/diagnostic artifact upload.
- [x] Add POSIX executable-mode preparation to CI for reliable archive uploads.
- [x] Add concise root README and Redmi Note 11 Pro install checklist.
- [ ] Actual Wrapper JAR download/verification and Gradle distribution execution on GitHub Runner (not available in this container).

### Part03-B + Part03-C — First Build and focused automated QA

- [x] Compile pure Java Core using `javac --release 17`.
- [x] Run the existing Core test methods through a local minimal JUnit-compatible shim: 66 passed, 0 failed.
- [x] Fix stale test fixtures/expectations for Replay start-anchor and first Goal-contact timestamp; do not modify production Core logic for this correction.
- [x] Parse all 35 Java source files with zero syntax-parse errors.
- [x] Parse all 5 Android XML files, the Workflow YAML and POSIX launcher scripts.
- [ ] Run actual `./gradlew :core:test :app:lintDebug :app:assembleDebug` on an Android/JDK17 runner.
- [ ] Inspect the generated APK and verify its manifest/package/minSdk.
- [ ] Add/fix Android-specific tests only if the real build or device run exposes a concrete blocker; no speculative test expansion.

### Part03-D — Redmi Note 11 Pro

- [ ] Install the generated APK on the actual handset.
- [ ] Verify first startup offline, Home/navigation, five Levels, touch/geometry/Gates, Result/Replay, Settings/Volume and lifecycle recovery.
- [ ] Observe crashes, input latency, frame pacing, memory and thermal/battery behavior in a 10–15 minute session.
- [ ] Fix only reproducible, evidenced product defects and rerun relevant regression checks.

### Current stop condition

Do not claim APK availability or device PASS before GitHub Actions produces the APK and it is installed on the handset. The current container lacks Gradle, Android SDK and external download access; actual Build/Lint/Android tests must execute on GitHub Actions or another Android-capable machine. The owner has explicitly instructed that A then B+C then D proceed without repetitive redesign or unrelated work.

## Operating rules

1. Source-first: inspect active source and exact Patch baseline before changing files.
2. No fictional certainty: distinguish implemented, static PASS, runtime PASS, NOT RUN, BLOCKED and INFERENCE.
3. Never claim Build/APK/device success without the actual output and evidence.
4. Preserve existing code and previous AI candidate archives; do not merge candidates without a later reverse-engineering review.
5. Follow the owner-approved bounded sequence A → B+C → D; do not introduce extra approval gates or unrelated redesign.
6. When a new chat starts, read `00-START-HERE-FA.md`, this Ledger, Phase02 report, current `PROJECT-STRUCTURE-STATUS.md`, the relevant design/source docs and the exact patch baseline before acting.
