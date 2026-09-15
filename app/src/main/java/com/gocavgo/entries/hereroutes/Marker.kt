package com.gocavgo.entries.hereroutes

import org.maplibre.android.geometry.LatLng

/**
 * Lightweight marker model used across the app. MapLibre renders markers as
 * SymbolLayers over a single GeoJsonSource per marker (see MapLibreLayerManager).
 */
class Marker(
    var id: String = "",
    var position: LatLng,
    var title: String? = null,
    var snippet: String? = null,
    var icon: String? = null,
    var mapMarker: org.maplibre.android.annotations.Marker? = null
)