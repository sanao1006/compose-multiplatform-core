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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.createSkiaLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.interop.LocalLayerContainer
import androidx.compose.ui.interop.LocalUIViewController
import androidx.compose.ui.native.ComposeLayer
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.uikit.*
import androidx.compose.ui.unit.*
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import org.jetbrains.skiko.SkikoUIView
import org.jetbrains.skiko.TextActions
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.NSObject

private val uiContentSizeCategoryToFontScaleMap = mapOf(
    UIContentSizeCategoryExtraSmall to 0.9f,
    UIContentSizeCategorySmall to 0.95f,
    UIContentSizeCategoryMedium to 1.0f,
    UIContentSizeCategoryLarge to 1.05f,
    UIContentSizeCategoryExtraLarge to 1.1f,
    UIContentSizeCategoryExtraExtraLarge to 1.15f,
    UIContentSizeCategoryExtraExtraExtraLarge to 1.2f,

    UIContentSizeCategoryAccessibilityMedium to 1.3f,
    UIContentSizeCategoryAccessibilityLarge to 1.4f,
    UIContentSizeCategoryAccessibilityExtraLarge to 1.5f,
    UIContentSizeCategoryAccessibilityExtraExtraLarge to 1.6f,
    UIContentSizeCategoryAccessibilityExtraExtraExtraLarge to 1.7f,

    // UIContentSizeCategoryUnspecified
)

fun ComposeUIViewController(content: @Composable () -> Unit): UIViewController =
    ComposeWindow().apply {
        setContent(content)
    }

// The only difference with macos' Window is that
// it has return type of UIViewController rather than unit.
@Deprecated(
    "use ComposeUIViewController instead",
    replaceWith = ReplaceWith(
        "ComposeUIViewController(content = content)",
        "androidx.compose.ui.window"
    )
)
fun Application(
    title: String = "JetpackNativeWindow",
    content: @Composable () -> Unit = { }
): UIViewController = ComposeUIViewController(content)

