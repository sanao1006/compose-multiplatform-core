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

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.selection.TextFieldSelectionManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.OffsetMapping

internal actual fun getTextSelectionModifier(manager: TextFieldSelectionManager, enabled: Boolean, onTap: () -> Unit) =
    if (enabled) {
        Modifier
            .then(Modifier.pointerInput(Unit) {
                detectDragGesturesAfterLongPress(onDragStart = { manager.touchDragTapSelectionObserver.onStart(startPoint = it) },
                    onDrag = { _, delta -> manager.touchDragTapSelectionObserver.onDrag(delta = delta)},
                    onDragCancel = { manager.touchDragTapSelectionObserver.onCancel() },
                    onDragEnd = { manager.touchDragTapSelectionObserver.onStop() }
                )
            })
            .then(Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        println("onDoubleTap, offset: $offset")
                        manager.touchDragTapSelectionObserver.onDoubleTap(startPoint = offset)
                    },
                    onTap = {
                        onTap()
                        manager.touchDragTapSelectionObserver.onTap(startPoint = it)
                    }
                )
            })
    } else {
        Modifier
    }

internal actual fun getTextTouchModifier(state: TextFieldState,
    interactionSource: MutableInteractionSource?,
    manager: TextFieldSelectionManager,
    offsetMapping: OffsetMapping,
    enabled: Boolean): Modifier = Modifier