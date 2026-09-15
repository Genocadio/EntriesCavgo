package com.gocavgo.entries.hereroutes

import android.content.Intent
import android.net.Uri
import android.util.Log
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlinx.coroutines.*

/**
 * Processor class to handle shared locations from navigation apps like Google Maps
 * Extracts coordinates and location information from various share formats
 */
class ShareLocationProcessor {
    
    companion object {
        private const val TAG = "ShareLocationProcessor"
        
        // Common patterns for extracting coordinates from shared text
        private val COORDINATE_PATTERNS = listOf(
            // Google Maps patterns
            Pattern.compile("(-?[+]?\\d+\\.\\d+),\\s*(-?[+]?\\d+\\.\\d+)"), // Basic lat,lng
            Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"), // @lat,lng format
            Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"), // Google Maps 3d/4d format
            
            // Apple Maps patterns
            Pattern.compile("ll=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"), // ll=lat,lng format
            
            // Generic patterns
            Pattern.compile("lat[=:](-?\\d+\\.\\d+).*?lng[=:](-?\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("latitude[=:](-?\\d+\\.\\d+).*?longitude[=:](-?\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE)
        )
        
        // Google Maps URL patterns (including shortened URLs)
        private val GOOGLE_MAPS_URL_PATTERN = Pattern.compile(
            "https?://(?:www\\.)?(?:maps\\.google\\.com|goo\\.gl/maps|maps\\.app\\.goo\\.gl)/.*"
        )
        
        // Shortened URL patterns
        private val SHORTENED_URL_PATTERN = Pattern.compile(
            "https?://(?:goo\\.gl|maps\\.app\\.goo\\.gl|bit\\.ly|tinyurl\\.com|t\\.co)/.*"
        )
        
        // Apple Maps URL patterns
        private val APPLE_MAPS_URL_PATTERN = Pattern.compile(
            "https?://(?:maps\\.apple\\.com|maps\\.app\\.goo\\.gl)/.*"
        )
    }
    
    /**
     * Process shared intent and extract location information
     * @param intent The shared intent
     * @return ShareLocationResult containing extracted location data
     */
    fun processSharedIntent(intent: Intent): ShareLocationResult? {
        Log.d(TAG, "=== ShareLocationProcessor.processSharedIntent ===")
        Log.d(TAG, "Processing shared intent: ${intent.action}")
        Log.d(TAG, "Intent type: ${intent.type}")
        Log.d(TAG, "Intent data: ${intent.data}")
        
        // Log all extras
        intent.extras?.let { extras ->
            Log.d(TAG, "Intent extras in processor:")
            for (key in extras.keySet()) {
                val value = extras.get(key)
                Log.d(TAG, "  $key: $value")
            }
        }
        
        val result = when (intent.action) {
            Intent.ACTION_SEND -> {
                Log.d(TAG, "Intent action is SEND")
                when (intent.type) {
                    "text/plain" -> {
                        Log.d(TAG, "Processing text/plain share")
                        processTextShare(intent)
                    }
                    else -> {
                        Log.w(TAG, "Unsupported share type: ${intent.type}")
                        null
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                Log.d(TAG, "Intent action is VIEW")
                processViewIntent(intent)
            }
            else -> {
                Log.w(TAG, "Unsupported intent action: ${intent.action}")
                null
            }
        }
        
        Log.d(TAG, "ShareLocationProcessor result: $result")
        Log.d(TAG, "=== End ShareLocationProcessor.processSharedIntent ===")
        return result
    }
    
    /**
     * Process text share intent
     */
    private fun processTextShare(intent: Intent): ShareLocationResult? {
        Log.d(TAG, "=== processTextShare ===")
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        
        Log.d(TAG, "Shared text: $sharedText")
        Log.d(TAG, "Shared subject: $sharedSubject")
        
        if (sharedText.isNullOrBlank()) {
            Log.w(TAG, "No shared text found")
            return null
        }
        
        // Try to extract location from the shared text
        Log.d(TAG, "Attempting to extract location from text: $sharedText")
        val locationData = extractLocationFromText(sharedText)
        Log.d(TAG, "Location data extracted: $locationData")
        
        val result = if (locationData != null) {
            // Check if we also have route data
            val routeData = extractRouteFromUrl(sharedText)
            
            val shareResult = ShareLocationResult(
                coordinates = locationData.coordinates,
                locationName = "Shared Location", // Will be updated by reverse geocoding
                sourceApp = detectSourceApp(sharedText),
                originalText = sharedText,
                routeData = routeData
            )
            Log.d(TAG, "Created ShareLocationResult: $shareResult")
            shareResult
        } else {
            Log.w(TAG, "Could not extract location from shared text")
            null
        }
        
        Log.d(TAG, "=== End processTextShare ===")
        return result
    }
    
    /**
     * Process view intent (for direct URL opening)
     */
    private fun processViewIntent(intent: Intent): ShareLocationResult? {
        Log.d(TAG, "=== processViewIntent ===")
        val data = intent.data
        Log.d(TAG, "View intent data: $data")
        
        if (data == null) {
            Log.w(TAG, "No data URI in view intent")
            return null
        }
        
        val url = data.toString()
        Log.d(TAG, "Processing URL: $url")
        
        val locationData = extractLocationFromText(url)
        Log.d(TAG, "Location data from URL: $locationData")
        
        val result = if (locationData != null) {
            ShareLocationResult(
                coordinates = locationData.coordinates,
                locationName = "Shared Location", // Will be updated by reverse geocoding
                sourceApp = detectSourceApp(url),
                originalText = url
            )
        } else {
            Log.w(TAG, "Could not extract location from URL")
            null
        }
        
        Log.d(TAG, "=== End processViewIntent ===")
        return result
    }
    
    /**
     * Extract location information from shared text
     */
    private fun extractLocationFromText(text: String): LocationData? {
        Log.d(TAG, "=== extractLocationFromText ===")
        Log.d(TAG, "Input text: $text")
        
        // First try to extract route data from URLs
        Log.d(TAG, "Trying to extract route data from URLs...")
        val routeResult = extractRouteFromUrl(text)
        if (routeResult != null) {
            Log.d(TAG, "Successfully extracted route from URL: $routeResult")
            // Return the origin as the primary location
            return LocationData(routeResult.origin, null)
        }
        
        // Then try to extract single location from URLs
        Log.d(TAG, "Trying to extract single location from URLs...")
        val urlResult = extractFromUrl(text)
        if (urlResult != null) {
            Log.d(TAG, "Successfully extracted from URL: $urlResult")
            return urlResult
        }
        
        // Finally try to extract coordinates directly from text
        Log.d(TAG, "Trying to extract coordinates directly from text...")
        val coordResult = extractCoordinatesFromText(text)
        Log.d(TAG, "Coordinate extraction result: $coordResult")
        
        Log.d(TAG, "=== End extractLocationFromText ===")
        return coordResult
    }
    
    /**
     * Extract route data from URL (Google Maps routes)
     */
    private fun extractRouteFromUrl(text: String): RouteData? {
        val urls = extractUrlsFromText(text)
        
        for (url in urls) {
            Log.d(TAG, "Checking URL for route data: $url")
            
            // Check if it's a shortened URL and resolve it
            val resolvedUrl = if (SHORTENED_URL_PATTERN.matcher(url).matches()) {
                Log.d(TAG, "Detected shortened URL, attempting to resolve: $url")
                try {
                    runBlocking(Dispatchers.IO) {
                        resolveShortenedUrlAsync(url)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error resolving URL in background: ${e.message}")
                    url
                } ?: url
            } else {
                url
            }
            
            Log.d(TAG, "Resolved URL for route extraction: $resolvedUrl")
            
            // Check for Google Maps route URLs
            if (resolvedUrl.contains("maps.google.com/maps/dir/") || resolvedUrl.contains("maps/dir/")) {
                Log.d(TAG, "Detected Google Maps route URL")
                val routeData = extractFromGoogleMapsRouteUrl(resolvedUrl)
                if (routeData != null) {
                    Log.d(TAG, "Successfully extracted route data: $routeData")
                    return routeData
                }
            }
        }
        
        return null
    }
    
    /**
     * Extract route data from Google Maps route URL
     */
    private fun extractFromGoogleMapsRouteUrl(url: String): RouteData? {
        try {
            Log.d(TAG, "Extracting route from Google Maps URL: $url")
            
            // Extract all coordinates from the URL using multiple patterns
            val allCoordinates = extractAllCoordinatesFromUrl(url)
            
            if (allCoordinates.size >= 2) {
                Log.d(TAG, "Found ${allCoordinates.size} coordinates in route URL")
                
                val origin = allCoordinates[0]
                val destination = allCoordinates[1]
                val waypoints = if (allCoordinates.size > 2) {
                    allCoordinates.subList(2, allCoordinates.size)
                } else {
                    emptyList()
                }
                
                Log.d(TAG, "Successfully parsed route coordinates:")
                Log.d(TAG, "  Origin: ${origin.latitude}, ${origin.longitude}")
                Log.d(TAG, "  Destination: ${destination.latitude}, ${destination.longitude}")
                if (waypoints.isNotEmpty()) {
                    Log.d(TAG, "  Waypoints: ${waypoints.size}")
                    waypoints.forEachIndexed { index, coord ->
                        Log.d(TAG, "    Waypoint ${index + 1}: ${coord.latitude}, ${coord.longitude}")
                    }
                }
                
                return RouteData(
                    origin = origin,
                    destination = destination,
                    waypoints = waypoints
                )
            } else {
                Log.w(TAG, "Not enough coordinates found in route URL: ${allCoordinates.size}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting route from Google Maps URL: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Extract all coordinates from a Google Maps route URL
     */
    private fun extractAllCoordinatesFromUrl(url: String): List<LatLng> {
        val coordinates = mutableListOf<LatLng>()

        Log.d(TAG, "Extracting all coordinates from URL")

        // Pattern 1: Extract coordinates from !3d and !4d format (embedded in data parameter)
        val embeddedPattern = Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)")
        val embeddedMatcher = embeddedPattern.matcher(url)
        
        while (embeddedMatcher.find()) {
            val lat = embeddedMatcher.group(1).toDoubleOrNull()
            val lng = embeddedMatcher.group(2).toDoubleOrNull()
            
            if (lat != null && lng != null && isValidCoordinate(lat, lng)) {
                val coord = LatLng(lat, lng)
                coordinates.add(coord)
                Log.d(TAG, "Found embedded coordinate: ${lat}, ${lng}")
            }
        }
        
        // Pattern 2: Extract coordinates from /dir/ path (if they're actual coordinates)
        val dirPattern = Pattern.compile("/dir/([^/]+)/([^/]+)")
        val dirMatcher = dirPattern.matcher(url)
        
        if (dirMatcher.find()) {
            val originStr = dirMatcher.group(1)
            val destinationStr = dirMatcher.group(2)
            
            // Check if these are coordinate pairs
            val originCoords = parseCoordinatePair(originStr)
            val destinationCoords = parseCoordinatePair(destinationStr)
            
            if (originCoords != null && destinationCoords != null) {
                // Add to beginning if they're actual coordinates
                coordinates.add(0, originCoords)
                coordinates.add(1, destinationCoords)
                Log.d(TAG, "Found dir coordinates - Origin: ${originCoords.latitude}, ${originCoords.longitude}, Destination: ${destinationCoords.latitude}, ${destinationCoords.longitude}")
            }
        }
        
        // Pattern 3: Extract any remaining coordinate pairs in the URL
        val coordPattern = Pattern.compile("(-?[+]?\\d+\\.\\d+),(-?[+]?\\d+\\.\\d+)")
        val coordMatcher = coordPattern.matcher(url)
        
        while (coordMatcher.find()) {
            val lat = coordMatcher.group(1).toDoubleOrNull()
            val lng = coordMatcher.group(2).toDoubleOrNull()
            
            // Skip the map center (@lat,lng,...) — it is not a route stop
            val isMapCenter = coordMatcher.start() > 0 && url[coordMatcher.start() - 1] == '@'
            
            if (lat != null && lng != null && isValidCoordinate(lat, lng) && !isMapCenter) {
                val coord = LatLng(lat, lng)
                
                // Avoid duplicates
                val isDuplicate = coordinates.any { existing ->
                    kotlin.math.abs(existing.latitude - coord.latitude) < 0.0001 &&
                    kotlin.math.abs(existing.longitude - coord.longitude) < 0.0001
                }
                
                if (!isDuplicate) {
                    coordinates.add(coord)
                    Log.d(TAG, "Found additional coordinate: ${lat}, ${lng}")
                }
            }
        }
        
        Log.d(TAG, "Total coordinates extracted: ${coordinates.size}")
        return coordinates
    }
    
    /**
     * Parse coordinate pair string (e.g., "-1.9887713,30.1480708")
     */
    private fun parseCoordinatePair(coordStr: String): LatLng? {
        return try {
            val parts = coordStr.split(",")
            if (parts.size >= 2) {
                val lat = parts[0].toDoubleOrNull()
                val lng = parts[1].toDoubleOrNull()
                
                if (lat != null && lng != null && isValidCoordinate(lat, lng)) {
                    LatLng(lat, lng)
                } else {
                    Log.w(TAG, "Invalid coordinates: $coordStr")
                    null
                }
            } else {
                Log.w(TAG, "Invalid coordinate format: $coordStr")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing coordinates: $coordStr, ${e.message}")
            null
        }
    }
    
    /**
     * Extract location from URL (Google Maps, Apple Maps, etc.)
     */
    private fun extractFromUrl(text: String): LocationData? {
        val urls = extractUrlsFromText(text)
        
        for (url in urls) {
            Log.d(TAG, "Processing URL: $url")
            
            // Check if it's a shortened URL and resolve it
            val resolvedUrl = if (SHORTENED_URL_PATTERN.matcher(url).matches()) {
                Log.d(TAG, "Detected shortened URL, attempting to resolve: $url")
                try {
                    // Use runBlocking for synchronous resolution in background thread
                    runBlocking(Dispatchers.IO) {
                        resolveShortenedUrlAsync(url)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error resolving URL in background: ${e.message}")
                    url
                } ?: url
            } else {
                url
            }
            
            Log.d(TAG, "Resolved URL: $resolvedUrl")
            
            when {
                GOOGLE_MAPS_URL_PATTERN.matcher(resolvedUrl).matches() -> {
                    val result = extractFromGoogleMapsUrl(resolvedUrl)
                    if (result != null) return result
                }
                APPLE_MAPS_URL_PATTERN.matcher(resolvedUrl).matches() -> {
                    val result = extractFromAppleMapsUrl(resolvedUrl)
                    if (result != null) return result
                }
                else -> {
                    // Try generic URL coordinate extraction
                    val result = extractCoordinatesFromUrl(resolvedUrl)
                    if (result != null) return result
                }
            }
        }
        
        return null
    }
    
    /**
     * Extract location from Google Maps URL
     */
    private fun extractFromGoogleMapsUrl(url: String): LocationData? {
        try {
            Log.d(TAG, "Extracting from Google Maps URL: $url")
            
            // Handle different Google Maps URL formats
            when {
                url.contains("@") -> {
                    // Format: https://maps.google.com/maps?q=...@lat,lng,zoom
                    val atIndex = url.indexOf("@")
                    val afterAt = url.substring(atIndex + 1)
                    val parts = afterAt.split(",")
                    
                    if (parts.size >= 2) {
                        val lat = parts[0].toDoubleOrNull()
                        val lng = parts[1].toDoubleOrNull()
                        
                        if (lat != null && lng != null) {
                            val locationName = extractLocationNameFromUrl(url)
                            return LocationData(
                                coordinates = LatLng(lat, lng),
                                locationName = locationName
                            )
                        }
                    }
                }
                
                url.contains("!3d") && url.contains("!4d") -> {
                    // Format: https://maps.google.com/maps?q=...!3dlat!4dlng
                    val latMatch = Pattern.compile("!3d(-?\\d+\\.\\d+)").matcher(url)
                    val lngMatch = Pattern.compile("!4d(-?\\d+\\.\\d+)").matcher(url)
                    
                    if (latMatch.find() && lngMatch.find()) {
                        val lat = latMatch.group(1).toDoubleOrNull()
                        val lng = lngMatch.group(1).toDoubleOrNull()
                        
                        if (lat != null && lng != null) {
                            val locationName = extractLocationNameFromUrl(url)
                            return LocationData(
                                coordinates = LatLng(lat, lng),
                                locationName = locationName
                            )
                        }
                    }
                }
                
                url.contains("ll=") -> {
                    // Format: https://maps.google.com/maps?ll=lat,lng
                    val llMatch = Pattern.compile("ll=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)").matcher(url)
                    if (llMatch.find()) {
                        val lat = llMatch.group(1).toDoubleOrNull()
                        val lng = llMatch.group(2).toDoubleOrNull()
                        
                        if (lat != null && lng != null) {
                            val locationName = extractLocationNameFromUrl(url)
                            return LocationData(
                                coordinates = LatLng(lat, lng),
                                locationName = locationName
                            )
                        }
                    }
                }
            }
            
            // Try generic coordinate extraction from URL
            return extractCoordinatesFromUrl(url)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting from Google Maps URL: ${e.message}")
            return null
        }
    }
    
    /**
     * Extract location from Apple Maps URL
     */
    private fun extractFromAppleMapsUrl(url: String): LocationData? {
        try {
            Log.d(TAG, "Extracting from Apple Maps URL: $url")
            
            // Apple Maps format: https://maps.apple.com/?ll=lat,lng&q=...
            val llMatch = Pattern.compile("ll=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)").matcher(url)
            if (llMatch.find()) {
                val lat = llMatch.group(1).toDoubleOrNull()
                val lng = llMatch.group(2).toDoubleOrNull()
                
                if (lat != null && lng != null) {
                    val locationName = extractLocationNameFromUrl(url)
                    return LocationData(
                        coordinates = LatLng(lat, lng),
                        locationName = locationName
                    )
                }
            }
            
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting from Apple Maps URL: ${e.message}")
            return null
        }
    }
    
    /**
     * Extract coordinates directly from text using patterns
     */
    private fun extractCoordinatesFromText(text: String): LocationData? {
        Log.d(TAG, "=== extractCoordinatesFromText ===")
        Log.d(TAG, "Input text: $text")
        Log.d(TAG, "Testing ${COORDINATE_PATTERNS.size} coordinate patterns")
        
        for ((index, pattern) in COORDINATE_PATTERNS.withIndex()) {
            Log.d(TAG, "Testing pattern $index: ${pattern.pattern()}")
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                Log.d(TAG, "Pattern $index matched!")
                val latStr = matcher.group(1)
                val lngStr = matcher.group(2)
                Log.d(TAG, "Extracted lat string: '$latStr', lng string: '$lngStr'")
                
                val lat = latStr.toDoubleOrNull()
                val lng = lngStr.toDoubleOrNull()
                Log.d(TAG, "Parsed lat: $lat, lng: $lng")
                
                if (lat != null && lng != null && isValidCoordinate(lat, lng)) {
                    Log.d(TAG, "Valid coordinates found: $lat, $lng")
                    val locationName = extractLocationNameFromText(text)
                    Log.d(TAG, "Extracted location name: $locationName")
                    
                    val result = LocationData(
                        coordinates = LatLng(lat, lng),
                        locationName = locationName
                    )
                    Log.d(TAG, "Created LocationData: $result")
                    return result
                } else {
                    Log.d(TAG, "Invalid coordinates: lat=$lat, lng=$lng, isValid=${lat != null && lng != null && isValidCoordinate(lat, lng)}")
                }
            } else {
                Log.d(TAG, "Pattern $index did not match")
            }
        }
        
        Log.d(TAG, "No coordinates found in text")
        Log.d(TAG, "=== End extractCoordinatesFromText ===")
        return null
    }
    
    /**
     * Extract coordinates from any URL
     */
    private fun extractCoordinatesFromUrl(url: String): LocationData? {
        try {
            val decodedUrl = URLDecoder.decode(url, "UTF-8")
            return extractCoordinatesFromText(decodedUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding URL: ${e.message}")
            return null
        }
    }
    
    /**
     * Extract location name from URL parameters
     */
    private fun extractLocationNameFromUrl(url: String): String? {
        try {
            // Try to extract from 'q' parameter
            val qMatch = Pattern.compile("[?&]q=([^&]+)").matcher(url)
            if (qMatch.find()) {
                val qValue = URLDecoder.decode(qMatch.group(1), "UTF-8")
                if (qValue.isNotBlank() && !qValue.matches(Regex("-?\\d+\\.\\d+,-?\\d+\\.\\d+"))) {
                    return qValue
                }
            }
            
            // Try to extract from 'address' parameter
            val addressMatch = Pattern.compile("[?&]address=([^&]+)").matcher(url)
            if (addressMatch.find()) {
                return URLDecoder.decode(addressMatch.group(1), "UTF-8")
            }
            
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting location name from URL: ${e.message}")
            return null
        }
    }
    
    /**
     * Extract location name from text (remove coordinates and clean up)
     */
    private fun extractLocationNameFromText(text: String): String? {
        // Remove coordinate patterns and clean up text
        var cleanedText = text
        
        // Remove common coordinate patterns
        COORDINATE_PATTERNS.forEach { pattern ->
            cleanedText = pattern.matcher(cleanedText).replaceAll("")
        }
        
        // Remove URLs
        cleanedText = cleanedText.replace(Regex("https?://[^\\s]+"), "")
        
        // Clean up extra spaces and punctuation
        cleanedText = cleanedText.trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^[,;:\\-\\s]+"), "")
            .replace(Regex("[,;:\\-\\s]+$"), "")
        
        return if (cleanedText.isNotBlank()) cleanedText else null
    }
    
    /**
     * Extract URLs from text
     */
    private fun extractUrlsFromText(text: String): List<String> {
        val urlPattern = Pattern.compile("https?://[^\\s]+")
        val matcher = urlPattern.matcher(text)
        val urls = mutableListOf<String>()
        
        while (matcher.find()) {
            urls.add(matcher.group())
        }
        
        return urls
    }
    
    /**
     * Detect source app from shared text
     */
    private fun detectSourceApp(text: String): String {
        return when {
            text.contains("maps.google.com") || text.contains("goo.gl/maps") || text.contains("maps.app.goo.gl") -> "Google Maps"
            text.contains("maps.apple.com") -> "Apple Maps"
            text.contains("waze.com") -> "Waze"
            text.contains("bing.com/maps") -> "Bing Maps"
            else -> "Unknown"
        }
    }
    
    /**
     * Resolve shortened URL to its full form (async version)
     */
    private suspend fun resolveShortenedUrlAsync(shortUrl: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Resolving shortened URL: $shortUrl")
                val url = URL(shortUrl)
                val connection = url.openConnection() as HttpURLConnection
                
                // Set up connection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.requestMethod = "HEAD"
                
                // Add user agent to avoid blocking
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:13.0) Gecko/13.0 Firefox/13.0")
                
                // Get the redirect location
                val responseCode = connection.responseCode
                Log.d(TAG, "Response code: $responseCode")
                
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                    Log.d(TAG, "Redirect location: $location")
                    location
                } else {
                    Log.w(TAG, "No redirect found for shortened URL")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving shortened URL: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Resolve shortened URL to its full form (public method)
     */
    fun resolveShortenedUrl(shortUrl: String): String? {
        return try {
            Log.d(TAG, "Resolving shortened URL (sync): $shortUrl")
            val url = URL(shortUrl)
            val connection = url.openConnection() as HttpURLConnection
            
            // Set up connection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.requestMethod = "HEAD"
            
            // Add user agent to avoid blocking
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:13.0) Gecko/13.0 Firefox/13.0")
            
            // Get the redirect location
            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")
            
            if (responseCode in 300..399) {
                val location = connection.getHeaderField("Location")
                Log.d(TAG, "Redirect location: $location")
                location
            } else {
                Log.w(TAG, "No redirect found for shortened URL")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving shortened URL: ${e.message}", e)
            null
        }
    }
    
    /**
     * Validate coordinate values
     */
    private fun isValidCoordinate(lat: Double, lng: Double): Boolean {
        return lat in -90.0..90.0 && lng in -180.0..180.0
    }
    
    /**
     * Data class for extracted location information
     */
    data class LocationData(
        val coordinates: LatLng,
        val locationName: String?
    )
    
    /**
     * Data class for route information (multiple locations)
     */
    data class RouteData(
        val origin: LatLng,
        val destination: LatLng,
        val waypoints: List<LatLng> = emptyList()
    )
    
    /**
     * Result class for processed share intent
     */
    data class ShareLocationResult(
        val coordinates: LatLng,
        val locationName: String,
        val sourceApp: String,
        val originalText: String,
        val routeData: RouteData? = null // For route shares
    )
}
