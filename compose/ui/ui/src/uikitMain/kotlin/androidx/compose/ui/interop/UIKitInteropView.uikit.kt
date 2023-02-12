/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.compose.ui.interop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.UIKitInteropModifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import cnames.structs.CGContext
import kotlin.native.concurrent.AtomicNativePtr
import kotlin.native.internal.NativePtr
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.CValue
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.UByteVarOf
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.pin
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import org.jetbrains.skia.GrBackendTexture
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkikoTouchEvent
import org.jetbrains.skiko.SkikoTouchEventKind
import org.jetbrains.skiko.createFromMetalTexture
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformIdentity
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGAffineTransformScale
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetAlphaInfo
import platform.CoreGraphics.CGBitmapContextGetBytesPerRow
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextClearRect
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGLayerGetContext
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.Metal.MTLBufferProtocol
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLPixelFormatRGBA8Unorm
import platform.Metal.MTLRegion
import platform.Metal.MTLRegionMake2D
import platform.Metal.MTLResourceStorageModePrivate
import platform.Metal.MTLResourceStorageModeShared
import platform.Metal.MTLTextureDescriptor
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLTextureUsageShaderRead
import platform.QuartzCore.CATransaction
import platform.QuartzCore.CATransform3DConcat
import platform.QuartzCore.CATransform3DMakeScale
import platform.QuartzCore.CATransform3DMakeTranslation
import platform.UIKit.UIColor
import platform.UIKit.UIEvent
import platform.UIKit.UITouch
import platform.UIKit.UIView
import platform.UIKit.addSubview
import platform.UIKit.backgroundColor
import platform.UIKit.insertSubview
import platform.UIKit.layoutIfNeeded
import platform.UIKit.removeFromSuperview
import platform.UIKit.setContentScaleFactor
import platform.UIKit.setFrame
import platform.UIKit.setNeedsDisplay
import platform.UIKit.setNeedsUpdateConstraints
import platform.darwin.Byte
import platform.darwin.ByteVar
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.getpagesize
import platform.posix.posix_memalign

const val ON_SIMULATOR = false
/**
 * On simulator available only private storage mode
 * https://developer.apple.com/documentation/metal/developing_metal_apps_that_run_in_simulator?language=objc
 */
val metalResourceStorageMode =
    if (ON_SIMULATOR) MTLResourceStorageModePrivate else MTLResourceStorageModeShared
val NoOpUpdate: UIView.() -> Unit = {}
private val device = MTLCreateSystemDefaultDevice()!!//todo hardcode

private class Cache(
    val textureMemoryPtr: CPointer<UByteVarOf<Byte>>, //todo clear cache
    val context: CPointer<CGContext>,
    val textureRegion: CValue<MTLRegion>,
    val descriptor: MTLTextureDescriptor,
    var texture: MTLTextureProtocol? = null,
)

