package app.southbound.core.export

import app.southbound.core.network.MiniJson
import app.southbound.core.network.NetworkFeature
import app.southbound.core.packs.Regions
import app.southbound.core.routing.BikePrefs
import app.southbound.core.routing.BikeType
import app.southbound.core.routing.FuelSettings
import app.southbound.core.routing.GasRegion
import app.southbound.core.routing.PriceSource
import app.southbound.core.routing.RoutePrefs
import app.southbound.core.routing.VehicleKind
import app.southbound.core.routing.WalkPrefs
import app.southbound.core.voice.AudioOutput
import app.southbound.core.voice.BuiltInVoice
import app.southbound.core.voice.DetailLevel
import app.southbound.core.voice.DistanceStyle
import app.southbound.core.voice.GuidanceVolume
import app.southbound.core.voice.OtherAudio
import app.southbound.core.voice.SpeedAlertMode
import app.southbound.core.voice.VoiceEngine
import app.southbound.core.voice.VoiceMode
import app.southbound.core.voice.VoiceSettings

/**
 * `settings.json`: the preferences, in groups (network, route, driving, fuel, voice, packs, updates). Every group
 * and every field of the bundle's schema is required and nothing else is allowed (schema 2 added the route group's
 * `avoid_dot_cameras`, schema 3 the network group's `WEATHER`, schema 4 its `TRAFFIC_511PA` and `TRAFFIC_CTROADS`,
 * schema 5 its `TRAFFIC_CHART_MD` and `TRAFFIC_DELDOT`, schema 6 its `TRAFFIC_511WI` and `TRAFFIC_TRAVEL_MIDWEST`; an
 * older bundle reads with each off); an enum is its constant's name; a number
 * must be finite and inside the range the app's own setting enforces. Export runs the same check before writing.
 */
internal object SettingsEntry {
    /** The schema each newer network toggle arrived with; the rest were in schema 1. */
    private val NETWORK_SINCE = mapOf(
        NetworkFeature.WEATHER to 3L,
        NetworkFeature.TRAFFIC_511PA to 4L,
        NetworkFeature.TRAFFIC_CTROADS to 4L,
        NetworkFeature.TRAFFIC_CHART_MD to 5L,
        NetworkFeature.TRAFFIC_DELDOT to 5L,
        NetworkFeature.TRAFFIC_511WI to 6L,
        NetworkFeature.TRAFFIC_TRAVEL_MIDWEST to 6L,
    )

    private fun inSchema(feature: NetworkFeature, schema: Long): Boolean = (NETWORK_SINCE[feature] ?: 1L) <= schema

    /** The network toggles a bundle of [schema] lists. */
    private fun networkKeys(schema: Long): Set<String> = NetworkFeature.entries.filter { inSchema(it, schema) }.map { it.name }.toSet()
    private val ROUTE_KEYS_1 = setOf(
        "avoid_tolls", "avoid_highways", "avoid_ferries", "avoid_alpr", "vehicle", "height_ft", "width_ft", "length_ft",
        "weight_tons", "walk_avoid_stairs", "walk_step_free", "walk_prefer_paths", "bike_type", "bike_avoid_hills",
        "bike_avoid_busy_roads",
    )

    /** Schema 2: "Avoid DOT cameras". */
    private val ROUTE_KEYS = ROUTE_KEYS_1 + "avoid_dot_cameras"
    private val FUEL_KEYS = setOf("mpg", "price_per_gallon", "price_source", "region")
    private val VOICE_KEYS = setOf(
        "mode", "driving", "cycling", "walking", "transit", "engine", "street_names", "detail", "traffic_alerts",
        "speed_alerts", "reroute_announcement", "transit_stop_alerts", "volume", "other_audio", "output", "speech_rate",
        "distance_style", "built_in_voice", "instruction_chime", "alert_chime", "haptic_turns", "speed_camera_alerts",
        "alpr_camera_alerts", "school_zone_alerts",
    )
    private val GROUPS = setOf("network", "route", "driving", "fuel", "voice", "packs", "updates")

    private val REGION_IDS: Set<String> get() = Regions.basemaps.map { it.id }.toSet()

    fun encode(s: AppSettings): ByteArray {
        check(s)
        return MiniJson.stringify(tree(s)).toByteArray(Charsets.UTF_8)
    }

    /** How many values the settings hold, as the manifest counts them (the region list is one value). */
    fun count(s: AppSettings): Int = tree(s).values.sumOf { (it as Map<*, *>).size }

