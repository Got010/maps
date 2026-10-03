package app.southbound.core.routing

import uniffi.ferrostar.LaneInfo
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.VisualInstruction

/**
 * Lane guidance from Ferrostar's route model. A step's banners announce the
 * maneuver at the END of the step, and Valhalla adds its lanes to them as a
 * `sub` banner (from 400 m out, or on the step's only banner when it is
 * shorter). Ferrostar 0.57 parses those into `subContent.laneInfo`; its
 * `primaryContent.laneInfo` is always null for OSRM responses.
 */
object StepLanes {

    /** The lanes for the maneuver ending [step], from any of its banners; empty when Valhalla gave none. */
    fun of(step: RouteStep): List<Lane> {
        for (banner in step.visualInstructions) {
            val lanes = of(banner)
            if (lanes.isNotEmpty()) return lanes
        }
        return emptyList()
    }

    /** The lanes one banner carries; empty when it has none. */
    fun of(banner: VisualInstruction): List<Lane> {
        val info: List<LaneInfo> = banner.subContent?.laneInfo?.takeIf { it.isNotEmpty() }
            ?: banner.primaryContent.laneInfo?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return info.map { Lanes.fromBanner(it.active, it.directions, it.activeDirection) }
    }
}
