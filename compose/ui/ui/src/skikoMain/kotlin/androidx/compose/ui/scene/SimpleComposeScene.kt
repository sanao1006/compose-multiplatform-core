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
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionContext
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerInputEvent
import androidx.compose.ui.platform.setContent
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.RootNodeOwner
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

@InternalComposeUiApi
fun ComposeScene(
    density: Density = Density(1f),
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    coroutineContext: CoroutineContext = Dispatchers.Unconfined,
    composeSceneContext: ComposeSceneContext = EmptyComposeSceneContext,
    invalidate: () -> Unit = {},
): ComposeScene = SimpleComposeSceneImpl(
    density = density,
    layoutDirection = layoutDirection,
    coroutineContext = coroutineContext,
    composeSceneContext = composeSceneContext,
    invalidate = invalidate
)

@OptIn(InternalComposeUiApi::class)
private class SimpleComposeSceneImpl(
    density: Density,
    layoutDirection: LayoutDirection,
    coroutineContext: CoroutineContext,
    private val composeSceneContext: ComposeSceneContext,
    invalidate: () -> Unit = {},
) : BaseComposeScene(
    coroutineContext = coroutineContext,
    invalidate = invalidate
) {
    private val mainOwner by lazy {
        RootNodeOwner(
            density = density,
            layoutDirection = layoutDirection,
            coroutineContext = compositionContext.effectCoroutineContext,
            constraints = constraints,
            platformContext = composeSceneContext.platformContext,
            snapshotInvalidationTracker = snapshotInvalidationTracker,
            inputHandler = inputHandler,
        )
    }

    override var density: Density = density
        set(value) {
            check(!isClosed) { "ComposeScene is closed" }
            field = value
            mainOwner.density = value
        }

    override var layoutDirection: LayoutDirection = layoutDirection
        set(value) {
            check(!isClosed) { "ComposeScene is closed" }
            field = value
            mainOwner.layoutDirection = value
        }

    override var constraints: Constraints = Constraints()
        set(value) {
            check(!isClosed) { "ComposeScene is closed" }
            field = value
            mainOwner.constraints = constraints
        }

    override val semanticsOwner: SemanticsOwner
        get() = mainOwner.semanticsOwner

    override fun close() {
        super.close()
        mainOwner.dispose()
    }

    override fun calculateContentSize(): IntSize {
        TODO("Not yet implemented")
    }

    override fun createComposition(content: @Composable () -> Unit): Composition {
        return mainOwner.setContent(
            compositionContext,
            { compositionLocalContext },
            content = content
        )
        // TODO: Set LocalComposeScene
    }

    override fun processPointerInputEvent(event: PointerInputEvent) =
        mainOwner.onPointerInput(event)

    override fun processKeyEvent(keyEvent: KeyEvent): Boolean =
        mainOwner.onKeyEvent(keyEvent)

    override fun measureAndLayout() {
        mainOwner.measureAndLayout()
    }

    override fun draw(canvas: Canvas) {
        mainOwner.draw(canvas)
    }

    override fun releaseFocus() {
        mainOwner.focusOwner.releaseFocus()
    }

    override fun requestFocus() {
        mainOwner.focusOwner.takeFocus()
    }

    override fun moveFocus(focusDirection: FocusDirection): Boolean {
        return mainOwner.focusOwner.moveFocus(focusDirection)
    }

    override fun createLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        compositionContext: CompositionContext,
    ): ComposeSceneLayer = composeSceneContext.createPlatformLayer(
        density = density,
        layoutDirection = layoutDirection,
        compositionContext = compositionContext
    )
}