    /**
     * [count] as a bundle of [schema] lists them: schema 1 had no `avoid_dot_cameras`, schemas 1 and 2 no `WEATHER`,
     * schemas 1 to 3 neither `TRAFFIC_511PA` nor `TRAFFIC_CTROADS`, schemas 1 to 4 neither `TRAFFIC_CHART_MD` nor
     * `TRAFFIC_DELDOT`, schemas 1 to 5 neither `TRAFFIC_511WI` nor `TRAFFIC_TRAVEL_MIDWEST`.
     */
    fun count(s: AppSettings, schema: Long): Int =
        count(s) - (if (schema < 2) 1 else 0) - NetworkFeature.entries.count { !inSchema(it, schema) }

    private fun tree(s: AppSettings): Map<String, Any?> = linkedMapOf(
        "network" to NetworkFeature.entries.associate { it.name to (s.network[it] ?: false) },
        "route" to linkedMapOf<String, Any?>(
            "avoid_tolls" to s.route.avoidTolls,
            "avoid_highways" to s.route.avoidHighways,
            "avoid_ferries" to s.route.avoidFerries,
            "avoid_alpr" to s.route.avoidAlpr,
            "avoid_dot_cameras" to s.route.avoidDotCameras,
            "vehicle" to s.route.vehicle.name,
            "height_ft" to s.route.heightFt,
            "width_ft" to s.route.widthFt,
            "length_ft" to s.route.lengthFt,
            "weight_tons" to s.route.weightTons,
            "walk_avoid_stairs" to s.route.walk.avoidStairs,
            "walk_step_free" to s.route.walk.stepFree,
            "walk_prefer_paths" to s.route.walk.preferPaths,
            "bike_type" to s.route.bike.type.name,
            "bike_avoid_hills" to s.route.bike.avoidHills,
            "bike_avoid_busy_roads" to s.route.bike.avoidBusyRoads,
        ),
        "driving" to linkedMapOf<String, Any?>("night_by_sunset" to s.nightBySunset),
        "fuel" to linkedMapOf<String, Any?>(
            "mpg" to s.fuel.mpg,
            "price_per_gallon" to s.fuel.pricePerGallon,
            "price_source" to s.fuel.source.name,
            "region" to s.fuel.region.name,
        ),
        "voice" to linkedMapOf<String, Any?>(
            "mode" to s.voice.mode.name,
            "driving" to s.voice.driving,
            "cycling" to s.voice.cycling,
            "walking" to s.voice.walking,
            "transit" to s.voice.transit,
            "engine" to s.voice.engine?.name,
            "street_names" to s.voice.streetNames,
            "detail" to s.voice.detail.name,
            "traffic_alerts" to s.voice.trafficAlerts,
            "speed_alerts" to s.voice.speedAlerts.name,
            "reroute_announcement" to s.voice.rerouteAnnouncement,
            "transit_stop_alerts" to s.voice.transitStopAlerts,
            "volume" to s.voice.volume.name,
            "other_audio" to s.voice.otherAudio.name,
            "output" to s.voice.output.name,
            "speech_rate" to s.voice.speechRate,
            "distance_style" to s.voice.distanceStyle.name,
            "built_in_voice" to s.voice.builtInVoice.name,
            "instruction_chime" to s.voice.instructionChime,
            "alert_chime" to s.voice.alertChime,
            "haptic_turns" to s.voice.hapticTurns,
            "speed_camera_alerts" to s.voice.speedCameraAlerts,
            "alpr_camera_alerts" to s.voice.alprCameraAlerts,
            "school_zone_alerts" to s.voice.schoolZoneAlerts,
        ),
        "packs" to linkedMapOf<String, Any?>("regions" to s.regions.sorted()),
        "updates" to linkedMapOf<String, Any?>("allow_mobile_data" to s.allowMobileData),
    )

