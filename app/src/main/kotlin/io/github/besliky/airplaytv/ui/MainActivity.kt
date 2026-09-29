package io.github.besliky.airplaytv.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import io.github.besliky.airplaytv.R

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = getString(R.string.title_airplay)
            textSize = 64f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_primary))
        })
    }
}
