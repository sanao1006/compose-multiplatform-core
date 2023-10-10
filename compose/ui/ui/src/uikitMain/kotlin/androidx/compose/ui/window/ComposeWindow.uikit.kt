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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.input.pointer.HistoricalChange
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.toCompose
import androidx.compose.ui.interop.LocalLayerContainer
import androidx.compose.ui.interop.LocalUIKitInteropContext
import androidx.compose.ui.interop.LocalUIViewController
import androidx.compose.ui.interop.UIKitInteropContext
import androidx.compose.ui.interop.UIKitInteropTransaction
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.uikit.*
import androidx.compose.ui.unit.*
import kotlin.math.absoluteValue
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.window.new.*
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.roundToLong
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.BreakIterator
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.OSVersion
import org.jetbrains.skiko.SkikoKey
import org.jetbrains.skiko.SkikoKeyboardEvent
import org.jetbrains.skiko.SkikoKeyboardEventKind
import org.jetbrains.skiko.SkikoPointerEvent
import org.jetbrains.skiko.currentNanoTime
import platform.CoreGraphics.CGPoint
import org.jetbrains.skiko.available
import platform.CoreGraphics.CGAffineTransformIdentity
import platform.CoreGraphics.CGAffineTransformInvert
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeEqualToSize
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private val uiContentSizeCategoryToFontScaleMap = mapOf(
    UIContentSizeCategoryExtraSmall to 0.8f,
    UIContentSizeCategorySmall to 0.85f,
    UIContentSizeCategoryMedium to 0.9f,
    UIContentSizeCategoryLarge to 1f, // default preference
    UIContentSizeCategoryExtraLarge to 1.1f,
    UIContentSizeCategoryExtraExtraLarge to 1.2f,
    UIContentSizeCategoryExtraExtraExtraLarge to 1.3f,

    // These values don't work well if they match scale shown by
    // Text Size control hint, because iOS uses non-linear scaling
    // calculated by UIFontMetrics, while Compose uses linear.
    UIContentSizeCategoryAccessibilityMedium to 1.4f, // 160% native
    UIContentSizeCategoryAccessibilityLarge to 1.5f, // 190% native
    UIContentSizeCategoryAccessibilityExtraLarge to 1.6f, // 235% native
    UIContentSizeCategoryAccessibilityExtraExtraLarge to 1.7f, // 275% native
    UIContentSizeCategoryAccessibilityExtraExtraExtraLarge to 1.8f, // 310% native

    // UIContentSizeCategoryUnspecified
)

fun ComposeUIViewController(content: @Composable () -> Unit): UIViewController =
    ComposeUIViewController(configure = {}, content = content)

fun ComposeUIViewController(
    configure: ComposeUIViewControllerConfiguration.() -> Unit = {},
    content: @Composable () -> Unit
): UIViewController {
    var composeWindow: ComposeWindow? = null
    val configuration = ComposeUIViewControllerConfiguration().apply(configure)
    return ComposeWindow(
        configuration = configuration,
        content = content,
        keyboardVisibilityListener = KeyboardVisibilityListenerImpl(
            viewProvider = { composeWindow!!.view },
            configuration = configuration,
            attachedComposeContextProvider = { composeWindow!!.attachedComposeContext },
            densityProvider = { composeWindow!!.density }
        ),
    ).also {
        composeWindow = it
    }
}

internal class AttachedComposeContext(
    val scene: ComposeScene,
    val view: SkikoUIView,
    val interopContext: UIKitInteropContext
) {
    private var constraints: List<NSLayoutConstraint> = emptyList()
        set(value) {
            if (field.isNotEmpty()) {
                NSLayoutConstraint.deactivateConstraints(field)
            }
            field = value
            NSLayoutConstraint.activateConstraints(value)
        }

    fun setConstraintsToCenterInView(parentView: UIView, size: CValue<CGSize>) {
        size.useContents {
            constraints = listOf(
                view.centerXAnchor.constraintEqualToAnchor(parentView.centerXAnchor),
                view.centerYAnchor.constraintEqualToAnchor(parentView.centerYAnchor),
                view.widthAnchor.constraintEqualToConstant(width),
                view.heightAnchor.constraintEqualToConstant(height)
            )
        }
    }

    fun setConstraintsToFillView(parentView: UIView) {
        constraints = listOf(
            view.leftAnchor.constraintEqualToAnchor(parentView.leftAnchor),
            view.rightAnchor.constraintEqualToAnchor(parentView.rightAnchor),
            view.topAnchor.constraintEqualToAnchor(parentView.topAnchor),
            view.bottomAnchor.constraintEqualToAnchor(parentView.bottomAnchor)
        )
    }

    fun dispose() {
        scene.close()
        // After scene is disposed all UIKit interop actions can't be deferred to be synchronized with rendering
        // Thus they need to be executed now.
        interopContext.retrieve().actions.forEach { it.invoke() }
        view.dispose()
    }
}