    /**
     * Reads settings written with bundle [schema] (1 to 6; schema 1 has no `avoid_dot_cameras`, schemas 1 and 2 no
     * `WEATHER` toggle, schemas 1 to 3 no `TRAFFIC_511PA` or `TRAFFIC_CTROADS`, schemas 1 to 4 no
     * `TRAFFIC_CHART_MD` or `TRAFFIC_DELDOT` and schemas 1 to 5 no `TRAFFIC_511WI` or `TRAFFIC_TRAVEL_MIDWEST`, each
     * read as off).
     */
    fun decode(bytes: ByteArray, schema: Long = SouthboundBundle.SCHEMA): AppSettings {
        val root = Obj.of(parseJson(bytes, "settings.json"), "settings", GROUPS)

        val net = root.obj("network", networkKeys(schema))
        val network = NetworkFeature.entries.associateWith { inSchema(it, schema) && net.bool(it.name) }

        val r = root.obj("route", if (schema >= 2) ROUTE_KEYS else ROUTE_KEYS_1)
        val route = RoutePrefs(
            avoidTolls = r.bool("avoid_tolls"),
            avoidHighways = r.bool("avoid_highways"),
            avoidFerries = r.bool("avoid_ferries"),
            avoidAlpr = r.bool("avoid_alpr"),
            avoidDotCameras = schema >= 2 && r.bool("avoid_dot_cameras"),
            vehicle = r.enum<VehicleKind>("vehicle"),
            heightFt = r.double("height_ft", 3.0, 16.0),
            widthFt = r.double("width_ft", 3.0, 12.0),
            lengthFt = r.double("length_ft", 5.0, 80.0),
            weightTons = r.double("weight_tons", 0.5, 80.0),
            walk = WalkPrefs(
                avoidStairs = r.bool("walk_avoid_stairs"),
                stepFree = r.bool("walk_step_free"),
                preferPaths = r.bool("walk_prefer_paths"),
            ),
            bike = BikePrefs(
                type = r.enum<BikeType>("bike_type"),
                avoidHills = r.bool("bike_avoid_hills"),
                avoidBusyRoads = r.bool("bike_avoid_busy_roads"),
            ),
        )

        val night = root.obj("driving", setOf("night_by_sunset")).bool("night_by_sunset")

        val f = root.obj("fuel", FUEL_KEYS)
        val fuel = FuelChoices(
            mpg = f.double("mpg", FuelSettings.MIN_MPG, FuelSettings.MAX_MPG),
            pricePerGallon = f.double("price_per_gallon", FuelSettings.MIN_PRICE, FuelSettings.MAX_PRICE),
            source = f.enum<PriceSource>("price_source"),
            region = f.enum<GasRegion>("region"),
        )

        val v = root.obj("voice", VOICE_KEYS)
        val voice = VoiceSettings(
            mode = v.enum<VoiceMode>("mode"),
            driving = v.bool("driving"),
            cycling = v.bool("cycling"),
            walking = v.bool("walking"),
            transit = v.bool("transit"),
            engine = v.enumOrNull<VoiceEngine>("engine"),
            streetNames = v.bool("street_names"),
            detail = v.enum<DetailLevel>("detail"),
            trafficAlerts = v.bool("traffic_alerts"),
            speedAlerts = v.enum<SpeedAlertMode>("speed_alerts"),
            rerouteAnnouncement = v.bool("reroute_announcement"),
            transitStopAlerts = v.bool("transit_stop_alerts"),
            volume = v.enum<GuidanceVolume>("volume"),
            otherAudio = v.enum<OtherAudio>("other_audio"),
            output = v.enum<AudioOutput>("output"),
            speechRate = v.double("speech_rate", VoiceSettings.MIN_SPEECH_RATE.toDouble(), VoiceSettings.MAX_SPEECH_RATE.toDouble()).toFloat(),
            distanceStyle = v.enum<DistanceStyle>("distance_style"),
            builtInVoice = v.enum<BuiltInVoice>("built_in_voice"),
            instructionChime = v.bool("instruction_chime"),
            alertChime = v.bool("alert_chime"),
            hapticTurns = v.bool("haptic_turns"),
            speedCameraAlerts = v.bool("speed_camera_alerts"),
            alprCameraAlerts = v.bool("alpr_camera_alerts"),
            schoolZoneAlerts = v.bool("school_zone_alerts"),
        )

        val packs = root.obj("packs", setOf("regions"))
        val regions = LinkedHashSet<String>()
        for (id in packs.list("regions", REGION_IDS.size)) {
            val region = id as? String ?: packs.bad("regions")
            if (region !in REGION_IDS || !regions.add(region)) packs.bad("regions")
        }

        val allowMobile = root.obj("updates", setOf("allow_mobile_data")).bool("allow_mobile_data")

        return AppSettings(network, route, night, fuel, voice, regions, allowMobile).also { check(it) }
    }

    /** Range checks for settings that did not come from [decode] (an export of what the stores hold). */
    fun check(s: AppSettings) {
        if (s.network.keys != NetworkFeature.entries.toSet()) invalid("network toggles are missing")
        val r = s.route
        if (!r.heightFt.isFinite() || !r.widthFt.isFinite() || !r.lengthFt.isFinite() || !r.weightTons.isFinite() ||
            r.sanitized() != r
        ) {
            invalid("a route dimension is outside its range")
        }
        val f = s.fuel
        if (!f.mpg.isFinite() || f.mpg !in FuelSettings.MIN_MPG..FuelSettings.MAX_MPG) invalid("mpg is outside its range")
        if (!f.pricePerGallon.isFinite() || f.pricePerGallon !in FuelSettings.MIN_PRICE..FuelSettings.MAX_PRICE) {
            invalid("the gas price is outside its range")
        }
        val rate = s.voice.speechRate
        if (!rate.isFinite() || rate < VoiceSettings.MIN_SPEECH_RATE || rate > VoiceSettings.MAX_SPEECH_RATE) {
            invalid("the speech rate is outside its range")
        }
        if (!REGION_IDS.containsAll(s.regions)) invalid("a map region is not one Southbound knows")
    }
}
