package org.xune.ztransfer

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val ACTION_PERM = "org.xune.ztransfer.USB_PERMISSION"
private const val ZUNE_VENDOR = 0x045E
private const val REQ_KEYS = 1
private const val REQ_FILES = 2
private const val REQ_FOLDER = 3
// The key file is NOT hosted by this project. It is fetched on the user's phone, after confirmation, from a public repo.
private const val KEY_URL = "https://raw.githubusercontent.com/kbhomes/libmtp-zune/master/src/.mtpz-data"

// Zune desktop software look: white canvas, huge thin lowercase title, bold-caps headings with gray tags,
// plain light list text, pink-to-orange accent tab on the left edge.
private const val INK = 0xFF222222.toInt()
private const val DIM = 0xFF9A9A9A.toInt()
private const val LINE = 0xFFD6D6D6.toInt()
private const val PINK = 0xFFEC008C.toInt()
private const val ORANGE = 0xFFF7611F.toInt()
private val AUDIO_EXT = setOf("mp3", "wma", "m4a", "flac", "aac", "ogg")
private val TRACK_RE = Regex("""Track (\d+)/(\d+):\s*(.*)""")
private val CONNECTED_RE = Regex("""Connected to:\s*(.*?)\s*\[""")

class MainActivity : Activity() {
    private lateinit var keysRow: TextView
    private lateinit var zuneRow: TextView
    private lateinit var listHeading: TextView
    private lateinit var stagedList: LinearLayout
    private lateinit var nowText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var setupPanel: View
    private lateinit var transferPanel: View
    private lateinit var tabMusic: TextView
    private lateinit var tabLog: TextView
    private lateinit var sendBtn: Button
    private val buttons = mutableListOf<Button>()
    private var zuneState = "NOT CONNECTED"
    private var zuneActive = false

    private val keys get() = File(filesDir, "mtpz-data")
    private val userKeyMarker get() = File(filesDir, "mtpz-user")   // present once the user imported their own key
    private var builtInKeys = false
    private val musicDir get() = File(cacheDir, "music")
    private var busy = false

