package com.gocavgo.entries

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gocavgo.entries.hereroutes.ManageRoute
import com.gocavgo.entries.manageplace.PlaceManagementActivity
import com.gocavgo.entries.manageroutes.RouteManagementActivity
import com.gocavgo.entries.mapview.CreateRouteActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_dashboard)

        val btnCreateRoute: Button = findViewById(R.id.btn_create_route)
        val btnViewRoutes: Button = findViewById(R.id.btn_view_routes)
        val btnViewPlaces: Button = findViewById(R.id.btn_view_locations)

        if (!PermissionManager.hasPermissions(this)) {
            PermissionManager.requestPermissions(this)
        }

        btnCreateRoute.setOnClickListener {
            if (PermissionManager.hasPermissions(this)) {
                startActivity(Intent(this, ManageRoute::class.java))
                Toast.makeText(this, "View Existing Routes (coming soon)", Toast.LENGTH_SHORT)
                    .show()
            } else {
                PermissionManager.requestPermissions(this)
            }
        }
        btnViewRoutes.setOnClickListener {
            startActivity(Intent(this, RouteManagementActivity::class.java))

        }
        btnViewPlaces.setOnClickListener {
            startActivity(Intent(this, PlaceManagementActivity::class.java))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionManager.PERMISSION_REQUEST_CODE) {
            if (!PermissionManager.hasPermissions(this)) {
                Toast.makeText(this, "Permissions are required to use this app.", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }
}