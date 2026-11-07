package com.sameerasw.essentials.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.border
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sameerasw.essentials.data.repository.SettingsRepository
import kotlin.math.max

/**
 * Liquid Glass: content sits on translucent "glass" cards with a light specular edge, floating over
 * a soft backdrop tinted from the colour scheme (dynamic colour where available).
 *
 * The theme turns it on app-wide; pages paint the backdrop with [glassPageBackground] and cards pick
 * up the glass through the colour scheme ([liquidGlassColors]) and [liquidGlassEdge].
 */
data class LiquidGlassStyle(
    val enabled: Boolean,
    val dark: Boolean,
    val pitchBlack: Boolean,
)

val LocalLiquidGlass = staticCompositionLocalOf { LiquidGlassStyle(enabled = false, dark = false, pitchBlack = false) }

object LiquidGlass {
    val current: LiquidGlassStyle
        @Composable @ReadOnlyComposable get() = LocalLiquidGlass.current

    /** Glass fill alpha for cards and list items. */
    internal fun cardAlpha(dark: Boolean) = if (dark) 0.50f else 0.62f
}

/** Reads the Liquid Glass setting and follows changes to it. */
@Composable
internal fun rememberLiquidGlassSetting(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var enabled by remember(prefs) {
        mutableStateOf(prefs.getBoolean(SettingsRepository.KEY_LIQUID_GLASS_ENABLED, true))
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == SettingsRepository.KEY_LIQUID_GLASS_ENABLED) {
                enabled = p.getBoolean(SettingsRepository.KEY_LIQUID_GLASS_ENABLED, true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

/**
 * The glass colour scheme: cards ([ColorScheme.surfaceBright], the low containers) become
 * translucent so the backdrop shows through. Sheets, dialogs and menus (the high containers) stay
 * nearly opaque so text over other content stays legible.
 */
internal fun liquidGlassColors(base: ColorScheme, dark: Boolean): ColorScheme {
    val card = LiquidGlass.cardAlpha(dark)
    // Glass is a little lighter than the surface it replaces, like frosted glass catching light.
    val frost = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.35f)
    fun glass(c: Color, alpha: Float) = frost.compositeOver(c).copy(alpha = alpha)
    return base.copy(
        surfaceBright = glass(base.surfaceBright, card),
        surfaceContainerLowest = glass(base.surfaceContainerLowest, card),
        surfaceContainerHigh = glass(base.surfaceContainerHigh, 0.94f),
        surfaceContainerHighest = glass(base.surfaceContainerHighest, 0.96f),
    )
}

/**
 * Paints a page's backdrop: the scheme's page colour, with soft glows of the primary and tertiary
 * colours behind the glass. Plain [ColorScheme.surfaceContainer] when Liquid Glass is off.
 */
fun Modifier.glassPageBackground(): Modifier = composed {
    val glass = LocalLiquidGlass.current
    val scheme = MaterialTheme.colorScheme
    val base = scheme.surfaceContainer
    if (!glass.enabled) return@composed drawBehind { drawRect(base) }
    val glowStrength = when {
        glass.pitchBlack -> 0.22f
        glass.dark -> 0.30f
        else -> 0.42f
    }
    val primary = scheme.primary.copy(alpha = glowStrength)
    val tertiary = scheme.tertiary.copy(alpha = glowStrength * 0.85f)
    val secondary = scheme.secondaryContainer.copy(alpha = glowStrength)
    drawBehind {
        drawRect(base)
        val r = max(size.width, size.height)
        drawRect(
            Brush.radialGradient(
                listOf(primary, Color.Transparent),
                center = Offset(size.width * 0.1f, size.height * 0.05f),
                radius = r * 0.75f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(tertiary, Color.Transparent),
                center = Offset(size.width * 0.95f, size.height * 0.55f),
                radius = r * 0.65f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(secondary, Color.Transparent),
                center = Offset(size.width * 0.2f, size.height * 1.0f),
                radius = r * 0.6f,
            ),
        )
    }
}

/** The page fill to hand a Scaffold whose modifier carries [glassPageBackground]. */
val glassPageContainerColor: Color
    @Composable @ReadOnlyComposable get() =
        if (LocalLiquidGlass.current.enabled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer

/** The thin bright rim along a glass card's edge, brightest at the top where light catches it. */
fun Modifier.liquidGlassEdge(shape: Shape, width: Dp = 1.dp): Modifier = composed {
    val glass = LocalLiquidGlass.current
    if (!glass.enabled) return@composed this
    val onDark = glass.dark || MaterialTheme.colorScheme.surfaceContainer.luminance() < 0.4f
    val top = Color.White.copy(alpha = if (onDark) 0.22f else 0.75f)
    val bottom = Color.White.copy(alpha = if (onDark) 0.04f else 0.25f)
    border(width, Brush.verticalGradient(listOf(top, bottom)), shape)
}
