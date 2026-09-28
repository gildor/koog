# Fork integration verification

28 September 2026. Branch: `feature/native-sdk-nodes`.
Upstream base: `16d83270f8a7f25358ae0165466f14e70416c428` (Koog 1.3.0).

## Packaging change

Moved the tested prototype into a standalone fork-hosted Gradle JVM library at
`integrations/native-sessions`, using coordinates
`io.github.gildor.koog:native-sessions:0.1.0-SNAPSHOT`. Renamed the Kotlin package to
`io.github.gildor.koog.nativeagents`, exported dependencies used by public APIs,
separated live job entry points into `examples`, and documented runtime bridge
paths. Added an independent Gradle consumer to exercise automatic composite-build
substitution. Adapter execution behavior was not intentionally changed.

## Commands and results

From `integrations/native-sessions`:

| Command | Result |
| --- | --- |
| `./gradlew jvmTest build` | Passed: 26 JVM tests, 10 Node bridge tests, library JAR and example distributions built. |
| `./gradlew -p consumer-smoke test` | Passed: 2 separate-consumer JVM tests; resolved the library via `includeBuild("..")`. |

All 28 JVM tests report zero failures, errors and skipped tests. The Node suite
reports 10 passed and zero failed. No tests were disabled or compiler warnings
suppressed. The affected standalone JVM build's quality gates passed. No JS/WASM
target is declared for this module. The unchanged upstream root build and live
provider/API-key suites are outside this packaging verification.

The migrated suites are `CopilotStepTest` (12), `NativeBridgeTest` (10),
`KoogCliConformanceTest` (3) and `NativeOnlyExecutorTest` (1), under the library's
`src/test/kotlin/io/github/gildor/koog/nativeagents/` directory. They retain
cancellation, serialization, callback forwarding, process failure, typed graph
execution and selected Koog CLI conformance coverage.

New consumer tests are in
`consumer-smoke/src/test/kotlin/consumer/IncludedBuildTest.kt`:

- `testIncludedLibraryRunsTypedGraphFromDifferentWorkingDirectory`: separate
  Gradle project/package resolves the public API and its transitive types, then
  executes two nodes through a real deterministic Node process and returns 42.
- `testInvalidNativeOutputFailsConsumerGraph`: invalid native text propagates a
  decoding failure through the consumer's Koog graph.

These consumer tests deliberately run the child from the system temporary
directory with an absolute script path, so success does not depend on the
consumer using the integration directory as its working directory.

## Live evidence boundary

No new model calls were needed for this move. Prior prototype live evidence:
Copilot/Codex/Claude typed output, Kotlin tool invocation and conversation reuse;
Codex/Claude completed-session process restart/recall. OpenCode discovery/smoke
passed but its chosen free-model lookup timed out; later jobs were not reached.
The README retains these limits. This branch does not claim interrupted-turn
recovery, full Koog feature conformance or production readiness.