    private val permReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                                  else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) runUpload(dev)
            else log("USB permission denied")
        }
    }

    // ---------- UI building blocks ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun light(sp: Float, color: Int) = TextView(this).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); textSize = sp; setTextColor(color)
    }

    /** "34 ARTISTS  A-Z" style heading: bold black caps followed by a gray caps tag. */
    private fun setHeading(tv: TextView, bold: String, tag: String, tagColor: Int = DIM) {
        val sb = SpannableStringBuilder(bold.uppercase())
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(INK), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = sb.length
        sb.append("  ").append(tag.uppercase())
        sb.setSpan(ForegroundColorSpan(tagColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = sb
    }

    private fun heading() = TextView(this).apply {
        textSize = 13f; letterSpacing = 0.04f; setPadding(0, dp(10), 0, dp(10))
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label.uppercase(); isAllCaps = true; textSize = 12f; letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(INK)
        background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), LINE) }
        stateListAnimator = null; minHeight = dp(44); minimumHeight = dp(44)
        setOnClickListener { if (!busy) onClick() }
    }.also { buttons += it }

    private fun primaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label.uppercase(); isAllCaps = true; textSize = 14f; letterSpacing = 0.1f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(Color.WHITE)
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(PINK, ORANGE))
        stateListAnimator = null; minHeight = dp(52); minimumHeight = dp(52)
        setOnClickListener { if (!busy) onClick() }
    }.also { buttons += it }

    private fun hairline() = View(this).apply { setBackgroundColor(LINE); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)) }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h)) }

    private fun pair(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }

        // pink-to-orange tab on the left edge, as in the Zune software
        val tab = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(PINK, ORANGE))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(20), dp(6)) }
        head.addView(light(46f, INK).apply { text = "transfer"; includeFontPadding = false })
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        tabMusic = TextView(this).apply { textSize = 13f; letterSpacing = 0.05f; setOnClickListener { showPanel(true) } }
        tabLog = TextView(this).apply { textSize = 13f; letterSpacing = 0.05f; setPadding(dp(18), 0, 0, 0); setOnClickListener { showPanel(false) } }
        tabs.addView(tabMusic); tabs.addView(tabLog)
        head.addView(tabs)
        root.addView(FrameLayout(this).apply {
            addView(head)
            addView(tab, FrameLayout.LayoutParams(dp(10), dp(64)).apply { topMargin = dp(18) })
        })

        // transfer panel: info rows, button grid, staged list
        keysRow = heading(); zuneRow = heading()
        listHeading = heading()
        stagedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(20), dp(16))
            addView(keysRow); addView(hairline()); addView(zuneRow); addView(hairline())
            addView(gap(14))
            addView(pair(outlineButton("add files") { pick(REQ_FILES, true, "audio/*") },
                         outlineButton("add folder") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER) }))
            addView(gap(8))
            addView(pair(outlineButton("clear") { clearStaged() },
                         outlineButton("settings") { showSetup() }))
            addView(gap(14))
            addView(listHeading); addView(hairline())
            addView(stagedList)
        }
        transferPanel = ScrollView(this).apply { addView(body) }

        // log panel
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(0xFF555555.toInt())
            setTextIsSelectable(true); setPadding(dp(24), dp(12), dp(20), dp(24))
        }
        logScroll = ScrollView(this).apply { addView(logView); visibility = View.GONE }

        // initial setup panel: MTPZ key, shown once on first launch (and from "settings")
        setupPanel = ScrollView(this).apply {
            visibility = View.GONE
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(20), dp(16))
                addView(heading().also { setHeading(it, "initial setup", "one time") })
                addView(hairline())
                addView(light(16f, INK).apply {
                    text = "Your Zune only accepts new music from apps that prove themselves with an MTPZ key file. " +
                        "Set it up once here and you won't be asked again."
                    setPadding(0, dp(14), 0, dp(18))
                })
                addView(outlineButton("download keys online") { confirmDownloadKeys() })
                addView(gap(8))
                addView(outlineButton("import key file") { pick(REQ_KEYS, false, "*/*") })
                addView(gap(8))
                addView(outlineButton("close") { finishSetup() })
            })
        }
        root.addView(FrameLayout(this).apply { addView(transferPanel); addView(logScroll); addView(setupPanel) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // pinned bottom "player" area: progress, now-playing line, send
        nowText = light(14f, DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.INVISIBLE
            progressTintList = ColorStateList.valueOf(PINK)
            progressBackgroundTintList = ColorStateList.valueOf(LINE)
            indeterminateTintList = ColorStateList.valueOf(PINK)
        }
        sendBtn = primaryButton("send to zune") { connectAndSend() }
        root.addView(hairline())
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(10), dp(20), dp(14))
            addView(nowText); addView(gap(6))
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(4))); addView(gap(10))
            addView(sendBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        setContentView(root)

        val f = IntentFilter(ACTION_PERM)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permReceiver, f, RECEIVER_NOT_EXPORTED) else registerReceiver(permReceiver, f)
        installBuiltInKeys()
        showPanel(true)
        refreshStatus()
        // one-time setup: only when there is no key yet and the user has not finished/skipped it before
        if (keys.exists()) setupDoneMarker.writeText("1")
        else if (!setupDoneMarker.exists()) showSetup()
        log("Plug the Zune in with an OTG cable.")
        if (intent.getBooleanExtra("selftest", false)) selfTest()
    }

    override fun onDestroy() { unregisterReceiver(permReceiver); super.onDestroy() }

    /** Optional key bundled at build time (app/src/main/assets/mtpz-data, git-ignored). A user-imported key wins. */
    private fun installBuiltInKeys() {
        if (userKeyMarker.exists()) return
        runCatching {
            assets.open("mtpz-data").use { i -> keys.outputStream().use { i.copyTo(it) } }
            builtInKeys = true
        }
    }

    private val setupDoneMarker get() = File(filesDir, "setup-done")

    private fun showSetup() {
        transferPanel.visibility = View.GONE; logScroll.visibility = View.GONE; setupPanel.visibility = View.VISIBLE
        tabMusic.setTextColor(DIM); tabLog.setTextColor(DIM)
    }

    private fun finishSetup() {
        setupDoneMarker.writeText("1")
        showPanel(true); refreshStatus()
    }

    /** Called after a key was imported or downloaded: closes the one-time setup if it is open. */
    private fun keyObtained() { if (setupPanel.visibility == View.VISIBLE) finishSetup() else refreshStatus() }

    private fun showPanel(transfer: Boolean) {
        setupPanel.visibility = View.GONE
        transferPanel.visibility = if (transfer) View.VISIBLE else View.GONE
        logScroll.visibility = if (transfer) View.GONE else View.VISIBLE
        fun style(t: TextView, text: String, on: Boolean) {
            t.text = text; t.setTextColor(if (on) INK else DIM)
            t.typeface = Typeface.create("sans-serif", if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        style(tabMusic, "MUSIC", transfer); style(tabLog, "LOG", !transfer)
    }

    private fun setBusy(b: Boolean) { busy = b; buttons.forEach { it.alpha = if (b) 0.4f else 1f } }

    private fun refreshStatus() {
        if (keys.exists()) setHeading(keysRow, "mtpz keys", if (builtInKeys) "built-in" else "imported", PINK) else setHeading(keysRow, "mtpz keys", "none")
        setHeading(zuneRow, "zune", zuneState, if (zuneActive) PINK else DIM)
        val files = musicDir.walkTopDown().filter { it.isFile }.sortedBy { it.name.lowercase() }.toList()
        setHeading(listHeading, if (files.isEmpty()) "no songs" else "${files.size} songs",
            if (files.isEmpty()) "add files or a folder" else "${mb(files.sumOf { it.length() })}  A-Z")
        stagedList.removeAllViews()
        files.take(300).forEach { f ->
            stagedList.addView(light(18f, INK).apply {
                text = f.nameWithoutExtension.replace("__", " / "); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(9), 0, dp(9))
            })
        }
        if (files.size > 300) stagedList.addView(light(14f, DIM).apply { text = "+ ${files.size - 300} more"; setPadding(0, dp(8), 0, 0) })
    }

    private fun mb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / 1073741824.0) else "%d MB".format(bytes / 1048576)

    private fun log(s: String) { logView.append(s + "\n"); logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) } }

    // ---------- picking and staging ----------

    private fun pick(req: Int, multi: Boolean, mime: String) = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multi), req)

    @Deprecated("fine for a single-screen tool")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (res != RESULT_OK || data == null) return
        when (req) {
            REQ_KEYS -> {
                val u = data.data ?: return
                contentResolver.openInputStream(u)!!.use { i -> keys.outputStream().use { i.copyTo(it) } }
                userKeyMarker.writeText("1"); builtInKeys = false
                log("Imported MTPZ keys (${keys.length()} bytes)"); keyObtained()
            }
            REQ_FILES -> {
                val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
                stage(uris.map { Staged(it, displayName(it) ?: "track-${it.hashCode()}", -1L) })
            }
            REQ_FOLDER -> {
                val tree = data.data ?: return
                setBusy(true); nowText.text = "reading folder…"
                Thread {
                    val found = mutableListOf<Staged>()
                    walkTree(tree, DocumentsContract.getTreeDocumentId(tree), "", found)
                    runOnUiThread { stage(found, folderMode = true) }
                }.start()
            }
        }
    }

    private class Staged(val uri: Uri, val name: String, val size: Long)

    private fun displayName(u: Uri): String? =
        contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null }

    /** Recursively lists audio files under a SAF tree; [rel] keeps subfolder names so same-named tracks don't collide. */
    private fun walkTree(tree: Uri, docId: String, rel: String, out: MutableList<Staged>) {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        val dirs = mutableListOf<Pair<String, String>>()
        contentResolver.query(kids, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) dirs += id to name
                else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXT)
                    out += Staged(DocumentsContract.buildDocumentUriUsingTree(tree, id), rel + name, c.getLong(3))
            }
        }
        dirs.forEach { (id, name) -> walkTree(tree, id, "$rel${name}__", out) }
    }

    private fun stage(items: List<Staged>, folderMode: Boolean = false) {
        if (items.isEmpty()) { setBusy(false); nowText.text = ""; log("No audio files found."); return }
        val total = items.sumOf { maxOf(it.size, 0L) }
        if (total > 0 && total > cacheDir.usableSpace - (200L shl 20)) {
            setBusy(false); nowText.text = ""
            log("Not enough free space to stage ${mb(total)} (free: ${mb(cacheDir.usableSpace)}). Pick fewer files.")
            return
        }
        setBusy(true)
        Thread {
            musicDir.mkdirs()
            items.forEachIndexed { i, s ->
                runOnUiThread { nowText.text = "staging ${i + 1} of ${items.size}…" }
                runCatching {
                    contentResolver.openInputStream(s.uri)!!.use { inp -> uniqueFile(s.name).outputStream().use { inp.copyTo(it) } }
                }.onFailure { runOnUiThread { log("Skipped ${s.name}: ${it.message}") } }
            }
            runOnUiThread {
                setBusy(false); nowText.text = ""; refreshStatus()
                log("Staged ${items.size} file(s)${if (folderMode) " from folder" else ""}")
            }
        }.start()
    }

    private fun uniqueFile(name: String): File {
        var f = File(musicDir, name.replace('/', '_')); var n = 1
        while (f.exists()) { f = File(musicDir, "${n++}_" + name.replace('/', '_')) }
        return f
    }

    private fun confirmDownloadKeys() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Get MTPZ keys online")
            .setMessage("Downloads a small credentials file (about 2 KB) from the public GitHub repository " +
                "kbhomes/libmtp-zune. It is not part of this app or its repository. " +
                "You are responsible for being allowed to use it with your own Zune.")
            .setPositiveButton("Download") { _, _ -> downloadKeys() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadKeys() {
        setBusy(true); nowText.text = "downloading keys…"; nowText.setTextColor(DIM)
        Thread {
            val result = runCatching {
                val c = URL(KEY_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                if (c.responseCode != 200) error("HTTP ${c.responseCode}")
                val text = c.inputStream.bufferedReader().use { it.readText() }
                val lines = text.lines().filter { it.isNotBlank() }
                // sanity check: five hex lines, small file; bytes are written unchanged
                require(text.length < 8192 && lines.size == 5 && lines.all { l -> l.trim().all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } }) { "unexpected file format" }
                keys.writeText(text)
                userKeyMarker.writeText("1"); builtInKeys = false
            }
            runOnUiThread {
                setBusy(false); nowText.text = ""
                result.onSuccess { log("Downloaded MTPZ keys (${keys.length()} bytes)"); keyObtained() }
                    .onFailure { log("Key download failed: ${it.message}") }
            }
        }.start()
    }

    private fun clearStaged() { musicDir.deleteRecursively(); log("Cleared staged music"); refreshStatus() }

    // ---------- USB + upload ----------

    private fun connectAndSend() {
        if (musicDir.walkTopDown().none { it.isFile }) return log("Add some music first.")
        if (!keys.exists()) log("No MTPZ key file: trying plain MTP (the Zune may refuse writes). Tap 'import keys' or 'get keys online'.")
        val um = getSystemService(USB_SERVICE) as UsbManager
        val dev = um.deviceList.values.firstOrNull { it.vendorId == ZUNE_VENDOR }
            ?: return log("No Zune found (vendor 045E). Devices: ${um.deviceList.values.map { "%04x:%04x".format(it.vendorId, it.productId) }}")
        if (um.hasPermission(dev)) runUpload(dev)
        else um.requestPermission(dev, PendingIntent.getBroadcast(this, 0,
            Intent(ACTION_PERM).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
    }

    private fun runUpload(dev: UsbDevice) {
        val conn = (getSystemService(USB_SERVICE) as UsbManager).openDevice(dev) ?: return log("openDevice failed")
        setBusy(true)
        progress.visibility = View.VISIBLE; progress.isIndeterminate = true
        nowText.text = "connecting…"; nowText.setTextColor(DIM)
        zuneState = "%04x:%04x".format(dev.vendorId, dev.productId); zuneActive = true; refreshStatus()
        log("Connected %04x:%04x, starting upload...".format(dev.vendorId, dev.productId))
        Thread {
            val rc = try { Native.upload(conn.fileDescriptor, if (keys.exists()) keys.path else "", musicDir.path, true) { onNativeLine(it) } }
                     catch (e: Throwable) { runOnUiThread { log("native error: $e") }; -1 }
            conn.close()
            runOnUiThread { finishUpload(rc) }
        }.start()
    }

    private fun onNativeLine(line: String) = runOnUiThread {
        log(line)
        CONNECTED_RE.find(line)?.let { zuneState = it.groupValues[1]; zuneActive = true; refreshStatus() }
        TRACK_RE.find(line)?.let { m ->
            val n = m.groupValues[1].toInt(); val total = m.groupValues[2].toInt()
            progress.isIndeterminate = false; progress.progress = (n - 1) * 100 / total
            nowText.text = "$n of $total  •  ${m.groupValues[3]}"; nowText.setTextColor(INK)
        }
    }

    private fun finishUpload(rc: Int) {
        setBusy(false)
        progress.isIndeterminate = false
        if (rc == 0) {
            progress.progress = 100
            nowText.text = "done — sent to your zune"; nowText.setTextColor(PINK)
            musicDir.deleteRecursively()   // free the staged copy; originals are untouched
            log("Finished OK; staged copies removed.")
        } else {
            progress.visibility = View.INVISIBLE
            nowText.text = "failed (code $rc) — see log"; nowText.setTextColor(PINK)
            log("Finished, rc=$rc")
        }
        zuneActive = false; zuneState = "NOT CONNECTED"
        refreshStatus()
    }

    /** adb shell am start --ez selftest true: proves the .so loads and JNI + log bridge work, no Zune needed. */
    private fun selfTest() = Thread {
        val rc = runCatching { Native.upload(-1, "", "/nonexistent", false) { l -> runOnUiThread { log("[native] $l") } } }
        runOnUiThread { log("selftest rc=${rc.getOrNull()} err=${rc.exceptionOrNull()}") }
    }.start()
}
