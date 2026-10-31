package dev.buddy

import android.content.Context
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Color tokens (light and dark, following the system theme) ----

val Brand = Color(0xFFD97757)

/** Tints for the small rounded-square icons in rows. Restrained: mostly the brand colour, a few system-style hues. */
object Tint {
    val Brand = dev.buddy.Brand
    val Blue = Color(0xFF0A84FF)
    val Green = Color(0xFF34C759)
    val Orange = Color(0xFFFF9F0A)
    val Purple = Color(0xFFBF5AF2)
    val Grey = Color(0xFF8E8E93)
    val Red = Color(0xFFFF3B30)
}

@Immutable
class BuddyColors(
    val background: Color, val card: Color, val elevated: Color, val separator: Color,
    val label: Color, val labelSecondary: Color, val labelTertiary: Color,
    val accent: Color = Brand, val success: Color = Color(0xFF34C759), val error: Color = Color(0xFFFF3B30),
)

private val LightColors = BuddyColors(
    background = Color(0xFFF2F2F7), card = Color.White, elevated = Color.White, separator = Color(0x2E3C3C43),
    label = Color.Black, labelSecondary = Color(0x993C3C43), labelTertiary = Color(0x4D3C3C43),
)
private val DarkColors = BuddyColors(
    background = Color.Black, card = Color(0xFF1C1C1E), elevated = Color(0xFF2C2C2E), separator = Color(0x66545458),
    label = Color.White, labelSecondary = Color(0x99EBEBF5), labelTertiary = Color(0x4DEBEBF5),
)

val LocalBuddy = staticCompositionLocalOf { LightColors }

val colors: BuddyColors @Composable get() = LocalBuddy.current

// ---- Typography: an iOS-like scale on the system font ----

private fun t(size: Int, weight: FontWeight, line: Int) = TextStyle(fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)

private val BuddyTypography = Typography(
    headlineLarge = t(34, FontWeight.Bold, 41),      // large title
    titleLarge = t(22, FontWeight.SemiBold, 28),     // title
    titleMedium = t(17, FontWeight.SemiBold, 22),    // headline
    bodyLarge = t(17, FontWeight.Normal, 22),        // body
    bodyMedium = t(15, FontWeight.Normal, 20),       // subhead
    bodySmall = t(13, FontWeight.Normal, 18),        // footnote
    labelSmall = t(12, FontWeight.Normal, 16),       // caption
)

@Composable
fun BuddyTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkColors else LightColors
    val scheme = (if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = Color.White, background = c.background, onBackground = c.label,
        surface = c.card, onSurface = c.label, surfaceVariant = c.elevated, onSurfaceVariant = c.labelSecondary, outline = c.separator,
    )
    CompositionLocalProvider(LocalBuddy provides c) {
        MaterialTheme(
            colorScheme = scheme, typography = BuddyTypography,
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(20.dp)),
            content = content,
        )
    }
}

// ---- Motion: gentle springs, press feedback, and plain fades when the system asks for reduced motion ----

/** True when the user has switched animations off (Settings > Accessibility > Remove animations). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember { reduceMotion(context) }
}

fun reduceMotion(context: Context): Boolean =
    AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun <T> motion(): FiniteAnimationSpec<T> =
    if (rememberReduceMotion()) tween(0) else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)

/** A tap target that shrinks to 0.97 while pressed and gives a light haptic when tapped. */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val reduce = rememberReduceMotion()
    val scale by animateFloatAsState(if (pressed && !reduce) 0.97f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "press")
    return this.scale(scale).clickable(source, null) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
}

// ---- Building blocks ----

@Composable
fun ScreenTitle(text: String) = Text(
    text, style = MaterialTheme.typography.headlineLarge, color = colors.label,
    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp),
)

@Composable
fun SectionHeader(text: String) = Text(
    text.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.labelTertiary,
    modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 8.dp),
)

/** A grouped inset card: corner radius 20, rows inside separated by hairlines. */
@Composable
fun GroupCard(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.card),
    content = content,
)

@Composable
fun Hairline() = Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(colors.separator))

@Composable
fun Footnote(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = colors.labelSecondary,
    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
)

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color = Tint.Brand,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(tint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.label)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.labelSecondary)
        }
        trailing()
    }
}

@Composable
fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.labelTertiary)

@Composable
fun CheckMark(tint: Color = Brand) = Icon(Icons.Default.Check, null, tint = tint)

@Composable
fun ValueText(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.labelSecondary)

/** A small rounded status label: green when fine, red when not. */
@Composable
fun StatusChip(text: String, ok: Boolean) {
    val tint = if (ok) colors.success else colors.error
    Text(
        text, color = tint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun MinimalSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked, onChange,
    colors = SwitchDefaults.colors(
        checkedTrackColor = colors.accent, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
        uncheckedTrackColor = colors.separator, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent,
    ),
)

/** An iOS-style segmented control: a rounded track with a thumb that springs to the chosen segment. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val reduce = rememberReduceMotion()
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.separator.copy(alpha = 0.5f)).padding(2.dp),
    ) {
        val segment = maxWidth / options.size
        val offset by androidx.compose.animation.core.animateDpAsState(
            segment * selected, if (reduce) tween(0) else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), label = "thumb",
        )
        Box(Modifier.offset(x = offset).width(segment).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(colors.elevated))
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) {
                        if (i != selected) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSelect(i) }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal, color = colors.label)
                }
            }
        }
    }
}

/** A full-width pill button. */
@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, color: Color = colors.accent, onClick: () -> Unit) = Box(
    modifier.fillMaxWidth().height(54.dp).pressable(onClick).clip(RoundedCornerShape(27.dp)).background(color),
    contentAlignment = Alignment.Center,
) { Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium) }

/** A permission row with a live state: a green check once granted, else an Allow button. */
@Composable
fun PermissionRow(title: String, subtitle: String, icon: ImageVector, tint: Color, granted: Boolean, onAllow: () -> Unit) =
    SettingRow(title, subtitle, icon, tint) {
        if (granted) CheckMark(colors.success)
        else Text(
            "Allow", color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.accent).pressable(onAllow).padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
