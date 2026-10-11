package app.southbound.feature.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.southbound.core.traffic.MessageSign

private val SIGN_BLACK = Color(0xFF0B0B0B)
private val SIGN_AMBER = Color(0xFFFFB300)
private val SIGN_AMBER_DIM = Color(0x66FFB300)

/**
 * A tapped state 511 message sign: what it says right now, amber on black in a
 * fixed-width face like the sign itself, with its road and direction. Pages
 * the sign flips through are split by a faint rule.
 */
@Composable
internal fun MessageSignCard(sign: MessageSign, onClose: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(width = 22.dp, height = 15.dp)
                        .background(SIGN_BLACK, RoundedCornerShape(3.dp))
                        .border(1.5.dp, SIGN_AMBER, RoundedCornerShape(3.dp)),
                )
                Text(sign.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_sign_close))
                }
            }
            val road = listOfNotNull(sign.roadway, sign.direction).joinToString(" · ")
            val updated = sign.updatedAt?.let {
                stringResource(
                    R.string.explore_incident_updated,
                    android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS),
                )
            }
            Text(
                listOfNotNull(stringResource(R.string.explore_sign_source, sign.source.label), road.ifEmpty { null }, updated).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp, bottom = 10.dp),
            )
            Column(
                Modifier
                    .padding(end = 8.dp)
                    .fillMaxWidth()
                    .background(SIGN_BLACK, RoundedCornerShape(6.dp))
                    .border(2.dp, Color(0xFF3A3A3A), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (sign.isBlank) {
                    Text(
                        stringResource(R.string.explore_sign_blank),
                        color = SIGN_AMBER_DIM,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                for (line in sign.lines) {
                    if (line.isEmpty()) {
                        // A new page of the sign's message.
                        Box(Modifier.padding(vertical = 4.dp).fillMaxWidth(0.6f).height(1.dp).background(SIGN_AMBER_DIM))
                    } else {
                        Text(
                            line,
                            color = SIGN_AMBER,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            letterSpacing = 1.sp,
                            lineHeight = 22.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
