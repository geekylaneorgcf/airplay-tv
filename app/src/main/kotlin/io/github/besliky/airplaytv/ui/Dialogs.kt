package io.github.besliky.airplaytv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.Settings
import kotlin.math.min

/**
 * Small framework dialogs styled for the TV theme: a left-aligned title and text, steps and shell
 * commands in boxes of their own, and buttons whose text stays readable when they have focus.
 */
object Dialogs {

    private const val WIDTH_DP = 680

    private fun builder(activity: Activity) = AlertDialog.Builder(activity, R.style.Theme_AirPlayTV_Dialog)

    private fun dp(activity: Activity, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), activity.resources.displayMetrics).toInt()

    /** Shows [dialog], styles its buttons and, with [widthDp], keeps its width readable on a wide screen. */
    private fun present(activity: Activity, dialog: AlertDialog, widthDp: Int = 0): AlertDialog {
        dialog.show()
        for (which in intArrayOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.let { styleButton(activity, it) }
        }
        if (widthDp > 0) {
            val screen = activity.resources.displayMetrics.widthPixels
            dialog.window?.setLayout(min(dp(activity, widthDp), screen - dp(activity, 48)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    /** The framework's focused button is a light box with light text; this keeps the text dark on it. */
    private fun styleButton(activity: Activity, button: Button) {
        button.isAllCaps = false
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.setTextColor(activity.getColorStateList(R.color.row_title))
        button.background = activity.getDrawable(R.drawable.dialog_button_background)
        button.minWidth = dp(activity, 120)
        button.minHeight = dp(activity, 48)
        button.setPadding(dp(activity, 24), 0, dp(activity, 24), 0)
        button.stateListAnimator = null
    }

    private fun titleView(activity: Activity, text: CharSequence) = TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(activity.getColor(R.color.text_primary))
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 6))
    }

    private fun paragraph(activity: Activity, text: CharSequence, topDp: Int = 0) = TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTextColor(activity.getColor(R.color.text_primary))
        setLineSpacing(0f, 1.15f)
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(activity, topDp) }
    }

    /** One shell command in a box: numbered, monospace, wrapped at spaces so the next command cannot run into it. */
    private fun commandBox(activity: Activity, number: Int, text: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        background = activity.getDrawable(R.drawable.code_block_background)
        setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(activity, 12) }
        addView(TextView(activity).apply {
            this.text = number.toString()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(activity.getColor(R.color.text_secondary))
            minWidth = dp(activity, 20)
        })
        addView(TextView(activity).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(activity.getColor(R.color.text_primary))
            setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun body(activity: Activity, fill: LinearLayout.() -> Unit): ScrollView {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24), dp(activity, 4), dp(activity, 24), dp(activity, 12))
            fill()
        }
        return ScrollView(activity).apply { addView(column) }
    }

    fun editName(activity: Activity, current: String, onDone: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(current)
            setSelection(current.length)
            hint = activity.getString(R.string.dialog_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(Settings.MAX_NAME_LENGTH))
            setSingleLine()
            setTextColor(activity.getColor(R.color.text_primary))
            setHintTextColor(activity.getColor(R.color.text_secondary))
            textSize = 22f
        }
        val container = FrameLayout(activity).apply {
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), 0)
            addView(input)
        }
        val dialog = builder(activity)
            .setCustomTitle(titleView(activity, activity.getString(R.string.dialog_name_title)))
            .setView(container)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                Settings.sanitizeName(input.text.toString())?.let(onDone)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                Settings.sanitizeName(input.text.toString())?.let(onDone)
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        present(activity, dialog, WIDTH_DP)
        input.requestFocus()
    }

    fun confirm(activity: Activity, title: Int, message: Int, confirmLabel: Int, onConfirm: () -> Unit) {
        present(
            activity,
            builder(activity)
                .setCustomTitle(titleView(activity, activity.getString(title)))
                .setView(body(activity) { addView(paragraph(activity, activity.getString(message))) })
                .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
                .setNegativeButton(R.string.dialog_cancel, null)
                .create(),
            WIDTH_DP,
        )
    }

    fun message(activity: Activity, title: CharSequence, message: CharSequence) {
        present(
            activity,
            builder(activity)
                .setCustomTitle(titleView(activity, title))
                .setView(body(activity) { addView(paragraph(activity, message)) })
                .setPositiveButton(R.string.dialog_close, null)
                .create(),
            WIDTH_DP,
        )
    }

    /**
     * Setup instructions for things Android only lets a computer switch on: a sentence of context, the
     * shell commands each in a numbered box, and an optional closing note. A TV is a poor place to read
     * commands, so keep them few and short.
     */
    fun steps(activity: Activity, title: CharSequence, intro: CharSequence, commands: List<String>, outro: CharSequence? = null) {
        present(
            activity,
            builder(activity)
                .setCustomTitle(titleView(activity, title))
                .setView(body(activity) {
                    addView(paragraph(activity, intro))
                    commands.forEachIndexed { i, command -> addView(commandBox(activity, i + 1, command)) }
                    if (outro != null) addView(paragraph(activity, outro, topDp = 16))
                })
                .setPositiveButton(R.string.dialog_close, null)
                .create(),
            WIDTH_DP,
        )
    }

    /** Scrollable monospace text, for diagnostics and license texts. */
    fun longText(activity: Activity, title: CharSequence, text: CharSequence) {
        val tv = TextView(activity).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_primary))
            setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), dp(activity, 8))
            setTextIsSelectable(false)
            isFocusable = true
        }
        val scroll = ScrollView(activity).apply { addView(tv) }
        present(
            activity,
            builder(activity)
                .setCustomTitle(titleView(activity, title))
                .setView(scroll)
                .setPositiveButton(R.string.dialog_close, null)
                .create(),
        )
    }

    fun choose(activity: Activity, title: Int, items: List<String>, selected: Int, onChoose: (Int) -> Unit) {
        present(
            activity,
            builder(activity)
                .setCustomTitle(titleView(activity, activity.getString(title)))
                .setSingleChoiceItems(items.toTypedArray(), selected) { dialog, which ->
                    onChoose(which)
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .create(),
        )
    }
}
