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
import androidx.compose.runtime.collection.mutableVectorOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ComposeSceneInputHandler
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.Autofill
import androidx.compose.ui.autofill.AutofillTree
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusOwner
import androidx.compose.ui.focus.FocusOwnerImpl
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.InteropViewCatchPointerModifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerIconService
import androidx.compose.ui.input.pointer.PointerInputEvent
import androidx.compose.ui.input.pointer.PointerInputEventProcessor
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PositionCalculator
import androidx.compose.ui.layout.RootMeasurePolicy
import androidx.compose.ui.modifier.ModifierLocalManager
import androidx.compose.ui.node.BackwardsCompatNode
import androidx.compose.ui.node.HitTestResult
import androidx.compose.ui.node.InternalCoreApi
import androidx.compose.ui.node.LayoutNode
import androidx.compose.ui.node.LayoutNodeDrawScope
import androidx.compose.ui.node.MeasureAndLayoutDelegate
import androidx.compose.ui.node.Owner
import androidx.compose.ui.node.SnapshotInvalidationTracker
import androidx.compose.ui.platform.DefaultAccessibilityManager
import androidx.compose.ui.platform.DefaultHapticFeedback
import androidx.compose.ui.platform.DefaultUiApplier
import androidx.compose.ui.platform.Platform
import androidx.compose.ui.platform.PlatformClipboardManager
import androidx.compose.ui.platform.RenderNodeLayer
import androidx.compose.ui.platform.SkiaRootForTest
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.EmptySemanticsElement
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.InternalTextApi
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.input.PlatformTextInputPluginRegistry
import androidx.compose.ui.text.input.PlatformTextInputPluginRegistryImpl
import androidx.compose.ui.text.input.TextInputService
import androidx.compose.ui.text.platform.FontLoader
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toIntRect
import kotlin.coroutines.CoroutineContext

internal interface RootNodeOwner {
    var constraints: Constraints
    val contentSize: IntSize

    var bounds: IntRect

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

    fun setRootModifier(modifier: Modifier)
    fun createComposition(parent: CompositionContext): Composition
}

internal fun RootNodeOwner(
    snapshotInvalidationTracker: SnapshotInvalidationTracker,
    inputHandler: ComposeSceneInputHandler,
    platform: Platform,
    density: Density,
    coroutineContext: CoroutineContext,
    layoutDirection: LayoutDirection,
    constraints: Constraints,
) : RootNodeOwner = RootNodeOwnerImpl(
    snapshotInvalidationTracker = snapshotInvalidationTracker,
    inputHandler = inputHandler,
    platform = platform,
    density = density,
    layoutDirection = layoutDirection,
    coroutineContext = coroutineContext,
    constraints = constraints
)

