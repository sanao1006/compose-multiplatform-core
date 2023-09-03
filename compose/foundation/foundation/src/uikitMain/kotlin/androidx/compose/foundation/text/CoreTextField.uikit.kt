/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.foundation.text

import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.text.selection.TextFieldSelectionManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.OffsetMapping
import kotlinx.coroutines.launch

@OptIn(InternalFoundationTextApi::class)
internal actual fun Modifier.focusBehavior(
    manager: TextFieldSelectionManager,
    state: TextFieldState,
    focusRequester: FocusRequester,
    readOnly: Boolean,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    offsetMapping: OffsetMapping
) = tapPressTextFieldModifier2(interactionSource, enabled,
    onTap = { offset: Offset ->
        tapToFocus(state, focusRequester, !readOnly)
        if (state.hasFocus) {
            if (state.handleState != HandleState.Selection) {
                state.layoutResult?.let { layoutResult ->
                    TextFieldDelegate.setCursorOffset(
                        offset,
                        layoutResult,
                        state.processor,
                        offsetMapping,
                        state.onValueChange
                    )
                    // Won't enter cursor state when text is empty.
                    if (state.textDelegate.text.isNotEmpty()) {
                        state.handleState = HandleState.Cursor
                    }
                }
            } else {
                manager.deselect(offset)
            }
        }
    },
    onDoubleTap = {
        tapToFocus(state, focusRequester, !readOnly)
        manager.doDoubleTapSelection(it)
    }
)


/**
 * Required for the press and tap [MutableInteractionSource] consistency for TextField.
 */
private fun Modifier.tapPressTextFieldModifier2(
    interactionSource: MutableInteractionSource?,
    enabled: Boolean = true,
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit
): Modifier = if (enabled) composed {
    val scope = rememberCoroutineScope()
    val pressedInteraction = remember { mutableStateOf<PressInteraction.Press?>(null) }
    val onTapState = rememberUpdatedState(onTap)
    DisposableEffect(interactionSource) {
        onDispose {
            pressedInteraction.value?.let { oldValue ->
                val interaction = PressInteraction.Cancel(oldValue)
                interactionSource?.tryEmit(interaction)
                pressedInteraction.value = null
            }
        }
    }
    Modifier.pointerInput(interactionSource) {

        val onPressHandler: suspend PressGestureScope.(Offset) -> Unit = {
            scope.launch {
                // Remove any old interactions if we didn't fire stop / cancel properly
                pressedInteraction.value?.let { oldValue ->
                    val interaction = PressInteraction.Cancel(oldValue)
                    interactionSource?.emit(interaction)
                    pressedInteraction.value = null
                }
                val interaction = PressInteraction.Press(it)
                interactionSource?.emit(interaction)
                pressedInteraction.value = interaction
            }
            val success = tryAwaitRelease()
            scope.launch {
                pressedInteraction.value?.let { oldValue ->
                    val interaction =
                        if (success) {
                            PressInteraction.Release(oldValue)
                        } else {
                            PressInteraction.Cancel(oldValue)
                        }
                    interactionSource?.emit(interaction)
                    pressedInteraction.value = null
                }
            }
        }

        detectTapGestures(
            onPress = if (pressSelectionEnabled) { onPressHandler } else { {} },
            onTap = { onTapState.value.invoke(it) },
            onDoubleTap = if (doubleTapSelectionEnabled) { onDoubleTap } else { {} }
        )
    }
} else this


internal actual fun Modifier.selectionBehavior(
    manager: TextFieldSelectionManager,
    state: TextFieldState,
    focusRequester: FocusRequester,
    readOnly: Boolean,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    offsetMapping: OffsetMapping
): Modifier = longPressDragGestureFilter(manager.touchSelectionObserver, enabled)
