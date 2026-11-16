package io.github.eslamasabry.oc.feature.agents.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgent
import io.github.eslamasabry.oc.engine.agents.imported.ImportedAgentProblem
import io.github.eslamasabry.oc.feature.agents.hub.AgentsActions
import io.github.eslamasabry.oc.feature.agents.hub.AgentsUi
import io.github.eslamasabry.oc.feature.agents.hub.ImportedAgentInput
import io.github.eslamasabry.oc.ui.design.tokens.Space
import io.github.eslamasabry.oc.ui.kit.KitAction
import io.github.eslamasabry.oc.ui.kit.KitButton
import io.github.eslamasabry.oc.ui.kit.KitButtonRole
import io.github.eslamasabry.oc.ui.kit.KitText
import io.github.eslamasabry.oc.ui.kit.foundation.KitEnabled
import io.github.eslamasabry.oc.ui.kit.foundation.KitLineAction
import io.github.eslamasabry.oc.ui.kit.icon.KitIcons
import io.github.eslamasabry.oc.ui.kit.input.KitField
import io.github.eslamasabry.oc.ui.kit.input.KitFieldKind
import io.github.eslamasabry.oc.ui.kit.modal.KitConfirm
import io.github.eslamasabry.oc.ui.kit.modal.KitConfirmKind
import io.github.eslamasabry.oc.ui.kit.modal.KitConfirmSpec
import io.github.eslamasabry.oc.ui.kit.modal.KitSheet
import io.github.eslamasabry.oc.ui.kit.row.KitRow
import io.github.eslamasabry.oc.ui.kit.row.KitRowLeading
import io.github.eslamasabry.oc.ui.kit.row.KitRowTrailing
import io.github.eslamasabry.oc.ui.kit.state.KitActionCard
import io.github.eslamasabry.oc.ui.kit.state.KitErrorNotice
import io.github.eslamasabry.oc.ui.kit.state.KitNotice
import io.github.eslamasabry.oc.ui.words.res.Res
import io.github.eslamasabry.oc.ui.words.res.agentsImportArguments
import io.github.eslamasabry.oc.ui.words.res.agentsImportArgumentsHelp
import io.github.eslamasabry.oc.ui.words.res.agentsImportChoose
import io.github.eslamasabry.oc.ui.words.res.agentsImportChosen
import io.github.eslamasabry.oc.ui.words.res.agentsImportInfo
import io.github.eslamasabry.oc.ui.words.res.agentsImportInfoProviders
import io.github.eslamasabry.oc.ui.words.res.agentsImportInfoTitle
import io.github.eslamasabry.oc.ui.words.res.agentsImportInfoWhere
import io.github.eslamasabry.oc.ui.words.res.agentsImportInstall
import io.github.eslamasabry.oc.ui.words.res.agentsImportLine
import io.github.eslamasabry.oc.ui.words.res.agentsImportName
import io.github.eslamasabry.oc.ui.words.res.agentsImportProblemArguments
import io.github.eslamasabry.oc.ui.words.res.agentsImportProblemArchive
import io.github.eslamasabry.oc.ui.words.res.agentsImportProblemName
import io.github.eslamasabry.oc.ui.words.res.agentsImportProblemProgram
import io.github.eslamasabry.oc.ui.words.res.agentsImportProblemProvider
import io.github.eslamasabry.oc.ui.words.res.agentsImportProblemStorage
import io.github.eslamasabry.oc.ui.words.res.agentsImportProblemTooLarge
import io.github.eslamasabry.oc.ui.words.res.agentsImportProblemUnsupported
import io.github.eslamasabry.oc.ui.words.res.agentsImportProgram
import io.github.eslamasabry.oc.ui.words.res.agentsImportProgramHelp
import io.github.eslamasabry.oc.ui.words.res.agentsImportProviders
import io.github.eslamasabry.oc.ui.words.res.agentsImportProvidersHelp
import io.github.eslamasabry.oc.ui.words.res.agentsImportRemove
import io.github.eslamasabry.oc.ui.words.res.agentsImportRemoveBody
import io.github.eslamasabry.oc.ui.words.res.agentsImportRowLine
import io.github.eslamasabry.oc.ui.words.res.agentsImportSection
import io.github.eslamasabry.oc.ui.words.res.agentsImportTitle
import io.github.eslamasabry.oc.ui.words.res.agentsImportWorking
import io.github.eslamasabry.oc.ui.words.res.serversCancel
import io.github.eslamasabry.oc.ui.words.res.serversWorking
import org.jetbrains.compose.resources.stringResource

/**
 * "Import an agent" (owner request 2026-11-15): a file the person already has, unpacked into this phone's Ubuntu and
 * run by the same agent helper as the catalog's agents. The card carries the one line; the sheet carries the whole
 * story of what a file may be, where it goes and how its provider keys are kept.
 */
@Composable
internal fun ImportAgentCard(state: AgentsUi, actions: AgentsActions, modifier: Modifier) {
    if (!state.importOffered) return
    KitActionCard(
        KitIcons.archive,
        stringResource(Res.string.agentsImportLine),
        KitAction(stringResource(Res.string.agentsImportTitle), actions::openImport),
        modifier,
    )
}

/** The agents the person imported, each with its own Remove; nothing shows while there are none. */
@Composable
internal fun ImportedAgentRows(state: AgentsUi, actions: AgentsActions, modifier: Modifier) {
    if (state.imported.isEmpty()) return
    KitText(stringResource(Res.string.agentsImportSection), modifier)
    for (agent in state.imported) {
        ImportedAgentRow(agent, actions::requestRemoveImported, state.importWorking)
    }
}

