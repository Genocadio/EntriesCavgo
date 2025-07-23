package com.gocavgo.entries

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gocavgo.entries.mapview.CreateRouteActivity

class MainActivity : AppCompatActivity() {
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_dashboard)


        val btnCreateRoute: Button = findViewById(R.id.btn_create_route)
        val btnViewRoutes: Button = findViewById(R.id.btn_view_routes)


        btnCreateRoute.setOnClickListener {
            startActivity(Intent(this, CreateRouteActivity::class.java))
        }
        btnViewRoutes.setOnClickListener {
            Toast.makeText(this, "View Existing Routes (coming soon)", Toast.LENGTH_SHORT).show()
        }

    }
}