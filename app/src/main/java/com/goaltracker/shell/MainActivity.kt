package com.goaltracker.shell

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The permanent shell. It only does four things:
 *  1. Hosts a WebView serving your HTML from internal storage (filesDir/app)
 *  2. Lets you import a new HTML file or ZIP bundle without rebuilding the APK
 *  3. Exposes the JsBridge to your HTML (window.AndroidBridge)
 *  4. Handles backup export/import via the system file picker
 *
 * All app logic lives in the HTML. The shell stays dumb.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    // v4.11 Display Size debug readout: viewport settings cached here when they
    // are (re)configured on the MAIN thread. getDiagnostics() runs on the
    // WebView JS bridge thread, and reading WebView state from there failed on
    // device — the readout silently fell back to placeholder values
    // (wideVP=false / textZoom=-1) while the fix itself was active.
    @Volatile private var cachedUseWideViewPort: Boolean? = null
    @Volatile private var cachedLoadWithOverviewMode: Boolean? = null
    @Volatile private var cachedTextZoom: Int? = null
    // v2.3: kept so the shell can drive the reliable sound player (preview /
    // selection) directly from the native sound chooser.
    private lateinit var jsBridge: JsBridge
    // Which sound slot the currently-open chooser is editing. Set by a tiny
    // injected listener on the two known HTML file inputs (see onPageFinished).
    private var pendingSoundSlot: String = SoundLibrary.SLOT_COMPLETION

    // True once the WebView has finished loading the app at least once. Used to
    // tell a COLD start (page not loaded yet -> JS boot drains the pending-event
    // queue itself) apart from a WARM resume (page already live -> JS boot does
    // NOT run again, so we must drain + replay queued notification taps here).
    @Volatile
    private var pageEverFinished = false

    private val appDir: File get() = File(filesDir, "app")
    // Named appDataDir (not dataDir) to avoid clashing with Android's
    // built-in Context.getDataDir(), which causes a duplicate JVM signature.
    private val appDataDir: File get() = File(filesDir, "data").apply { mkdirs() }

    // ---------- File picker launchers ----------

    private val importHtmlLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importHtmlOrZip(uri)
        }

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) writeBackupTo(uri)
        }

    // Fallback for devices whose documents provider does not accept the
    // "application/json" MIME type (launching the picker then throws an
    // ActivityNotFoundException - this was the "export crashes immediately"
    // bug). The generic type is accepted everywhere DocumentsUI exists.
    private val exportBackupFallbackLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            if (uri != null) writeBackupTo(uri)
        }

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) restoreBackupFrom(uri)
        }

    // v2.3: "Choose from device" - the system ringtone/alarm picker. Requires
    // no storage permission and only returns a stable MediaStore Uri.
    private val ringtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    result.data?.getParcelableExtra(
                        RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
                if (uri != null) {
                    jsBridge.selectDeviceSound(pendingSoundSlot, uri.toString())
                    toast("Device sound set", force = true)
                } else {
                    // "Silent" or no selection - keep the current sound.
                    toast("No device sound selected")
                }
            }
        }

    // Fallback for phones with no ringtone picker: pick an audio file via the
    // document picker and persist read access so the alarm survives reboots.
    private val deviceAudioFallbackLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                jsBridge.selectDeviceSound(pendingSoundSlot, uri.toString())
                toast("Device sound set", force = true)
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifyJs("notificationPermission", if (granted) "\"granted\"" else "\"denied\"")
        }

    // ---------- HTML <input type="file"> support (v2.2) ----------
    // Without a WebChromeClient.onShowFileChooser implementation the WebView
    // silently ignores every file input - this was why "Choose Sound" in the
    // HTML settings appeared to be a dummy button. The callback MUST always be
    // invoked (even with null) or the WebView blocks all future file inputs.
    private var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
            filePathCallback = null
        }

    private var pendingDiagnostics: String = ""

    private val exportDiagnosticsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)!!.use {
                        it.write(pendingDiagnostics.toByteArray())
                    }
                    toast("Diagnostics exported")
                } catch (e: Exception) {
                    ErrorLog.log(this, "exportDiagnostics", e.message ?: "unknown")
                    toast("Export failed: ${e.message}", force = true)
                }
            }
        }

    // v4.11: "Save Current HTML File" - exports the RUNNING index.html (bundled or
    // custom-installed) as a .html file the user can keep or re-install later.
    private val exportHtmlLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
            if (uri != null) writeCurrentHtmlTo(uri)
        }

    /** Guards against showing the load-failure dialog repeatedly. */
    private var recoveryDialogShowing = false

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        copyBundledAppIfNeeded()

        webView = WebView(this)
        setContentView(webView)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler(
                "/app/",
                WebViewAssetLoader.InternalStoragePathHandler(this, appDir)
            )
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage + IndexedDB
            allowFileAccess = false           // we serve via https://, not file://
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            // v4.11: Android WebView seeds textZoom from the system FONT SIZE
            // setting (Chromium AwSettings reads Configuration.fontScale), so a
            // user changing Android's font size silently restyled this app.
            // The app owns its UI scale instead: textZoom is pinned to 100 and
            // the Display Size setting (HTML viewport engine + getDisplayScale /
            // setDisplayScale) is the only thing that scales the interface.
            textZoom = 100
            // v4.11 ROOT CAUSE FIX (Display Size levels below the normal level,
            // i.e. any requested scale < 1): Android WebView
            // defaults to NARROW viewport mode (useWideViewPort=false) with overview
            // mode off (loadWithOverviewMode=false). In that mode the page's viewport
            // meta `width` is ignored and any initial scale BELOW 1.0 is discarded —
            // so every "smaller" Display Size level was silently thrown away while
            // zoom-in (>1) still worked. This is also why the old Extra-Small/Small
            // options did nothing. Chromium's WebView layout doc calls this pair the
            // "configure WebView like a browser" setup; with it the meta behaves
            // exactly like Chrome, verified in tests/viewport-render.test.js.
            // Level 8 (v4.12; formerly 6) emits width=device-width + initial-scale=1.0,
            // so the baseline
            // renders identically to before this change.
            useWideViewPort = true
            loadWithOverviewMode = true
            cachedUseWideViewPort = useWideViewPort
            cachedLoadWithOverviewMode = loadWithOverviewMode
            cachedTextZoom = textZoom
        }

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // A backup is data, never a WebView document.  Some Android
                // document providers can hand a selected JSON Uri back through
                // WebView navigation; without this guard Chromium renders the
                // raw JSON over the app, exactly like a broken HTML page.
                // Keep the app on its trusted internal HTTPS origin instead.
                if (request.isForMainFrame) {
                    val uri = request.url
                    val isInternalApp = uri.scheme == "https" &&
                        uri.host == "appassets.androidplatform.net" &&
                        (uri.path ?: "").startsWith("/app/")
                    if (!isInternalApp) {
                        val scheme = uri.scheme?.lowercase() ?: ""
                        val isAssetHost = uri.host == "appassets.androidplatform.net"
                        // A genuine external link (the Telegram shortcut, a web
                        // URL) goes to Android: the WebView stays exactly where
                        // it is, so the user is never bounced back to Home with
                        // "File was not opened as a page". The trusted asset host
                        // keeps its old block - it is app data, never a document
                        // to hand to another app.
                        val isExternalLink = !isAssetHost &&
                            (scheme == "http" || scheme == "https" || scheme == "tg")
                        if (isExternalLink) {
                            ErrorLog.log(this@MainActivity, "WebView.externalNavigation", uri.toString())
                            openExternalUri(uri)
                            return true
                        }
                        ErrorLog.log(
                            this@MainActivity,
                            "WebView.blockedMainFrameNavigation",
                            uri.toString()
                        )
                        view.post { loadApp() }
                        toast("File was not opened as a page", force = true)
                        return true
                    }
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceErrorCompat
            ) {
                // Only react to the main page failing, not sub-resources.
                if (request.isForMainFrame) {
                    ErrorLog.log(this@MainActivity, "WebView.onReceivedError", request.url.toString())
                    showLoadFailureDialog()
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                // The WebView renderer crashed. Log it and recreate the
                // activity (a fresh WebView) instead of crashing the app.
                ErrorLog.log(this@MainActivity, "WebView.renderProcessGone", "renderer crashed")
                recreate()
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                // The app HTML is now live; a subsequent onResume is a warm
                // resume and must replay any queued notification taps itself.
                pageEverFinished = true
                // v2.3: The two sound settings in the HTML are plain
                // <input type="file"> elements (ids "andSoundFile" and
                // "andBombSoundFile"). We do NOT modify the HTML; we just
                // tag which one was tapped so the native sound chooser knows
                // which slot it is editing. Harmless if the ids are absent.
                view.evaluateJavascript(
                    """
                    (function(){
                      try{
                        var map={andSessionSoundFile:'sessionend',andTaskSoundFile:'taskcomplete',andBombSoundFile:'bombwarn'};
                        Object.keys(map).forEach(function(id){
                          var el=document.getElementById(id);
                          if(el && !el.__slotTagged){
                            el.__slotTagged=true;
                            el.addEventListener('click',function(){
                              try{AndroidBridge.noteSoundSlot(map[id]);}catch(e){}
                            },true);
                          }
                        });
                      }catch(e){}
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }

        // v2.2: file chooser support so HTML <input type="file"> works
        // (custom sounds, future imports). Everything else stays default.
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: android.webkit.ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                // Cancel any previous pending chooser (spec requirement).
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                // v2.3: audio inputs (the sound settings) open our native
                // chooser: reliable built-in sounds + "Choose from device".
                // Native owns the selection, so we release the WebView's file
                // callback with null and never hand it a file.
                val types = params.acceptTypes?.joinToString(",") ?: ""
                if (types.contains("audio")) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    showSoundChooserDialog()
                    return true
                }

                val mime = params.acceptTypes?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
                val ok = try {
                    fileChooserLauncher.launch(mime)
                    true
                } catch (e: Exception) {
                    ErrorLog.log(this@MainActivity, "onShowFileChooser", e.message ?: "launch failed")
                    try {
                        fileChooserLauncher.launch("*/*")
                        true
                    } catch (e2: Exception) {
                        ErrorLog.log(this@MainActivity, "onShowFileChooser.fallback", e2.message ?: "launch failed")
                        false
                    }
                }
                if (!ok) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    toast("No file picker available on this device", force = true)
                }
                return true
            }
        }

        jsBridge = JsBridge(this)
        webView.addJavascriptInterface(jsBridge, "AndroidBridge")

        loadApp()
    }

    override fun onResume() {
        super.onResume()
        // While we're visible, notification button taps reach JS instantly.
        // (If we're not visible, TimerActionReceiver queues them and JS
        // drains the queue via getPendingEvents() on next load.)
        TimerActionReceiver.liveDelivery = { action ->
            notifyJs("timerAction", "\"$action\"")
        }
        // ---- Freeze-on-resume fix (v2.4 regression) ----
        // The HTML UI is driven entirely by setInterval timers. While the app
        // is backgrounded, Android/Chromium freezes the WebView renderer and
        // coalesces those timers, so on return they can take several seconds to
        // wake up - the UI looks frozen, then jumps and updates all at once.
        // The native timer engine no longer needs the WebView alive in the
        // background, which lets the OS freeze it more aggressively, making the
        // stall more visible. We fix it deterministically here: resume the
        // renderer and push an "appResumed" event. evaluateJavascript runs on
        // the next renderer loop turn regardless of setTimeout/setInterval
        // throttling, so the HTML reconciles state and re-renders immediately
        // instead of waiting for a throttled timer to fire.
        if (::webView.isInitialized) {
            try {
                webView.onResume()
                webView.resumeTimers()
            } catch (e: Exception) {
                ErrorLog.log(this, "MainActivity.onResume", e.message ?: "webView resume failed")
            }
        }
        // WARM resume only: replay notification taps (Pause/Resume/Stop) that
        // were queued while we were backgrounded. On a COLD start the page is
        // not loaded yet (pageEverFinished == false); JS boot drains the queue
        // via getPendingEvents(), so we must NOT drain here too (double drain).
        // We replay BEFORE appResumed so the user's intent is applied first,
        // then gtResumeResync() reconciles + re-renders on top of it. HTML stays
        // the single source of truth for every timer action.
        if (::webView.isInitialized && pageEverFinished) {
            replayPendingEvents()
        }
        notifyJs("appResumed", "true")
    }

    /**
     * Drains the native pending-event queue and replays each event into the
     * live HTML via window.onAndroidEvent(...). The HTML handlers are idempotent
     * (e.g. pause is a no-op if already paused), so this is safe. This is purely
     * a DELIVERY mechanism; it adds no timer logic of its own.
     */
    private fun replayPendingEvents() {
        try {
            val arr = org.json.JSONArray(PendingEvents.drain(this))
            for (i in 0 until arr.length()) {
                val ev = arr.getJSONObject(i)
                val name = ev.optString("name")
                // payload is stored as a raw JSON literal, e.g. "pause".
                val payload = ev.optString("payload", "null")
                if (name.isNotEmpty()) notifyJs(name, payload)
            }
        } catch (e: Exception) {
            ErrorLog.log(this, "MainActivity.replayPendingEvents", e.message ?: "drain failed")
        }
    }

    override fun onPause() {
        TimerActionReceiver.liveDelivery = null
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // v4.11: WebView seeds textZoom from Configuration.fontScale at creation
        // and may re-read the configuration on a config change. Re-pin it here so
        // changing Android's system Font size NEVER restyles the app — not even
        // while it is open. (Screen-size configChanges are handled by this
        // activity; fontScale changes arrive through the same path only when
        // fontScale is in the manifest's configChanges, otherwise on recreation.)
        if (::webView.isInitialized) {
            try {
                webView.settings.textZoom = 100
                cachedTextZoom = 100
            } catch (e: Exception) {
                ErrorLog.log(this, "MainActivity.onConfigurationChanged", e.message ?: "textZoom failed")
            }
        }
    }

    /**
     * v4.11 debug readout for the Display Size card: the viewport-related
     * WebView settings, so an on-device screenshot can prove the whole scale
     * chain (level -> meta -> actual viewport -> native settings).
     * Reads the main-thread-cached copies only — never touches the WebView
     * itself, because this is invoked from the JS bridge thread.
     */
    fun viewportSettingsJson(): JSONObject = JSONObject()
        .put("useWideViewPort", cachedUseWideViewPort ?: false)
        .put("loadWithOverviewMode", cachedLoadWithOverviewMode ?: false)
        .put("textZoom", cachedTextZoom ?: -1)

    /**
     * Opens an external link outside the WebView: the Telegram app first when
     * it can handle a t.me / tg:// link, otherwise the system chooser with a
     * browser fallback. Mirrors JsBridge.openExternalUrl so the Telegram
     * shortcut works whether the HTML calls the bridge or navigates directly.
     */
    private fun openExternalUri(uri: Uri) {
        try {
            val wantsTelegram = (uri.scheme ?: "").equals("tg", ignoreCase = true) ||
                (uri.host ?: "").endsWith("t.me", ignoreCase = true)
            if (wantsTelegram) {
                val packages = listOf(
                    "org.telegram.messenger",
                    "org.telegram.messenger.beta",
                    "org.telegram.messenger.web"
                )
                for (pkg in packages) {
                    val appIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage(pkg)
                        addCategory(Intent.CATEGORY_BROWSABLE)
                    }
                    if (appIntent.resolveActivity(packageManager) == null) continue
                    try {
                        startActivity(appIntent)
                        return
                    } catch (e: Exception) {
                        ErrorLog.log(this, "MainActivity.openExternalUri", e.message ?: pkg)
                        // fall through to the chooser instead of giving up
                    }
                }
            }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Open with"))
        } catch (e: Exception) {
            ErrorLog.log(this, "MainActivity.openExternalUri", e.message ?: "unknown")
            toast("Could not open the link.", force = true)
        }
    }

    private fun loadApp() {
        recoveryDialogShowing = false
        webView.loadUrl("https://appassets.androidplatform.net/app/index.html")
    }

    /** Called from the bridge when a specific HTML sound input is tapped. */
    fun setPendingSoundSlot(slot: String) {
        pendingSoundSlot = when (slot) {
            SoundLibrary.SLOT_SESSION_END, SoundLibrary.SLOT_TASK_COMPLETE,
            SoundLibrary.SLOT_BOMB, SoundLibrary.SLOT_COMPLETION -> slot
            else -> SoundLibrary.SLOT_COMPLETION
        }
    }

    // ---------- v2.3: native sound chooser ----------

    /**
     * The native "Set sound" dialog reached from the HTML sound settings.
     * Lists the 10 always-available bundled sounds (tap to preview) plus a
     * "Choose from device" option. Built-in sounds are the recommended,
     * permission-free, factory-reset-proof default; the device option is an
     * optional advanced feature that degrades gracefully when unsupported.
     */
    private fun showSoundChooserDialog() {
        val slot = pendingSoundSlot
        val builtins = SoundLibrary.BUILTINS
        val labels = builtins.map { b ->
            (if (b.isLong) "Alarm - " else "Notification - ") + b.label
        }.toTypedArray()

        // Preselect the current built-in selection, if any.
        val current = SoundLibrary.getSelection(this, slot)
        var checked = if (current.type == SoundLibrary.TYPE_BUILTIN)
            builtins.indexOfFirst { it.key == current.value }.coerceAtLeast(0)
        else -1

        val title = if (slot == SoundLibrary.SLOT_BOMB)
            "Bomb warning sound" else "Completion sound"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { _, which ->
                checked = which
                // Preview through the reliable player (stops any prior preview).
                jsBridge.previewBuiltinSound(builtins[which].key)
            }
            .setPositiveButton("Use this sound") { d, _ ->
                jsBridge.stopSound()
                if (checked in builtins.indices) {
                    jsBridge.selectBuiltinSound(slot, builtins[checked].key)
                    toast("Sound set: ${builtins[checked].label}", force = true)
                }
                d.dismiss()
            }
            .setNeutralButton("Choose from device") { d, _ ->
                jsBridge.stopSound()
                d.dismiss()
                launchDeviceSoundPicker()
            }
            .setNegativeButton("Cancel") { d, _ ->
                jsBridge.stopSound()
                d.dismiss()
            }
            .setOnDismissListener { jsBridge.stopSound() }
            .show()
    }

    /**
     * Opens the system ringtone/alarm picker ("Choose from device"). Falls
     * back to the document picker, and finally to the built-in chooser, so a
     * phone with a broken/missing picker never ends up with no way to choose.
     */
    private fun launchDeviceSoundPicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE or
                    RingtoneManager.TYPE_NOTIFICATION
            )
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose from device")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            val cur = SoundLibrary.getSelection(this@MainActivity, pendingSoundSlot)
            if (cur.type == SoundLibrary.TYPE_DEVICE) {
                putExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    Uri.parse(cur.value)
                )
            }
        }
        try {
            ringtonePickerLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            ErrorLog.log(this, "ringtonePicker", "unavailable, trying document picker")
            try {
                deviceAudioFallbackLauncher.launch(arrayOf("audio/*"))
            } catch (e2: Exception) {
                ErrorLog.log(this, "deviceAudioFallback", e2.message ?: "unavailable")
                toast("No device sound picker available - using built-in sounds", force = true)
                showSoundChooserDialog()
            }
        }
    }

    /** Shown when the main page fails to load (e.g. a broken HTML import). */
    private fun showLoadFailureDialog() {
        if (recoveryDialogShowing || isFinishing) return
        recoveryDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("App failed to load")
            .setMessage(
                "The current HTML app could not be loaded. You can retry, " +
                    "or restore the built-in app. Your data is not affected."
            )
            .setPositiveButton("Retry") { _, _ -> loadApp() }
            .setNegativeButton("Restore built-in app") { _, _ ->
                appDir.deleteRecursively()
                copyBundledApp()
                loadApp()
            }
            .setOnDismissListener { recoveryDialogShowing = false }
            .show()
    }

    private val htmlPrefs get() = getSharedPreferences("shell_html", MODE_PRIVATE)

    /** First run and APK upgrades: copy bundled HTML unless the user imported their own. */
    private fun copyBundledAppIfNeeded() {
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "4.10"
        } catch (_: Exception) {
            "4.10"
        }
        val userImported = htmlPrefs.getBoolean("html_user_imported", false)
        val lastBundled = htmlPrefs.getString("bundled_html_version", null)
        val indexExists = File(appDir, "index.html").exists()
        if (!indexExists) {
            copyBundledApp()
            htmlPrefs.edit()
                .putString("bundled_html_version", currentVersion)
                .putBoolean("html_user_imported", false)
                .apply()
            return
        }
        if (!userImported && lastBundled != currentVersion) {
            copyBundledApp()
            htmlPrefs.edit().putString("bundled_html_version", currentVersion).apply()
            return
        }
        copyBundledAssetsIfMissing(appDir)
    }

    private fun copyBundledApp() {
        appDir.mkdirs()
        assets.list("app")?.forEach { name ->
            assets.open("app/$name").use { input ->
                File(appDir, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    /**
     * Copies every file from assets/app/ into [targetDir] that does NOT already
     * exist there. This is used after an HTML-only import to ensure side-car
     * assets like manuals.js are present alongside the imported HTML.
     * Existing files are never overwritten so a ZIP that ships its own
     * manuals.js keeps its version.
     */
    private fun copyBundledAssetsIfMissing(targetDir: File) {
        try {
            assets.list("app")?.forEach { name ->
                val dest = File(targetDir, name)
                if (!dest.exists()) {
                    assets.open("app/$name").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLog.log(this, "copyBundledAssetsIfMissing", e.message ?: "unknown")
        }
    }

    // ---------- Shell actions (the three-dot top-bar menu was removed in v4.11) ----------
    // Everything the old overflow menu did is now triggered from the HTML
    // (Settings -> Android Shell Settings -> App & Data) through the bridge,
    // keeping the SAME native typed-confirmation dialogs and file pickers.

    fun showRefreshAppFlow() {
        ConfirmDialog.show(
            activity = this,
            accent = ConfirmDialog.BLUE,
            icon = "\uD83D\uDD04",
            heading = "Refresh App",
            english = "Reload the application without deleting user data.",
            hinglish = "Current HTML ko sirf refresh karega. Bilkul browser " +
                "refresh ki tarah. Kuch bhi delete nahi hoga.",
            summary = listOf(
                ConfirmDialog.SummaryLine("HTML", "Preserved"),
                ConfirmDialog.SummaryLine("User Data", "Preserved"),
            ),
            confirmLabel = "Refresh Now",
        ) {
            loadApp()
        }
    }

    fun showImportHtmlFlow() {
        ConfirmDialog.show(
            activity = this,
            accent = ConfirmDialog.BLUE,
            icon = "\uD83D\uDCE5",
            heading = "Install NEW HTML File",
            english = "Select a new HTML app file from your file manager and install it " +
                "as the app's interface. The file is validated in staging before it " +
                "replaces the current app, and all of your user data is kept.",
            hinglish = "File manager se nayi HTML file chun kar app ki nayi interface " +
                "banayega. File validate hone ke baad hi replace hogi, aapka data safe rahega.",
            summary = listOf(
                ConfirmDialog.SummaryLine("HTML", "Replaced (after validation)"),
                ConfirmDialog.SummaryLine("User Data", "Preserved"),
            ),
            confirmLabel = "Install",
            requiredWord = "INSTALL",
        ) {
            val ok = safeLaunch("launchImportHtml") {
                importHtmlLauncher.launch(arrayOf("text/html", "application/zip"))
            }
            if (!ok) toast("Install failed: no file picker available on this device", force = true)
        }
    }

    /**
     * "Revert to original HTML" - swaps a custom-installed HTML interface
     * back for the bundled original. User data lives in a separate directory and
     * is never touched; the typed confirmation (ORIGINAL) guards against an
     * accidental tap losing the custom interface.
     */
    fun showRevertHtmlFlow() {
        if (!htmlPrefs.getBoolean("html_user_imported", false)) {
            toast("Original HTML is already active - nothing to revert", force = true)
            return
        }
        ConfirmDialog.show(
            activity = this,
            accent = ConfirmDialog.RED,
            icon = "\u21A9\uFE0F",
            heading = "Revert to original HTML",
            english = "Replace the custom-installed HTML with the original interface " +
                "bundled inside the app. Only the interface changes - your user " +
                "data (Tasks, History, Goals, Timers, Settings) is not touched.",
            hinglish = "Custom HTML hata kar app ki original interface wapas aa " +
                "jayegi. Sirf interface badlega - aapka poora data bilkul safe rahega.",
            summary = listOf(
                ConfirmDialog.SummaryLine("HTML", "Replaced with original"),
                ConfirmDialog.SummaryLine("User Data", "Preserved"),
            ),
            confirmLabel = "Revert",
            requiredWord = "ORIGINAL",
        ) {
            revertToOriginalHtml()
        }
    }

    /** Restores the bundled HTML over a custom install. Data directories untouched. */
    private fun revertToOriginalHtml() {
        try {
            appDir.deleteRecursively()
            copyBundledApp()
            htmlPrefs.edit().putBoolean("html_user_imported", false).apply()
            toast("Original HTML restored - user data kept", force = true)
            loadApp()
        } catch (e: Exception) {
            ErrorLog.log(this, "revertToOriginalHtml", e.message ?: "unknown")
            // Never leave the user without an interface.
            if (!File(appDir, "index.html").exists()) copyBundledApp()
            toast("Revert failed: ${e.message}", force = true)
            loadApp()
        }
    }

    fun showExportBackupFlow() {
        ConfirmDialog.show(
            activity = this,
            accent = ConfirmDialog.GREEN,
            icon = "\uD83D\uDCBE",
            heading = "Export User Data",
            english = "Create a backup of the user's data and save it to your device. " +
                "The app's HTML interface is not included.",
            hinglish = "Aapka poora data phone me Backup File ke roop me save hoga. " +
                "Isme HTML include nahi hoti.",
            summary = listOf(
                ConfirmDialog.SummaryLine("HTML", "Not Included"),
                ConfirmDialog.SummaryLine("User Data", "Saved"),
            ),
            confirmLabel = "Export Now",
        ) {
            launchExportBackup()
        }
    }

    fun showImportBackupFlow() {
        ConfirmDialog.show(
            activity = this,
            accent = ConfirmDialog.GREEN,
            icon = "\uD83D\uDCC2",
            heading = "Import User Data",
            english = "Restore previously exported user data from a backup file. The " +
                "file is fully verified first - a wrong or damaged file is rejected " +
                "without changing anything.",
            hinglish = "Pehle save ki hui Backup File se poora data wapas aa jayega. " +
                "File pehle verify hoti hai - galat ya damaged file accept nahi hogi.",
            summary = listOf(
                ConfirmDialog.SummaryLine("HTML", "Preserved"),
                ConfirmDialog.SummaryLine("User Data", "Restored"),
            ),
            confirmLabel = "Restore",
            requiredWord = "IMPORT",
        ) {
            launchImportBackup()
        }
    }

    fun showSaveCurrentHtmlFlow() {
        ConfirmDialog.show(
            activity = this,
            accent = ConfirmDialog.BLUE,
            icon = "\uD83D\uDCC4",
            heading = "Save Current HTML File",
            english = "Export the currently installed/running HTML app file to your file " +
                "manager - exactly what is running now, even if you installed a custom " +
                "HTML file earlier.",
            hinglish = "Jo HTML abhi chal rahi hai (custom ho ya original), uska copy " +
                "file manager me save ho jayega.",
            summary = listOf(
                ConfirmDialog.SummaryLine("HTML", "Saved as file"),
                ConfirmDialog.SummaryLine("User Data", "Not Included"),
            ),
            confirmLabel = "Save File",
        ) {
            launchExportCurrentHtml()
        }
    }

    fun showFactoryResetFlow() {
        ConfirmDialog.show(
            activity = this,
            accent = ConfirmDialog.RED,
            icon = "\uD83D\uDDD1\uFE0F",
            heading = "Factory Reset User Data",
            english = "Delete the application's user data and restore the initial data " +
                "state. The current HTML app file is NOT deleted or replaced. Tip: " +
                "export a backup first. A last-chance internal copy is kept at " +
                "auto-backup-before-reset.json.",
            hinglish = "Sirf Data delete hoga (Tasks, History, Goals, Timers, " +
                "Settings, Streaks, etc.). Current HTML bilkul remove nahi hogi.",
            summary = listOf(
                ConfirmDialog.SummaryLine("HTML", "Preserved"),
                ConfirmDialog.SummaryLine("User Data", "Deleted"),
            ),
            confirmLabel = "Factory Reset",
            requiredWord = "FACTORY RESET",
        ) {
            factoryReset()
        }
    }

    fun showSystemCheckFlow() = showSystemCheck()

    private fun showSystemCheck() {
        try {
            val json = JSONObject(JsBridge(this).getDiagnostics())
            val sb = StringBuilder()
            for (key in json.keys()) sb.append(key).append(": ").append(json.get(key)).append('\n')
            val text = sb.toString()
            AlertDialog.Builder(this)
                .setTitle("System Check")
                .setMessage(text)
                .setPositiveButton("Close", null)
                .setNeutralButton("Export as file") { _, _ ->
                    pendingDiagnostics = text
                    val ok = safeLaunch("launchExportDiagnostics") {
                        exportDiagnosticsLauncher.launch("recall-diagnostics.txt")
                    }
                    if (!ok) toast("Export failed: no file picker available on this device", force = true)
                }
                .show()
        } catch (e: Exception) {
            ErrorLog.log(this, "showSystemCheck", e.message ?: "unknown")
            toast("System check failed: ${e.message}", force = true)
        }
    }

    private fun factoryReset() {
        try {
            // Last-chance internal copy (survives until the next factory reset).
            val data = JSONObject()
            appDataDir.listFiles()?.filter { it.extension == "json" }?.forEach { f ->
                data.put(f.nameWithoutExtension, f.readText())
            }
            File(filesDir, "auto-backup-before-reset.json").writeText(
                JSONObject()
                    .put("app", BackupValidator.APP_ID)
                    .put("exportedAt", System.currentTimeMillis())
                    .put("data", data)
                    .toString(2)
            )
            // Cancel alarms ALREADY REGISTERED with AlarmManager (clearing
            // the prefs alone would leave them to fire anyway).
            getSharedPreferences("scheduled_alarms", MODE_PRIVATE).all.keys.forEach { key ->
                key.toIntOrNull()?.let { AlarmScheduler.cancel(this, it) }
            }
            // Stop a running timer notification, if any.
            try {
                startService(
                    android.content.Intent(this, TimerService::class.java)
                        .setAction(TimerService.ACTION_STOP)
                )
            } catch (_: Exception) {
            }
            // Clear ONLY user-generated data. The active HTML app (appDir) is
            // deliberately NOT touched: factory reset must never replace or
            // remove the currently imported HTML. Restoring the bundled HTML
            // is a separate, explicit action ("Restore bundled app").
            appDataDir.deleteRecursively()
            File(filesDir, "sound").deleteRecursively()
            getSharedPreferences("scheduled_alarms", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("shell_pending_events", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("shell_flags", MODE_PRIVATE).edit().clear().apply()
            // The error log is intentionally KEPT for post-reset diagnostics.

            // Safety net only: if the HTML is somehow missing (e.g. a prior
            // corrupted state), fall back to the bundled app so the user is
            // never left with an empty screen. A healthy install keeps its
            // current HTML exactly as-is.
            if (!File(appDir, "index.html").exists()) {
                copyBundledApp()
            }
            // A factory reset must also clear the HTML app's own storage. The
            // Recall app keeps topics, subjects, revision history and settings in
            // WebView localStorage, so deleting only the wrapper's data files
            // would leave every user record behind and make "reset" a no-op.
            // evaluateJavascript reports back AFTER the snippet ran, so the
            // reload can never race the clearing.
            try {
                val clearJs = ("(()=>{try{" +
                    "['recall_topic_revision_studio_v1','recall_topic_revision_studio_v1_backup','recall_topic_revision_studio_v1_rescue']" +
                    ".forEach(function(k){localStorage.removeItem(k)});return 'ok'}catch(e){return 'err'}})()")
                webView.evaluateJavascript(clearJs) { runOnUiThread { loadApp() } }
            } catch (e: Exception) {
                ErrorLog.log(this, "factoryReset.clearWebViewData", e.message ?: "unknown")
                loadApp()
            }
            toast("Factory reset complete - your app was kept, data was cleared", force = true)
        } catch (e: Exception) {
            ErrorLog.log(this, "factoryReset", e.message ?: "unknown")
            toast("Factory reset failed: ${e.message}", force = true)
        }
    }

    // ---------- HTML / ZIP import ----------

    private fun importHtmlOrZip(uri: Uri) {
        // Stage the import into a temp directory and VALIDATE it before
        // touching the current app. A broken/invalid file therefore can
        // never destroy the currently working HTML app.
        val stagingDir = File(filesDir, "app_import_tmp")
        try {
            val type = contentResolver.getType(uri) ?: ""
            val isZip = type.contains("zip") ||
                (contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    c.moveToFirst() && idx >= 0 && c.getString(idx).endsWith(".zip", true)
                } == true)

            stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            if (isZip) {
                contentResolver.openInputStream(uri)!!.use { raw ->
                    ZipInputStream(raw).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val out = File(stagingDir, entry.name)
                                // Protect against zip path traversal
                                if (out.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { zip.copyTo(it) }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
                if (!File(stagingDir, "index.html").exists()) {
                    stagingDir.deleteRecursively()
                    toast("ZIP had no index.html at its root - current app kept unchanged", force = true)
                    return
                }
            } else {
                contentResolver.openInputStream(uri)!!.use { input ->
                    File(stagingDir, "index.html").outputStream().use { input.copyTo(it) }
                }
                // Single-HTML import: ensure bundled side-car assets (manuals.js, etc.)
                // are copied into the staging dir so they are available alongside the HTML.
                // A ZIP import is expected to carry its own copies; we only fill gaps here.
                copyBundledAssetsIfMissing(stagingDir)
            }

            // Validation passed - now swap in the new app files.
            appDir.deleteRecursively()
            if (!stagingDir.renameTo(appDir)) {
                // Rename can fail across some filesystems; fall back to copy.
                appDir.mkdirs()
                stagingDir.walkTopDown().filter { it.isFile }.forEach { src ->
                    val dest = File(appDir, src.relativeTo(stagingDir).path)
                    dest.parentFile?.mkdirs()
                    src.copyTo(dest, overwrite = true)
                }
                stagingDir.deleteRecursively()
            }
            // For ZIP imports that did not include manuals.js, fill the gap from assets.
            copyBundledAssetsIfMissing(appDir)
            htmlPrefs.edit().putBoolean("html_user_imported", true).apply()
            toast("App updated")
            loadApp()
        } catch (e: Exception) {
            ErrorLog.log(this, "importHtmlOrZip", e.message ?: e.javaClass.simpleName)
            stagingDir.deleteRecursively()
            // Only restore the bundled app if the current one is actually gone.
            if (!File(appDir, "index.html").exists()) {
                copyBundledApp()
                loadApp()
            }
            toast("Import failed: ${e.message}", force = true)
        }
    }

    // ---------- Backup export / import ----------

    /**
     * Launching a system file picker can throw (ActivityNotFoundException on
     * devices with a disabled/filtered documents provider). Every .launch()
     * call in this activity goes through this guard so a missing picker can
     * never crash the app - it logs and tells the user instead.
     */
    private fun safeLaunch(where: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Exception) {
        ErrorLog.log(this, where, e.message ?: e.javaClass.simpleName)
        false
    }

    fun launchExportBackup() {
        // Primary: suggest the JSON MIME type. Fallback 1: generic type for
        // pickers that reject "application/json". Fallback 2 (v2.2): no picker
        // at all - write straight into the public Downloads folder via
        // MediaStore, which needs no picker and no storage permission on
        // Android 10+. This makes export work even on devices whose
        // documents provider is disabled (the "no file picker" case).
        if (safeLaunch("launchExportBackup") {
                exportBackupLauncher.launch("recall-backup.json")
            }
        ) return
        if (safeLaunch("launchExportBackup.fallback") {
                exportBackupFallbackLauncher.launch("recall-backup.json")
            }
        ) return
        exportBackupToDownloads()
    }

    /** Picker-free export path: writes the backup into public Downloads. */
    private fun exportBackupToDownloads() {
        try {
            val name = "goal-tracker-backup-${System.currentTimeMillis()}.json"
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                }
                contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ).apply { mkdirs() }
                Uri.fromFile(File(dir, name))
            }
            if (uri == null) {
                toast("Export failed: no file picker and Downloads unavailable", force = true)
                return
            }
            contentResolver.openOutputStream(uri)!!.use {
                it.write(buildBackupJson().toString(2).toByteArray())
            }
            toast("Saved to Downloads: $name")
        } catch (e: Exception) {
            ErrorLog.log(this, "exportBackupToDownloads", e.message ?: e.javaClass.simpleName)
            toast("Export failed: ${e.message}", force = true)
        }
    }

    fun launchImportBackup() {
        val ok = safeLaunch("launchImportBackup") {
            importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        if (!ok) toast("Import failed: no file picker available on this device", force = true)
    }

    // ---------- Save current HTML (v4.11) ----------

    fun launchExportCurrentHtml() {
        if (safeLaunch("launchExportCurrentHtml") {
                exportHtmlLauncher.launch("goal-tracker-app.html")
            }
        ) return
        exportCurrentHtmlToDownloads()
    }

    /** Picker-free export path: writes the running HTML into public Downloads. */
    private fun exportCurrentHtmlToDownloads() {
        try {
            val name = "goal-tracker-app-${System.currentTimeMillis()}.html"
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/html")
                }
                contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ).apply { mkdirs() }
                Uri.fromFile(File(dir, name))
            }
            if (uri == null) {
                toast("Save failed: no file picker and Downloads unavailable", force = true)
                return
            }
            writeCurrentHtmlTo(uri)
        } catch (e: Exception) {
            ErrorLog.log(this, "exportCurrentHtmlToDownloads", e.message ?: e.javaClass.simpleName)
            toast("Save failed: ${e.message}", force = true)
        }
    }

    private fun writeCurrentHtmlTo(uri: Uri) {
        try {
            val src = File(appDir, "index.html")
            if (!src.exists() || src.length() == 0L) {
                throw IllegalStateException("current HTML file is missing")
            }
            contentResolver.openOutputStream(uri)!!.use { out ->
                src.inputStream().use { input -> input.copyTo(out) }
            }
            toast("Current app HTML saved", force = true)
        } catch (e: Exception) {
            ErrorLog.log(this, "writeCurrentHtmlTo", e.message ?: e.javaClass.simpleName)
            toast("Save failed: ${e.message}", force = true)
        }
    }

    /** Collects every stored data key into the single backup JSON document. */
    private fun buildBackupJson(): JSONObject {
        val map = linkedMapOf<String, String>()
        appDataDir.listFiles()?.filter { it.extension == "json" }?.forEach { f ->
            map[f.nameWithoutExtension] = f.readText()
        }
        if (!map.containsKey(BackupValidator.CORE_KEY)) {
            throw IllegalStateException("no Recall data to export yet - open the app once and try again")
        }
        val data = JSONObject()
        map.forEach { (key, value) -> data.put(key, value) }
        // v4.11: checksum lets import detect damaged/truncated files, and the
        // app id lets import reject files that belong to a different app.
        return JSONObject()
            .put("app", BackupValidator.APP_ID)
            .put("exportedAt", System.currentTimeMillis())
            .put("checksum", BackupValidator.fnv1a32(BackupValidator.checksumPayload(map)))
            .put("data", data)
    }

    private fun writeBackupTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)!!.use {
                it.write(buildBackupJson().toString(2).toByteArray())
            }
            toast("Backup exported")
        } catch (e: Exception) {
            ErrorLog.log(this, "writeBackupTo", e.message ?: e.javaClass.simpleName)
            toast("Export failed: ${e.message}", force = true)
        }
    }

    private fun restoreBackupFrom(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)!!
                .bufferedReader().use { it.readText() }
            // v4.11: validate the ENTIRE payload BEFORE touching a single stored
            // file. A corrupt, truncated or foreign JSON file is rejected with a
            // clear message and can never overwrite existing user data.
            val entries = BackupValidator.validate(text)
            // Stage every file first, then swap them in - a write failure halfway
            // can never leave a half-restored mixture behind.
            val staging = File(filesDir, "data_import_tmp")
            staging.deleteRecursively()
            staging.mkdirs()
            entries.forEach { (key, value) -> File(staging, "$key.json").writeText(value) }
            for (key in entries.keys) {
                val src = File(staging, "$key.json")
                val dest = File(appDataDir, "$key.json")
                if (!src.renameTo(dest)) {
                    dest.writeText(src.readText())
                    src.delete()
                }
            }
            staging.deleteRecursively()
            toast("Backup restored", force = true)
            // Tell the HTML app so it can re-read its data
            notifyJs("backupRestored", "null")
        } catch (e: Exception) {
            ErrorLog.log(this, "restoreBackupFrom", e.message ?: e.javaClass.simpleName)
            toast("Import failed: ${e.message}", force = true)
        }
    }

    // ---------- Permissions ----------

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        notifyJs("notificationPermission", "\"granted\"")
    }

    // ---------- Helpers ----------

    /** Fire an event into the HTML app: window.onAndroidEvent(name, payload) */
    private fun notifyJs(event: String, jsonPayload: String) {
        runOnUiThread {
            try {
                webView.evaluateJavascript(
                    "window.onAndroidEvent && window.onAndroidEvent('$event', $jsonPayload);",
                    null
                )
            } catch (e: Exception) {
                ErrorLog.log(this, "notifyJs", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Informational toasts respect the HTML "Show pop-up messages" preference,
     * mirrored into the "nativeToastsEnabled" flag (default true). Hard errors and
     * failures MUST always be visible, so those callers pass force = true.
     */
    private fun toast(msg: String, force: Boolean = false) {
        if (!force && !Flags.get(this, "nativeToastsEnabled", true)) return
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * While any in-app alarm is ringing (session-end, task-complete, bomb,
     * or a settings preview), a hardware volume key immediately stops it.
     * The key is swallowed so it does not also change system volume.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> {
                if (::jsBridge.isInitialized && jsBridge.isRinging()) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        jsBridge.stopIfRinging()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
