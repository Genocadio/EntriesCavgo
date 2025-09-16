package com.gocavgo.entries.manageroutes;
import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.gocavgo.entries.R
import com.gocavgo.entries.dataclass.RouteWaypoint

class WaypointEditAdapter(
        val waypoints: MutableList<RouteWaypoint>,
        private val onRemoveWaypoint: (Int) -> Unit,
private val onPriceChanged: (Int, Double) -> Unit
) : RecyclerView.Adapter<WaypointEditAdapter.WaypointViewHolder>() {

class WaypointViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val orderTextView: TextView = itemView.findViewById(R.id.waypointOrderTextView)
    val locationNameTextView: TextView = itemView.findViewById(R.id.locationNameTextView)
    val locationDetailsTextView: TextView = itemView.findViewById(R.id.locationDetailsTextView)
    val priceEditText: EditText = itemView.findViewById(R.id.priceEditText)
    val removeButton: ImageButton = itemView.findViewById(R.id.removeWaypointButton)
}

override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WaypointViewHolder {
    val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_waypoint_edit, parent, false)
    return WaypointViewHolder(view)
}

override fun onBindViewHolder(holder: WaypointViewHolder, @SuppressLint("RecyclerView") position: Int) {
    val waypoint = waypoints[position]

    with(holder) {
        // Set order number
        orderTextView.text = "${waypoint.order}"

        // Set location name
        val locationName = waypoint.location.custom_name?.takeIf { it.isNotBlank() }
                ?: waypoint.location.google_place_name
        locationNameTextView.text = locationName

        // Set location details
        val details = buildString {
            waypoint.location.district?.let { append("$it • ") }
            waypoint.location.province?.let { append(it) }
        }
        locationDetailsTextView.text = if (details.isNotBlank()) details else "Location details"

        // Set current price; hide/disable when pass-through
        if (waypoint.is_pass_through) {
            priceEditText.setText("")
            priceEditText.hint = "Pass-through"
            priceEditText.isEnabled = false
        } else {
            priceEditText.isEnabled = true
            priceEditText.hint = "Price"
            priceEditText.setText(waypoint.price?.toString() ?: "")
        }

        // Remove existing text watcher to prevent multiple listeners
        priceEditText.tag?.let { tag ->
            if (tag is TextWatcher) {
                priceEditText.removeTextChangedListener(tag)
            }
        }

        // Add text watcher for price changes (skip for pass-through)
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (waypoint.is_pass_through) return
                val newPriceText = s?.toString()?.trim() ?: ""
                if (newPriceText.isNotEmpty()) {
                    try {
                        val newPrice = newPriceText.toDouble()
                        if (waypoint.price == null || newPrice != waypoint.price) {
                            waypoints[adapterPosition].price = newPrice
                            onPriceChanged(adapterPosition, newPrice)
                        }
                    } catch (e: NumberFormatException) {
                        // Invalid number format, ignore
                    }
                }
            }
        }

        priceEditText.addTextChangedListener(textWatcher)
        priceEditText.tag = textWatcher

        // Remove button click listener
        removeButton.setOnClickListener {
            showRemoveConfirmationDialog(holder.itemView.context, position, locationName)
        }
    }
}

override fun getItemCount(): Int = waypoints.size

private fun showRemoveConfirmationDialog(context: android.content.Context, position: Int, locationName: String) {
    AlertDialog.Builder(context)
            .setTitle("Remove Waypoint")
            .setMessage("Are you sure you want to remove the waypoint '$locationName'?")
            .setPositiveButton("Remove") { _, _ ->
            removeWaypoint(position)
    }
            .setNegativeButton("Cancel", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
}

private fun removeWaypoint(position: Int) {
    if (position >= 0 && position < waypoints.size) {
        waypoints.removeAt(position)
        notifyItemRemoved(position)

        // Update order numbers for remaining waypoints
        for (i in position until waypoints.size) {
            waypoints[i].order = i + 1
            notifyItemChanged(i)
        }

        onRemoveWaypoint(position)
    }
}

fun updateWaypoints(newWaypoints: List<RouteWaypoint>) {
    waypoints.clear()
    waypoints.addAll(newWaypoints)
    notifyDataSetChanged()
}
}