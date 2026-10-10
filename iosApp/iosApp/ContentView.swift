import SwiftUI
import FirebaseCrashlytics
import SynthDivelogUI

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController { enabled in
            Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(enabled.boolValue)
        }
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView().ignoresSafeArea(.all)
    }
}
