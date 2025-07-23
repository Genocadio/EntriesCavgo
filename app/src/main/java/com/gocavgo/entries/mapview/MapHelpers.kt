package com.gocavgo.entries.mapview

import com.google.android.gms.maps.model.LatLng
import org.json.JSONObject

class MapHelpers {

    // Function to create the JSON body
    fun createJsonBody(origin: LatLng, destination: LatLng, waypoints: List<LatLng>, apiKey: String): String {
        val waypointsJson = waypoints.joinToString(",") { waypoint ->
            """
        {
          "location": {
            "latLng": {
              "latitude": ${waypoint.latitude},
              "longitude": ${waypoint.longitude}
            }
          },
          "vehicleStopover": true
        }
        """.trimIndent()
        }

        return """
    {
      "origin": {
        "location": {
          "latLng": {
            "latitude": ${origin.latitude},
            "longitude": ${origin.longitude}
          }
        },
       
      },
      "destination": {
        "location": {
          "latLng": {
            "latitude": ${destination.latitude},
            "longitude": ${destination.longitude}
          }
        },
     
      },
      ${if (waypoints.isNotEmpty()) """"intermediates": [$waypointsJson],""" else ""}
      "travelMode": "DRIVE",
      "routingPreference": "TRAFFIC_AWARE",
      "computeAlternativeRoutes": false,
      "optimizeWaypointOrder": "true",
      "routeModifiers": {
        "avoidTolls": false,
        "avoidHighways": false,
        "avoidFerries": false
      },
      "languageCode": "en-US",
      "units": "IMPERIAL"
    }
    """.trimIndent()
    }


    // Parse the route from the response
    fun parseRoute(response: String): List<LatLng> {
        val polyline = JSONObject(response)
            .getJSONArray("routes")
            .getJSONObject(0)
            .getJSONObject("polyline")
            .getString("encodedPolyline")
        return decodePolyline(polyline)
    }


    // Decode the polyline into a list of LatLng points
    fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var shift = 0
            var result = 0
            while (true) {
                val byte = encoded[index++].toInt() - 63
                result = result or (byte and 0x1f shl shift)
                if (byte < 0x20) break
                shift += 5
            }
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            while (true) {
                val byte = encoded[index++].toInt() - 63
                result = result or (byte and 0x1f shl shift)
                if (byte < 0x20) break
                shift += 5
            }
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }

        return poly
    }
}
