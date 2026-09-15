package com.gocavgo.entries

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

@HiltAndroidApp
class EntriesApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // MapLibre must be initialized before any MapView is inflated. The app
        // uses a custom ArcGIS World Imagery style (asset://satellite_style.json),
        // so no hosted-tile API key is required.
        MapLibre.getInstance(this, "", WellKnownTileServer.MapLibre)
    }
}
