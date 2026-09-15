package com.gocavgo.entries.hereroutes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.VectorDrawable
import androidx.core.content.ContextCompat
import com.gocavgo.entries.R
import org.maplibre.android.maps.Style

/**
 * Registers the shared marker icons into a MapLibre [Style] once per style load.
 * MapLibre symbol layers reference images by name (iconImage), so every icon must
 * be added to the style before the first marker layer that uses it is created.
 */
object MapMarkerIcons {

    const val ICON_ORIGIN = "icon_origin"
    const val ICON_DESTINATION = "icon_destination"
    const val ICON_WAYPOINT = "icon_waypoint"
    const val ICON_LOCATION = "icon_location"

    private val registrations = listOf(
        ICON_ORIGIN to R.drawable.ic_map_marker_red,
        ICON_DESTINATION to R.drawable.ic_flag_marker_red,
        ICON_WAYPOINT to R.drawable.ic_map_marker_blue,
        ICON_LOCATION to R.drawable.ic_my_location
    )

    fun registerAll(style: Style, context: Context) {
        registrations.forEach { (name, drawableRes) ->
            if (style.getImage(name) == null) {
                style.addImage(name, renderMarkerBitmap(drawableRes, context))
            }
        }
    }

    fun renderMarkerBitmap(drawableRes: Int, context: Context): Bitmap {
        val vectorDrawable = ContextCompat.getDrawable(context, drawableRes) as? VectorDrawable
            ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val density = context.resources.displayMetrics.density
        val sizeInPixels = (32 * density).toInt()
        val bitmap = Bitmap.createBitmap(sizeInPixels, sizeInPixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        vectorDrawable.setBounds(0, 0, sizeInPixels, sizeInPixels)
        vectorDrawable.draw(canvas)
        return bitmap
    }

    fun iconFor(markerType: String): String {
        return when (markerType.lowercase()) {
            "origin" -> ICON_ORIGIN
            "destination" -> ICON_DESTINATION
            "mylocation" -> ICON_LOCATION
            else -> ICON_WAYPOINT
        }
    }
}