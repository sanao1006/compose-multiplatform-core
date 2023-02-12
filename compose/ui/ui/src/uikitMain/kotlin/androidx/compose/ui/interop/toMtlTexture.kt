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
import platform.Metal.MTLRegionMake2D
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

fun UIView.toMtlTexture(): MTLTextureProtocol? {
    val device = MTLCreateSystemDefaultDevice() ?: error("Failed to create MTLCreateSystemDefaultDevice")
    return createMetalTexture(this, device)
}

fun alignUp(size: Int, align: Int): Int {
    require((align - 1) and align == 0) //Align must be a power of two
    val alignmentMask = align - 1
    return (size + alignmentMask) and alignmentMask.inv()
}

fun createMetalTexture(uiView: UIView, device: MTLDeviceProtocol): MTLTextureProtocol? {//todo move to skiko
    val (width, height) = uiView.bounds().useContents { size.width to size.height }
    val pixelFormat = MTLPixelFormatRGBA8Unorm
    val pixelRowAlignment = device.minimumTextureBufferAlignmentForPixelFormat(pixelFormat)
    val bytesPerRow = alignUp(size = width.toInt(), align = pixelRowAlignment.toInt()) * 4 // 4 color components
    val pagesize = getpagesize()
    val allocationSize = alignUp(size = (bytesPerRow * height).toInt(), align = pagesize)

//    val dataPtr:CValuesRef<CPointerVarOf<COpaquePointer>> = cValue()
//    posix_memalign(dataPtr, pagesize.toULong(), allocationSize.toULong())
    val dataPtr = nativeHeap.allocArray<ByteVar>(allocationSize)

    val context: CPointer<CGContext>? = CGBitmapContextCreate(
        data = dataPtr,
        width = width.toULong(),
        height = height.toULong(),
        bitsPerComponent = 8,
        bytesPerRow = bytesPerRow.toULong(),
        space = CGColorSpaceCreateDeviceRGB(),
        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
    )

//    context.scaleBy(x: 1.0, y: -1.0)
//    context.translateBy(x: 0, y: -CGFloat(context.height))
//    val data: CPointer<out CPointed>? = CGBitmapContextGetData(context)
    val desc = MTLTextureDescriptor.texture2DDescriptorWithPixelFormat(
        pixelFormat = pixelFormat,
        width = width.toULong(),
        height = height.toULong(),
        mipmapped = false
    )
    uiView.layer.renderInContext(context)
    if (ON_SIMULATOR) {
        val texture = device.newTextureWithDescriptor(desc)!!
        texture.replaceRegion(
            region = MTLRegionMake2D(0, 0, width.toULong(), height.toULong()),
            mipmapLevel = 0,
            withBytes = CGBitmapContextGetData(context),
            bytesPerRow = CGBitmapContextGetBytesPerRow(context)
        )
        return texture
    } else {
        val buffer = device.newBufferWithBytesNoCopy(
            pointer = CGBitmapContextGetData(context),
            length = allocationSize.toULong(),
            options = MTLResourceStorageModeShared /*.storageModeShared*/,
            deallocator = null /*{ pointer, length in free(data) }*/
        )!!
        desc.storageMode = buffer.storageMode
        // we are only going to read from this texture on GPU side
        desc.usage = MTLTextureUsageShaderRead
        return buffer.newTextureWithDescriptor(
            descriptor = desc,
            offset= 0,
            bytesPerRow = CGBitmapContextGetBytesPerRow(context)
        )
    }
}