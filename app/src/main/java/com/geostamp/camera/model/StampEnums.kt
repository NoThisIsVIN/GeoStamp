package com.geostamp.camera.model

enum class StampTemplate(val displayName: String, val description: String) {
    CLASSIC_BOTTOM_BAR(
        "Classic Bottom Bar",
        "Dark bottom banner with map thumbnail, address, coordinates, and metadata."
    ),
    COMPACT_CORNER_BOX(
        "Compact Corner Box",
        "Semi-transparent card placed in the corner with mini map and key info."
    ),
    FULL_WIDTH_LARGE_MAP(
        "Full-Width Large Map",
        "Prominent map thumbnail beside comprehensive multi-line details."
    ),
    MINIMAL_TEXT_ONLY(
        "Minimal Text Only",
        "Clean, modern text overlay with drop shadow without map thumbnail."
    )
}

enum class StampPosition(val displayName: String) {
    BOTTOM("Bottom"),
    TOP("Top")
}

enum class MapStyle(val displayName: String, val attribution: String) {
    SATELLITE("Satellite", "© Esri, Maxar"),
    STREET("Street Map", "© OpenStreetMap")
}

enum class CoordinateFormat(val displayName: String) {
    DECIMAL_DEGREES("Decimal (DD)"),
    DMS("Degrees, Minutes, Seconds (DMS)")
}

enum class UnitSystem(val displayName: String) {
    METRIC("Metric (m, °C)"),
    IMPERIAL("Imperial (ft, °F)")
}

enum class CaptureQuality(val displayName: String, val jpegQuality: Int) {
    // 95 is visually indistinguishable from 100 but encodes ~2x faster at roughly half the file size.
    HIGH("High (95%)", 95),
    MEDIUM("Medium (90%)", 90),
    STANDARD("Standard (80%)", 80)
}

enum class ColorProfile(val displayName: String, val description: String) {
    VIBRANT("Vibrant (Rich Colors)", "Boosts contrast and saturation for rich, lifelike photos"),
    NATURAL("Natural", "Standard unenhanced sensor colors")
}

enum class AspectRatioChoice(val displayName: String, val ratio: Float) {
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_1_1("1:1", 1f)
}

enum class TimerOption(val displayName: String, val seconds: Int) {
    OFF("Off", 0),
    SEC_3("3s", 3),
    SEC_10("10s", 10)
}
