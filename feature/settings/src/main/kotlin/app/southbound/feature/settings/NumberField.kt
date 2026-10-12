package app.southbound.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/**
 * A decimal field that keeps what is typed and reports only values that parse and are above zero.
 * [decimals] is how many places the starting value shows ("25" for a whole mpg, "3.50" for a price).
 */
@Composable
internal fun SettingsNumberField(@StringRes label: Int, value: Double, decimals: Int, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(show(value, decimals)) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            typed.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let(onValue)
        },
        label = { Text(stringResource(label)) },
        // A number outside the setting's range is kept inside it: say what was saved rather than show the typed one alone.
        supportingText = text.replace(',', '.').toDoubleOrNull()
            ?.takeIf { it > 0 && abs(it - value) > SAME }
            ?.let { { Text(stringResource(R.string.settings_number_saved_as, show(value, decimals))) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Closer than this, a typed number is the stored one (a price typed as 3.5 is 3.50). */
private const val SAME = 1e-6

private fun show(v: Double, decimals: Int): String =
    if (decimals == 0 && v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.${decimals.coerceAtLeast(1)}f", v)
