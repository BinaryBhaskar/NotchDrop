// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "NotchDrop",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "NotchDrop",
            path: "Sources/NotchDrop",
            swiftSettings: [.swiftLanguageMode(.v5)]
        )
    ]
)
