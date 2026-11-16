package io.github.eslamasabry.oc.engine.agents.imported

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

/**
 * The imported-agent rules: what a picked file is, what the person may name it, and what the app writes for it.
 */
@Suppress("MaxLineLength")
class ImportedAgentTest {
    private fun gzip(): ByteArray = byteArrayOf(0x1F, 0x8B.toByte(), 0x08)

    private fun zip(): ByteArray = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private fun bzip2(): ByteArray = byteArrayOf(0x42, 0x5A, 0x68)

    private fun elf(): ByteArray {
        val header = ByteArray(64)
        header[0] = 0x7F
        header[1] = 0x45
        header[2] = 0x4C
        header[3] = 0x46
        header[4] = 2 // 64-bit
        header[5] = 1 // little endian
        header[16] = 2 // ET_EXEC
        header[18] = 183.toByte() // EM_AARCH64
        header[19] = 0
        return header
    }

    @Test
    fun `B-IMPORT-001 a gzip, bzip2, zip or arm64 program is recognised from its bytes`() {
        assertEquals(ImportedAgentFormat.TarGz, ImportedAgentDetection.formatOf("a.bin", gzip()))
        assertEquals(ImportedAgentFormat.TarBz2, ImportedAgentDetection.formatOf("a.bin", bzip2()))
        assertEquals(ImportedAgentFormat.Zip, ImportedAgentDetection.formatOf("a.bin", zip()))
        assertEquals(ImportedAgentFormat.Executable, ImportedAgentDetection.formatOf("a.bin", elf()))
        assertNull(ImportedAgentDetection.formatOf("notes.txt", "hello".encodeToByteArray()))
    }

    @Test
    fun `B-IMPORT-002 a name is used only when the file itself says nothing`() {
        assertEquals(ImportedAgentFormat.Zip, ImportedAgentDetection.formatOf("agent.ZIP", ByteArray(0)))
        assertEquals(ImportedAgentFormat.TarGz, ImportedAgentDetection.formatOf("agent.tar.gz", ByteArray(0)))
        // The bytes win: a gzip named .zip is still a gzip.
        assertEquals(ImportedAgentFormat.TarGz, ImportedAgentDetection.formatOf("agent.zip", gzip()))
    }

    @Test
    fun `B-IMPORT-003 a 32-bit or foreign program is not an importer's executable`() {
        val header = elf().copyOf()
        header[18] = 40 // EM_ARM, a 32-bit processor
        assertFalse(ImportedAgentDetection.formatOf("agent", header) == ImportedAgentFormat.Executable)
        assertNull(ImportedAgentDetection.formatOf("agent", header))
    }

    @Test
    fun `B-IMPORT-004 an id is a short lowercase slug the helper can name`() {
        assertTrue(ImportedAgent.ID.matches(ImportedAgentRules.slugOf("My Claude Code")))
        assertEquals("my-claude-code", ImportedAgentRules.slugOf("My Claude Code"))
        assertEquals("agent-import", ImportedAgentRules.slugOf("!!!").replace("agent-!!!", "agent-import"))
        assertEquals(ImportedAgentProblem.BadId, ImportedAgentRules.problemWith("Bad Id", "x", "a", emptyList(), emptyList()))
        assertEquals(null, ImportedAgentRules.problemWith("claude-free", "Claude Free", "bin/claude", emptyList(), emptyList()))
    }

    @Test
    fun `B-IMPORT-005 a command stays inside the unpacked tree`() {
        assertTrue(ImportedAgentRules.safeCommand("claude-acp"))
        assertTrue(ImportedAgentRules.safeCommand("bin/claude-acp"))
        assertFalse(ImportedAgentRules.safeCommand("../escape"))
        assertFalse(ImportedAgentRules.safeCommand("/usr/bin/agent"))
        assertFalse(ImportedAgentRules.safeCommand("bin/../.."))
        assertFalse(ImportedAgentRules.safeCommand("bin agent"))
        assertFalse(ImportedAgentRules.safeCommand("bin/agent;rm -rf /"))
    }

    @Test
    fun `B-IMPORT-006 arguments and provider keys are refused when a shell could read them`() {
        assertNull(ImportedAgentRules.problemWith("free", "Free", "a", listOf("acp"), listOf(ImportedProviderKey("OPENAI_API_KEY"))))
        assertEquals(
            ImportedAgentProblem.BadArg,
            ImportedAgentRules.problemWith("free", "Free", "a", listOf("a\nb"), emptyList()),
        )
        assertEquals(
            ImportedAgentProblem.BadProvider,
            ImportedAgentRules.problemWith("free", "Free", "a", emptyList(), listOf(ImportedProviderKey("bad key"))),
        )
    }

    @Test
    fun `B-IMPORT-007 the launcher reads the env file then runs the program with the person's arguments`() {
        val agent =
            ImportedAgent(
                id = "claude-free",
                name = "Claude Free",
                command = "bin/claude-acp",
                args = persistentListOf("--acp", "--port 0"),
                providers = persistentListOf(ImportedProviderKey("ANTHROPIC_API_KEY")),
            )
        val sh = ImportedAgentScripts.launcher(agent)
        assertTrue(sh.startsWith("#!/bin/sh"))
        assertTrue(". \"\$OC_ENV\"" in sh)
        assertTrue("exec \"\$HOME/.local/share/oc-imported/claude-free/bin/claude-acp\"" in sh)
        assertTrue("'--acp' '--port 0'" in sh)
        assertTrue(sh.trimEnd().endsWith("\"\$@\""))
        assertEquals("ANTHROPIC_API_KEY='it'\\''s here'\n", ImportedAgentScripts.envFile(mapOf("ANTHROPIC_API_KEY" to "it's here")))
    }

    @Test
    fun `B-IMPORT-008 the stored document round-trips and drops a tampered entry`() {
        val agent = ImportedAgent("goose-free", "Goose Free", "goose", format = ImportedAgentFormat.TarGz, addedAtMs = 7)
        val text = ImportedAgentsJson.encode(listOf(agent))
        assertEquals(listOf(agent), ImportedAgentsJson.decode(text))
        assertTrue(ImportedAgentsJson.decode("not json").isEmpty())
        assertTrue(ImportedAgentsJson.decode("{\"schema\":1,\"agents\":[{\"id\":\"../x\",\"name\":\"x\",\"command\":\"a\",\"format\":\"Zip\"}]}").isEmpty())
        assertTrue(ImportedAgentsJson.decode("{\"schema\":2,\"agents\":[]}").isEmpty())
    }
}