@Composable
public fun <T : UIView> UIKitInteropView(
    background: Color = Color.White,
    factory: () -> T,
    modifier: Modifier = Modifier,
    update: (T) -> Unit = NoOpUpdate,
    dispose: (T) -> Unit = {},
    useMetalTexture: Boolean = true,
    useAlphaComponent: Boolean = true,
) {
    val componentInfo = remember { ComponentInfo<T>() }

    val root = LocalLayerContainer.current
    val skikoTouchEventHandler = SkikoTouchEventHandler.current
    val density = LocalDensity.current.density
    val focusManager = LocalFocusManager.current
    val backendTextureToImage = SkikoBackendTextureToImage.current
    val focusSwitcher = remember { FocusSwitcher(componentInfo, focusManager) }
    var rectInPixels by remember { mutableStateOf(IntRect(0, 0, 0, 0)) }
    var uiViewSize by remember { mutableStateOf(IntSize(0, 0)) }
    var cache: Cache? by remember { mutableStateOf(null) }
    val mtlSkikoImage: Image? = remember(cache?.texture) {
        cache?.texture?.let {
            val skikoBackendTexture = GrBackendTexture.Companion.createFromMetalTexture(
                mtlTexture = it,
                width = it.width.toInt(),
                height = it.height.toInt()
            )
            backendTextureToImage(skikoBackendTexture)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { it }
            val uiView = componentInfo.component
            val size = uiView.bounds().useContents { IntSize((size.width * density).toInt(), (size.height * density).toInt()) }

            if (size.width != 0 && size.height != 0) {
                val pixelFormat = MTLPixelFormatRGBA8Unorm
                val pixelRowAlignment = device.minimumTextureBufferAlignmentForPixelFormat(pixelFormat)
                val bytesPerRow = alignUp(size = size.width, align = pixelRowAlignment.toInt()) * 4 // 4 color components
                val pagesize = getpagesize()
                val allocationSize = alignUp(size = bytesPerRow * size.height, align = pagesize)

                if (uiViewSize != size) {
                    uiViewSize = size
                    val desc = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
                        pixelFormat = pixelFormat,
                        width = size.width.toULong(),
                        height = size.height.toULong(),
                        mipmapped = false
                    )
                    val textureMemoryPtr = nativeHeap.allocArray<ByteVar>(allocationSize)
                    //val textureMemoryPtr:CValuesRef<CPointerVarOf<COpaquePointer>> = cValue()
                    //posix_memalign(textureMemoryPtr, pagesize.toULong(), allocationSize.toULong())
                    //val textureMemoryPtr: CPointer<UByteVarOf<Byte>> = nativeHeap.allocArray<ByteVar>(allocationSize)
                    val textureRegion = MTLRegionMake2D(0, 0, size.width.toULong(), size.height.toULong())
                    val context = CGBitmapContextCreate(
                        data = textureMemoryPtr,
                        width = size.width.toULong(),
                        height = size.height.toULong(),
                        bitsPerComponent = 8,
                        bytesPerRow = bytesPerRow.toULong(),
                        space = CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo = if (useAlphaComponent) CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value else CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value
                    )
                    CGContextScaleCTM(context, density.toDouble(), density.toDouble())
                    //context.translateBy(x: 0, y: -CGFloat(context.height))
                    cache = Cache(
                        textureMemoryPtr,
                        context!!,
                        textureRegion,
                        desc
                    ).also {
                        if (ON_SIMULATOR) {
                            it.texture = device.newTextureWithDescriptor(desc)!!
                        } else {
                            it.descriptor.storageMode = metalResourceStorageMode
                            // we are only going to read from this texture on GPU side
                            it.descriptor.usage = MTLTextureUsageShaderRead
                        }
                    }
                }
                val cache = cache
                if (cache != null) {
                    CGContextClearRect(cache.context, componentInfo.container.bounds())
                    componentInfo.container.layer.renderInContext(cache.context)
                    if (ON_SIMULATOR) {
                        cache.texture?.replaceRegion(
                            region = cache.textureRegion,
                            mipmapLevel = 0,
                            withBytes = cache.textureMemoryPtr /*CGBitmapContextGetData(context)*/,
                            bytesPerRow = bytesPerRow.toULong() /*CGBitmapContextGetBytesPerRow(context)*/
                        )
                    } else {
                        val buffer = device.newBufferWithBytesNoCopy(
                            pointer = CGBitmapContextGetData(cache.context),
                            length = allocationSize.toULong(),
                            options = metalResourceStorageMode,
                            deallocator = null /*{ pointer, length in free(data) }*/
                        )!!
                        if (cache.texture == null) {
                            cache.texture = buffer.newTextureWithDescriptor(
                                descriptor = cache.descriptor,
                                offset = 0,
                                bytesPerRow = bytesPerRow.toULong() /*CGBitmapContextGetBytesPerRow(context)*/
                            )
                        }
                    }
                }
            }
        }
    }
    Box(
        modifier = modifier.onGloballyPositioned { childCoordinates ->
            val coordinates = childCoordinates.parentCoordinates!!
            val newRectInPixels = IntRect(coordinates.localToWindow(Offset.Zero).round(), coordinates.size)
            if (rectInPixels != newRectInPixels) {
                rectInPixels = newRectInPixels
                dispatch_async(dispatch_get_main_queue()) {
                    val rect = rectInPixels / density
                    val cgRect = rect.toCGRect()
                    CATransaction.begin()
                    componentInfo.container.setFrame(cgRect)
                    componentInfo.component.setFrame(CGRectMake(0.0, 0.0, rect.width.toDouble(), rect.height.toDouble()))
                    CATransaction.commit()
                    componentInfo.component.layoutIfNeeded()
                    componentInfo.component.setNeedsDisplay()
                    componentInfo.component.setNeedsUpdateConstraints()
                    componentInfo.component.resignFirstResponder()
                }
            }
        }.drawBehind {
            if (useMetalTexture) {
                drawIntoCanvas {canvas->
                    if (mtlSkikoImage != null) {
                        canvas.drawRect(0f, 0f, uiViewSize.width.toFloat(), uiViewSize.height.toFloat(), Paint().apply {
                            color = background
                        })
                        canvas.nativeCanvas.drawImage(mtlSkikoImage, 0f, 0f)
                    }
                }
            } else {
                drawRect(Color.Transparent, blendMode = BlendMode.DstAtop)
            }
        }.then(UIKitInteropModifier(rectInPixels.width, rectInPixels.height))
    ) {
        focusSwitcher.Content()
    }

    DisposableEffect(factory) {
//        val focusListener = object : FocusListener {
//            override fun focusGained(e: FocusEvent) {
//                if (componentInfo.container.isParentOf(e.oppositeComponent)) {
//                    when (e.cause) {
//                        FocusEvent.Cause.TRAVERSAL_FORWARD -> focusSwitcher.moveForward()
//                        FocusEvent.Cause.TRAVERSAL_BACKWARD -> focusSwitcher.moveBackward()
//                        else -> Unit
//                    }
//                }
//            }
//
//            override fun focusLost(e: FocusEvent) = Unit
//        }
//        root.addFocusListener(focusListener)
        componentInfo.component = factory()
        componentInfo.container = object : UIView(CGRectMake(0.0, 0.0, 0.0, 0.0)) {
            override fun touchesBegan(touches: Set<*>, withEvent: UIEvent?) {
                super.touchesBegan(touches, withEvent)
                sendTouchEventToSkikoView(touches, SkikoTouchEventKind.STARTED)
            }

            override fun touchesEnded(touches: Set<*>, withEvent: UIEvent?) {
                super.touchesEnded(touches, withEvent)
                sendTouchEventToSkikoView(touches, SkikoTouchEventKind.ENDED)
            }

            override fun touchesMoved(touches: Set<*>, withEvent: UIEvent?) {
                super.touchesMoved(touches, withEvent)
                sendTouchEventToSkikoView(touches, SkikoTouchEventKind.MOVED)
            }

            override fun touchesCancelled(touches: Set<*>, withEvent: UIEvent?) {
                super.touchesCancelled(touches, withEvent)
                sendTouchEventToSkikoView(touches, SkikoTouchEventKind.CANCELLED)
            }

            private fun sendTouchEventToSkikoView(touches: Set<*>, kind: SkikoTouchEventKind) {
                val events: Array<SkikoTouchEvent> = touches.map {
                    val event = it as UITouch
                    val (x, y) = event.locationInView(null).useContents { x to y }
                    val timestamp = (event.timestamp * 1_000).toLong()
                    SkikoTouchEvent(x, y, kind, timestamp, event)
                }.toTypedArray()
                skikoTouchEventHandler(events)
            }
        }.apply {
//            layout = BorderLayout(0, 0)
//            focusTraversalPolicy = object : LayoutFocusTraversalPolicy() {
//                override fun getComponentAfter(aContainer: Container?, aComponent: Component?): Component? {
//                    return if (aComponent == getLastComponent(aContainer)) {
//                        root
//                    } else {
//                        super.getComponentAfter(aContainer, aComponent)
//                    }
//                }
//
//                override fun getComponentBefore(aContainer: Container?, aComponent: Component?): Component? {
//                    return if (aComponent == getFirstComponent(aContainer)) {
//                        root
//                    } else {
//                        super.getComponentBefore(aContainer, aComponent)
//                    }
//                }
//            }
//            isFocusCycleRoot = true
            addSubview(componentInfo.component)
        }
        componentInfo.updater = Updater(componentInfo.component, update)
        root.insertSubview(componentInfo.container, 0)
        onDispose {
            componentInfo.container.removeFromSuperview()
            componentInfo.updater.dispose()
            dispose(componentInfo.component)
//            root.removeFocusListener(focusListener)
        }
    }
    SideEffect {
        if (!useMetalTexture) {
            componentInfo.container.backgroundColor = parseColor(background)
        }
        componentInfo.updater.update = update
    }
}

