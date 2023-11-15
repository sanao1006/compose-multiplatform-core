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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyInputElement
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputEvent
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.node.RootNodeOwner
import androidx.compose.ui.platform.setContent
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachReversed
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

@InternalComposeUiApi
fun CombinedComposeScene(
    density: Density = Density(1f),
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    bounds: IntRect = IntRect.Zero,
    coroutineContext: CoroutineContext = Dispatchers.Unconfined,
    composeSceneContext: ComposeSceneContext = EmptyComposeSceneContext,
    invalidate: () -> Unit = {},
): ComposeScene = CombinedComposeSceneImpl(
    density = density,
    layoutDirection = layoutDirection,
    bounds = bounds,
    coroutineContext = coroutineContext,
    composeSceneContext = composeSceneContext,
    invalidate = invalidate
)

@OptIn(InternalComposeUiApi::class)
private class CombinedComposeSceneImpl(
    density: Density,
    layoutDirection: LayoutDirection,
    bounds: IntRect,
    coroutineContext: CoroutineContext,
    private val composeSceneContext: ComposeSceneContext,
    invalidate: () -> Unit = {},
) : BaseComposeScene(
    coroutineContext = coroutineContext,
    invalidate = invalidate
) {
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

    override var bounds: IntRect = bounds
        set(value) {
            check(!isClosed) { "ComposeScene is closed" }
            field = value
            mainOwner.bounds = bounds
        }

    private val mainOwner = RootNodeOwner(
        density = density,
        layoutDirection = layoutDirection,
        bounds = bounds,
        coroutineContext = compositionContext.effectCoroutineContext,
        platformContext = composeSceneContext.platformContext,
        snapshotInvalidationTracker = snapshotInvalidationTracker,
        inputHandler = inputHandler,
    )

    private val layers = mutableListOf<AttachedComposeSceneLayer>()
    private var isFocused = false

    private val _layersCopyCache = CopiedList {
        it.add(null)
        for (layer in layers) {
            it.add(layer)
        }
    }
    private val _ownersCopyCache = CopiedList {
        it.add(mainOwner)
        for (layer in layers) {
            it.add(layer.owner)
        }
    }

    private inline fun forEachLayerReversed(action: (AttachedComposeSceneLayer?) -> Unit) =
        _layersCopyCache.withCopy {
            it.fastForEachReversed(action)
        }

    private inline fun forEachOwner(action: (RootNodeOwner) -> Unit) =
        _ownersCopyCache.withCopy {
            it.fastForEach(action)
        }

    private var focusedLayer: AttachedComposeSceneLayer? = null
    private val focusedOwner
        get() = focusedLayer?.owner ?: mainOwner
    private var gestureOwner: RootNodeOwner? = null
    private var lastHoverOwner: RootNodeOwner? = null

    override fun close() {
        check(!isClosed) { "ComposeScene is already closed" }
        mainOwner.dispose()
        layers.fastForEach {
            it.close()
        }
        super.close()
    }

    override fun setKeyEventListener(
        onPreviewKeyEvent: ((KeyEvent) -> Boolean)?,
        onKeyEvent: ((KeyEvent) -> Boolean)?
    ) {
        mainOwner.setRootModifier(
            KeyInputElement(
                onKeyEvent = onKeyEvent,
                onPreKeyEvent = onPreviewKeyEvent
            )
        )
    }

    override fun calculateContentSize(): IntSize {
        check(!isClosed) { "ComposeScene is closed" }
        return mainOwner.measureInConstraints(Constraints()) ?: IntSize.Zero
    }

    override fun createComposition(content: @Composable () -> Unit): Composition {
        return mainOwner.setContent(
            compositionContext,
            { compositionLocalContext },
            content = content
        )
    }

    override fun processPointerInputEvent(event: PointerInputEvent) {
        when (event.eventType) {
            PointerEventType.Press -> processPress(event)
            PointerEventType.Release -> processRelease(event)
            PointerEventType.Move -> processMove(event)
            PointerEventType.Enter -> processMove(event)
            PointerEventType.Exit -> processMove(event)
            PointerEventType.Scroll -> processScroll(event)
        }

        // Clean gestureOwner when there is no pressed pointers/buttons
        if (!event.isGestureInProgress) {
            gestureOwner = null
        }
    }

    override fun processKeyEvent(keyEvent: KeyEvent): Boolean =
        focusedOwner.onKeyEvent(keyEvent)

    override fun measureAndLayout() {
        forEachOwner { it.measureAndLayout() }
    }

    override fun draw(canvas: Canvas) {
        forEachOwner { it.draw(canvas) }
    }

    /**
     * Find hovered owner for position of first pointer.
     */
    private fun hoveredOwner(event: PointerInputEvent): RootNodeOwner {
        val position = event.pointers.first().position
        return layers.lastOrNull { it.isInBounds(position) }?.owner ?: mainOwner
    }

    /**
     * Check if [focusedLayer] blocks input for this owner.
     */
    private fun isInteractive(owner: RootNodeOwner?): Boolean {
        if (owner == null || focusedLayer == null) {
            return true
        }
        if (owner == mainOwner) {
            return false
        }
        for (layer in layers) {
            if (layer == focusedLayer) {
                return true
            }
            if (layer.owner == owner) {
                return false
            }
        }
        return true
    }

    private fun processPress(event: PointerInputEvent) {
        val currentGestureOwner = gestureOwner
        if (currentGestureOwner != null) {
            currentGestureOwner.onPointerInput(event)
            return
        }
        val position = event.pointers.first().position
        forEachLayerReversed { layer ->

            // If the position of in bounds of the owner - send event to it and stop processing
            if (layer == null || layer.isInBounds(position)) {
                val owner = layer?.owner ?: mainOwner
                owner.onPointerInput(event)
                gestureOwner = owner
                return
            }

            // Input event is out of bounds - send click outside notification
            layer.onOutsidePointerEvent(event)

            // if the owner is in focus, do not pass the event to underlying owners
            if (layer == focusedLayer) {
                return
            }
        }
    }

    private fun processRelease(event: PointerInputEvent) {
        // Send Release to gestureOwner even if is not hovered or under focusedOwner
        gestureOwner?.onPointerInput(event)
        if (!event.isGestureInProgress) {
            val owner = hoveredOwner(event)
            if (isInteractive(owner)) {
                processHover(event, owner)
            } else if (gestureOwner == null) {
                // If hovered owner is not interactive, then it means that
                // - It's not focusedOwner
                // - It placed under focusedOwner or not exist at all
                // In all these cases the even happened outside focused owner bounds
                focusedLayer?.onOutsidePointerEvent(event)
            }
        }
    }

    private fun processMove(event: PointerInputEvent) {
        var owner = when {
            // All touch events or mouse with pressed button(s)
            event.isGestureInProgress -> gestureOwner

            // Do not generate Enter and Move
            event.eventType == PointerEventType.Exit -> null

            // Find owner under mouse position
            else -> hoveredOwner(event)
        }

        // Even if the owner is not interactive, hover state still need to be updated
        if (!isInteractive(owner)) {
            owner = null
        }
        if (processHover(event, owner)) {
            return
        }
        owner?.onPointerInput(event.copy(eventType = PointerEventType.Move))
    }

    /**
     * Updates hover state and generates [PointerEventType.Enter] and [PointerEventType.Exit]
     * events. Returns true if [event] is consumed.
     */
    private fun processHover(event: PointerInputEvent, owner: RootNodeOwner?): Boolean {
        if (event.pointers.fastAny { it.type != PointerType.Mouse }) {
            // Track hover only for mouse
            return false
        }
        // Cases:
        // - move from outside to the window (owner != null, lastMoveOwner == null): Enter
        // - move from the window to outside (owner == null, lastMoveOwner != null): Exit
        // - move from one point of the window to another (owner == lastMoveOwner): Move
        // - move from one popup to another (owner != lastMoveOwner): [Popup 1] Exit, [Popup 2] Enter
        if (owner == lastHoverOwner) {
            // Owner wasn't changed
            return false
        }
        lastHoverOwner?.onPointerInput(event.copy(eventType = PointerEventType.Exit))
        owner?.onPointerInput(event.copy(eventType = PointerEventType.Enter))
        lastHoverOwner = owner

        // Changing hovering state replaces Move event, so treat it as consumed
        return true
    }

    private fun processScroll(event: PointerInputEvent) {
        val owner = hoveredOwner(event)
        if (isInteractive(owner)) {
            owner.onPointerInput(event)
        }
    }

    override fun releaseFocus() {
        forEachOwner { it.focusOwner.releaseFocus() }
        isFocused = false
    }

    override fun requestFocus() {
        focusedOwner.focusOwner.takeFocus()
        isFocused = true
    }

    override fun moveFocus(focusDirection: FocusDirection): Boolean {
        return focusedOwner.focusOwner.moveFocus(focusDirection)
    }

    override fun createLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        compositionContext: CompositionContext,
    ): ComposeSceneLayer = AttachedComposeSceneLayer(
        density = density,
        layoutDirection = layoutDirection,
        compositionContext = compositionContext,
    )

    private fun attach(layer: AttachedComposeSceneLayer) {
        check(!isClosed) { "ComposeScene is closed" }
        layers.add(layer)

        if (layer.focusable) {
            requestFocus(layer)
        }
        with(layer.owner.focusOwner) {
            if (isFocused) {
                takeFocus()
            } else {
                releaseFocus()
            }
        }
        inputHandler.onPointerUpdate()
        invalidateIfNeeded()
    }

    private fun detach(layer: AttachedComposeSceneLayer) {
        check(!isClosed) { "ComposeScene is closed" }
        layers.remove(layer)

        releaseFocus(layer)
        if (layer.owner == lastHoverOwner) {
            lastHoverOwner = null
        }
        if (layer.owner == gestureOwner) {
            gestureOwner = null
        }
        inputHandler.onPointerUpdate()
        invalidateIfNeeded()
    }

    private fun requestFocus(layer: AttachedComposeSceneLayer) {
        if (isInteractive(layer.owner)) {
            focusedLayer = layer

            // Exit event to lastHoverOwner will be sent via synthetic event on next frame
        }
        inputHandler.onPointerUpdate()
        invalidateIfNeeded()
    }

    private fun releaseFocus(layer: AttachedComposeSceneLayer) {
        if (layer == focusedLayer) {
            focusedLayer = layers.lastOrNull { it.focusable }

            // Enter event to new focusedOwner will be sent via synthetic event on next frame
        }
        inputHandler.onPointerUpdate()
        invalidateIfNeeded()
    }

    private inner class AttachedComposeSceneLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        private val compositionContext: CompositionContext,
    ) : ComposeSceneLayer {
        val owner = RootNodeOwner(
            density = density,
            layoutDirection = layoutDirection,
            coroutineContext = compositionContext.effectCoroutineContext,
            bounds = this@CombinedComposeSceneImpl.bounds,
            platformContext = composeSceneContext.platformContext,
            snapshotInvalidationTracker = snapshotInvalidationTracker,
            inputHandler = inputHandler,
        )
        private var composition: Composition? = null
        private var callback: ((Boolean) -> Unit)? = null

        override var density: Density by owner::density
        override var layoutDirection: LayoutDirection by owner::layoutDirection
        override var bounds: IntRect by mutableStateOf(this@CombinedComposeSceneImpl.bounds)
        override var scrimColor: Color? by mutableStateOf(null)
        override var focusable: Boolean = false
            set(value) {
                field = value
                if (value) {
                    requestFocus(this)
                } else {
                    releaseFocus(this)
                }
            }

        private val windowInfo
            get() = composeSceneContext.platformContext.windowInfo

        private val dialogScrimBlendMode
            get() = if (windowInfo.isWindowTransparent) {
                // Use background alpha channel to respect transparent window shape.
                BlendMode.SrcAtop
            } else {
                BlendMode.SrcOver
            }

        private val background: Modifier
            get() = scrimColor?.let {
                Modifier.drawBehind {
                    drawRect(
                        color = it,
                        blendMode = dialogScrimBlendMode
                    )
                }
            } ?: Modifier
        private var keyInput: Modifier by mutableStateOf(Modifier)

        init {
            attach(this)
        }

        override fun close() {
            detach(this)
            composition?.dispose()
            owner.dispose()
        }

        override fun setKeyEventListener(
            onPreviewKeyEvent: ((KeyEvent) -> Boolean)?,
            onKeyEvent: ((KeyEvent) -> Boolean)?,
        ) {
            keyInput = if (onPreviewKeyEvent != null || onKeyEvent != null) {
                Modifier.then(KeyInputElement(
                    onKeyEvent = onKeyEvent,
                    onPreKeyEvent = onPreviewKeyEvent
                ))
            } else {
                Modifier
            }
        }

        override fun setOutsidePointerEventListener(
            onOutsidePointerEvent: ((Boolean) -> Unit)?,
        ) {
            callback = onOutsidePointerEvent
        }

        override fun setContent(content: @Composable () -> Unit) {
            composition?.dispose()
            composition = owner.setContent(parent = compositionContext) {
                owner.setRootModifier(background then keyInput)
                content()
            }
        }

        fun isInBounds(point: Offset): Boolean {
            val intOffset = IntOffset(point.x.toInt(), point.y.toInt())
            return bounds.contains(intOffset)
        }

        fun onOutsidePointerEvent(event: PointerInputEvent) {
            callback?.invoke(event.isDismissRequest())
        }
    }
}

private val PointerInputEvent.isGestureInProgress get() = pointers.fastAny { it.down }

private fun PointerInputEvent.isMainAction() =
    button == PointerButton.Primary ||
        button == null && pointers.size == 1

private fun PointerInputEvent.isDismissRequest() =
    eventType == PointerEventType.Release && isMainAction()

private class CopiedList<T>(
    private val populate: (MutableList<T>) -> Unit
) : MutableList<T> by mutableListOf() {
    inline fun withCopy(
        block: (List<T>) -> Unit
    ) {
        // In case of recursive calls, allocate new list
        val copy = if (isEmpty()) this else mutableListOf()
        populate(copy)
        try {
            block(copy)
        } finally {
            copy.clear()
        }
    }
}
