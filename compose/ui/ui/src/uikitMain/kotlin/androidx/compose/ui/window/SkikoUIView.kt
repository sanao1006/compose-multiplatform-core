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

import androidx.compose.ui.interop.UIKitInteropTransaction
import androidx.compose.ui.platform.TextActions
import androidx.compose.ui.window.di.KeyboardEventHandler
import androidx.compose.ui.window.di.SkikoUIViewDelegate
import kotlinx.cinterop.*
import org.jetbrains.skia.Rect
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.QuartzCore.CAMetalLayer
import platform.UIKit.*
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkikoInputModifiers
import org.jetbrains.skiko.SkikoKey
import org.jetbrains.skiko.SkikoKeyboardEvent
import org.jetbrains.skiko.SkikoKeyboardEventKind

internal enum class UITouchesEventPhase {
    BEGAN, MOVED, ENDED, CANCELLED
}

@Suppress("CONFLICTING_OVERLOADS")
@ExportObjCClass
internal class SkikoUIView(
    private val delegate: SkikoUIViewDelegate,
    private val keyboardEventHandler: KeyboardEventHandler,
) : UIView(frame = CGRectZero.readValue()) {
    companion object : UIViewMeta() {
        override fun layerClass() = CAMetalLayer
    }

//    @Suppress("UNUSED") // required for Objective-C
//    @OverrideInit
//    constructor(coder: NSCoder) : super(coder) {
//        throw UnsupportedOperationException("init(coder: NSCoder) is not supported for SkikoUIView")
//    }

    private val _device: MTLDeviceProtocol =
        MTLCreateSystemDefaultDevice() ?: throw IllegalStateException("Metal is not supported on this system")
    private val _metalLayer: CAMetalLayer get() = layer as CAMetalLayer
    private var _inputDelegate: UITextInputDelegateProtocol? = null
    private var _currentTextMenuActions: TextActions? = null
    private val _redrawer: MetalRedrawer = MetalRedrawer(
        _metalLayer,
        callbacks = object : MetalRedrawerCallbacks {
            override fun render(canvas: Canvas, targetTimestamp: NSTimeInterval) {
                delegate?.render(canvas, targetTimestamp)
            }

            override fun retrieveInteropTransaction(): UIKitInteropTransaction =
                delegate?.retrieveInteropTransaction() ?: UIKitInteropTransaction.empty

        }
    )

    /*
     * When there at least one tracked touch, we need notify redrawer about it. It should schedule CADisplayLink which
     * affects frequency of polling UITouch events on high frequency display and forces it to match display refresh rate.
     */
    private var _touchesCount = 0
        set(value) {
            field = value

            val needHighFrequencyPolling = value > 0

            _redrawer.needsProactiveDisplayLink = needHighFrequencyPolling
        }

    init {
        multipleTouchEnabled = true

        _metalLayer.also {
            // Workaround for KN compiler bug
            // Type mismatch: inferred type is platform.Metal.MTLDeviceProtocol but objcnames.protocols.MTLDeviceProtocol? was expected
            @Suppress("USELESS_CAST")
            it.device = _device as objcnames.protocols.MTLDeviceProtocol?

            it.pixelFormat = MTLPixelFormatBGRA8Unorm
            doubleArrayOf(0.0, 0.0, 0.0, 0.0).usePinned { pinned ->
                it.backgroundColor = CGColorCreate(CGColorSpaceCreateDeviceRGB(), pinned.addressOf(0))
            }
            it.setOpaque(true)
            it.framebufferOnly = false
        }
    }

    fun needRedraw() = _redrawer.needRedraw()

    var isForcedToPresentWithTransactionEveryFrame by _redrawer::isForcedToPresentWithTransactionEveryFrame

    /**
     * Show copy/paste text menu
     * @param targetRect - rectangle of selected text area
     * @param textActions - available (not null) actions in text menu
     */
    fun showTextMenu(targetRect: Rect, textActions: TextActions) {
        _currentTextMenuActions = textActions
        val menu: UIMenuController = UIMenuController.sharedMenuController()
        val cgRect = CGRectMake(
            x = targetRect.left.toDouble(),
            y = targetRect.top.toDouble(),
            width = targetRect.width.toDouble(),
            height = targetRect.height.toDouble()
        )
        val isTargetVisible = CGRectIntersectsRect(bounds, cgRect)
        if (isTargetVisible) {
            if (menu.isMenuVisible()) {
                menu.setTargetRect(cgRect, this)
            } else {
                menu.showMenuFromView(this, cgRect)
            }
        } else {
            if (menu.isMenuVisible()) {
                menu.hideMenu()
            }
        }
    }

    fun hideTextMenu() {
        _currentTextMenuActions = null
        val menu: UIMenuController = UIMenuController.sharedMenuController()
        menu.hideMenu()
    }

    fun isTextMenuShown(): Boolean {
        return _currentTextMenuActions != null
    }

    override fun copy(sender: Any?) {
        _currentTextMenuActions?.copy?.invoke()
    }

    override fun paste(sender: Any?) {
        _currentTextMenuActions?.paste?.invoke()
    }

    override fun cut(sender: Any?) {
        _currentTextMenuActions?.cut?.invoke()
    }

    override fun selectAll(sender: Any?) {
        _currentTextMenuActions?.selectAll?.invoke()
    }

    fun dispose() {
        _redrawer.dispose()
        removeFromSuperview()
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()

        window?.screen?.let {
            contentScaleFactor = it.scale
            _redrawer.maximumFramesPerSecond = it.maximumFramesPerSecond
        }
        if (window != null) {
            delegate?.onAttachedToWindow()
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()

        val scaledSize = bounds.useContents {
            CGSizeMake(size.width * contentScaleFactor, size.height * contentScaleFactor)
        }

        // If drawableSize is zero in any dimension it means that it's a first layout
        // we need to synchronously dispatch first draw and block until it's presented
        // so user doesn't have a flicker
        val needsSynchronousDraw = _metalLayer.drawableSize.useContents {
            width == 0.0 || height == 0.0
        }

        _metalLayer.drawableSize = scaledSize

        if (needsSynchronousDraw) {
            _redrawer.drawSynchronously()
        }
    }

    override fun canBecomeFirstResponder() = true

    override fun pressesBegan(presses: Set<*>, withEvent: UIPressesEvent?) {//todo duplicated
        if (withEvent != null) {
            for (press in withEvent.allPresses) {
                val uiPress = press as? UIPress
                if (uiPress != null) {
                    keyboardEventHandler.onKeyboardEvent(
                        toSkikoKeyboardEvent(press, SkikoKeyboardEventKind.DOWN)
                    )
                }
            }
        }
        super.pressesBegan(presses, withEvent)
    }

    override fun pressesEnded(presses: Set<*>, withEvent: UIPressesEvent?) {//todo duplicated
        if (withEvent != null) {
            for (press in withEvent.allPresses) {
                val uiPress = press as? UIPress
                if (uiPress != null) {
                    keyboardEventHandler.onKeyboardEvent(
                        toSkikoKeyboardEvent(press, SkikoKeyboardEventKind.UP)
                    )
                }
            }
        }
        super.pressesEnded(presses, withEvent)
    }

    /**
     * https://developer.apple.com/documentation/uikit/uiview/1622533-point
     */
    override fun pointInside(point: CValue<CGPoint>, withEvent: UIEvent?): Boolean =
        delegate?.pointInside(point, withEvent) ?: super.pointInside(point, withEvent)


    override fun touchesBegan(touches: Set<*>, withEvent: UIEvent?) {
        super.touchesBegan(touches, withEvent)

        _touchesCount += touches.size

        withEvent?.let { event ->
            delegate?.onTouchesEvent(this, event, UITouchesEventPhase.BEGAN)
        }
    }

    override fun touchesEnded(touches: Set<*>, withEvent: UIEvent?) {
        super.touchesEnded(touches, withEvent)

        _touchesCount -= touches.size

        withEvent?.let { event ->
            delegate?.onTouchesEvent(this, event, UITouchesEventPhase.ENDED)
        }
    }

    override fun touchesMoved(touches: Set<*>, withEvent: UIEvent?) {
        super.touchesMoved(touches, withEvent)

        withEvent?.let { event ->
            delegate?.onTouchesEvent(this, event, UITouchesEventPhase.MOVED)
        }
    }

    override fun touchesCancelled(touches: Set<*>, withEvent: UIEvent?) {
        super.touchesCancelled(touches, withEvent)

        _touchesCount -= touches.size

        withEvent?.let { event ->
            delegate?.onTouchesEvent(this, event, UITouchesEventPhase.CANCELLED)
        }
    }

    override fun canPerformAction(action: COpaquePointer?, withSender: Any?): Boolean =
        when (action) {
            NSSelectorFromString(UIResponderStandardEditActionsProtocol::copy.name + ":") ->
                _currentTextMenuActions?.copy != null

            NSSelectorFromString(UIResponderStandardEditActionsProtocol::cut.name + ":") ->
                _currentTextMenuActions?.cut != null

            NSSelectorFromString(UIResponderStandardEditActionsProtocol::paste.name + ":") ->
                _currentTextMenuActions?.paste != null

            NSSelectorFromString(UIResponderStandardEditActionsProtocol::selectAll.name + ":") ->
                _currentTextMenuActions?.selectAll != null

            else -> false
        }

//    /**
//     * Call when something changes in text data
//     */
//    fun textWillChange() {
//        _inputDelegate?.textWillChange(this)
//    }
//
//    /**
//     * Call when something changes in text data
//     */
//    fun textDidChange() {
//        _inputDelegate?.textDidChange(this)
//    }
//
//    /**
//     * Call when something changes in text data
//     */
//    fun selectionWillChange() {
//        _inputDelegate?.selectionWillChange(this)
//    }
//
//    /**
//     * Call when something changes in text data
//     */
//    fun selectionDidChange() {
//        _inputDelegate?.selectionDidChange(this)
//    }
}

private fun NSWritingDirection.directionToStr() =
    when (this) {
        UITextLayoutDirectionLeft -> "Left"
        UITextLayoutDirectionRight -> "Right"
        UITextLayoutDirectionUp -> "Up"
        UITextLayoutDirectionDown -> "Down"
        else -> "unknown direction"
    }

private fun toSkikoKeyboardEvent(
    event: UIPress,
    kind: SkikoKeyboardEventKind
): SkikoKeyboardEvent {
    val timestamp = (event.timestamp * 1_000).toLong()
    return SkikoKeyboardEvent(
        SkikoKey.valueOf(event.key!!.keyCode),
        toSkikoModifiers(event),
        kind,
        timestamp,
        event
    )
}

internal fun toSkikoModifiers(event: UIPress): SkikoInputModifiers { //todo move?
    var result = 0
    val modifiers = event.key!!.modifierFlags
    if (modifiers and UIKeyModifierAlternate != 0L) {
        result = result.or(SkikoInputModifiers.ALT.value)
    }
    if (modifiers and UIKeyModifierShift != 0L) {
        result = result.or(SkikoInputModifiers.SHIFT.value)
    }
    if (modifiers and UIKeyModifierControl != 0L) {
        result = result.or(SkikoInputModifiers.CONTROL.value)
    }
    if (modifiers and UIKeyModifierCommand != 0L) {
        result = result.or(SkikoInputModifiers.META.value)
    }
    return SkikoInputModifiers(result)
}
