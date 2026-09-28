# Experimental fork integrations

[Native sessions](native-sessions/README.md) is a standalone Kotlin/JVM included
build for running official native agent SDK/session turns inside typed Koog nodes.

Consumer settings: `includeBuild("../koog/integrations/native-sessions")`.
Dependency: `io.github.gildor.koog:native-sessions:0.1.0-SNAPSHOT`.

It uses published Koog 1.3.0 artifacts. It is deliberately separate from the root
multiplatform build, so including it does not configure all of Koog's modules.