private class FocusSwitcher<T : UIView>(
    private val info: ComponentInfo<T>,
    private val focusManager: FocusManager
) {
    private val backwardRequester = FocusRequester()
    private val forwardRequester = FocusRequester()
    private var isRequesting = false

    fun moveBackward() {
        try {
            isRequesting = true
            backwardRequester.requestFocus()
        } finally {
            isRequesting = false
        }
        focusManager.moveFocus(FocusDirection.Previous)
    }

    fun moveForward() {
        try {
            isRequesting = true
            forwardRequester.requestFocus()
        } finally {
            isRequesting = false
        }
        focusManager.moveFocus(FocusDirection.Next)
    }

    @Composable
    fun Content() {
        Box(
            Modifier
                .focusRequester(backwardRequester)
                .onFocusChanged {
                    if (it.isFocused && !isRequesting) {
                        focusManager.clearFocus(force = true)

//                        val component = info.container.focusTraversalPolicy.getFirstComponent(info.container)
//                        if (component != null) {
//                            component.requestFocus(FocusEvent.Cause.TRAVERSAL_FORWARD)
//                        } else {
//                            moveForward()
//                        }
                    }
                }
                .focusTarget()
        )
        Box(
            Modifier
                .focusRequester(forwardRequester)
                .onFocusChanged {
                    if (it.isFocused && !isRequesting) {
                        focusManager.clearFocus(force = true)

//                        val component = info.container.focusTraversalPolicy.getLastComponent(info.container)
//                        if (component != null) {
//                            component.requestFocus(FocusEvent.Cause.TRAVERSAL_BACKWARD)
//                        } else {
//                            moveBackward()
//                        }
                    }
                }
                .focusTarget()
        )
    }
}

