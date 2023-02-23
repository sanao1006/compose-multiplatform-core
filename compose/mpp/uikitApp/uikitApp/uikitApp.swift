import UIKit
import demo
import SwiftUI

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        if (false) {
            let mainViewController = Main_uikitKt.MainViewController()
            window?.rootViewController = mainViewController
        } else {
            window?.rootViewController = UIHostingController(rootView: ContentView())
        }
        window?.makeKeyAndVisible()
        return true
    }
}

struct ContentView: View {
    var body: some View {
        GradientTemplate(title: "Compose") {
            ComposeView()
        }
        //.ignoresSafeArea(.keyboard)
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        Main_uikitKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct GradientTemplate<Content: View>: View {
    var title: String
    var content: () -> Content

    var body: some View {
        NavigationView {
            ZStack {
                Color.black
                VStack {
                    Color.yellow.ignoresSafeArea(edges: .top).frame(height: 0)
                    Spacer()
                }
                content()
                VStack {
                    Spacer()
                    Rectangle().frame(height: 0).background(Color.yellow)
                }
            }
                    .navigationTitle(title)
                    .navigationBarTitleDisplayMode(.inline)
                    .statusBar(hidden: false)
        }
//                .toolbar(.visible, for: .tabBar)
    }
}