@OptIn(InternalComposeApi::class)
@ExportObjCClass
private class ComposeWindow(
    private val configuration: ComposeUIViewControllerConfiguration,
    private val content: @Composable () -> Unit,
    private val keyboardVisibilityListener: KeyboardVisibilityListener,
) : UIViewController(nibName = null, bundle = null) {

    private var isInsideSwiftUI = false
    private var safeAreaState by mutableStateOf(PlatformInsets())
    private var layoutMarginsState by mutableStateOf(PlatformInsets())

    /*
     * Initial value is arbitarily chosen to avoid propagating invalid value logic
     * It's never the case in real usage scenario to reflect that in type system
     */
    private val interfaceOrientationState = mutableStateOf(
        InterfaceOrientation.Portrait
    )

    private val systemTheme = mutableStateOf(
        traitCollection.userInterfaceStyle.asComposeSystemTheme()
    )

    /*
     * On iOS >= 13.0 interfaceOrientation will be deduced from [UIWindowScene] of [UIWindow]
     * to which our [ComposeWindow] is attached.
     * It's never UIInterfaceOrientationUnknown, if accessed after owning [UIWindow] was made key and visible:
     * https://developer.apple.com/documentation/uikit/uiwindow/1621601-makekeyandvisible?language=objc
     */
    private val currentInterfaceOrientation: InterfaceOrientation?
        get() {
            // Modern: https://developer.apple.com/documentation/uikit/uiwindowscene/3198088-interfaceorientation?language=objc
            // Deprecated: https://developer.apple.com/documentation/uikit/uiapplication/1623026-statusbarorientation?language=objc
            return if (available(OS.Ios to OSVersion(13))) {
                view.window?.windowScene?.interfaceOrientation?.let {
                    InterfaceOrientation.getByRawValue(it)
                }
            } else {
                InterfaceOrientation.getByRawValue(UIApplication.sharedApplication.statusBarOrientation)
            }
        }

    private val _windowInfo = WindowInfoImpl().apply {
        isWindowFocused = true
    }

    private val fontScale: Float
        get() {
            val contentSizeCategory =
                traitCollection.preferredContentSizeCategory ?: UIContentSizeCategoryUnspecified

            return uiContentSizeCategoryToFontScaleMap[contentSizeCategory] ?: 1.0f
        }

    val density: Density
        get() = Density(
            attachedComposeContext?.view?.contentScaleFactor?.toFloat() ?: 1f,
            fontScale
        )

    var attachedComposeContext: AttachedComposeContext? = null

    private val nativeKeyboardVisibilityListener = object : NSObject() {
        @Suppress("unused")
        @ObjCAction
        fun keyboardWillShow(arg: NSNotification) {
            keyboardVisibilityListener.keyboardWillShow(arg)
        }

        @Suppress("unused")
        @ObjCAction
        fun keyboardWillHide(arg: NSNotification) {
            keyboardVisibilityListener.keyboardWillHide(arg)
        }
    }

    @Suppress("unused")
    @ObjCAction
    fun viewSafeAreaInsetsDidChange() {
        // super.viewSafeAreaInsetsDidChange() // TODO: call super after Kotlin 1.8.20
        view.safeAreaInsets.useContents {
            safeAreaState = PlatformInsets(
                left = left.dp,
                top = top.dp,
                right = right.dp,
                bottom = bottom.dp,
            )
        }
        view.directionalLayoutMargins.useContents {
            layoutMarginsState = PlatformInsets(
                left = leading.dp, // TODO: Check RTL support
                top = top.dp,
                right = trailing.dp, // TODO: Check RTL support
                bottom = bottom.dp,
            )
        }
    }

    override fun loadView() {
        view = UIView().apply {
            backgroundColor = UIColor.whiteColor
            setClipsToBounds(true)
        } // rootView needs to interop with UIKit
    }

    override fun viewDidLoad() {
        super.viewDidLoad()

        PlistSanityCheck.performIfNeeded()

        configuration.delegate.viewDidLoad()
    }

    override fun traitCollectionDidChange(previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)

        systemTheme.value = traitCollection.userInterfaceStyle.asComposeSystemTheme()
    }

    override fun viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()

        // UIKit possesses all required info for layout at this point
        currentInterfaceOrientation?.let {
            interfaceOrientationState.value = it
        }

        attachedComposeContext?.let {
            updateLayout(it)
        }
    }

    private fun updateLayout(context: AttachedComposeContext) {
        val scale = density.density
        val size = view.frame.useContents {
            IntSize(
                width = (size.width * scale).roundToInt(),
                height = (size.height * scale).roundToInt()
            )
        }
        _windowInfo.containerSize = size
        context.scene.density = density
        context.scene.constraints = Constraints(
            maxWidth = size.width,
            maxHeight = size.height
        )

        context.view.needRedraw()
    }

    override fun viewWillTransitionToSize(
        size: CValue<CGSize>,
        withTransitionCoordinator: UIViewControllerTransitionCoordinatorProtocol
    ) {
        super.viewWillTransitionToSize(size, withTransitionCoordinator)

        if (isInsideSwiftUI || presentingViewController != null) {
            // SwiftUI will do full layout and scene constraints update on each frame of orientation change animation
            // This logic is not needed

            // When presented modally, UIKit performs non-trivial hierarchy update durting orientation change,
            // its logic is not feasible to integrate into
            return
        }

        val attachedComposeContext = attachedComposeContext ?: return

        // Happens during orientation change from LandscapeLeft to LandscapeRight, for example
        val isSameSizeTransition = view.frame.useContents {
            CGSizeEqualToSize(size, this.size.readValue())
        }
        if (isSameSizeTransition) {
            return
        }

        val startSnapshotView =
            attachedComposeContext.view.snapshotViewAfterScreenUpdates(false) ?: return

        startSnapshotView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(startSnapshotView)
        size.useContents {
            NSLayoutConstraint.activateConstraints(
                listOf(
                    startSnapshotView.widthAnchor.constraintEqualToConstant(height),
                    startSnapshotView.heightAnchor.constraintEqualToConstant(width),
                    startSnapshotView.centerXAnchor.constraintEqualToAnchor(view.centerXAnchor),
                    startSnapshotView.centerYAnchor.constraintEqualToAnchor(view.centerYAnchor)
                )
            )
        }

        attachedComposeContext.view.isForcedToPresentWithTransactionEveryFrame = true

        attachedComposeContext.setConstraintsToCenterInView(view, size)
        attachedComposeContext.view.transform = withTransitionCoordinator.targetTransform

        view.layoutIfNeeded()

        withTransitionCoordinator.animateAlongsideTransition(
            animation = {
                startSnapshotView.alpha = 0.0
                startSnapshotView.transform =
                    CGAffineTransformInvert(withTransitionCoordinator.targetTransform)
                attachedComposeContext.view.transform = CGAffineTransformIdentity.readValue()
            },
            completion = {
                startSnapshotView.removeFromSuperview()
                attachedComposeContext.setConstraintsToFillView(view)
                attachedComposeContext.view.isForcedToPresentWithTransactionEveryFrame = false
            }
        )
    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)

        isInsideSwiftUI = checkIfInsideSwiftUI()
        attachComposeIfNeeded()
        configuration.delegate.viewWillAppear(animated)
    }

    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)

        NSNotificationCenter.defaultCenter.addObserver(
            observer = nativeKeyboardVisibilityListener,
            selector = NSSelectorFromString(nativeKeyboardVisibilityListener::keyboardWillShow.name + ":"),
            name = UIKeyboardWillShowNotification,
            `object` = null
        )
        NSNotificationCenter.defaultCenter.addObserver(
            observer = nativeKeyboardVisibilityListener,
            selector = NSSelectorFromString(nativeKeyboardVisibilityListener::keyboardWillHide.name + ":"),
            name = UIKeyboardWillHideNotification,
            `object` = null
        )

        configuration.delegate.viewDidAppear(animated)

    }

    // viewDidUnload() is deprecated and not called.
    override fun viewWillDisappear(animated: Boolean) {
        super.viewWillDisappear(animated)

        NSNotificationCenter.defaultCenter.removeObserver(
            observer = keyboardVisibilityListener,
            name = UIKeyboardWillShowNotification,
            `object` = null
        )
        NSNotificationCenter.defaultCenter.removeObserver(
            observer = keyboardVisibilityListener,
            name = UIKeyboardWillHideNotification,
            `object` = null
        )

        configuration.delegate.viewWillDisappear(animated)
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)

        dispose()

        dispatch_async(dispatch_get_main_queue()) {
            kotlin.native.internal.GC.collect()
        }

        configuration.delegate.viewDidDisappear(animated)
    }

    override fun didReceiveMemoryWarning() {
        println("didReceiveMemoryWarning")
        kotlin.native.internal.GC.collect()
        super.didReceiveMemoryWarning()
    }

    private fun dispose() {
        attachedComposeContext?.dispose()
        attachedComposeContext = null
    }

    private var _textUIView: IntermediateTextInputUIView? = null
    private lateinit var skikoUIViewDelegate: SkikoUIViewDelegate

    private fun attachComposeIfNeeded() {
        if (attachedComposeContext != null) {
            return // already attached
        }

        val skikoUIView = SkikoUIView()

        val interopContext = UIKitInteropContext(requestRedraw = skikoUIView::needRedraw)

        skikoUIView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(skikoUIView)

        val inputServices = object : PlatformTextInputService {
            private var currentInput: CurrentInput? = null
            private var currentImeOptions: ImeOptions? = null
            private var currentImeActionHandler: ((ImeAction) -> Unit)? = null

            /**
             * Workaround to prevent calling textWillChange, textDidChange, selectionWillChange, and
             * selectionDidChange when the value of the current input is changed by the system (i.e., by the user
             * input) not by the state change of the Compose side. These 4 functions call methods of
             * UITextInputDelegateProtocol, which notifies the system that the text or the selection of the
             * current input has changed.
             *
             * This is to properly handle multi-stage input methods that depend on text selection, required by
             * languages such as Korean (Chinese and Japanese input methods depend on text marking). The writing
             * system of these languages contains letters that can be broken into multiple parts, and each keyboard
             * key corresponds to those parts. Therefore, the input system holds an internal state to combine these
             * parts correctly. However, the methods of UITextInputDelegateProtocol reset this state, resulting in
             * incorrect input. (e.g., 컴포즈 becomes ㅋㅓㅁㅍㅗㅈㅡ when not handled properly)
             *
             * @see _tempCurrentInputSession holds the same text and selection of the current input. It is used
             * instead of the old value passed to updateState. When the current value change is due to the
             * user input, updateState is not effective because _tempCurrentInputSession holds the same value.
             * However, when the current value change is due to the change of the user selection or to the
             * state change in the Compose side, updateState calls the 4 methods because the new value holds
             * these changes.
             */
            private var _tempCurrentInputSession: EditProcessor? = null

            /**
             * Workaround to prevent IME action from being called multiple times with hardware keyboards.
             * When the hardware return key is held down, iOS sends multiple newline characters to the application,
             * which makes UIKitTextInputService call the current IME action multiple times without an additional
             * debouncing logic.
             *
             * @see _tempHardwareReturnKeyPressed is set to true when the return key is pressed with a
             * hardware keyboard.
             * @see _tempImeActionIsCalledWithHardwareReturnKey is set to true when the
             * current IME action has been called within the current hardware return key press.
             */
            private var _tempHardwareReturnKeyPressed: Boolean = false
            private var _tempImeActionIsCalledWithHardwareReturnKey: Boolean = false

            /**
             * Workaround to fix voice dictation.
             * UIKit call insertText(text) and replaceRange(range,text) immediately,
             * but Compose recomposition happen on next draw frame.
             * So the value of getSelectedTextRange is in the old state when the replaceRange function is called.
             * @see _tempCursorPos helps to fix this behaviour. Permanently update _tempCursorPos in function insertText.
             * And after clear in updateState function.
             */
            private var _tempCursorPos: Int? = null

            override fun startInput(
                value: TextFieldValue,
                imeOptions: ImeOptions,
                onEditCommand: (List<EditCommand>) -> Unit,
                onImeActionPerformed: (ImeAction) -> Unit
            ) {
                currentInput = CurrentInput(value, onEditCommand)
                _tempCurrentInputSession = EditProcessor().apply {
                    reset(value, null)
                }
                currentImeOptions = imeOptions
                currentImeActionHandler = onImeActionPerformed

                _textUIView = IntermediateTextInputUIView().also {
                    skikoUIView.addSubview(it)
                }
                _textUIView?.input = object : IOSSkikoInput {

                    /**
                     * A Boolean value that indicates whether the text-entry object has any text.
                     * https://developer.apple.com/documentation/uikit/uikeyinput/1614457-hastext
                     */
                    override fun hasText(): Boolean = getState()?.text?.isNotEmpty() ?: false

                    /**
                     * Inserts a character into the displayed text.
                     * Add the character text to your class’s backing store at the index corresponding to the cursor and redisplay the text.
                     * https://developer.apple.com/documentation/uikit/uikeyinput/1614543-inserttext
                     * @param text A string object representing the character typed on the system keyboard.
                     */
                    override fun insertText(text: String) {
                        if (text == "\n") {
                            if (runImeActionIfRequired()) {
                                return
                            }
                        }
                        getCursorPos()?.let {
                            _tempCursorPos = it + text.length
                        }
                        sendEditCommand(CommitTextCommand(text, 1))
                    }

                    /**
                     * Deletes a character from the displayed text.
                     * Remove the character just before the cursor from your class’s backing store and redisplay the text.
                     * https://developer.apple.com/documentation/uikit/uikeyinput/1614572-deletebackward
                     */
                    override fun deleteBackward() {
                        // Before this function calls, iOS changes selection in setSelectedTextRange.
                        // All needed characters should be allready selected, and we can just remove them.
                        sendEditCommand(
                            CommitTextCommand("", 0)
                        )
                    }

                    /**
                     * The text position for the end of a document.
                     * https://developer.apple.com/documentation/uikit/uitextinput/1614555-endofdocument
                     */
                    override fun endOfDocument(): Long = getState()?.text?.length?.toLong() ?: 0L

                    /**
                     * The range of selected text in a document.
                     * If the text range has a length, it indicates the currently selected text.
                     * If it has zero length, it indicates the caret (insertion point).
                     * If the text-range object is nil, it indicates that there is no current selection.
                     * https://developer.apple.com/documentation/uikit/uitextinput/1614541-selectedtextrange
                     */
                    override fun getSelectedTextRange(): IntRange? {
                        val cursorPos = getCursorPos()
                        if (cursorPos != null) {
                            return cursorPos until cursorPos
                        }
                        val selection = getState()?.selection
                        return if (selection != null) {
                            selection.start until selection.end
                        } else {
                            null
                        }
                    }

                    override fun setSelectedTextRange(range: IntRange?) {
                        if (range != null) {
                            sendEditCommand(
                                SetSelectionCommand(range.start, range.endInclusive + 1)
                            )
                        } else {
                            sendEditCommand(
                                SetSelectionCommand(endOfDocument().toInt(), endOfDocument().toInt())
                            )
                        }
                    }

                    override fun selectAll() {
                        sendEditCommand(
                            SetSelectionCommand(0, endOfDocument().toInt())
                        )
                    }

                    /**
                     * Returns the text in the specified range.
                     * https://developer.apple.com/documentation/uikit/uitextinput/1614527-text
                     * @param range A range of text in a document.
                     * @return A substring of a document that falls within the specified range.
                     */
                    override fun textInRange(range: IntRange): String {
                        val text = getState()?.text
                        return text?.substring(range.first, min(range.last + 1, text.length)) ?: ""
                    }

                    /**
                     * Replaces the text in a document that is in the specified range.
                     * https://developer.apple.com/documentation/uikit/uitextinput/1614558-replace
                     * @param range A range of text in a document.
                     * @param text A string to replace the text in range.
                     */
                    override fun replaceRange(range: IntRange, text: String) {
                        sendEditCommand(
                            SetComposingRegionCommand(range.start, range.endInclusive + 1),
                            SetComposingTextCommand(text, 1),
                            FinishComposingTextCommand(),
                        )
                    }

                    /**
                     * Inserts the provided text and marks it to indicate that it is part of an active input session.
                     * Setting marked text either replaces the existing marked text or,
                     * if none is present, inserts it in place of the current selection.
                     * https://developer.apple.com/documentation/uikit/uitextinput/1614465-setmarkedtext
                     * @param markedText The text to be marked.
                     * @param selectedRange A range within markedText that indicates the current selection.
                     * This range is always relative to markedText.
                     */
                    override fun setMarkedText(markedText: String?, selectedRange: IntRange) {
                        if (markedText != null) {
                            sendEditCommand(
                                SetComposingTextCommand(markedText, 1)
                            )
                        }
                    }

                    /**
                     * The range of currently marked text in a document.
                     * If there is no marked text, the value of the property is nil.
                     * Marked text is provisionally inserted text that requires user confirmation;
                     * it occurs in multistage text input.
                     * The current selection, which can be a caret or an extended range, always occurs within the marked text.
                     * https://developer.apple.com/documentation/uikit/uitextinput/1614489-markedtextrange
                     */
                    override fun markedTextRange(): IntRange? {
                        val composition = getState()?.composition
                        return if (composition != null) {
                            composition.start until composition.end
                        } else {
                            null
                        }
                    }

                    /**
                     * Unmarks the currently marked text.
                     * After this method is called, the value of markedTextRange is nil.
                     * https://developer.apple.com/documentation/uikit/uitextinput/1614512-unmarktext
                     */
                    override fun unmarkText() {
                        sendEditCommand(FinishComposingTextCommand())
                    }

                    /**
                     * Returns the text position at a specified offset from another text position.
                     */
                    override fun positionFromPosition(position: Long, offset: Long): Long {
                        val text = getState()?.text ?: return 0

                        if (position + offset >= text.lastIndex + 1) {
                            return (text.lastIndex + 1).toLong()
                        }
                        if (position + offset <= 0) {
                            return 0
                        }
                        var resultPosition = position.toInt()
                        val iterator = BreakIterator.makeCharacterInstance()
                        iterator.setText(text)

                        repeat(offset.absoluteValue.toInt()) {
                            resultPosition = if (offset > 0) {
                                iterator.following(resultPosition)
                            } else {
                                iterator.preceding(resultPosition)
                            }
                        }

                        return resultPosition.toLong()
                    }

                    /**
                     * Return the range for the text enclosing a text position in a text unit of a given granularity in a given direction.
                     * https://developer.apple.com/documentation/uikit/uitextinputtokenizer/1614464-rangeenclosingposition?language=objc
                     * @param position
                     * A text-position object that represents a location in a document.
                     * @param granularity
                     * A constant that indicates a certain granularity of text unit.
                     * @param direction
                     * A constant that indicates a direction relative to position. The constant can be of type UITextStorageDirection or UITextLayoutDirection.
                     * @return
                     * A text-range representing a text unit of the given granularity in the given direction, or nil if there is no such enclosing unit.
                     * Whether a boundary position is enclosed depends on the given direction, using the same rule as the isPosition:withinTextUnit:inDirection: method.
                     */
                    override fun rangeEnclosingPosition(
                        position: Int,
                        withGranularity: UITextGranularity,
                        inDirection: UITextDirection
                    ): IntRange? {
                        val text = getState()?.text ?: return null
                        assert(position >= 0) { "rangeEnclosingPosition position >= 0" }

                        fun String.isMeaningless(): Boolean {
                            return when (withGranularity) {
                                UITextGranularity.UITextGranularityWord -> {
                                    this.all { it in arrayOf(' ', ',') }
                                }

                                else -> false
                            }
                        }

                        val iterator: BreakIterator =
                            when (withGranularity) {
                                UITextGranularity.UITextGranularitySentence -> BreakIterator.makeSentenceInstance()
                                UITextGranularity.UITextGranularityLine -> BreakIterator.makeLineInstance()
                                UITextGranularity.UITextGranularityWord -> BreakIterator.makeWordInstance()
                                UITextGranularity.UITextGranularityCharacter -> BreakIterator.makeCharacterInstance()
                                UITextGranularity.UITextGranularityParagraph -> TODO("UITextGranularityParagraph iterator")
                                UITextGranularity.UITextGranularityDocument -> TODO("UITextGranularityDocument iterator")
                                else -> error("Unknown granularity")
                            }
                        iterator.setText(text)

                        if (inDirection == UITextStorageDirectionForward) {
                            return null
                        } else if (inDirection == UITextStorageDirectionBackward) {
                            var current: Int = position

                            fun currentRange() = IntRange(current, position)
                            fun nextAddition() = IntRange(iterator.preceding(current).coerceAtLeast(0), current)
                            fun IntRange.text() = text.substring(start, endInclusive)

                            while (
                                current == position
                                || currentRange().text().isMeaningless()
                                || nextAddition().text().isMeaningless()
                            ) {
                                current = iterator.preceding(current)
                                if (current <= 0) {
                                    current = 0
                                    break
                                }
                            }

                            return IntRange(current, position)
                        } else {
                            error("Unknown inDirection: $inDirection")
                        }
                    }
                }
                _textUIView?.inputTraits = getSkikoUITextInputTraits(imeOptions)
                _textUIView?.delegate = skikoUIViewDelegate

                showSoftwareKeyboard()
            }

            override fun stopInput() {
                currentInput = null
                _tempCurrentInputSession = null
                currentImeOptions = null
                currentImeActionHandler = null
                hideSoftwareKeyboard()

                //stopInputCallback()
                _textUIView?.removeFromSuperview()
                _textUIView = null
            }

            override fun showSoftwareKeyboard() {
                _textUIView?.becomeFirstResponder()
            }

            override fun hideSoftwareKeyboard() {
                _textUIView?.resignFirstResponder()
            }

            override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) {
                val internalOldValue = _tempCurrentInputSession?.toTextFieldValue()
                val textChanged = internalOldValue == null || internalOldValue.text != newValue.text
                val selectionChanged = textChanged || internalOldValue == null || internalOldValue.selection != newValue.selection
                if (textChanged) {
                    _textUIView?.textWillChange()
                }
                if (selectionChanged) {
                    _textUIView?.selectionWillChange()
                }
                _tempCurrentInputSession?.reset(newValue, null)
                currentInput?.let { input ->
                    input.value = newValue
                    _tempCursorPos = null
                }
                if (textChanged) {
                    _textUIView?.textDidChange()
                }
                if (selectionChanged) {
                    _textUIView?.selectionDidChange()
                }
                if (textChanged || selectionChanged) {
                    //updateView
                    skikoUIView.setNeedsDisplay() // redraw on next frame
                    platform.QuartzCore.CATransaction.flush() // clear all animations
                    skikoUIView.reloadInputViews() // update input (like screen keyboard)
                }
            }

            fun onPreviewKeyEvent(event: KeyEvent): Boolean {
                val nativeKeyEvent = event.nativeKeyEvent
                return when (nativeKeyEvent.key) {
                    SkikoKey.KEY_ENTER -> handleEnterKey(nativeKeyEvent)
                    SkikoKey.KEY_BACKSPACE -> handleBackspace(nativeKeyEvent)
                    else -> false
                }
            }

            private fun handleEnterKey(event: NativeKeyEvent): Boolean {
                _tempImeActionIsCalledWithHardwareReturnKey = false
                return when (event.kind) {
                    SkikoKeyboardEventKind.UP -> {
                        _tempHardwareReturnKeyPressed = false
                        false
                    }

                    SkikoKeyboardEventKind.DOWN -> {
                        _tempHardwareReturnKeyPressed = true
                        // This prevents two new line characters from being added for one hardware return key press.
                        true
                    }

                    else -> false
                }
            }

            private fun handleBackspace(event: NativeKeyEvent): Boolean {
                // This prevents two characters from being removed for one hardware backspace key press.
                return event.kind == SkikoKeyboardEventKind.DOWN
            }

            private fun sendEditCommand(vararg commands: EditCommand) {
                val commandList = commands.toList()
                _tempCurrentInputSession?.apply(commandList)
                currentInput?.let { input ->
                    input.onEditCommand(commandList)
                }
            }

            private fun getCursorPos(): Int? {
                if (_tempCursorPos != null) {
                    return _tempCursorPos
                }
                val selection = getState()?.selection
                if (selection != null && selection.start == selection.end) {
                    return selection.start
                }
                return null
            }

            private fun imeActionRequired(): Boolean =
                currentImeOptions?.run {
                    singleLine || (
                        imeAction != ImeAction.None
                            && imeAction != ImeAction.Default
                            && !(imeAction == ImeAction.Search && _tempHardwareReturnKeyPressed)
                        )
                } ?: false

            private fun runImeActionIfRequired(): Boolean {
                val imeAction = currentImeOptions?.imeAction ?: return false
                val imeActionHandler = currentImeActionHandler ?: return false
                if (!imeActionRequired()) {
                    return false
                }
                if (!_tempImeActionIsCalledWithHardwareReturnKey) {
                    if (imeAction == ImeAction.Default) {
                        imeActionHandler(ImeAction.Done)
                    } else {
                        imeActionHandler(imeAction)
                    }
                }
                if (_tempHardwareReturnKeyPressed) {
                    _tempImeActionIsCalledWithHardwareReturnKey = true
                }
                return true
            }

            private fun getState(): TextFieldValue? = currentInput?.value

        }

        val platform = object : Platform by Platform.Empty {
            override val windowInfo: WindowInfo
                get() = _windowInfo
            override val textInputService: PlatformTextInputService = inputServices
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
                    skikoUIView.showTextMenu(
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
                override fun hide() = skikoUIView.hideTextMenu()

                override val status: TextToolbarStatus
                    get() = if (skikoUIView.isTextMenuShown())
                        TextToolbarStatus.Shown
                    else
                        TextToolbarStatus.Hidden
            }

            override val inputModeManager = DefaultInputModeManager(InputMode.Touch)
        }

        val scene = ComposeScene(
            coroutineContext = Dispatchers.Main,
            platform = platform,
            density = density,
            invalidate = skikoUIView::needRedraw,
        )

        skikoUIViewDelegate = object : SkikoUIViewDelegate {
            override fun onKeyboardEvent(event: SkikoKeyboardEvent) {
                scene.sendKeyEvent(KeyEvent(event))
            }

            override fun pointInside(point: CValue<CGPoint>, event: UIEvent?): Boolean =
                point.useContents {
                    val position = Offset(
                        (x * density.density).toFloat(),
                        (y * density.density).toFloat()
                    )

                    !scene.hitTestInteropView(position)
                }

            override fun onTouchesEvent(view: UIView, event: UIEvent, phase: UITouchesEventPhase) {
                val density = density.density

                scene.sendPointerEvent(
                    eventType = phase.toPointerEventType(),
                    pointers = event.touchesForView(view)?.map {
                        val touch = it as UITouch
                        val id = touch.hashCode().toLong()

                        val position = touch.offsetInView(view, density)

                        ComposeScene.Pointer(
                            id = PointerId(id),
                            position = position,
                            pressed = touch.isPressed,
                            type = PointerType.Touch,
                            pressure = touch.force.toFloat(),
                            historical = event.historicalChangesForTouch(touch, view, density)
                        )
                    } ?: emptyList(),
                    timeMillis = (event.timestamp * 1e3).toLong(),
                    nativeEvent = event
                )
            }

            override fun retrieveInteropTransaction(): UIKitInteropTransaction =
                interopContext.retrieve()

            override fun render(canvas: Canvas, targetTimestamp: NSTimeInterval) {
                // The calculation is split in two instead of
                // `(targetTimestamp * 1e9).toLong()`
                // to avoid losing precision for fractional part
                val integral = floor(targetTimestamp)
                val fractional = targetTimestamp - integral
                val secondsToNanos = 1_000_000_000L
                val nanos =
                    integral.roundToLong() * secondsToNanos + (fractional * 1e9).roundToLong()

                scene.render(canvas, nanos)
            }
        }
        skikoUIView.delegate = skikoUIViewDelegate

        scene.setContent(
            onPreviewKeyEvent = inputServices::onPreviewKeyEvent,
            onKeyEvent = { false },
            content = {
                CompositionLocalProvider(
                    LocalLayerContainer provides view,
                    LocalUIViewController provides this,
                    LocalKeyboardOverlapHeightState provides keyboardVisibilityListener.keyboardOverlapHeightState,
                    LocalSafeArea provides safeAreaState,
                    LocalLayoutMargins provides layoutMarginsState,
                    LocalInterfaceOrientationState provides interfaceOrientationState,
                    LocalSystemTheme provides systemTheme.value,
                    LocalUIKitInteropContext provides interopContext,
                ) {
                    content()
                }
            },
        )


        attachedComposeContext =
            AttachedComposeContext(scene, skikoUIView, interopContext).also {
                it.setConstraintsToFillView(view)
                updateLayout(it)
            }
    }
}

