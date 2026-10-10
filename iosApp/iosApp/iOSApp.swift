import SwiftUI
import FirebaseCore
import FirebaseCrashlytics
import SynthDivelogUI

@main
struct iOSApp: App {
    init() {
        FirebaseApp.configure()
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(MainViewControllerKt.crashReportingEnabled())
    }

    var body: some Scene {
        WindowGroup {
            ContentView().ignoresSafeArea()
        }
    }
}
