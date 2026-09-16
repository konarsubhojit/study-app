package dev.studyflow.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The branded fallback palette (issue #17).
 *
 * Dynamic colour is preferred where the platform offers it, but it cannot be the only source of
 * truth: it does not exist below API 31, and screenshots, marketing material and the launcher icon
 * all need colours that do not change with the wallpaper. These tonal values are the StudyFlow
 * palette — an indigo primary with a teal secondary and an amber tertiary — and they are the only
 * place in the app where a colour literal is allowed to appear.
 */
private object BrandPalette {
    val Indigo10 = Color(0xFF00105C)
    val Indigo20 = Color(0xFF131E92)
    val Indigo30 = Color(0xFF2F3CAB)
    val Indigo40 = Color(0xFF4A57C6)
    val Indigo80 = Color(0xFFBBC3FF)
    val Indigo90 = Color(0xFFDEE0FF)

    val Teal10 = Color(0xFF00201A)
    val Teal20 = Color(0xFF00382F)
    val Teal30 = Color(0xFF005046)
    val Teal40 = Color(0xFF00695C)
    val Teal80 = Color(0xFF52DBC4)
    val Teal90 = Color(0xFF71F8E0)

    val Amber10 = Color(0xFF2A1800)
    val Amber20 = Color(0xFF462A00)
    val Amber30 = Color(0xFF643D00)
    val Amber40 = Color(0xFF855200)
    val Amber80 = Color(0xFFFFB95C)
    val Amber90 = Color(0xFFFFDDB3)

    val Red10 = Color(0xFF410002)
    val Red20 = Color(0xFF690005)
    val Red30 = Color(0xFF93000A)
    val Red40 = Color(0xFFBA1A1A)
    val Red80 = Color(0xFFFFB4AB)
    val Red90 = Color(0xFFFFDAD6)

    val Neutral10 = Color(0xFF1B1B1F)
    val Neutral20 = Color(0xFF303034)
    val Neutral30 = Color(0xFF46464A)
    val Neutral80 = Color(0xFFC7C6CA)
    val Neutral90 = Color(0xFFE4E1E6)
    val Neutral95 = Color(0xFFF2EFF4)
    val Neutral99 = Color(0xFFFFFBFF)

    val White = Color(0xFFFFFFFF)
}

/** Brand palette, light variant — used whenever dynamic colour is unavailable or switched off. */
internal val BrandLightColorScheme =
    lightColorScheme(
        primary = BrandPalette.Indigo40,
        onPrimary = BrandPalette.White,
        primaryContainer = BrandPalette.Indigo90,
        onPrimaryContainer = BrandPalette.Indigo10,
        secondary = BrandPalette.Teal40,
        onSecondary = BrandPalette.White,
        secondaryContainer = BrandPalette.Teal90,
        onSecondaryContainer = BrandPalette.Teal10,
        tertiary = BrandPalette.Amber40,
        onTertiary = BrandPalette.White,
        tertiaryContainer = BrandPalette.Amber90,
        onTertiaryContainer = BrandPalette.Amber10,
        error = BrandPalette.Red40,
        onError = BrandPalette.White,
        errorContainer = BrandPalette.Red90,
        onErrorContainer = BrandPalette.Red10,
        background = BrandPalette.Neutral99,
        onBackground = BrandPalette.Neutral10,
        surface = BrandPalette.Neutral99,
        onSurface = BrandPalette.Neutral10,
        surfaceVariant = BrandPalette.Neutral90,
        onSurfaceVariant = BrandPalette.Neutral30,
        outline = BrandPalette.Neutral30,
        outlineVariant = BrandPalette.Neutral80,
        inverseSurface = BrandPalette.Neutral20,
        inverseOnSurface = BrandPalette.Neutral95,
        inversePrimary = BrandPalette.Indigo80,
    )

/** Brand palette, dark variant. */
internal val BrandDarkColorScheme =
    darkColorScheme(
        primary = BrandPalette.Indigo80,
        onPrimary = BrandPalette.Indigo20,
        primaryContainer = BrandPalette.Indigo30,
        onPrimaryContainer = BrandPalette.Indigo90,
        secondary = BrandPalette.Teal80,
        onSecondary = BrandPalette.Teal20,
        secondaryContainer = BrandPalette.Teal30,
        onSecondaryContainer = BrandPalette.Teal90,
        tertiary = BrandPalette.Amber80,
        onTertiary = BrandPalette.Amber20,
        tertiaryContainer = BrandPalette.Amber30,
        onTertiaryContainer = BrandPalette.Amber90,
        error = BrandPalette.Red80,
        onError = BrandPalette.Red20,
        errorContainer = BrandPalette.Red30,
        onErrorContainer = BrandPalette.Red90,
        background = BrandPalette.Neutral10,
        onBackground = BrandPalette.Neutral90,
        surface = BrandPalette.Neutral10,
        onSurface = BrandPalette.Neutral90,
        surfaceVariant = BrandPalette.Neutral30,
        onSurfaceVariant = BrandPalette.Neutral80,
        outline = BrandPalette.Neutral80,
        outlineVariant = BrandPalette.Neutral30,
        inverseSurface = BrandPalette.Neutral90,
        inverseOnSurface = BrandPalette.Neutral20,
        inversePrimary = BrandPalette.Indigo40,
    )
