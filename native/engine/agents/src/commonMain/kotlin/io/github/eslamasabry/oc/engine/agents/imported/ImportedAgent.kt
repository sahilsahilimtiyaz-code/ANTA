package io.github.eslamasabry.oc.engine.agents.imported

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf

// An agent the person brought themselves (owner request 2026-11-15): a file installed into the phone's Ubuntu instead
// of a pinned catalog download. Nothing here grants a qualification: an imported agent is listed as installed because
// its files are in place, never because a check proved it can answer.

/** The shapes a person can hand the app: the two tar compressions, a zip, or one Linux program. */
public enum class ImportedAgentFormat {
    TarGz,
    TarBz2,
    Zip,
    Executable,
}

/** A provider key the imported agent reads from its environment. Only the name is stored; the value never is. */
public data class ImportedProviderKey(val name: String)

/**
 * One agent the person installed into this phone's Ubuntu.
 *
 * [id] is the slug used three times over: the folder under `~/.local/share/oc-imported`, the launcher in `~/.local/bin`
 * and the helper's provider id (so a chat started with this agent names it). [command] is the program inside the
 * unpacked tree, relative to it, or the file's own name for a bare executable.
 */
public data class ImportedAgent(
    public val id: String,
    public val name: String,
    public val command: String,
    public val args: ImmutableList<String> = persistentListOf(),
    public val format: ImportedAgentFormat = ImportedAgentFormat.Executable,
    public val providers: ImmutableList<ImportedProviderKey> = persistentListOf(),
    /** Wall clock of the import; 0 when the platform could not say. */
    public val addedAtMs: Long = 0L,
) {
    /** The provider id the helper renders and a chat names. */
    public val providerId: String get() = id

    /** `/home/oc/.local/share/oc-imported/<id>`: the unpacked tree, inside the guest. */
    public val guestFolder: String get() = "$GUEST_ROOT/$id"

    /** `/home/oc/.local/bin/<id>`: what the helper launches. */
    public val guestLauncher: String get() = "$GUEST_BIN/$id"

    /** `/home/oc/.config/oc-imported/<id>.env`: the provider values, read by the launcher only. */
    public val guestEnvFile: String get() = "$GUEST_ENV/$id.env"

    /** The program the helper runs, inside the guest. */
    public val guestProgram: String get() = "$guestFolder/$command"

    public companion object {
        public const val GUEST_ROOT: String = "/home/oc/.local/share/oc-imported"
        public const val GUEST_BIN: String = "/home/oc/.local/bin"
        public const val GUEST_ENV: String = "/home/oc/.config/oc-imported"

        /** A slug the helper accepts as a provider id and a file name: lowercase, short, no dots. */
        public val ID: Regex = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+){0,3}$")

        public const val MAX_ID: Int = 32
        public const val MAX_NAME: Int = 48
        public const val MAX_ARGS: Int = 8
        public const val MAX_ARG: Int = 120
        public const val MAX_PROVIDERS: Int = 6
        public const val MAX_PROVIDER_VALUE: Int = 4096

        /** The largest file worth unpacking on a phone. */
        public const val MAX_FILE_BYTES: Long = 512L * 1024 * 1024

        /** How many entries an archive may hold; a bomb or a wrong file never fills the phone. */
        public const val MAX_ENTRIES: Int = 20_000

        /** The unpacked bytes one import may write. */
        public const val MAX_UNPACKED_BYTES: Long = 1024L * 1024 * 1024
    }
}

/** Why an import was refused, in a closed set; the words live in the feature. */
public enum class ImportedAgentProblem {
    /** The picked file is not a tar, a zip or a Linux program. */
    UnsupportedFile,

    /** The file is empty, or too large to unpack on a phone. */
    EmptyFile,
    TooLarge,

    /** A name or slug the app will not store. */
    BadName,
    BadId,
    DuplicateId,

    /** The command is not a plain relative path inside the unpacks tree. */
    BadCommand,

    /** Arguments or provider keys the app will not hand a program. */
    TooManyArgs,
    BadArg,
    TooManyProviders,
    BadProvider,

    /** The archive was refused while unpacking it (unsafe entry, too many entries, too much data). */
    UnsafeArchive,

    /** The Ubuntu environment could not be read or written. */
    Storage,
}

/** The plain rules an import must satisfy before anything is written. */
public object ImportedAgentRules {
    public fun problemWith(
        id: String,
        name: String,
        command: String,
        args: List<String>,
        providers: List<ImportedProviderKey>,
    ): ImportedAgentProblem? = when {
        id.length > ImportedAgent.MAX_ID || !ImportedAgent.ID.matches(id) -> ImportedAgentProblem.BadId
        name.isBlank() -> ImportedAgentProblem.BadName
        name.length > ImportedAgent.MAX_NAME || name.any { it.isISOControl() } -> ImportedAgentProblem.BadName
        !safeCommand(command) -> ImportedAgentProblem.BadCommand
        args.size > ImportedAgent.MAX_ARGS -> ImportedAgentProblem.TooManyArgs
        args.any { !safeArg(it) } -> ImportedAgentProblem.BadArg
        providers.size > ImportedAgent.MAX_PROVIDERS -> ImportedAgentProblem.TooManyProviders
        providers.any { !PROVIDER.matches(it.name) } -> ImportedAgentProblem.BadProvider
        else -> null
    }

