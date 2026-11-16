package io.github.eslamasabry.oc.feature.agents.hub

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the import sheet reads from its two free-text fields. */
class ImportedAgentInputTest {
    @Test
    fun `B-IMPORT-020 arguments are split on spaces, tabs and new lines`() {
        assertEquals(listOf("--acp", "--port", "0"), ImportedAgentInput.args(" --acp\t--port\n0  "))
        assertEquals(emptyList(), ImportedAgentInput.args("   "))
    }

    @Test
    fun `B-IMPORT-021 provider lines keep a value that holds an equals sign`() {
        assertEquals(
            listOf("OPENAI_API_KEY" to "sk-a=b", "ANTHROPIC_API_KEY" to "x"),
            ImportedAgentInput.providers("OPENAI_API_KEY=sk-a=b\n\n  ANTHROPIC_API_KEY = x  "),
        )
    }

    @Test
    fun `B-IMPORT-022 a line with no name is dropped, never written as an empty variable`() {
        assertTrue(ImportedAgentInput.providers("=value\nNOEQUALS\n").isEmpty())
    }
}
