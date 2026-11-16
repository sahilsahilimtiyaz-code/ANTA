package io.github.eslamasabry.opencode_mobile

import io.github.eslamasabry.oc.engine.agents.imported.AgentImportResult
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgent
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentDraft
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentDetection
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentFormat
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentProblem
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentRules
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentScripts
import io.github.eslamasabry.oc.engine.agents.imported.ImportedProviderKey
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.collections.immutable.toImmutableList
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Unpacks a person's own agent file into this phone's Ubuntu and writes the launcher the agent helper runs.
 *
 * The guest home is the only place written ([guestHome], `<rootfs>/home/oc`), every entry stays inside it, and only
 * regular files and folders are created: a link, a device or a path with `..` refuses the whole archive. The provider
 * values are written to the agent's own environment file with owner-only permissions; they are never logged.
 */
@Suppress("TooManyFunctions", "ReturnCount", "MagicNumber")
public class ImportedAgentPayloads(private val guestHome: File) {
    /** What the file picker found, decided from its first bytes and its name. */
    public fun formatOf(head: ByteArray, displayName: String): ImportedAgentFormat? =
        ImportedAgentDetection.formatOf(displayName, head)

    /**
     * Reads [source] to the end and installs it, then closes it. A refusal removes the folder again, so a failed
     * import never leaves half a program behind.
     */
    @Suppress("ReturnCount")
    public fun install(
        source: InputStream,
        format: ImportedAgentFormat,
        draft: ImportedAgentDraft,
        nowMs: Long,
    ): AgentImportResult {
        val providers = draft.providerValues.keys.map(::ImportedProviderKey)
        ImportedAgentRules.problemWith(draft.id, draft.name, draft.command, draft.args, providers)
            ?.let { return AgentImportResult.Refused(it) }
        val agent =
            ImportedAgent(
                id = draft.id,
                name = draft.name,
                command = draft.command,
                args = draft.args.toImmutableList(),
                format = format,
                providers = providers.toImmutableList(),
                addedAtMs = nowMs,
            )
        val added =
            try {
                write(agent, source)
            } catch (_: Exception) {
                false
            }
        if (!added) {
            removeFolder(agent)
            return AgentImportResult.Refused(ImportedAgentProblem.UnsafeArchive)
        }
        // The archive must hold the program it names, or the launcher would run nothing.
        if (!File(guestHome, guestPath(agent.guestProgram)).isFile) {
            removeFolder(agent)
            return AgentImportResult.Refused(ImportedAgentProblem.BadCommand)
        }
        val written =
            try {
                writeFiles(agent, draft.providerValues)
            } catch (_: Exception) {
                false
            }
        if (!written) {
            removeFolder(agent)
            return AgentImportResult.Refused(ImportedAgentProblem.Storage)
        }
        return AgentImportResult.Installed(agent)
    }

    /** True when the program the record names is still where the import put it. */
    public fun present(agent: ImportedAgent): Boolean = File(guestHome, guestPath(agent.guestProgram)).isFile

    /** Deletes the unpacked tree, the launcher and the environment file; nothing else in the guest home is touched. */
    public fun remove(agent: ImportedAgent): Boolean {
        var ok = removeFolder(agent)
        ok = deleteInside(guestPath(agent.guestLauncher)) && ok
        ok = deleteInside(guestPath(agent.guestEnvFile)) && ok
        return ok
    }

    @Suppress("ReturnCount")
    private fun write(agent: ImportedAgent, source: InputStream): Boolean {
        val folder = File(guestHome, guestPath(agent.guestFolder))
        if (!inside(guestHome, folder)) return false
        if (folder.exists() && !folder.deleteRecursively()) return false
        if (!folder.mkdirs()) return false
        return when (agent.format) {
            ImportedAgentFormat.Executable -> writeProgram(folder, agent, source)
            ImportedAgentFormat.Zip -> unpack(ZipArchiveInputStream(source), folder)
            ImportedAgentFormat.TarGz -> unpack(TarArchiveInputStream(GzipCompressorInputStream(source)), folder)
            ImportedAgentFormat.TarBz2 -> unpack(TarArchiveInputStream(BZip2CompressorInputStream(source)), folder)
        }
    }

