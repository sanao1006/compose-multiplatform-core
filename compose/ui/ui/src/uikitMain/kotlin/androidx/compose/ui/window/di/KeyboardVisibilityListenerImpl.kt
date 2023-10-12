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

package androidx.compose.ui.window.di

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.uikit.ComposeUIViewControllerConfiguration
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.toDpRect
import androidx.compose.ui.window.AttachedComposeContext
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotification
import platform.Foundation.NSValue
import platform.UIKit.CGRectValue
import platform.UIKit.UIScreen
import platform.UIKit.UIView

internal class KeyboardVisibilityListenerImpl(
    val viewProvider: () -> UIView,
    val configuration: ComposeUIViewControllerConfiguration,
    val attachedComposeContextProvider: () -> AttachedComposeContext?,
    val densityProvider: () -> Density
): KeyboardVisibilityListener {

    val view get() = viewProvider()
    val attachedComposeContext get() = attachedComposeContextProvider()
    val density get() = densityProvider()

    override val keyboardOverlapHeightState: MutableState<Float> = mutableStateOf(0f)

    override fun keyboardWillShow(arg: NSNotification) {
        val keyboardInfo = arg.userInfo!!["UIKeyboardFrameEndUserInfoKey"] as NSValue
        val keyboardHeight = keyboardInfo.CGRectValue().useContents { size.height }
        val screenHeight = UIScreen.mainScreen.bounds.useContents { size.height }

        val composeViewBottomY = UIScreen.mainScreen.coordinateSpace.convertPoint(
            point = CGPointMake(0.0, view.frame.useContents { size.height }),
            fromCoordinateSpace = view.coordinateSpace
        ).useContents { y }
        val bottomIndent = screenHeight - composeViewBottomY

        if (bottomIndent < keyboardHeight) {
            keyboardOverlapHeightState.value = (keyboardHeight - bottomIndent).toFloat()
        }

        val scene = attachedComposeContext?.scene ?: return

        if (configuration.onFocusBehavior == OnFocusBehavior.FocusableAboveKeyboard) {
            val focusedRect = scene.mainOwner?.focusOwner?.getFocusRect()?.toDpRect(density)

            if (focusedRect != null) {
                updateViewBounds(
                    offsetY = calcFocusedLiftingY(focusedRect, keyboardHeight)
                )
            }
        }
    }

    override fun keyboardWillHide(arg: NSNotification) {
        keyboardOverlapHeightState.value = 0f
        if (configuration.onFocusBehavior == OnFocusBehavior.FocusableAboveKeyboard) {
            updateViewBounds(offsetY = 0.0)
        }
    }

    private fun calcFocusedLiftingY(focusedRect: DpRect, keyboardHeight: Double): Double {
        val viewHeight = attachedComposeContext?.view?.frame?.useContents {
            size.height
        } ?: 0.0

        val hiddenPartOfFocusedElement: Double =
            keyboardHeight - viewHeight + focusedRect.bottom.value
        return if (hiddenPartOfFocusedElement > 0) {
            // If focused element is partially hidden by the keyboard, we need to lift it upper
            val focusedTopY = focusedRect.top.value
            val isFocusedElementRemainsVisible = hiddenPartOfFocusedElement < focusedTopY
            if (isFocusedElementRemainsVisible) {
                // We need to lift focused element to be fully visible
                hiddenPartOfFocusedElement
            } else {
                // In this case focused element height is bigger than remain part of the screen after showing the keyboard.
                // Top edge of focused element should be visible. Same logic on Android.
                maxOf(focusedTopY, 0f).toDouble()
            }
        } else {
            // Focused element is not hidden by the keyboard.
            0.0
        }
    }

    private fun updateViewBounds(offsetX: Double = 0.0, offsetY: Double = 0.0) {
        view.layer.setBounds(
            view.frame.useContents {
                CGRectMake(
                    x = offsetX,
                    y = offsetY,
                    width = size.width,
                    height = size.height
                )
            }
        )
    }

}
