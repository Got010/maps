package app.southbound.feature.explore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.southbound.core.network.NetworkFeatureDisabledException
import app.southbound.core.network.NetworkGate
import app.southbound.core.traffic.Hls
import app.southbound.core.traffic.MiDrive
import app.southbound.core.traffic.TrafficCamera
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Date

/** Camera stills are a few tens of kB; anything far bigger isn't one. */
private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

/** What happened to the last image request. */
private sealed interface StillState {
    data object Loading : StillState
    data class Shown(val bitmap: Bitmap, val fetchedAt: Long) : StillState
    data class Failed(val reason: Reason, val detail: String? = null) : StillState

    enum class Reason { TURNED_OFF, NETWORK, NOT_AN_IMAGE }
}

/**
 * A tapped state 511 traffic camera: its name, road and direction, and its latest
 * still. The still is fetched through [gate] under its 511's toggle (511NY, 511PA,
 * CTroads, CHART, DelDOT) from the camera's own image link (which names the camera,
 * never the viewer) when the card opens and on Refresh. A camera that only streams
 * video (CHART's, DelDOT's) shows a frame of its newest few seconds instead
 * ([videoFrame]). An offline camera says so and fetches nothing.
 */
@Composable
internal fun TrafficCameraCard(camera: TrafficCamera, gate: NetworkGate, onClose: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var reloads by remember(camera.id) { mutableIntStateOf(0) }
    var state by remember(camera.id) { mutableStateOf<StillState>(StillState.Loading) }
    var busy by remember(camera.id) { mutableStateOf(false) }
    LaunchedEffect(camera.id, camera.imageUrl, camera.videoUrl, camera.stillVia, reloads) {
        val url = camera.imageUrl?.takeIf { it.isNotBlank() }
        val video = camera.videoUrl?.takeIf { it.isNotBlank() }
        val via = camera.stillVia?.takeIf { it.isNotBlank() }
        if (!camera.hasImage || (url == null && video == null && via == null)) return@LaunchedEffect
        // Keep the old picture on screen while a refresh loads; only the first load shows a spinner.
        if (state !is StillState.Shown) state = StillState.Loading
        busy = true
        state = try {
            val bitmap = withContext(Dispatchers.IO) {
                // Mi Drive's list names no still: its camera's detail page does (the same toggle, the same rules).
                val still = url ?: via?.let { MiDrive.stillOf(gate.get(camera.source.feature, it).decodeToString()) }
                if (still == null) {
                    videoFrame(gate, camera, video ?: return@withContext null)
                } else {
                    val bytes = gate.get(camera.source.feature, still)
                    if (bytes.size > MAX_IMAGE_BYTES) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            if (bitmap == null) StillState.Failed(StillState.Reason.NOT_AN_IMAGE) else StillState.Shown(bitmap, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkFeatureDisabledException) {
            StillState.Failed(StillState.Reason.TURNED_OFF)
        } catch (e: IOException) {
            StillState.Failed(StillState.Reason.NETWORK, e.message)
        } catch (e: IllegalArgumentException) {
            // A malformed link in the feed.
            StillState.Failed(StillState.Reason.NETWORK, e.message)
        } finally {
            busy = false
        }
    }

    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (camera.hasImage) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    contentDescription = null,
                    tint = Color(0xFF00897B),
                    modifier = Modifier.size(20.dp),
                )
                Text(camera.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.explore_traffic_camera_close))
                }
            }
            val road = listOfNotNull(camera.roadway, camera.direction).joinToString(" · ")
            Text(
                listOf(stringResource(R.string.explore_traffic_camera_source, camera.source.label), road).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
            )
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val s = state
                if (!camera.hasImage) {
                    Text(
                        stringResource(R.string.explore_traffic_camera_offline),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else when (s) {
                    is StillState.Loading -> CircularProgressIndicator(color = Color.White)
                    is StillState.Shown -> Image(
                        bitmap = remember(s.bitmap) { s.bitmap.asImageBitmap() },
                        contentDescription = stringResource(R.string.explore_traffic_camera_image, camera.name),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    is StillState.Failed -> Text(
                        when (s.reason) {
                            StillState.Reason.TURNED_OFF -> stringResource(R.string.explore_traffic_camera_turned_off, camera.source.label)
                            StillState.Reason.NOT_AN_IMAGE -> stringResource(R.string.explore_traffic_camera_not_image, camera.source.label)
                            StillState.Reason.NETWORK -> stringResource(R.string.explore_traffic_camera_failed, s.detail ?: "no connection")
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (camera.hasImage) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shown = state as? StillState.Shown
                    Text(
                        when {
                            busy -> stringResource(R.string.explore_traffic_camera_loading)
                            shown != null -> stringResource(
                                R.string.explore_traffic_camera_fetched,
                                DateFormat.getTimeFormat(context).format(Date(shown.fetchedAt)),
                            )
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { reloads++ }, enabled = !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.explore_traffic_camera_refresh), Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

/**
 * One frame of a camera that only streams video: its playlist, the media playlist it names, then the newest segment
 * (a few seconds of MPEG-TS, a few hundred kB), each through [gate] under the camera's toggle, and the segment's
 * last frame decoded on the phone. Null when the segment holds no frame the phone can decode.
 *
 * @throws IOException when a request fails; IllegalArgumentException when a playlist isn't one.
 */
private fun videoFrame(gate: NetworkGate, camera: TrafficCamera, playlistUrl: String): Bitmap? {
    var url = playlistUrl
    // A master playlist, then a media playlist: never more than that.
    repeat(2) {
        val playlist = gate.get(camera.source.feature, url)
        if (playlist.size > Hls.MAX_PLAYLIST_BYTES) throw IllegalArgumentException("a video playlist far too big")
        when (val next = Hls.next(url, playlist.decodeToString())) {
            is Hls.Next.Playlist -> url = next.url
            is Hls.Next.Segment -> {
                val segment = gate.get(camera.source.feature, next.url, timeoutMillis = 30_000)
                if (segment.size > Hls.MAX_SEGMENT_BYTES) return null
                return lastFrame(segment)
            }
        }
    }
    throw IllegalArgumentException("a video playlist that names no video")
}

/** The last frame of a short MPEG-TS segment, read from memory (nothing written to storage). */
private fun lastFrame(segment: ByteArray): Bitmap? = MediaMetadataRetriever().use { retriever ->
    try {
        retriever.setDataSource(BytesDataSource(segment))
        val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000)
        durationUs?.let { retriever.getFrameAtTime(it, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC) }
            ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (e: RuntimeException) {
        // MediaMetadataRetriever throws IllegalArgumentException or RuntimeException for data it can't read.
        null
    }
}

private class BytesDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() {}
}
