package io.github.eslamasabry.oc.feature.agents.hub

/**
 * Reading what the person types on the import sheet. Kept here, outside the screen, so the rules are testable and the
 * screen only arranges fields.
 */
public object ImportedAgentInput {
    /** Arguments separated by spaces, tabs or new lines; empty pieces are not arguments. */
    public fun args(text: String): List<String> =
        text.split(' ', '\n', '\t', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * `KEY=VALUE` lines. The first `=` splits, so a value may hold another one; a line without a name is dropped rather
     * than written as an empty variable. A value's surrounding spaces are trimmed: a key pasted with one is a paste
     * artefact, and a provider key never ends in a space on purpose.
     */
    public fun providers(text: String): List<Pair<String, String>> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            .toList()
}
