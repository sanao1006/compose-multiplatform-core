/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.compose.ui.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.InputModeManagerImpl
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.node.Owner
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Platform specific bindings for [Owner].
 */
@InternalComposeUiApi
interface PlatformContext {
    val windowInfo: WindowInfo
    val inputContext: InputContext

    val focusManager: FocusManager
    fun requestFocus(): Boolean

    val rootForTestListener: RootForTestListener?
    val accessibilityListener: AccessibilityListener?

    interface InputContext {
        val viewConfiguration: ViewConfiguration
        val inputModeManager: InputModeManager
        val textInputService: PlatformTextInputService
        val textToolbar: TextToolbar
        fun setPointerIcon(pointerIcon: PointerIcon)
    }

    interface RootForTestListener {
        fun onRootForTestCreated(root: RootForTest)
        fun onRootForTestDisposed(root: RootForTest)
    }

    interface AccessibilityListener {
        fun onSemanticsOwnerCreated(semanticsOwner: SemanticsOwner)
        fun onSemanticsOwnerDisposed(semanticsOwner: SemanticsOwner)

        fun onSemanticsChange(semanticsOwner: SemanticsOwner)
    }
}

@InternalComposeUiApi
class EmptyPlatformContext(
    override val windowInfo: WindowInfo = WindowInfoImpl().apply {
        // true is a better default if platform doesn't provide WindowInfo.
        // otherwise UI will be rendered always in unfocused mode
        // (hidden textfield cursor, gray titlebar, etc)
        isWindowFocused = true
    },
    textInputService: PlatformTextInputService = EmptyPlatformTextInputService,
    override val rootForTestListener: PlatformContext.RootForTestListener? = null,
    override val accessibilityListener: PlatformContext.AccessibilityListener? = null
) : PlatformContext {
    override val inputContext = object : PlatformContext.InputContext {
        override val viewConfiguration get() = EmptyViewConfiguration
        override val inputModeManager = DefaultInputModeManager()
        override val textInputService = textInputService
        override val textToolbar: TextToolbar get() = EmptyTextToolbar

        override fun setPointerIcon(pointerIcon: PointerIcon) = Unit
    }

    override val focusManager: FocusManager
        get() = EmptyFocusManager

    override fun requestFocus(): Boolean = false
}

internal class DefaultInputModeManager(
    initialInputMode: InputMode = InputMode.Keyboard
) : InputModeManager {
    override var inputMode: InputMode by mutableStateOf(initialInputMode)

    @ExperimentalComposeUiApi
    override fun requestInputMode(inputMode: InputMode) =
        if (inputMode == InputMode.Touch || inputMode == InputMode.Keyboard) {
            this.inputMode = inputMode
            true
        } else {
            false
        }
}

internal object EmptyViewConfiguration : ViewConfiguration {
    override val longPressTimeoutMillis: Long = 500
    override val doubleTapTimeoutMillis: Long = 300
    override val doubleTapMinTimeMillis: Long = 40
    override val touchSlop: Float = 18f
}

internal object EmptyPlatformTextInputService : PlatformTextInputService {
    override fun startInput(
        value: TextFieldValue,
        imeOptions: ImeOptions,
        onEditCommand: (List<EditCommand>) -> Unit,
        onImeActionPerformed: (ImeAction) -> Unit
    ) = Unit

    override fun stopInput() = Unit
    override fun showSoftwareKeyboard() = Unit
    override fun hideSoftwareKeyboard() = Unit
    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) = Unit
}

private object EmptyTextToolbar : TextToolbar {
    override fun hide() = Unit
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) = Unit
}

private object EmptyFocusManager : FocusManager {
    override fun clearFocus(force: Boolean) = Unit
    override fun moveFocus(focusDirection: FocusDirection) = false
}