    /** A program inside the unpacked tree: relative, plain segments, no traversal and no shell metacharacters. */
    @Suppress("ReturnCount", "MagicNumber")
    public fun safeCommand(value: String): Boolean {
        if (value.isEmpty() || value.length > 160) return false
        if (value.startsWith("/") || value.startsWith("./")) return false
        if (value.any { it.isISOControl() } || '\\' in value) return false
        val segments = value.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        return SEGMENT.matches(value)
    }

    /** An argument is data, never a shell fragment: no NUL, no newline. */
    public fun safeArg(value: String): Boolean =
        value.length <= ImportedAgent.MAX_ARG && value.none { it.isISOControl() }

    /** A provider key name: the shape a shell accepts on the left of `=`. */
    public fun safeProviderName(value: String): Boolean = PROVIDER.matches(value)

    /** The slug a name suggests, so most imports need no typing. */
    public fun slugOf(name: String): String {
        val slug =
            name.lowercase()
                .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
                .joinToString("")
                .trim('-')
                .split('-')
                .filter { it.isNotEmpty() }
                .joinToString("-")
        val clipped = slug.take(ImportedAgent.MAX_ID).trim('-')
        return if (clipped.isEmpty() || !clipped.first().isLetter()) {
            "agent-${clipped.ifEmpty { "import" }}".take(ImportedAgent.MAX_ID).trim('-')
        } else clipped
    }

    private val SEGMENT = Regex("^[A-Za-z0-9._+@-]+(?:/[A-Za-z0-9._+@-]+)*$")
    private val PROVIDER = Regex("^[A-Z][A-Z0-9_]{0,63}$")
}

/** What a picked file is, decided from its name and its first bytes, never from the person's word. */
public object ImportedAgentDetection {
    /** `null` when the bytes are a shape the app cannot use. */
    @Suppress("CyclomaticComplexMethod", "MagicNumber")
    public fun formatOf(displayName: String, head: ByteArray): ImportedAgentFormat? {
        val bytes = head.map { it.toInt() and 0xFF }
        return when {
            bytes.size >= 2 && bytes[0] == 0x1F && bytes[1] == 0x8B -> ImportedAgentFormat.TarGz
            bytes.size >= 3 && bytes[0] == 0x42 && bytes[1] == 0x5A && bytes[2] == 0x68 -> ImportedAgentFormat.TarBz2
            bytes.size >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B && bytes[2] == 0x03 && bytes[3] == 0x04 ->
                ImportedAgentFormat.Zip
            isElf(bytes) -> ImportedAgentFormat.Executable
            else -> byName(displayName)
        }
    }

    /** The name's own answer, used only when the bytes say nothing (a provider that hands over no prefix). */
    public fun byName(displayName: String): ImportedAgentFormat? {
        val name = displayName.lowercase()
        return when {
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> ImportedAgentFormat.TarGz
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") || name.endsWith(".tbz") -> ImportedAgentFormat.TarBz2
            name.endsWith(".zip") -> ImportedAgentFormat.Zip
            name.endsWith(".tar") -> ImportedAgentFormat.TarGz
            else -> null
        }
    }

    /** The file says Linux, and which processor it is for: true only for a 64-bit ARM or x86 program. */
    @Suppress("MagicNumber")
    public fun isElf(bytes: List<Int>): Boolean =
        bytes.size >= 20 &&
            bytes[0] == 0x7F && bytes[1] == 0x45 && bytes[2] == 0x4C && bytes[3] == 0x46 &&
            machine(bytes) in MACHINES

    /** `e_machine` of a little- or big-endian ELF header (EM_AARCH64 183, EM_X86_64 62). */
    @Suppress("MagicNumber")
    public fun machine(bytes: List<Int>): Int {
        if (bytes.size < 20) return -1
        val le = bytes[5] == 1
        val low = if (le) bytes[18] else bytes[19]
        val high = if (le) bytes[19] else bytes[18]
        return (high shl 8) or low
    }

    @Suppress("MagicNumber")
    private val MACHINES = setOf(183, 62)
}

/** The picked file as the platform hands it over: an opaque handle, its name, and what it is. */
public data class PickedAgentFile(
    /** A `content://` handle on Android; an app-readable token, never shown and never logged. */
    public val handle: String,
    public val displayName: String,
    public val format: ImportedAgentFormat,
    public val sizeBytes: Long?,
) {
    override fun toString(): String = "PickedAgentFile($format, ${sizeBytes ?: 0} bytes)"
}

/** What the person typed for the import; provider values stay in the screen and reach the platform only here. */
public data class ImportedAgentDraft(
    public val id: String,
    public val name: String,
    public val command: String,
    public val args: ImmutableList<String>,
    /** Environment names with the values the person typed; written to the agent's own env file. */
    public val providerValues: ImmutableMap<String, String>,
)

/** Whether an import went in; a refusal names one plain reason. */
public sealed interface AgentImportResult {
    public data class Installed(val agent: ImportedAgent) : AgentImportResult

    public data class Refused(val problem: ImportedAgentProblem) : AgentImportResult
}
