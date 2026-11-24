# NEON MAZE — Phase Ledger / Anti-Forgetting Record

> **این Ledger وضعیت اجرایی جاری پروژه است.** اسناد قدیمی داخل Snapshot ممکن است مربوط به تاریخچه/شاخه‌های قبلی باشند و نباید بر این Ledger یا `00-START-HERE-FA.md` و `IMPLEMENTATION-BLUEPRINT.md` تحمیل شوند.

## Project identity

- Project: NEON MAZE Android finger path-following puzzle game.
- Historical source baseline: `SOURCE/neon-maze-source-baseline.zip` (SHA-256 ثبت‌شده در Handoff: `0bcb85760f0328f0e5918a8db713b4ae5d8da2169a87cec53aa76a7293fc40a0`).
- Current rolling source after this delivery: Part 2 project tree.
- Architecture protected: Android `:app` -> pure Java `:core`; offline-first; five existing Levels; no unapproved dependencies/framework rewrite.
- Do not claim Android Build/APK/device QA without actual execution evidence.

## Owner decisions

- APPROVED: deliver complete replacement ZIP, Patch, reports, SHA-256 and stop at each Part.
- APPROVED: next Part starts only after explicit owner approval.
- APPROVED: source-first, evidence-driven work; no fake runtime/build claims.
- APPROVED: five-Part roadmap, including mandatory Part 5 for complete Game UI/UX and Gaming Animation after Part 4.
- PROTECTED: no commit/push/deploy/release, destructive data reset, or external dependencies without approval.

## Phase status

| Part | Scope | State | Owner gate |
|---|---|---|---|
| 1 | Gameplay visibility, Maze/Viewport/Input, permanent Exit affordance | DELIVERED | Used as the accepted working base for Part 2 |
| 2 | Gate HUD, run idempotency, gate-pass ledger, save retry, trail/replay budget, lifecycle recovery, star rules | DELIVERED — runtime/build limits remain | Awaiting explicit owner review/approval |
| 3 | Features, progression/content, Accessibility and feature integrations | NOT STARTED | Requires explicit owner approval after Part 2 |
| 4 | App-wide visual identity outside deep gameplay, release integrity, Build and final QA | NOT STARTED | Requires Part 3 completion/approval |
| 5 | Complete in-game Game UI/UX and Gaming Animation system | MANDATORY — NOT STARTED | After Part 4 completion/approval; explicitly requested by owner 2026-11-23 |

## Part 2 — changes delivered

- Context-aware Gate HUD chooses the closest not-yet-passed Gate to the current marker/start centroid; this is a HUD relevance heuristic, not pathfinding. It hides when no relevant Gate exists.
- Engine-owned immutable passed-Gate ledger is exposed in `RunOutcome` and merged into `ProgressRules`, independent of Replay survival.
- `LocalStore` schema v2 adds `run_id` and `payload_hash`, migrates v1 records without dropping rows, and enforces unique Run IDs. Same UUID + identical canonical payload returns the existing result; same UUID + different payload is rejected.
- Failed result persistence retains the immutable `RunRecordInput` and its original UUID in controller memory; retry reuses the exact payload. A new run is blocked while that result is pending retry.
- Replay has existing caps; presentation Trail is separately bounded to 16,384 points and compacted without changing collision sweep or Replay sampling.
- Interrupted runs do not auto-resume; Result provides an explicit restart action.
- No-Optional completion uses an explicit 80%-of-Par threshold for three stars; this is a tuning default and must be validated with device/playtest data later.

## Verification evidence

- Core acceptance methods via local minimal JUnit-compatible harness: 69 passed, 0 failed. This is not Gradle/JUnit or Android instrumentation.
- English and Persian Android strings XML parse: PASS; 319 keys each, exact key parity.
- SQLite v1→v2 migration/uniqueness behavior: tested with an in-memory SQLite simulation based on the exact migration statements; this is not Android SQLite instrumentation.
- App Java sources: syntax-level parser review found no Java syntax diagnostics; Android symbol/type compilation was blocked by missing SDK/framework dependencies.
- Real Gradle Build, Android Lint, AAPT/resource linking, APK generation, installation and physical-device QA: BLOCKED/NOT RUN in this environment (Gradle command, wrapper JAR and Android SDK unavailable).

## Part 5 — mandatory non-forgetting entry

The owner explicitly said the game currently has no complete Game UI/UX and Gaming Animation design. Part 5 has therefore been added to the Canonical Blueprint. It must cover end-to-end gameplay UX, HUD hierarchy/state matrix, feedback, motion/animation catalog, reduced-motion/accessibility, Persian RTL/English LTR, technical integration and measured device performance. Do not treat Part 4's general screen polish or Parts 1/2's technical HUD work as completion of Part 5. Do not remove this stage in any future chat/handoff.

## Next step / stop condition

Part 2 delivery is complete. **Stop here.** Do not start Part 3 until the owner explicitly approves it. If a new chat is opened, read `00-START-HERE-FA.md` first, then the Canonical Blueprint, Source Code Map, Skill, this Ledger and `NEXT-PART-HANDOFF-FA.md`. Use the Part 2 `project/` tree as the rolling source; do not revert to the historical Baseline ZIP.
