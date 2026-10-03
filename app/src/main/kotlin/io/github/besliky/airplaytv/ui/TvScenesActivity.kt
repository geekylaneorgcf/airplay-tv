package io.github.besliky.airplaytv.ui

import io.github.besliky.airplaytv.R
import io.github.besliky.airplaytv.lg.TvPicture
import io.github.besliky.airplaytv.lg.TvScenes
import io.github.besliky.airplaytv.lg.TvSound

/**
 * What each scene of the TV quick panel does (see [TvScenes]): the input, the picture mode, the sound output, a volume that is not
 * exceeded, and whether the screen goes off. "Leave" means the scene does not touch that thing.
 */
class TvScenesActivity : SubPage() {

    override val pageTitle = R.string.tv_scenes_title

    private class Field(val row: Row, val id: String, val show: (TvScenes.Scene) -> String)

    private val fields = ArrayList<Field>()

    override fun buildRows() {
        val leave = "Leave"
        val pictures = listOf("") + TvPicture.MODES.map { it.id }
        val sounds = listOf("") + TvSound.OUTPUTS.map { it.id }
        for (id in TvScenes.IDS) {
            val name = TvScenes.label(id)
            field(id, "$name · Input", { TvScenes.inputLabel(it.input) }) { it.copy(input = next(TvScenes.INPUT_CHOICES, it.input)) }
            field(id, "$name · Picture Mode", { if (it.picture.isEmpty()) leave else TvPicture.label(it.picture) }) { it.copy(picture = next(pictures, it.picture)) }
            field(id, "$name · Sound Output", { if (it.sound.isEmpty()) leave else TvSound.label(it.sound) }) { it.copy(sound = next(sounds, it.sound)) }
            field(id, "$name · Volume", { if (it.volume < 0) leave else "At Most ${it.volume}" }) { it.copy(volume = next(TvScenes.VOLUME_CHOICES, it.volume)) }
            field(id, "$name · Screen", { if (it.screenOff) "Off" else leave }) { it.copy(screenOff = !it.screenOff) }
        }
        addRow(getString(R.string.tv_scenes_reset)) {
            settings.resetTvScenes()
            bind()
        }
    }

    private fun field(id: String, title: String, show: (TvScenes.Scene) -> String, edit: (TvScenes.Scene) -> TvScenes.Scene) {
        val row = addRow(title) {
            settings.setTvScene(edit(settings.tvScene(id)))
            bind()
        }
        fields.add(Field(row, id, show))
    }

    override fun bind() {
        for (field in fields) field.row.value(field.show(settings.tvScene(field.id)))
    }
}
