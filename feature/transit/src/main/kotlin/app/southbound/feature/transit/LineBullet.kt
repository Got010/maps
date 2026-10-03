package app.southbound.feature.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.southbound.core.transit.TransitRoute

/**
 * A line in its MTA colour: a round bullet for subway lines, a rounded tag
 * for buses and rail branches.
 */
@Composable
fun LineBullet(route: TransitRoute, modifier: Modifier = Modifier) {
    val background = TransitFormat.color(route.color) ?: MaterialTheme.colorScheme.secondary
    val foreground = TransitFormat.color(route.textColor) ?: TransitFormat.contrastOn(background)
    val label = route.label
    // "FX" and "6X" are the diamond express services; show the line letter.
    val shown = if (route.type == SUBWAY && label.length == 2 && label.endsWith("X")) label.dropLast(1) else label
    if (route.type == SUBWAY && shown.length <= 2) {
        Box(modifier.size(24.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
            Text(shown, color = foreground, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
        }
    } else {
        Box(
            modifier
                .defaultMinSize(minHeight = 24.dp)
                .background(background, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                shown,
                color = foreground,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val SUBWAY = 1
