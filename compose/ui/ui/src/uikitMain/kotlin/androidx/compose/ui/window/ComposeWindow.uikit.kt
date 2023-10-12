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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.interop.LocalLayerContainer
import androidx.compose.ui.interop.LocalUIKitInteropContext
import androidx.compose.ui.interop.LocalUIViewController
import androidx.compose.ui.interop.UIKitInteropContext
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.uikit.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.di.*
import kotlin.math.roundToInt
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExportObjCClass
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.OSVersion
import org.jetbrains.skiko.available
import platform.CoreGraphics.CGAffineTransformIdentity
import platform.CoreGraphics.CGAffineTransformInvert
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
    val densityProvider = { composeWindow!!.density }
    val sceneProvider = {composeWindow!!.scene}
    val configuration = ComposeUIViewControllerConfiguration().apply(configure)
    val isReadyToShowContent = mutableStateOf(false)
    val interopContext = UIKitInteropContext(
        requestRedraw = {}
    )
    val skikoViewDelegate = SkikoUIViewDelegateImpl(
        densityProvider = densityProvider,
        sceneProvider = sceneProvider,
        interopContext = interopContext,
        attachedComposeContextProvider = { composeWindow!!.attachedComposeContext!! },
        isReadyToShowContent = isReadyToShowContent
    )
    val keyboardEventHandler = KeyboardEventHandlerImpl(
        sceneProvider = sceneProvider
    )
    val inputService = InputServiceImpl(
        rootViewProvider = { composeWindow!!.view },
        skikoUIViewProvider = { composeWindow!!.attachedComposeContext!!.view },
        keyboardEventHandler = keyboardEventHandler
    )
    val keyEventHandler = KeyEventHandlerImpl(
        inputService = inputService
    )
    return ComposeWindow(
        configuration = configuration,
        content = content,
        keyboardVisibilityListener = KeyboardVisibilityListenerImpl(
            viewProvider = { composeWindow!!.view },
            configuration = configuration,
            attachedComposeContextProvider = { composeWindow!!.attachedComposeContext },
            densityProvider = densityProvider,
        ),
        keyEventHandler = keyEventHandler,
        inputService = inputService,
        delegate = skikoViewDelegate,
        keyboardEventHandler = keyboardEventHandler,
        isReadyToShowContent = isReadyToShowContent,
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
    private val keyEventHandler: KeyEventHandler,
    private val inputService: PlatformTextInputService,
    private val delegate: SkikoUIViewDelegate,
    private val keyboardEventHandler: KeyboardEventHandler,
    private val isReadyToShowContent: MutableState<Boolean>,
) : UIViewController(nibName = null, bundle = null) {

    lateinit var scene: ComposeScene //todo lateinit
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

    private lateinit var skikoUIViewDelegate: SkikoUIViewDelegate

    private fun attachComposeIfNeeded() {
        if (attachedComposeContext != null) {
            return // already attached
        }

        val skikoUIView = SkikoUIView(
            delegate = delegate,
            keyboardEventHandler = keyboardEventHandler,
        )

        val interopContext = UIKitInteropContext(requestRedraw = skikoUIView::needRedraw)

        skikoUIView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(skikoUIView)

        val platform = object : Platform by Platform.Empty {
            override val windowInfo: WindowInfo
                get() = _windowInfo
            override val textInputService: PlatformTextInputService = inputService
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

        scene = ComposeScene(
            coroutineContext = Dispatchers.Main,
            platform = platform,
            density = density,
            invalidate = skikoUIView::needRedraw,
        )

        scene.setContent(
            onPreviewKeyEvent = keyEventHandler::onPreviewKeyEvent,
            onKeyEvent = keyEventHandler::onKeyEvent,
            content = {
                if (!isReadyToShowContent.value) return@setContent
                CompositionLocalProvider(
                    LocalLayerContainer provides view,
                    LocalUIViewController provides this,
                    LocalKeyboardOverlapHeightState provides keyboardVisibilityListener.keyboardOverlapHeightState,
                    LocalSafeArea provides safeAreaState,
                    LocalLayoutMargins provides layoutMarginsState,
                    LocalInterfaceOrientationState provides interfaceOrientationState,
                    LocalSystemTheme provides systemTheme.value,
                    LocalUIKitInteropContext provides interopContext,
                    content = content
                )
            },
        )

        attachedComposeContext =
            AttachedComposeContext(scene, skikoUIView, interopContext).also {
                it.setConstraintsToFillView(view)
                updateLayout(it)
            }
    }
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
