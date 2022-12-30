package androidx.compose.mpp.demo

import androidx.compose.ui.window.Application
import androidx.compose.ui.main.defaultUIKitMain
import platform.UIKit.UIViewController

fun main() {
    defaultUIKitMain("ComposeDemo", Application("Compose/Native sample") {
        myContent()
    })
}

fun MainViewController() : UIViewController =
    Application("UIKit Demo") {
        myContent()
    }
