# Native agent sessions for Koog (experimental, JVM)

Use host-owned Copilot, Codex, Claude or OpenCode native agent sessions inside
ordinary typed Koog nodes. Koog owns graph composition and Kotlin values; the
native runtime owns the model/tool loop, authentication and conversation.

This fork adds a **standalone Gradle included build** at
`integrations/native-sessions`. It uses published Koog **1.3.0** dependencies and
does not modify Koog core or require the whole upstream build. No tg-agent service
is required. The API is experimental and not published to Maven Central.

## Use from another project

Clone this branch beside your application:

```sh
git clone --branch feature/native-sdk-nodes https://github.com/gildor/koog.git koog-native
```

In your application's `settings.gradle.kts` (adjust the checkout path):

```kotlin
includeBuild("../koog-native/integrations/native-sessions")
```

In the consuming JVM module's `build.gradle.kts`:

```kotlin
repositories { mavenCentral() }

dependencies {
    implementation("io.github.gildor.koog:native-sessions:0.1.0-SNAPSHOT")
}
```

Gradle automatically substitutes the declared coordinates with this included
build's library. You do not need `mavenLocal()`, a published artifact, a source
copy or a dependency on the root Koog project. Pin your checkout to a tested commit
for reproducibility. Use JDK 21+ (library toolchain 21); this build uses Kotlin
2.3.10 and Gradle 9.5.0. Older consumer compiler/build versions are not validated.
The standalone consumer test uses the same Kotlin and Gradle versions.

Public APIs live in `io.github.gildor.koog.nativeagents`:

- `CopilotStep` / `copilotNode`: official Copilot Java SDK.
- `JsonLineProcess`, `NativeBridgeStep` / `nativeNode`: structured process bridge
  for Codex App Server, Claude Agent SDK and OpenCode SDK/server.
- `NativeOnlyExecutor` and `nativeOnlyModel`: fail-fast placeholders for Koog's
  required configuration when your graph contains only custom nodes.

The library exports the Koog, Copilot SDK, Jackson and coroutine types used by its
public API as Gradle `api` dependencies. Business DTO serialization remains your
application's choice.

## Verify installation without model calls

From this directory:

```sh
./gradlew jvmTest build
./gradlew -p consumer-smoke test
```

`build` also installs the locked bridge npm dependencies and runs the Node tests.
Requires Node 22+ and npm, plus network access for dependencies. These checks do
not use provider credentials or consume model quota.

The [consumer test](consumer-smoke/src/test/kotlin/consumer/IncludedBuildTest.kt)
is a **separate Gradle build and Kotlin package** with only the library dependency.
It executes a typed two-node Koog graph through a real deterministic Node process
from a different working directory, and verifies invalid output fails the graph.
It tests actual dependency substitution and public API accessibility; it does not
pretend the fixture is a live provider.

## Open a real native runtime

For Codex/Claude/OpenCode, install the bridge dependencies once per checkout:

```sh
./gradlew installBridgeDependencies
```

The Java SDK path for Copilot needs no Node bridge. Codex/Claude/OpenCode currently
need this checkout's `bridges` directory at runtime; the bridge files and npm SDKs
are **not embedded in the library JAR**. Pass an absolute bridge path so your
application's working directory can be anywhere:

```kotlin
import io.github.gildor.koog.nativeagents.JsonLineProcess
import io.github.gildor.koog.nativeagents.NativeBridgeStep
import java.io.File

val step = NativeBridgeStep(
    JsonLineProcess(
        command = listOf("node", File(bridgeDirectory, "main.mjs").absolutePath, "codex"),
        cwd = workspace,
        onNativeRequest = ::handleNativeRequest,
        onEvent = ::observeNativeEvent,
    )
)
```

Here `bridgeDirectory` and `workspace` are your `File` values.
`handleNativeRequest` is your suspending `(JsonNode) -> JsonNode` callback;
`observeNativeEvent` is your nonblocking `(JsonNode) -> Unit` observer. Implement
provider-native requests/answers; the library does not choose permission policy.
Use `step.open(options)` before turns and `step.shutdown()` in `finally`, including
when opening fails. `options` is a Jackson object with `cwd`, optional `model`,
optional `sessionId`, optional custom `tools`, and provider-specific `native`
configuration. See the [complete native job host](examples/src/main/kotlin/io/github/gildor/koog/nativeagents/NativeJobs.kt)
for exact schemas, callback shapes, session resume and lifecycle handling. Its
read-only fixture approvals are examples, not a policy for your application.

