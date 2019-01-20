package com.mercandalli.androis.apps.camera.main

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import com.mercandalli.androis.apps.camera.R

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.activity_main_button).setOnClickListener {
            AndroidCameraApi.start(this)
        }
    }
}
