package io.github.gildor.koog.nativeagents

import ai.koog.prompt.dsl.prompt
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class NativeOnlyExecutorTest {
    @Test
    fun testAccidentalKoogModelCallsFailLocally() = runTest {
        val input = prompt("must-not-dispatch") {}
        assertFailsWith<IllegalStateException> { NativeOnlyExecutor.execute(input, nativeOnlyModel, emptyList()) }
        assertFailsWith<IllegalStateException> { NativeOnlyExecutor.executeStreaming(input, nativeOnlyModel, emptyList()) }
        assertFailsWith<IllegalStateException> { NativeOnlyExecutor.moderate(input, nativeOnlyModel) }
        NativeOnlyExecutor.close()
    }
}
