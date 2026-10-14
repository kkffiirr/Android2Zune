package io.github.kkffiirr.zuneorganizer

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

private const val ACTION_PERM = "io.github.kkffiirr.zuneorganizer.USB_PERMISSION"
private const val ZUNE_VENDOR = 0x045E
private const val INK = 0xFF222222.toInt()
private const val DIM = 0xFF9A9A9A.toInt()
private const val LINE = 0xFFD6D6D6.toInt()
private const val PINK = 0xFFEC008C.toInt()
private const val ORANGE = 0xFFF7611F.toInt()
private const val BUSY_TEXT = "talking to zune…"
private const val SETTLE_MS = 30_000L    // pause before reconnecting after an edit
private const val RETRY_WAIT_MS = 15_000L
private const val CONNECT_TRIES = 6
private const val STALL_MS = 60_000L   // give up if the Zune says nothing for this long (native bulk transfers time out at 20 s)
// The Zune stores "no artist" as this GUID (seen on every untagged song). Shown as text here, converted back when writing.
private const val UNKNOWN_ARTIST_GUID = "{09316835-3188-481c-a359-0f8d2a937ac8}"
private const val UNKNOWN_ARTIST = "Unknown artist"
private fun shownArtist(s: String) = if (s.equals(UNKNOWN_ARTIST_GUID, ignoreCase = true)) UNKNOWN_ARTIST else s
private fun rawArtist(s: String) = if (s.equals(UNKNOWN_ARTIST, ignoreCase = true)) UNKNOWN_ARTIST_GUID else s

private class Track(val id: Long, val title: String, val artist: String, val album: String, val genre: String,
                    val no: Int, val disc: Int, val dur: Int, val year: Int, val plays: Int, val albumObj: Long)

private class Alb(val obj: Long, val title: String, val artist: String, val year: Int)

private enum class Tab { ARTISTS, ALBUMS, GENRES, SONGS, LOG }

private const val MAX_ROWS = 300   // views are built per row; refine the search to see the rest