private fun UITouch.offsetInView(view: UIView, density: Float): Offset =
    locationInView(view).useContents {
        Offset(x.toFloat() * density, y.toFloat() * density)
    }

private fun UIEvent.historicalChangesForTouch(touch: UITouch, view: UIView, density: Float): List<HistoricalChange> {
    val touches = coalescedTouchesForTouch(touch) ?: return emptyList()

    return if (touches.size > 1) {
        // subList last index is exclusive, so the last touch in the list is not included
        // because it's the actual touch for which coalesced touches were requested
        touches.subList(0, touches.size - 1).map {
            val historicalTouch = it as UITouch
            HistoricalChange(
                uptimeMillis = (historicalTouch.timestamp * 1e3).toLong(),
                position = historicalTouch.offsetInView(view, density)
            )
        }
    } else {
        emptyList()
    }
}

private val UITouch.isPressed
    get() = when (phase) {
        UITouchPhase.UITouchPhaseEnded, UITouchPhase.UITouchPhaseCancelled -> false
        else -> true
    }

private fun UITouchesEventPhase.toPointerEventType(): PointerEventType =
    when (this) {
        UITouchesEventPhase.BEGAN -> PointerEventType.Press
        UITouchesEventPhase.MOVED -> PointerEventType.Move
        UITouchesEventPhase.ENDED -> PointerEventType.Release
        UITouchesEventPhase.CANCELLED -> PointerEventType.Release
    }

private fun UIViewController.checkIfInsideSwiftUI(): Boolean {
    var parent = parentViewController

    while (parent != null) {
        val isUIHostingController = parent.`class`()?.let {
            val className = NSStringFromClass(it)
            // SwiftUI UIHostingController has mangled name depending on generic instantiation type,
            // It always contains UIHostingController substring though
            return className.contains("UIHostingController")
        } ?: false

        if (isUIHostingController) {
            return true
        }

        parent = parent.parentViewController
    }

    return false
}

private fun UIUserInterfaceStyle.asComposeSystemTheme(): SystemTheme {
    return when (this) {
        UIUserInterfaceStyle.UIUserInterfaceStyleLight -> SystemTheme.Light
        UIUserInterfaceStyle.UIUserInterfaceStyleDark -> SystemTheme.Dark
        else -> SystemTheme.Unknown
    }
}

private data class CurrentInput(
    var value: TextFieldValue,
    val onEditCommand: (List<EditCommand>) -> Unit
)
