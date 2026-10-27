package com.maranone.rplacert

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

open class QuestVrActivity : Activity() {
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var vrStarted = false
    @Volatile private var quickPresetRenameDialogOpen = false

    private var lastSavedRomFilename = ""
    private val prefs by lazy { getSharedPreferences("qrd_prefs", MODE_PRIVATE) }

    // BT gamepad state — digital keys and hat/stick axes tracked separately so they
    // can't cancel each other out (e.g. a centered stick won't clear a held dpad key).
    private var btKeyUp = false; private var btKeyDown = false
    private var btKeyLeft = false; private var btKeyRight = false
    private var btAxisUp = false; private var btAxisDown = false
    private var btAxisLeft = false; private var btAxisRight = false
    private var btA = false; private var btB = false
    private var btX = false; private var btY = false
    private var btL = false; private var btR = false
    private var btStart = false; private var btSelect = false

    private fun syncBtInput() = nativeSetBtInputState(
        btKeyUp || btAxisUp, btKeyDown || btAxisDown,
        btKeyLeft || btAxisLeft, btKeyRight || btAxisRight,
        btA, btB, btX, btY, btL, btR, btStart, btSelect
    )

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (updateBtKey(keyCode, true)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (updateBtKey(keyCode, false)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun updateBtKey(keyCode: Int, pressed: Boolean): Boolean {
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP              -> { btKeyUp    = pressed; true }
            KeyEvent.KEYCODE_DPAD_DOWN            -> { btKeyDown  = pressed; true }
            KeyEvent.KEYCODE_DPAD_LEFT            -> { btKeyLeft  = pressed; true }
            KeyEvent.KEYCODE_DPAD_RIGHT           -> { btKeyRight = pressed; true }
            KeyEvent.KEYCODE_BUTTON_A             -> { btA        = pressed; true }
            KeyEvent.KEYCODE_BUTTON_B             -> { btB        = pressed; true }
            KeyEvent.KEYCODE_BUTTON_X             -> { btX        = pressed; true }
            KeyEvent.KEYCODE_BUTTON_Y             -> { btY        = pressed; true }
            KeyEvent.KEYCODE_BUTTON_L1            -> { btL        = pressed; true }
            KeyEvent.KEYCODE_BUTTON_R1            -> { btR        = pressed; true }
            KeyEvent.KEYCODE_BUTTON_L2            -> { btL        = pressed; true }
            KeyEvent.KEYCODE_BUTTON_R2            -> { btR        = pressed; true }
            KeyEvent.KEYCODE_BUTTON_START         -> { btStart    = pressed; true }
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE          -> { btSelect   = pressed; true }
            else -> false
        }
        if (handled) syncBtInput()
        return handled
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val src = event.source
        if (src and InputDevice.SOURCE_JOYSTICK == 0 &&
            src and InputDevice.SOURCE_GAMEPAD  == 0) {
            return super.onGenericMotionEvent(event)
        }
        val deadzone = 0.5f
        val hatX  = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY  = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)
        btAxisLeft  = hatX < -deadzone || stickX < -deadzone
        btAxisRight = hatX >  deadzone || stickX >  deadzone
        btAxisUp    = hatY < -deadzone || stickY < -deadzone
        btAxisDown  = hatY >  deadzone || stickY >  deadzone
        syncBtInput()
        return true
    }

    private val statusPoll = object : Runnable {
        override fun run() {
            statusView.text = nativeGetVrStatus()
            // Persist the last ROM loaded from the browser panel
            val fn = nativeGetLastLoadedRomFilename()
            if (fn.isNotEmpty() && fn != lastSavedRomFilename) {
                lastSavedRomFilename = fn
                prefs.edit().putString("last_rom_path", fn).apply()
            }
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (javaClass != QuestVrActivity::class.java) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
        }

        statusView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            text = "Starting OpenXR shell..."
        }

