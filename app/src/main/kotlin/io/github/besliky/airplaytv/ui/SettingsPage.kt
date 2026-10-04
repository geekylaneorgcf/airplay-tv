package io.github.besliky.airplaytv.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.besliky.airplaytv.R

/**
 * Two-pane TV page: a large title with status on the left, a column of focusable
 * rows on the right. The focused row turns into a light card and grows slightly,
 * which is the only focus feedback on the page.
 */
abstract class SettingsPage : Activity() {

    class Row internal constructor(val view: View) {
        private val title: TextView = view.findViewById(R.id.rowTitle)
        private val value: TextView = view.findViewById(R.id.rowValue)
        private val chevron: ImageView = view.findViewById(R.id.rowChevron)

        fun title(text: CharSequence) = apply { title.text = text }

        fun value(text: CharSequence?) = apply {
            value.text = text ?: ""
            value.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        fun navigates(on: Boolean) = apply { chevron.visibility = if (on) View.VISIBLE else View.GONE }

        fun visible(on: Boolean) = apply { view.visibility = if (on) View.VISIBLE else View.GONE }
    }

    protected lateinit var rows: LinearLayout
    protected lateinit var titleView: TextView
    protected lateinit var statusRow: View
    protected lateinit var statusDot: View
    protected lateinit var statusView: TextView
    protected lateinit var nameView: TextView
    protected lateinit var addressView: TextView
    protected lateinit var hintView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rows = findViewById(R.id.rows)
        titleView = findViewById(R.id.title)
        statusRow = findViewById(R.id.statusRow)
        statusDot = findViewById(R.id.statusDot)
        statusView = findViewById(R.id.status)
        nameView = findViewById(R.id.deviceName)
        addressView = findViewById(R.id.address)
        hintView = findViewById(R.id.hint)
    }

    /**
     * A row of the page. With a [hint] (what the option does, in plain words) the hint shows on the left, under the title, for as long as
     * the row has the focus.
     */
    protected fun addRow(title: CharSequence, hint: CharSequence? = null, onClick: (Row) -> Unit): Row {
        val view = LayoutInflater.from(this).inflate(R.layout.view_setting_row, rows, false)
        val row = Row(view).title(title)
        view.setOnClickListener { onClick(row) }
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) FOCUS_SCALE else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_ANIM_MS).start()
            v.elevation = if (hasFocus) 8f else 0f
            if (hasFocus && hint != null) {
                hintView.text = hint
                hintView.visibility = View.VISIBLE
            }
        }
        rows.addView(view)
        return row
    }

    protected fun onOff(on: Boolean): String = getString(if (on) R.string.value_on else R.string.value_off)

    companion object {
        private const val FOCUS_SCALE = 1.04f
        private const val FOCUS_ANIM_MS = 120L
    }
}
