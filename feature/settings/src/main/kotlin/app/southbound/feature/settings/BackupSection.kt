package app.southbound.feature.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.southbound.core.network.NetworkFeature
import kotlinx.coroutines.CoroutineScope
import app.southbound.core.export.BackupCounts
import app.southbound.core.export.BackupService
import app.southbound.core.export.ResumeOutcome
import app.southbound.core.export.SouthboundBundle
import app.southbound.core.export.fwx.Fwx
import app.southbound.core.export.fwx.FwxHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Where the Export and Import dialogs are. Nothing here holds a passphrase: the dialogs keep their own text. */
private sealed interface Step {
    data object Idle : Step
    data class ExportPassphrase(val uri: Uri) : Step
    data object ImportNotice : Step
    data class ImportPassphrase(val uri: Uri, val header: FwxHeader) : Step
    data class Working(@StringRes val message: Int) : Step

    /** A result to read; [applied] means an import changed the stores, so the settings screen is rebuilt on OK. */
    data class Message(val text: String, val applied: Boolean = false) : Step
}

/**
 * Settings > Backup: export everything Southbound keeps for you (saved places, lists, recents, Home and Work, API
 * keys, preferences) to one passphrase-sealed file, and import such a file. The file is picked with the system
 * file picker, which needs no permission. The passphrase is typed here, never stored, and never part of any saved
 * state; the work runs off the main thread.
 */
@Composable
internal fun BackupSection(service: BackupService, onImported: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val transfer = remember(service) { BackupTransfer(context, service) }
    var step by remember { mutableStateOf<Step>(Step.Idle) }
    var pending by remember { mutableStateOf(service.hasPending()) }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) step = Step.ExportPassphrase(uri)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            step = Step.Working(R.string.backup_checking)
            scope.launch {
                val header = withContext(Dispatchers.IO) { transfer.peek(uri) }
                step = header.fold(
                    onSuccess = { Step.ImportPassphrase(uri, it) },
                    onFailure = { Step.Message(transfer.importFailure(it)) },
                )
            }
        }
    }
    // Leaving Settings with the passphrase dialog open: remove the empty document the picker made.
    val latestStep by rememberUpdatedState(step)
    DisposableEffect(transfer) {
        onDispose {
            (latestStep as? Step.ExportPassphrase)?.let { exporting -> Thread { transfer.discardIfEmpty(exporting.uri) }.start() }
        }
    }

    Section(R.string.backup_title)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_body), style = MaterialTheme.typography.bodyMedium)
        if (pending) {
            Text(stringResource(R.string.backup_pending), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    step = Step.Working(R.string.backup_finishing)
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { service.resume() }
                        pending = service.hasPending()
                        step = when (outcome) {
                            is ResumeOutcome.Applied -> Step.Message(resources.getString(R.string.backup_finished, summary(resources, outcome.counts), networkSentence(resources, outcome.turnedOn, done = true)), applied = true)
                            is ResumeOutcome.Failed -> Step.Message(resources.getString(R.string.backup_finish_failed, outcome.reason))
                            ResumeOutcome.NothingPending -> Step.Idle
                        }
                    }
                }) { Text(stringResource(R.string.backup_finish)) }
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { service.discardPending() } }
                        pending = service.hasPending()
                    }
                }) { Text(stringResource(R.string.backup_discard)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val reason = service.blocker()
                if (reason != null) {
                    step = Step.Message(resources.getString(R.string.backup_refused, reason))
                } else {
                    createDocument.launch(SouthboundBundle.fileName(System.currentTimeMillis()))
                }
            }) { Text(stringResource(R.string.backup_export)) }
            OutlinedButton(onClick = {
                val reason = service.blocker()
                step = if (reason != null) Step.Message(resources.getString(R.string.backup_refused, reason)) else Step.ImportNotice
            }) { Text(stringResource(R.string.backup_import)) }
        }
    }

    when (val current = step) {
        Step.Idle -> Unit
        is Step.ExportPassphrase -> ExportDialog(
            onCancel = {
                scope.launch(Dispatchers.IO) { transfer.discardIfEmpty(current.uri) }
                step = Step.Idle
            },
            onStart = { passphrase ->
                step = Step.Working(R.string.backup_exporting)
                scope.launchWiping(passphrase) {
                    val result = withContext(Dispatchers.IO) { transfer.export(current.uri, passphrase) }
                    step = Step.Message(
                        when (result) {
                            is ExportResult.Done -> resources.getString(
                                R.string.backup_exported,
                                summary(resources, result.counts),
                                result.size?.let { Formatter.formatShortFileSize(context, it) } ?: "?",
                            )
                            is ExportResult.Failed -> resources.getString(
                                when (result.cleanup) {
                                    Cleanup.DELETED -> R.string.backup_export_failed
                                    Cleanup.UNTOUCHED -> R.string.backup_export_failed_untouched
                                    Cleanup.NOT_DELETED -> R.string.backup_export_failed_kept
                                },
                                result.reason,
                            )
                        },
                    )
                }
            },
        )
        Step.ImportNotice -> AlertDialog(
            onDismissRequest = { step = Step.Idle },
            title = { Text(stringResource(R.string.backup_import_title)) },
            text = { Text(stringResource(R.string.backup_import_replaces)) },
            confirmButton = {
                TextButton(onClick = {
                    step = Step.Idle
                    openDocument.launch(arrayOf("*/*"))
                }) { Text(stringResource(R.string.backup_import_choose)) }
            },
            dismissButton = { TextButton(onClick = { step = Step.Idle }) { Text(stringResource(R.string.backup_cancel)) } },
        )
        is Step.ImportPassphrase -> ImportDialog(
            header = current.header,
            onCancel = { step = Step.Idle },
            onStart = { passphrase ->
                step = Step.Working(R.string.backup_importing)
                scope.launchWiping(passphrase) {
                    val result = withContext(Dispatchers.IO) { transfer.import(current.uri, passphrase) }
                    pending = service.hasPending()
                    step = when (result) {
                        is ImportResult.Applied -> Step.Message(
                            resources.getString(
                                R.string.backup_imported,
                                summary(resources, result.counts),
                                networkSentence(resources, result.turnedOn, done = true),
                            ),
                            applied = true,
                        )
                        is ImportResult.Pending -> Step.Message(
                            resources.getString(
                                R.string.backup_import_pending,
                                result.reason,
                                networkSentence(resources, result.turnedOn, done = false),
                            ),
                            applied = false,
                        )
                        is ImportResult.Refused -> Step.Message(result.message)
                    }
                }
            },
        )
        is Step.Working -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = { Text(stringResource(current.message)) },
        )
        is Step.Message -> AlertDialog(
            onDismissRequest = {},
            text = { Text(current.text) },
            confirmButton = {
                TextButton(onClick = {
                    step = Step.Idle
                    if (current.applied) onImported()
                }) { Text(stringResource(R.string.backup_ok)) }
            },
        )
    }
}

