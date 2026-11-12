package com.audiomixer.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private class Row(val track: Track, val box: LinearLayout, val info: View, val meta: TextView, val tag: TextView)
    private class Published(val uri: Uri?, val where: String)

    private val tracks = ArrayList<Track>()
    private val rows = ArrayList<Row>()
    private var nextId = 1
    private var activeIndex = -1
    private var pendingReplace = false
    private var askedStorage = false
    private var seeking = false
    private var syncing = false
    private var quietUntil = 0L
    private var wasPlaying = false
    private var lastNames: List<String> = emptyList()
    @Volatile private var cancelExport = false

    private lateinit var player: MixPlayer
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var scroll: ScrollView
    private lateinit var fileList: LinearLayout
    private lateinit var timeline: TimelineView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var importBtn: Button
    private lateinit var addBtn: Button
    private lateinit var summaryBtn: Button
    private lateinit var play1Btn: Button
    private lateinit var play2Btn: Button
    private lateinit var finalBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var fileSpinner: Spinner
    private lateinit var modeSpinner: Spinner
    private lateinit var chkAfter: CheckBox
    private lateinit var chkBefore: CheckBox
    private lateinit var chkMix: CheckBox
    private lateinit var chkOver: CheckBox
    private lateinit var posSlider: SeekBar
    private lateinit var posValue: TextView
    private lateinit var posInfo: TextView
    private lateinit var posDown: Button
    private lateinit var posUp: Button
    private lateinit var volSlider: SeekBar
    private lateinit var volValue: TextView
    private lateinit var volInfo: TextView
    private lateinit var volDown: Button
    private lateinit var volUp: Button
    private lateinit var chkMute: CheckBox
    private lateinit var chkLock: CheckBox
    private lateinit var markBtn: Button
    private lateinit var removeBtn: Button
    private lateinit var totalInfo: TextView
    private lateinit var headInfo: TextView
    private lateinit var finalSeek: SeekBar

    private val ticker = object : Runnable {
        override fun run() {
            refreshHead()
            if (player.playing) ui.postDelayed(this, 100)
        }
    }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.pause()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("am_") || it.name.startsWith("AudioMixer_")) it.delete()
        }
        player = MixPlayer(this)
        player.setStateListener { ui.post { onPlayerState() } }
        try {
            val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(noisy, f)
        } catch (_: Throwable) {
        }
        setContentView(buildUi())
        refreshAll()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lastNames = emptyList()
        setContentView(buildUi())
        refreshAll()
        onPlayerState()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(noisy) } catch (_: Throwable) {}
        ui.removeCallbacksAndMessages(null)
        player.pause()
        worker.shutdownNow()
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    // ------------------------------------------------------------------ UI construction

    @Suppress("DEPRECATION")
    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(UiKit.dp(this, 14f), UiKit.dp(this, 16f), UiKit.dp(this, 14f), UiKit.dp(this, 24f))

        scroll = ScrollView(this)
        scroll.setBackgroundColor(Palette.BG)
        scroll.clipToPadding = false
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val l: Int
            val t: Int
            val r: Int
            val b: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = bars.left; t = bars.top; r = bars.right; b = bars.bottom
            } else {
                l = insets.systemWindowInsetLeft; t = insets.systemWindowInsetTop
                r = insets.systemWindowInsetRight; b = insets.systemWindowInsetBottom
            }
            v.setPadding(l, t, r, b)
            insets
        }

        val title = UiKit.text(this, "Audio Mixer", 26f, Palette.TEXT, true)
        title.isAccessibilityHeading = true
        root.addView(title)
        statusText = UiKit.text(this, "Welcome. Press Import File to choose your first audio.", 15f, Palette.GOLD)
        statusText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        root.addView(statusText, UiKit.lp(this, 4f, 6f))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progressTintList = ColorStateList.valueOf(Palette.ACCENT)
        progress.visibility = View.GONE
        progress.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 5f)).also {
            it.bottomMargin = UiKit.dp(this, 8f)
        })

        // Timeline overview (one lane per file, gold outline = locked, line = playhead)
        timeline = TimelineView(this)
        timeline.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        timeline.contentDescription = "Timeline is empty"
        val tlWrap = LinearLayout(this)
        tlWrap.orientation = LinearLayout.VERTICAL
        tlWrap.background = UiKit.bg(Palette.CARD2, 12f, Palette.LINE, 1f, this)
        tlWrap.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(tlWrap, UiKit.lp(this, 0f, 12f))

        // ---- 1. Files
        val filesCard = UiKit.card(this)
        filesCard.addView(UiKit.sectionTitle(this, "1", "FILES", "Step 1. Files"))
        importBtn = UiKit.button(this, "Import File", true)
        importBtn.contentDescription = "Import File. Choose the first audio from your file manager"
        importBtn.setOnClickListener { onImportClicked() }
        filesCard.addView(importBtn, UiKit.lp(this, 0f, 8f))
        addBtn = UiKit.button(this, "Add Another File", false)
        addBtn.setOnClickListener { pendingReplace = false; pickFile() }
        filesCard.addView(addBtn, UiKit.lp(this, 0f, 8f))
        summaryBtn = UiKit.button(this, "Read Mix Summary", false)
        summaryBtn.contentDescription = "Read mix summary. Lists every file, how it is placed and its volume"
        summaryBtn.setOnClickListener { showSummary() }
        filesCard.addView(summaryBtn, UiKit.lp(this, 0f, 6f))
        filesCard.addView(UiKit.hint(this, "All audio formats are accepted. The last added file is selected automatically for the settings in step 3."), UiKit.lp(this, 0f, 8f))
        fileList = LinearLayout(this)
        fileList.orientation = LinearLayout.VERTICAL
        filesCard.addView(fileList)
        root.addView(filesCard)

        // ---- 2. Play one file
        val playCard = UiKit.card(this)
        playCard.addView(UiKit.sectionTitle(this, "2", "PLAY ONE FILE", "Step 2. Play one file"))
        val playRow = UiKit.row(this)
        play1Btn = UiKit.button(this, "Play 1", false)
        play2Btn = UiKit.button(this, "Play 2", false)
        play1Btn.setOnClickListener { toggleFirst() }
        play2Btn.setOnClickListener { toggleLast() }
        playRow.addView(play1Btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = UiKit.dp(this, 8f) })
        playRow.addView(play2Btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        playCard.addView(playRow)
        playCard.addView(UiKit.hint(this, "Play 1 plays only the first audio. Play 2 plays only the last added audio, to check it was added correctly."), UiKit.lp(this, 8f))
        root.addView(playCard)

        // ---- 3. Settings for the selected file
        val set = UiKit.card(this)
        set.addView(UiKit.sectionTitle(this, "3", "SETTINGS OF THE SELECTED FILE", "Step 3. Settings of the selected file"))

        set.addView(UiKit.subHeading(this, "Selected file"))
        fileSpinner = UiKit.spinner(this, "Selected file. Choose which file the settings below change")
        fileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (syncing || pos == activeIndex || pos !in tracks.indices) return
                selectIndex(pos, true)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        set.addView(fileSpinner, UiKit.lp(this))

        set.addView(UiKit.subHeading(this, "Placement: merge, mix or override"))
        modeSpinner = UiKit.spinner(this, "Placement mode combo box")
        modeSpinner.adapter = DarkAdapter(this, MODE_LABELS)
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (syncing) return
                val t = activeTrack() ?: return
                if (t.anchor || t.locked || pos == modeOf(t.slider)) return
                applyMode(t, pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        set.addView(modeSpinner, UiKit.lp(this, 0f, 4f))
        chkAfter = UiKit.check(this, "Merge after: play right after, no gap")
        chkBefore = UiKit.check(this, "Merge before: play first, then the first file")
        chkMix = UiKit.check(this, "Mix: play together on top")
        chkOver = UiKit.check(this, "Override: partly overlap, use the slider")
        val checks = listOf(chkAfter, chkBefore, chkMix, chkOver)
        for ((mode, c) in checks.withIndex()) {
            c.setOnCheckedChangeListener { _, checked ->
                if (syncing) return@setOnCheckedChangeListener
                val t = activeTrack() ?: return@setOnCheckedChangeListener
                if (t.anchor || t.locked) { syncSettings(); return@setOnCheckedChangeListener }
                if (!checked) {
                    syncSettings()
                    say("One placement is always active. Check another one to change it")
                } else applyMode(t, mode)
            }
            set.addView(c, UiKit.lp(this))
        }

        set.addView(UiKit.subHeading(this, "Position slider, 0 to 100"))
        posValue = UiKit.text(this, "50", 28f, Palette.ACCENT, true)
        posValue.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        set.addView(posValue)
        posInfo = UiKit.hint(this, "")
        set.addView(posInfo, UiKit.lp(this, 0f, 4f))
        posSlider = UiKit.slider(this)
        posSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (!syncing) onPositionSlider(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        set.addView(posSlider, UiKit.lp(this))
        val posRow = UiKit.row(this)
        posDown = UiKit.button(this, "Earlier −1", false)
        posUp = UiKit.button(this, "Later +1", false)
        posDown.contentDescription = "Move earlier by one step"
        posUp.contentDescription = "Move later by one step"
        posDown.setOnClickListener { nudgePos(-1) }
        posUp.setOnClickListener { nudgePos(1) }
        posRow.addView(posDown, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = UiKit.dp(this, 8f) })
        posRow.addView(posUp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        set.addView(posRow, UiKit.lp(this, 4f))
        set.addView(UiKit.hint(this, "100 = exactly after everything before it. 50 = on top, together. 0 = plays completely before the first file. In between = partial override. Changes are heard instantly while Final Play runs."), UiKit.lp(this, 6f))

        set.addView(UiKit.subHeading(this, "Volume of this file, 0 to 100"))
        volValue = UiKit.text(this, "100 %", 28f, Palette.GOLD, true)
        volValue.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        set.addView(volValue)
        volInfo = UiKit.hint(this, "")
        set.addView(volInfo, UiKit.lp(this, 0f, 4f))
        volSlider = UiKit.slider(this)
        volSlider.progressTintList = ColorStateList.valueOf(Palette.GOLD)
        volSlider.thumbTintList = ColorStateList.valueOf(Palette.GOLD)
        volSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (!syncing) onVolumeSlider(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        set.addView(volSlider, UiKit.lp(this))
        val volRow = UiKit.row(this)
        volDown = UiKit.button(this, "Quieter −5", false)
        volUp = UiKit.button(this, "Louder +5", false)
        volDown.contentDescription = "Make this file quieter by five"
        volUp.contentDescription = "Make this file louder by five"
        volDown.setOnClickListener { nudgeVol(-5) }
        volUp.setOnClickListener { nudgeVol(5) }
        volRow.addView(volDown, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = UiKit.dp(this, 8f) })
        volRow.addView(volUp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        set.addView(volRow, UiKit.lp(this, 4f))
        chkMute = UiKit.check(this, "Mute this file")
        chkMute.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            val t = activeTrack() ?: return@setOnCheckedChangeListener
            if (t.anchor) { syncSettings(); return@setOnCheckedChangeListener }
            t.muted = checked
            refreshLight()
            say("File ${indexOf(t) + 1} ${if (checked) "muted" else "unmuted"}")
        }
        set.addView(chkMute, UiKit.lp(this, 4f))
        set.addView(UiKit.hint(this, "100 = full, equal to the first file. 50 = half, lightly under it. 0 = off. The first file always keeps its own voice."), UiKit.lp(this, 2f))

        set.addView(UiKit.subHeading(this, "Lock position"))
        markBtn = UiKit.button(this, "Add Mark", false)
        markBtn.contentDescription = "Add Mark. Locks the selected file at its current place"
        markBtn.setOnClickListener { addMark() }
        set.addView(markBtn, UiKit.lp(this, 0f, 4f))
        chkLock = UiKit.check(this, "Marked: this file is locked and will not move")
        chkLock.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            val t = activeTrack() ?: return@setOnCheckedChangeListener
            if (t.anchor) { syncSettings(); return@setOnCheckedChangeListener }
            if (checked) addMark() else unlock(t)
        }
        set.addView(chkLock, UiKit.lp(this))
        removeBtn = UiKit.button(this, "Remove Selected File", false)
        removeBtn.setOnClickListener { confirmRemove() }
        set.addView(removeBtn, UiKit.lp(this, 10f))
        root.addView(set)

        // ---- 4. Final play
        val finalCard = UiKit.card(this)
        finalCard.addView(UiKit.sectionTitle(this, "4", "FINAL PLAY: LIVE CONTROL", "Step 4. Final play with live control"))
        finalBtn = UiKit.button(this, "Final Play", true)
        finalBtn.setOnClickListener { toggleFinal() }
        finalCard.addView(finalBtn, UiKit.lp(this, 0f, 8f))
        headInfo = UiKit.text(this, "Ready", 15f, Palette.TEXT)
        finalCard.addView(headInfo)
        totalInfo = UiKit.text(this, "", 13.5f, Palette.GOLD)
        finalCard.addView(totalInfo, UiKit.lp(this, 4f))
        finalSeek = UiKit.slider(this)
        finalSeek.max = 1000
        finalSeek.contentDescription = "Final mix playhead. Move to jump to another time"
        finalSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser && !seeking) seekToSlider(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                seekToSlider(sb?.progress ?: 0)
            }
        })
        finalCard.addView(finalSeek, UiKit.lp(this, 6f))
        finalCard.addView(UiKit.hint(this, "While it plays, go back to step 3: change placement, position, volume, mute or Add Mark and hear the result immediately."), UiKit.lp(this, 4f))
        root.addView(finalCard)

        // ---- 5. Save
        val saveCard = UiKit.card(this)
        saveCard.addView(UiKit.sectionTitle(this, "5", "SAVE", "Step 5. Save"))
        saveBtn = UiKit.button(this, "Save", true)
        saveBtn.contentDescription = "Save. Choose Save in WAV or Save in MP3"
        saveBtn.setOnClickListener { askSave() }
        saveCard.addView(saveBtn)
        saveCard.addView(UiKit.hint(this, "Saves the full mix as one continuous file, exactly as you heard it."), UiKit.lp(this, 8f))
        root.addView(saveCard)

        return scroll
    }

    // ------------------------------------------------------------------ file picking

    private fun onImportClicked() {
        if (tracks.isEmpty()) {
            pendingReplace = false
            pickFile()
        } else {
            dialog().setTitle("Start a new mix?")
                .setMessage("Import File replaces the current ${tracks.size} file(s) and all marks. Use Add Another File to keep them.")
                .setPositiveButton("Replace") { _, _ -> pendingReplace = true; pickFile() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, PICK)
        } catch (e: Throwable) {
            say("No file manager found")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            pendingReplace = false
            say("No file chosen")
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {
        }
        if (pendingReplace) {
            pendingReplace = false
            clearAll()
        }
        addTrack(uri)
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "audio"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && c.columnCount > 0) {
                    val v = c.getString(0)
                    if (!v.isNullOrBlank()) name = v
                }
            }
        } catch (_: Throwable) {
        }
        return name
    }

    private fun clearAll() {
        muteStopMessage()
        player.pause()
        for (t in tracks) t.pcm?.delete()
        tracks.clear()
        player.resetPositions()
        activeIndex = -1
        refreshAll()
    }

    private fun addTrack(uri: Uri) {
        if (tracks.size >= MAX_FILES) {
            say("Maximum $MAX_FILES files")
            return
        }
        muteStopMessage()
        player.pause()
        val t = Track(nextId++, displayName(uri), uri, tracks.isEmpty())
        tracks.add(t)
        activeIndex = tracks.size - 1
        refreshAll()
        busy("Decoding file ${tracks.size}: ${t.name}. Please wait.")
        worker.execute {
            var res: Decoder.Result? = null
            try {
                res = Decoder.decode(this, uri, File(cacheDir, "am_${t.id}.pcm"))
            } catch (_: Throwable) {
            }
            val done = res
            ui.post {
                progress.visibility = View.GONE
                if (!tracks.contains(t)) {
                    done?.file?.delete()
                    return@post
                }
                t.decoding = false
                if (done != null) {
                    t.pcm = done.file
                    t.frames = done.frames
                    recomputeOffsets()
                    refreshAll()
                    val n = tracks.indexOf(t) + 1
                    say(
                        "File $n ready, ${Fmt.spoken(t.frames)} long: ${t.name}. " +
                            if (t.anchor) "It is the base file. Now press Add Another File, or Play 1."
                            else "It is selected. Choose how to place it in step 3, or press Play 2 to check it."
                    )
                } else {
                    removeInternal(t)
                    say("Could not read ${t.name} as audio, so it was not added")
                }
            }
        }
    }

    // ------------------------------------------------------------------ placement logic

    /**
     * s = 100: starts exactly where everything placed before it ends (seamless merge after)
     * s = 50 : starts at 0, fully on top of the first file (mix)
     * s = 0  : plays completely before the first file (merge before)
     * anything else: partial override. The first file never moves; locked files never move.
     */
    private fun recomputeOffsets() {
        if (tracks.isEmpty()) return
        tracks[0].offsetFrames = 0L
        tracks[0].gain = 1f
        var hi = tracks[0].frames
        for (i in 1 until tracks.size) {
            val t = tracks[i]
            if (t.frames > 0 && !t.locked) {
                val s = t.slider.coerceIn(0, 100)
                t.offsetFrames = if (s >= 50) (s - 50).toLong() * hi / 50L else -((50L - s) * t.frames / 50L)
            }
            if (t.frames > 0) hi = maxOf(hi, t.offsetFrames + t.frames)
        }
    }

    private fun hiBefore(index: Int): Long {
        var hi = 0L
        for (j in 0 until index) if (tracks[j].frames > 0) hi = maxOf(hi, tracks[j].offsetFrames + tracks[j].frames)
        return hi
    }

    private fun modeOf(slider: Int): Int = when {
        slider >= 100 -> MODE_AFTER
        slider <= 0 -> MODE_BEFORE
        slider == 50 -> MODE_MIX
        else -> MODE_OVERRIDE
    }

    private fun applyMode(t: Track, mode: Int) {
        if (t.anchor || t.locked) return
        t.slider = when (mode) {
            MODE_AFTER -> 100
            MODE_BEFORE -> 0
            MODE_MIX -> 50
            else -> if (t.slider in 1..99 && t.slider != 50) t.slider else 75
        }
        recomputeOffsets()
        refreshLight()
        say("${MODE_NAMES[mode]}. ${startSpoken(t)}. Mix length ${Fmt.spoken(mixTotal())}")
    }

    private fun onPositionSlider(value: Int) {
        val t = activeTrack() ?: return
        if (t.anchor || t.locked) {
            syncSettings()
            return
        }
        t.slider = value
        recomputeOffsets()
        refreshLight()
    }

    private fun nudgePos(d: Int) {
        val t = activeTrack() ?: run { say("Import a file first"); return }
        if (t.anchor) { say("File 1 is the base and stays fixed"); return }
        if (t.locked) { say("This file is locked. Uncheck Marked to move it"); return }
        val v = (t.slider + d).coerceIn(0, 100)
        t.slider = v
        recomputeOffsets()
        refreshLight()
        say("Position $v. ${MODE_NAMES[modeOf(v)]}. ${startSpoken(t)}")
    }

    private fun onVolumeSlider(value: Int) {
        val t = activeTrack() ?: return
        if (t.anchor) {
            syncSettings()
            return
        }
        t.gain = value / 100f
        refreshLight()
    }

    private fun nudgeVol(d: Int) {
        val t = activeTrack() ?: run { say("Import a file first"); return }
        if (t.anchor) { say("The first file keeps its original voice"); return }
        val v = (Math.round(t.gain * 100f) + d).coerceIn(0, 100)
        t.gain = v / 100f
        refreshLight()
        say("Volume $v percent" + if (t.muted) ", but the file is muted" else "")
    }

    private fun addMark() {
        val t = activeTrack() ?: run { say("Import a file first"); return }
        if (t.anchor) { say("File 1 is the base and is always fixed"); return }
        if (t.frames <= 0) { say("Wait until the file is decoded"); return }
        if (t.locked) { say("File ${indexOf(t) + 1} is already marked"); return }
        t.locked = true
        refreshLight()
        say("File ${indexOf(t) + 1} locked. ${startSpoken(t)}. It will not move again")
    }

    private fun unlock(t: Track) {
        val i = indexOf(t)
        if (i > 0) {
            val hi = hiBefore(i)
            val off = t.offsetFrames
            t.slider = if (off >= 0) {
                if (hi > 0) (50 + off * 50 / hi).toInt().coerceIn(50, 100) else 50
            } else {
                (50 - (-off) * 50 / maxOf(1L, t.frames)).toInt().coerceIn(0, 50)
            }
        }
        t.locked = false
        recomputeOffsets()
        refreshLight()
        say("File ${i + 1} unlocked")
    }

    private fun removeInternal(t: Track) {
        val i = tracks.indexOf(t)
        if (i < 0) return
        tracks.removeAt(i)
        t.pcm?.delete()
        if (tracks.isNotEmpty()) tracks[0].anchor = true
        activeIndex = if (tracks.isEmpty()) -1 else (tracks.size - 1).coerceAtMost(activeIndex.coerceAtLeast(0))
        recomputeOffsets()
        refreshAll()
    }

    private fun confirmRemove() {
        val t = activeTrack() ?: run { say("There is no file to remove"); return }
        dialog().setTitle("Remove file ${indexOf(t) + 1}?")
            .setMessage(t.name)
            .setPositiveButton("Remove") { _, _ -> removeTrack(t) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeTrack(t: Track) {
        muteStopMessage()
        player.pause()
        val n = indexOf(t) + 1
        removeInternal(t)
        player.resetPositions()
        say("File $n removed. ${tracks.size} file(s) left")
        refreshHead()
    }

    private fun selectIndex(i: Int, announce: Boolean) {
        if (i !in tracks.indices) return
        activeIndex = i
        refreshLight()
        if (announce) {
            val t = tracks[i]
            say("File ${i + 1} selected: ${t.name}. " + if (t.anchor) "Base file." else "${MODE_NAMES[modeOf(t.slider)]}. ${startSpoken(t)}.")
        }
    }

    // ------------------------------------------------------------------ playback

    private fun toggleFirst() {
        val t = tracks.firstOrNull() ?: run { say("Import a file first"); return }
        if (t.frames <= 0) { say("Still decoding"); return }
        userToggle(MixPlayer.Mode.FIRST, t, "Play 1")
    }

    private fun toggleLast() {
        val t = tracks.lastOrNull() ?: run { say("Add a file first"); return }
        if (t.frames <= 0) { say("Still decoding"); return }
        userToggle(MixPlayer.Mode.LAST, t, "Play 2, file ${tracks.size}")
    }

    private fun toggleFinal() {
        if (tracks.isEmpty()) { say("Import a file first"); return }
        if (tracks.all { it.frames <= 0 }) { say("Still decoding"); return }
        userToggle(MixPlayer.Mode.FINAL, null, "Final Play")
    }

    private fun userToggle(m: MixPlayer.Mode, preview: Track?, label: String) {
        val wasThis = player.playing && player.mode == m
        muteStopMessage()
        player.toggle(m, preview, tracks)
        if (wasThis) {
            val (pos, _, _) = player.position(tracks)
            say("$label paused at ${Fmt.spoken(pos)}")
        } else if (player.playing) {
            say(if (m == MixPlayer.Mode.FINAL) "Final Play started. You can change settings now and hear it live" else "$label started")
        }
    }

    private fun seekToSlider(value: Int) {
        val usable = tracks.filter { it.frames > 0 }
        if (usable.isEmpty()) return
        val total = MixLayout.total(usable, MixLayout.origin(usable))
        player.seekFinal(tracks, total * value / 1000L)
        refreshHead()
    }

    private fun onPlayerState() {
        val playing = player.playing
        scroll.keepScreenOn = playing
        ui.removeCallbacks(ticker)
        if (playing) ui.post(ticker)
        refreshHead()
        syncButtons()
        if (wasPlaying && !playing && android.os.SystemClock.uptimeMillis() > quietUntil) say("Playback stopped")
        wasPlaying = playing
    }

    private fun refreshHead() {
        val (pos, total, mode) = player.position(tracks)
        val playing = player.playing
        if (mode == MixPlayer.Mode.NONE || total <= 0L || (!playing && pos == 0L)) {
            headInfo.text = "Ready"
            headInfo.contentDescription = "Ready"
            timeline.setHead(-1f)
            if (!seeking && mode != MixPlayer.Mode.FINAL) finalSeek.progress = 0
            return
        }
        val label = when (mode) {
            MixPlayer.Mode.FIRST -> "Play 1"
            MixPlayer.Mode.LAST -> "Play 2"
            else -> "Final mix"
        }
        headInfo.text = "${if (playing) "" else "Paused: "}$label  ${Fmt.time(pos)} / ${Fmt.time(total)}"
        headInfo.contentDescription = "$label, ${if (playing) "playing" else "paused"} at ${Fmt.spoken(pos)} of ${Fmt.spoken(total)}"

        val usable = tracks.filter { it.frames > 0 }
        val origin = MixLayout.origin(usable)
        val mixTotal = MixLayout.total(usable, origin)
        val abs = when (mode) {
            MixPlayer.Mode.FINAL -> pos
            MixPlayer.Mode.FIRST -> (tracks.firstOrNull()?.offsetFrames ?: 0L) + pos - origin
            else -> (tracks.lastOrNull()?.offsetFrames ?: 0L) + pos - origin
        }
        timeline.setHead(abs.toFloat() / mixTotal)
        if (mode == MixPlayer.Mode.FINAL && !seeking) finalSeek.progress = (pos * 1000L / total).toInt()
    }

    private fun syncButtons() {
        val m = player.mode
        val ready1 = (tracks.firstOrNull()?.frames ?: 0L) > 0
        val ready2 = (tracks.lastOrNull()?.frames ?: 0L) > 0
        val readyF = tracks.any { it.frames > 0 }
        val p1 = m == MixPlayer.Mode.FIRST
        val p2 = m == MixPlayer.Mode.LAST
        val pf = m == MixPlayer.Mode.FINAL
        play1Btn.text = if (p1) "Pause 1" else "Play 1"
        play2Btn.text = if (p2) "Pause 2" else "Play 2"
        finalBtn.text = if (pf) "Final Pause" else "Final Play"
        play1Btn.contentDescription = if (p1) "Pause first audio" else "Play first audio"
        play2Btn.contentDescription = if (p2) "Pause last added audio" else "Play last added audio"
        finalBtn.contentDescription = if (pf) "Final Pause. Stops all audios together" else "Final Play. Plays all audios together with live control"
        for ((b, ok) in listOf(play1Btn to ready1, play2Btn to ready2, finalBtn to readyF)) {
            b.isEnabled = ok
            b.alpha = if (ok) 1f else 0.45f
        }
        saveBtn.isEnabled = readyF
        saveBtn.alpha = if (readyF) 1f else 0.45f
        summaryBtn.isEnabled = tracks.isNotEmpty()
        summaryBtn.alpha = if (tracks.isNotEmpty()) 1f else 0.45f
    }

    // ------------------------------------------------------------------ save

    private fun askSave() {
        if (tracks.none { it.frames > 0 }) {
            say("Nothing to save yet")
            return
        }
        if (Build.VERSION.SDK_INT <= 28 && !askedStorage &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            askedStorage = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        showSaveDialog()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) showSaveDialog()
    }

    private fun showSaveDialog() {
        dialog().setTitle("Save")
            .setMessage("Saves the full mix as one continuous file, exactly as you heard it.")
            .setPositiveButton("Save in WAV") { _, _ -> doExport(Exporter.Format.WAV) }
            .setNegativeButton("Save in MP3") { _, _ -> doExport(Exporter.Format.MP3) }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun doExport(format: Exporter.Format) {
        muteStopMessage()
        player.pause()
        cancelExport = false
        val stamp = android.text.format.DateFormat.format("yyyyMMdd_HHmmss", System.currentTimeMillis())
        val ext = if (format == Exporter.Format.WAV) "wav" else "mp3"
        val name = "AudioMixer_$stamp.$ext"
        val mime = if (format == Exporter.Format.WAV) "audio/wav" else "audio/mpeg"
        val tmp = File(cacheDir, name)
        val snapshot = ArrayList(tracks)

        val label = UiKit.text(this, "Mixing and encoding. Please wait…", 16f, Palette.TEXT)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progressTintList = ColorStateList.valueOf(Palette.ACCENT)
        bar.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val p = UiKit.dp(this, 22f)
        box.setPadding(p, UiKit.dp(this, 12f), p, UiKit.dp(this, 6f))
        box.addView(label)
        box.addView(bar, UiKit.lp(this, 12f))
        val dlg = dialog().setTitle("Saving ${ext.uppercase()}").setView(box).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelExport = true }
            .create()
        dlg.show()
        say("Saving $ext. Please wait")

        worker.execute {
            var last = -1
            try {
                Exporter.export(snapshot, format, tmp, { pct ->
                    if (pct != last) {
                        last = pct
                        ui.post {
                            bar.progress = pct
                            label.text = "Saving… $pct %"
                            if (pct % 25 == 0 && pct in 1..99) say("Saving, $pct percent")
                        }
                    }
                }, { cancelExport })
                val pub = publish(tmp, name, mime)
                tmp.delete()
                ui.post {
                    dlg.dismiss()
                    say("Saved $name in ${pub.where}")
                    showSaved(name, mime, pub)
                }
            } catch (e: CancellationException) {
                tmp.delete()
                ui.post { dlg.dismiss(); say("Save cancelled") }
            } catch (e: Throwable) {
                tmp.delete()
                ui.post { dlg.dismiss(); say("Save failed: ${e.message ?: "unknown error"}") }
            }
        }
    }

    private fun publish(file: File, name: String, mime: String): Published {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Audio Mixer")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create the file in Music")
            try {
                val os = contentResolver.openOutputStream(uri) ?: throw IOException("Could not write the file")
                os.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 16) } }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (e: Throwable) {
                try { contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
                throw e
            }
            return Published(uri, "Music, folder Audio Mixer")
        }
        val canWrite = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val base = if (canWrite) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        else (getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir)
        val dir = File(base, "Audio Mixer")
        dir.mkdirs()
        val dest = File(dir, name)
        file.inputStream().use { ins -> dest.outputStream().use { ins.copyTo(it, 1 shl 16) } }
        var result: Uri? = null
        if (canWrite) {
            val latch = CountDownLatch(1)
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf(mime)) { _, u ->
                result = u
                latch.countDown()
            }
            latch.await(4, TimeUnit.SECONDS)
        }
        return Published(result, if (canWrite) "Music, folder Audio Mixer" else dir.absolutePath)
    }

    private fun showSaved(name: String, mime: String, pub: Published) {
        val b = dialog().setTitle("Saved").setMessage("$name\nFolder: ${pub.where}")
        val uri = pub.uri
        if (uri != null) {
            b.setPositiveButton("Open") { _, _ ->
                try {
                    val i = Intent(Intent.ACTION_VIEW)
                    i.setDataAndType(uri, mime)
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(i)
                } catch (_: Throwable) {
                    say("No audio player found")
                }
            }
            b.setNegativeButton("Share") { _, _ ->
                val i = Intent(Intent.ACTION_SEND)
                i.type = mime
                i.putExtra(Intent.EXTRA_STREAM, uri)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(Intent.createChooser(i, "Share mix"))
                } catch (_: Throwable) {
                    say("No app to share with")
                }
            }
        }
        b.setNeutralButton("Close", null)
        b.show()
    }

    // ------------------------------------------------------------------ refresh

    private fun activeTrack(): Track? {
        if (tracks.isEmpty()) return null
        if (activeIndex !in tracks.indices) activeIndex = tracks.size - 1
        return tracks[activeIndex]
    }

    private fun indexOf(t: Track): Int = tracks.indexOf(t)

    private fun mixTotal(): Long {
        val usable = tracks.filter { it.frames > 0 }
        if (usable.isEmpty()) return 0L
        return MixLayout.total(usable, MixLayout.origin(usable))
    }

    /** Structural change (files added or removed): rebuild the list. */
    private fun refreshAll() {
        buildRows()
        refreshLight()
        refreshHead()
    }

    /** Cheap in-place update used while sliders move: never rebuilds views. */
    private fun refreshLight() {
        updateRowTexts()
        updateTimeline()
        updateInfoLabels()
        syncSettings()
        syncButtons()
        addBtn.isEnabled = tracks.isNotEmpty()
        addBtn.alpha = if (tracks.isNotEmpty()) 1f else 0.45f
        addBtn.contentDescription = if (tracks.isEmpty()) "Add Another File. Available after the first file is imported" else "Add Another File"
    }

    private fun syncSettings() {
        syncing = true
        try {
            val t = activeTrack()
            val names = tracks.mapIndexed { i, x -> "File ${i + 1}: ${x.name}" }
            if (names != lastNames) {
                lastNames = names
                fileSpinner.adapter = DarkAdapter(this, if (names.isEmpty()) listOf("No files yet") else names)
            }
            if (t != null && fileSpinner.selectedItemPosition != activeIndex) fileSpinner.setSelection(activeIndex, false)

            val free = t != null && !t.anchor && !t.locked && t.frames > 0
            val mode = if (t == null) MODE_MIX else modeOf(t.slider)
            if (modeSpinner.selectedItemPosition != mode) modeSpinner.setSelection(mode, false)
            chkAfter.isChecked = t != null && !t.anchor && mode == MODE_AFTER
            chkBefore.isChecked = t != null && !t.anchor && mode == MODE_BEFORE
            chkMix.isChecked = t != null && !t.anchor && mode == MODE_MIX
            chkOver.isChecked = t != null && !t.anchor && mode == MODE_OVERRIDE
            for (v in listOf<View>(modeSpinner, chkAfter, chkBefore, chkMix, chkOver, posSlider, posDown, posUp)) {
                v.isEnabled = free
                v.alpha = if (free) 1f else 0.45f
            }
            fileSpinner.isEnabled = tracks.isNotEmpty()
            fileSpinner.alpha = if (tracks.isNotEmpty()) 1f else 0.45f

            val volOk = t != null && !t.anchor && t.frames > 0
            for (v in listOf<View>(volSlider, volDown, volUp, chkMute)) {
                v.isEnabled = volOk
                v.alpha = if (volOk) 1f else 0.45f
            }
            val markOk = t != null && !t.anchor && t.frames > 0
            for (v in listOf<View>(markBtn, chkLock)) {
                v.isEnabled = markOk
                v.alpha = if (markOk) 1f else 0.45f
            }
            removeBtn.isEnabled = t != null
            removeBtn.alpha = if (t != null) 1f else 0.45f

            posSlider.progress = t?.slider ?: 50
            val vol = Math.round((t?.gain ?: 1f) * 100f).coerceIn(0, 100)
            volSlider.progress = if (t != null && t.anchor) 100 else vol
            chkMute.isChecked = t?.muted ?: false
            chkLock.isChecked = t?.locked ?: false
            posValue.text = (t?.slider ?: 50).toString()
            volValue.text = if (t != null && t.anchor) "100 %" else "$vol %"
            describeSliders(t)
        } finally {
            syncing = false
        }
    }

    private fun describeSliders(t: Track?) {
        if (t == null) {
            posSlider.contentDescription = "Position slider. Import a file first"
            volSlider.contentDescription = "Volume slider. Import a file first"
            return
        }
        val n = indexOf(t) + 1
        posSlider.contentDescription = "Position slider of file $n, 0 to 100"
        volSlider.contentDescription = "Volume slider of file $n, 0 to 100"
        if (Build.VERSION.SDK_INT >= 30) {
            posSlider.stateDescription = if (t.anchor) "Base file, fixed" else "${t.slider}. ${MODE_NAMES[modeOf(t.slider)]}. ${startSpoken(t)}"
            volSlider.stateDescription = if (t.anchor) "Original volume" else "${Math.round(t.gain * 100f)} percent" + if (t.muted) ", muted" else ""
        }
    }

    private fun startSpoken(t: Track): String = when {
        t.anchor -> "Base file, starts at zero"
        t.offsetFrames == 0L -> "Starts together with file 1"
        t.offsetFrames > 0 -> "Starts ${Fmt.spoken(t.offsetFrames)} after file 1 begins"
        else -> "Starts ${Fmt.spoken(-t.offsetFrames)} before file 1 begins"
    }

    private fun updateInfoLabels() {
        val t = activeTrack()
        val total = mixTotal()
        posInfo.text = when {
            t == null -> "Import a file to start."
            t.anchor -> "File 1 is the base. It always starts at 00:00.0 with its original volume."
            else -> "File ${indexOf(t) + 1}: ${t.name}\n${MODE_NAMES[modeOf(t.slider)]} · start ${Fmt.signed(t.offsetFrames)} from file 1" +
                if (t.locked) " · LOCKED" else ""
        }
        volInfo.text = when {
            t == null -> "Import a file to start."
            t.anchor -> "The first file keeps its original volume."
            else -> "File ${indexOf(t) + 1} of ${tracks.size}" + if (t.muted) " · muted" else ""
        }
        totalInfo.text = if (tracks.isEmpty()) "" else "Mix length ${Fmt.time(total)} · ${tracks.size} file(s)"
        totalInfo.contentDescription = if (tracks.isEmpty()) "" else "Mix length ${Fmt.spoken(total)}, ${tracks.size} files"
    }

    private fun updateTimeline() {
        val usable = tracks.filter { it.frames > 0 }
        if (usable.isEmpty()) {
            timeline.setLanes(emptyList())
            timeline.contentDescription = "Timeline is empty"
            return
        }
        val origin = MixLayout.origin(usable)
        val total = MixLayout.total(usable, origin)
        val colors = intArrayOf(Palette.ACCENT, Palette.BLUE, Palette.GOLD, 0xFFB48CFF.toInt(), 0xFF57D9C4.toInt(), Palette.RED)
        val lanes = ArrayList<TimelineView.Lane>()
        val desc = StringBuilder("Timeline, total ${Fmt.spoken(total)}. ")
        for ((i, t) in tracks.withIndex()) {
            if (t.frames <= 0) continue
            val start = (t.offsetFrames - origin).toFloat() / total
            val len = t.frames.toFloat() / total
            lanes.add(TimelineView.Lane(start.coerceIn(0f, 1f), len.coerceIn(0.005f, 1f), colors[i % colors.size], t.locked, "${i + 1}"))
            desc.append("File ${i + 1} from ${Fmt.spoken(t.offsetFrames - origin)} to ${Fmt.spoken(t.offsetFrames - origin + t.frames)}${if (t.locked) ", locked" else ""}. ")
        }
        timeline.setLanes(lanes)
        timeline.contentDescription = desc.toString()
    }

    private fun summaryText(): String {
        if (tracks.isEmpty()) return "No files yet. Press Import File to start."
        val usable = tracks.filter { it.frames > 0 }
        val origin = if (usable.isEmpty()) 0L else MixLayout.origin(usable)
        val sb = StringBuilder("The mix has ${tracks.size} file(s) and lasts ${Fmt.spoken(mixTotal())}.\n\n")
        for ((i, t) in tracks.withIndex()) {
            sb.append("File ${i + 1}: ${t.name}.\n")
            if (t.decoding) {
                sb.append("Still decoding.\n\n")
                continue
            }
            if (t.anchor) sb.append("Base file, starts at the beginning, original volume.\n")
            else {
                sb.append("${MODE_NAMES[modeOf(t.slider)]}. ${startSpoken(t)}.\n")
                sb.append("Volume ${Math.round(t.gain * 100f)} percent${if (t.muted) ", muted" else ""}${if (t.locked) ". Locked" else ""}.\n")
            }
            sb.append("Plays from ${Fmt.spoken(t.offsetFrames - origin)} to ${Fmt.spoken(t.offsetFrames - origin + t.frames)} of the mix.\n\n")
        }
        return sb.toString().trim()
    }

    private fun showSummary() {
        dialog().setTitle("Mix summary").setMessage(summaryText()).setPositiveButton("Close", null).show()
    }

    private fun rowSpoken(i: Int, t: Track): String {
        val sb = StringBuilder("File ${i + 1}, ${t.name}. ")
        if (t.decoding) sb.append("Decoding. ")
        else {
            sb.append("Length ${Fmt.spoken(t.frames)}. ")
            sb.append(if (t.anchor) "Base file. " else "${MODE_NAMES[modeOf(t.slider)]}. ${startSpoken(t)}. ")
            sb.append("Volume ${Math.round((if (t.anchor) 1f else t.gain) * 100f)} percent. ")
            if (t.muted) sb.append("Muted. ")
            if (t.locked) sb.append("Marked, locked. ")
        }
        sb.append(if (i == activeIndex) "Selected." else "Double tap to select.")
        return sb.toString()
    }

    private fun buildRows() {
        fileList.removeAllViews()
        rows.clear()
        if (tracks.isEmpty()) {
            fileList.addView(UiKit.hint(this, "No files yet."), UiKit.lp(this, 2f))
            return
        }
        for ((i, t) in tracks.withIndex()) {
            val box = UiKit.row(this)
            box.setPadding(UiKit.dp(this, 10f), UiKit.dp(this, 6f), UiKit.dp(this, 6f), UiKit.dp(this, 6f))
            box.layoutParams = UiKit.lp(this, 5f)

            val info = LinearLayout(this)
            info.orientation = LinearLayout.VERTICAL
            info.isClickable = true
            info.isFocusable = true
            info.minimumHeight = UiKit.dp(this, 48f)
            info.gravity = Gravity.CENTER_VERTICAL

            val top = UiKit.row(this)
            val badge = UiKit.text(this, "${i + 1}", 14f, Palette.ON_ACCENT, true)
            badge.gravity = Gravity.CENTER
            badge.background = UiKit.bg(if (t.anchor) Palette.ACCENT else Palette.BLUE, 8f, ctx = this)
            top.addView(badge, LinearLayout.LayoutParams(UiKit.dp(this, 26f), UiKit.dp(this, 26f)).also { it.rightMargin = UiKit.dp(this, 8f) })
            val name = UiKit.text(this, t.name, 14.5f, Palette.TEXT, true)
            name.maxLines = 1
            name.ellipsize = TextUtils.TruncateAt.MIDDLE
            top.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val tag = UiKit.text(this, "", 12f, Palette.MUTED, true)
            top.addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = UiKit.dp(this, 6f) })
            info.addView(top)
            val meta = UiKit.text(this, "", 12.5f, Palette.MUTED)
            info.addView(meta, UiKit.lp(this, 4f))
            info.setOnClickListener { selectIndex(i, true) }
            box.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val opts = UiKit.button(this, "Options", false)
            opts.contentDescription = "Options for file ${i + 1}: mark, unlock or remove"
            opts.setOnClickListener { selectIndex(i, false); trackMenu(t) }
            box.addView(opts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.leftMargin = UiKit.dp(this, 6f) })

            fileList.addView(box)
            rows.add(Row(t, box, info, meta, tag))
        }
    }

    /** Updates row texts, highlight and spoken description in place. */
    private fun updateRowTexts() {
        for ((i, r) in rows.withIndex()) {
            val t = r.track
            val selected = i == activeIndex
            r.box.background = UiKit.bg(
                if (selected) 0xFF20323A.toInt() else Palette.CARD2, 10f,
                if (selected) Palette.ACCENT else 0, 1.5f, this
            )
            r.info.isSelected = selected
            r.tag.text = when {
                t.decoding -> "DECODING"
                t.locked -> "MARKED"
                t.muted -> "MUTED"
                t.anchor -> "BASE"
                else -> ""
            }
            r.tag.setTextColor(if (t.locked) Palette.GOLD else Palette.MUTED)
            r.meta.text = if (t.decoding) "Decoding to a fast working format …"
            else "${fmtDur(t.durationMs)} · ${if (t.anchor) "base" else MODE_NAMES[modeOf(t.slider)]} · start ${Fmt.signed(t.offsetFrames)} · volume ${Math.round((if (t.anchor) 1f else t.gain) * 100f)}%"
            r.info.contentDescription = rowSpoken(i, t)
        }
    }

    private fun trackMenu(t: Track) {
        val opts = ArrayList<String>()
        if (!t.anchor) opts.add(if (t.locked) "Unlock (remove mark)" else "Add Mark (lock here)")
        opts.add("Remove this file")
        dialog().setTitle("File ${indexOf(t) + 1}: ${t.name}")
            .setItems(opts.toTypedArray()) { _, which ->
                when (opts[which]) {
                    "Unlock (remove mark)" -> unlock(t)
                    "Add Mark (lock here)" -> addMark()
                    "Remove this file" -> confirmRemove()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun fmtDur(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(java.util.Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }

    /** User-initiated stops announce themselves; suppress the generic message for a moment. */
    private fun muteStopMessage() {
        quietUntil = android.os.SystemClock.uptimeMillis() + 800
    }

    private fun screenReaderOn(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.isEnabled && am.isTouchExplorationEnabled
    }

    private fun busy(s: String) {
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        say(s)
    }

    /** Status line for everyone; spoken for screen reader users, toast for sighted users. */
    @Suppress("DEPRECATION")
    private fun say(s: String) {
        statusText.text = s
        if (screenReaderOn()) statusText.announceForAccessibility(s)
        else Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PICK = 1001
        private const val REQ_STORAGE = 77
        private const val MAX_FILES = 12

        private const val MODE_AFTER = 0
        private const val MODE_BEFORE = 1
        private const val MODE_MIX = 2
        private const val MODE_OVERRIDE = 3

        private val MODE_NAMES = listOf("Merge after", "Merge before", "Mix", "Override")
        private val MODE_LABELS = listOf(
            "Merge after: plays right after the files before it, no gap",
            "Merge before: plays completely before the first file",
            "Mix: plays together with the first file, fully on top",
            "Override: partly overlaps, set the amount with the slider"
        )
    }
}
