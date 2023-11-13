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

package androidx.compose.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputEvent
import androidx.compose.ui.input.pointer.PointerInputEventData
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.copyFor
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skiko.currentNanoTime

@ExperimentalComposeUiApi
abstract class BaseComposeScene {
    abstract var constraints: Constraints
    abstract val contentSize: IntSize

    abstract fun hasInvalidations(): Boolean

    abstract fun setContent(content: @Composable () -> Unit)

    // TODO(demin): return Boolean (when it is consumed)
    /**
     * Send pointer event to the content.
     *
     * @param eventType Indicates the primary reason that the event was sent.
     * @param position The [Offset] of the current pointer event, relative to the content.
     * @param scrollDelta scroll delta for the PointerEventType.Scroll event
     * @param timeMillis The time of the current pointer event, in milliseconds. The start (`0`) time
     * is platform-dependent.
     * @param type The device type that produced the event, such as [mouse][PointerType.Mouse],
     * or [touch][PointerType.Touch].
     * @param buttons Contains the state of pointer buttons (e.g. mouse and stylus buttons) after the event.
     * @param keyboardModifiers Contains the state of modifier keys, such as Shift, Control,
     * and Alt, as well as the state of the lock keys, such as Caps Lock and Num Lock.
     * @param nativeEvent The original native event.
     * @param button Represents the index of a button which state changed in this event. It's null
     * when there was no change of the buttons state or when button is not applicable (e.g. touch event).
     */
    abstract fun sendPointerEvent(
        eventType: PointerEventType,
        position: Offset,
        scrollDelta: Offset = Offset(0f, 0f),
        timeMillis: Long = (currentNanoTime() / 1E6).toLong(),
        type: PointerType = PointerType.Mouse,
        buttons: PointerButtons? = null,
        keyboardModifiers: PointerKeyboardModifiers? = null,
        nativeEvent: Any? = null,
        button: PointerButton? = null
    )

    // TODO(demin): return Boolean (when it is consumed)
    // TODO(demin) verify that pressure is the same on Android and iOS
    /**
     * Send pointer event to the content. The more detailed version of [sendPointerEvent] that can accept
     * multiple pointers.
     *
     * @param eventType Indicates the primary reason that the event was sent.
     * @param pointers The current pointers with position relative to the content.
     * There can be multiple pointers, for example, if we use Touch and touch screen with multiple fingers.
     * Contains only the state of the active pointers.
     * Touch that is released still considered as active on PointerEventType.Release event (but with pressed=false). It
     * is no longer active after that, and shouldn't be passed to the scene.
     * @param buttons Contains the state of pointer buttons (e.g. mouse and stylus buttons) after the event.
     * @param keyboardModifiers Contains the state of modifier keys, such as Shift, Control,
     * and Alt, as well as the state of the lock keys, such as Caps Lock and Num Lock.
     * @param scrollDelta scroll delta for the PointerEventType.Scroll event
     * @param timeMillis The time of the current pointer event, in milliseconds. The start (`0`) time
     * is platform-dependent.
     * @param nativeEvent The original native event.
     * @param button Represents the index of a button which state changed in this event. It's null
     * when there was no change of the buttons state or when button is not applicable (e.g. touch event).
     */
    @ExperimentalComposeUiApi
    abstract fun sendPointerEvent(
        eventType: PointerEventType,
        pointers: List<ComposeScene.Pointer>,
        buttons: PointerButtons = PointerButtons(),
        keyboardModifiers: PointerKeyboardModifiers = PointerKeyboardModifiers(),
        scrollDelta: Offset = Offset(0f, 0f),
        timeMillis: Long = (currentNanoTime() / 1E6).toLong(),
        nativeEvent: Any? = null,
        button: PointerButton? = null,
    )


    /**
     * Send [KeyEvent] to the content.
     * @return true if the event was consumed by the content
     */
    abstract fun sendKeyEvent(keyEvent: KeyEvent): Boolean
}
