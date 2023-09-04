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

import androidx.compose.foundation.gestures.NoPressGesture
import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.gestures.PressGestureScopeImpl
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.selection.TextFieldSelectionManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal actual fun Modifier.getTextFieldSelectionModifier(
    manager: TextFieldSelectionManager,
    enabled: Boolean,
    state: TextFieldState,
    focusRequester: FocusRequester,
    readOnly: Boolean
): Modifier =
    longPressDragGestureFilter(manager.touchSelectionObserver, enabled)
        .pointerInput(Unit) {
            detectTapGestures2(
                onDoubleTap = {
                    tapToFocus(state, focusRequester, !readOnly)
                    manager.doDoubleTapSelection(it)
                }
            )
        }

private suspend fun PointerInputScope.detectTapGestures2(
    onDoubleTap: ((Offset) -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    onPress: suspend PressGestureScope.(Offset) -> Unit = NoPressGesture,
    onTap: ((Offset) -> Unit)? = null
) = coroutineScope {
    // special signal to indicate to the sending side that it shouldn't intercept and consume
    // cancel/up events as we're only require down events
    val pressScope = PressGestureScopeImpl(this@detectTapGestures2)

    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume2()
        launch {
            pressScope.reset()
        }
        if (onPress !== NoPressGesture) launch {
            pressScope.onPress(down.position)
        }
        val longPressTimeout = onLongPress?.let {
            viewConfiguration.longPressTimeoutMillis
        } ?: (Long.MAX_VALUE / 2)
        var upOrCancel: PointerInputChange? = null
        try {
            // wait for first tap up or long press
            upOrCancel = withTimeout(longPressTimeout) {
                val insideWaitTimeout = waitForUpOrCancellation()
                println("insideWaitTimeout: $insideWaitTimeout")
                insideWaitTimeout
            }
            if (upOrCancel == null) {
                launch {
                    pressScope.cancel() // tap-up was canceled
                }
            } else {
                upOrCancel.consume2()
                launch {
                    pressScope.release()
                }
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            onLongPress?.invoke(down.position)
            consumeUntilUp2()
            launch {
                pressScope.release()
            }
        }

        if (upOrCancel != null) {
            // tap was successful.
            if (onDoubleTap == null) {
                onTap?.invoke(upOrCancel.position) // no need to check for double-tap.
            } else {
                // check for second tap
                val secondDown = awaitSecondDown2(upOrCancel)
                println("secondDown: $secondDown")
                if (secondDown == null) {
                    onTap?.invoke(upOrCancel.position) // no valid second tap started
                } else {
                    // Second tap down detected
                    launch {
                        pressScope.reset()
                    }
                    if (onPress !== NoPressGesture) {
                        launch { pressScope.onPress(secondDown.position) }
                    }

                    try {
                        // Might have a long second press as the second tap
                        withTimeout(longPressTimeout) {
                            val secondUp = waitForUpOrCancellation()
                            if (secondUp != null) {
                                secondUp.consume2()
                                launch {
                                    pressScope.release()
                                }
                                onDoubleTap(secondUp.position)
                            } else {
                                launch {
                                    pressScope.cancel()
                                }
                                onTap?.invoke(upOrCancel.position)
                            }
                        }
                    } catch (e: PointerEventTimeoutCancellationException) {
                        // The first tap was valid, but the second tap is a long press.
                        // notify for the first tap
                        onTap?.invoke(upOrCancel.position)

                        // notify for the long press
                        onLongPress?.invoke(secondDown.position)
                        consumeUntilUp2()
                        launch {
                            pressScope.release()
                        }
                    }
                }
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitSecondDown2(
    firstUp: PointerInputChange,
): PointerInputChange? = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
    val minUptime = firstUp.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
    var change: PointerInputChange
    // The second tap doesn't count if it happens before DoubleTapMinTime of the first tap
    do {
        val awaitFirstDown = awaitFirstDown(requireUnconsumed = false)
        println("awaitFirstDown: $awaitFirstDown")
        change = awaitFirstDown
    } while (change.uptimeMillis < minUptime)
    change
}

fun PointerEvent.consume2() {
    // I removed consume. It needs to proper focus request after the selection will applies
}

fun PointerInputChange.consume2() {
    // I removed consume. It needs to proper focus request after the selection will applies
}

fun consumeUntilUp2() {
    // I removed consume. It needs to proper focus request after the selection will applies
}
