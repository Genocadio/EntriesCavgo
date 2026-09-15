package com.gocavgo.entries.hereroutes

import android.util.Log
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Shared MapLibre drawing helper. Every marker is backed by its own GeoJsonSource
 * (containing one Point feature) plus a SymbolLayer, and the active route polyline
 * is a single LineLayer. All operations must happen after the style has loaded.
 */
class MapLibreLayerManager(private val style: Style) {

    companion object {
        private const val TAG = "MapLibreLayerManager"

        const val ROUTE_SOURCE_ID = "route_source"
        const val ROUTE_LAYER_ID = "route_layer"
    }

    private fun markerSourceId(id: String) = "marker_source_$id"
    private fun markerLayerId(id: String) = "marker_layer_$id"

    private val markerIds = mutableSetOf<String>()

    val registeredMarkerCount: Int get() = markerIds.size

    /** Adds a new marker, or moves an existing marker with the same [id]. */
    fun addOrUpdateMarker(id: String, position: LatLng, iconName: String) {
        val sourceId = markerSourceId(id)
        val feature = Feature.fromGeometry(pointOf(position))

        val existing = style.getSourceAs<GeoJsonSource>(sourceId)
        if (existing != null) {
            existing.setGeoJson(feature)
            return
        }

        style.addSource(GeoJsonSource(sourceId, feature))
        val layer = SymbolLayer(markerLayerId(id), sourceId).withProperties(
            iconImage(iconName),
            iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconAllowOverlap(true),
            iconIgnorePlacement(true)
        )
        style.addLayer(layer)
        markerIds.add(id)
        Log.d(TAG, "Added marker '$id' at ${position.latitude}, ${position.longitude}")
    }

    /** Removes the marker with the given [id] from the map and style. */
    fun removeMarker(id: String) {
        val layerId = markerLayerId(id)
        val sourceId = markerSourceId(id)
        style.removeLayer(layerId)
        style.removeSource(sourceId)
        markerIds.remove(id)
        Log.d(TAG, "Removed marker '$id'")
    }

    fun clearAllMarkers() {
        markerIds.toList().forEach { removeMarker(it) }
        markerIds.clear()
    }

    /** Draws (or updates) the route polyline through the given points. */
    fun setRouteLine(points: List<LatLng>, color: Int, widthPx: Float, opacity: Float = 0.85f) {
        if (points.isEmpty()) {
            clearRouteLine()
            return
        }
        val feature = Feature.fromGeometry(LineString.fromLngLats(points.map { pointOf(it) }))

        val existing = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
        if (existing != null) {
            existing.setGeoJson(feature)
            return
        }

        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, feature))
        val layer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            lineColor(color),
            lineWidth(widthPx),
            lineOpacity(opacity),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND)
        )
        style.addLayer(layer)
        Log.d(TAG, "Route line drawn with ${points.size} points")
    }

    fun clearRouteLine() {
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)?.let { source ->
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        }
    }

    /** Removes all markers and the route line from the style. */
    fun clearAll() {
        clearAllMarkers()
        clearRouteLine()
    }

    private fun pointOf(latLng: LatLng): Point = Point.fromLngLat(latLng.longitude, latLng.latitude)
}