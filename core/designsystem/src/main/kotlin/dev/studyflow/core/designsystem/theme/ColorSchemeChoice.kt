package dev.studyflow.core.designsystem.theme

/**
 * Which of the four colour schemes a theme request resolves to (issue #17).
 *
 * The decision is a pure function so it can be asserted on the JVM: dynamic colour is a platform
 * capability, and "wallpaper colours when the device has them, the brand palette otherwise" is
 * exactly the kind of branch that silently regresses when it only exists inside a `@Composable`.
 */
public enum class ColorSchemeChoice {
    DynamicLight,
    DynamicDark,
    BrandLight,
    BrandDark,
    ;

    /** True when the scheme is the dark variant, whatever its source. */
    public val isDark: Boolean
        get() = this == DynamicDark || this == BrandDark

    /** True when the scheme is derived from the user's wallpaper rather than the brand palette. */
    public val isDynamic: Boolean
        get() = this == DynamicLight || this == DynamicDark

    public companion object {
        /**
         * Resolves a theme request.
         *
         * @param darkTheme whether the dark variant was asked for, usually from the system setting.
         * @param dynamicColorRequested the user's preference, which may not be satisfiable.
         * @param dynamicColorSupported whether the platform can supply wallpaper colours (API 31+).
         */
        public fun of(
            darkTheme: Boolean,
            dynamicColorRequested: Boolean,
            dynamicColorSupported: Boolean,
        ): ColorSchemeChoice =
            when {
                dynamicColorRequested && dynamicColorSupported && darkTheme -> DynamicDark
                dynamicColorRequested && dynamicColorSupported -> DynamicLight
                darkTheme -> BrandDark
                else -> BrandLight
            }
    }
}
