package com.azeem.azremoteconnect

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        container.addView(TextView(this).apply {
            text = "AZ RemoteConnect"
            textSize = 26f
            gravity = Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = "Android foundation ready. Remote sessions are not implemented yet."
            textSize = 16f
            gravity = Gravity.CENTER
        })
        setContentView(container)
    }
}