@OptIn(InternalComposeApi::class)
@ExportObjCClass
internal actual class ComposeWindow : UIViewController {

    private val keyboardOverlapHeightState = mutableStateOf(0f)
    private val safeAreaState = mutableStateOf(IOSInsets())
    private val layoutMarginsState = mutableStateOf(IOSInsets())

    /*
     * Initial value is arbitarily chosen to avoid propagating invalid value logic
     * It's never the case in real usage scenario to reflect that in type system
     */
    private val interfaceOrientationState = mutableStateOf(
        InterfaceOrientation.Portrait
    )

    /*
     * On iOS >= 13.0 interfaceOrientation will be deduced from [UIWindowScene] of [UIWindow]
     * to which our [ComposeWindow] is attached.
     * It's never UIInterfaceOrientationUnknown, if accessed after owning [UIWindow] was made key and visible:
     * https://developer.apple.com/documentation/uikit/uiwindow/1621601-makekeyandvisible?language=objc
     */
    private val currentInterfaceOrientation: InterfaceOrientation?
        get() {
            // Flag for checking which API to use
            // Modern: https://developer.apple.com/documentation/uikit/uiwindowscene/3198088-interfaceorientation?language=objc
            // Deprecated: https://developer.apple.com/documentation/uikit/uiapplication/1623026-statusbarorientation?language=objc
            val supportsWindowSceneApi = NSProcessInfo.processInfo.operatingSystemVersion.useContents {
                majorVersion >= 13
            }

            return if (supportsWindowSceneApi) {
                view.window?.windowScene?.interfaceOrientation?.let {
                    InterfaceOrientation.getByRawValue(it)
                }
            } else {
                InterfaceOrientation.getByRawValue(UIApplication.sharedApplication.statusBarOrientation)
            }
        }

    @OverrideInit
    actual constructor() : super(nibName = null, bundle = null)

    @OverrideInit
    constructor(coder: NSCoder) : super(coder)

    private val fontScale: Float
        get() {
            val contentSizeCategory =
                traitCollection.preferredContentSizeCategory ?: UIContentSizeCategoryUnspecified

            return uiContentSizeCategoryToFontScaleMap[contentSizeCategory] ?: 1.0f
        }

    private val density: Density
        get() = Density(
            density = view.window?.screen?.scale?.toFloat() ?: 1f,
            fontScale = fontScale
        )

    private lateinit var layer: ComposeLayer
    private lateinit var content: @Composable () -> Unit
    private lateinit var skikoUIView: SkikoUIView

    private val keyboardVisibilityListener = object : NSObject() {
        @Suppress("unused")
        @ObjCAction
        fun keyboardDidShow(arg: NSNotification) {
            val keyboardInfo = arg.userInfo!!["UIKeyboardFrameEndUserInfoKey"] as NSValue
            val keyboardHeight = keyboardInfo.CGRectValue().useContents { size.height }
            val screenHeight = UIScreen.mainScreen.bounds.useContents { size.height }

            val composeViewBottomY = UIScreen.mainScreen.coordinateSpace.convertPoint(
                point = CGPointMake(0.0, view.frame.useContents { size.height }),
                fromCoordinateSpace = view.coordinateSpace
            ).useContents { y }
            val bottomIndent = screenHeight - composeViewBottomY

            if (bottomIndent < keyboardHeight) {
                keyboardOverlapHeightState.value = (keyboardHeight - bottomIndent).toFloat()
            }
        }

        @Suppress("unused")
        @ObjCAction
        fun keyboardDidHide(arg: NSNotification) {
            keyboardOverlapHeightState.value = 0f
        }
    }

    @Suppress("unused")
    @ObjCAction
    fun viewSafeAreaInsetsDidChange() {
        // super.viewSafeAreaInsetsDidChange() // TODO: call super after Kotlin 1.8.20
        view.safeAreaInsets.useContents {
            safeAreaState.value = IOSInsets(
                top = top.dp,
                bottom = bottom.dp,
                left = left.dp,
                right = right.dp,
            )
        }
        view.directionalLayoutMargins.useContents {
            layoutMarginsState.value = IOSInsets(
                top = top.dp,
                bottom = bottom.dp,
                left = leading.dp,
                right = trailing.dp,
            )
        }
    }

    override fun loadView() {
        val skiaLayer = createSkiaLayer()
        skikoUIView = SkikoUIView(
            skiaLayer = skiaLayer,
            pointInside = { point, _ ->
                !layer.hitInteropView(point, isTouchEvent = true)
            },
            onLayout = { width, height ->
                updateComposeLayer(width, height)
            }
        ).load()
        val rootView = UIView() // rootView needs to interop with UIKit
        rootView.backgroundColor = UIColor.whiteColor

        skikoUIView.translatesAutoresizingMaskIntoConstraints = false
        rootView.addSubview(skikoUIView)

        NSLayoutConstraint.activateConstraints(listOf(
            skikoUIView.leadingAnchor.constraintEqualToAnchor(rootView.leadingAnchor),
            skikoUIView.trailingAnchor.constraintEqualToAnchor(rootView.trailingAnchor),
            skikoUIView.topAnchor.constraintEqualToAnchor(rootView.topAnchor),
            skikoUIView.bottomAnchor.constraintEqualToAnchor(rootView.bottomAnchor)
        ))
        updateMetalLayerPresentationMode()

        view = rootView
        val uiKitTextInputService = UIKitTextInputService(
            showSoftwareKeyboard = {
                skikoUIView.showScreenKeyboard()
            },
            hideSoftwareKeyboard = {
                skikoUIView.hideScreenKeyboard()
            },
            updateView = {
                skikoUIView.setNeedsDisplay() // redraw on next frame
                platform.QuartzCore.CATransaction.flush() // clear all animations
                skikoUIView.reloadInputViews() // update input (like screen keyboard)
            },
            textWillChange = { skikoUIView.textWillChange() },
            textDidChange = { skikoUIView.textDidChange() },
            selectionWillChange = { skikoUIView.selectionWillChange() },
            selectionDidChange = { skikoUIView.selectionDidChange() },
        )
        val uiKitPlatform = object : Platform by Platform.Empty {
            override val textInputService: PlatformTextInputService = uiKitTextInputService
            override val viewConfiguration =
                object : ViewConfiguration {
                    override val longPressTimeoutMillis: Long get() = 500
                    override val doubleTapTimeoutMillis: Long get() = 300
                    override val doubleTapMinTimeMillis: Long get() = 40
                    override val touchSlop: Float get() = with(density) { 3.dp.toPx() }
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
        layer = ComposeLayer(
            layer = skiaLayer,
            platform = uiKitPlatform,
            getTopLeftOffset = ::getTopLeftOffset,
            input = uiKitTextInputService.skikoInput,
        )
        layer.setContent(content = {
            CompositionLocalProvider(
                LocalLayerContainer provides rootView,
                LocalUIViewController provides this,
                LocalKeyboardOverlapHeightState provides keyboardOverlapHeightState,
                LocalSafeAreaState provides safeAreaState,
                LocalLayoutMarginsState provides layoutMarginsState,
                LocalInterfaceOrientationState provides interfaceOrientationState,
            ) {
                content()
            }
        })
    }

    private var viewIsInSizeTransition = false
        set(value) {
            field = value

            updateMetalLayerPresentationMode()
        }

    var hasActiveUIViewInterop = true
        set(value) {
            field = value

            updateMetalLayerPresentationMode()
        }

    private fun updateMetalLayerPresentationMode() {
        skikoUIView.metalLayer.presentsWithTransaction = viewIsInSizeTransition || hasActiveUIViewInterop
//        skikoUIView.metalLayer.setOpaque(!hasActiveUIViewInterop)
    }

    override fun viewWillTransitionToSize(
        size: CValue<CGSize>,
        withTransitionCoordinator: UIViewControllerTransitionCoordinatorProtocol
    ) {
        super.viewWillTransitionToSize(size, withTransitionCoordinator)

        // view for animating smooth orientation change
        val snapshotView = skikoUIView.snapshotViewAfterScreenUpdates(false)

        viewIsInSizeTransition = true

        snapshotView?.let {
            it.setOpaque(false)
            it.setClipsToBounds(true)
            view.addSubview(it)
            it.setFrame(view.frame)

            withTransitionCoordinator.animateAlongsideTransition(
                animation = { _ ->
                    size.useContents {
                        it.setFrame(CGRectMake(0.0, 0.0, width, height))
                    }
                    it.alpha = 0.0
                },
                completion = { _ ->
                    it.removeFromSuperview()

                    viewIsInSizeTransition = false
                }
            )
        }
    }

    override fun traitCollectionDidChange(previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)

        val newSizeCategory = traitCollection.preferredContentSizeCategory

        if (newSizeCategory != null && previousTraitCollection?.preferredContentSizeCategory != newSizeCategory) {
            // will force a view to do layout on a next main runloop tick
            // which will force layout of UISkikoView, which will complete and call [updateComposeLayer]
            // which will assign new density to layer (which takes new fontScale into consideration)
            // which will change density state value inside composition and force recomposition

            view.setNeedsLayout()
        }
    }

    // Update [ComposeLayer] with latest layout data
    private fun updateComposeLayer(width: Int, height: Int) {
        currentInterfaceOrientation?.let {
            interfaceOrientationState.value = it
        }

        layer.setDensity(density)
        layer.setSize(width, height)
    }

    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)
        NSNotificationCenter.defaultCenter.addObserver(
            observer = keyboardVisibilityListener,
            selector = NSSelectorFromString(keyboardVisibilityListener::keyboardDidShow.name + ":"),
            name = UIKeyboardDidShowNotification,
            `object` = null
        )
        NSNotificationCenter.defaultCenter.addObserver(
            observer = keyboardVisibilityListener,
            selector = NSSelectorFromString(keyboardVisibilityListener::keyboardDidHide.name + ":"),
            name = UIKeyboardDidHideNotification,
            `object` = null
        )
    }

    // viewDidUnload() is deprecated and not called.
    override fun viewDidDisappear(animated: Boolean) {
        // TODO call dispose() function, but check how it will works with SwiftUI interop between different screens.
        super.viewDidDisappear(animated)
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
        NSNotificationCenter.defaultCenter.removeObserver(
            observer = keyboardVisibilityListener,
            name = UIKeyboardDidHideNotification,
            `object` = null
        )
    }

    override fun didReceiveMemoryWarning() {
        println("didReceiveMemoryWarning")
        kotlin.native.internal.GC.collect()
        super.didReceiveMemoryWarning()
    }

    actual fun setContent(
        content: @Composable () -> Unit
    ) {
        this.content = content
    }

    actual fun dispose() {
        layer.dispose()
    }

    private fun getViewFrameSize(): IntSize {
        val (width, height) = view.frame().useContents { this.size.width to this.size.height }
        return IntSize(width.toInt(), height.toInt())
    }

    private fun getTopLeftOffset(): Offset {
        val topLeftPoint =
            view.coordinateSpace().convertPoint(
                point = CGPointMake(0.0, 0.0),
                toCoordinateSpace = UIScreen.mainScreen.coordinateSpace()
            )
        return topLeftPoint.useContents { DpOffset(x.dp, y.dp).toOffset(density) }
    }
}