@Composable
private fun ImportedAgentRow(agent: ImportedAgent, remove: (String) -> Unit, working: Boolean) {
    KitRow(
        title = agent.name,
        supporting = stringResource(Res.string.agentsImportRowLine, agent.guestLauncher),
        supportingMaxLines = 2,
        leading = KitRowLeading.Glyph(KitIcons.file),
        trailing =
            KitRowTrailing.Action(
                KitLineAction(
                    stringResource(Res.string.agentsImportRemove, agent.name),
                    { remove(agent.id) },
                    enabled = !working,
                    disabledReason = if (working) stringResource(Res.string.serversWorking) else null,
                )
            ),
    )
}

/**
 * The import sheet: what a file can be (a `.tar.gz`, `.tar.bz2`, `.zip` or one Linux arm64 program), where it lands
 * (`~/.local/share/oc-imported`, a launcher in `~/.local/bin`), the provider keys in its own file, and the four fields
 * only a person with an agent in hand can fill.
 */
@Composable
@Suppress("LongMethod")
internal fun ImportAgentSheet(state: AgentsUi, actions: AgentsActions, enabled: KitEnabled) {
    if (!state.importOpen) return
    // Never rememberSaveable: provider values are secrets and must not enter saved instance state.
    val name = remember { TextFieldState() }
    val program = remember { TextFieldState() }
    val args = remember { TextFieldState() }
    val keys = remember { TextFieldState() }
    val can = if (state.importWorking) KitEnabled.Disabled(stringResource(Res.string.serversWorking)) else enabled
    KitSheet(
        visible = true,
        onDismissRequest = actions::closeImport,
        title = stringResource(Res.string.agentsImportTitle),
        icon = KitIcons.archive,
        dismissible = !state.importWorking,
        loading = state.importWorking,
        primary =
            KitAction(
                stringResource(Res.string.agentsImportInstall),
                {
                    actions.installImport(
                        name.text.toString(),
                        program.text.toString(),
                        args.text.toString(),
                        ImportedAgentInput.providers(keys.text.toString()),
                    )
                },
                enabled = can,
                working = state.importWorking,
            ),
        secondary = KitAction(stringResource(Res.string.serversCancel), actions::closeImport, enabled = can),
    ) {
        KitNotice(
            stringResource(Res.string.agentsImportInfo),
            title = stringResource(Res.string.agentsImportInfoTitle),
            notes =
                listOf(
                    stringResource(Res.string.agentsImportInfoWhere),
                    stringResource(Res.string.agentsImportInfoProviders),
                ),
            icon = KitIcons.archive,
        )
        state.importProblem?.let { problem ->
            KitErrorNotice(stringResource(importProblemWords(problem)))
        }
        KitButton(
            stringResource(Res.string.agentsImportChoose),
            actions::pickImportFile,
            role = KitButtonRole.Secondary,
            icon = KitIcons.file,
            enabled = can,
        )
        state.importPicked?.let { picked ->
            KitText(stringResource(Res.string.agentsImportChosen, picked.displayName, picked.format.name))
        }
        if (state.importWorking) KitText(stringResource(Res.string.agentsImportWorking))
        Column(verticalArrangement = Arrangement.spacedBy(Space.S3.dp)) {
            KitField(name, stringResource(Res.string.agentsImportName), enabled = can)
            KitField(
                program,
                stringResource(Res.string.agentsImportProgram),
                helper = stringResource(Res.string.agentsImportProgramHelp),
                enabled = can,
            )
            KitField(
                args,
                stringResource(Res.string.agentsImportArguments),
                helper = stringResource(Res.string.agentsImportArgumentsHelp),
                enabled = can,
            )
            KitField(
                keys,
                stringResource(Res.string.agentsImportProviders),
                kind = KitFieldKind.Secret,
                helper = stringResource(Res.string.agentsImportProvidersHelp),
                enabled = can,
            )
        }
    }
}

/** Why an import was refused, in one plain sentence. */
internal fun importProblemWords(problem: ImportedAgentProblem): org.jetbrains.compose.resources.StringResource =
    when (problem) {
        ImportedAgentProblem.UnsupportedFile -> Res.string.agentsImportProblemUnsupported
        ImportedAgentProblem.EmptyFile,
        ImportedAgentProblem.TooLarge -> Res.string.agentsImportProblemTooLarge
        ImportedAgentProblem.BadName,
        ImportedAgentProblem.BadId,
        ImportedAgentProblem.DuplicateId -> Res.string.agentsImportProblemName
        ImportedAgentProblem.BadCommand -> Res.string.agentsImportProblemProgram
        ImportedAgentProblem.TooManyArgs,
        ImportedAgentProblem.BadArg -> Res.string.agentsImportProblemArguments
        ImportedAgentProblem.TooManyProviders,
        ImportedAgentProblem.BadProvider -> Res.string.agentsImportProblemProvider
        ImportedAgentProblem.UnsafeArchive -> Res.string.agentsImportProblemArchive
        ImportedAgentProblem.Storage -> Res.string.agentsImportProblemStorage
    }

/** The confirm step before an imported agent's files and keys are deleted. */
@Composable
internal fun RemoveImportedConfirm(state: AgentsUi, actions: AgentsActions) {
    val agent = state.removingImported
    KitConfirm(
        agent?.let {
            KitConfirmSpec(
                title = stringResource(Res.string.agentsImportRemove, it.name),
                confirmLabel = stringResource(Res.string.agentsImportRemove, it.name),
                kind = KitConfirmKind.Destructive,
                body = stringResource(Res.string.agentsImportRemoveBody),
                icon = KitIcons.archive,
            )
        },
        onConfirm = actions::confirmRemoveImported,
        onCancel = actions::cancelRemoveImported,
        working = state.importWorking || state.busy,
    )
}
