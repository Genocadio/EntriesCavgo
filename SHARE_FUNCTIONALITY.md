# Share Functionality for Navigation Apps

This document explains how the app handles shared locations from navigation apps like Google Maps, Apple Maps, and Waze.

## Overview

The app can now receive shared locations from navigation apps and automatically add them to the map as origin, destination, or waypoint markers. This allows users to seamlessly transfer location data between apps.

## Supported Apps

- **Google Maps** - Supports various URL formats and coordinate patterns
- **Apple Maps** - Supports Apple Maps URLs and coordinate formats
- **Waze** - Supports Waze share formats
- **Generic** - Supports basic coordinate patterns from any app

## How It Works

### 1. Intent Processing

The `ShareLocationProcessor` class handles incoming share intents and extracts location information:

- **Text Share**: Processes `text/plain` shares containing location data
- **URL Share**: Handles direct links to maps.google.com, maps.apple.com, etc.
- **Coordinate Extraction**: Uses regex patterns to find latitude/longitude coordinates

### 2. Location Detection

The processor can extract coordinates from various formats:

```
Google Maps formats:
- https://maps.google.com/maps?q=...@lat,lng
- https://maps.google.com/maps?q=...!3dlat!4dlng
- https://maps.google.com/maps?ll=lat,lng

Apple Maps formats:
- https://maps.apple.com/?ll=lat,lng&q=...

Generic formats:
- lat,lng coordinates
- @lat,lng format
- latitude:lat longitude:lng format
```

### 3. Integration with ManageRoute

When a location is shared:

1. **Automatic Detection**: The app detects the shared location on startup or when receiving new intents
2. **Smart Placement**: Locations are automatically placed as:
   - Origin (if no origin exists)
   - Destination (if origin exists but no destination)
   - Waypoint (if both origin and destination exist)
3. **Map Integration**: The shared location is added to the map with appropriate markers
4. **Route Calculation**: If both origin and destination are set, the route is calculated automatically

## AndroidManifest Configuration

The app is configured to handle multiple intent types:

```xml
<!-- Handle "Share" intents from navigation apps -->
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/plain" />
</intent-filter>

<!-- Handle "View" intents for location URLs -->
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="maps.google.com" />
</intent-filter>
```

## Usage Examples

### From Google Maps:
1. Open Google Maps
2. Search for a location
3. Tap "Share" → "More" → Select this app
4. The location will be added to the map automatically

### From Apple Maps:
1. Open Apple Maps
2. Find a location
3. Tap "Share" → Select this app
4. The location will be added to the map automatically

### From Any App:
1. Copy location text containing coordinates
2. Share the text to this app
3. The app will extract coordinates and add the location

## Technical Details

### ShareLocationProcessor Class

- **Location Extraction**: Uses regex patterns to find coordinates in various formats
- **URL Processing**: Handles different map service URLs
- **Validation**: Validates coordinate ranges (-90 to 90 for latitude, -180 to 180 for longitude)
- **Error Handling**: Gracefully handles malformed or invalid location data

### Integration Points

- **onCreate()**: Processes shared location when app starts
- **onNewIntent()**: Handles new shares while app is running
- **Single Top Mode**: Prevents multiple instances when sharing multiple locations

## Testing

To test the functionality:

1. **Google Maps Test**:
   - Open Google Maps
   - Search for "Kigali, Rwanda"
   - Share the location to this app
   - Verify the location appears on the map

2. **Coordinate Test**:
   - Share text: "Location: -1.9441, 30.0619"
   - Verify coordinates are extracted and location is added

3. **URL Test**:
   - Share URL: "https://maps.google.com/maps?q=-1.9441,30.0619"
   - Verify location is extracted from URL

## Future Enhancements

- Support for more navigation apps (Waze, HERE Maps, etc.)
- Better location name extraction from shared content
- Support for route sharing (multiple waypoints)
- Integration with other map services
