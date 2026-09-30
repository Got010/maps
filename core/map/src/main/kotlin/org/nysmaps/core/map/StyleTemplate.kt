package org.nysmaps.core.map

import java.io.File

/**
 * The bundled styles name their tile source `{{PMTILES_URL}}`. This swaps in the
 * installed basemap pack so the style points at a local file and nothing else.
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
}
