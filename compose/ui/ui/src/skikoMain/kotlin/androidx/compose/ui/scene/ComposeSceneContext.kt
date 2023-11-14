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

import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.EmptyPlatformContext
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.RootNodeOwner

@InternalComposeUiApi
interface ComposeSceneContext {
    val platformContext: PlatformContext

    fun createPlatformLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        compositionContext: CompositionContext,
    ) : ComposeSceneLayer
}

@OptIn(InternalComposeUiApi::class)
internal object EmptyComposeSceneContext : ComposeSceneContext {
    override val platformContext: PlatformContext = EmptyPlatformContext()

    override fun createPlatformLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        compositionContext: CompositionContext
    ): ComposeSceneLayer {
        throw IllegalStateException()
    }
}