Copilot instead takes a host-owned `CopilotSession`:
`val step = CopilotStep(session)`. Use the SDK's native configuration and permission
handler when creating that session, and close both session and client explicitly.
See the [Copilot job host](examples/src/main/kotlin/io/github/gildor/koog/nativeagents/LiveJobs.kt).

Node helpers fit an ordinary strategy:

```kotlin
val work by nativeNode<Request, Result>(
    "work", step,
    prompt = { request -> renderTask(request) },
    decode = { text -> decodeAndValidateResult(text) },
)
```

`Request`, `Result` and those functions belong to your domain. For Copilot use
`copilotNode` with its `CopilotStep`. Connect the node with ordinary Koog edges.
The [consumer fixture](consumer-smoke/src/test/kotlin/consumer/IncludedBuildTest.kt)
shows complete graph construction and both levels of cleanup.

## Opt-in live checks

Use existing native runtime authentication. Install a compatible runtime and pick
an explicit model from its probe; SDK versions are pinned in Gradle/npm lockfiles.

```sh
./gradlew :examples:run --args='probe'
./gradlew :examples:nativeJobs --args='codex probe'
./gradlew :examples:nativeJobs --args='claude probe'
./gradlew :examples:nativeJobs --args='opencode probe'
```

A probe starts the runtime and inspects login/model availability without asking
for a model turn. To test one provider, replace the model placeholder below with
an identifier from that provider's probe:

```sh
./gradlew :examples:run --args='benchmark 1 MODEL_FROM_COPILOT_PROBE'
./gradlew :examples:nativeJobs --args='codex jobs MODEL_FROM_CODEX_PROBE'
./gradlew :examples:nativeJobs --args='claude jobs MODEL_FROM_CLAUDE_PROBE'
./gradlew :examples:nativeJobs --args='opencode jobs MODEL_FROM_OPENCODE_PROBE'
```

These jobs consume normal provider usage. They are opt-in and separate from
`build`. They use temporary workspaces and deliberately restricted read-only
fixtures, not general coding-agent permission settings. Provider discovery does
not guarantee every model is available to the current account. The adapters use
the runtime's native login; they do not implement a billing proxy.

Previously tested versions: Copilot Java SDK 1.0.14 / CLI 1.0.88; Codex CLI 0.153.4;
Claude Agent SDK 0.3.282; OpenCode SDK/CLI 1.18.29. Copilot CLI 1.0.78 failed on
`session.detach`; use a compatible CLI. Environment overrides:
`KOOG_COPILOT_CLI`, `KOOG_CODEX_CLI`, `KOOG_CLAUDE_CLI`. Claude otherwise uses its
bundled runtime; OpenCode resolves its CLI from PATH.

## Scope and current limits

- Copilot, Codex and Claude previously passed live typed output/tool/history jobs.
  Codex and Claude also passed recall after replacing the runtime and resuming a
  completed conversation. These are prior prototype results, not new model runs
  performed for this packaging change.
- OpenCode is included, but only discovery/smoke passed on the tested free-model
  route; its tool job timed out. History/resume are not yet live validated there.
- One step must own all sends to its native session. Turns are serialized. Use a
  fresh session per independent job; intentional follow-up nodes may share it.
- A captured step does not fork with Koog graph context and does not roll back with
  Koog checkpoints. Durable interrupted-job recovery, per-branch session isolation,
  general ToolRegistry export and persistent approval UI are not implemented.
- Cancellation attempts native abort. A failed/interrupted transport is not
  automatically replayed. A host must reconcile any potentially executed effects.
- Native events are observable, but they are not automatically Koog LLM/tool spans.
  Native callback cancellation signals are not individually carried over the bridge.
- This integration is JVM-only, with an additional Node runtime for the bridges.
  Antigravity is deferred. No stable universal provider abstraction is promised.

See [VERIFICATION.md](VERIFICATION.md) for this branch's packaging checks. Upstream
Koog's root build and all-provider API integration suite are separate from this
standalone integration's build.
