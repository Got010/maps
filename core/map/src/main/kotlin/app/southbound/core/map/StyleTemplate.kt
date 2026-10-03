package app.southbound.core.map

import app.southbound.core.network.MiniJson
import java.io.File

/**
 * The bundled styles name their tile source `{{PMTILES_URL}}`. This swaps in the
 * installed basemap pack so the style points at a local file and nothing else.
 *
 * With several regional basemaps installed, the one source becomes one per
 * pack and every layer drawn from it is repeated per pack, so the regions
 * draw as one map. Regions overlap only along their edges, and MapLibre's
 * label collision runs across layers, so nothing shows twice.
 */
object StyleTemplate {
    const val PMTILES_URL_PLACEHOLDER = "{{PMTILES_URL}}"

    fun pmtilesUrl(basemap: File): String = "pmtiles://file://${basemap.absolutePath}"

    fun resolve(template: String, basemap: File): String {
        require(PMTILES_URL_PLACEHOLDER in template) {
            "Style has no $PMTILES_URL_PLACEHOLDER source"
        }
        return template.replace(PMTILES_URL_PLACEHOLDER, pmtilesUrl(basemap))
    }

    /**
     * The style for [basemaps] (at least one). One pack keeps the style as it
     * is; more get a source and a copy of each layer per pack, with the pack's
     * region in the ids: `road-minor@hudson`.
     */
    fun resolve(template: String, basemaps: List<Pair<String, File>>): String {
        require(basemaps.isNotEmpty()) { "No basemap" }
        if (basemaps.size == 1) return resolve(template, basemaps.single().second)
        require(PMTILES_URL_PLACEHOLDER in template) { "Style has no $PMTILES_URL_PLACEHOLDER source" }
        val root = LinkedHashMap(MiniJson.parse(template) as Map<*, *>)
        val sources = root["sources"] as Map<*, *>
        val (sourceId, source) = sources.entries.single { (_, v) -> (v as Map<*, *>)["url"] == PMTILES_URL_PLACEHOLDER }
        val newSources = LinkedHashMap<String, Any?>()
        for ((k, v) in sources) if (k != sourceId) newSources[k.toString()] = v
        for ((region, file) in basemaps) {
            newSources["$sourceId@$region"] = LinkedHashMap(source as Map<*, *>).also { it["url"] = pmtilesUrl(file) }
        }
        root["sources"] = newSources
        root["layers"] = (root["layers"] as List<*>).flatMap { layer ->
            layer as Map<*, *>
            if (layer["source"] != sourceId) {
                listOf(layer)
            } else {
                basemaps.map { (region, _) ->
                    LinkedHashMap(layer).also {
                        it["id"] = "${layer["id"]}@$region"
                        it["source"] = "$sourceId@$region"
                    }
                }
            }
        }
        return MiniJson.stringify(root)
    }
}
