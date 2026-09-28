// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "BlackBoxCompanion",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "BlackBoxCompanion", path: "Sources/BlackBoxCompanion"),
        .testTarget(name: "BlackBoxCompanionTests", dependencies: ["BlackBoxCompanion"], path: "Tests/BlackBoxCompanionTests"),
    ]
)
