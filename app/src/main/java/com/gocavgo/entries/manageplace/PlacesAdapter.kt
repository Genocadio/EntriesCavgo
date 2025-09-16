package com.gocavgo.entries.manageplace

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gocavgo.entries.databinding.ItemPlaceManagementBinding
import com.gocavgo.entries.hereroutes.DbPlace
import java.text.SimpleDateFormat
import java.util.*

class PlacesAdapter(
    private val onEditClick: (DbPlace) -> Unit,
    private val onDeleteClick: (DbPlace) -> Unit,
    private val onViewOnMapClick: (DbPlace) -> Unit
) : RecyclerView.Adapter<PlacesAdapter.PlaceViewHolder>() {

    private val places = mutableListOf<DbPlace>()
    private val dateFormatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    fun setPlaces(newPlaces: List<DbPlace>) {
        places.clear()
        places.addAll(newPlaces)
        notifyDataSetChanged()
    }

    fun addPlaces(newPlaces: List<DbPlace>) {
        val startPosition = places.size
        places.addAll(newPlaces)
        notifyItemRangeInserted(startPosition, newPlaces.size)
    }

    fun clearPlaces() {
        places.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val binding = ItemPlaceManagementBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PlaceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(places[position])
    }

    override fun getItemCount(): Int = places.size

    inner class PlaceViewHolder(
        private val binding: ItemPlaceManagementBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(place: DbPlace) {
            // Place name - show custom name if available, otherwise Google place name
            binding.placeNameTextView.text = place.placeName

            // Custom name - show only if different from place name
            if (!place.customName.isNullOrBlank() && place.customName != place.placeName) {
                binding.customNameTextView.text = place.customName
                binding.customNameTextView.visibility = View.VISIBLE
            } else {
                binding.customNameTextView.visibility = View.GONE
            }

            // Place code
            binding.placeCodeTextView.text = place.code ?: "N/A"

            // Location details (District, Province)
            val locationText = buildString {
                place.displayDistrict?.let { district ->
                    append(district)
                    place.province?.let { province ->
                        append(", $province")
                    }
                } ?: append("Location not set")
            }
            binding.locationDetailsTextView.text = locationText

            // Coordinates
            binding.coordinatesTextView.text = String.format(
                "%.3f, %.3f",
                place.latitude,
                place.longitude
            )

            // Google place name (show original name)
            binding.googlePlaceNameTextView.text = place.placeName

            // Created date - parse from ISO format if available
            // Since we don't have created_at in DbPlace, we'll show a placeholder
            binding.createdDateTextView.text = "ID: ${place.id ?: "Unknown"}"

            // Action buttons
            binding.editButton.setOnClickListener {
                onEditClick(place)
            }

            binding.deleteButton.setOnClickListener {
                onDeleteClick(place)
            }

            binding.viewOnMapButton.setOnClickListener {
                onViewOnMapClick(place)
            }
        }
    }
}