class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var mergeBar: Button
    private val tabs = mutableMapOf<Tab, TextView>()
    private var tab = Tab.ARTISTS
    private var open: String? = null           // group currently drilled into
    private val sel = mutableSetOf<String>()   // selected group names (of the current tab) for rename/merge
    private val selSongs = mutableSetOf<Long>() // selected song ids: SONGS tab and inside an opened group
    private lateinit var songsBtn: Button
    private lateinit var autoBtn: Button
    private var albums = listOf<Alb>()
    private var isHd = false
    private var filter = ""                    // lower-cased search text; every word must match title/artist/album/genre
    private lateinit var filterBox: EditText
    private lateinit var barRow: LinearLayout
    private lateinit var allBtn: Button
    private lateinit var noneBtn: Button
    private var tracks = listOf<Track>()
    private var deviceName = ""
    private var busy = false
    private var pending: ((Int) -> String)? = null
    private var pendingWrites = false
    @Volatile private var lastWriteAt = -SETTLE_MS             // when the last edit finished; the next connection waits SETTLE_MS after it
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastProgress = 0L          // last time the native side said anything
    private var runId = 0
    private val staleRuns = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()   // runs given up on (watchdog/cancel); their late results are ignored
    private var activeConn: android.hardware.usb.UsbDeviceConnection? = null
    private var activeWrites = false
    private var runStart = 0L
    private var statusBase = ""
    private var finalStatus: String? = null   // what the status line should read once the run ends (the ticker would overwrite it)
    private lateinit var bigRead: Button
    private lateinit var refreshBtn: Button
    private lateinit var cancelBtn: Button
    private val threadRun = ThreadLocal<Int>()

    private val keys get() = File(filesDir, "mtpz-data")

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                                  else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) runOnDevice(dev) else log("USB permission denied")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun light(sp: Float, color: Int) = TextView(this).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textSize = sp; setTextColor(color)
    }
    private fun heading(bold: String, tag: String) = TextView(this).apply {
        textSize = 13f; letterSpacing = 0.04f; setPadding(0, dp(10), 0, dp(10))
        val sb = SpannableStringBuilder(bold.uppercase())
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(INK), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val s = sb.length; sb.append("  ").append(tag.uppercase())
        sb.setSpan(ForegroundColorSpan(DIM), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = sb
    }
    private fun hairline() = View(this).apply { setBackgroundColor(LINE); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.WHITE; window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(20), dp(6)) }
        head.addView(light(46f, INK).apply { text = "organize"; includeFontPadding = false })
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        Tab.values().forEach { v ->
            tabs[v] = TextView(this).apply {
                textSize = 13f; letterSpacing = 0.05f; setPadding(0, 0, dp(18), 0)
                setOnClickListener { tab = v; open = null; sel.clear(); selSongs.clear(); render() }
            }.also { tabRow.addView(it) }
        }
        head.addView(tabRow); root.addView(head)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(20), dp(16)) }
        scroll = ScrollView(this).apply { addView(body) }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(0xFF555555.toInt())
            setTextIsSelectable(true); setPadding(dp(24), dp(12), dp(20), dp(24))
        }
        logScroll = ScrollView(this).apply { addView(logView); visibility = View.GONE }
        // pinned above the list so the search box and selection buttons stay visible while scrolling
        filterBox = EditText(this).apply {
            hint = "search"; setSingleLine(); textSize = 15f; visibility = View.GONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { filter = s?.toString().orEmpty().trim().lowercase(); render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        fun barBtn(color: Int, always: Boolean = false, onClick: () -> Unit) = Button(this).apply {
            textSize = 12f; setTextColor(color); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), color) }
            stateListAnimator = null; minHeight = dp(40); minimumHeight = dp(40); setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { if (always || !busy) onClick() }
        }
        allBtn = barBtn(INK) { selectAllShown() }.apply { text = "ALL" }
        noneBtn = barBtn(INK) { clearSelection() }.apply { text = "NONE" }
        mergeBar = barBtn(PINK) { actOnSelection() }
        songsBtn = barBtn(PINK) { editTracks(tracks.filter { keyOf(it) in sel }) }.apply { text = "EDIT SONGS" }
        autoBtn = barBtn(INK) { autoFill() }.apply { text = "AUTO-FILL" }
        barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE
            addView(allBtn); addView(noneBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6) })
            addView(mergeBar, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
            addView(songsBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6) })
            addView(autoBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6) })
        }
        root.addView(filterBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(24), 0, dp(20), 0) })
        root.addView(barRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(24), dp(2), dp(20), dp(6)) })
        root.addView(FrameLayout(this).apply { addView(scroll); addView(logScroll) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Bottom: status text with a small REFRESH (or CANCEL while talking to the Zune). The big READ button exists only
        // until the first library is loaded, so it cannot be hit by accident later.
        status = light(14f, DIM).apply { maxLines = 2; setPadding(0, 0, dp(8), 0) }
        refreshBtn = barBtn(INK) { readLibrary() }.apply { text = "REFRESH"; visibility = View.GONE }
        cancelBtn = barBtn(PINK, always = true) { cancelRun() }.apply { text = "CANCEL"; visibility = View.GONE }
        root.addView(hairline())
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(dp(24), dp(8), dp(20), dp(8))
            addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(refreshBtn); addView(cancelBtn)
        })
        bigRead = Button(this).apply {
            text = "READ ZUNE LIBRARY"; textSize = 14f; letterSpacing = 0.1f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(PINK, ORANGE))
            stateListAnimator = null; minHeight = dp(52); minimumHeight = dp(52)
            setOnClickListener { if (!busy) readLibrary() }
        }
        root.addView(bigRead, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(24), dp(2), dp(20), dp(14)) })
        setContentView(root)

        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permReceiver, f, RECEIVER_NOT_EXPORTED) else registerReceiver(permReceiver, f)
        runCatching { assets.open("mtpz-data").use { i -> keys.outputStream().use { i.copyTo(it) } } }
        log(if (keys.exists()) "MTPZ key present." else "No MTPZ key bundled: edits will fail.")
        render()
    }

    override fun onDestroy() { unregisterReceiver(permReceiver); super.onDestroy() }

    @Deprecated("fine for a single-screen tool")
    override fun onBackPressed() { if (open != null) { open = null; selSongs.clear(); render() } else super.onBackPressed() }

    private fun log(s: String) { android.util.Log.i("ZuneOrganizer", s); logView.append(s + "\n"); logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) } }

    // ---------- device access ----------

    /** Runs [action] on a worker thread with an open USB fd to the Zune, asking for permission first if needed. */
    private fun withZune(writes: Boolean = false, action: (Int) -> String) {
        val um = getSystemService(USB_SERVICE) as UsbManager
        val dev = um.deviceList.values.firstOrNull { it.vendorId == ZUNE_VENDOR }
            ?: return log("No Zune found. Devices: ${um.deviceList.values.map { "%04x:%04x".format(it.vendorId, it.productId) }}")
        pending = action; pendingWrites = writes
        if (um.hasPermission(dev)) runOnDevice(dev)
        else um.requestPermission(dev, PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERM).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
    }

    /**
     * Every hang so far came on the connection right after an edit session: the Zune ignores a new MTP session for a while.
     * So: let it settle after a write, and if connecting fails (nothing was written yet) wait and retry with a fresh handle.
     */
    private fun runOnDevice(dev: UsbDevice) {
        val action = pending ?: return; pending = null
        val writes = pendingWrites
        val um = getSystemService(USB_SERVICE) as UsbManager
        var conn = um.openDevice(dev) ?: return log("openDevice failed")
        val myRun = ++runId
        busy = true; activeWrites = writes; activeConn = conn
        lastProgress = SystemClock.elapsedRealtime(); runStart = lastProgress
        statusBase = BUSY_TEXT; finalStatus = null; status.text = BUSY_TEXT; syncButtons()
        // Ticker + watchdog: shows how long this has been going, and if the Zune says nothing for STALL_MS the app gives up waiting.
        val watch = object : Runnable {
            override fun run() {
                if (!busy || runId != myRun || myRun in staleRuns) return
                if (SystemClock.elapsedRealtime() - lastProgress > STALL_MS) {
                    abandon(myRun, "timed out - unplug and replug the Zune, then try again")
                } else {
                    status.text = "$statusBase  ${(SystemClock.elapsedRealtime() - runStart) / 1000} s"
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(watch, 1000)
        fun note(msg: String) { lastProgress = SystemClock.elapsedRealtime(); runOnUiThread { statusBase = msg; log(msg) } }
        /** Sleeps in short steps so Cancel takes effect quickly. Returns false if this run was cancelled meanwhile. */
        fun pause(ms: Long): Boolean {
            var left = ms
            while (left > 0 && myRun !in staleRuns) { Thread.sleep(minOf(300L, left)); left -= 300; lastProgress = SystemClock.elapsedRealtime() }
            return myRun !in staleRuns
        }
        Thread {
            threadRun.set(myRun)
            try {
                // After an artist/album edit the Zune stops answering for a while (it is probably updating its library),
                // so wait before the first attempt, then keep trying patiently; the user can cancel at any time.
                val sinceWrite = SystemClock.elapsedRealtime() - lastWriteAt
                if (sinceWrite < SETTLE_MS) {
                    note("letting the Zune settle after the last edit…")
                    if (!pause(SETTLE_MS - sinceWrite)) return@Thread
                }
                var res = ""
                for (attempt in 1..CONNECT_TRIES) {
                    if (myRun in staleRuns) return@Thread
                    lastProgress = SystemClock.elapsedRealtime()
                    res = action(conn.fileDescriptor)
                    // "ERR connect"/"ERR no MTP session" mean nothing was sent yet, so retrying is safe even for edits
                    if (!res.startsWith("ERR connect") && !res.startsWith("ERR no MTP")) break
                    runCatching { conn.close() }
                    if (attempt == CONNECT_TRIES) { res = "ERR the Zune did not answer after $CONNECT_TRIES tries - it may still be updating; wait a few minutes or unplug and replug it"; break }
                    note("the Zune is busy (probably updating its library) - waiting to retry, try ${attempt + 1} of $CONNECT_TRIES…")
                    if (!pause(RETRY_WAIT_MS)) return@Thread
                    val again = um.deviceList.values.firstOrNull { it.vendorId == ZUNE_VENDOR }
                    if (again == null || !um.hasPermission(again)) { res = "ERR the Zune was unplugged or needs permission again"; break }
                    conn = um.openDevice(again) ?: run { res = "ERR openDevice failed"; null } ?: break
                    activeConn = conn
                }
                if (writes && res.startsWith("OK")) lastWriteAt = SystemClock.elapsedRealtime()
                applyListing(res)
            } catch (e: Throwable) { runOnUiThread { log("error: $e") } }
            runCatching { conn.close() }
            runOnUiThread { if (myRun !in staleRuns) { busy = false; status.text = finalStatus ?: ""; syncButtons() } }
        }.start()
    }

    /** Stop waiting for the current run. A run that already sent an edit is not cut off mid-write; only the waiting stops. */
    private fun abandon(run: Int, message: String) {
        staleRuns += run; busy = false
        if (!activeWrites) runCatching { activeConn?.close() }
        status.text = message; log(message); syncButtons(); render()
    }

    private fun cancelRun() {
        if (busy) abandon(runId, if (activeWrites) "stopped waiting - an edit that was already sent may still be finishing on the Zune"
                                 else "cancelled")
    }

    private fun syncButtons() {
        bigRead.visibility = if (tracks.isEmpty() && !busy) View.VISIBLE else View.GONE
        refreshBtn.visibility = if (tracks.isNotEmpty() && !busy) View.VISIBLE else View.GONE
        cancelBtn.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun call(fd: Int, cmd: String, arg: String = ""): String =
        Native.run(fd, if (keys.exists()) keys.path else "", cmd, arg) { l -> lastProgress = SystemClock.elapsedRealtime(); runOnUiThread { log(l) } }

    private fun readLibrary() = withZune { fd -> call(fd, "list") }

    private fun applyListing(res: String) {
        val run = threadRun.get()
        runOnUiThread { if (run == null || run !in staleRuns) showListing(res) else log("Ignored a late answer from a run that already timed out.") }
    }

    private fun showListing(res: String) {
        val lines = res.lines()
        if (lines.firstOrNull() != "OK") { finalStatus = "failed"; status.text = "failed"; return log(res.take(300)) }
        runCatching { File(filesDir, "library.tsv").writeText(res) }   // raw listing, for diagnosing odd values (adb run-as)
        val out = mutableListOf<Track>(); val albs = mutableListOf<Alb>()
        var summary = ""
        for (l in lines.drop(1)) {
            val f = l.split('\t')
            if (f[0] == "R") summary = f.getOrElse(1) { "" }
            else if (f[0] == "D") { deviceName = f.getOrElse(1) { "" }; isHd = f.getOrElse(2) { "0" } == "1" }
            else if (f[0] == "T" && f.size >= 12) out += Track(f[1].toLong(), f[2], shownArtist(f[3]), f[4], f[5],
                f[6].toIntOrNull() ?: 0, f[7].toIntOrNull() ?: 1, f[8].toIntOrNull() ?: 0, f[9].toIntOrNull() ?: 0, f[10].toIntOrNull() ?: 0, f[11].toLongOrNull() ?: 0)
            else if (f[0] == "A" && f.size >= 5) albs += Alb(f[1].toLongOrNull() ?: 0, f[2], shownArtist(f[3]), f[4].toIntOrNull() ?: 0)
        }
        // If the opened group no longer exists (e.g. every song of artist Henry was renamed Tommy), follow its songs to the
        // group they ended up in, or back to the list when they were split up.
        val opened = open
        val groupSongs = if (opened != null) tracks.filter { keyOf(it) == opened }.map { it.id }.toSet() else emptySet()
        tracks = out; albums = albs; sel.clear(); selSongs.clear()
        if (opened != null && out.none { keyOf(it) == opened }) {
            val now = out.filter { it.id in groupSongs }.map { keyOf(it) }.distinct()
            open = now.singleOrNull()
            log(if (open != null) "'${opened.ifBlank { "(none)" }}' has no songs left - showing '${open}'" else "'${opened.ifBlank { "(none)" }}' has no songs left - back to the list")
        }
        finalStatus = if (summary.isEmpty()) "$deviceName: ${out.size} songs" else summary
        status.text = finalStatus
        log(if (summary.isEmpty()) "Read ${out.size} tracks" else "$summary - re-read ${out.size} tracks"); render()
    }

    // ---------- editing ----------

    private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]"), " ").trim()
    private fun tsv(vararg x: Any) = x.joinToString("\t")

    /**
     * One USB session: write track lines, album lines, then re-read the library so the screen shows what the Zune reports.
     * Line fields are "=" (leave alone) or "+value"; only "+" fields are ever written to the Zune.
     * Track line: id, title, artist, genre, track#, year.  Album line: album object id, name, album artist.
     */
    private fun applyEdits(trackLines: String, albumLines: String, linkLines: String = "") {
        log("Applying ${trackLines.lines().count { it.isNotBlank() }} track edits, ${albumLines.lines().count { it.isNotBlank() }} album edits, " +
            "${linkLines.lines().count { it.isNotBlank() }} albums to fill with songs")
        sel.clear(); selSongs.clear()
        withZune(writes = true) { fd -> call(fd, "apply", "TRACKS\n$trackLines\nALBUMS\n$albumLines\nLINKS\n$linkLines") }
    }

    /**
     * Tag editor for one or many songs. A field is written only if its text differs from what the dialog showed:
     * a blank "mixed" field stays untouched, a typed value is applied to every selected song.
     * Title / track # are per-song, so they appear only for a single song.
     * Album and album artist live on the album object and so apply to the whole album the song(s) belong to.
     */
    private fun editTracks(ts: List<Track>) {
        if (ts.isEmpty()) return
        val one = ts.size == 1
        val albs = ts.map { it.albumObj }.filter { it != 0L }.distinct().mapNotNull { id -> albums.firstOrNull { it.obj == id } }
        val orphans = ts.filter { it.albumObj == 0L }   // songs with no album object on the Zune: an album can be created for them
        val canAlbum = !isHd && (albs.isNotEmpty() || orphans.isNotEmpty())
        val canAlbumArtist = !isHd && albs.isNotEmpty()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val initial = mutableMapOf<EditText, String>()
        // MediaMonkey style: each field is a text box plus a drop-down of the values already in the selection
        // (with counts, most common first) followed by every other value on the Zune; or just type a new one.
        fun options(selected: List<String>): List<Pair<String, String>> =
            selected.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                .map { "${it.key}  (${it.value} of ${ts.size} songs)" to it.key }
        fun field(label: String, init: String, numeric: Boolean = false, opts: List<Pair<String, String>> = emptyList()): EditText {
            form.addView(TextView(this).apply { text = label.uppercase(); textSize = 11f; setTextColor(DIM); letterSpacing = 0.06f; setPadding(0, dp(8), 0, 0) })
            val box = EditText(this).apply {
                setText(init); setSingleLine(); if (!one && init.isEmpty()) hint = "<multiple values> - pick or type"
                if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            initial[box] = init
            form.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(box, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (opts.isNotEmpty()) addView(Button(this@MainActivity).apply {
                    text = "▾"; minWidth = dp(44); minimumWidth = dp(44); stateListAnimator = null
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setTitle(label)
                            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> box.setText(opts[i].second); box.setSelection(box.text.length) }.show()
                    }
                })
            })
            return box
        }
        fun shared(f: (Track) -> String) = ts.map(f).distinct().singleOrNull().orEmpty()
        val title = if (one) field("Title", ts[0].title) else null
        val artist = field("Artist", shared { it.artist }, opts = options(ts.map { it.artist }))
        // blank when some songs have no album, so whatever is typed counts as a change and also reaches those songs
        val album = if (canAlbum) field("Album", if (orphans.isEmpty()) albs.map { it.title }.distinct().singleOrNull().orEmpty() else "",
            opts = options(ts.map { it.album })) else null
        val albumArtist = if (canAlbumArtist) field("Album artist", albs.map { it.artist }.distinct().singleOrNull().orEmpty(),
            opts = options(albs.map { it.artist })) else null
        val genre = field("Genre", shared { it.genre }, opts = options(ts.map { it.genre }))
        val year = field("Year", ts.map { it.year }.distinct().singleOrNull()?.takeIf { it > 0 }?.toString().orEmpty(), true,
            options(ts.map { it.year.toString() }.filter { it != "0" }))
        val no = if (one) field("Track #", ts[0].no.toString(), true) else null
        if (canAlbum) form.addView(TextView(this).apply {
            text = (if (albs.isNotEmpty()) "Album and album artist apply to the whole album these songs belong to, not just the selected songs. " else "") +
                (if (orphans.isNotEmpty()) "${orphans.size} selected song(s) have no album: the album you type is created on the Zune (or joined if one with that name and artist exists) and they are added to it." else "")
            textSize = 12f; setTextColor(DIM); setPadding(0, dp(10), 0, dp(4))
        })
        if (one) form.addView(Button(this).apply {
            text = "SUGGEST FROM MUSICBRAINZ"; textSize = 12f; setTextColor(PINK); stateListAnimator = null
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), PINK) }
            setOnClickListener { suggestTags(ts[0], artist, album, year, no, genre, this) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        AlertDialog.Builder(this).setTitle(if (one) ts[0].title else "Edit ${ts.size} songs").setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Apply") { _, _ ->
                fun changed(e: EditText?) = e != null && clean(e.text.toString()) != initial[e]
                // "=" leaves a field alone, "+value" sets it: untouched fields are never written back to the Zune
                fun set(e: EditText?) = if (changed(e)) "+" + clean(e!!.text.toString()) else "="
                val titleSet = if (title != null && changed(title) && clean(title.text.toString()).isEmpty()) "=" else set(title)   // never blank a title
                fun setArtist(e: EditText?) = if (changed(e)) "+" + rawArtist(clean(e!!.text.toString())) else "="
                val trackFields = listOf(titleSet, setArtist(artist), set(genre), set(no), set(year))
                val trackLines = if (trackFields.none { it.startsWith("+") }) "" else ts.joinToString("\n") { t -> tsv(t.id, *trackFields.toTypedArray()) }
                // A new artist also becomes the album artist of every album whose songs are ALL selected
                // (otherwise the album would still list the old artist); partly selected albums keep theirs.
                val ids = ts.map { it.id }.toSet()
                val newArtist = if (changed(artist)) clean(artist.text.toString()) else null
                // Songs with no album are linked into the album named in the box, grouped by artist (the Zune keeps one album
                // object per album+artist). An existing album with the same name and artist is joined instead of duplicated.
                val linkName = if (album != null && changed(album)) clean(album.text.toString()) else ""
                val linkLines = if (linkName.isEmpty()) "" else orphans.groupBy { rawArtist(newArtist ?: it.artist).ifBlank { UNKNOWN_ARTIST_GUID } }.map { (art, list) ->
                    val existing = albums.firstOrNull { it.obj != 0L && it.title.equals(linkName, true) && rawArtist(it.artist).ifBlank { UNKNOWN_ARTIST_GUID } == art }?.obj ?: 0L
                    tsv(existing, art, linkName, list.joinToString(",") { it.id.toString() })
                }.joinToString("\n")
                val albumLines = if (!canAlbum) "" else albs.map { a ->
                    val whole = tracks.filter { it.albumObj == a.obj }.all { it.id in ids }
                    val aa = if (changed(albumArtist)) setArtist(albumArtist) else if (newArtist != null && whole) "+${rawArtist(newArtist)}" else "="
                    tsv(a.obj, set(album), aa)
                }.filter { line -> line.split('\t').drop(1).any { it.startsWith("+") } }.joinToString("\n")
                if (trackLines.isBlank() && albumLines.isBlank() && linkLines.isBlank()) return@setPositiveButton log("Nothing changed.")
                applyEdits(trackLines, albumLines, linkLines)
            }.setNegativeButton("Cancel", null).show()
    }

    /**
     * Asks MusicBrainz what it knows about this song and lists the matches (a song often exists on a soundtrack AND on
     * the artist's own album, so each release is its own row). Picking one only fills the editor's boxes; nothing is
     * written to the Zune until the user taps Apply, and fields stay editable.
     */
    private fun suggestTags(t: Track, artist: EditText, album: EditText?, year: EditText, no: EditText?, genre: EditText, btn: Button) {
        val knownArtist = clean(artist.text.toString()).takeIf { it.isNotEmpty() && !it.equals(UNKNOWN_ARTIST, true) }
        btn.isEnabled = false; btn.text = "SEARCHING…"
        Thread {
            val res = runCatching { MusicBrainz.suggest(t.title, knownArtist, t.album, t.dur) }
            runOnUiThread {
                btn.isEnabled = true; btn.text = "SUGGEST FROM MUSICBRAINZ"
                val list = res.getOrNull()
                if (list == null) return@runOnUiThread log("Suggest failed: ${res.exceptionOrNull()?.message ?: res.exceptionOrNull()}")
                    .also { android.widget.Toast.makeText(this, "No answer from MusicBrainz - check the internet connection", android.widget.Toast.LENGTH_LONG).show() }
                if (list.isEmpty()) return@runOnUiThread android.widget.Toast.makeText(this, "MusicBrainz knows nothing about this one", android.widget.Toast.LENGTH_LONG).show()
                val labels = list.map { c ->
                    "${c.artist} - ${c.album}\n" + listOf(c.year.ifEmpty { "no year" }, if (c.track.isEmpty()) "" else "track ${c.track}", c.kinds,
                        if (c.lengthMatches) "length matches ✓" else "").filter { it.isNotEmpty() }.joinToString("  ·  ")
                }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Which release is it?").setItems(labels) { _, i ->
                    val c = list[i]
                    val wait = AlertDialog.Builder(this).setMessage("Looking up genres…").setCancelable(false).show()
                    Thread {
                        val genres = MusicBrainz.genres(c)   // best effort: empty when MusicBrainz has none
                        runOnUiThread { wait.dismiss(); reviewSuggestion(c, genres, artist, album, year, no, genre) }
                    }.start()
                }.setNegativeButton("Close", null).show()
            }
        }.start()
    }

    /** Lets the user tick exactly which suggested fields to take. Ticked ones only fill the editor's boxes (Apply still writes). */
    private fun reviewSuggestion(c: Candidate, genres: List<String>, artist: EditText, album: EditText?, year: EditText, no: EditText?, genre: EditText) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val takes = mutableListOf<Pair<CheckBox, () -> Unit>>()
        fun field(label: String, new: String, box: EditText?) {
            if (box == null || new.isEmpty()) return
            val now = clean(box.text.toString())
            val cb = CheckBox(this).apply {
                text = "$label: $new\n     now: ${now.ifEmpty { "-" }}"; isChecked = new != now; isEnabled = new != now
            }
            form.addView(cb); takes += cb to { box.setText(new) }
        }
        field("Artist", c.artist, artist); field("Album", c.album, album); field("Year", c.year, year); field("Track #", c.track, no)
        if (genres.isEmpty()) form.addView(TextView(this).apply { text = "Genre: MusicBrainz has no genre for this one"; textSize = 14f; setPadding(0, dp(8), 0, 0) })
        else {
            val pick = android.widget.Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, genres) }
            val now = clean(genre.text.toString())
            val cb = CheckBox(this).apply { text = "Genre (now: ${now.ifEmpty { "-" }})"; isChecked = now.isEmpty() }   // only pre-ticked when the song has none
            form.addView(cb); form.addView(pick)
            takes += cb to { genre.setText(pick.selectedItem.toString()) }
        }
        AlertDialog.Builder(this).setTitle("${c.artist} - ${c.album}").setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Use ticked") { _, _ ->
                val chosen = takes.filter { it.first.isChecked }
                chosen.forEach { it.second() }
                android.widget.Toast.makeText(this, if (chosen.isEmpty()) "Nothing ticked" else "Filled ${chosen.size} field(s) - tap Apply to write them to the Zune", android.widget.Toast.LENGTH_LONG).show()
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- search + selection ----------

    /** Every word must match title/artist/album/genre. "no:album", "no:artist", "no:genre", "no:year" match songs missing that field. */
    private fun matches(t: Track): Boolean = filter.isEmpty() || filter.split(' ').filter { it.isNotEmpty() }.all { w ->
        when (w) {
            "no:album" -> t.album.isBlank()
            "no:artist" -> t.artist.isBlank() || t.artist == UNKNOWN_ARTIST
            "no:genre" -> t.genre.isBlank()
            "no:year" -> t.year <= 0
            else -> t.title.lowercase().contains(w) || t.artist.lowercase().contains(w) || t.album.lowercase().contains(w) || t.genre.lowercase().contains(w)
        }
    }

    /**
     * Mass version of Suggest: looks every selected song up on MusicBrainz (one song at a time, slowly, as MusicBrainz asks),
     * keeps only CONFIDENT matches (length agrees, and artist agrees when the song has one), and shows one review list.
     */
    private fun autoFill() {
        val ts = tracks.filter { it.id in selSongs }
        if (ts.isEmpty()) return
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this).setTitle("Looking up ${ts.size} songs").setMessage("starting…").setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }.create()
        dlg.show()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val rows = mutableListOf<Pair<Track, Candidate?>>()
            val failed = mutableSetOf<Long>()
            var failure: String? = null; var inARow = 0
            // MusicBrainz allows ~1 request per second, so asking about every song would take minutes. Instead, whenever a song
            // is found, its WHOLE release (track list) is downloaded once and the following songs are matched against it locally.
            val releases = mutableListOf<MusicBrainz.ReleaseInfo>(); var quick = 0
            for ((i, t) in ts.withIndex()) {
                if (cancelled.get()) break
                runOnUiThread { dlg.setMessage("${i + 1} of ${ts.size}: ${t.title}\n\n$quick matched from album track lists already downloaded.\nOnly the first song of each album needs a slow lookup.") }
                val known = t.artist.takeIf { it.isNotBlank() && !it.equals(UNKNOWN_ARTIST, true) }
                try {
                    var best: Candidate? = releases.firstNotNullOfOrNull { MusicBrainz.matchLocal(it, t.title, t.dur, known) }
                    if (best != null) quick++
                    else {
                        val found = MusicBrainz.suggest(t.title, known, t.album, t.dur).firstOrNull { c -> c.lengthMatches && (known == null || MusicBrainz.sameArtist(c.artist, known)) }
                        best = found
                        if (found != null && found.releaseId.isNotEmpty() && releases.none { it.id == found.releaseId }) {
                            Thread.sleep(1100)
                            val info = runCatching { MusicBrainz.release(found.releaseId) }.getOrNull()
                            if (info != null) { releases += info; best = MusicBrainz.matchLocal(info, t.title, t.dur, known) ?: found }
                        }
                        Thread.sleep(1100)
                    }
                    rows += t to best; inARow = 0
                    android.util.Log.i("ZuneOrganizer", "lookup ${i + 1}/${ts.size} '${t.title}': " + (best?.let { "${it.artist} - ${it.album}" } ?: "no confident match"))
                } catch (e: Exception) {
                    // one failed song must not end the batch: note it, pause, carry on; give up only after 5 failures in a row
                    failed += t.id; rows += t to null; inARow++
                    android.util.Log.i("ZuneOrganizer", "lookup ${i + 1}/${ts.size} '${t.title}' FAILED: ${e.message ?: e}")
                    if (inARow >= 5) { failure = e.message ?: e.toString(); break }
                    Thread.sleep(3000)
                }
            }
            runOnUiThread {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); dlg.dismiss()
                val matched = rows.count { it.second != null }
                log("Auto-fill lookup: ${rows.size} of ${ts.size} songs looked up, $matched matched, ${failed.size} failed" + (if (cancelled.get()) ", cancelled" else ""))
                if (failure != null) { log("Lookup stopped after repeated failures: $failure"); android.widget.Toast.makeText(this, "Lookup stopped after repeated failures - check the internet connection", android.widget.Toast.LENGTH_LONG).show() }
                if (rows.isNotEmpty()) reviewBatch(rows, ts.size, failed)
            }
        }.start()
    }

    /** One list for all looked-up songs: tick the ones to take, choose which fields to take, apply everything in one session. */
    private fun reviewBatch(rows: List<Pair<Track, Candidate?>>, requested: Int, failed: Set<Long>) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun box(text: String, on: Boolean) = CheckBox(this).apply { this.text = text; isChecked = on }.also { form.addView(it) }
        val takeAlbum = box("Album (only for songs that have none)", true)
        val takeYear = box("Year", true)
        val takeTrack = box("Track #", true)
        val takeArtist = box("Artist (only for songs that have none)", false)
        form.addView(hairline())
        val picked = mutableListOf<Pair<CheckBox, Pair<Track, Candidate>>>()
        for ((t, c) in rows) {
            if (c == null) form.addView(TextView(this).apply { text = "${t.title}\n     " + (if (t.id in failed) "lookup failed (network) - try again" else "no confident match - left alone"); textSize = 14f; setTextColor(DIM); setPadding(0, dp(6), 0, dp(6)) })
            else picked += box("${t.title}\n     → ${c.album} · ${c.artist} · ${c.year.ifEmpty { "no year" }}" + (if (c.track.isEmpty()) "" else " · track ${c.track}"), true) to (t to c)
        }
        AlertDialog.Builder(this).setTitle("${picked.size} matched, ${rows.size} looked up of $requested selected").setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Apply ticked") { _, _ ->
                val chosen = picked.filter { it.first.isChecked }.map { it.second }
                val artistFor = { t: Track, c: Candidate -> if (takeArtist.isChecked && (t.artist.isBlank() || t.artist == UNKNOWN_ARTIST)) c.artist else t.artist }
                val trackLines = chosen.mapNotNull { (t, c) ->
                    val a = if (artistFor(t, c) != t.artist) "+${rawArtist(c.artist)}" else "="
                    val y = if (takeYear.isChecked && c.year.isNotEmpty() && c.year != t.year.toString()) "+${c.year}" else "="
                    val n = if (takeTrack.isChecked && c.track.isNotEmpty() && c.track != t.no.toString()) "+${c.track}" else "="
                    if (a == "=" && y == "=" && n == "=") null else tsv(t.id, "=", a, "=", n, y)
                }.joinToString("\n")
                val orphans = chosen.filter { it.first.albumObj == 0L }
                val linkLines = if (!takeAlbum.isChecked) "" else orphans.groupBy { (t, c) -> c.album to rawArtist(artistFor(t, c)).ifBlank { UNKNOWN_ARTIST_GUID } }.map { (key, list) ->
                    val (albumName, art) = key
                    val existing = albums.firstOrNull { it.obj != 0L && it.title.equals(albumName, true) && rawArtist(it.artist).ifBlank { UNKNOWN_ARTIST_GUID } == art }?.obj ?: 0L
                    tsv(existing, art, albumName, list.joinToString(",") { it.first.id.toString() })
                }.joinToString("\n")
                if (trackLines.isBlank() && linkLines.isBlank()) return@setPositiveButton log("Nothing to apply.")
                log("Auto-fill: ${chosen.size} songs ticked, ${orphans.size} without an album")
                applyEdits(trackLines, "", linkLines)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun keyOf(t: Track): String = when (tab) { Tab.ARTISTS -> t.artist; Tab.ALBUMS -> t.album; Tab.GENRES -> t.genre; else -> t.id.toString() }

    /** Songs are selected on the SONGS tab and inside an opened group; groups are selected on the group lists. */
    private fun songMode() = tab == Tab.SONGS || open != null

    private fun selectAllShown() {
        val shown = tracks.filter(::matches)
        if (songMode()) selSongs.addAll(shown.filter { open == null || keyOf(it) == open }.map { it.id }) else sel.addAll(shown.map(::keyOf))
        render()
    }

    private fun clearSelection() { if (songMode()) selSongs.clear() else sel.clear(); render() }

    private fun toggleSong(id: Long) { if (!selSongs.add(id)) selSongs.remove(id); render() }

    private fun toggle(k: String) { if (!sel.add(k)) sel.remove(k); render() }

    private fun actOnSelection() { if (songMode()) editTracks(tracks.filter { it.id in selSongs }) else mergeSelected() }

    /**
     * Rename / merge the selected groups of the current tab to one common name (picked from the selection or typed).
     * Genres: one edit batch on the tracks. Artists: tracks' artist + their albums' album artist. Albums: the album objects.
     * One connection, then a verifying re-read.
     */
    private fun mergeSelected() {
        if (tab != Tab.GENRES && isHd) return log("Artist/album edits are not supported on Zune HD yet (genres are).")
        val names = sel.sortedBy { it.lowercase() }
        val key: (Track) -> String = when (tab) { Tab.ARTISTS -> { t -> t.artist }; Tab.ALBUMS -> { t -> t.album }; else -> { t -> t.genre } }
        val picked = tracks.filter { key(it) in sel }
        val suggestion = picked.groupBy(key).maxByOrNull { it.value.size }?.key.orEmpty()
        val what = tab.name.lowercase().removeSuffix("s")
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        // MediaMonkey style: one radio per existing name (with its song count), plus "new name" with a text box
        val counts = picked.groupingBy(key).eachCount()
        val group = android.widget.RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val ids = names.map { n ->
            android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "${n.ifBlank { "(none)" }}  (${counts[n] ?: 0} songs)" }.also { group.addView(it) }.id
        }
        val newId = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "New $what name…" }.also { group.addView(it) }.id
        group.check(ids.getOrElse(names.indexOf(suggestion)) { newId })
        form.addView(group)
        val input = EditText(this).apply { setSingleLine(); hint = "type the new $what name"; setOnFocusChangeListener { _, f -> if (f) group.check(newId) } }
        form.addView(input)
        val artistInput = EditText(this).apply { setSingleLine(); hint = "album artist (blank = keep each one's own)" }
        if (tab == Tab.ALBUMS) form.addView(artistInput)
        AlertDialog.Builder(this).setTitle("Rename / merge ${picked.size} songs").setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Apply") { _, _ ->
                val i = ids.indexOf(group.checkedRadioButtonId)
                if (i >= 0) input.setText(names[i])   // existing name chosen: reuse it as-is
                mergeSelectedConfirm(input, artistInput)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun mergeSelectedConfirm(input: EditText, artistInput: EditText) {
        val name = clean(input.text.toString())
        if (name.isEmpty()) return log("Cancelled: empty name.")
        var trackLines = ""; var albumLines = ""
        when (tab) {
            Tab.GENRES -> trackLines = tracks.filter { it.genre in sel }
                .joinToString("\n") { t -> tsv(t.id, "=", "=", "+$name", "=", "=") }
            Tab.ARTISTS -> {
                trackLines = tracks.filter { it.artist in sel }
                    .joinToString("\n") { t -> tsv(t.id, "=", "+${rawArtist(name)}", "=", "=", "=") }
                albumLines = albums.filter { it.artist in sel && it.obj != 0L }
                    .joinToString("\n") { a -> tsv(a.obj, "=", "+${rawArtist(name)}") }
            }
            else -> {
                val newArtist = clean(artistInput.text.toString())
                albumLines = albums.filter { it.title in sel && it.obj != 0L }
                    .joinToString("\n") { a -> tsv(a.obj, "+$name", if (newArtist.isEmpty()) "=" else "+${rawArtist(newArtist)}") }
            }
        }
        log("Merging into '$name'")
        applyEdits(trackLines, albumLines)
    }

    // ---------- rendering ----------

    private fun render() {
        tabs.forEach { (v, tv) ->
            tv.text = v.name; tv.setTextColor(if (v == tab) INK else DIM)
            tv.typeface = Typeface.create("sans-serif", if (v == tab) Typeface.BOLD else Typeface.NORMAL)
        }
        scroll.visibility = if (tab == Tab.LOG) View.GONE else View.VISIBLE
        logScroll.visibility = if (tab == Tab.LOG) View.VISIBLE else View.GONE
        syncButtons()
        body.removeAllViews()
        val hasLib = tab != Tab.LOG && tracks.isNotEmpty()
        filterBox.visibility = if (hasLib) View.VISIBLE else View.GONE
        val active = if (songMode()) selSongs.size else sel.size
        barRow.visibility = if (hasLib) View.VISIBLE else View.GONE
        noneBtn.visibility = if (active > 0) View.VISIBLE else View.GONE
        mergeBar.visibility = if (active > 0) View.VISIBLE else View.GONE
        mergeBar.text = if (songMode()) "EDIT $active SONGS" else "RENAME/MERGE $active"
        autoBtn.visibility = if (songMode() && selSongs.isNotEmpty() && !isHd) View.VISIBLE else View.GONE   // mass MusicBrainz lookup for the selected songs
        songsBtn.visibility = if (!songMode() && sel.isNotEmpty()) View.VISIBLE else View.GONE   // edit every song of the selected groups
        if (tab == Tab.LOG) return
        if (tracks.isEmpty()) { body.addView(heading("no library", "plug in the zune and tap read")); return }

        val shown = tracks.filter(::matches)
        val o = open
        fun songRow(t: Track) = row(t.title, "${t.artist} - ${t.album.ifBlank { "no album" }} - ${t.genre.ifBlank { "no genre" }}",
            if (t.id in selSongs) PINK else INK, onLong = { toggleSong(t.id) }) {
            if (busy) return@row
            if (selSongs.isNotEmpty()) toggleSong(t.id) else editTracks(listOf(t))
        }
        fun more(n: Int) { if (n > MAX_ROWS) body.addView(light(14f, DIM).apply { text = "+ ${n - MAX_ROWS} more - type in the search box to narrow"; setPadding(0, dp(8), 0, 0) }) }

        if (tab == Tab.SONGS) {
            val ts = shown.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.disc }, { it.no }))
            body.addView(heading("${ts.size} songs", "tap to edit - hold to select")); body.addView(hairline())
            ts.take(MAX_ROWS).forEach { body.addView(songRow(it)) }; more(ts.size)
        } else if (o == null) {
            val groups = shown.groupBy(::keyOf).toSortedMap(compareBy { it.lowercase() })
            val totals = tracks.groupingBy(::keyOf).eachCount()
            body.addView(heading("${groups.size} ${tab.name}", "A-Z - hold to select")); body.addView(hairline())
            groups.entries.take(MAX_ROWS).forEach { (name, ts) ->
                body.addView(row(name.ifBlank { "(none)" }, if (filter.isEmpty()) "${ts.size} songs" else "${ts.size} matching of ${totals[name]} songs",
                    if (name in sel) PINK else INK, onLong = { toggle(name) }) { if (sel.isNotEmpty()) toggle(name) else { open = name; selSongs.clear(); render() } })
            }
            more(groups.size)
        } else {
            val ts = shown.filter { keyOf(it) == o }.sortedWith(compareBy({ it.album.lowercase() }, { it.disc }, { it.no }))
            body.addView(row("‹ all ${tab.name.lowercase()}", "back to the list", PINK) { open = null; selSongs.clear(); render() })
            body.addView(heading(o.ifBlank { "(none)" }, "${ts.size} songs - tap to edit - hold to select")); body.addView(hairline())
            ts.take(MAX_ROWS).forEach { body.addView(songRow(it)) }; more(ts.size)
        }
    }

    private fun row(title: String, sub: String, color: Int = INK, onLong: (() -> Unit)? = null, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, dp(9), 0, dp(9)); setOnClickListener { onClick() }
        if (onLong != null) setOnLongClickListener { onLong(); true }
        addView(light(18f, color).apply { text = title; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        addView(light(12f, DIM).apply { text = sub; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    }
}
