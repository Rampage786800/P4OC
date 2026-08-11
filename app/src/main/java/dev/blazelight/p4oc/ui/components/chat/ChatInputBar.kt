package dev.blazelight.p4oc.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blazelight.p4oc.R
import dev.blazelight.p4oc.domain.model.Command
import dev.blazelight.p4oc.ui.components.TuiLoadingIndicator
import dev.blazelight.p4oc.ui.components.command.rememberResolvedCommandMetadata
import dev.blazelight.p4oc.ui.theme.LocalOpenCodeTheme
import dev.blazelight.p4oc.ui.theme.Sizing
import dev.blazelight.p4oc.ui.theme.Spacing
import dev.blazelight.p4oc.ui.theme.TuiCodeFontSize

data class ModelOption(
    val key: String,
    val displayName: String
)

private fun nextCommandIndex(
    currentIndex: Int,
    delta: Int,
    commandCount: Int
): Int = (currentIndex + delta + commandCount) % commandCount

@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    onAbort: () -> Unit = {},
    attachedFiles: List<SelectedFile> = emptyList(),
    onAttachClick: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    commands: List<Command> = emptyList(),
    isLoadingCommands: Boolean = false,
    commandLoadError: String? = null,
    onRetryCommands: () -> Unit = {},
    onCommandSelected: (Command) -> Unit = {},
    requestFocus: Boolean = false,
    enterToSend: Boolean = false,
) {
    val theme = LocalOpenCodeTheme.current
    val focusRequester = remember { FocusRequester() }
    val textState = rememberTextFieldState(initialText = value)

    // External value changes (e.g. a programmatic set from the parent) → field.
    LaunchedEffect(value) {
        if (value != textState.text.toString()) {
            textState.setTextAndPlaceCursorAtEnd(value)
        }
    }
    // Field edits → hoisted state. TextFieldState manages the IME composing
    // region correctly, so clearText() (on send) actually clears even on IMEs
    // like Samsung's that re-commit composing text with the old value API.
    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }.collect { onValueChange(it) }
    }

    // Request focus when triggered
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // Focus request can fail if not attached yet
            }
        }
    }

    // Determine button state
    val currentText = textState.text.toString()
    val hasContent = currentText.isNotBlank() || attachedFiles.isNotEmpty()
    val canSubmit = hasContent && enabled && !isLoading
    val loadingDescription = stringResource(R.string.cd_loading)
    val sendDescription = stringResource(R.string.chat_action_send)
    val disconnectedDescription = stringResource(R.string.chat_disabled_disconnected)
    val emptyDescription = stringResource(R.string.chat_disabled_empty)
    val attachDescription = stringResource(R.string.chat_action_attach)
    val stopDescription = stringResource(R.string.chat_action_stop)
    val sendContentDescription = when {
        isLoading -> loadingDescription
        canSubmit -> sendDescription
        !enabled -> disconnectedDescription
        else -> emptyDescription
    }
    val resolvedCommands = rememberResolvedCommandMetadata(commands)

    // Show slash commands popup when input starts with "/"
    val showSlashCommands = currentText.startsWith("/") && !currentText.contains(" ")
    val filteredCommands = remember(resolvedCommands, currentText) {
        val searchTerm = currentText.removePrefix("/").lowercase()
        val matches = if (searchTerm.isEmpty()) {
            resolvedCommands
        } else {
            resolvedCommands.filter { cmd ->
                cmd.name.lowercase().contains(searchTerm) ||
                    cmd.description?.lowercase()?.contains(searchTerm) == true
            }
        }
        matches
    }
    var activeCommandIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentText, filteredCommands.size) {
        activeCommandIndex = activeCommandIndex.coerceIn(0, (filteredCommands.size - 1).coerceAtLeast(0))
    }

    fun selectActiveCommand(): Boolean {
        val command = filteredCommands.getOrNull(activeCommandIndex) ?: return false
        textState.setTextAndPlaceCursorAtEnd("/${command.name} ")
        onCommandSelected(command)
        return true
    }

    fun clearInput() {
        // TextFieldState.clearText() resets the editing buffer including the IME
        // composing region, so the field stays cleared after send.
        textState.clearText()
    }

    fun submitFromEnter(): Boolean = when {
        showSlashCommands -> selectActiveCommand()
        canSubmit -> {
            onSend()
            clearInput()
            true
        }
        else -> false
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(Sizing.strokeThin, theme.border, RectangleShape),
            color = theme.backgroundElement,
            shape = RectangleShape
        ) {
            Column {
                if (attachedFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        attachedFiles.forEach { file ->
                            val chipColor = if (file.available) theme.accent else theme.warning
                            val chipLabelColor = if (file.available) theme.text else theme.warning
                            val removeDescription = stringResource(
                                R.string.chat_action_remove_attachment,
                                file.name,
                            )
                            Box(modifier = Modifier.height(48.dp)) {
                                Surface(
                                    shape = RectangleShape,
                                    color = chipColor.copy(alpha = 0.1f),
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .height(Sizing.buttonHeightSm)
                                        .border(Sizing.strokeMd, chipColor, RectangleShape)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = Spacing.mdLg),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                                    ) {
                                        Text(
                                            file.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = chipLabelColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = Sizing.panelWidthMd)
                                        )
                                        if (!file.available) {
                                            Text(
                                                text = stringResource(R.string.attachment_unavailable),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = theme.warning,
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(Sizing.iconButtonMd))
                                    }
                                }
                                IconButton(
                                    onClick = { onRemoveAttachment(file.path) },
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .size(48.dp)
                                        .semantics { contentDescription = removeDescription }
                                ) {
                                    Text(
                                        text = "×",
                                        color = theme.textMuted,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    if (enabled) {
                        IconButton(
                            onClick = onAttachClick,
                            modifier = Modifier
                                .size(Sizing.iconButtonMd)
                                .semantics { contentDescription = attachDescription }
                                .testTag("chat_attach_button")
                        ) {
                            Text(
                                text = "+",
                                color = theme.textMuted,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Sizing.textFieldHeightSm)
                            .border(
                                width = Sizing.strokeMd,
                                color = theme.border,
                                shape = RoundedCornerShape(50)
                            )
                            .background(
                                theme.background,
                                RoundedCornerShape(50)
                            )
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (currentText.isEmpty()) {
                            Text(
                                "> Message...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Default,
                                color = theme.textMuted
                            )
                        }
                        BasicTextField(
                            state = textState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) {
                                        return@onPreviewKeyEvent false
                                    }
                                    when (event.key) {
                                        Key.DirectionDown -> {
                                            if (!showSlashCommands) return@onPreviewKeyEvent false
                                            if (filteredCommands.isNotEmpty()) {
                                                activeCommandIndex = nextCommandIndex(
                                                    activeCommandIndex,
                                                    1,
                                                    filteredCommands.size
                                                )
                                            }
                                            true
                                        }
                                        Key.DirectionUp -> {
                                            if (!showSlashCommands) return@onPreviewKeyEvent false
                                            if (filteredCommands.isNotEmpty()) {
                                                activeCommandIndex =
                                                    nextCommandIndex(
                                                        activeCommandIndex,
                                                        -1,
                                                        filteredCommands.size
                                                    )
                                            }
                                            true
                                        }
                                        Key.Tab -> showSlashCommands && selectActiveCommand()
                                        Key.Enter, Key.NumPadEnter -> {
                                            when {
                                                showSlashCommands -> selectActiveCommand()
                                                enterToSend -> submitFromEnter()
                                                else -> false
                                            }
                                        }
                                        else -> false
                                    }
                                }
                                .testTag("chat_input"),
                            enabled = true,
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = TuiCodeFontSize.xxl,
                                color = theme.text
                            ),
                            cursorBrush = SolidColor(theme.accent),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
                            ),
                            onKeyboardAction = { performDefault ->
                                if (enterToSend) submitFromEnter() else performDefault()
                            },
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4)
                        )
                    }

                    if (isBusy) {
                        IconButton(
                            onClick = onAbort,
                            modifier = Modifier
                                .size(Sizing.iconButtonMd)
                                .semantics { contentDescription = stopDescription }
                                .testTag("chat_abort_button")
                        ) {
                            Text(
                                text = "■",
                                color = theme.text,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (!canSubmit) return@IconButton
                            onSend()
                            clearInput()
                            focusRequester.requestFocus()
                        },
                        enabled = canSubmit,
                        modifier = Modifier
                            .size(Sizing.iconButtonMd)
                            .background(
                                color = if (canSubmit) theme.accent else theme.background,
                                shape = RoundedCornerShape(50)
                            )
                            .semantics { contentDescription = sendContentDescription }
                            .testTag("send_button")
                    ) {
                        if (isLoading) {
                            TuiLoadingIndicator()
                        } else {
                            Text(
                                text = "↑",
                                color = if (canSubmit) theme.background else theme.textMuted,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
        }

        // Overlay above the input bar so it doesn't push agent/model controls.
        if (showSlashCommands) {
            SlashCommandsPopup(
                state = SlashCommandsPopupState(
                    commands = resolvedCommands,
                    filter = currentText,
                    isLoading = isLoadingCommands,
                    error = commandLoadError,
                    activeCommandName = filteredCommands.getOrNull(activeCommandIndex)?.name
                ),
                callbacks = SlashCommandsPopupCallbacks(
                    onRetry = onRetryCommands,
                    onCommandSelected = { command ->
                        // Replace the current text with the command
                        textState.setTextAndPlaceCursorAtEnd("/${command.name} ")
                        onCommandSelected(command)
                    },
                    onDismiss = { /* Keep popup open while typing */ }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
            )
        }
    }
}
