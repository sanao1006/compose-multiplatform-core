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
    coroutineContext: CoroutineContext = Dispatchers.Unconfined,
    composeSceneContext: ComposeSceneContext = EmptyComposeSceneContext,
    invalidate: () -> Unit = {},
): ComposeScene = CombinedComposeSceneImpl(
    density = density,
    layoutDirection = layoutDirection,
    coroutineContext = coroutineContext,
    composeSceneContext = composeSceneContext,
    invalidate = invalidate
)

@OptIn(InternalComposeUiApi::class)
private class CombinedComposeSceneImpl(
    density: Density,
    layoutDirection: LayoutDirection,
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

    override var constraints: Constraints = Constraints()
        set(value) {
            check(!isClosed) { "ComposeScene is closed" }
            field = value
            mainOwner.constraints = constraints
        }

    override val semanticsOwner: SemanticsOwner
        get() = mainOwner.semanticsOwner

    private val mainOwner = RootNodeOwner(
        density = density,
        layoutDirection = layoutDirection,
        coroutineContext = compositionContext.effectCoroutineContext,
        constraints = constraints,
        platformContext = composeSceneContext.platformContext,
        snapshotInvalidationTracker = snapshotInvalidationTracker,
        inputHandler = inputHandler,
    )

    private val attachedLayers = mutableListOf<AttachedComposeSceneLayer>()
    private var isFocused = false

    /**
     * Contains all registered [RootNodeOwner] (main frame, popups, etc.) in order of registration.
     * So that Popup opened from main owner will have bigger index.
     * This logic is used by accessibility.
     */
    private val owners = mutableListOf(mainOwner)

    // Cache to reduce allocations
    private val _ownersCopyCache = mutableListOf<RootNodeOwner>()
    private inline fun withOwnersCopy(
        block: (List<RootNodeOwner>) -> Unit
    ) {
        // In case of recursive calls, allocate new list
        val copy = if (_ownersCopyCache.isEmpty()) _ownersCopyCache else mutableListOf()
        copy.addAll(owners)
        try {
            block(copy)
        } finally {
            copy.clear()
        }
    }

    private inline fun forEachOwner(action: (RootNodeOwner) -> Unit) =
        withOwnersCopy {
            it.fastForEach(action)
        }

    private inline fun forEachOwnerReversed(action: (RootNodeOwner) -> Unit) =
        withOwnersCopy {
            it.fastForEachReversed(action)
        }

    private var focusedOwner: RootNodeOwner = mainOwner
    private var gestureOwner: RootNodeOwner? = null
    private var lastHoverOwner: RootNodeOwner? = null

    private val lastFocusableOwner
        get() = attachedLayers.lastOrNull { it.focusable }?.owner ?: mainOwner

    override fun close() {
        super.close()
        mainOwner.dispose()
        attachedLayers.fastForEach {
            it.dispose()
        }
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
        return owners.lastOrNull { it.isInBounds(position) } ?: mainOwner
    }

    /**
     * Check if [focusedOwner] blocks input for this owner.
     */
    private fun isInteractive(owner: RootNodeOwner?): Boolean =
        owner == null || owners.indexOf(focusedOwner) <= owners.indexOf(owner)

    private fun processPress(event: PointerInputEvent) {
        val currentGestureOwner = gestureOwner
        if (currentGestureOwner != null) {
            currentGestureOwner.onPointerInput(event)
            return
        }
        val position = event.pointers.first().position
        forEachOwnerReversed { owner ->

            // If the position of in bounds of the owner - send event to it and stop processing
            if (owner.isInBounds(position)) {
                owner.onPointerInput(event)
                gestureOwner = owner
                return
            }

            // Input event is out of bounds - send click outside notification
            // TODO owner.onOutsidePointerEvent?.invoke(event)

            // if the owner is in focus, do not pass the event to underlying owners
            if (owner == focusedOwner) {
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
                // TODO focusedOwner.onOutsidePointerEvent?.invoke(event)
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
        focusable = false, // TODO
    )

    private fun attach(layer: AttachedComposeSceneLayer) {
        check(!isClosed) { "ComposeScene is closed" }
        owners.add(layer.owner)

        if (layer.focusable) {
            focusedOwner = layer.owner

            // Exit event to lastHoverOwner will be sent via synthetic event on next frame
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
        if (isClosed) return
        owners.remove(layer.owner)

        if (layer.owner == focusedOwner) {
            focusedOwner = lastFocusableOwner

            // Enter event to new focusedOwner will be sent via synthetic event on next frame
        }
        if (layer.owner == lastHoverOwner) {
            lastHoverOwner = null
        }
        if (layer.owner == gestureOwner) {
            gestureOwner = null
        }
        inputHandler.onPointerUpdate()
        invalidateIfNeeded()
    }

    private inner class AttachedComposeSceneLayer(
        density: Density,
        layoutDirection: LayoutDirection,
        private val compositionContext: CompositionContext,
        override var focusable: Boolean,
    ) : ComposeSceneLayer {
        val owner = RootNodeOwner(
            density = density,
            layoutDirection = layoutDirection,
            coroutineContext = compositionContext.effectCoroutineContext,
            constraints = constraints,
            platformContext = composeSceneContext.platformContext,
            snapshotInvalidationTracker = snapshotInvalidationTracker,
            inputHandler = inputHandler,
        )
        private var composition: Composition? = null

        override var density: Density by owner::density
        override var layoutDirection: LayoutDirection by owner::layoutDirection
        override var bounds: IntRect by owner::bounds
        override var scrimColor: Color? by mutableStateOf(null)

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

        override fun dispose() {
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
            // TODO
        }

        override fun setContent(content: @Composable () -> Unit) {
            composition?.dispose()
            composition = owner.setContent(parent = compositionContext) {
                owner.setRootModifier(background then keyInput)
                content()
            }
        }
    }
}

private val PointerInputEvent.isGestureInProgress get() = pointers.fastAny { it.down }

private fun RootNodeOwner.isInBounds(point: Offset): Boolean {
    val intOffset = IntOffset(point.x.toInt(), point.y.toInt())
    return bounds.contains(intOffset)
}