@OptIn(
    ExperimentalComposeUiApi::class,
    ExperimentalTextApi::class,
    InternalCoreApi::class,
    InternalComposeUiApi::class
)
private class RootNodeOwnerImpl(
    private val snapshotInvalidationTracker: SnapshotInvalidationTracker,
    private val inputHandler: ComposeSceneInputHandler,

    private val platform: Platform,
    density: Density,
    layoutDirection: LayoutDirection,
    override val coroutineContext: CoroutineContext,
    override var constraints: Constraints,
) : Owner, RootNodeOwner, SkiaRootForTest {
    override val windowInfo: WindowInfo
        get() = platform.windowInfo

    override var bounds by mutableStateOf(constraints.maxSize.toIntRect())

    override var density by mutableStateOf(density)

    private var _layoutDirection by mutableStateOf(layoutDirection)
    override var layoutDirection: LayoutDirection
        get() = _layoutDirection
        set(value) {
            _layoutDirection = value
            focusOwner.layoutDirection = value
            root.layoutDirection = value
        }

    override val sharedDrawScope = LayoutNodeDrawScope()

    // TODO(https://github.com/JetBrains/compose-multiplatform/issues/2944)
    //  Check if ComposePanel/SwingPanel focus interop work correctly with new features of
    //  the focus system (it works with the old features like moveFocus/clearFocus)
    override val focusOwner: FocusOwner = FocusOwnerImpl {
        registerOnEndApplyChangesListener(it)
    }.also {
        it.layoutDirection = layoutDirection
    }

    override val inputModeManager: InputModeManager
        get() = platform.inputModeManager

    override val modifierLocalManager = ModifierLocalManager(this)

    // TODO(b/177931787) : Consider creating a KeyInputManager like we have for FocusManager so
    //  that this common logic can be used by all owners.
    private val keyInputModifier = Modifier.onKeyEvent {
        val focusDirection = getFocusDirection(it)
        if (focusDirection == null || it.type != KeyEventType.KeyDown) return@onKeyEvent false

        inputModeManager.requestInputMode(InputMode.Keyboard)
        // Consume the key event if we moved focus.
        focusOwner.moveFocus(focusDirection)
    }

    override val root = LayoutNode().also {
        it.layoutDirection = layoutDirection
        it.measurePolicy = RootMeasurePolicy
        it.modifier = EmptySemanticsElement
            .then(focusOwner.modifier)
            .then(keyInputModifier)
    }

    override val rootForTest
        get() = this

    override val snapshotObserver = snapshotInvalidationTracker.snapshotObserver()

    private val pointerInputEventProcessor = PointerInputEventProcessor(root)
    private val measureAndLayoutDelegate = MeasureAndLayoutDelegate(root)
    private val endApplyChangesListeners = mutableVectorOf<(() -> Unit)?>()

    override val textInputService = TextInputService(platform.textInputService)

    @Suppress("UNUSED_ANONYMOUS_PARAMETER")
    @OptIn(InternalTextApi::class)
    override val platformTextInputPluginRegistry: PlatformTextInputPluginRegistry
        get() = PlatformTextInputPluginRegistryImpl { factory, platformTextInput ->
            TODO("See https://issuetracker.google.com/267235947")
        }

    @Deprecated(
        "fontLoader is deprecated, use fontFamilyResolver",
        replaceWith = ReplaceWith("fontFamilyResolver")
    )
    override val fontLoader = FontLoader()

    override val fontFamilyResolver = createFontFamilyResolver()

    override val hapticFeedBack = DefaultHapticFeedback()

    override val clipboardManager = PlatformClipboardManager()

    override val accessibilityManager = DefaultAccessibilityManager()

    override val textToolbar
        get() = platform.textToolbar

    override val semanticsOwner: SemanticsOwner = SemanticsOwner(root)

    // TODO: Move out of here
    val accessibilityController = platform.accessibilityController(semanticsOwner)

    override val autofillTree = AutofillTree()

    override val autofill: Autofill?
        get() = null

    override val viewConfiguration
        get() = platform.viewConfiguration


    override val containerSize: IntSize
        // TODO: properly initialize Platform/WindowInfo in tests
        // get() = platform.windowInfo.containerSize
        get() = constraints.maxSize

    override val hasPendingMeasureOrLayout: Boolean
        get() = measureAndLayoutDelegate.hasPendingMeasureOrLayout

    override fun initialize() {
        snapshotObserver.startObserving()
        root.attach(this)

        // TODO: Move to SharedContext
        SkiaRootForTest.onRootCreatedCallback?.invoke(rootForTest as SkiaRootForTest)
    }

    override fun dispose() {
        // TODO: Move to SharedContext
        SkiaRootForTest.onRootDisposedCallback?.invoke(rootForTest as SkiaRootForTest)

        snapshotObserver.stopObserving()
        // we don't need to call root.detach() because root will be garbage collected
    }

    override var showLayoutBounds = false

    override fun requestFocus() = platform.requestFocusForOwner()

    override fun onAttach(node: LayoutNode) = Unit

    override fun onDetach(node: LayoutNode) {
        measureAndLayoutDelegate.onNodeDetached(node)
        snapshotObserver.clear(node)
        needClearObservations = true
    }

    override val measureIteration: Long get() = measureAndLayoutDelegate.measureIteration

    private var needClearObservations = false
    fun clearInvalidObservations() {
        if (needClearObservations) {
            snapshotObserver.clearInvalidObservations()
            needClearObservations = false
        }
    }
    override var contentSize = IntSize.Zero
        private set

    override fun measureAndLayout(sendPointerUpdate: Boolean) {
        measureAndLayoutDelegate.updateRootConstraints(constraints)
        val rootNodeResized = measureAndLayoutDelegate.measureAndLayout {
            if (sendPointerUpdate) {
                inputHandler.onPointerUpdate()
            }
        }
        if (rootNodeResized) {
            snapshotInvalidationTracker.requestDraw()
        }
        measureAndLayoutDelegate.dispatchOnPositionedCallbacks()
        contentSize = computeContentSize()
    }

    override fun measureAndLayout(layoutNode: LayoutNode, constraints: Constraints) {
        measureAndLayoutDelegate.measureAndLayout(layoutNode, constraints)
        inputHandler.onPointerUpdate()
        measureAndLayoutDelegate.dispatchOnPositionedCallbacks()
        contentSize = computeContentSize()
    }

    override fun measureAndLayout() {
        measureAndLayout(sendPointerUpdate = true)
    }

    override fun measureAndLayoutForTest() {
        measureAndLayout(sendPointerUpdate = true)
    }


    // Don't use mainOwner.root.width here, as it strictly coerced by [constraints]
    private fun computeContentSize() = IntSize(
        root.children.maxOfOrNull { it.outerCoordinator.measuredWidth } ?: 0,
        root.children.maxOfOrNull { it.outerCoordinator.measuredHeight } ?: 0,
    )

    override fun forceMeasureTheSubtree(layoutNode: LayoutNode, affectsLookahead: Boolean) {
        measureAndLayoutDelegate.forceMeasureTheSubtree(layoutNode, affectsLookahead)
    }

    override fun onRequestMeasure(
        layoutNode: LayoutNode,
        affectsLookahead: Boolean,
        forceRequest: Boolean,
        scheduleMeasureAndLayout: Boolean
    ) {
        if (affectsLookahead) {
            if (measureAndLayoutDelegate.requestLookaheadRemeasure(layoutNode, forceRequest) &&
                scheduleMeasureAndLayout
            ) {
                snapshotInvalidationTracker.requestLayout()
            }
        } else if (measureAndLayoutDelegate.requestRemeasure(layoutNode, forceRequest) &&
            scheduleMeasureAndLayout
        ) {
            snapshotInvalidationTracker.requestLayout()
        }
    }

    override fun onRequestRelayout(
        layoutNode: LayoutNode,
        affectsLookahead: Boolean,
        forceRequest: Boolean
    ) {
        this.onRequestMeasure(layoutNode, affectsLookahead, forceRequest, scheduleMeasureAndLayout = true)
    }

    override fun requestOnPositionedCallback(layoutNode: LayoutNode) {
        measureAndLayoutDelegate.requestOnPositionedCallback(layoutNode)
        snapshotInvalidationTracker.requestLayout()
    }

    override fun createLayer(
        drawBlock: (Canvas) -> Unit,
        invalidateParentLayer: () -> Unit
    ) = RenderNodeLayer(
        density,
        invalidateParentLayer = {
            invalidateParentLayer()
            snapshotInvalidationTracker.requestDraw()
        },
        drawBlock = drawBlock,
        onDestroy = { needClearObservations = true }
    )

    override fun onSemanticsChange() {
        accessibilityController.onSemanticsChange()
    }

    override fun onLayoutChange(layoutNode: LayoutNode) {
        accessibilityController.onLayoutChange(layoutNode)
    }

    override fun getFocusDirection(keyEvent: KeyEvent): FocusDirection? {
        return when (keyEvent.key) {
            Key.Tab -> if (keyEvent.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
            Key.DirectionCenter -> FocusDirection.In
            Key.Back -> FocusDirection.Out
            else -> null
        }
    }

    override fun calculatePositionInWindow(localPosition: Offset): Offset = localPosition

    override fun calculateLocalPosition(positionInWindow: Offset): Offset = positionInWindow

    override fun draw(canvas: Canvas) {
        root.draw(canvas)
        clearInvalidObservations()
    }

    override fun setRootModifier(modifier: Modifier) {
        root.modifier = EmptySemanticsElement
            .then(focusOwner.modifier)
            .then(keyInputModifier)
            .then(modifier)
    }

    override fun createComposition(parent: CompositionContext): Composition {
        return Composition(DefaultUiApplier(root), parent)
    }

    /**
     * Handles the input initiated by tests.
     */
    override fun sendPointerEvent(
        eventType: PointerEventType,
        position: Offset,
        scrollDelta: Offset,
        timeMillis: Long,
        type: PointerType,
        buttons: PointerButtons?,
        keyboardModifiers: PointerKeyboardModifiers?,
        nativeEvent: Any?,
        button: PointerButton?
    ) {
        snapshotInvalidationTracker.onLayout()
        measureAndLayout()
        inputHandler.onLayout()

        inputHandler.onPointerEvent(
            eventType = eventType,
            position = position,
            scrollDelta = scrollDelta,
            timeMillis = timeMillis,
            type = type,
            buttons = buttons,
            keyboardModifiers = keyboardModifiers,
            nativeEvent = nativeEvent,
            button = button
        )
    }

    /**
     * Handles the input initiated by tests.
     */
    override fun sendPointerEvent(
        eventType: PointerEventType,
        pointers: List<ComposeScene.Pointer>,
        buttons: PointerButtons,
        keyboardModifiers: PointerKeyboardModifiers,
        scrollDelta: Offset,
        timeMillis: Long,
        nativeEvent: Any?,
        button: PointerButton?,
    ) {
        snapshotInvalidationTracker.onLayout()
        measureAndLayout()
        inputHandler.onLayout()

        inputHandler.onPointerEvent(
            eventType = eventType,
            pointers = pointers,
            buttons = buttons,
            keyboardModifiers = keyboardModifiers,
            scrollDelta = scrollDelta,
            timeMillis = timeMillis,
            nativeEvent = nativeEvent,
            button = button
        )
    }

    /**
     * Handles the input initiated by tests or accessibility.
     */
    override fun sendKeyEvent(keyEvent: KeyEvent): Boolean {
        return inputHandler.onKeyEvent(keyEvent)
    }

    override fun onPointerInput(event: PointerInputEvent) {
        if (event.button != null) {
            inputModeManager.requestInputMode(InputMode.Touch)
        }
        val isInBounds = event.eventType != PointerEventType.Exit && event.pointers.all {
            bounds.contains(it.position.round())
        }
        pointerInputEventProcessor.process(
            event,
            IdentityPositionCalculator,
            isInBounds = isInBounds
        )
    }

    override fun onKeyEvent(keyEvent: KeyEvent): Boolean {
        return focusOwner.dispatchKeyEvent(keyEvent)
    }

    override fun hitTestInteropView(position: Offset): Boolean {
        val result = HitTestResult()
        pointerInputEventProcessor.root.hitTest(position, result, true)
        val last = result.lastOrNull()
        return (last as? BackwardsCompatNode)?.element is InteropViewCatchPointerModifier
    }

    override fun onEndApplyChanges() {
        clearInvalidObservations()

        // Listeners can add more items to the list and we want to ensure that they
        // are executed after being added, so loop until the list is empty
        while (endApplyChangesListeners.isNotEmpty()) {
            val size = endApplyChangesListeners.size
            for (i in 0 until size) {
                val listener = endApplyChangesListeners[i]
                // null out the item so that if the listener is re-added then we execute it again.
                endApplyChangesListeners[i] = null
                listener?.invoke()
            }
            // Remove all the items that were visited. Removing items shifts all items after
            // to the front of the list, so removing in a chunk is cheaper than removing one-by-one
            endApplyChangesListeners.removeRange(0, size)
        }
    }

    override fun registerOnEndApplyChangesListener(listener: () -> Unit) {
        if (listener !in endApplyChangesListeners) {
            endApplyChangesListeners += listener
        }
    }

    override fun registerOnLayoutCompletedListener(listener: Owner.OnLayoutCompletedListener) {
        measureAndLayoutDelegate.registerOnLayoutCompletedListener(listener)
        snapshotInvalidationTracker.requestLayout()
    }

    override val pointerIconService = object : PointerIconService {
        private var desiredPointerIcon: PointerIcon? = null

        override fun getIcon(): PointerIcon {
            return desiredPointerIcon ?: PointerIcon.Default
        }

        override fun setIcon(value: PointerIcon?) {
            desiredPointerIcon = value
            platform.setPointerIcon(desiredPointerIcon ?: PointerIcon.Default)
        }
    }
}

internal val Constraints.maxSize get() =
    IntSize(maxWidth, maxHeight)

private object IdentityPositionCalculator: PositionCalculator {
    override fun screenToLocal(positionOnScreen: Offset): Offset = positionOnScreen
    override fun localToScreen(localPosition: Offset): Offset = localPosition
}
