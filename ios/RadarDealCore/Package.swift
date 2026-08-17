// swift-tools-version: 5.9
import PackageDescription

/// The platform-independent half of RadarDeal.
///
/// Everything here is pure Swift with no UIKit, SwiftUI or Foundation-networking dependency,
/// which is what lets the whole detection engine be unit-tested on any machine — including the
/// Linux CI that produced the numbers in ios/PERFORMANCE-iOS.md — rather than only on a Mac.
let package = Package(
    name: "RadarDealCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "RadarDealCore", targets: ["RadarDealCore"]),
    ],
    targets: [
        .target(name: "RadarDealCore"),
        .testTarget(name: "RadarDealCoreTests", dependencies: ["RadarDealCore"]),
    ]
)
