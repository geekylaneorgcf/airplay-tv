package io.github.besliky.airplaytv.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
 * Small dialogs built from plain views for the TV theme: a left-aligned title and text, steps and shell
 * commands in boxes of their own, and buttons that light up when they have focus. They are not
 * [android.app.AlertDialog]s on purpose: that one filled the whole screen height on Fire OS, did not
 * shrink its text to make room for its buttons, and ignored the focus colours set on them.
 */
object Dialogs {

    private const val WIDTH_DP = 760

    private class Action(val label: CharSequence, val onClick: () -> Unit)

    private fun dp(context: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    /** A scroll view that never grows taller than [maxHeight], so the buttons below it cannot be pushed off the screen. */
    private class BoundedScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST))
        }
    }

    private fun rounded(context: Context, color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radiusDp).toFloat()
    }

    /**
     * Gives [view] the settings-row look: a raised fill, and a light card with dark text while it has
     * focus. Done in a focus listener rather than a state list, because the state list never showed.
     */
    private fun lightsUpOnFocus(activity: Activity, view: TextView, radiusDp: Int) {
        val idle = activity.getColor(R.color.surface_raised)
        val lit = activity.getColor(R.color.surface_focused)
        val light = activity.getColor(R.color.text_primary)
        val dark = activity.getColor(R.color.text_on_focus)
        fun look(focused: Boolean) {
            view.background = rounded(activity, if (focused) lit else idle, radiusDp)
            view.setTextColor(if (focused) dark else light)
        }
        view.setOnFocusChangeListener { _, focused -> look(focused) }
        look(view.isFocused)
    }

    private fun titleView(activity: Activity, text: CharSequence) = TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(activity.getColor(R.color.text_primary))
        gravity = Gravity.START
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setPadding(dp(activity, 28), dp(activity, 24), dp(activity, 28), dp(activity, 8))
    }

    private fun paragraph(activity: Activity, text: CharSequence, sizeSp: Float = 17f, topDp: Int = 0) = TextView(activity).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
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
        background = rounded(activity, activity.getColor(R.color.surface_raised), 12)
        setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(activity, 12) }
        addView(TextView(activity).apply {
            this.text = number.toString()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(activity.getColor(R.color.text_secondary))
            minWidth = dp(activity, 20)
        })
        addView(TextView(activity).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(activity.getColor(R.color.text_primary))
            setLineSpacing(0f, 1.2f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun column(activity: Activity, fill: LinearLayout.() -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(activity, 28), dp(activity, 4), dp(activity, 28), dp(activity, 12))
        fill()
    }

    private fun actionButton(activity: Activity, action: Action, dialog: Dialog) = Button(activity).apply {
        isAllCaps = false
        text = action.label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minWidth = dp(activity, 128)
        minHeight = dp(activity, 52)
        setPadding(dp(activity, 28), 0, dp(activity, 28), 0)
        stateListAnimator = null
        isFocusable = true
        lightsUpOnFocus(activity, this, 14)
        setOnClickListener {
            dialog.dismiss()
            action.onClick()
        }
    }

    /**
     * Shows a dialog: [title], a [body] that scrolls if it has to, and [actions] along the bottom. The
     * panel is as tall as its content but never taller than the screen. [focus] starts with the focus
     * (default: the last action).
     */
    private fun present(
        activity: Activity,
        title: CharSequence,
        body: View,
        actions: List<Action>,
        focus: (List<View>) -> View? = { it.lastOrNull() },
        softInput: Boolean = false,
    ): Dialog {
        val metrics = activity.resources.displayMetrics
        val width = min(dp(activity, WIDTH_DP), metrics.widthPixels - dp(activity, 96))
        val maxBody = metrics.heightPixels - dp(activity, 250)
        val dialog = Dialog(activity, R.style.Theme_AirPlayTV_Overlay)

        val scroll = BoundedScrollView(activity, maxBody).apply { addView(body) }
        val buttons = ArrayList<View>()
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(activity, 28), dp(activity, 12), dp(activity, 28), dp(activity, 24))
            for (action in actions) {
                val button = actionButton(activity, action, dialog)
                buttons += button
                addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(activity, 12) })
            }
        }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(activity, activity.getColor(R.color.surface), 20)
            addView(titleView(activity, title))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(
            FrameLayout(activity).apply {
                addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            },
        )
        // The up and down keys scroll a body that is taller than its box, wherever the focus is.
        dialog.setOnKeyListener { _, keyCode, event ->
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -1
                else -> 0
            }
            if (direction != 0 && event.action == KeyEvent.ACTION_DOWN && scroll.canScrollVertically(direction)) {
                scroll.smoothScrollBy(0, direction * dp(activity, 96))
                true
            } else {
                false
            }
        }
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.65f)
            if (softInput) setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        focus(buttons)?.requestFocus()
        return dialog
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
        lateinit var dialog: Dialog
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                Settings.sanitizeName(input.text.toString())?.let(onDone)
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog = present(
            activity,
            activity.getString(R.string.dialog_name_title),
            column(activity) { addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) },
            listOf(
                Action(activity.getString(R.string.dialog_cancel)) {},
                Action(activity.getString(R.string.dialog_ok)) { Settings.sanitizeName(input.text.toString())?.let(onDone) },
            ),
            focus = { input },
            softInput = true,
        )
    }

    /** Asks for one line of text under [message]; [clean] turns what was typed into the value or null (not accepted). */
    fun editLine(
        activity: Activity,
        title: CharSequence,
        message: CharSequence,
        hint: CharSequence,
        current: String,
        clean: (String) -> String?,
        onDone: (String) -> Unit,
    ) {
        val input = EditText(activity).apply {
            setText(current)
            setSelection(current.length)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(80))
            setSingleLine()
            setTextColor(activity.getColor(R.color.text_primary))
            setHintTextColor(activity.getColor(R.color.text_secondary))
            textSize = 22f
        }
        lateinit var dialog: Dialog
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                clean(input.text.toString())?.let(onDone)
                dialog.dismiss()
                true
            } else {
                false
            }
        }
        dialog = present(
            activity,
            title,
            column(activity) {
                addView(paragraph(activity, message))
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(activity, 12) })
            },
            listOf(
                Action(activity.getString(R.string.dialog_cancel)) {},
                Action(activity.getString(R.string.dialog_ok)) { clean(input.text.toString())?.let(onDone) },
            ),
            focus = { input },
            softInput = true,
        )
    }

    /** Asks before something destructive; the focus starts on the safe answer. */
    fun confirm(activity: Activity, title: Int, message: Int, confirmLabel: Int, onConfirm: () -> Unit) {
        present(
            activity,
            activity.getString(title),
            column(activity) { addView(paragraph(activity, activity.getString(message))) },
            listOf(
                Action(activity.getString(R.string.dialog_cancel)) {},
                Action(activity.getString(confirmLabel), onConfirm),
            ),
            focus = { it.firstOrNull() },
        )
    }

    fun message(activity: Activity, title: CharSequence, message: CharSequence) {
        present(
            activity,
            title,
            column(activity) { addView(paragraph(activity, message)) },
            listOf(Action(activity.getString(R.string.dialog_close)) {}),
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
            title,
            column(activity) {
                addView(paragraph(activity, intro))
                commands.forEachIndexed { i, command -> addView(commandBox(activity, i + 1, command)) }
                if (outro != null) addView(paragraph(activity, outro, sizeSp = 15f, topDp = 16))
            },
            listOf(Action(activity.getString(R.string.dialog_close)) {}),
        )
    }

    /** A dialog whose text changes while something runs in the background; its one button stops the waiting. */
    class Progress internal constructor(private val dialog: Dialog, private val text: TextView) {
        fun update(message: CharSequence) {
            text.text = message
        }

        fun dismiss() {
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    fun progress(activity: Activity, title: CharSequence, message: CharSequence, onCancel: () -> Unit): Progress {
        val text = paragraph(activity, message)
        val dialog = present(
            activity,
            title,
            column(activity) { addView(text) },
            listOf(Action(activity.getString(R.string.dialog_cancel), onCancel)),
        )
        return Progress(dialog, text)
    }

    /** A question with two answers; the focus starts on the cancelling one. */
    fun decide(activity: Activity, title: CharSequence, message: CharSequence, confirmLabel: CharSequence, onConfirm: () -> Unit) {
        present(
            activity,
            title,
            column(activity) { addView(paragraph(activity, message)) },
            listOf(
                Action(activity.getString(R.string.dialog_cancel)) {},
                Action(confirmLabel, onConfirm),
            ),
            focus = { it.firstOrNull() },
        )
    }

    /**
     * A question with two answers: [confirmLabel] runs [onConfirm], [cancelLabel] (the one with the focus first) does nothing, and
     * [onDismiss] runs when the dialog is gone whichever way it went (Back too).
     */
    fun prompt(
        activity: Activity,
        title: CharSequence,
        message: CharSequence,
        confirmLabel: CharSequence,
        cancelLabel: CharSequence,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        val dialog = present(
            activity,
            title,
            column(activity) { addView(paragraph(activity, message)) },
            listOf(Action(cancelLabel) {}, Action(confirmLabel, onConfirm)),
            focus = { it.firstOrNull() },
        )
        dialog.setOnDismissListener { onDismiss() }
    }

    /** Scrollable monospace text, for diagnostics and license texts. The text takes the focus so the arrow keys scroll it. */
    fun longText(activity: Activity, title: CharSequence, text: CharSequence) {
        val tv = TextView(activity).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(activity.getColor(R.color.text_primary))
            setPadding(dp(activity, 28), dp(activity, 8), dp(activity, 28), dp(activity, 8))
            setTextIsSelectable(false)
            isFocusable = true
        }
        present(
            activity,
            title,
            tv,
            listOf(Action(activity.getString(R.string.dialog_close)) {}),
            focus = { tv },
        )
    }

    /** A short list to pick one entry from; the focus starts on the current choice. */
    fun choose(activity: Activity, title: Int, items: List<String>, selected: Int, onChoose: (Int) -> Unit) =
        choose(activity, title, items, selected, true, onChoose)

    /** A list of actions, with no mark beside any of them: [first] only says which one has the focus when it opens. */
    fun actions(activity: Activity, title: Int, items: List<String>, first: Int = 0, onChoose: (Int) -> Unit) =
        choose(activity, title, items, first, false, onChoose)

    private fun choose(activity: Activity, title: Int, items: List<String>, selected: Int, marked: Boolean, onChoose: (Int) -> Unit) {
        lateinit var dialog: Dialog
        val rows = ArrayList<View>()
        val list = column(activity) {
            items.forEachIndexed { i, label ->
                val row = TextView(activity).apply {
                    text = if (!marked) label else if (i == selected) "✓  $label" else "     $label"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(dp(activity, 20), dp(activity, 14), dp(activity, 20), dp(activity, 14))
                    isFocusable = true
                    isClickable = true
                    lightsUpOnFocus(activity, this, 14)
                    setOnClickListener {
                        dialog.dismiss()
                        onChoose(i)
                    }
                }
                rows += row
                addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(activity, 4) })
            }
        }
        dialog = present(
            activity,
            activity.getString(title),
            list,
            listOf(Action(activity.getString(R.string.dialog_cancel)) {}),
            focus = { rows.getOrNull(selected) ?: rows.firstOrNull() },
        )
    }
}
