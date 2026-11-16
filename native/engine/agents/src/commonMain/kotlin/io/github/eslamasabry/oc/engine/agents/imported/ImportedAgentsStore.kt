package io.github.eslamasabry.oc.engine.agents.imported

import io.github.eslamasabry.oc.core.common.Failure
import io.github.eslamasabry.oc.core.common.FeatureId
import io.github.eslamasabry.oc.core.common.Outcome
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The imported agents this phone has, as the platform keeps them (`imported-agents.json` in the app's own files). The
 * document is data written by this app only; a malformed one is dropped rather than trusted, so a hand-edited file can
 * never put an arbitrary program into the helper's provider list.
 */
public interface ImportedAgents {
    public val agents: StateFlow<ImmutableList<ImportedAgent>>

    /** Re-reads the stored list. */
    public suspend fun refresh(): Outcome<Unit>

    /** Opens the system file picker and reports what came back. */
    public suspend fun pick(): AgentPickResult

    /** Unpacks [picked] into this phone's Ubuntu and records it. */
    public suspend fun install(picked: PickedAgentFile, draft: ImportedAgentDraft): AgentImportResult

    /** Removes the record and everything the import wrote; the person's other files stay. */
    public suspend fun remove(id: String): Outcome<Unit>
}

/** What the file picker answered: a file the app can unpack, the person's cancel, or a plain refusal. */
public sealed interface AgentPickResult {
    public data class Picked(val file: PickedAgentFile) : AgentPickResult

    public data object Cancelled : AgentPickResult

    public data class Refused(val problem: ImportedAgentProblem) : AgentPickResult
}

/** No built-in Linux on this device (desktop, or an app build without it): the import card is not offered. */
public class NoImportedAgents : ImportedAgents {
    override val agents: StateFlow<ImmutableList<ImportedAgent>> =
        MutableStateFlow<ImmutableList<ImportedAgent>>(persistentListOf()).asStateFlow()

    override suspend fun refresh(): Outcome<Unit> = Outcome.Failed(Failure.Unavailable(FeatureId("importedAgents")))

    override suspend fun pick(): AgentPickResult = AgentPickResult.Refused(ImportedAgentProblem.Storage)

    override suspend fun install(picked: PickedAgentFile, draft: ImportedAgentDraft): AgentImportResult =
        AgentImportResult.Refused(ImportedAgentProblem.Storage)

    override suspend fun remove(id: String): Outcome<Unit> =
        Outcome.Failed(Failure.Unavailable(FeatureId("importedAgents")))
}

/** The stored document: `{"schema":1,"agents":[…]}`. */
public object ImportedAgentsJson {
    private const val SCHEMA = 1L
    private val json = Json { ignoreUnknownKeys = false }

    public fun encode(agents: List<ImportedAgent>): String =
        buildJsonObject {
            put("schema", SCHEMA)
            put(
                "agents",
                buildJsonArray {
                    for (agent in agents) {
                        add(
                            buildJsonObject {
                                put("id", agent.id)
                                put("name", agent.name)
                                put("command", agent.command)
                                put("format", agent.format.name)
                                put("addedAt", agent.addedAtMs)
                                put("args", buildJsonArray { agent.args.forEach { add(JsonPrimitive(it)) } })
                                put(
                                    "providers",
                                    buildJsonArray { agent.providers.forEach { add(JsonPrimitive(it.name)) } },
                                )
                            }
                        )
                    }
                },
            )
        }.toString()

    /** A document that cannot be read, or holds an entry the rules refuse, is treated as no document at all. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    public fun decode(text: String): ImmutableList<ImportedAgent> {
        val root =
            try {
                json.parseToJsonElement(text).jsonObject
            } catch (_: IllegalArgumentException) {
                return persistentListOf()
            }
        if ((root["schema"] as? JsonPrimitive)?.longOrNull != SCHEMA) return persistentListOf()
        val entries = (root["agents"] as? JsonArray) ?: return persistentListOf()
        val agents =
            entries.mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                fun str(key: String): String? = (entry[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val id = str("id") ?: return@mapNotNull null
                val name = str("name") ?: return@mapNotNull null
                val command = str("command") ?: return@mapNotNull null
                val format =
                    str("format")?.let { value -> ImportedAgentFormat.entries.firstOrNull { it.name == value } }
                        ?: return@mapNotNull null
                val args =
                    (entry["args"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        ?: emptyList()
                val providers =
                    (entry["providers"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        ?.map(::ImportedProviderKey)
                        ?: emptyList()
                val addedAt = (entry["addedAt"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (ImportedAgentRules.problemWith(id, name, command, args, providers) != null) return@mapNotNull null
                ImportedAgent(
                    id = id,
                    name = name,
                    command = command,
                    args = args.toImmutableList(),
                    format = format,
                    providers = providers.toImmutableList(),
                    addedAtMs = addedAt,
                )
            }
        if (agents.map { it.id }.toSet().size != agents.size) return persistentListOf()
        return agents.toImmutableList()
    }
}

/**
 * What the import writes inside this phone's Ubuntu, as text: the launcher the helper runs, and the environment file
 * that carries the provider values. Both are app-authored; the person's input reaches them only as validated segments.
 */
public object ImportedAgentScripts {
    /** `/home/oc/.local/bin/<id>`: a POSIX shell script, 0755, that reads the env file then runs the program. */
    public fun launcher(agent: ImportedAgent): String =
        buildString {
            appendLine("#!/bin/sh")
            appendLine("# Imported by OpenCode Mobile. Edits are overwritten by the next import of the same name.")
            appendLine("OC_ENV=\"\${HOME:-/home/oc}/${agent.guestEnvFile.removePrefix("/home/oc/")}\"")
            appendLine("if [ -f \"\$OC_ENV\" ]; then")
            appendLine("  set -a")
            appendLine("  . \"\$OC_ENV\"")
            appendLine("  set +a")
            appendLine("fi")
            append("exec \"\$HOME/${agent.guestProgram.removePrefix("/home/oc/")}\"")
            for (arg in agent.args) append(" ").append(quote(arg))
            appendLine(" \"\$@\"")
        }

    /** `KEY='value'`: single-quoted so a value with spaces or symbols reaches the program unchanged. */
    public fun envFile(values: Map<String, String>): String =
        buildString {
            for ((name, value) in values) {
                if (!ImportedAgentRules.safeProviderName(name)) continue
                append(name)
                append('=')
                append(quote(value.take(ImportedAgent.MAX_PROVIDER_VALUE)))
                appendLine()
            }
        }

    /** A value as one shell word: single quotes, with the one character single quotes cannot hold escaped. */
    public fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