@Composable
private fun Box(modifier: Modifier, content: @Composable () -> Unit = {}) {
    Layout(
        content = content,
        modifier = modifier,
        measurePolicy = { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }
            layout(
                placeables.maxOfOrNull { it.width } ?: 0,
                placeables.maxOfOrNull { it.height } ?: 0
            ) {
                placeables.forEach {
                    it.place(0, 0)
                }
            }
        }
    )
}

private fun parseColor(color: Color): UIColor {
    return UIColor(
        red = color.red.toDouble(),
        green = color.green.toDouble(),
        blue = color.blue.toDouble(),
        alpha = color.alpha.toDouble()
    )
}

private class ComponentInfo<T : UIView> {
    lateinit var container: UIView
    lateinit var component: T
    lateinit var updater: Updater<T>
}

private class Updater<T : UIView>(
    private val component: T,
    update: (T) -> Unit
) {
    private var isDisposed = false
    private val isUpdateScheduled = atomic(false)
    private val snapshotObserver = SnapshotStateObserver { command ->
        command()
    }

    private val scheduleUpdate = { _: T ->
        if (!isUpdateScheduled.getAndSet(true)) {
            dispatch_async(dispatch_get_main_queue()) {
                isUpdateScheduled.value = false
                if (!isDisposed) {
                    performUpdate()
                }
            }
        }
    }

    var update: (T) -> Unit = update
        set(value) {
            if (field != value) {
                field = value
                performUpdate()
            }
        }

    private fun performUpdate() {
        // don't replace scheduleUpdate by lambda reference,
        // scheduleUpdate should always be the same instance
        snapshotObserver.observeReads(component, scheduleUpdate) {
            update(component)
        }
    }

    init {
        snapshotObserver.start()
        performUpdate()
    }

    fun dispose() {
        snapshotObserver.stop()
        snapshotObserver.clear()
        isDisposed = true
    }
}

fun UIView.scale(scale: Float) {//todo scale needs as workaround to correctly handle density
    layer.anchorPoint = CGPointMake(0.0, 0.0)//todo works only on iOS 16 and newer
    layer.transform = CATransform3DMakeScale(scale.toDouble(), scale.toDouble(), 1.0)
}
