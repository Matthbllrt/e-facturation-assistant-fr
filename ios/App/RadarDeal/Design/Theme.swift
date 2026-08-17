import SwiftUI

/// RadarDeal's palette — identical values to the Android build so the two apps are visibly the
/// same product. Dark by design, not "dark mode": there is no light variant to keep consistent.
enum RD {
    static let background = Color(hex: 0x070B10)
    static let surface = Color(hex: 0x0D151D)
    static let surfaceElevated = Color(hex: 0x111A23)

    static let accent = Color(hex: 0x00C7A5)
    static let accentSoft = Color(hex: 0x00C7A5).opacity(0.16)
    static let onAccent = Color(hex: 0x00251E)

    static let textPrimary = Color(hex: 0xF5F8FB)
    static let textSecondary = Color(hex: 0x8492A0)
    static let textTertiary = Color(hex: 0x5A6875)

    static let outline = Color(hex: 0x1C2833)
    static let danger = Color(hex: 0xFF5B6E)
    static let warning = Color(hex: 0xFFB020)
    static let priceDrop = Color(hex: 0x4DA3FF)
    static let live = Color(hex: 0x00E0A8)

    enum Space {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
        static let screen: CGFloat = 20
    }

    enum Radius {
        static let card: CGFloat = 24
        static let tile: CGFloat = 20
        static let field: CGFloat = 16
        static let pill: CGFloat = 999
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

/// Typography with the same weight contrast as the Android build: near-black display numbers
/// against light body copy is what makes the hierarchy read instantly.
extension Font {
    static let rdDisplayLarge = Font.system(size: 40, weight: .black, design: .default)
    static let rdDisplayMedium = Font.system(size: 32, weight: .black, design: .default)
    static let rdHeadline = Font.system(size: 21, weight: .bold, design: .default)
    static let rdTitle = Font.system(size: 17, weight: .semibold, design: .default)
    static let rdTitleSmall = Font.system(size: 15, weight: .semibold, design: .default)
    static let rdBody = Font.system(size: 15, weight: .regular, design: .default)
    static let rdBodySmall = Font.system(size: 13.5, weight: .regular, design: .default)
    static let rdLabel = Font.system(size: 11, weight: .bold, design: .default)
    static let rdLabelSmall = Font.system(size: 11, weight: .medium, design: .default)
}
