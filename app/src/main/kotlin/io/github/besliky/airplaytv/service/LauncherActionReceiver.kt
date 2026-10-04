package io.github.besliky.airplaytv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.besliky.airplaytv.Settings
import io.github.besliky.airplaytv.lg.TvScenes
import io.github.besliky.airplaytv.lg.TvSettings

/**
 * What the home screen app asks of the receiver beyond the music (see [LauncherLink]): to watch the TV while its TV cell is on, to
 * press a TV tile (wake the TV and apply a scene), to add time to the sleep timer or end it, to open the quick panel, and the weather
 * line for the glance card. Any app on the stick can send such a broadcast, so each does only what the owner could do with the remote,
 * and nothing it carries is trusted beyond a scene name from a fixed list, a short command and a line of text.
 */
class LauncherActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val settings = Settings(app)
        when (intent.action) {
            LauncherLink.ACTION_CONFIG -> settings.launcherTvCue = intent.getBooleanExtra(LauncherLink.EXTRA_TV_CUE, false)
            LauncherLink.ACTION_TV_TILE -> {
                val scene = intent.getStringExtra(LauncherLink.EXTRA_SCENE)
                if (scene != null && scene in TvScenes.IDS && TvSettings.paired(settings)) TvActions.applySceneWaking(app, scene)
            }
            LauncherLink.ACTION_SLEEP_COMMAND -> when (intent.getStringExtra(LauncherLink.EXTRA_COMMAND)) {
                "extend" -> TvSleepTimer.extend(app)
                "cancel" -> TvSleepTimer.start(app, 0)
            }
            LauncherLink.ACTION_OPEN_PANEL -> MenuKeyService.openPanel()
            LauncherLink.ACTION_WEATHER -> {
                val line = intent.getStringExtra(LauncherLink.EXTRA_WEATHER).orEmpty().take(60)
                settings.launcherWeather = line
                settings.launcherWeatherAt = System.currentTimeMillis()
            }
        }
    }
}
