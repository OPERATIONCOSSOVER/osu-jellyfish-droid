package com.osudroid.beatmaps.editor

import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.edlplan.ui.fragment.BaseFragment
import com.reco1l.framework.android.cornerRadius
import com.reco1l.framework.android.dp
import ru.nsu.ccfit.zuev.osuplus.R

class BeatmapSetupFragment : BaseFragment() {
    override val layoutID = R.layout.beatmap_setup_fragment

    private lateinit var initial: EditableBeatmapMetadata
    private lateinit var onApply: (EditableBeatmapMetadata) -> Unit

    init {
        isDismissOnBackgroundClick = true
    }

    override fun onLoadView() {
        findViewById<View>(R.id.fullLayout)!!.cornerRadius = 14f.dp
        field(R.id.editorMetadataTitle).setText(initial.title)
        field(R.id.editorMetadataTitleUnicode).setText(initial.titleUnicode)
        field(R.id.editorMetadataArtist).setText(initial.artist)
        field(R.id.editorMetadataArtistUnicode).setText(initial.artistUnicode)
        field(R.id.editorMetadataCreator).setText(initial.creator)
        field(R.id.editorMetadataVersion).setText(initial.version)
        field(R.id.editorMetadataSource).setText(initial.source)
        field(R.id.editorMetadataTags).setText(initial.tags)

        findViewById<View>(R.id.editorSetupCancel)!!.setOnClickListener { dismiss() }
        findViewById<View>(R.id.editorSetupApply)!!.setOnClickListener {
            val values = EditableBeatmapMetadata(
                title = text(R.id.editorMetadataTitle),
                titleUnicode = text(R.id.editorMetadataTitleUnicode),
                artist = text(R.id.editorMetadataArtist),
                artistUnicode = text(R.id.editorMetadataArtistUnicode),
                creator = text(R.id.editorMetadataCreator),
                version = text(R.id.editorMetadataVersion),
                source = text(R.id.editorMetadataSource),
                tags = text(R.id.editorMetadataTags),
            )
            val error = when {
                values.title.isBlank() -> getString(R.string.editor_title_required)
                values.artist.isBlank() -> getString(R.string.editor_artist_required)
                values.creator.isBlank() -> getString(R.string.editor_creator_required)
                values.version.isBlank() -> getString(R.string.editor_version_required)
                listOf(
                    values.title,
                    values.titleUnicode,
                    values.artist,
                    values.artistUnicode,
                    values.creator,
                    values.version,
                    values.source,
                    values.tags,
                ).any { value -> value.any { it == '\n' || it == '\r' } } -> getString(R.string.editor_line_break_error)
                else -> null
            }

            if (error != null) {
                findViewById<TextView>(R.id.editorSetupError)!!.apply {
                    text = error
                    visibility = View.VISIBLE
                }
            } else {
                onApply(values)
                dismiss()
            }
        }
    }

    fun show(initial: EditableBeatmapMetadata, onApply: (EditableBeatmapMetadata) -> Unit) {
        this.initial = initial
        this.onApply = onApply
        show()
    }

    private fun field(id: Int) = findViewById<EditText>(id)!!
    private fun text(id: Int) = field(id).text.toString().trim()
}
