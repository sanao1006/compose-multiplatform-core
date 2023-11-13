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

package androidx.compose.ui.window

import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.focus.FocusOwner
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerInputEvent
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

internal interface RootNodeOwner {
    var constraints: Constraints
    val contentSize: IntSize

    val bounds: IntRect

    val semanticsOwner: SemanticsOwner
    val focusOwner: FocusOwner

    var density: Density
    var layoutDirection: LayoutDirection

    fun hitTestInteropView(position: Offset): Boolean

    fun onPointerInput(event: PointerInputEvent)
    fun onKeyEvent(keyEvent: KeyEvent): Boolean

    fun initialize()
    fun dispose()

    fun measureAndLayout()
    fun draw(canvas: Canvas)
    fun compose(parent: CompositionContext): Composition
}