@Composable
private fun ExportDialog(onCancel: () -> Unit, onStart: (CharArray) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_passphrase_help, Fwx.MIN_WRITER_PASSPHRASE_CODE_POINTS))
                PassphraseField(first, R.string.backup_passphrase) { first = it }
                PassphraseField(second, R.string.backup_passphrase_again) { second = it }
                problem?.let {
                    Text(
                        stringResource(it, Fwx.MIN_WRITER_PASSPHRASE_CODE_POINTS),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    first != second -> problem = R.string.backup_passphrase_mismatch
                    codePointsAfterNfc(first) < Fwx.MIN_WRITER_PASSPHRASE_CODE_POINTS -> problem = R.string.backup_passphrase_short
                    else -> {
                        val chars = first.toCharArray()
                        first = ""
                        second = ""
                        onStart(chars)
                    }
                }
            }) { Text(stringResource(R.string.backup_export_start)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun ImportDialog(header: FwxHeader, onCancel: () -> Unit, onStart: (CharArray) -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    val local = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(header.createdMs))
    val utc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(header.createdMs))
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Read from the file's plaintext header: anyone who can edit the file can set these.
                Text(stringResource(R.string.backup_import_header, local, utc, header.schemaVersion))
                Text(stringResource(R.string.backup_import_replaces))
                PassphraseField(passphrase, R.string.backup_passphrase) { passphrase = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars = passphrase.toCharArray()
                    passphrase = ""
                    onStart(chars)
                },
                enabled = passphrase.isNotEmpty(),
            ) { Text(stringResource(R.string.backup_import_start)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun PassphraseField(value: String, @StringRes label: Int, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Runs [block] and zeroes [passphrase] when it ends, however it ends: finished, failed, or cancelled with the
 * composition's scope (also when it was cancelled before it started, which a finally inside would miss).
 */
private fun CoroutineScope.launchWiping(passphrase: CharArray, block: suspend () -> Unit) {
    val job = launch {
        try {
            block()
        } finally {
            passphrase.fill('\u0000')
        }
    }
    job.invokeOnCompletion { passphrase.fill('\u0000') }
}

/** Which network features an import switched on (or will, when [done] is false), by name; "none" when it changed no toggle to on. */
private fun networkSentence(resources: android.content.res.Resources, turnedOn: List<NetworkFeature>, done: Boolean): String =
    if (turnedOn.isEmpty()) {
        resources.getString(if (done) R.string.backup_network_none else R.string.backup_network_will_none)
    } else {
        resources.getString(
            if (done) R.string.backup_network_on else R.string.backup_network_will_on,
            turnedOn.joinToString(", ") { resources.getString(networkLabel(it)) },
        )
    }

@StringRes
private fun networkLabel(feature: NetworkFeature): Int = when (feature) {
    NetworkFeature.PACK_DOWNLOADS -> R.string.backup_net_pack_downloads
    NetworkFeature.APP_UPDATES -> R.string.backup_net_app_updates
    NetworkFeature.TRAFFIC_511NY -> R.string.backup_net_511
    NetworkFeature.TRAFFIC_NYC_DOT -> R.string.backup_net_dot
    NetworkFeature.TRAFFIC_TOMTOM -> R.string.backup_net_tomtom
    NetworkFeature.TRANSIT_REALTIME -> R.string.backup_net_transit
    NetworkFeature.TRANSIT_BUS_TIME -> R.string.backup_net_bus
    NetworkFeature.GAS_PRICES -> R.string.backup_net_gas
    NetworkFeature.WEATHER -> R.string.backup_net_weather
}

/** Counts only: no place, list or key name, and never a key. */
private fun summary(resources: android.content.res.Resources, c: BackupCounts): String = resources.getString(
    R.string.backup_summary, c.savedPlaces, c.lists, c.recents, c.home + c.work, c.apiKeys, c.settings,
)
