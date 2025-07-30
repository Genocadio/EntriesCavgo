package com.gocavgo.entries.hereroutes

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.gocavgo.entries.R
import com.google.android.material.textfield.TextInputEditText

class PlaceEditDialog : DialogFragment() {

    private val TAG = PlaceEditDialog::class.java.simpleName

    private lateinit var tvOriginalName: TextView
    private lateinit var tvOriginalAddress: TextView
    private lateinit var etCustomName: TextInputEditText
    private lateinit var spinnerDistrict: AutoCompleteTextView
    private lateinit var btnCancel: Button
    private lateinit var btnReset: Button
    private lateinit var btnSave: Button

    private var dbPlace: DbPlace? = null
    private var onPlaceUpdated: ((DbPlace) -> Unit)? = null

    companion object {
        fun newInstance(dbPlace: DbPlace, onPlaceUpdated: (DbPlace) -> Unit): PlaceEditDialog {
            return PlaceEditDialog().apply {
                this.dbPlace = dbPlace
                this.onPlaceUpdated = onPlaceUpdated
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_place_edit, null)

        initializeViews(view)
        setupViews()
        setupClickListeners()

        builder.setView(view)

        val dialog = builder.create()
        dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        )

        return dialog
    }

    private fun initializeViews(view: android.view.View) {
        tvOriginalName = view.findViewById(R.id.tv_original_name)
        tvOriginalAddress = view.findViewById(R.id.tv_original_address)
        etCustomName = view.findViewById(R.id.et_custom_name)
        spinnerDistrict = view.findViewById(R.id.spinner_district)
        btnCancel = view.findViewById(R.id.btn_cancel)
        btnReset = view.findViewById(R.id.btn_reset)
        btnSave = view.findViewById(R.id.btn_save)
    }

    private fun setupViews() {
        dbPlace?.let { place ->
                // Set original place data
                tvOriginalName.text = place.placeName
            tvOriginalAddress.text = place.address

            // Set current custom values if they exist
            etCustomName.setText(place.customName ?: "")

            // Setup district dropdown
            setupDistrictDropdown()

            // Set current district value
            val currentDistrict = place.customDistrict ?: place.displayDistrict
            if (!currentDistrict.isNullOrBlank()) {
                spinnerDistrict.setText(currentDistrict, false)
            }

            Log.d(TAG, "Dialog setup for place: ${place.getName()}")
        }
    }

    private fun setupDistrictDropdown() {
        val districts = DbPlace.RWANDAN_DISTRICTS
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, districts)
        spinnerDistrict.setAdapter(adapter)

        spinnerDistrict.setOnClickListener {
            spinnerDistrict.showDropDown()
        }

        // Validate district selection
        spinnerDistrict.setOnItemClickListener { _, _, position, _ ->
                val selectedDistrict = districts[position]
            Log.d(TAG, "District selected: $selectedDistrict")
        }
    }

    private fun setupClickListeners() {
        btnCancel.setOnClickListener {
            Log.d(TAG, "Edit canceled")
            dismiss()
        }

        btnReset.setOnClickListener {
            resetToOriginal()
        }

        btnSave.setOnClickListener {
            saveChanges()
        }
    }

    private fun resetToOriginal() {
        etCustomName.setText("")
        spinnerDistrict.setText("", false)
        Log.d(TAG, "Fields reset to original values")
    }

    private fun saveChanges() {
        dbPlace?.let { place ->
                val customName = etCustomName.text?.toString()?.trim()
            val customDistrict = spinnerDistrict.text?.toString()?.trim()

            // Validate district selection
            val validDistrict = if (!customDistrict.isNullOrBlank() &&
                    DbPlace.RWANDAN_DISTRICTS.contains(customDistrict)) {
                customDistrict
            } else {
                null
            }

            val updatedPlace = place.withCustomData(
                    customName = if (customName.isNullOrBlank()) null else customName,
                    customDistrict = validDistrict
            )

            Log.d(TAG, "Saving place changes:")
            Log.d(TAG, "  Original name: ${place.placeName}")
            Log.d(TAG, "  Custom name: ${updatedPlace.customName}")
            Log.d(TAG, "  Custom district: ${updatedPlace.customDistrict}")
            Log.d(TAG, "  Display name: ${updatedPlace.getName()}")

            onPlaceUpdated?.invoke(updatedPlace)
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}