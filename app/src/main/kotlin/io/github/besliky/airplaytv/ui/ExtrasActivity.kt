package io.github.besliky.airplaytv.ui

import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.service.AccessibilitySetup
import io.github.besliky.airplaytv.service.PhoneTypingService

/**
 * The options that are off until chosen: cards over other apps, Hold Home, the TV doing things by itself, and typing on the phone. Each row
 * says on the left, while it has the focus, what it does; each option can be switched on and off here at any time.
 */
class ExtrasActivity : SubPage() {

    override val pageTitle = R.string.extras_title

    private lateinit var glanceRow: Row
    private lateinit var remoteRow: Row
    private lateinit var infoRow: Row
    private lateinit var holdHomeRow: Row
    private lateinit var oledRow: Row
    private lateinit var oledFromRow: Row
    private lateinit var oledToRow: Row
    private lateinit var oledLevelRow: Row
    private lateinit var idleRow: Row
    private lateinit var typingRow: Row

    override fun buildRows() {
        glanceRow = addRow(getString(R.string.extras_glance), getString(R.string.extras_glance_hint)) {
            settings.tvGlance = !settings.tvGlance
            bind()
        }
        remoteRow = addRow(getString(R.string.extras_remote), getString(R.string.extras_remote_hint)) {
            settings.tvMiniRemote = !settings.tvMiniRemote
            bind()
        }
        infoRow = addRow(getString(R.string.extras_info), getString(R.string.extras_info_hint)) {
            settings.tvGlanceInfo = !settings.tvGlanceInfo
            bind()
        }
        holdHomeRow = addRow(getString(R.string.extras_hold_home), getString(R.string.extras_hold_home_hint)) {
            settings.holdHome = !settings.holdHome
            bind()
        }
        oledRow = addRow(getString(R.string.extras_oled), getString(R.string.extras_oled_hint)) {
            settings.tvOledNight = !settings.tvOledNight
            bind()
        }
        oledFromRow = addRow(getString(R.string.extras_oled_from), getString(R.string.extras_oled_from_hint)) {
            settings.tvOledFrom = next(io.github.besliky.airplaytv.Settings.OLED_FROM_CHOICES, settings.tvOledFrom)
            bind()
        }
        oledToRow = addRow(getString(R.string.extras_oled_to), getString(R.string.extras_oled_to_hint)) {
            settings.tvOledTo = next(io.github.besliky.airplaytv.Settings.OLED_TO_CHOICES, settings.tvOledTo)
            bind()
        }
        oledLevelRow = addRow(getString(R.string.extras_oled_level), getString(R.string.extras_oled_level_hint)) {
            settings.tvOledLevel = next(io.github.besliky.airplaytv.Settings.OLED_LEVEL_CHOICES, settings.tvOledLevel)
            bind()
        }
        idleRow = addRow(getString(R.string.extras_idle), getString(R.string.extras_idle_hint)) {
            settings.tvIdleOffHours = next(io.github.besliky.airplaytv.Settings.IDLE_OFF_CHOICES, settings.tvIdleOffHours)
            bind()
        }
        typingRow = addRow(getString(R.string.extras_typing), getString(R.string.extras_typing_hint)) {
            settings.phoneTyping = !settings.phoneTyping
            if (settings.phoneTyping && !PhoneTypingService.isEnabled) {
                val commands = AccessibilitySetup.commands(this, PhoneTypingService.component(packageName))
                Dialogs.message(this, getString(R.string.typing_setup_title), getString(R.string.typing_setup_message, commands[0], commands[1]))
            }
            bind()
        }
    }

    override fun bind() {
        glanceRow.value(onOff(settings.tvGlance))
        remoteRow.value(onOff(settings.tvMiniRemote))
        infoRow.value(onOff(settings.tvGlanceInfo))
        holdHomeRow.value(onOff(settings.holdHome))
        oledRow.value(onOff(settings.tvOledNight))
        oledFromRow.value(hour(settings.tvOledFrom))
        oledToRow.value(hour(settings.tvOledTo))
        oledLevelRow.value(getString(R.string.value_percent_short, settings.tvOledLevel))
        idleRow.value(if (settings.tvIdleOffHours == 0) getString(R.string.value_off) else getString(R.string.value_hours, settings.tvIdleOffHours))
        typingRow.value(
            when {
                !settings.phoneTyping -> getString(R.string.value_off)
                PhoneTypingService.isEnabled -> getString(R.string.value_on)
                else -> getString(R.string.value_on_needs_setup)
            },
        )
        oledFromRow.visible(settings.tvOledNight)
        oledToRow.visible(settings.tvOledNight)
        oledLevelRow.visible(settings.tvOledNight)
    }
}
