package androidx.compose.mpp.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitInteropView
import androidx.compose.ui.main.defaultUIKitMain
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Application
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.random.Random
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.MapKit.MKMapView
import platform.UIKit.UIButton
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UISwitch
import platform.UIKit.UIViewController
import platform.WebKit.WKWebView

fun main() {
    defaultUIKitMain("ComposeDemo", Application("Compose/Native sample") {
        myContent()
    })
}

fun MainViewController(): UIViewController =
    Application("UIKit Demo") {
        if (true) {
            UIKitDemo()
        } else {
            myContent()
        }
    }

val BACKGROUND_COLOR = Color.LightGray

@Composable
private fun UIKitDemo() {
    val textState1 = remember { mutableStateOf("sync text state") }
    val counter = remember { mutableStateOf(0) }
    if (true) Popup(object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize
        ): IntOffset = IntOffset(50, 50)
    }) {
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier.size(150.dp).clip(shape).background(Color.LightGray)
                .border(2.dp, color = Color.Black, shape),
            contentAlignment = Alignment.Center,
        ) {
            FpsCounter()
        }
    }
    LazyColumn(Modifier.background(Color.LightGray)) {
        repeat(14) {
            Stub()
        }
        Example("UISwitch") {
            repeat(2) {
                Row {
                    repeat(6) {
                        UIKitInteropView(
                            modifier = Modifier.size(51.dp, 31.dp),
                            factory = { UISwitch() },
                            background = if(Random.nextBoolean()) Color.White else Color.LightGray
                        )
                    }
                }
            }
        }
        Example("UITextField with shared state") {
            repeat(2) {
                ComposeUITextField(
                    Modifier.fillMaxWidth().height(50.dp),
                    textState1.value,
                    onValueChange = { textState1.value = it })
            }
            TextField(value = textState1.value, onValueChange = { textState1.value = it })
        }
//                Example("WebView") {
//                    UIKitInteropView(modifier = Modifier.size(300.dp, 400.dp), factory = {
//                        val wkWebView = WKWebView(frame = CGRectMake(0.0, 0.0, 300.0, 400.0))
//                        wkWebView.loadRequest(NSURLRequest.requestWithURL(NSURL.URLWithString("https://kotlinlang.org")!!))
//                        wkWebView
//                    })
//                }
        Example("MapView") {
            Row {
                UIKitInteropView(modifier = Modifier.size(300.dp, 300.dp), factory = {
                    val mapView = MKMapView(frame = CGRectMake(0.0, 0.0, 200.0, 200.0))
                    mapView
                })
            }
        }
        Example("Modifiers") {
            var alpha by remember { mutableStateOf(1f) }
            var corner by remember { mutableStateOf(0f) }
            var rotate by remember { mutableStateOf(0f) }
            Row {
                UIKitInteropView(
                    modifier = Modifier.size(300.dp, 300.dp)
                        .alpha(alpha)
                        .clip(RoundedCornerShape(size = corner.dp))
                        .rotate(rotate),
                    factory = {
                        val mapView = MKMapView(frame = CGRectMake(0.0, 0.0, 200.0, 200.0))
                        mapView
                    })

            }
            Row {
                Text("Alpha")
                Slider(alpha, onValueChange = { alpha = it }, Modifier.fillMaxWidth())
            }
            Row {
                Text("Corner")
                Slider(
                    corner,
                    onValueChange = { corner = it },
                    Modifier.fillMaxWidth(),
                    valueRange = 0f..150f
                )
            }
            Row {
                Text("Rotate")
                Slider(
                    rotate,
                    onValueChange = { rotate = it },
                    Modifier.fillMaxWidth(),
                    valueRange = 0f..360f
                )
            }
        }
        Example("Todo") {
            Box(Modifier.size(200.dp, 200.dp)) {
                UIKitInteropView(modifier = Modifier.fillMaxSize(), factory = {
                    UISwitch(CGRectMake(0.0, 0.0, 100.0, 100.0))
                })
//                Button(onClick = { counter.value++ }, Modifier.align(Alignment.BottomCenter)) {
//                    Text("Click ${counter.value}")
//                }
            }
        }
        repeat(15) {
            Stub()
        }
    }
}

@Composable
internal fun ColumnScope.Example(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().border(width = 1.dp, color = Color.Black).padding(10.dp)) {
        Text(title)
        Spacer(Modifier.size(10.dp))
        content()
    }
    Stub2()
}

internal fun LazyListScope.Example(title: String, content: @Composable () -> Unit) {
    item {
        Column(Modifier.fillMaxWidth().border(width = 1.dp, color = Color.Black)) {
//            Text(title)
//            Spacer(Modifier.size(10.dp))
            content()
        }
    }
    item {
        Stub2()
    }
}

internal fun LazyListScope.Stub() {
    item {
        Stub2()
    }
}

@Composable
internal fun Stub2() {
    Box(Modifier.fillMaxWidth().height(50.dp).padding(10.dp))
}

@Composable
internal fun FpsCounter() {
    var averageFps: Double by remember { mutableStateOf(20.0) }
    LaunchedEffect(Unit) {
        var previousNanos = withFrameNanos { it }
        while (true) {
            val delta = -previousNanos + withFrameNanos { it }.also { previousNanos = it }
            val seconds = delta.toFloat() / 1E9
            val fps = 1 / seconds
            averageFps = (averageFps * 60 + fps) / 61
        }
    }
    val displayFps = ((averageFps * 10.0).toInt() / 10.0)
    Text("FPS $displayFps", Modifier.background(Color.LightGray))
}
