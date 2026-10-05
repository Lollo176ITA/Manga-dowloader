import UIKit
import UserNotifications
import Shared

@main
final class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    var window: UIWindow?
    private let services = NativePlatformServices()
    private var app: IosApp!

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0"
        let code = Int32(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1") ?? 1
        app = IosApp(services: services, versionName: version, versionCode: code)
        services.onNotificationPermissionChanged = { [weak self] granted in
            self?.app.updateNotificationPermission(granted: granted)
        }
        let root = ReaderContainerViewController(content: app.makeViewController(), services: services)
        services.presenter = root
        services.onReaderConfiguration = { [weak root] in root?.updateReaderConfiguration() }
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = root
        self.window = window
        window.makeKeyAndVisible()
        services.refreshNotificationPermission()
        if let url = launchOptions?[.url] as? URL { _ = app.handleUrl(url: url.absoluteString) }
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        services.refreshNotificationPermission()
    }

    func application(_ app: UIApplication, open url: URL, options: [UIApplication.OpenURLOptionsKey: Any] = [:]) -> Bool {
        self.app.handleUrl(url: url.absoluteString)
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        services.allowLandscape ? .allButUpsideDown : .portrait
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        DispatchQueue.main.async { [weak self] in
            self?.app.openUpdates()
            completionHandler()
        }
    }
}

private final class ReaderContainerViewController: UIViewController {
    private let content: UIViewController
    private let services: NativePlatformServices

    init(content: UIViewController, services: NativePlatformServices) {
        self.content = content
        self.services = services
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("Storyboard non utilizzato") }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(content)
        content.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(content.view)
        NSLayoutConstraint.activate([
            content.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            content.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            content.view.topAnchor.constraint(equalTo: view.topAnchor),
            content.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        content.didMove(toParent: self)
    }

    override var prefersStatusBarHidden: Bool { services.fullscreen }
    override var prefersHomeIndicatorAutoHidden: Bool { services.fullscreen }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        services.allowLandscape ? .allButUpsideDown : .portrait
    }

    func updateReaderConfiguration() {
        setNeedsStatusBarAppearanceUpdate()
        setNeedsUpdateOfHomeIndicatorAutoHidden()
        if #available(iOS 16.0, *) {
            setNeedsUpdateOfSupportedInterfaceOrientations()
            view.window?.windowScene?.requestGeometryUpdate(.iOS(interfaceOrientations: supportedInterfaceOrientations))
        } else {
            UIViewController.attemptRotationToDeviceOrientation()
        }
    }
}
