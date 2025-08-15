package com.gocavgo.entries.manageroutes


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.gocavgo.entries.R
import com.gocavgo.entries.dataclass.SaveRouteResponse
import java.text.SimpleDateFormat
import java.util.*

class RouteManagementAdapter(
    private val routes: MutableList<SaveRouteResponse>,
    private val onEditRoute: (SaveRouteResponse) -> Unit,
    private val onDeleteRoute: (SaveRouteResponse) -> Unit
) : RecyclerView.Adapter<RouteManagementAdapter.RouteViewHolder>() {

    companion object {
        private val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault())
    }

    class RouteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val routeNameTextView: TextView = itemView.findViewById(R.id.routeNameTextView)
        val originDestinationTextView: TextView = itemView.findViewById(R.id.originDestinationTextView)
        val routeDetailsTextView: TextView = itemView.findViewById(R.id.routeDetailsTextView)
        val routePriceTextView: TextView = itemView.findViewById(R.id.routePriceTextView)
        val waypointsCountTextView: TextView = itemView.findViewById(R.id.waypointsCountTextView)
        val routeTypeTextView: TextView = itemView.findViewById(R.id.routeTypeTextView)
        val createdDateTextView: TextView = itemView.findViewById(R.id.createdDateTextView)
        val editButton: ImageButton = itemView.findViewById(R.id.editButton)
        val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_route_management, parent, false)
        return RouteViewHolder(view)
    }

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        val route = routes[position]

        with(holder) {
            // Route name
            routeNameTextView.text = route.name

            // Origin and destination
            val originName = route.origin.custom_name?.takeIf { it.isNotBlank() }
                ?: route.origin.google_place_name
            val destinationName = route.destination.custom_name?.takeIf { it.isNotBlank() }
                ?: route.destination.google_place_name
            originDestinationTextView.text = "$originName → $destinationName"

            // Route details (distance and duration)
            val distanceKm = route.distance_meters / 1000.0
            val durationHours = route.estimated_duration_seconds / 3600
            val durationMinutes = (route.estimated_duration_seconds % 3600) / 60

            val distanceStr = String.format("%.1f km", distanceKm)
            val durationStr = when {
                durationHours > 0 -> "${durationHours}h ${durationMinutes}min"
                else -> "${durationMinutes}min"
            }
            routeDetailsTextView.text = "$distanceStr • $durationStr"

            // Route price
            routePriceTextView.text = "RWF ${String.format("%.0f", route.route_price)}"

            // Waypoints count
            if (route.waypoints.isNotEmpty()) {
                waypointsCountTextView.text = "${route.waypoints.size} waypoint${if (route.waypoints.size != 1) "s" else ""}"
                waypointsCountTextView.visibility = View.VISIBLE
            } else {
                waypointsCountTextView.visibility = View.GONE
            }

            // Route type
            routeTypeTextView.text = if (route.city_route) "City Route" else "Inter-City Route"
            routeTypeTextView.setBackgroundResource(
                if (route.city_route) R.drawable.bg_city_route_tag else R.drawable.bg_intercity_route_tag
            )

            // Created date
            try {
                val createdDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    .parse(route.created_at.substringBefore('.'))
                createdDate?.let {
                    createdDateTextView.text = "Created ${dateFormat.format(it)}"
                }
            } catch (e: Exception) {
                createdDateTextView.text = "Created ${route.created_at.substringBefore('T')}"
            }

            // Button click listeners
            editButton.setOnClickListener { onEditRoute(route) }
            deleteButton.setOnClickListener { onDeleteRoute(route) }
        }
    }

    override fun getItemCount(): Int = routes.size

    fun updateRoutes(newRoutes: List<SaveRouteResponse>) {
        routes.clear()
        routes.addAll(newRoutes)
        notifyDataSetChanged()
    }

    fun addRoutes(newRoutes: List<SaveRouteResponse>) {
        val startPosition = routes.size
        routes.addAll(newRoutes)
        notifyItemRangeInserted(startPosition, newRoutes.size)
    }

    fun removeRoute(route: SaveRouteResponse) {
        val position = routes.indexOf(route)
        if (position != -1) {
            routes.removeAt(position)
            notifyItemRemoved(position)
        }
    }
}