package io.github.eslamasabry.opencode_mobile.graph

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.github.eslamasabry.oc.core.common.AppCoroutineScope
import io.github.eslamasabry.oc.core.common.AppDispatchers
import io.github.eslamasabry.oc.core.common.Failure
import io.github.eslamasabry.oc.core.common.FeatureId
import io.github.eslamasabry.oc.core.common.Outcome
import io.github.eslamasabry.oc.engine.agents.imported.AgentImportResult
import io.github.eslamasabry.oc.engine.agents.imported.AgentPickResult
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgent
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentDraft
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentProblem
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentDetection
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgents
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentsJson
import io.github.eslamasabry.oc.engine.agents.imported.PickedAgentFile
import io.github.eslamasabry.opencode_mobile.BuiltinLinux
import io.github.eslamasabry.opencode_mobile.ImportedAgentPayloads
import java.io.File
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The imported agents on this phone: one small JSON document in the app's own files, and the unpacking done into the
 * built-in Ubuntu's home by [ImportedAgentPayloads]. The picker is a single system launch owned by [attach] (called
 * from
 * `MainActivity.onCreate`, as the other product launchers are); only one pick runs at a time and a result that arrives
 * with nobody waiting is dropped.
 *
 * The document holds no provider values: those are written to the agent's own environment file inside the Ubuntu home,
 * with owner-only permissions, and are never read back by the app.
 */
@SingleIn(AppScope::class)
@Inject
@ContributesBinding(AppScope::class, binding = binding<ImportedAgents>())
private const val HEAD_BYTES = 64

class AppImportedAgents(
    private val app: Application,
    private val dispatchers: AppDispatchers,
    private val appScope: AppCoroutineScope,
) : ImportedAgents {
    private val mutable = MutableStateFlow<ImmutableList<ImportedAgent>>(persistentListOf())
    override val agents: StateFlow<ImmutableList<ImportedAgent>> = mutable.asStateFlow()

    private val document = File(app.filesDir, "imported-agents.json")
    private val linux by lazy { BuiltinLinux.get(app) }
    private var launcher: ActivityResultLauncher<Array<String>>? = null
    private var waiting: CompletableDeferred<Uri?>? = null

    init {
        appScope.launch { refresh() }
    }

    /** Registers the system document picker on [activity]; call from `MainActivity.onCreate`. */
    fun attach(activity: ComponentActivity) {
        launcher =
            activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                // Only the request this launcher was launched with accepts a result.
                val request = waiting.also { waiting = null } ?: return@registerForActivityResult
                request.complete(uri)
            }
    }

    fun detach() {
        launcher?.unregister()
        launcher = null
        waiting?.cancel()
        waiting = null
    }

    override suspend fun refresh(): Outcome<Unit> =
        withContext(dispatchers.io) {
            val text = try {
                if (document.isFile) document.readText() else null
            } catch (_: Exception) {
                return@withContext Outcome.Failed(Failure.SecretUnavailable())
            }
            mutable.value = if (text == null) persistentListOf() else ImportedAgentsJson.decode(text)
            Outcome.Ok(Unit)
        }

    @Suppress("ReturnCount")
    override suspend fun pick(): AgentPickResult {
        if (!linux.installed) return AgentPickResult.Refused(ImportedAgentProblem.Storage)
        val activityLauncher = launcher ?: return AgentPickResult.Refused(ImportedAgentProblem.Storage)
        if (waiting != null) return AgentPickResult.Refused(ImportedAgentProblem.Storage)
        val request = CompletableDeferred<Uri?>()
        waiting = request
        withContext(dispatchers.main) {
            activityLauncher.launch(arrayOf("*/*"))
        }
        val uri = request.await() ?: return AgentPickResult.Cancelled
        val head =
            withContext(dispatchers.io) {
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(HEAD_BYTES)
                        val read = input.read(buffer)
                        if (read <= 0) ByteArray(0) else buffer.copyOf(read)
                    }
                } catch (_: Exception) {
                    null
                }
            } ?: return AgentPickResult.Refused(ImportedAgentProblem.Storage)
        val displayName = displayName(uri)
        val format = ImportedAgentDetection.formatOf(displayName, head)
            ?: return AgentPickResult.Refused(ImportedAgentProblem.UnsupportedFile)
        if (head.isEmpty()) return AgentPickResult.Refused(ImportedAgentProblem.EmptyFile)
        val size = sizeOf(uri)
        if (size != null && size > ImportedAgent.MAX_FILE_BYTES) {
            return AgentPickResult.Refused(ImportedAgentProblem.TooLarge)
        }
        return AgentPickResult.Picked(PickedAgentFile(uri.toString(), displayName, format, size))
    }

    @Suppress("ReturnCount")
    override suspend fun install(picked: PickedAgentFile, draft: ImportedAgentDraft): AgentImportResult {
        if (!linux.installed) return AgentImportResult.Refused(ImportedAgentProblem.Storage)
        val uri = Uri.parse(picked.handle)
        val added =
            withContext(dispatchers.io) {
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        payloads().install(input, picked.format, draft, System.currentTimeMillis())
                    } ?: AgentImportResult.Refused(ImportedAgentProblem.Storage)
                } catch (_: Exception) {
                    AgentImportResult.Refused(ImportedAgentProblem.Storage)
                }
            }
        if (added is AgentImportResult.Installed) {
            val next = (mutable.value.filterNot { it.id == added.agent.id } + added.agent).toImmutableList()
            mutable.value = next
            withContext(dispatchers.io) {
                try {
                    document.writeText(ImportedAgentsJson.encode(next))
                } catch (_: Exception) {
                    Unit
                }
            }
        }
        return added
    }

    @Suppress("ReturnCount")
    override suspend fun remove(id: String): Outcome<Unit> {
        val agent = mutable.value.firstOrNull { it.id == id }
            ?: return Outcome.Failed(Failure.Unavailable(FeatureId("importedAgents")))
        val removed =
            withContext(dispatchers.io) {
                try {
                    payloads().remove(agent)
                } catch (_: Exception) {
                    false
                }
            }
        if (!removed) return Outcome.Failed(Failure.Unavailable(FeatureId("importedAgents")))
        val next = mutable.value.filterNot { it.id == id }.toImmutableList()
        mutable.value = next
        withContext(dispatchers.io) {
            try {
                document.writeText(ImportedAgentsJson.encode(next))
            } catch (_: Exception) {
                Unit
            }
        }
        return Outcome.Ok(Unit)
    }

    /** What the agent helper renders as its providers: read at start, so a new import joins the next start. */
    fun providers(): List<ImportedAgent> = mutable.value

    private fun payloads(): ImportedAgentPayloads = ImportedAgentPayloads(File(linux.rootfs, "home/oc"))

    public companion object {
        /**
         * The stored list, read where a helper start needs its providers. A helper start runs on the io thread (the
         * file is a few hundred bytes) and must not wait for the page's own read to have finished.
         */
        public fun stored(filesDir: File): List<ImportedAgent> {
            val document = File(filesDir, "imported-agents.json")
            val text =
                try {
                    if (document.isFile) document.readText() else null
                } catch (_: Exception) {
                    null
                }
            return if (text == null) emptyList() else ImportedAgentsJson.decode(text)
        }
    }

    private fun displayName(uri: Uri): String =
        try {
            app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "agent"

    private fun sizeOf(uri: Uri): Long? =
        try {
            app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
            }
        } catch (_: Exception) {
            null
        }
}
