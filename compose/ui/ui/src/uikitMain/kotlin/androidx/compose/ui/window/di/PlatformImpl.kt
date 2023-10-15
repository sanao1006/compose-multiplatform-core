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

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.DefaultInputModeManager
import androidx.compose.ui.platform.Platform
import androidx.compose.ui.platform.TextActions
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

internal class PlatformImpl(
    override val windowInfo: WindowInfo,
    override val textInputService: PlatformTextInputService,
    private val densityProvider: () -> Density,
    private val textMenuView: TextMenuView,
) : Platform by Platform.Empty {

    val density get() = densityProvider()

    override val viewConfiguration =
        object : ViewConfiguration {
            override val longPressTimeoutMillis: Long get() = 500
            override val doubleTapTimeoutMillis: Long get() = 300
            override val doubleTapMinTimeMillis: Long get() = 40

            // this value is originating from iOS 16 drag behavior reverse engineering
            override val touchSlop: Float get() = with(density) { 10.dp.toPx() }
        }
    override val textToolbar = object : TextToolbar {
        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?
        ) {
            val skiaRect = with(density) {
                org.jetbrains.skia.Rect.makeLTRB(
                    l = rect.left / density,
                    t = rect.top / density,
                    r = rect.right / density,
                    b = rect.bottom / density,
                )
            }
            textMenuView.showTextMenu(
                targetRect = skiaRect,
                textActions = object : TextActions {
                    override val copy: (() -> Unit)? = onCopyRequested
                    override val cut: (() -> Unit)? = onCutRequested
                    override val paste: (() -> Unit)? = onPasteRequested
                    override val selectAll: (() -> Unit)? = onSelectAllRequested
                }
            )
        }

        /**
         * TODO on UIKit native behaviour is hide text menu, when touch outside
         */
        override fun hide() = textMenuView.hideTextMenu()

        override val status: TextToolbarStatus
            get() = if (textMenuView.isTextMenuShown())
                TextToolbarStatus.Shown
            else
                TextToolbarStatus.Hidden
    }

    override val inputModeManager = DefaultInputModeManager(InputMode.Touch)
}