        val randomizeBtn = Button(this).apply {
            text = "✦ RANDOMIZE"
            setOnClickListener { nativeRandomize() }
        }

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        for (i in 0 until 5) {
            val btn = Button(this).apply {
                text = "P${i + 1}"
                textSize = 12f
                setOnClickListener { nativeLoadPreset(i) }
                setOnLongClickListener { nativeSavePreset(i); true }
            }
            presetRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val presetHint = TextView(this).apply {
            text = "Tap preset to load  |  Long-press to save"
            textSize = 11f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
        }


        root.addView(randomizeBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(presetRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(presetHint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })

        val wrapper = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP))
        }
        setContentView(wrapper)
    }

    override fun onResume() {
        super.onResume()
        if (javaClass != QuestVrActivity::class.java) return
        // Require external storage access before starting VR so getRomDirectory() can
        // create /storage/emulated/0/QuestRetroDepth/ and find ROMs inside it.
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return  // don't start VR yet — wait for permission
        }
        if (!vrStarted) {
            vrStarted = true
            val startupPrefs = readSaveAutomationPrefs()
            val devRomName = intent.getStringExtra("rom")
            val anyRomCandidate = findStartupRomCandidate(devRomName)
            val forceLoad = devRomName != null
            val startupCandidate = if (forceLoad || startupPrefs.loadLastSaveEnabled) anyRomCandidate else null
            // Auto-starting rplace playback with no menu on screen left the
            // compositor with nothing valid to render for the several seconds
            // the canvas takes to decode -- the runtime saw invalid/empty
            // frames and killed the OpenXR session outright ("swapchain
            // validation failure" -> xrDestroySession within ~20ms of start,
            // confirmed via logcat on-device). Opening the normal menu first
            // (same as stock questretrodepth) gives the render loop valid
            // content from frame 1; rplace playback should be started from
            // there once a year-picker is wired in (see startRplaceYear).
            val openMenuOnStartup = startupCandidate == null
            statusView.text = nativeStartVr(
                this,
                openMenuOnStartup,
                startupPrefs.autosaveIntervalSeconds,
                startupPrefs.loadLastSaveEnabled
            )
            nativeSetHomebrewFeed(selectedHomebrewFeedIndex())
            if ((forceLoad || startupPrefs.loadLastSaveEnabled) && startupCandidate != null) {
                autoLoadStartupRom(startupCandidate)
            }
        }
        handler.post(statusPoll)
    }

    // Called when the user returns from the API 30+ "All Files Access" settings screen.
    // onResume() won't fire in that case, so we re-check here.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !vrStarted) onResume()
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) onResume()
    }

    private fun findStartupRomCandidate(overrideName: String? = null): File? {
        val romDir = File(getRomDirectory())
        Log.i("QuestRetroDepthXR", "findStartupRomCandidate: dir=${romDir.absolutePath} exists=${romDir.exists()}")

        // Collect all ROM/archive files from romDir and one level of subdirectories.
        val allFiles = mutableListOf<File>()
        romDir.listFiles()?.forEach { entry ->
            if (entry.isFile && isSupportedOrArchiveFile(entry)) {
                allFiles += entry
            } else if (entry.isDirectory) {
                entry.listFiles()?.filter { it.isFile && isSupportedOrArchiveFile(it) }
                    ?.let { allFiles += it }
            }
        }
        Log.i("QuestRetroDepthXR", "findStartupRomCandidate: found ${allFiles.size} ROM(s)")

        // Intent extra "rom" overrides pref; then fall back to last played; then first alphabetically.
        val lastName = (overrideName ?: prefs.getString("last_rom_path", null))
            ?.substringAfterLast('/')
        return if (lastName != null) {
            allFiles.firstOrNull { it.name == lastName }
                ?: allFiles.minByOrNull { it.name.lowercase(Locale.US) }
        } else {
            allFiles.minByOrNull { it.name.lowercase(Locale.US) }
        }
    }

    private fun autoLoadStartupRom(candidate: File) {
        Log.i("QuestRetroDepthXR", "autoLoadStartupRom: chosen=${candidate.name}")

        val romFile = runCatching { prepareRomFile(candidate) }.getOrElse {
            Log.e("QuestRetroDepthXR", "autoLoadStartupRom: extraction failed", it)
            statusView.text = "ROM extract failed: ${it.message}"
            nativeOpenMainMenu()
            return
        }

        Log.i("QuestRetroDepthXR", "autoLoadStartupRom: loading ${romFile.absolutePath}")
        val result = nativeLoadRom(romFile.absolutePath, candidate.name)
        Log.i("QuestRetroDepthXR", "autoLoadStartupRom: result=$result")
        statusView.text = result
        if (!result.startsWith("ROM load failed")) {
            lastSavedRomFilename = candidate.name
            prefs.edit().putString("last_rom_path", candidate.name).apply()
        } else {
            nativeOpenMainMenu()
        }
    }

    private fun readSaveAutomationPrefs(): SaveAutomationPrefs {
        val file = File(getSettingsDirectory(), SAVE_AUTOMATION_FILE_NAME)
        if (!file.isFile) return SaveAutomationPrefs()

        var autosaveIntervalSeconds = 30
        var loadLastSaveEnabled = true
        runCatching {
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val sep = line.indexOf('=')
                if (sep <= 0) return@forEachLine
                val key = line.substring(0, sep).trim()
                val value = line.substring(sep + 1).trim()
                when (key) {
                    "autosave_interval_seconds" -> {
                        autosaveIntervalSeconds = value.toIntOrNull()
                            ?.takeIf { it in VALID_AUTOSAVE_INTERVALS }
                            ?: autosaveIntervalSeconds
                    }
                    "load_last_save" -> loadLastSaveEnabled = value == "1"
                }
            }
        }
        return SaveAutomationPrefs(autosaveIntervalSeconds, loadLastSaveEnabled)
    }

    /** If the file is a raw ROM, return it as-is. If it's an archive, extract the ROM inside. */
    private fun prepareRomFile(file: File): File {
        val lower = file.name.lowercase(Locale.US)
        val cacheDir = File(cacheDir, "roms").apply { mkdirs() }
        return when {
            lower.endsWith(".zip") -> extractZipRom(file, cacheDir, preferredRomFamily(file))
            lower.endsWith(".7z")  -> extract7zRom(file, cacheDir, preferredRomFamily(file))
            else                   -> file
        }
    }

    private fun extractZipRom(archive: File, targetDir: File, preferredFamily: RomFamily?): File {
        ZipInputStream(archive.inputStream()).use { zip ->
            var entry = zip.nextEntry
            var fallback: File? = null
            while (entry != null) {
                if (!entry.isDirectory && isSupportedRomExtension(entry.name)) {
                    val out = File(targetDir, sanitize(entry.name.substringAfterLast('/')))
                    FileOutputStream(out).use { zip.copyTo(it) }
                    if (preferredFamily == null || romFamilyForName(entry.name) == preferredFamily) {
                        return out
                    }
                    if (fallback == null) fallback = out
                }
                entry = zip.nextEntry
            }
            fallback?.let { return it }
        }
        error("ZIP contains no supported ROM: ${archive.name}")
    }

    private fun extract7zRom(archive: File, targetDir: File, preferredFamily: RomFamily?): File {
        SevenZFile(archive).use { sz ->
            val buf = ByteArray(8192)
            var entry = sz.nextEntry
            var fallback: File? = null
            while (entry != null) {
                if (!entry.isDirectory && isSupportedRomExtension(entry.name)) {
                    val out = File(targetDir, sanitize(
                        entry.name.substringAfterLast('/').substringAfterLast('\\')))
                    FileOutputStream(out).use { fos ->
                        var rem = entry.size
                        while (rem > 0) {
                            val n = sz.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            rem -= n
                        }
                    }
                    if (preferredFamily == null || romFamilyForName(entry.name) == preferredFamily) {
                        return out
                    }
                    if (fallback == null) fallback = out
                }
                entry = sz.nextEntry
            }
            fallback?.let { return it }
        }
        error("7z contains no supported ROM: ${archive.name}")
    }

    private fun isSnesExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".smc") || lower.endsWith(".sfc") ||
               lower.endsWith(".fig") || lower.endsWith(".swc")
    }

    private fun isGenesisExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".md") || lower.endsWith(".bin") ||
               lower.endsWith(".gen") || lower.endsWith(".smd")
    }

    private fun isNesExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".nes") || lower.endsWith(".unf") || lower.endsWith(".unif")
    }

    private fun isGbExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".gb") || lower.endsWith(".gbc")
    }

    private fun isGbaExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".gba")
    }

    private fun isGgExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".gg") || lower.endsWith(".sms")
    }

    private fun isPceExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".pce") || lower.endsWith(".sgx")
    }

    private fun is32xExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".32x")
    }

    private fun isAtari2600Extension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".a26")
    }

    private fun isDsExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".nds")
    }

    private fun isSaturnExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".cue") || lower.endsWith(".iso") || lower.endsWith(".chd")
    }

    private fun isDreamcastExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".gdi") || lower.endsWith(".cdi")
    }

    private fun isSupportedRomExtension(name: String): Boolean {
        return isSnesExtension(name) || isGenesisExtension(name) || isNesExtension(name) ||
               isGbExtension(name) || isGbaExtension(name) || isGgExtension(name) ||
               isPceExtension(name) || is32xExtension(name) || isAtari2600Extension(name) ||
               isDsExtension(name) || isSaturnExtension(name) ||
               isDreamcastExtension(name)
    }

    private fun isSupportedOrArchiveFile(file: File): Boolean {
        val lower = file.name.lowercase(Locale.US)
        return isSupportedRomExtension(lower) || lower.endsWith(".zip") || lower.endsWith(".7z")
    }

    private fun romFamilyForName(name: String): RomFamily? = when {
        isSnesExtension(name) -> RomFamily.Snes
        isGenesisExtension(name) -> RomFamily.Genesis
        isNesExtension(name) -> RomFamily.Nes
        isGbExtension(name) -> RomFamily.Gb
        isGbaExtension(name) -> RomFamily.Gba
        isGgExtension(name) -> RomFamily.Gg
        isPceExtension(name) -> RomFamily.Pce
        is32xExtension(name) -> RomFamily.Sega32x
        isAtari2600Extension(name) -> RomFamily.Atari2600
        isDsExtension(name) -> RomFamily.Ds
        isSaturnExtension(name) -> RomFamily.Saturn
        isDreamcastExtension(name) -> RomFamily.Dreamcast
        else -> null
    }

    private fun preferredRomFamily(file: File): RomFamily? {
        val path = file.absolutePath.lowercase(Locale.US)
        return when {
            path.contains("/roms/snes/") || path.contains("\\roms\\snes\\") -> RomFamily.Snes
            path.contains("/roms/genesis/") || path.contains("\\roms\\genesis\\") -> RomFamily.Genesis
            path.contains("/roms/nes/") || path.contains("\\roms\\nes\\") -> RomFamily.Nes
            path.contains("/roms/gb/") || path.contains("\\roms\\gb\\") -> RomFamily.Gb
            path.contains("/roms/gba/") || path.contains("\\roms\\gba\\") -> RomFamily.Gba
            path.contains("/roms/gg/") || path.contains("\\roms\\gg\\") -> RomFamily.Gg
            path.contains("/roms/pce/") || path.contains("\\roms\\pce\\") -> RomFamily.Pce
            path.contains("/roms/32x/") || path.contains("\\roms\\32x\\") -> RomFamily.Sega32x
            path.contains("/roms/atari2600/") || path.contains("\\roms\\atari2600\\") -> RomFamily.Atari2600
            path.contains("/roms/ds/") || path.contains("\\roms\\ds\\") -> RomFamily.Ds
            path.contains("/roms/saturn/") || path.contains("\\roms\\saturn\\") -> RomFamily.Saturn
            path.contains("/roms/dreamcast/") || path.contains("\\roms\\dreamcast\\") -> RomFamily.Dreamcast
            else -> romFamilyForName(file.name)
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")

    override fun onPause() {
        handler.removeCallbacks(statusPoll)
        super.onPause()
    }

    override fun onDestroy() {
        if (javaClass != QuestVrActivity::class.java) {
            super.onDestroy()
            return
        }
        nativeStopVr()
        vrStarted = false
        super.onDestroy()
    }

    private external fun nativeSetBtInputState(
        up: Boolean, down: Boolean, left: Boolean, right: Boolean,
        a: Boolean, b: Boolean, x: Boolean, y: Boolean,
        l: Boolean, r: Boolean, start: Boolean, select: Boolean
    )
    private external fun nativeStartVr(
        activity: Activity,
        openMenuOnStartup: Boolean,
        autosaveIntervalSeconds: Int,
        loadLastSaveEnabled: Boolean
    ): String
    private external fun nativeGetVrStatus(): String
    private external fun nativeStopVr()
    private external fun nativeRandomize()
    private external fun nativeLoadPreset(idx: Int)
    private external fun nativeSavePreset(idx: Int)
    private external fun nativeGetVrStateSummary(): String
    private external fun nativeLoadRom(path: String, sourceName: String): String
    private external fun nativeGetLastLoadedRomFilename(): String
    private external fun nativeApplyStateCode(code: String): Boolean
    private external fun nativeOpenMainMenu()
    private external fun nativeOpenHomebrew()
    private external fun nativeSubmitQuickPresetName(kind: Int, slot: Int, name: String)
    private external fun nativeCancelQuickPresetName(kind: Int, slot: Int)
    private external fun nativeHomebrewDataReady()
    private external fun nativeHomebrewDownloadComplete(entryIdx: Int)
    private external fun nativeSetHomebrewFeed(idx: Int)

    // r/place canvas playback (rplaceRT)
    private external fun nativeRplaceStart(binPath: String)
    private external fun nativeRplaceStop()
    private external fun nativeRplaceIsReady(): Boolean
    private external fun nativeRplaceSetSpeed(speed: Float)
    private external fun nativeRplaceSetPlaying(playing: Boolean)
    private external fun nativeRplaceSeekMs(targetMs: Double)
    private external fun nativeRplaceGetVirtualTimeMs(): Double
    private external fun nativeRplaceGetDurationMs(): Double
    private external fun nativeRplaceLoadProgress(): Int
    private external fun nativeRplaceBeginConvert(dstPath: String): Boolean
    private external fun nativeRplaceFeedBytes(chunk: ByteArray, len: Int)
    private external fun nativeRplaceEndConvert(): Boolean
    private external fun nativeRplaceGetWidth(): Int
    private external fun nativeRplaceGetHeight(): Int
    private external fun nativeRplaceGetOffsetX(): Int
    private external fun nativeRplaceGetOffsetY(): Int
    private external fun nativeRplaceSetDirectorEnabled(enabled: Boolean)
    private external fun nativeRplaceGetDirectorEnabled(): Boolean
    // Hands the director an atlas-derived reel: xs[i]/ys[i] are canvas-pixel
    // space, startMs[i]/endMs[i] are virtual-ms (0 = this year's first
    // event) -- see buildRplaceDirectorReel(). Arrays MUST already be sorted
    // ascending by startMs with no duplicate startMs values.
    private external fun nativeRplaceSetDirectorWaypoints(
        xs: IntArray, ys: IntArray, startMs: DoubleArray, endMs: DoubleArray)

    fun showQuickPresetRenameDialog(kind: Int, slot: Int, currentName: String) {
        runOnUiThread {
            if (isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed)) {
                nativeCancelQuickPresetName(kind, slot)
                return@runOnUiThread
            }
            if (quickPresetRenameDialogOpen) {
                nativeCancelQuickPresetName(kind, slot)
                return@runOnUiThread
            }
            quickPresetRenameDialogOpen = true
            val input = EditText(this).apply {
                setText(currentName)
                setSelection(text.length)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                hint = if (kind == 0) "Settings name" else "Layers name"
            }
            AlertDialog.Builder(this)
                .setTitle(if (kind == 0) "Rename Settings Preset" else "Rename Layer Preset")
                .setView(input)
                .setCancelable(true)
                .setPositiveButton("Save") { _, _ ->
                    quickPresetRenameDialogOpen = false
                    nativeSubmitQuickPresetName(kind, slot, input.text?.toString() ?: "")
                }
                .setNegativeButton("Cancel") { _, _ ->
                    quickPresetRenameDialogOpen = false
                    nativeCancelQuickPresetName(kind, slot)
                }
                .setOnCancelListener {
                    quickPresetRenameDialogOpen = false
                    nativeCancelQuickPresetName(kind, slot)
                }
                .show()
        }
    }

    // -----------------------------------------------------------------------
    // Called from C++ GL thread to render the ROM browser panel texture.
    // Returns ARGB_8888 pixel array of size width×height.
    // -----------------------------------------------------------------------
    fun renderRomPanelBitmap(
        romNames: Array<String>,
        isDir: BooleanArray,
        hoveredIdx: Int,
        width: Int,
        height: Int,
        hasMoreUp: Boolean,
        hasMoreDown: Boolean
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Panel background
        paint.color = android.graphics.Color.argb(235, 12, 12, 22)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Title bar
        paint.color = android.graphics.Color.argb(255, 28, 45, 78)
        canvas.drawRect(0f, 0f, width.toFloat(), 88f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 48f
        paint.isFakeBoldText = true
        canvas.drawText("ROM Browser", 14f, 60f, paint)
        paint.isFakeBoldText = false

        // Scroll arrows
        if (hasMoreUp) {
            paint.color = android.graphics.Color.argb(200, 160, 200, 255)
            paint.textSize = 40f
            canvas.drawText("▲ scroll up", width - 260f, 60f, paint)
        }

        val titleH = 88f
        val rowH = if (romNames.isEmpty()) (height - titleH)
                   else ((height - titleH) / romNames.size.coerceAtLeast(1)).toFloat()

        if (romNames.isEmpty()) {
            paint.color = android.graphics.Color.argb(180, 150, 150, 160)
            paint.textSize = 40f
            canvas.drawText("No ROMs found in roms/ root", 14f, titleH + 80f, paint)
        } else {
            paint.textSize = rowH.coerceIn(28f, 56f) * 0.58f
            for (i in romNames.indices) {
                val y = titleH + i * rowH
                val entryIsDir = i < isDir.size && isDir[i]
                // Alternating row backgrounds only (hover highlight is a separate GL quad)
                if (i % 2 == 0) {
                    paint.color = android.graphics.Color.argb(80, 35, 35, 55)
                    canvas.drawRect(0f, y, width.toFloat(), y + rowH, paint)
                }
                // Folder icon prefix
                val label = if (entryIsDir) "\uD83D\uDCC1 ${romNames[i]}" else romNames[i]
                paint.color = when {
                    entryIsDir -> android.graphics.Color.argb(215, 140, 220, 200)
                    else       -> android.graphics.Color.argb(215, 190, 195, 210)
                }
                canvas.drawText(label, 14f, y + rowH * 0.68f, paint)
            }
        }

        // "more below" hint
        if (hasMoreDown) {
            paint.color = android.graphics.Color.argb(200, 160, 200, 255)
            paint.textSize = 36f
            canvas.drawText("▼ more", width / 2f - 60f, height - 12f, paint)
        }

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    // -----------------------------------------------------------------------
    // Called from C++ GL thread — renders the layer order panel.
    // layerNames are in display order (top = nearest). grabbed = dragged row (-1 = none).
    // frozen: true = paused (game frozen), false = playing.
    // Returns ARGB_8888 pixels, width×height.
    // -----------------------------------------------------------------------
    fun renderLayerPanelBitmap(
        layerNames: Array<String>,
        layerEnabled: BooleanArray,
        layerAmbilight: BooleanArray,
        grabbed: Int,           // row being dragged (-1 = none)
        dropTarget: Int,        // row laser is pointing at while dragging (-1 = none)
        depthValues: FloatArray, // depth_meters per layer in display order
        depthSelectedRow: Int,  // row in depth-edit mode (-1 = none)
        width: Int,
        height: Int,
        frozen: Boolean,        // frozen state for play/pause button
        autoDupLabel: String,
        filterLabel: String,
        showFilter: Boolean
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint  = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Background
        paint.color = android.graphics.Color.argb(235, 14, 14, 24)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Title bar
        paint.color = android.graphics.Color.argb(255, 30, 50, 90)
        canvas.drawRect(0f, 0f, width.toFloat(), 88f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 44f
        paint.isFakeBoldText = true
        canvas.drawText("Layers  (near→far)", 12f, 60f, paint)
        paint.isFakeBoldText = false

        val titleH = 88f
        val n      = layerNames.size
        val totalRows = n + 3 + if (showFilter) 1 else 0  // layers + play/pause + auto-dup + reset-depths + optional filter
        if (n == 0) {
            paint.color = android.graphics.Color.argb(160, 150, 150, 160)
            paint.textSize = 36f
            canvas.drawText("No layers", 14f, titleH + 72f, paint)
        }

        val rowH = ((height - titleH) / totalRows).toFloat().coerceAtLeast(56f)
        paint.textSize = rowH * 0.46f

        val isDragging = (grabbed >= 0)

        // Compute depth display scale: map depth_meters → 0–10 using min/max of the array
        val depthMin = if (depthValues.isNotEmpty()) depthValues.min() else 0.9f
        val depthMax = if (depthValues.isNotEmpty()) depthValues.max() else 2.0f
        val depthRange = if (depthMax > depthMin) depthMax - depthMin else 1.0f

        fun depthDisplay(depthMeters: Float): String {
            val t = (depthMeters - depthMin) / depthRange
            val d = (1.0f - t) * 10.0f
            return "%.1f".format(d.coerceIn(0.0f, 10.0f))
        }

        // Render layer rows (indices 0 to n-1)
        for (i in 0 until n) {
            val y          = titleH + i * rowH
            val enabled    = if (i < layerEnabled.size) layerEnabled[i] else true
            val isGrabbed  = (i == grabbed)
            val isDepthSel = (i == depthSelectedRow)

            // Row background
            when {
                isDepthSel -> {
                    paint.color = android.graphics.Color.argb(120, 40, 100, 60)
                    canvas.drawRect(0f, y, width * 0.60f, y + rowH, paint)
                    if (i % 2 == 0) {
                        paint.color = android.graphics.Color.argb(70, 40, 40, 65)
                        canvas.drawRect(width * 0.60f, y, width.toFloat(), y + rowH, paint)
                    }
                }
                isGrabbed -> {
                    paint.color = android.graphics.Color.argb(100, 60, 120, 220)
                    canvas.drawRect(2f, y + 1f, width - 2f, y + rowH - 1f, paint)
                }
                i % 2 == 0 -> {
                    paint.color = android.graphics.Color.argb(70, 40, 40, 65)
                    canvas.drawRect(0f, y, width.toFloat(), y + rowH, paint)
                }
            }

            // Depth value in left zone
            val depthStr = if (i < depthValues.size) depthDisplay(depthValues[i]) else ""
            paint.textSize = rowH * 0.38f
            paint.color = if (isDepthSel) android.graphics.Color.argb(255, 100, 240, 140)
                          else android.graphics.Color.argb(160, 140, 200, 160)
            canvas.drawText(depthStr, 8f, y + rowH * 0.65f, paint)
            paint.textSize = rowH * 0.46f

            // Layer name
            paint.color = when {
                isGrabbed   -> android.graphics.Color.argb(100, 180, 190, 220)
                !enabled    -> android.graphics.Color.argb(110, 150, 150, 160)
                else        -> android.graphics.Color.argb(220, 200, 210, 230)
            }
            canvas.save()
            canvas.clipRect(width * 0.22f, y, width * 0.58f, y + rowH)
            canvas.drawText(layerNames[i], width * 0.22f, y + rowH * 0.70f, paint)
            canvas.restore()

            // Visibility toggle and ambilight toggle
            if (!isGrabbed) {
                val visX  = width * 0.60f
                val ambX  = width * 0.80f
                val ambiEnabled = if (i < layerAmbilight.size) layerAmbilight[i] else true

                paint.color = if (enabled) android.graphics.Color.argb(200, 80, 200, 100)
                              else android.graphics.Color.argb(120, 180, 60, 60)
                canvas.drawRoundRect(visX, y + rowH * 0.18f, ambX - 4f, y + rowH * 0.82f, 6f, 6f, paint)
                paint.color = android.graphics.Color.WHITE
                paint.textSize = rowH * 0.36f
                canvas.drawText(if (enabled) "ON" else "OFF", visX + 4f, y + rowH * 0.68f, paint)

                paint.color = if (ambiEnabled) android.graphics.Color.argb(200, 255, 170, 50)
                              else android.graphics.Color.argb(100, 100, 100, 100)
                canvas.drawRoundRect(ambX, y + rowH * 0.18f, width - 4f, y + rowH * 0.82f, 6f, 6f, paint)
                paint.color = android.graphics.Color.WHITE
                canvas.drawText("AMB", ambX + 4f, y + rowH * 0.68f, paint)

                paint.textSize = rowH * 0.46f
            }

            // Drop-target indicator
            if (isDragging && i == dropTarget && dropTarget != grabbed) {
                paint.color = android.graphics.Color.argb(255, 80, 200, 255)
                paint.strokeWidth = 3f
                paint.style = android.graphics.Paint.Style.STROKE
                canvas.drawLine(4f, y, width - 4f, y, paint)
                paint.style = android.graphics.Paint.Style.FILL
                paint.strokeWidth = 0f
            }
        }

        // Play/Pause button as last row (index n)
        val pauseY = titleH + n * rowH
        val isPauseHovered = (dropTarget == n)  // use dropTarget for hover state

        // Row background
        paint.color = if (isPauseHovered) android.graphics.Color.argb(100, 100, 140, 200)
                      else android.graphics.Color.argb(70, 40, 40, 65)
        canvas.drawRect(0f, pauseY, width.toFloat(), pauseY + rowH, paint)

        // Play/Pause icon and text
        paint.color = if (frozen) android.graphics.Color.argb(220, 80, 160, 255)  // blue when paused
                      else android.graphics.Color.argb(220, 60, 180, 80)   // green when playing
        val iconLabel = if (frozen) "\u25B6 PLAY" else "\u275A\u275A PAUSED"
        paint.textSize = rowH * 0.50f
        val labelW = paint.measureText(iconLabel)
        val labelX = (width - labelW) / 2
        canvas.drawText(iconLabel, labelX, pauseY + rowH * 0.68f, paint)

        // Auto duplication row after play/pause (index n + 1)
        val autoDupY = titleH + (n + 1) * rowH
        val isAutoDupHovered = (dropTarget == n + 1)
        paint.color = if (isAutoDupHovered) android.graphics.Color.argb(100, 100, 140, 200)
                      else android.graphics.Color.argb(70, 40, 40, 65)
        canvas.drawRect(0f, autoDupY, width.toFloat(), autoDupY + rowH, paint)

        paint.textSize = rowH * 0.42f
        paint.color = android.graphics.Color.argb(220, 210, 220, 240)
        canvas.drawText("AUTO DUP", 16f, autoDupY + rowH * 0.68f, paint)

        paint.color = if (autoDupLabel == "OFF") android.graphics.Color.argb(180, 160, 170, 180)
                      else android.graphics.Color.argb(220, 255, 205, 100)
        val autoDupW = paint.measureText(autoDupLabel)
        canvas.drawText(autoDupLabel, width - autoDupW - 16f, autoDupY + rowH * 0.68f, paint)

        // Reset depths row (index n + 2)
        val resetY = titleH + (n + 2) * rowH
        val isResetHovered = (dropTarget == n + 2)
        paint.color = if (isResetHovered) android.graphics.Color.argb(120, 60, 130, 80)
                      else android.graphics.Color.argb(70, 30, 55, 40)
        canvas.drawRect(0f, resetY, width.toFloat(), resetY + rowH, paint)
        paint.textSize = rowH * 0.42f
        paint.color = android.graphics.Color.argb(200, 120, 220, 140)
        val resetLabel = "RESET DEPTHS"
        val resetW = paint.measureText(resetLabel)
        canvas.drawText(resetLabel, (width - resetW) / 2f, resetY + rowH * 0.68f, paint)

        if (showFilter) {
            // Layer filter row (index n + 3)
            val filterY = titleH + (n + 3) * rowH
            val isFilterHovered = (dropTarget == n + 3)
            paint.color = if (isFilterHovered) android.graphics.Color.argb(100, 100, 140, 200)
                          else android.graphics.Color.argb(70, 40, 40, 65)
            canvas.drawRect(0f, filterY, width.toFloat(), filterY + rowH, paint)

            paint.textSize = rowH * 0.42f
            paint.color = android.graphics.Color.argb(220, 210, 220, 240)
            canvas.drawText("LAYER FILTER", 16f, filterY + rowH * 0.68f, paint)

            paint.color = android.graphics.Color.argb(220, 120, 210, 255)
            val filterW = paint.measureText(filterLabel)
            canvas.drawText(filterLabel, width - filterW - 16f, filterY + rowH * 0.68f, paint)
        }

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    fun renderQuickEditPanelBitmap(
        settingsNames: Array<String>,
        layerNames: Array<String>,
        layerEnabled: BooleanArray,
        hoveredSettingsLoad: Int,
        hoveredSettingsSave: Int,
        hoveredLayersLoad: Int,
        hoveredLayersSave: Int,
        hoveredAction: Int,
        width: Int,
        height: Int
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.argb(238, 10, 18, 12)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val titleH = 96f
        paint.color = android.graphics.Color.argb(255, 36, 98, 56)
        canvas.drawRect(0f, 0f, width.toFloat(), titleH, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 50f
        paint.isFakeBoldText = true
        canvas.drawText("Quick Edit", 20f, 62f, paint)
        paint.isFakeBoldText = false
        paint.textSize = 24f
        paint.color = android.graphics.Color.argb(210, 202, 240, 214)
        canvas.drawText("Pick presets, then press left thumbstick again to close", 20f, 88f, paint)

        val gap = 24f
        val columnW = (width - gap * 3f) / 2f
        val leftX = gap
        val rightX = leftX + columnW + gap
        val sectionTop = titleH + 32f
        val sectionBottom = height - 24f

        fun drawSection(
            x: Float,
            label: String,
            items: List<String>,
            enabled: List<Boolean>,
            actionLabels: List<String>,
            accent: Int,
            hoveredLoad: Int,
            hoveredSave: Int,
            actionBase: Int
        ) {
            paint.color = accent
            canvas.drawRoundRect(x, sectionTop, x + columnW, sectionTop + 64f, 14f, 14f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 38f
            paint.isFakeBoldText = true
            canvas.drawText(label, x + 18f, sectionTop + 43f, paint)
            paint.isFakeBoldText = false

            val totalRows = items.size + actionLabels.size
            val rowGap = 12f
            val startY = sectionTop + 84f
            val rowH = ((sectionBottom - startY) - rowGap * (totalRows - 1)) / totalRows.toFloat()

            fun actionFillColor(actionLabel: String): Int = when (actionLabel) {
                "Reset Presets" -> android.graphics.Color.argb(170, 122, 52, 44)
                "Manual Edit", "Manual Layers" -> android.graphics.Color.argb(165, 112, 84, 30)
                "Open Settings" -> android.graphics.Color.argb(165, 34, 82, 124)
                else -> android.graphics.Color.argb(150, 28, 86, 70)
            }

            for (i in items.indices) {
                val y0 = startY + i * (rowH + rowGap)
                val y1 = y0 + rowH
                val isEnabled = enabled.getOrElse(i) { true }
                paint.color = if (isEnabled) {
                    android.graphics.Color.argb(165, 36, 62, 44)
                } else {
                    android.graphics.Color.argb(96, 42, 52, 46)
                }
                canvas.drawRoundRect(x, y0, x + columnW, y1, 14f, 14f, paint)
                if (i == hoveredLoad) {
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = 5f
                    paint.color = android.graphics.Color.argb(235, 196, 255, 222)
                    canvas.drawRoundRect(x + 2f, y0 + 2f, x + columnW - 2f, y1 - 2f, 14f, 14f, paint)
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.strokeWidth = 0f
                }
                paint.color = if (isEnabled) android.graphics.Color.WHITE else android.graphics.Color.argb(170, 168, 160, 150)
                paint.textSize = rowH * 0.34f
                canvas.save()
                canvas.clipRect(x + 12f, y0, x + columnW - 12f, y1)
                canvas.drawText(items[i], x + 18f, y0 + rowH * 0.62f, paint)
                canvas.restore()
                if (!isEnabled) {
                    paint.color = android.graphics.Color.argb(150, 162, 184, 166)
                    paint.textSize = rowH * 0.18f
                    canvas.drawText("NOT AVAILABLE FOR THIS LAYER SET", x + 18f, y0 + rowH * 0.84f, paint)
                }
            }

            for (i in actionLabels.indices) {
                val row = items.size + i
                val y0 = startY + row * (rowH + rowGap)
                val y1 = y0 + rowH
                paint.color = actionFillColor(actionLabels[i])
                canvas.drawRoundRect(x, y0, x + columnW, y1, 14f, 14f, paint)
                if (hoveredAction == actionBase + i) {
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = 5f
                    paint.color = android.graphics.Color.argb(235, 196, 255, 222)
                    canvas.drawRoundRect(x + 2f, y0 + 2f, x + columnW - 2f, y1 - 2f, 14f, 14f, paint)
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.strokeWidth = 0f
                }
                paint.color = android.graphics.Color.WHITE
                paint.textSize = rowH * 0.32f
                canvas.drawText(actionLabels[i], x + 18f, y0 + rowH * 0.62f, paint)
            }
        }

        drawSection(
            leftX,
            "Settings",
            settingsNames.toList(),
            List(settingsNames.size) { true },
            listOf("Reset Presets", "Manual Edit", "Open Settings"),
            android.graphics.Color.argb(255, 52, 118, 70),
            hoveredSettingsLoad,
            hoveredSettingsSave,
            0
        )
        drawSection(
            rightX,
            "Layers",
            layerNames.toList(),
            layerEnabled.map { it },
            listOf("Reset Presets", "Manual Layers"),
            android.graphics.Color.argb(255, 30, 96, 86),
            hoveredLayersLoad,
            hoveredLayersSave,
            3
        )

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    // -----------------------------------------------------------------------
    // Called from C++ GL thread — renders the settings panel.
    // names/values/isBoolean are parallel arrays.
    // hoveredRow / area: 0=none, 1=minus, 2=plus.
    // Returns ARGB_8888 pixels, width×height.
    // -----------------------------------------------------------------------
    fun renderSettingsPanelBitmap(
        names: Array<String>,
        values: Array<String>,
        isBoolean: BooleanArray,
        hoveredRow: Int,
        area: Int,
        width: Int,
        height: Int,
        shareCode: String
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint  = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Background
        paint.color = android.graphics.Color.argb(235, 12, 14, 24)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Title bar
        paint.color = android.graphics.Color.argb(255, 30, 60, 50)
        canvas.drawRect(0f, 0f, width.toFloat(), 88f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 44f
        paint.isFakeBoldText = true
        canvas.drawText("Settings", 12f, 60f, paint)
        paint.isFakeBoldText = false
        // Share code (right side of title bar, monospace-style)
        if (shareCode.isNotEmpty()) {
            paint.textSize = 28f
            paint.color = android.graphics.Color.argb(200, 140, 230, 180)
            val codeLabel = "CODE: $shareCode"
            val tw = paint.measureText(codeLabel)
            canvas.drawText(codeLabel, width - tw - 8f, 56f, paint)
        }

        val titleH  = 88f
        val n       = names.size
        val rowH    = if (n > 0) ((height - titleH) / n).toFloat().coerceIn(52f, 96f) else 72f
        val btnW    = width * 0.20f  // left/right button zones

        for (i in 0 until n) {
            val y         = titleH + i * rowH
            val isBool    = if (i < isBoolean.size) isBoolean[i] else false
            val value     = if (i < values.size) values[i] else ""
            val isOn      = (value == "ON")

            // Row background (alternating rows only; hover highlight is a separate GL quad)
            if (i % 2 == 0) {
                paint.color = android.graphics.Color.argb(60, 35, 40, 60)
                canvas.drawRect(0f, y, width.toFloat(), y + rowH, paint)
            }

            paint.textSize = rowH * 0.44f

            val isAction = (value == "ACTION")
            val isDisabledAction = (value == "DISABLED")

            // Draw separator before action buttons
            if (i == 19) {
                paint.color = android.graphics.Color.argb(120, 100, 130, 200)
                paint.strokeWidth = 2f
                paint.style = android.graphics.Paint.Style.STROKE
                canvas.drawLine(8f, y + 1f, width - 8f, y + 1f, paint)
                paint.style = android.graphics.Paint.Style.FILL
                paint.strokeWidth = 0f
            }

            if (isAction) {
                // Full-width action buttons
                paint.color = when (i) {
                    19 -> android.graphics.Color.argb(170, 160, 30, 30)
                    20 -> android.graphics.Color.argb(170, 35, 140, 65)
                    21 -> android.graphics.Color.argb(170, 15, 85, 40)
                    22 -> android.graphics.Color.argb(170, 35, 95, 180)
                    23 -> android.graphics.Color.argb(170, 20, 50, 130)
                    24 -> android.graphics.Color.argb(120, 35, 55, 45)
                    else -> android.graphics.Color.argb(140, 40, 70, 120)
                }
                canvas.drawRoundRect(6f, y + rowH * 0.12f, width - 6f, y + rowH * 0.88f, 8f, 8f, paint)
                paint.color = android.graphics.Color.WHITE
                paint.textSize = rowH * 0.46f
                canvas.drawText(if (i < names.size) names[i] else "", 18f, y + rowH * 0.68f, paint)
            } else if (isDisabledAction) {
                paint.color = android.graphics.Color.argb(95, 58, 60, 68)
                canvas.drawRoundRect(6f, y + rowH * 0.12f, width - 6f, y + rowH * 0.88f, 8f, 8f, paint)
                paint.color = android.graphics.Color.argb(175, 168, 174, 188)
                paint.textSize = rowH * 0.42f
                canvas.drawText(if (i < names.size) names[i] else "", 18f, y + rowH * 0.56f, paint)
                paint.color = android.graphics.Color.argb(145, 138, 146, 160)
                paint.textSize = rowH * 0.22f
                canvas.drawText("LOAD A ROM FIRST", 18f, y + rowH * 0.82f, paint)
            } else if (isBool) {
                // Name on left
                paint.color = android.graphics.Color.argb(215, 190, 200, 220)
                canvas.drawText(if (i < names.size) names[i] else "", 12f, y + rowH * 0.68f, paint)
                // ON/OFF or status badge on right
                val badgeX = width * 0.68f
                paint.color = when {
                    value == "ON" -> android.graphics.Color.argb(210, 50, 180, 90)
                    value == "OFF" -> android.graphics.Color.argb(150, 160, 50, 50)
                    value == "USER OK" -> android.graphics.Color.argb(210, 45, 135, 210)
                    value == "BUNDLED" -> android.graphics.Color.argb(210, 70, 120, 205)
                    value == "USER BROKEN" || value == "USER BROKEN -> BUNDLED" ->
                        android.graphics.Color.argb(210, 185, 105, 35)
                    else -> android.graphics.Color.argb(170, 95, 95, 120)
                }
                canvas.drawRoundRect(badgeX, y + rowH * 0.18f, width - 8f, y + rowH * 0.82f, 8f, 8f, paint)
                paint.color = android.graphics.Color.WHITE
                paint.textSize = if (value.length > 10) rowH * 0.28f else rowH * 0.34f
                canvas.drawText(value, badgeX + 8f, y + rowH * 0.68f, paint)
            } else {
                // [−] name [value] [+] layout
                // Left minus button (static color — hover highlight is a GL quad)
                paint.color = android.graphics.Color.argb(120, 100, 60, 50)
                canvas.drawRoundRect(4f, y + rowH * 0.18f, btnW - 2f, y + rowH * 0.82f, 6f, 6f, paint)
                paint.color = android.graphics.Color.WHITE
                paint.textSize = rowH * 0.54f
                canvas.drawText("−", btnW * 0.22f, y + rowH * 0.70f, paint)

                // Name + value (centre)
                paint.textSize = rowH * 0.40f
                paint.color    = android.graphics.Color.argb(215, 190, 200, 220)
                canvas.drawText(if (i < names.size) names[i] else "", btnW + 6f, y + rowH * 0.52f, paint)
                paint.color = android.graphics.Color.argb(255, 120, 210, 255)
                canvas.drawText(value, btnW + 6f, y + rowH * 0.88f, paint)

                // Right plus button (static color — hover highlight is a GL quad)
                paint.color = android.graphics.Color.argb(120, 40, 100, 70)
                canvas.drawRoundRect(width - btnW + 2f, y + rowH * 0.18f, width - 4f, y + rowH * 0.82f, 6f, 6f, paint)
                paint.color = android.graphics.Color.WHITE
                paint.textSize = rowH * 0.54f
                canvas.drawText("+", width - btnW + btnW * 0.22f, y + rowH * 0.70f, paint)
            }
        }

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    fun renderDashboardLeftPanelBitmap(
        rowNames: Array<String>,
        rowValues: Array<String>,
        hoveredRow: Int,
        width: Int,
        height: Int
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Background
        paint.color = android.graphics.Color.argb(235, 12, 14, 24)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Title bar
        paint.color = android.graphics.Color.argb(255, 20, 50, 40)
        canvas.drawRect(0f, 0f, width.toFloat(), 88f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 44f
        paint.isFakeBoldText = true
        canvas.drawText("Dashboard", 12f, 60f, paint)
        paint.isFakeBoldText = false

        val titleH = 88f
        val n = rowNames.size
        val rowH = if (n > 0) ((height - titleH) / n).toFloat().coerceIn(52f, 96f) else 72f
        val btnW = width * 0.20f  // left/right button zones

        for (i in 0 until n) {
            val y = titleH + i * rowH
            val name = if (i < rowNames.size) rowNames[i] else ""
            val value = if (i < rowValues.size) rowValues[i] else ""

            // Row background (alternating)
            if (i % 2 == 0) {
                paint.color = android.graphics.Color.argb(60, 35, 40, 60)
                canvas.drawRect(0f, y, width.toFloat(), y + rowH, paint)
            }

            paint.textSize = rowH * 0.44f

            // Row name on left
            paint.color = android.graphics.Color.argb(215, 190, 200, 220)
            canvas.drawText(name, 12f, y + rowH * 0.68f, paint)

            // Value in center
            paint.color = android.graphics.Color.argb(200, 140, 200, 140)
            paint.textSize = rowH * 0.42f
            val tw = paint.measureText(value)
            canvas.drawText(value, width / 2f - tw / 2f, y + rowH * 0.68f, paint)

            // Left minus button (static color)
            paint.color = android.graphics.Color.argb(120, 70, 40, 40)
            canvas.drawRoundRect(2f, y + rowH * 0.18f, btnW - 2f, y + rowH * 0.82f, 6f, 6f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = rowH * 0.54f
            canvas.drawText("-", btnW * 0.25f, y + rowH * 0.70f, paint)

            // Right plus button (static color)
            paint.color = android.graphics.Color.argb(120, 40, 100, 70)
            canvas.drawRoundRect(width - btnW + 2f, y + rowH * 0.18f, width - 4f, y + rowH * 0.82f, 6f, 6f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = rowH * 0.54f
            canvas.drawText("+", width - btnW + btnW * 0.22f, y + rowH * 0.70f, paint)

            // Hover highlight (will be overlaid by GL quad, but draw the row rect for reference)
            if (i == hoveredRow) {
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = android.graphics.Color.argb(150, 120, 200, 200)
                canvas.drawRect(4f, y + 2f, width - 4f, y + rowH - 2f, paint)
                paint.style = android.graphics.Paint.Style.FILL
            }
        }

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    // rplace controller HUD -- small always-on panels attached to each
    // controller while a year is playing. `names` is unused here; it exists
    // only because the native rebuild_panel_tex() helper (openxr_shell.cpp)
    // always passes a names_arr (possibly null) as the first Kotlin argument
    // for every panel it calls, regardless of whether that panel uses it.
    // Shrinks paint.textSize (starting from startSize) until `text` measures
    // within maxWidth -- prevents any HUD line from silently running off the
    // fixed-resolution texture's edge (Canvas.drawText doesn't wrap or clip
    // to a max width on its own, it just draws past the bitmap and gets cut).
    private fun fitTextSize(paint: android.graphics.Paint, text: String, maxWidth: Float, startSize: Float): Float {
        var size = startSize
        paint.textSize = size
        while (size > 8f && paint.measureText(text) > maxWidth) {
            size -= 1f
            paint.textSize = size
        }
        return size
    }

    // All rplace controller-HUD stats now live on ONE panel (right
    // controller) so the left controller is free to be dedicated entirely
    // to the atlas caption -- see renderRplaceAtlasCaptionBitmap and its
    // hand-anchored placement in openxr_shell.cpp. Laid out with a running
    // row cursor (rowH-based) rather than hardcoded height fractions, so
    // adding/removing a row never silently overlaps or clips another one.
    fun renderRplaceHudRightPanelBitmap(
        names: Array<String>?,
        epochMs: Long,
        speed: Float,
        pixelsPerSec: Float,
        percentComplete: Float,
        lastEventEpochMs: Long,
        lastEventX: Int,
        lastEventY: Int,
        lastEventColorBgra: Int,
        width: Int,
        height: Int
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.argb(235, 12, 12, 22)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = android.graphics.Color.argb(180, 80, 120, 200)
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, paint)
        paint.style = android.graphics.Paint.Style.FILL

        val dateFormatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val timeFormatter = java.text.SimpleDateFormat("HH:mm:ss 'UTC'", java.util.Locale.US)
        dateFormatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        timeFormatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = java.util.Date(epochMs)
        val dateStr = dateFormatter.format(date)
        val timeStr = timeFormatter.format(date)
        val speedStr = "%.1fx".format(speed)
        val ppsStr = "%,d px/s".format(pixelsPerSec.toInt())
        val pctStr = "%.0f%%".format(percentComplete)

        val b = (lastEventColorBgra ushr 0) and 0xFF
        val g = (lastEventColorBgra ushr 8) and 0xFF
        val r = (lastEventColorBgra ushr 16) and 0xFF
        val hexStr = "#%02X%02X%02X".format(r, g, b)
        val eventFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.US)
        eventFormatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val eventTimeStr = eventFormatter.format(java.util.Date(lastEventEpochMs))
        val coordStr = "($lastEventX, $lastEventY)  $hexStr"

        val maxTextW = width * 0.90f
        val rowH = height / 11f
        var y = rowH * 0.75f

        fun label(text: String) {
            paint.color = android.graphics.Color.argb(255, 160, 190, 255)
            paint.textSize = rowH * 0.55f
            canvas.drawText(text, width * 0.05f, y, paint)
            y += rowH * 0.75f
        }
        fun value(text: String, argb: IntArray, bold: Boolean, maxW: Float = maxTextW) {
            paint.color = android.graphics.Color.argb(argb[0], argb[1], argb[2], argb[3])
            paint.isFakeBoldText = bold
            val size = fitTextSize(paint, text, maxW, rowH * 0.85f)
            paint.textSize = size
            canvas.drawText(text, width * 0.05f, y, paint)
            paint.isFakeBoldText = false
            y += rowH
        }

        label("CANVAS TIME")
        value(dateStr, intArrayOf(255, 190, 210, 255), false)
        value(timeStr, intArrayOf(255, 220, 235, 255), true)
        value(speedStr, intArrayOf(255, 160, 210, 160), true)
        y += rowH * 0.35f // small gap between the two stat groups

        value(ppsStr, intArrayOf(255, 200, 220, 255), false)
        value(pctStr, intArrayOf(255, 160, 210, 160), true)
        y += rowH * 0.15f

        label("LAST EVENT")
        // Ground-truth last-painted-event readout -- lets you compare this
        // raw record against whatever's actually visible on the canvas,
        // independent of where the camera/director happens to be pointed.
        val swatchTop = y - rowH * 0.7f
        value(coordStr, intArrayOf(255, 220, 220, 230), false, width * 0.75f) // leave room for swatch
        value(eventTimeStr, intArrayOf(255, 220, 220, 230), false)

        // Small swatch of the actual last-painted color, for a faster visual
        // compare than reading the hex code.
        val swatchBottom = swatchTop + rowH * 1.6f
        paint.color = android.graphics.Color.argb(255, r, g, b)
        canvas.drawRect(width * 0.80f, swatchTop, width * 0.94f, swatchBottom, paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = android.graphics.Color.argb(180, 255, 255, 255)
        canvas.drawRect(width * 0.80f, swatchTop, width * 0.94f, swatchBottom, paint)
        paint.style = android.graphics.Paint.Style.FILL

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    // Place Atlas caption panel -- shows the name/description of whichever
    // loaded atlas entry (see loadRplaceAtlas below) is nearest
    // cameraCenterX/Y, if within kAtlasMatchRadiusFraction of the canvas
    // width. Returns a deliberately WRONG-sized (empty) IntArray as a
    // "nothing nearby" sentinel -- see rebuild_rplace_atlas_caption_texture's
    // (openxr_shell.cpp) handling of that, which distinguishes it from a
    // normal width*height bitmap and hides the panel for that frame.
    // Set by renderRplaceAtlasCaptionBitmap() each time it runs, read by
    // native right afterward (rplaceAtlasCaptionWidthScale/HeightScale
    // below) to size the world-space quad so the panel grows/shrinks to fit
    // however much title+description text the current entry actually has,
    // instead of a fixed box that clips long entries or wastes space on
    // short ones. 1.0 = the base panel size (see add_rplace_atlas_caption_panel's
    // 0.28x0.105m default in openxr_shell.cpp).
    private var rplaceAtlasCaptionWidthScale = 1.0f
    private var rplaceAtlasCaptionHeightScale = 1.0f

    fun rplaceAtlasCaptionWidthScale(): Float = rplaceAtlasCaptionWidthScale
    fun rplaceAtlasCaptionHeightScale(): Float = rplaceAtlasCaptionHeightScale

    // Reason-fallback labels (see RplaceCanvas::MomentReason in rplace_canvas.h)
    // -- shown when the director picked this spot but no Place Atlas entry
    // actually covers it at this exact time, so there's still an honest
    // "why we're here" instead of an empty caption. Derived purely from the
    // precompute's two coarse signals (event density + color-churn), not
    // anything more specific -- kept intentionally generic/hedged since
    // that's genuinely all the data supports.
    private fun directorReasonLabel(reason: Int): String = when (reason) {
        1 -> "Contested spot — heavy repainting"
        2 -> "Large-scale build in progress"
        else -> "High pixel activity here"
    }
    private fun directorReasonDescription(reason: Int): String = when (reason) {
        1 -> "This area had an unusually high rate of pixels being overwritten by different colors -- likely two or more groups repeatedly painting over each other."
        2 -> "This area had a large amount of paint land with relatively little overwriting -- likely one large piece being built."
        else -> "This area had a burst of pixel-placement activity relative to the rest of the canvas at this time."
    }

    fun renderRplaceAtlasCaptionBitmap(
        cameraCenterX: Int, cameraCenterY: Int, epochMs: Long,
        directorActive: Boolean, directorReason: Int,
        width: Int, height: Int
    ): IntArray {
        val entries = rplaceAtlasEntries
        val periods = rplaceAtlasPeriods
        var name: String
        var description: String
        var bestTimeKey = ""

        if (entries.isNotEmpty() && rplaceCanvasWidthPx > 0) {
            val maxDist = rplaceCanvasWidthPx * kAtlasMatchRadiusFraction
            var best: AtlasEntry? = null
            var bestDist = Float.MAX_VALUE
            // Years with a real period table (2022/2023) only match centers
            // whose own [startMs,endMs) window (+ slack) actually covers the
            // current playback time -- this is the real time-filtered
            // matching now that we have placeAtlas's actual period->timestamp
            // table (see AtlasEntry's doc comment). 2017 has no such table
            // (periods == null) and keeps the original nearest-distance-only
            // matching across all of an entry's centers, since there's no
            // real per-entry timing to filter by there at all.
            for (e in entries) {
                for (c in e.centers) {
                    if (periods != null) {
                        if (!c.hasTime) continue
                        if (epochMs < c.startMs - kAtlasTimeSlackMs || epochMs > c.endMs + kAtlasTimeSlackMs) continue
                    }
                    val dx = (c.x - cameraCenterX).toFloat()
                    val dy = (c.y - cameraCenterY).toFloat()
                    val d = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (d < bestDist) { bestDist = d; best = e; bestTimeKey = c.rawKey }
                }
            }
            if (best != null && bestDist <= maxDist) {
                name = best.name
                description = best.description
            } else {
                name = ""; description = ""
            }
        } else {
            name = ""; description = ""
        }

        if (name.isEmpty()) {
            // No atlas entry covers this spot/time -- if the director is why
            // we're here, fall back to the precompute's own reason instead
            // of an empty caption. If this is just manual camera browsing
            // with nothing labeled nearby, stay silent as before.
            if (!directorActive) {
                rplaceAtlasCaptionWidthScale = 1.0f
                rplaceAtlasCaptionHeightScale = 1.0f
                return IntArray(0)
            }
            name = directorReasonLabel(directorReason)
            description = directorReasonDescription(directorReason)
            bestTimeKey = "" // no atlas time-range key to compare against for a reason-only caption
        }
        val entry = AtlasEntry(name, description, emptyList())

        // Word-wrap EVERYTHING (title included -- a long place name used to
        // be drawn as one unwrapped line and silently clip past the bitmap
        // edge, which is what "text spans into the other card" turned out
        // to be) against a fixed reference paint, so the needed line count
        // is known before we pick the box's scale -- not the other way
        // around like the old fixed-lineStep layout, which could run text
        // past the bottom of the bitmap for entries with a wrapped title.
        val measurePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // Sizes cut to 1/3 of the previous ones -- the doubled sizes from
        // before still didn't fit within any reasonable box growth, so this
        // trades legibility-per-character for actually fitting the box.
        val baseTitleSize = height * 0.133f
        val baseBodySize = height * 0.08f
        val baseTimestampSize = height * 0.053f
        val wrapWidth = width * 0.90f

        fun wordWrap(text: String, textSize: Float, maxLines: Int): List<String> {
            measurePaint.textSize = textSize
            val out = mutableListOf<String>()
            for (w in text.split(" ")) {
                if (out.isEmpty()) { out.add(w); continue }
                val candidate = "${out.last()} $w"
                if (measurePaint.measureText(candidate) > wrapWidth) {
                    out.add(w)
                } else {
                    out[out.lastIndex] = candidate
                }
            }
            return out.take(maxLines)
        }

        val titleLines = wordWrap(entry.name, baseTitleSize, 3)
        val bodyLines = wordWrap(entry.description, baseBodySize, 6)
        // Longest wrapped line across title+body, in pixels -- since every
        // line is already wrapped to fit wrapWidth by construction, this
        // should rarely exceed it (only a single unbreakable word longer
        // than wrapWidth can), so widthScale should stay near 1.0 normally.
        measurePaint.textSize = baseTitleSize
        val titleLineMaxW = titleLines.maxOfOrNull { measurePaint.measureText(it) } ?: 0f
        measurePaint.textSize = baseBodySize
        val bodyLineMaxW = bodyLines.maxOfOrNull { measurePaint.measureText(it) } ?: 0f
        val longestLineW = maxOf(titleLineMaxW, bodyLineMaxW)

        val titleLineStep = baseTitleSize * 1.25f
        val bodyLineStep = baseBodySize * 1.35f
        val timestampLineStep = baseTimestampSize * 1.3f
        // Total content height needed, in bitmap-pixel units, top margin +
        // title block + a gap + body block + a gap + the two timestamp
        // lines + bottom margin -- drives heightScale directly instead of
        // assuming a fixed line count.
        val topMargin = height * 0.12f
        val contentH = titleLines.size * titleLineStep +
            height * 0.06f +
            bodyLines.size * bodyLineStep +
            height * 0.08f +
            2 * timestampLineStep +
            height * 0.06f // bottom margin

        val widthScale = (longestLineW / wrapWidth).coerceIn(1.0f, 1.5f)
        val heightScale = ((topMargin + contentH) / height).coerceIn(1.0f, 4.0f)
        rplaceAtlasCaptionWidthScale = widthScale
        rplaceAtlasCaptionHeightScale = heightScale

        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.argb(220, 12, 12, 22)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = android.graphics.Color.argb(180, 200, 160, 80)
        canvas.drawRect(1f, 1f, width - 1f, height - 1f, paint)
        paint.style = android.graphics.Paint.Style.FILL

        // Bitmap pixel resolution is fixed (kRplaceAtlasCaptionTexW/H), so
        // when the quad grows (heightScale/widthScale > 1) we draw text at
        // the same fixed pixel sizes but the quad's physical growth is what
        // keeps it legible -- there's now guaranteed to be enough vertical
        // room since heightScale was derived from the actual content above.
        var y = topMargin + baseTitleSize * 0.8f
        paint.color = android.graphics.Color.argb(255, 255, 230, 180)
        paint.textSize = baseTitleSize
        paint.isFakeBoldText = true
        for (l in titleLines) {
            canvas.drawText(l, width * 0.05f, y, paint)
            y += titleLineStep
        }
        paint.isFakeBoldText = false

        y += height * 0.06f - titleLineStep + baseBodySize * 0.8f
        paint.color = android.graphics.Color.argb(230, 210, 210, 220)
        paint.textSize = baseBodySize
        for (l in bodyLines) {
            canvas.drawText(l, width * 0.05f, y, paint)
            y += bodyLineStep
        }

        // Timestamp compare line: the CURRENT canvas playback time (same
        // clock as the "CANVAS TIME" HUD panel) next to this entry's raw
        // atlas time-range key, verbatim -- the key's own bucket-index
        // encoding isn't decoded (see AtlasEntry doc comment), so this is
        // meant as an eyeball sanity check: if the canvas time looks way
        // outside what the key's numbers plausibly represent, the match is
        // probably wrong for right now, even though its position matched.
        val tsFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.US)
        tsFormatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val canvasTimeStr = tsFormatter.format(java.util.Date(epochMs))
        y += height * 0.08f - bodyLineStep + baseTimestampSize * 0.8f
        paint.color = android.graphics.Color.argb(255, 140, 210, 255)
        paint.textSize = baseTimestampSize
        canvas.drawText("CANVAS: $canvasTimeStr", width * 0.05f, y, paint)
        y += timestampLineStep
        val secondLine = if (bestTimeKey.isNotEmpty()) "ATLAS WINDOW: $bestTimeKey" else "(director pick -- no atlas entry matched)"
        canvas.drawText(secondLine, width * 0.05f, y, paint)

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    fun renderSaveStatePanelBitmap(
        romName: String,
        loadLabels: Array<String>,
        loadEnabled: BooleanArray,
        saveLabels: Array<String>,
        autosaveLabel: String,
        autoloadLabel: String,
        hoveredCell: Int,
        width: Int,
        height: Int
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.argb(238, 10, 12, 22)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val titleH = 88f
        paint.color = android.graphics.Color.argb(255, 28, 62, 92)
        canvas.drawRect(0f, 0f, width.toFloat(), titleH, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 46f
        paint.isFakeBoldText = true
        canvas.drawText("Save States", 16f, 60f, paint)
        paint.isFakeBoldText = false
        val hasActiveRom = romName.isNotEmpty()
        if (hasActiveRom) {
            paint.textSize = 26f
            paint.color = android.graphics.Color.argb(210, 120, 210, 255)
            val tw = paint.measureText(romName)
            canvas.drawText(romName, width - tw - 12f, 56f, paint)
        } else {
            paint.textSize = 24f
            paint.color = android.graphics.Color.argb(180, 150, 158, 176)
            val note = "No ROM loaded"
            val tw = paint.measureText(note)
            canvas.drawText(note, width - tw - 12f, 56f, paint)
        }

        val top = titleH + 24f
        val bottom = height - 24f
        val totalRows = 4
        val rowH = (bottom - top) / totalRows.toFloat()
        val colW = width / 3f

        for (row in 0 until 2) {
            for (col in 0 until 3) {
                val idx = row * 3 + col
                val x0 = col * colW + 12f
                val x1 = (col + 1) * colW - 12f
                val y0 = top + row * rowH + 10f
                val y1 = top + (row + 1) * rowH - 10f
                val isLoad = row == 0
                val enabled = if (!hasActiveRom) {
                    false
                } else if (isLoad) {
                    idx < loadEnabled.size && loadEnabled[idx]
                } else {
                    true
                }
                val label = if (isLoad) {
                    if (idx < loadLabels.size) loadLabels[idx] else "LOAD ${idx + 1}"
                } else {
                    val sidx = idx - 3
                    if (sidx in saveLabels.indices) saveLabels[sidx] else "SAVE ${sidx + 1}"
                }

                paint.color = when {
                    isLoad && enabled -> android.graphics.Color.argb(170, 26, 96, 144)
                    isLoad -> android.graphics.Color.argb(110, 42, 48, 64)
                    enabled -> android.graphics.Color.argb(180, 34, 124, 66)
                    else -> android.graphics.Color.argb(110, 48, 54, 60)
                }
                canvas.drawRoundRect(x0, y0, x1, y1, 18f, 18f, paint)

                if (idx == hoveredCell) {
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = 4f
                    paint.color = android.graphics.Color.argb(210, 180, 220, 255)
                    canvas.drawRoundRect(x0 + 2f, y0 + 2f, x1 - 2f, y1 - 2f, 16f, 16f, paint)
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.strokeWidth = 0f
                }

                paint.textAlign = android.graphics.Paint.Align.CENTER
                paint.color = if (!enabled) {
                    android.graphics.Color.argb(170, 170, 176, 190)
                } else {
                    android.graphics.Color.WHITE
                }
                paint.textSize = if (label.length > 14) rowH * 0.18f else rowH * 0.22f
                canvas.drawText(label, (x0 + x1) * 0.5f, y0 + (y1 - y0) * 0.60f, paint)

                paint.textSize = rowH * 0.14f
                paint.color = if (isLoad) {
                    if (enabled) android.graphics.Color.argb(190, 170, 225, 255)
                    else android.graphics.Color.argb(150, 138, 146, 160)
                } else if (enabled) {
                    android.graphics.Color.argb(190, 180, 240, 195)
                } else {
                    android.graphics.Color.argb(150, 138, 146, 160)
                }
                canvas.drawText(if (isLoad) "LOAD" else "SAVE", (x0 + x1) * 0.5f, y0 + (y1 - y0) * 0.82f, paint)
                paint.textAlign = android.graphics.Paint.Align.LEFT
            }
        }

        val optionLabels = arrayOf(
            "Autosave Every",
            "Load Last Save"
        )
        val optionValues = arrayOf(autosaveLabel, autoloadLabel)
        for (row in 0 until 2) {
            val cellId = 6 + row
            val x0 = 12f
            val x1 = width - 12f
            val y0 = top + (row + 2) * rowH + 10f
            val y1 = top + (row + 3) * rowH - 10f

            paint.color = android.graphics.Color.argb(170, 42, 54, 88)
            canvas.drawRoundRect(x0, y0, x1, y1, 18f, 18f, paint)

            if (hoveredCell == cellId) {
                paint.style = android.graphics.Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = android.graphics.Color.argb(210, 180, 220, 255)
                canvas.drawRoundRect(x0 + 2f, y0 + 2f, x1 - 2f, y1 - 2f, 16f, 16f, paint)
                paint.style = android.graphics.Paint.Style.FILL
                paint.strokeWidth = 0f
            }

            paint.color = android.graphics.Color.argb(215, 196, 208, 228)
            paint.textSize = rowH * 0.22f
            canvas.drawText(optionLabels[row], x0 + 24f, y0 + (y1 - y0) * 0.45f, paint)

            paint.textAlign = android.graphics.Paint.Align.RIGHT
            paint.color = android.graphics.Color.argb(230, 120, 210, 255)
            paint.textSize = rowH * 0.26f
            canvas.drawText(optionValues[row], x1 - 24f, y0 + (y1 - y0) * 0.58f, paint)

            paint.textAlign = android.graphics.Paint.Align.LEFT
            paint.color = android.graphics.Color.argb(165, 170, 196, 220)
            paint.textSize = rowH * 0.14f
            canvas.drawText("TRIGGER TO CYCLE", x0 + 24f, y0 + (y1 - y0) * 0.78f, paint)
        }

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    // -----------------------------------------------------------------------
    // Called from C++ GL thread — renders the code-input panel (above ROM browser).
    // currentInput: chars typed so far. hoveredKey: 0-35=alphanum, 36=⌫, -1=none.
    // Layout: title area (current code) + 4 key rows:
    //   Title: current share code (large) + Enter Code label
    //   Row 0: 0-9   (keys 0-9)
    //   Row 1: A-J   (keys 10-19)
    //   Row 2: K-T   (keys 20-29)
    //   Row 3: U-Z+⌫ (keys 30-36; cols 0-5=U-Z, col 6=⌫)
    // -----------------------------------------------------------------------
    fun renderCodePanelBitmap(
        mode: Int,
        currentInput: String,
        secondaryText: String,
        hoveredKey: Int,
        width: Int,
        height: Int
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Background
        paint.color = android.graphics.Color.argb(235, 10, 16, 28)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Title area (80 px) - shows current share code + label
        val titleH = 80f
        paint.color = android.graphics.Color.argb(255, 20, 50, 80)
        canvas.drawRect(0f, 0f, width.toFloat(), titleH, paint)

        if (mode == 1) {
            paint.color = android.graphics.Color.argb(210, 235, 220, 160)
            paint.textSize = 26f
            paint.isFakeBoldText = true
            canvas.drawText("Cancel", 18f, 50f, paint)
            val saveLabel = "Save"
            val saveW = paint.measureText(saveLabel)
            canvas.drawText(saveLabel, width - saveW - 18f, 50f, paint)
            paint.color = android.graphics.Color.argb(190, 180, 210, 245)
            paint.textSize = 20f
            paint.isFakeBoldText = false
            val slotLabel = if (secondaryText.isNotEmpty()) secondaryText else "Preset Name"
            val slotW = paint.measureText(slotLabel)
            canvas.drawText(slotLabel, (width - slotW) * 0.5f, 26f, paint)
            val spaceLabel = "Space"
            val spaceW = paint.measureText(spaceLabel)
            canvas.drawText(spaceLabel, (width - spaceW) * 0.5f, 54f, paint)
            val displayStr = if (currentInput.isEmpty()) "Type preset name" else currentInput.take(24)
            paint.textSize = 30f
            paint.color = android.graphics.Color.argb(255, 120, 230, 160)
            val inputW = paint.measureText(displayStr)
            canvas.drawText(displayStr, (width - inputW) * 0.5f, 76f, paint)
        } else {
            paint.color = android.graphics.Color.argb(255, 100, 200, 255)
            paint.textSize = 36f
            paint.isFakeBoldText = true
            val codeDisplay = if (secondaryText.isNotEmpty()) secondaryText.take(16) else "(no code)"
            canvas.drawText(codeDisplay, 12f, 32f, paint)
            paint.isFakeBoldText = false

            paint.color = android.graphics.Color.argb(200, 180, 180, 180)
            paint.textSize = 20f
            canvas.drawText("Type to enter:", width - 160f, 28f, paint)

            val displayStr = if (currentInput.isEmpty()) "______" else currentInput
            paint.textSize = 28f
            paint.color = android.graphics.Color.argb(255, 120, 230, 160)
            val tw = paint.measureText(displayStr)
            canvas.drawText(displayStr, width - tw - 12f, 64f, paint)
        }

        // Key grid
        val cols = 10
        val rows = 4
        val keyAreaH = height - titleH
        val keyH = keyAreaH / rows
        val keyW = width.toFloat() / cols
        val keys = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ⌫"

        for (row in 0 until rows) {
            val y = titleH + row * keyH
            val colCount = if (row < 3) cols else 7
            for (col in 0 until colCount) {
                val keyIdx = when (row) {
                    0    -> col
                    1    -> 10 + col
                    2    -> 20 + col
                    else -> if (col < 6) 30 + col else 36
                }
                val isBackspace = (keyIdx == 36)
                val x = col * keyW

                paint.color = when {
                    isBackspace  -> android.graphics.Color.argb(140, 140, 50, 50)
                    row % 2 == 0 -> android.graphics.Color.argb(100, 30, 50, 80)
                    else         -> android.graphics.Color.argb(70, 20, 35, 60)
                }
                canvas.drawRoundRect(x + 2f, y + 2f, x + keyW - 2f, y + keyH - 2f, 6f, 6f, paint)

                val label = keys[keyIdx].toString()
                paint.color = android.graphics.Color.argb(210, 190, 205, 225)
                paint.textSize = keyH * 0.52f
                val lw = paint.measureText(label)
                canvas.drawText(label, x + (keyW - lw) * 0.5f, y + keyH * 0.68f, paint)
            }
        }

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    // -----------------------------------------------------------------------
    // Called from C++ GL thread — renders the controller mapping panel.
    // buttonNames: 12 emulated button names. questNames: 12 current Quest bindings.
    // hoveredRow: row under laser (-1=none). selectedRow: row being remapped (-1=none).
    // Bottom 6 rows after the 12 button rows are action buttons:
    //   Reset, Load Game, Load Global, Save Game, Save Global, Back
    // Returns ARGB_8888 pixels, width×height.
    // -----------------------------------------------------------------------
    fun renderCtrlMapPanelBitmap(
        title: String,
        buttonNames: Array<String>,
        questNames: Array<String>,
        hoveredRow: Int,
        selectedRow: Int,
        width: Int,
        height: Int
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint  = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Background
        paint.color = android.graphics.Color.argb(235, 14, 12, 24)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Title bar
        val titleH = 88f
        paint.color = android.graphics.Color.argb(255, 50, 30, 80)
        canvas.drawRect(0f, 0f, width.toFloat(), titleH, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 44f
        paint.isFakeBoldText = true
        canvas.drawText(title, 12f, 60f, paint)
        paint.isFakeBoldText = false

        val hint = if (selectedRow >= 0) "Use stick to change binding" else "Tap a button to remap"
        paint.textSize = 26f
        paint.color = android.graphics.Color.argb(180, 160, 200, 255)
        val hw = paint.measureText(hint)
        canvas.drawText(hint, width - hw - 8f, 56f, paint)

        val n = buttonNames.size // 12
        val actionLabels = arrayOf("Reset Defaults", "Load Game", "Load Global", "Save Game", "Save Global", "← Back")
        val totalRows = n + actionLabels.size
        val rowH = ((height - titleH) / totalRows).toFloat().coerceIn(44f, 80f)

        for (i in 0 until totalRows) {
            val y       = titleH + i * rowH

            if (i < n) {
                // Emulated button mapping row
                val isSelected = (i == selectedRow)

                // Row background (selected or alternating; hover highlight is a separate GL quad)
                val bgColor = when {
                    isSelected -> android.graphics.Color.argb(200, 60, 30, 120)
                    i % 2 == 0 -> android.graphics.Color.argb(60, 35, 35, 55)
                    else       -> 0
                }
                if (bgColor != 0) {
                    paint.color = bgColor
                    canvas.drawRect(0f, y, width.toFloat(), y + rowH, paint)
                }

                // Selection accent bar
                if (isSelected) {
                    paint.color = android.graphics.Color.argb(255, 140, 80, 255)
                    canvas.drawRect(0f, y, 5f, y + rowH, paint)
                }

                paint.textSize = rowH * 0.44f

                // Emulated button name (left column, ~40% width)
                paint.color = if (isSelected) android.graphics.Color.argb(255, 200, 160, 255)
                              else android.graphics.Color.argb(215, 190, 200, 220)
                canvas.drawText(if (i < buttonNames.size) buttonNames[i] else "", 14f, y + rowH * 0.68f, paint)

                // Arrow separator
                paint.color = android.graphics.Color.argb(120, 160, 160, 180)
                paint.textSize = rowH * 0.36f
                canvas.drawText("→", width * 0.42f, y + rowH * 0.68f, paint)

                // Quest binding (right column, highlight if selected)
                paint.textSize = rowH * 0.44f
                val bindingColor = if (isSelected) android.graphics.Color.argb(255, 100, 230, 255)
                                   else android.graphics.Color.argb(220, 90, 200, 255)
                paint.color = bindingColor
                canvas.drawText(if (i < questNames.size) questNames[i] else "---", width * 0.50f, y + rowH * 0.68f, paint)

                // If selected: show cycle hint with ◄►
                if (isSelected) {
                    paint.textSize = rowH * 0.34f
                    paint.color = android.graphics.Color.argb(160, 200, 200, 200)
                    canvas.drawText("◄ stick ►", width - 130f, y + rowH * 0.68f, paint)
                }
            } else {
                // Action button row
                val actionIdx = i - n
                val label = if (actionIdx < actionLabels.size) actionLabels[actionIdx] else ""
                val isBack = (actionIdx == actionLabels.size - 1)

                // Separator before action rows
                if (actionIdx == 0) {
                    paint.color = android.graphics.Color.argb(120, 100, 130, 200)
                    paint.strokeWidth = 2f
                    paint.style = android.graphics.Paint.Style.STROKE
                    canvas.drawLine(8f, y + 1f, width - 8f, y + 1f, paint)
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.strokeWidth = 0f
                }

                val btnColor = if (isBack) android.graphics.Color.argb(120, 35, 55, 45)
                               else        android.graphics.Color.argb(140, 40, 70, 120)
                paint.color = btnColor
                canvas.drawRoundRect(6f, y + rowH * 0.12f, width - 6f, y + rowH * 0.88f, 8f, 8f, paint)
                paint.color = android.graphics.Color.WHITE
                paint.textSize = rowH * 0.44f
                canvas.drawText(label, 18f, y + rowH * 0.68f, paint)
            }
        }

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    // -----------------------------------------------------------------------
    // Called from C++ XR thread — renders passive side help panels.
    // inputLabels/actionLabels are parallel arrays generated from native control metadata.
    // Returns ARGB_8888 pixels, width×height.
    // -----------------------------------------------------------------------
    fun renderHelpPanelBitmap(
        title: String,
        inputLabels: Array<String>,
        actionLabels: Array<String>,
        width: Int,
        height: Int
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        fun wrapText(text: String, maxWidth: Float): List<String> {
            if (text.isEmpty()) return listOf("")
            val words = text.split(' ')
            val lines = mutableListOf<String>()
            var line = ""
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                    line = candidate
                } else {
                    lines.add(line)
                    line = word
                }
            }
            if (line.isNotEmpty()) lines.add(line)
            return lines
        }

        paint.color = android.graphics.Color.argb(230, 10, 12, 18)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val titleH = 86f
        paint.color = android.graphics.Color.argb(255, 34, 48, 62)
        canvas.drawRect(0f, 0f, width.toFloat(), titleH, paint)

        paint.color = android.graphics.Color.WHITE
        paint.textSize = 42f
        paint.isFakeBoldText = true
        canvas.drawText(title, 18f, 58f, paint)
        paint.isFakeBoldText = false

        val count = minOf(inputLabels.size, actionLabels.size)
        var y = titleH + 36f
        val marginX = 24f
        val maxTextW = width - marginX * 2f
        val actionSize = if (count > 12) 25f else 28f
        val inputSize = if (count > 12) 23f else 25f

        for (i in 0 until count) {
            if (y > height - 36f) break

            if (i > 0) {
                paint.color = android.graphics.Color.argb(70, 120, 140, 160)
                canvas.drawRect(marginX, y - 17f, width - marginX, y - 15f, paint)
            }

            paint.textSize = inputSize
            paint.color = android.graphics.Color.argb(230, 115, 215, 245)
            paint.isFakeBoldText = true
            val input = inputLabels[i]
            val inputLines = wrapText(input, maxTextW)
            for (line in inputLines) {
                if (y > height - 30f) break
                canvas.drawText(line, marginX, y, paint)
                y += inputSize + 4f
            }
            paint.isFakeBoldText = false

            paint.textSize = actionSize
            paint.color = android.graphics.Color.argb(225, 218, 224, 232)
            val actionLines = wrapText(actionLabels[i], maxTextW)
            for (line in actionLines) {
                if (y > height - 30f) break
                canvas.drawText(line, marginX, y, paint)
                y += actionSize + 5f
            }
            y += 16f
        }

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    // Called from C++ XR thread to extract a zip/7z archive and return the ROM path inside.
    fun prepareRomFileForNative(rawPath: String): String =
        runCatching { prepareRomFile(File(rawPath)).absolutePath }.getOrElse { rawPath }

    // -----------------------------------------------------------------------
    // Called from C++ XR thread to render the main menu panel texture.
    // Returns ARGB_8888 pixel array of size width×height.
    // menuItems: labels for each menu option. hoveredRow: row under laser (-1=none).
    // romName: name of currently loaded ROM (empty = none).
    // -----------------------------------------------------------------------
    fun renderMainMenuPanelBitmap(
        menuItems: Array<String>,
        hoveredRow: Int,
        width: Int,
        height: Int,
        romName: String
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Panel background
        paint.color = android.graphics.Color.argb(240, 12, 12, 22)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Title bar
        val titleBarTop = 0f
        val titleBarBottom = titleBarTop + 88f
        paint.color = android.graphics.Color.argb(255, 30, 50, 90)
        canvas.drawRect(0f, titleBarTop, width.toFloat(), titleBarBottom, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 48f
        paint.isFakeBoldText = true
        canvas.drawText("RetroDepth", 14f, titleBarTop + 60f, paint)
        paint.isFakeBoldText = false

        // ROM name display (right side of title bar)
        if (romName.isNotEmpty()) {
            paint.textSize = 26f
            paint.color = android.graphics.Color.argb(200, 120, 210, 255)
            val tw = paint.measureText(romName)
            canvas.drawText(romName, width - tw - 12f, titleBarTop + 56f, paint)
        }

        val titleH = titleBarBottom
        val n = menuItems.size
        val rowH = ((height - titleH) / n).toFloat()

        for (i in menuItems.indices) {
            val y = titleH + i * rowH

            // Row background (alternating rows only; hover is a GL quad)
            if (i % 2 == 0) {
                paint.color = android.graphics.Color.argb(60, 35, 40, 60)
                canvas.drawRect(0f, y, width.toFloat(), y + rowH, paint)
            }

            // Separator before "Exit"
            if (i == n - 1) {
                paint.color = android.graphics.Color.argb(120, 100, 130, 200)
                paint.strokeWidth = 2f
                paint.style = android.graphics.Paint.Style.STROKE
                canvas.drawLine(8f, y + 1f, width - 8f, y + 1f, paint)
                paint.style = android.graphics.Paint.Style.FILL
                paint.strokeWidth = 0f
            }

            val label = menuItems[i]
            val maxLabelWidth = width - 40f
            var textSize = rowH * 0.48f
            paint.textSize = textSize
            // Shrink to fit -- rows like "r/place 2022 (download required)" are
            // long enough to run off the panel edge at the default row-height-
            // based size, since drawText doesn't wrap or auto-scale.
            while (textSize > 14f && paint.measureText(label) > maxLabelWidth) {
                textSize -= 2f
                paint.textSize = textSize
            }
            paint.color = android.graphics.Color.argb(220, 200, 210, 230)
            canvas.drawText(label, 20f, y + rowH * 0.68f, paint)
        }

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    // Called from C++ XR thread to exit the app
    fun exitApp() {
        handler.post {
            vrStarted = false
            nativeStopVr()
            finishAndRemoveTask()
        }
    }

    // Called from C++ to get the ROM root path.
    // Uses /storage/emulated/0/QuestRetroDepth/roms with `snes/` and `genesis/` subfolders.
    // Survives app uninstall/reinstall. Falls back to app-private storage if unavailable.
    fun getRomDirectory(): String {
        val dir = java.io.File(Environment.getExternalStorageDirectory(), "QuestRetroDepth/roms")
        if (dir.exists() || dir.mkdirs()) {
            createRomSubfolders(dir)
            return dir.absolutePath
        }
        return java.io.File(getExternalFilesDir(null), "roms").apply {
            mkdirs()
            createRomSubfolders(this)
        }.absolutePath
    }

    private fun createRomSubfolders(base: File) {
        for (name in listOf("pce", "snes", "genesis", "sms", "nes", "gb", "gg", "gba", "gbc")) {
            File(base, name).mkdirs()
        }
    }

    // Called from C++ to get the settings directory path (created if needed).
    // Uses /storage/emulated/0/QuestRetroDepth/config so settings survive reinstall and
    // are accessible via USB. Falls back to app-private storage if unavailable.
    fun getSettingsDirectory(): String {
        val dir = java.io.File(Environment.getExternalStorageDirectory(), "QuestRetroDepth/config")
        if (dir.exists() || dir.mkdirs()) return dir.absolutePath
        return java.io.File(getExternalFilesDir(null), "settings").apply { mkdirs() }.absolutePath
    }

    fun getRumbleDirectory(): String {
        val dir = java.io.File(Environment.getExternalStorageDirectory(), "QuestRetroDepth/rumble")
        if (dir.exists() || dir.mkdirs()) {
            createRomSubfolders(dir)
            return dir.absolutePath
        }
        return java.io.File(getExternalFilesDir(null), "rumble").apply {
            mkdirs()
            createRomSubfolders(this)
        }.absolutePath
    }

    // -----------------------------------------------------------------------
    // Homebrew Manager
    // -----------------------------------------------------------------------

    data class HomebrewEntry(
        val name: String,
        val author: String,
        val license: String,
        val website: String,
        val download: String,
        val system: String,
        val filename: String = "",
        val licenseUrl: String = "",
        val source: String = "",
        val sourceEntryUrl: String = "",
        val distributionMode: String = "official",
        val mirrorAllowed: Boolean = false,
        val notes: String = ""
    )

    data class HomebrewFeedSource(
        val fileName: String,
        val name: String,
        val url: String
    )

    private var hwEntries: List<HomebrewEntry> = emptyList()
    private var hwFeeds: List<HomebrewFeedSource> = fallbackHomebrewFeeds()
    private var hasValidatedRemoteHomebrewFeeds = false
    private val hwDownloaded = mutableSetOf<String>()

    private val supportedHomebrewSystems = setOf("nes", "gb", "gbc", "gba", "sms", "gg", "snes", "genesis", "pce")

    private fun fallbackHomebrewFeeds(): List<HomebrewFeedSource> = listOf(
        HomebrewFeedSource(
            "all_homebrew.json",
            "All Systems",
            "https://raw.githubusercontent.com/maranone/QuestRetroDepth/main/homebrew/all_homebrew.json"
        ),
        HomebrewFeedSource(
            "featured_homebrew.json",
            "Featured",
            "https://raw.githubusercontent.com/maranone/QuestRetroDepth/main/homebrew/featured_homebrew.json"
        ),
        HomebrewFeedSource(
            "ghb_curated.json",
            "GHB",
            "https://raw.githubusercontent.com/maranone/QuestRetroDepth/main/homebrew/ghb_curated.json"
        )
    )

    private fun fetchHttpText(url: String, accept: String? = null): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "QuestRetroDepth")
            if (!accept.isNullOrBlank()) {
                conn.setRequestProperty("Accept", accept)
            }
            conn.connect()
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseHomebrewCatalogDisplayName(fileName: String, body: String): String {
        return runCatching {
            val obj = org.json.JSONObject(body)
            obj.optString("feed").trim().ifBlank { fileName.removeSuffix(".json") }
        }.getOrDefault(fileName.removeSuffix(".json"))
    }

    private fun isValidHomebrewCatalog(body: String): Boolean {
        return runCatching {
            val obj = org.json.JSONObject(body)
            obj.optJSONArray("roms") != null
        }.getOrDefault(false)
    }

    private fun refreshHomebrewFeeds(): List<HomebrewFeedSource> {
        return runCatching {
            val body = fetchHttpText(HOME_BREW_GITHUB_API_URL, "application/vnd.github+json")

            val parsed = mutableListOf<HomebrewFeedSource>()
            val arr = org.json.JSONArray(body)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (!item.optString("type").equals("file", ignoreCase = true)) continue
                val fileName = item.optString("name").trim()
                val downloadUrl = item.optString("download_url").trim()
                if (!fileName.endsWith(".json", ignoreCase = true)) continue
                if (downloadUrl.isBlank()) continue
                val catalogBody = runCatching { fetchHttpText(downloadUrl, "application/json") }.getOrNull() ?: continue
                if (!isValidHomebrewCatalog(catalogBody)) continue
                parsed.add(
                    HomebrewFeedSource(
                        fileName = fileName,
                        name = parseHomebrewCatalogDisplayName(fileName, catalogBody),
                        url = downloadUrl
                    )
                )
            }
            if (parsed.isNotEmpty()) {
                hwFeeds = parsed.sortedBy { it.name.lowercase(Locale.US) }
                hasValidatedRemoteHomebrewFeeds = true
            } else if (!hasValidatedRemoteHomebrewFeeds) {
                hwFeeds = fallbackHomebrewFeeds()
            }
            hwFeeds
        }.getOrElse {
            Log.e("Homebrew", "Feed directory fetch failed: ${it.message}")
            if (!hasValidatedRemoteHomebrewFeeds || hwFeeds.isEmpty()) {
                hwFeeds = fallbackHomebrewFeeds()
            }
            hwFeeds
        }
    }

    private fun selectedHomebrewFeedIndex(feeds: List<HomebrewFeedSource> = if (hwFeeds.isEmpty()) fallbackHomebrewFeeds() else hwFeeds): Int {
        val savedFileName = prefs.getString(PREF_HOME_BREW_FEED_FILE_NAME, null)
        val byFileName = if (!savedFileName.isNullOrBlank()) feeds.indexOfFirst { it.fileName == savedFileName } else -1
        if (byFileName >= 0) return byFileName
        val savedIndex = prefs.getInt(PREF_HOME_BREW_FEED_INDEX, 0)
        return savedIndex.coerceIn(0, maxOf(0, feeds.size - 1))
    }

    private fun persistHomebrewFeedSelection(feed: HomebrewFeedSource, index: Int) {
        prefs.edit()
            .putString(PREF_HOME_BREW_FEED_FILE_NAME, feed.fileName)
            .putInt(PREF_HOME_BREW_FEED_INDEX, index)
            .apply()
    }

    private fun hwFeedName(feedIdx: Int): String {
        val feeds = if (hwFeeds.isEmpty()) fallbackHomebrewFeeds() else hwFeeds
        return feeds.getOrNull(feedIdx)?.name ?: feeds.firstOrNull()?.name ?: "Homebrew"
    }

    fun showHomebrewFeedDialog(currentFeedIdx: Int) {
        Thread {
            val feeds = refreshHomebrewFeeds()
            runOnUiThread {
                if (isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed)) {
                    return@runOnUiThread
                }
                if (feeds.isEmpty()) return@runOnUiThread
                val labels = feeds.map { it.name }.toTypedArray()
                val initial = selectedHomebrewFeedIndex(feeds).coerceIn(0, feeds.size - 1)
                AlertDialog.Builder(this)
                    .setTitle("Select Homebrew Feed")
                    .setSingleChoiceItems(labels, initial) { dialog, which ->
                        persistHomebrewFeedSelection(feeds[which], which)
                        nativeSetHomebrewFeed(which)
                        homebrewFetchFeed(which)
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }.start()
    }

    private fun normalizeHomebrewSystem(raw: String): String {
        val normalized = raw.trim().lowercase(Locale.US)
        return if (normalized in supportedHomebrewSystems) normalized else ""
    }

    private fun parseContentDispositionFilename(header: String?): String {
        if (header.isNullOrBlank()) return ""
        val starMatch = Regex("""filename\*\s*=\s*([^']*)''([^;]+)""", RegexOption.IGNORE_CASE).find(header)
        if (starMatch != null) {
            val encoded = starMatch.groupValues.getOrElse(2) { "" }
            return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
        }
        val plainMatch = Regex("""filename\s*=\s*"?([^\";]+)"?""", RegexOption.IGNORE_CASE).find(header)
        return plainMatch?.groupValues?.getOrElse(1) { "" } ?: ""
    }

    private fun inferredFilenameFromUrl(url: String): String {
        val clean = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        return clean
    }

    private fun resolvedHomebrewFilename(
        entry: HomebrewEntry,
        responseFilename: String = "",
        finalUrl: String = entry.download
    ): String {
        val candidates = listOf(
            entry.filename,
            responseFilename,
            inferredFilenameFromUrl(finalUrl),
            inferredFilenameFromUrl(entry.download)
        )
        for (candidate in candidates) {
            val sanitized = sanitize(candidate.trim())
            if (sanitized.isNotBlank() && isSupportedOrArchiveFile(File(sanitized))) {
                return sanitized
            }
        }
        return ""
    }

    private fun hwFile(entry: HomebrewEntry): File =
        File(
            getRomDirectory(),
            "${entry.system}/${resolvedHomebrewFilename(entry).ifBlank { "__invalid_homebrew__" }}"
        )

    fun isHomebrewDownloaded(entryIdx: Int): Boolean {
        val list = hwEntries
        if (entryIdx < 0 || entryIdx >= list.size) return false
        return hwFile(list[entryIdx]).exists()
    }

    fun homebrewFetchFeed(feedIdx: Int) {
        Thread {
            try {
                val feeds = refreshHomebrewFeeds()
                val url = feeds.getOrNull(feedIdx)?.url ?: return@Thread
                val body = fetchHttpText(url, "application/json")
                val entries = mutableListOf<HomebrewEntry>()
                val obj = org.json.JSONObject(body)
                val roms = obj.optJSONArray("roms") ?: org.json.JSONArray()
                for (i in 0 until roms.length()) {
                    val r = roms.getJSONObject(i)
                    val system = normalizeHomebrewSystem(r.optString("system"))
                    val download = r.optString("download").trim()
                    if (system.isEmpty() || download.isEmpty()) continue
                    entries.add(HomebrewEntry(
                        name     = r.optString("name"),
                        author   = r.optString("author"),
                        license  = r.optString("license"),
                        website  = r.optString("website"),
                        download = download,
                        system   = system,
                        filename = sanitize(r.optString("filename")),
                        licenseUrl = r.optString("license_url"),
                        source = r.optString("source"),
                        sourceEntryUrl = r.optString("source_entry_url"),
                        distributionMode = r.optString("distribution_mode", "official"),
                        mirrorAllowed = r.optBoolean("mirror_allowed", false),
                        notes = r.optString("notes")
                    ))
                }
                hwEntries = entries
                feeds.getOrNull(feedIdx)?.let { persistHomebrewFeedSelection(it, feedIdx) }
                hwDownloaded.clear()
                for (e in entries) { if (hwFile(e).exists()) hwDownloaded.add(e.download) }
            } catch (e: Exception) {
                Log.e("Homebrew", "Fetch failed: ${e.message}")
            }
            runOnUiThread { nativeHomebrewDataReady() }
        }.start()
    }

    fun homebrewDownload(entryIdx: Int) {
        val list = hwEntries
        if (entryIdx < 0 || entryIdx >= list.size) return
        val entry = list[entryIdx]
        Thread {
            try {
                val conn = java.net.URL(entry.download).openConnection() as java.net.HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "QuestRetroDepth")
                    conn.connect()
                    val responseFilename = parseContentDispositionFilename(conn.getHeaderField("Content-Disposition"))
                    val resolvedFilename = resolvedHomebrewFilename(entry, responseFilename, conn.url.toString())
                    if (resolvedFilename.isBlank()) {
                        throw IllegalStateException("Unsupported or missing filename for ${entry.name}")
                    }
                    val dest = File(getRomDirectory(), "${entry.system}/${resolvedFilename}")
                    dest.parentFile?.mkdirs()
                    conn.inputStream.use { inp ->
                        FileOutputStream(dest).use { out -> inp.copyTo(out) }
                    }
                    hwDownloaded.add(entry.download)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e("Homebrew", "Download failed: ${e.message}")
            }
            runOnUiThread { nativeHomebrewDownloadComplete(entryIdx) }
        }.start()
    }

    fun homebrewDelete(entryIdx: Int) {
        val list = hwEntries
        if (entryIdx < 0 || entryIdx >= list.size) return
        val entry = list[entryIdx]
        hwFile(entry).delete()
        hwDownloaded.remove(entry.download)
        nativeHomebrewDataReady()
    }

    // ============================================================
    // r/place canvas data (rplaceRT) -- year listing, bundled-asset convert
    // ============================================================

    // Set while a year's bundled asset is being streamed through brotli
    // decompression + native conversion into its playable .bin (RPL2
    // stream format). Progress is measured by compressed bytes CONSUMED
    // from the bundled asset (we don't know the exact decompressed size up
    // front, but compressed-bytes-read advances ~linearly through the
    // stream, which is an accurate enough proxy for a progress bar).
    @Volatile var rplaceDecompressingYear: String? = null
    @Volatile var rplaceDecompressProgress: Float = 0f // 0..1
    @Volatile var rplaceActiveYear: String? = null
    // Set right before nativeRplaceStart is called, cleared once the native
    // decode+sort finishes (nativeRplaceIsReady() true) or the file turns out
    // missing. There's only ever one RplaceCanvas in native, so this doesn't
    // need to be per-year -- just "which year is currently mid-decode".
    @Volatile var rplaceLoadingYear: String? = null
    // Set while deleteAllRplaceYears() is walking the year list; holds the
    // year currently being deleted so rplaceMenuLabel can show "(deleting)"
    // for that row specifically (deletion is otherwise near-instant, so a
    // small artificial pause per year is added purely so the label is
    // actually visible instead of flashing by unnoticed).
    @Volatile var rplaceDeletingYear: String? = null
    @Volatile var rplaceDeleteAllInProgress = false

    // ---- Place Atlas (github.com/placeAtlas) ----
    // One entry per named/described region in the currently-playing year's
    // atlas.json. `centers` collects every time-window's center point for
    // that entry, each carrying a real [startMs, endMs) UTC range when one
    // is derivable (see AtlasCenter/parseAtlasTimeRange below) -- confirmed
    // against placeAtlas's actual source (atlas-2022/2023's web/_js/config.js
    // `variationsConfig.default.versions[]`, a real period-index -> Unix-
    // timestamp table, NOT a formula -- spacing is irregular, mostly 30min
    // but with some 60min gaps, so it has to be looked up per-index, not
    // computed). Bundled per-year as assets/atlas/$year.periods.json (see
    // loadRplaceAtlasPeriodsForYear). 2017's atlas has no real per-entry
    // timing at all (every entry shares one placeholder key) and 2022/2023
    // entries tagged only with a non-default variation like "T:0-1" ("The
    // Final Clean", the post-event mod cleanup state) have no real
    // timestamp either -- both cases leave hasTime=false, and such centers
    // are excluded from time-filtered matching (see renderRplaceAtlasCaptionBitmap).
    data class AtlasCenter(val x: Int, val y: Int, val rawKey: String, val startMs: Long, val endMs: Long, val hasTime: Boolean)
    data class AtlasEntry(val name: String, val description: String, val centers: List<AtlasCenter>)
    @Volatile var rplaceAtlasEntries: List<AtlasEntry> = emptyList()
    // Period-index -> Unix-seconds table for the currently-playing year, or
    // null if this year has no real per-entry timing data (2017) -- see
    // AtlasEntry's doc comment. Index N corresponds directly to the atlas
    // JSON's period-range numbers (both are the site's own array index).
    @Volatile var rplaceAtlasPeriods: LongArray? = null
    // Canvas pixel width for the currently-playing year -- used to scale the
    // atlas match radius (see kAtlasMatchRadiusFraction) and set once
    // per-year alongside the atlas load (see loadRplaceAtlasForYear).
    @Volatile var rplaceCanvasWidthPx: Int = 0
    // How close (as a fraction of canvas width) the camera center has to be
    // to an atlas entry's center point before its caption shows.
    private val kAtlasMatchRadiusFraction = 0.15f
    // How far outside a center's own [startMs,endMs) window the current
    // playback time is still allowed to be for a time-filtered match --
    // period boundaries are ~30min apart and not exact to the second, so a
    // little slack avoids flickering the caption off right at the edges.
    private val kAtlasTimeSlackMs = 30L * 60L * 1000L

    // Parses one atlas time-range key (e.g. "69-152", "1-11, 61-66", or
    // "153-165, T:0-1") against the year's period table into an overall
    // [startMs, endMs) UTC range. Segments belonging to a non-default named
    // variation (anything with a "name:" prefix, e.g. "T:0-1") are skipped
    // -- they're a different canvas state (see AtlasEntry's doc comment),
    // not a point on the default timeline, so they can't contribute a real
    // timestamp. Returns null if no default-variation segment could be
    // resolved (either the year has no period table, or the key is ENTIRELY
    // non-default-variation segments).
    private fun parseAtlasTimeRange(key: String, periods: LongArray?): Pair<Long, Long>? {
        if (periods == null || periods.isEmpty()) return null
        var minStartSec = Long.MAX_VALUE
        var maxEndSec = Long.MIN_VALUE
        var found = false
        for (rawSeg in key.split(",")) {
            val seg = rawSeg.trim()
            if (seg.isEmpty() || seg.contains(':')) continue // named-variation segment (e.g. "T:0-1") -- skip
            val parts = seg.split("-", limit = 2)
            val a = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val b = parts.getOrNull(1)?.toIntOrNull() ?: a
            if (a !in periods.indices || b !in periods.indices) continue
            val startSec = periods[a]
            // End bound = the NEXT period's timestamp (this center was still
            // "current" up until the next snapshot replaced it), falling
            // back to its own timestamp if it's the last period in the table.
            val endSec = if (b + 1 < periods.size) periods[b + 1] else periods[b]
            minStartSec = minOf(minStartSec, startSec)
            maxEndSec = maxOf(maxEndSec, endSec)
            found = true
        }
        return if (found) Pair(minStartSec * 1000L, maxEndSec * 1000L) else null
    }

    // Loads assets/atlas/$year.periods.json (a flat JSON array of Unix-
    // second timestamps, one per period index -- extracted from placeAtlas's
    // own site source, see AtlasEntry's doc comment) if it exists for this
    // year. 2017 has none bundled (no real per-entry timing to decode), so
    // this returns null for it, same as a load failure.
    private fun loadRplaceAtlasPeriodsForYear(year: String): LongArray? {
        return try {
            val json = assets.open("atlas/$year.periods.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = org.json.JSONArray(json)
            LongArray(arr.length()) { arr.optLong(it) }
        } catch (e: Exception) {
            null
        }
    }

    // Parses assets/atlas/$year.json (bundled, see apk/app/src/main/assets/atlas/)
    // and translates every center point by the year's offset_x/y (see
    // RplaceCanvas::offset_x/y()'s doc comment -- atlas coordinates are in
    // raw, pre-offset event space, but camera_center_x/y from native is in
    // canvas-pixel space) so lookups in renderRplaceAtlasCaptionBitmap can
    // compare them directly with no per-frame translation. Called once when
    // a year finishes loading (see startRplaceYear); cheap even for the
    // ~10,900-entry 2022 file (a few MB of JSON, parsed once).
    private fun loadRplaceAtlasForYear(year: String) {
        rplaceAtlasEntries = emptyList()
        rplaceCanvasWidthPx = 0
        val periods = loadRplaceAtlasPeriodsForYear(year)
        rplaceAtlasPeriods = periods
        try {
            val json = assets.open("atlas/$year.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = org.json.JSONArray(json)
            val offsetX = nativeRplaceGetOffsetX()
            val offsetY = nativeRplaceGetOffsetY()
            val entries = ArrayList<AtlasEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name")
                if (name.isBlank()) continue
                val description = obj.optString("description")
                val centerObj = obj.optJSONObject("center") ?: continue
                val centers = ArrayList<AtlasCenter>(centerObj.length())
                val keys = centerObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val pt = centerObj.optJSONArray(k) ?: continue
                    if (pt.length() < 2) continue
                    val range = parseAtlasTimeRange(k, periods)
                    centers.add(AtlasCenter(
                        pt.optInt(0) + offsetX, pt.optInt(1) + offsetY, k,
                        range?.first ?: 0L, range?.second ?: 0L, range != null
                    ))
                }
                if (centers.isNotEmpty()) entries.add(AtlasEntry(name, description, centers))
            }
            rplaceAtlasEntries = entries
            rplaceCanvasWidthPx = nativeRplaceGetWidth()
            Log.i("RplaceAtlas", "$year: loaded ${entries.size} atlas entries" +
                (if (periods != null) " (${periods.size} periods, time-filtered matching enabled)" else " (no period table -- nearest-only matching)"))
            if (nativeRplaceGetDirectorEnabled()) buildRplaceDirectorReel(year)
        } catch (e: Exception) {
            Log.e("RplaceAtlas", "Failed to load atlas for $year: ${e.message}")
        }
    }

    // Hardcoded per-year real-world epoch (Unix ms, UTC) that virtual_time_ms()
    // == 0 corresponds to -- MUST match questretrodepth_main.cpp's
    // rplace_year_start_epoch_ms(), which is the authoritative source (this
    // is a duplicate purely so buildRplaceDirectorReel() can convert the
    // atlas's absolute epoch-ms time ranges into the virtual-ms domain
    // native's director waypoints live in, without a JNI round-trip).
    private fun rplaceYearStartEpochMs(year: String): Long = when (year) {
        "2017" -> 1490918688000L
        "2022" -> 1648817050315L
        "2023" -> 1689858026088L
        else -> 0L
    }

    // Builds the director's reel straight from the Place Atlas, replacing
    // the old native precompute entirely: randomly pick up to 50 atlas
    // points, sorted by start time, and hand them to native as waypoints --
    // "get there when it starts, leave when the next one's due (or this
    // one's own window closes), cap speed so we don't blow past its own end
    // time" is now RplaceCanvas::update_director_waypoints()'s whole job.
    // Two points sharing the exact same start time would conflict (which one
    // does the reel actually arrive at "when it starts"?), so duplicates are
    // skipped in favor of a different point instead of both being kept.
    private fun buildRplaceDirectorReel(year: String) {
        val entries = rplaceAtlasEntries
        val durationMs = nativeRplaceGetDurationMs()
        if (entries.isEmpty() || durationMs <= 0.0) return
        val hasTime = rplaceAtlasPeriods != null
        // Minimum separation between any two chosen waypoints, as a fraction
        // of canvas width -- random 50-of-N picking with no spatial
        // constraint tends to cluster several picks in whatever region has
        // the densest atlas entries, which reads as the director "getting
        // stuck" revisiting the same area over and over. Reject a candidate
        // that's too close to one already chosen instead.
        val canvasW = nativeRplaceGetWidth().coerceAtLeast(1)
        val canvasH = nativeRplaceGetHeight().coerceAtLeast(1)
        val minSepPx = (Math.min(canvasW, canvasH) * 0.06f).toInt().coerceAtLeast(1)
        fun tooClose(c: AtlasCenter, chosen: List<AtlasCenter>): Boolean {
            for (o in chosen) {
                val dx = (c.x - o.x).toLong(); val dy = (c.y - o.y).toLong()
                if (dx * dx + dy * dy < minSepPx.toLong() * minSepPx.toLong()) return true
            }
            return false
        }
        val xs: IntArray; val ys: IntArray; val starts: DoubleArray; val ends: DoubleArray
        if (hasTime) {
            val yearStart = rplaceYearStartEpochMs(year)
            val pool = ArrayList<AtlasCenter>()
            for (e in entries) for (c in e.centers) if (c.hasTime) pool.add(c)
            pool.shuffle()
            val usedStarts = HashSet<Long>()
            val chosen = ArrayList<AtlasCenter>(50)
            for (c in pool) {
                if (chosen.size >= 50) break
                if (!usedStarts.add(c.startMs)) continue // conflicts with an already-picked point -- skip, try another
                if (tooClose(c, chosen)) continue
                chosen.add(c)
            }
            val sorted = chosen.sortedBy { it.startMs }
            xs = IntArray(sorted.size) { sorted[it].x }
            ys = IntArray(sorted.size) { sorted[it].y }
            starts = DoubleArray(sorted.size) { (sorted[it].startMs - yearStart).toDouble().coerceIn(0.0, durationMs) }
            ends = DoubleArray(sorted.size) { (sorted[it].endMs - yearStart).toDouble().coerceIn(0.0, durationMs) }
        } else {
            // 2017: atlas.json has no real per-entry timing at all (every
            // entry shares one dummy key across the whole file -- see
            // AtlasCenter's doc comment), so there's no real start/end to
            // sort by. Depending on the year: synthesize evenly-spaced
            // waypoints across the whole run instead, so the director still
            // has real atlas points to travel between (spatially real, just
            // not time-anchored to anything the atlas actually claims).
            val pool = ArrayList<AtlasCenter>()
            for (e in entries) for (c in e.centers) pool.add(c)
            pool.shuffle()
            val chosen = ArrayList<AtlasCenter>(50)
            for (c in pool) {
                if (chosen.size >= 50) break
                if (tooClose(c, chosen)) continue
                chosen.add(c)
            }
            val slice = durationMs / chosen.size.coerceAtLeast(1)
            xs = IntArray(chosen.size) { chosen[it].x }
            ys = IntArray(chosen.size) { chosen[it].y }
            starts = DoubleArray(chosen.size) { it * slice }
            ends = DoubleArray(chosen.size) { (it + 1) * slice }
        }
        if (xs.isEmpty()) return
        nativeRplaceSetDirectorWaypoints(xs, ys, starts, ends)
        Log.i("RplaceDirector", "$year: built reel of ${xs.size} atlas waypoints" +
            (if (hasTime) "" else " (synthetic timing -- no real atlas timing for this year)"))
    }

    // ---- auto-director toggle (see docs/FORMAT_AND_DIRECTOR.md §6 for the
    // eventual full dial system -- this is the v1 on/off-only MVP) ----
    fun rplaceDirectorLabel(): String {
        return if (nativeRplaceGetDirectorEnabled()) "Auto Director: On" else "Auto Director: Off"
    }

    fun rplaceToggleDirector() {
        val enabling = !nativeRplaceGetDirectorEnabled()
        nativeRplaceSetDirectorEnabled(enabling)
        // Cover "turn director on while already watching a year": build the
        // reel right now instead of waiting for the next year switch (which
        // is the only other place buildRplaceDirectorReel() gets called,
        // see loadRplaceAtlasForYear()).
        val year = rplaceActiveYear
        if (enabling && year != null && rplaceAtlasEntries.isNotEmpty()) buildRplaceDirectorReel(year)
    }

    // Combined menu-row label for a year: loading % (native decode+sort in
    // progress) takes priority over downloading % (network fetch in
    // progress), then deleting, then downloaded/not-downloaded. Called from
    // native's rebuild_main_menu_texture() once per year per rebuild.
    fun rplaceMenuLabel(year: String): String {
        val base = "r/place $year"
        if (rplaceLoadingYear == year && !nativeRplaceIsReady()) {
            return "$base (loading ${(nativeRplaceLoadProgress()).coerceIn(0, 100)}%)"
        }
        if (rplaceDecompressingYear == year) {
            return "$base (preprocessing ${(rplaceDecompressProgress * 100).toInt().coerceIn(0, 100)}%)"
        }
        if (rplaceDeletingYear == year) {
            return "$base (deleting)"
        }
        // Data always ships in the APK -- "not downloaded" no longer exists,
        // just "not yet expanded into this device's playable form".
        return if (isRplaceYearDownloaded(year)) "$base (ready)" else "$base (tap to prepare)"
    }

    // Label for the "Delete downloaded files" menu row itself.
    fun rplaceDeleteAllLabel(): String {
        if (rplaceDeleteAllInProgress) return "Deleting downloaded files..."
        return "Delete downloaded files"
    }

    // Whether the main menu should keep refreshing itself every frame right
    // now (see openxr_shell.cpp's periodic re-dirty) so a live percentage is
    // actually visible instead of only updating on the next unrelated redraw.
    fun rplaceMenuActivityInProgress(): Boolean =
        rplaceDecompressingYear != null ||
            (rplaceLoadingYear != null && !nativeRplaceIsReady()) || rplaceDeleteAllInProgress

    // Mirrors getRomDirectory()'s dual-tier strategy: shared storage first (survives
    // app reinstall/uninstall -- important since SideQuest testing means frequent
    // reinstalls, see plan's storage-location discussion), app-private fallback.
    fun getRplaceDataDirectory(): String {
        val dir = File(Environment.getExternalStorageDirectory(), "rplaceRT/data")
        if (dir.exists() || dir.mkdirs()) return dir.absolutePath
        return File(getExternalFilesDir(null), "data").apply { mkdirs() }.absolutePath
    }

    private fun rplaceDecodedFile(year: String): File =
        File(getRplaceDataDirectory(), "$year.bin")

    // "Ready" is derived from the file's own existence/size, not a separate
    // tracked flag -- a truncated/interrupted conversion just reads as "not
    // ready" (below the header size any real converted file clears by
    // orders of magnitude) and gets cleanly reconverted, rather than needing
    // separate stage bookkeeping that could itself go stale. Every file this
    // produces is already in RplaceCanvas::kStreamMagic format (see
    // convertRplaceYearFromAssets), so there's no separate "needs
    // conversion" state to track here anymore.
    private fun isValidRplaceFile(f: File): Boolean = f.exists() && f.length() > 4096

    fun isRplaceYearDownloaded(year: String): Boolean = isValidRplaceFile(rplaceDecodedFile(year))

    // The 3 years r/place ever ran, bundled directly in the APK at
    // assets/rplace/{year}.bin.br (v8 columnar format, brotli q10 -- see
    // docs/FORMAT_AND_DIRECTOR.md). Hardcoded, not fetched: this is a closed,
    // finite set, unlike the homebrew ROM catalog which genuinely grows over
    // time and needs the GitHub-contents-API fetch (see refreshHomebrewFeeds).
    private val RPLACE_YEARS: List<String> = listOf("2017", "2022", "2023")

    // Streams the bundled brotli-compressed asset for `year` through
    // decompression and native conversion into the year's playable .bin
    // (RplaceCanvas::kStreamMagic format), with no full decompressed
    // intermediate file ever written to disk -- decompressed chunks go
    // straight from BrotliInputStream into native's incremental parser via
    // nativeRplaceFeedBytes. Runs once per year per device install; the
    // resulting .bin is cached in getRplaceDataDirectory() and reused on
    // every subsequent launch (see isRplaceYearDownloaded's file-exists check).
    fun convertRplaceYearFromAssets(year: String, onProgress: ((Float) -> Unit)? = null, onComplete: ((Boolean) -> Unit)? = null) {
        if (!hasStoragePermission()) {
            requestStoragePermission()
            onComplete?.invoke(false)
            return
        }
        rplaceDecompressingYear = year
        rplaceDecompressProgress = 0f
        Thread {
            val dataDir = File(getRplaceDataDirectory())
            dataDir.mkdirs()
            val tmpOut = File(dataDir, "$year.bin.converting")
            val finalBin = rplaceDecodedFile(year)

            var success = false
            try {
                val assetPath = "rplace/$year.bin.br"
                val compressedTotal = assets.openFd(assetPath).use { it.length }.coerceAtLeast(1L)
                var consumed = 0L
                val countingIn = object : java.io.FilterInputStream(assets.open(assetPath)) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            consumed += n
                            rplaceDecompressProgress = (consumed.toFloat() / compressedTotal.toFloat()).coerceIn(0f, 1f)
                            runOnUiThread { onProgress?.invoke(rplaceDecompressProgress) }
                        }
                        return n
                    }
                }
                if (nativeRplaceBeginConvert(tmpOut.absolutePath)) {
                    org.brotli.dec.BrotliInputStream(countingIn).use { br ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = br.read(buf)
                            if (n < 0) break
                            nativeRplaceFeedBytes(buf, n)
                        }
                    }
                    if (nativeRplaceEndConvert()) {
                        finalBin.delete()
                        success = tmpOut.renameTo(finalBin)
                    }
                }
            } catch (e: Exception) {
                Log.e("RplaceData", "Conversion failed for $year: ${e.message}")
            } finally {
                tmpOut.delete()
            }
            rplaceDecompressingYear = null
            runOnUiThread { onComplete?.invoke(success) }
        }.start()
    }

    fun deleteRplaceYear(year: String) {
        rplaceDecodedFile(year).delete()
        if (rplaceActiveYear == year) {
            nativeRplaceStop()
            rplaceActiveYear = null
        }
    }

    // Called from native's main menu "Delete downloaded files" row. Walks all
    // known years, deleting whichever have been converted, stepping the
    // label through ready -> deleting -> tap to prepare for each. Runs on a
    // background thread since it stops active playback (nativeRplaceStop)
    // and does file I/O; rplaceMenuActivityInProgress() keeps the menu
    // redrawing every ~100ms while rplaceDeleteAllInProgress is set so the
    // per-row label transitions are actually visible on screen.
    fun deleteAllRplaceYears() {
        if (rplaceDeleteAllInProgress) return
        rplaceDeleteAllInProgress = true
        Thread {
            for (year in RPLACE_YEARS) {
                if (!isRplaceYearDownloaded(year)) continue
                rplaceDeletingYear = year
                Thread.sleep(200) // let the "(deleting)" label actually be seen
                deleteRplaceYear(year)
                rplaceDeletingYear = null
            }
            rplaceDeleteAllInProgress = false
        }.start()
    }

    // Starts native playback of an already-downloaded year. Safe to call again
    // with a different year -- native side replaces the frame provider.
    fun startRplaceYear(year: String) {
        val f = rplaceDecodedFile(year)
        if (!f.exists()) return
        rplaceActiveYear = year
        rplaceLoadingYear = year
        nativeRplaceStart(f.absolutePath)
        // Starts at real-time (1x, native's default) -- the right/left
        // triggers speed it up/down from there (see rplace_input_handler).

        // nativeRplaceStart() kicks off native's decode+sort on a background
        // thread (see questretrodepth_main.cpp's nativeRplaceStart) -- the
        // canvas's offset_x/y/width aren't valid (native's RplaceCanvas isn't
        // "ready") until that finishes, so the atlas load (which needs them
        // to translate coordinates, see loadRplaceAtlasForYear) waits for it
        // here rather than racing it.
        Thread {
            var waited = 0
            while (!nativeRplaceIsReady() && waited < 120_000) {
                Thread.sleep(200)
                waited += 200
            }
            if (nativeRplaceIsReady() && rplaceActiveYear == year) {
                loadRplaceAtlasForYear(year)
            }
        }.start()
    }

    // Temporary MVP entry point until the real year-picker UI exists (see
    // startRplaceYear callers / plan point 1+2). Converts 2017 if needed
    // (it's the smallest bundled asset, ~41MB, for a fast test loop) and
    // starts playback.
    private fun autoStartRplaceForTesting() {
        if (isRplaceYearDownloaded("2017")) {
            startRplaceYear("2017")
            return
        }
        convertRplaceYearFromAssets("2017",
            onProgress = { p -> Log.i("RplaceData", "2017 convert: ${(p * 100).toInt()}%") },
            onComplete = { ok ->
                if (ok) startRplaceYear("2017")
                else Log.e("RplaceData", "2017 conversion failed")
            })
    }

    // Called from the native main menu (see openxr_shell.cpp rows 6/7/8) when
    // the user clicks a year directly. Handles all states in one click: needs
    // permission -> request it and stop (user must click the year again once
    // granted, same as the pre-existing storage-permission flow used at
    // startup); not yet converted -> stream-convert the bundled asset with
    // progress logged, then start; already converted -> start immediately.
    fun rplaceMenuSelect(year: String) {
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }
        if (isRplaceYearDownloaded(year)) {
            startRplaceYear(year)
            return
        }
        if (year !in RPLACE_YEARS) {
            Log.e("RplaceData", "unknown rplace year: $year")
            return
        }
        convertRplaceYearFromAssets(year,
            onProgress = { p -> Log.i("RplaceData", "$year convert: ${(p * 100).toInt()}%") },
            onComplete = { ok ->
                if (ok) startRplaceYear(year)
                else Log.e("RplaceData", "$year conversion failed")
            })
    }

    fun homebrewOpenWebsite(entryIdx: Int) {
        val list = hwEntries
        if (entryIdx < 0 || entryIdx >= list.size) return
        val url = list[entryIdx].website.ifBlank { list[entryIdx].sourceEntryUrl }
        if (url.isNotBlank()) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    fun renderHomebrewListBitmap(
        hovered: Int, scroll: Int, feedIdx: Int, loading: Boolean, width: Int, height: Int
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.argb(240, 12, 12, 22)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val titleH = 88f
        paint.color = android.graphics.Color.argb(255, 20, 50, 90)
        canvas.drawRect(0f, 0f, width.toFloat(), titleH, paint)

        val feedName = hwFeedName(feedIdx)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 38f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textAlign = android.graphics.Paint.Align.CENTER
        canvas.drawText("◀  $feedName  ▶", width / 2f, titleH - 22f, paint)

        val entries = hwEntries
        val totalRows = entries.size + 2
        val rowH = ((height - titleH) / totalRows).coerceIn(44f, 80f)

        if (loading) {
            paint.color = android.graphics.Color.argb(200, 0, 200, 255)
            paint.textSize = 34f
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText("Loading…", width / 2f, titleH + rowH + rowH / 2f, paint)
        } else {
            for (i in 1 until totalRows - 1) {
                val entryIdx = scroll + i - 1
                if (entryIdx < 0 || entryIdx >= entries.size) continue
                val entry = entries[entryIdx]
                val y0 = titleH + i * rowH
                val downloaded = hwDownloaded.contains(entry.download)

                if (i - 1 == hovered - 1) {
                    paint.color = android.graphics.Color.argb(80, 0, 180, 255)
                    canvas.drawRect(0f, y0, width.toFloat(), y0 + rowH, paint)
                }

                val sysTag = "[${entry.system.uppercase().take(4)}]"
                paint.textSize = 28f
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                paint.textAlign = android.graphics.Paint.Align.LEFT
                paint.color = android.graphics.Color.argb(200, 100, 200, 255)
                canvas.drawText(sysTag, 16f, y0 + rowH * 0.62f, paint)

                paint.color = android.graphics.Color.WHITE
                paint.textSize = 30f
                canvas.drawText(entry.name, 100f, y0 + rowH * 0.62f, paint)

                val indicator = if (downloaded) "✓" else "↓"
                paint.color = if (downloaded) android.graphics.Color.argb(255, 80, 220, 80)
                              else android.graphics.Color.argb(200, 180, 180, 180)
                paint.textAlign = android.graphics.Paint.Align.RIGHT
                canvas.drawText(indicator, width - 16f, y0 + rowH * 0.62f, paint)
            }
        }

        // Feed toggle row (row 0)
        val y0feed = titleH
        if (hovered == 0) {
            paint.color = android.graphics.Color.argb(80, 0, 180, 255)
            canvas.drawRect(0f, y0feed, width.toFloat(), y0feed + rowH, paint)
        }
        paint.color = android.graphics.Color.argb(220, 160, 220, 255)
        paint.textSize = 28f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText("Select Feed", width / 2f, y0feed + rowH * 0.65f, paint)

        // Back row (last row)
        val yBack = titleH + (totalRows - 1) * rowH
        if (hovered == totalRows - 1) {
            paint.color = android.graphics.Color.argb(80, 0, 180, 255)
            canvas.drawRect(0f, yBack, width.toFloat(), yBack + rowH, paint)
        }
        paint.color = android.graphics.Color.argb(220, 160, 160, 255)
        paint.textSize = 28f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        canvas.drawText("← Back", width / 2f, yBack + rowH * 0.65f, paint)

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    fun renderHomebrewDetailBitmap(
        entryIdx: Int, isDownloading: Boolean, width: Int, height: Int
    ): IntArray {
        val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        paint.color = android.graphics.Color.argb(240, 12, 12, 22)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val entries = hwEntries
        val entry = entries.getOrNull(entryIdx)

        val titleH = 88f
        paint.color = android.graphics.Color.argb(255, 20, 50, 90)
        canvas.drawRect(0f, 0f, width.toFloat(), titleH, paint)

        paint.color = android.graphics.Color.WHITE
        paint.textSize = 36f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textAlign = android.graphics.Paint.Align.CENTER
        canvas.drawText(entry?.name ?: "—", width / 2f, titleH - 22f, paint)

        val rowH = (height - titleH) / 4f
        val rows = listOf("← Back", "", "Open Website", "")

        if (entry != null) {
            // Info block
            val infoY = titleH + rowH * 0.15f
            paint.textAlign = android.graphics.Paint.Align.LEFT
            paint.textSize = 26f
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.color = android.graphics.Color.argb(200, 160, 200, 255)
            canvas.drawText("Author: ${entry.author}", 24f, infoY + 30f, paint)
            canvas.drawText("License: ${entry.license}", 24f, infoY + 64f, paint)
            canvas.drawText("System: ${entry.system.uppercase()}", 24f, infoY + 98f, paint)
            val mode = if (entry.distributionMode.equals("mirror", ignoreCase = true)) "Mirror"
                       else "Official"
            canvas.drawText("Source: ${entry.source.ifBlank { "Manual" }}", 24f, infoY + 132f, paint)
            canvas.drawText("Delivery: $mode", 24f, infoY + 166f, paint)
            paint.textSize = 20f
            paint.color = android.graphics.Color.argb(180, 120, 160, 220)
            val website = entry.website.ifBlank { entry.sourceEntryUrl }.take(52)
            canvas.drawText(website, 24f, infoY + 198f, paint)

            // Download/Delete button (row 1)
            val downloaded = hwDownloaded.contains(entry.download)
            val btnY1 = titleH + rowH
            val btnLabel = when {
                isDownloading -> "Downloading…"
                downloaded    -> "Delete"
                else          -> "Download"
            }
            val btnColor = when {
                isDownloading -> android.graphics.Color.argb(200, 80, 80, 80)
                downloaded    -> android.graphics.Color.argb(220, 180, 40, 40)
                else          -> android.graphics.Color.argb(220, 0, 160, 200)
            }
            paint.color = btnColor
            canvas.drawRoundRect(
                android.graphics.RectF(24f, btnY1 + 8f, width - 24f, btnY1 + rowH - 8f),
                16f, 16f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 32f
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText(btnLabel, width / 2f, btnY1 + rowH * 0.62f, paint)

            // Open Website button (row 2)
            val btnY2 = titleH + rowH * 2f
            paint.color = android.graphics.Color.argb(220, 30, 80, 160)
            canvas.drawRoundRect(
                android.graphics.RectF(24f, btnY2 + 8f, width - 24f, btnY2 + rowH - 8f),
                16f, 16f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 30f
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText("Open Website", width / 2f, btnY2 + rowH * 0.62f, paint)
        }

        // Back button (row 3)
        val btnY3 = titleH + rowH * 3f
        paint.color = android.graphics.Color.argb(180, 40, 40, 70)
        canvas.drawRoundRect(
            android.graphics.RectF(24f, btnY3 + 8f, width - 24f, btnY3 + rowH - 8f),
            16f, 16f, paint)
        paint.color = android.graphics.Color.argb(220, 160, 160, 255)
        paint.textSize = 30f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textAlign = android.graphics.Paint.Align.CENTER
        canvas.drawText("← Back", width / 2f, btnY3 + rowH * 0.62f, paint)

        val pixels = IntArray(width * height)
        bmp.getPixels(pixels, 0, width, 0, 0, width, height)
        bmp.recycle()
        return pixels
    }

    companion object {
        private const val SAVE_AUTOMATION_FILE_NAME = "save_automation.ini"
        private const val PREF_HOME_BREW_FEED_INDEX = "homebrew_feed_index"
        private const val PREF_HOME_BREW_FEED_FILE_NAME = "homebrew_feed_file_name"
        private const val PREF_HOME_BREW_ONBOARDING_DONE = "homebrew_onboarding_done"
        private const val HOME_BREW_GITHUB_API_URL =
            "https://api.github.com/repos/maranone/QuestRetroDepth/contents/homebrew?ref=main"
        private val VALID_AUTOSAVE_INTERVALS = setOf(0, 5, 30, 60, 300)
        init { System.loadLibrary("questretrodepth_native") }
    }

    private data class SaveAutomationPrefs(
        val autosaveIntervalSeconds: Int = 30,
        val loadLastSaveEnabled: Boolean = true
    )

    private enum class RomFamily {
        Snes, Genesis, Nes, Gb, Gba, Gg, Pce, Sega32x, Atari2600, Ds, Saturn, Dreamcast
    }
}
