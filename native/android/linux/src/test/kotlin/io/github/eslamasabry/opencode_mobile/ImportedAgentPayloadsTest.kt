package io.github.eslamasabry.opencode_mobile

import io.github.eslamasabry.oc.engine.agents.imported.AgentImportResult
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgent
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentDraft
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentFormat
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentProblem
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest-side half of an import: what lands in the Ubuntu home, what a hostile archive cannot do, and what removing
 * an import takes away.
 */
@Suppress("MaxLineLength")
class ImportedAgentPayloadsTest {
    private fun home(): File = Files.createTempDirectory("oc-import").toFile()

    private fun draft(command: String = "bin/agent", provider: String = "OPENAI_API_KEY") =
        ImportedAgentDraft(
            id = "free-agent",
            name = "Free Agent",
            command = command,
            args = persistentListOf("--acp"),
            providerValues = persistentMapOf(provider to "sk-test"),
        )

    private fun tarGz(entries: List<Pair<String, ByteArray>>, mode: Int = 0b111_101_101): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(bytes)).use { tar ->
            for ((name, content) in entries) {
                val entry = TarArchiveEntry(name)
                entry.size = content.size.toLong()
                entry.mode = mode
                tar.putArchiveEntry(entry)
                tar.write(content)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipArchiveOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                val entry = ZipArchiveEntry(name)
                zip.putArchiveEntry(entry)
                zip.write(content)
                zip.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun `B-IMPORT-030 a tar gz lands in the guest home with a launcher and the person's keys`() {
        val home = home()
        val result =
            ImportedAgentPayloads(home).install(
                tarGz(listOf("bin/agent" to "#!/bin/sh\necho agent\n".encodeToByteArray())).inputStream(),
                ImportedAgentFormat.TarGz,
                draft(),
                nowMs = 5,
            )
        val installed = result as? AgentImportResult.Installed ?: error("expected an install, got $result")
        val agent: ImportedAgent = installed.agent
        val program = File(home, ".local/share/oc-imported/free-agent/bin/agent")
        assertTrue("the program is unpacked", program.isFile)
        assertTrue("the program can run", program.canExecute())
        val launcher = File(home, ".local/bin/free-agent")
        assertTrue("the launcher exists", launcher.isFile)
        assertTrue("the launcher can run", launcher.canExecute())
        val env = File(home, ".config/oc-imported/free-agent.env")
        assertEquals("OPENAI_API_KEY='sk-test'\n", env.readText())
        assertTrue("the import reads back as present", ImportedAgentPayloads(home).present(agent))
    }

    @Test
    fun `B-IMPORT-031 a zip is unpacked, and a name that climbs out refuses the whole archive`() {
        val home = home()
        val good =
            ImportedAgentPayloads(home).install(
                zip(listOf("bin/agent" to "x".encodeToByteArray(), "docs/readme.txt" to "hi".encodeToByteArray()))
                    .inputStream(),
                ImportedAgentFormat.Zip,
                draft().copy(id = "zip-agent"),
                nowMs = 1,
            )
        assertTrue(good is AgentImportResult.Installed)
        assertTrue(File(home, ".local/share/oc-imported/zip-agent/docs/readme.txt").isFile)

        val escaped =
            ImportedAgentPayloads(home).install(
                zip(listOf("../escape" to "x".encodeToByteArray())).inputStream(),
                ImportedAgentFormat.Zip,
                draft().copy(id = "bad-agent"),
                nowMs = 1,
            )
        assertEquals(
            AgentImportResult.Refused(ImportedAgentProblem.UnsafeArchive),
            escaped,
        )
        assertFalse("nothing climbed out of the guest home", File(home.parentFile, "escape").exists())
        assertFalse("the refused import left no folder", File(home, ".local/share/oc-imported/bad-agent").exists())
    }

    @Test
    fun `B-IMPORT-032 an archive that does not hold the named program is refused, writing nothing`() {
        val home = home()
        val result =
            ImportedAgentPayloads(home).install(
                tarGz(listOf("other/program" to "x".encodeToByteArray())).inputStream(),
                ImportedAgentFormat.TarGz,
                draft(command = "bin/agent"),
                nowMs = 1,
            )
        assertEquals(AgentImportResult.Refused(ImportedAgentProblem.BadCommand), result)
        assertFalse(File(home, ".local/share/oc-imported/free-agent").exists())
        assertFalse(File(home, ".local/bin/free-agent").exists())
    }

    @Test
    fun `B-IMPORT-033 removing an import deletes its tree, launcher and keys, and nothing else`() {
        val home = home()
        val neighbour = File(home, "project.txt").apply { writeText("keep me") }
        val installed =
            ImportedAgentPayloads(home).install(
                tarGz(listOf("bin/agent" to "x".encodeToByteArray())).inputStream(),
                ImportedAgentFormat.TarGz,
                draft(),
                nowMs = 1,
            ) as AgentImportResult.Installed
        assertTrue(ImportedAgentPayloads(home).remove(installed.agent))
        assertFalse(File(home, ".local/share/oc-imported/free-agent").exists())
        assertFalse(File(home, ".local/bin/free-agent").exists())
        assertFalse(File(home, ".config/oc-imported/free-agent.env").exists())
        assertTrue("the person's own files stay", neighbour.readText() == "keep me")
    }

    @Test
    fun `B-IMPORT-034 one Linux program is written under the name the person gave it`() {
        val home = home()
        val result =
            ImportedAgentPayloads(home).install(
                "#!/bin/sh\necho hi\n".encodeToByteArray().inputStream(),
                ImportedAgentFormat.Executable,
                draft(command = "claude-free"),
                nowMs = 1,
            )
        assertTrue(result is AgentImportResult.Installed)
        val program = File(home, ".local/share/oc-imported/free-agent/claude-free")
        assertTrue(program.isFile && program.canExecute())
    }
}
