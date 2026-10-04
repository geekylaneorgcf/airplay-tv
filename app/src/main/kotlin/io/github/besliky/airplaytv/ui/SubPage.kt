package io.github.besliky.airplaytv.ui

import android.os.Bundle
import android.view.View
import io.github.besliky.airplaytv.Settings

/**
 * A page of settings of its own, like Advanced: a title on the left and the rows on the right. A subclass names the page, builds
 * its rows once and fills their values in [bind], which runs again after every change.
 */
abstract class SubPage : SettingsPage() {

    protected lateinit var settings: Settings

    protected abstract val pageTitle: Int

    protected abstract fun buildRows()

    protected abstract fun bind()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        titleView.text = getString(pageTitle)
        statusRow.visibility = View.GONE
        nameView.visibility = View.GONE
        addressView.visibility = View.GONE
        hintView.visibility = View.GONE
        hintView.setPadding(0, 16, 0, 0)
        buildRows()
        bind()
        rows.getChildAt(0)?.requestFocus()
    }

    /** The choice after [current] in [choices], wrapping round. */
    protected fun <T> next(choices: List<T>, current: T): T = choices[(choices.indexOf(current) + 1) % choices.size]

    protected fun percent(value: Int, none: Boolean = false): String =
        if (none) getString(io.github.besliky.airplaytv.R.string.value_off) else getString(io.github.besliky.airplaytv.R.string.value_percent, value)

    /** A whole hour of the day as people say it: 22 is 10 PM, 0 is 12 AM. */
    protected fun hour(h: Int): String =
        getString(io.github.besliky.airplaytv.R.string.value_hour, if (h % 12 == 0) 12 else h % 12, if (h < 12) "AM" else "PM")
}
