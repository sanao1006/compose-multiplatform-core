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

package androidx.compose.ui.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.LocalComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.requireCurrent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection

interface ComposeSceneLayer {
    var density: Density
    var layoutDirection: LayoutDirection

    var focusable: Boolean
    var bounds: IntRect
    var scrimColor: Color?

    fun setKeyEventListener(
        onPreviewKeyEvent: ((KeyEvent) -> Boolean)? = null,
        onKeyEvent: ((KeyEvent) -> Boolean)? = null,
    )
    fun setOutsidePointerEventListener(
        onOutsidePointerEvent: ((Boolean) -> Unit)? = null,
    )

    fun setContent(content: @Composable () -> Unit)
    fun dispose()
}

@Composable
internal fun rememberComposeSceneLayer(): ComposeSceneLayer {
    val scene = LocalComposeScene.requireCurrent()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val parentComposition = rememberCompositionContext()
    val layer = remember {
        scene.createLayer(
            density = density,
            layoutDirection = layoutDirection,
            compositionContext = parentComposition,
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            layer.dispose()
        }
    }
    SideEffect {
        layer.density = density
        layer.layoutDirection = layoutDirection
    }
    return layer
}
