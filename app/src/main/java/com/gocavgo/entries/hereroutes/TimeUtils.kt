package com.gocavgo.entries.hereroutes

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Time formatting utilities.
 * HERE SDK Route/LocationTime dependencies removed — only pure utility methods remain.
 */
class TimeUtils {

    fun formatTime(sec: Long): String {
        val hours = (sec / 3600).toInt()
        val minutes = ((sec % 3600) / 60).toInt()
        return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
    }

    fun formatLength(meters: Int): String {
        val kilometers = meters / 1000
        val remainingMeters = meters % 1000
        return String.format(Locale.getDefault(), "%02d.%02d km", kilometers, remainingMeters)
    }

    fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "$minutes mins"
            else -> "< 1 min"
        }
    }

    fun getETAasString(durationSeconds: Long): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.SECOND, durationSeconds.toInt())
        val dateFormat = SimpleDateFormat.getTimeInstance(DateFormat.SHORT)
        return dateFormat.format(calendar.time)
    }
}