    /** One Linux program: the file the person picked is the program, written under the name they gave it. */
    @Suppress("NestedBlockDepth", "ReturnCount")
    private fun writeProgram(folder: File, agent: ImportedAgent, source: InputStream): Boolean {
        val program = File(folder, agent.command)
        if (!inside(folder, program)) return false
        program.parentFile?.mkdirs()
        var written = 0L
        source.use { input ->
            program.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    written += read
                    if (written > ImportedAgent.MAX_UNPACKED_BYTES) return false
                    output.write(buffer, 0, read)
                }
            }
        }
        if (written == 0L) return false
        return executable(program)
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private fun unpack(input: ArchiveInputStream<*>, folder: File): Boolean {
        val root = folder.canonicalFile
        var entries = 0
        var total = 0L
        input.use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                entries++
                if (entries > ImportedAgent.MAX_ENTRIES) return false
                val name = entry.name.replace('\\', '/')
                if (!plainName(name)) return false
                val destination = File(root, name)
                if (!inside(root, destination)) return false
                when {
                    entry.isDirectory -> if (!destination.isDirectory && !destination.mkdirs()) return false
                    // Links, devices and pipes are refused: an import is files, and a link could point outside.
                    entry is TarArchiveEntry && !entry.isFile -> return false
                    entry is ZipArchiveEntry && entry.isUnixSymlink -> return false
                    else -> {
                        destination.parentFile?.mkdirs()
                        val written = copy(archive, destination, ImportedAgent.MAX_UNPACKED_BYTES - total)
                        if (written < 0) return false
                        total += written
                        mode(destination, modeOf(entry))
                    }
                }
            }
        }
        return entries > 0
    }

    /** The bytes of one entry, or -1 when it runs past [budget]. */
    private fun copy(archive: ArchiveInputStream<*>, destination: File, budget: Long): Long {
        var written = 0L
        destination.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = archive.read(buffer)
                if (read <= 0) break
                written += read
                if (written > budget) return -1
                output.write(buffer, 0, read)
            }
        }
        return written
    }

    /** The archive's own permission bits when it has them, `0644` otherwise. */
    private fun modeOf(entry: ArchiveEntry): Int {
        val mode =
            when (entry) {
                is TarArchiveEntry -> entry.mode
                is ZipArchiveEntry -> entry.unixMode
                else -> 0
            }
        return if (mode and 0xFFF == 0) DEFAULT_FILE else mode and 0xFFF
    }

    /** The launcher the helper runs and the environment file it reads, both app-authored. */
    @Suppress("ReturnCount")
    private fun writeFiles(agent: ImportedAgent, values: Map<String, String>): Boolean {
        val program = File(guestHome, guestPath(agent.guestProgram))
        if (!program.isFile) return false
        if (!executable(program)) return false
        val launcher = File(guestHome, guestPath(agent.guestLauncher))
        launcher.parentFile?.mkdirs()
        if (!inside(guestHome, launcher)) return false
        launcher.writeText(ImportedAgentScripts.launcher(agent))
        if (!executable(launcher)) return false
        val env = File(guestHome, guestPath(agent.guestEnvFile))
        env.parentFile?.mkdirs()
        if (!inside(guestHome, env)) return false
        env.writeText(ImportedAgentScripts.envFile(values))
        mode(env, 0b110_000_000) // rw------- : the values are the person's keys
        return true
    }

    private fun removeFolder(agent: ImportedAgent): Boolean {
        val folder = File(guestHome, guestPath(agent.guestFolder))
        if (!inside(guestHome, folder)) return false
        return !folder.exists() || folder.deleteRecursively()
    }

    private fun deleteInside(relative: String): Boolean {
        val file = File(guestHome, relative)
        if (!inside(guestHome, file)) return false
        return !file.exists() || file.delete()
    }

    private fun executable(file: File): Boolean = mode(file, 0b111_101_101) // rwxr-xr-x

    private fun mode(file: File, bits: Int): Boolean =
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                PosixFilePermissions.fromString(letters(bits)),
            )
            true
        } catch (_: Exception) {
            // A filesystem without POSIX permissions (or an app-private folder that already allows execution).
            file.canExecute() || !file.isFile
        }

    private fun letters(bits: Int): String =
        buildString {
            for (shift in intArrayOf(6, 3, 0)) {
                val trio = (bits shr shift) and 0b111
                append(if (trio and 0b100 != 0) 'r' else '-')
                append(if (trio and 0b010 != 0) 'w' else '-')
                append(if (trio and 0b001 != 0) 'x' else '-')
            }
        }

    /** A path inside the guest home, from an absolute guest path (`/home/oc/...`). */
    private fun guestPath(guest: String): String = guest.removePrefix("/home/oc/")

    /** `true` only for a relative name with plain segments. */
    @Suppress("ReturnCount")
    private fun plainName(name: String): Boolean {
        if (name.isEmpty() || name.length > 200 || name.startsWith("/")) return false
        if (name.any { it.isISOControl() }) return false
        return name.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun inside(root: File, file: File): Boolean {
        val base = root.canonicalPath.trimEnd(File.separatorChar)
        val path = file.canonicalPath
        return path == base || path.startsWith(base + File.separator)
    }

    public companion object {
        private const val DEFAULT_FILE = 0b110_100_100 // rw-r--r--
    }
}
