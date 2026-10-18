package app.southbound.core.export

import app.southbound.core.network.ApiKey
import app.southbound.core.network.NetworkFeature
import app.southbound.core.network.SecretUnreadableException
import app.southbound.core.routing.BikePrefs
import app.southbound.core.routing.BikeType
import app.southbound.core.routing.GasRegion
import app.southbound.core.routing.PriceSource
import app.southbound.core.routing.RoutePrefs
import app.southbound.core.routing.VehicleKind
import app.southbound.core.routing.WalkPrefs
import app.southbound.core.userdata.LoadState
import app.southbound.core.userdata.PlaceRef
import app.southbound.core.userdata.PlainSealer
import app.southbound.core.userdata.Sealer
import app.southbound.core.userdata.Shortcut
import app.southbound.core.userdata.UserData
import app.southbound.core.userdata.UserDataStore
import app.southbound.core.voice.AudioOutput
import app.southbound.core.voice.BuiltInVoice
import app.southbound.core.voice.GuidanceVolume
import app.southbound.core.voice.SpeedAlertMode
import app.southbound.core.voice.VoiceEngine
import app.southbound.core.voice.VoiceMode
import app.southbound.core.voice.VoiceSettings
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Test data. The names and keys are made up and easy to search for in bytes. */
object Samples {
    const val PASSPHRASE = "correct horse battery staple"
    const val CAFE_NAME = "Joe's Distinctive Cafe"
    const val BUS_KEY = "mta-bus-key-0123456789"
    const val GH_TOKEN = "fake-token-for-tests-0001"

    const val WIFI_WORKED = "n7001"
    const val WIFI_FAILED = "w7002"

    val cafe = PlaceRef(CAFE_NAME, 40.7, -74.0, "1 Main St", "cafe", "n1")
    val pin = PlaceRef("Dropped pin", 40.75, -73.98)
    val home = PlaceRef("Home", 40.6501, -73.9496, "9 Elm Rd")
    val work = PlaceRef("Work", 40.7527, -73.9772, osmRef = "w42")

    fun userData(): UserData {
        var d = UserData().save(cafe, UserData.FAVORITES_ID, 100).save(pin, UserData.WANT_TO_GO_ID, 200).createList("Pizza")
        d = d.save(PlaceRef("Slice", 40.73, -74.0, category = "restaurant", osmRef = "n99"), d.lists.last().id, 300)
        d = d.recordRecent(pin, 400).recordRecent(cafe, 500)
        // Two Wi-Fi marks, one each way (synthetic OSM refs).
        d = d.markWifi(WIFI_WORKED, worked = true, now = 600).markWifi(WIFI_FAILED, worked = false, now = 700)
        return d.withShortcut(Shortcut.HOME, home).withShortcut(Shortcut.WORK, work)
    }

    fun keys(): Map<ApiKey, String> = linkedMapOf(
        ApiKey.MTA_BUS_TIME to BUS_KEY,
        ApiKey.NY_511 to "511-key-abcdef",
        ApiKey.GITHUB_RELEASES to GH_TOKEN,
    )

    fun defaultSettings() = AppSettings(
        network = NetworkFeature.entries.associateWith { it.enabledByDefault },
        route = RoutePrefs(),
        nightBySunset = true,
        fuel = FuelChoices(25.0, 3.5, PriceSource.MANUAL, GasRegion.NY),
        voice = VoiceSettings(),
        regions = setOf("nyc"),
        allowMobileData = false,
        searchAtBottom = true,
        wifi = false,
    )

    /** Every setting away from its default, with values that are floats in the real stores. */
    fun settings() = AppSettings(
        network = NetworkFeature.entries.associateWith { !it.enabledByDefault },
        route = RoutePrefs(
            avoidTolls = true, avoidHighways = true, avoidFerries = true, avoidAlpr = true, avoidDotCameras = true, vehicle = VehicleKind.TRUCK,
            heightFt = 11.5, widthFt = 8.0, lengthFt = 53.0, weightTons = 33.5,
            walk = WalkPrefs(avoidStairs = true, stepFree = true, preferPaths = true),
            bike = BikePrefs(BikeType.MOUNTAIN, avoidHills = true, avoidBusyRoads = true),
        ),
        nightBySunset = false,
        fuel = FuelChoices(31.5, 4.25, PriceSource.EIA, GasRegion.NJ),
        voice = VoiceSettings(
            mode = VoiceMode.ALERTS_ONLY, driving = false, cycling = false, walking = true, transit = false,
            engine = VoiceEngine.BUILT_IN, streetNames = false, speedAlerts = SpeedAlertMode.BEEP,
            volume = GuidanceVolume.LOUDER, output = AudioOutput.PHONE_SPEAKER, speechRate = 1.1f,
            builtInVoice = BuiltInVoice.NORMAN, hapticTurns = true, schoolZoneAlerts = false,
        ),
        regions = setOf("nyc", "li", "njn"),
        allowMobileData = true,
        searchAtBottom = false,
        wifi = true,
    )

    fun data() = BackupData(userData(), keys(), settings())

    fun emptyData() = BackupData(UserData(), emptyMap(), defaultSettings())

    fun contains(haystack: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..haystack.size - n.size) {
            for (j in n.indices) if (haystack[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }
}

/** AES-GCM under a random key: a stand-in for the Keystore sealer, so a file can be checked for plaintext. */
class AesSealer : Sealer {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
}

/**
 * The phone's stores in memory, except the saved places, which are the real [UserDataStore] on a temp file. [apply]
 * can be made to stop part-way, as a full disk or a stopped process would.
 */
class FakeStores(dir: File, clock: () -> Long = { 1_000L }) : BackupStores {
    val places = UserDataStore(File(dir, "places.sealed"), PlainSealer, clock)
    var keys: Map<ApiKey, String> = emptyMap()
    var settings: AppSettings = Samples.defaultSettings()

    /** Throws an IOException after the places are replaced and before the keys and settings are. */
    var failApply = false

    /** Throws an IOException after the places and keys are replaced and before the preferences are: a failed preference commit. */
    var failSettings = false
    var applyCalls = 0

    /** A saved API key that the Keystore will not decrypt. */
    var keysUnreadable = false

    override fun blocker(): String? =
        if (places.loadState.value is LoadState.Unreadable) "The saved places file can't be read." else null

    override suspend fun ensureReadable() {
        blocker()?.let { throw BackupRefusedException(it) }
        if (keysUnreadable) throw BackupRefusedException("A saved API key can't be read.")
    }

    override suspend fun read(): BackupData {
        blocker()?.let { throw BackupRefusedException(it) }
        if (keysUnreadable) throw SecretUnreadableException(ApiKey.EIA)
        return BackupData(places.data.value, keys, settings)
    }

    override suspend fun apply(data: BackupData) {
        applyCalls++
        places.update(explicit = false) { data.userData }
        if (failApply) throw IOException("disk full")
        keys = data.apiKeys
        if (failSettings) throw IOException("commit failed")
        settings = data.settings
    }

    override fun networkToggles(): Map<NetworkFeature, Boolean> = settings.network

    override fun wifiWanted(): Boolean = settings.wifi

    /** Everything the stores hold, compared by value. */
    fun snapshot(): List<Any> = listOf(places.data.value, keys, settings)
}
