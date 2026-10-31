package dev.buddy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFD97757)
val Green = Color(0xFF34C759)

private val LightScheme = lightColorScheme(
    primary = Accent, onPrimary = Color.White, background = Color(0xFFF2F2F7), onBackground = Color(0xFF1C1C1E),
    surface = Color.White, onSurface = Color(0xFF1C1C1E), surfaceVariant = Color(0xFFE9E9EE),
    onSurfaceVariant = Color(0xFF6C6C70), outline = Color(0xFFD1D1D6),
)
private val DarkScheme = darkColorScheme(
    primary = Accent, onPrimary = Color.White, background = Color.Black, onBackground = Color.White,
    surface = Color(0xFF1C1C1E), onSurface = Color.White, surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF98989F), outline = Color(0xFF3A3A3C),
)

/** Clean, minimal, grouped-card styling that follows the system light/dark setting. */
@Composable
fun BuddyTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
    shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)),
    content = content,
)

@Composable
fun LargeTitle(text: String) =
    Text(text, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

@Composable
fun SectionLabel(text: String) = Text(
    text.uppercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 6.dp),
)

/** An inset rounded card that holds rows. */
@Composable
fun Group(content: @Composable ColumnScope.() -> Unit) = Surface(
    shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(),
) { Column(content = content) }

@Composable
fun RowDivider() =
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Accent,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(iconTint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun Chevron() = Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun CheckMark(tint: Color = Accent) = Icon(Icons.Default.Check, null, tint = tint)

@Composable
fun Note(text: String) = Text(
    text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
)

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) = Button(
    onClick, modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

/** A permission row with a live state: a green check once granted, else an Allow button. */
@Composable
fun PermissionRow(title: String, subtitle: String, icon: ImageVector, granted: Boolean, onAllow: () -> Unit) =
    SettingRow(title, subtitle, icon) {
        if (granted) CheckMark(Green) else TextButton(onClick = onAllow) { Text("Allow", color = Accent, fontWeight = FontWeight.SemiBold) }
    }

@Suppress("unused")
private val spacing = Arrangement.spacedBy(8.dp)
