package com.yolotouchhelp.aimbot.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import com.yolotouchhelp.aimbot.R
import com.yolotouchhelp.aimbot.inference.JniCallBack
import com.yolotouchhelp.aimbot.manager.ConfigManager
import com.yolotouchhelp.aimbot.service.FloatService
import com.yolotouchhelp.aimbot.util.ProjectionHolder
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    enum class YoloTouchHelpState { STANDBY, RUNNING, INFERENCING }
    private data class HtmlDialogHandle(val dialog: AlertDialog, val webView: WebView)

    data class ModelInfo(
        val filename: String,
        val displayName: String,
        val precision: String,
        val inputSize: Int,
        val outputSize: Int,
        val description: String,
        val classes: Map<Int, String> = emptyMap()
    )

    companion object {
        private const val REQ_SHIZUKU = 10001
        const val ACTION_STATE_CHANGE = "com.yolotouchhelp.aimbot.STATE_CHANGE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MODEL_NAME = "model_name"
        private const val GITHUB_URL = "https://github.com/DreamFekk/YoloTouchHelp"
        private const val QQ_GROUP_NUMBER = "977186929"
    }

    private val stateListener: (Int, String) -> Unit = { state, modelName ->
        runOnUiThread { setAppState(YoloTouchHelpState.entries[state], modelName) }
    }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQ_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) {
            runOnUiThread {
                updatePermissionStates()
                syncPageState()
            }
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        ProjectionHolder.resultCode = result.resultCode
        ProjectionHolder.resultData = data
        ProjectionHolder.modelList = modelList.map { model ->
            ProjectionHolder.ModelEntry(
                model.filename,
                model.displayName,
                model.precision,
                model.inputSize,
                model.outputSize,
                model.description,
                model.classes
            )
        }
        ProjectionHolder.selectedModelIndex = selectedModelIndex
        startForegroundService(Intent(this, FloatService::class.java))
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { ConfigManager.exportToUri(this, it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { ConfigManager.importFromUri(this, it) }
    }

    private val displayDensity: Float by lazy { resources.displayMetrics.density }

    private var modelList: List<ModelInfo> = emptyList()
    private var selectedModelIndex = 0
    private var appState = YoloTouchHelpState.STANDBY
    private lateinit var webView: WebView
    private var pageReady = false
    private var rootAvailable = false

    private var permissionDialog: HtmlDialogHandle? = null
    private var modelPickerDialog: HtmlDialogHandle? = null
    private var disclaimerDialog: HtmlDialogHandle? = null
    private var changelogDialog: HtmlDialogHandle? = null
    private var acknowledgementsDialog: HtmlDialogHandle? = null
    private var deviceInfoDialog: HtmlDialogHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConfigManager.init(this)
        loadModelsFromJson()

        val cfgModelIndex = ConfigManager.getConfig().modelIndex
        if (cfgModelIndex !in 0 until modelList.size) {
            ConfigManager.updateConfig { modelIndex = 0 }
        }
        selectedModelIndex = if (cfgModelIndex in 0 until modelList.size) cfgModelIndex else 0
        ProjectionHolder.selectedModelIndex = selectedModelIndex

        setContentView(R.layout.activity_main)
        bindViews()
        setupWebView()

        ProjectionHolder.setStateListener(stateListener)
        ProjectionHolder.setModelIndexListener { idx ->
            runOnUiThread {
                if (idx in modelList.indices) {
                    selectedModelIndex = idx
                    syncPageState()
                }
            }
        }

        if (!isDisclaimerAccepted()) {
            showDisclaimerDialog()
        } else {
            initAfterDisclaimer()
        }
    }

    override fun onStart() {
        super.onStart()
        rootAvailable = isRootAvailable()
        if (!rootAvailable) {
            try {
                Shizuku.addRequestPermissionResultListener(shizukuListener)
            } catch (_: Exception) {
            }
            try {
                if (Shizuku.pingBinder() && !isShizukuGranted()) {
                    Shizuku.requestPermission(REQ_SHIZUKU)
                }
            } catch (_: Exception) {
            }
        }
        updatePermissionStates()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
        if (ProjectionHolder.needsModelReload) {
            ProjectionHolder.needsModelReload = false
            loadDefaultModel()
        }

        val holderIndex = ProjectionHolder.selectedModelIndex
        if (holderIndex in modelList.indices && holderIndex != selectedModelIndex) {
            selectedModelIndex = holderIndex
        }
        syncStateFromHolder()
        refreshPermissionDialog()
        syncModelPickerDialogState()
    }

    override fun onStop() {
        super.onStop()
        if (!rootAvailable) {
            try {
                Shizuku.removeRequestPermissionResultListener(shizukuListener)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        ProjectionHolder.removeStateListener()
        ProjectionHolder.removeModelIndexListener()
        pageReady = false
        disclaimerDialog?.dialog?.dismiss()
        changelogDialog?.dialog?.dismiss()
        acknowledgementsDialog?.dialog?.dismiss()
        permissionDialog?.dialog?.dismiss()
        modelPickerDialog?.dialog?.dismiss()
        deviceInfoDialog?.dialog?.dismiss()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("YoloTouchHelpApp")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun bindViews() {
        webView = findViewById(R.id.mainWebView)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = false
        webView.addJavascriptInterface(WebAppBridge(), "YoloTouchHelpApp")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                syncPageState()
            }
        }
        webView.loadUrl("file:///android_asset/main_ui.html")
    }

    private fun initAfterDisclaimer() {
        android.os.Handler(mainLooper).postDelayed({ loadDefaultModel() }, 500)
        android.os.Handler(mainLooper).postDelayed({ checkPermissionsOnStart() }, 1500)
    }

    private fun isDisclaimerAccepted(): Boolean {
        val prefs = getSharedPreferences("disclaimer_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("accepted", false)
    }

    private fun setDisclaimerAccepted() {
        getSharedPreferences("disclaimer_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("accepted", true)
            .putLong("accepted_at", System.currentTimeMillis())
            .apply()
    }

    private fun showDisclaimerDialog() {
        disclaimerDialog?.dialog?.dismiss()
        disclaimerDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_disclaimer.html",
            bridgeName = "DisclaimerHost",
            bridge = DisclaimerDialogBridge(),
            cancelable = false,
            heightRatio = 0.86f,
            onDismiss = { disclaimerDialog = null }
        )
    }

    private fun dp(value: Int): Int = (value * displayDensity + 0.5f).toInt()

    private fun onFabClick() {
        if (appState != YoloTouchHelpState.STANDBY) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            ProjectionHolder.clearViews(wm)
            stopService(Intent(this, FloatService::class.java))
            ProjectionHolder.updateState(YoloTouchHelpState.STANDBY.ordinal, ProjectionHolder.currentModelName.ifEmpty { "---" })
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isInjectorAvailable()) {
            showPermissionHelpDialog()
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun checkPermissionsOnStart() {
        if (!Settings.canDrawOverlays(this) || !isInjectorAvailable()) {
            showPermissionHelpDialog()
        }
    }

    private fun updatePermissionStates() {
        rootAvailable = isRootAvailable()
        syncPageState()
    }

    private fun isShizukuGranted(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().use { it.readLine().orEmpty() }
            process.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    private fun isInjectorAvailable(): Boolean {
        return rootAvailable || isShizukuGranted()
    }

    private fun getPrivilegeStatus(): String {
        if (rootAvailable) {
            return "Root"
        }
        return try {
            when {
                !Shizuku.pingBinder() -> "Shizuku 连接中"
                isShizukuGranted() -> "Shizuku 已授权"
                else -> "Shizuku 未授权"
            }
        } catch (_: Exception) {
            "Shizuku 不可用"
        }
    }

    private fun getOverlayStatus(): String {
        return if (Settings.canDrawOverlays(this)) "已开启" else "未开启"
    }

    private fun getTouchStatus(): String {
        return when {
            appState == YoloTouchHelpState.STANDBY -> "等待触摸服务"
            rootAvailable -> "Root 触摸注入"
            isShizukuGranted() -> "Shizuku 触摸注入"
            else -> "等待权限"
        }
    }

    private fun showPermissionHelpDialog() {
        permissionDialog?.dialog?.dismiss()
        permissionDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_permission.html",
            bridgeName = "PermissionHost",
            bridge = PermissionDialogBridge(),
            cancelable = true,
            heightRatio = 0.62f,
            onDismiss = { permissionDialog = null }
        )
    }

    private fun refreshPermissionDialog() {
        val handle = permissionDialog ?: return
        val privilegeStatus = getPrivilegeStatus()
        val payload = JSONObject().apply {
            put("privilegeValue", privilegeStatus)
            put("overlayValue", getOverlayStatus())
            put("canGrantPrivilege", !rootAvailable && !privilegeStatus.contains("已授权") && !privilegeStatus.contains("连接中"))
            put("canGrantOverlay", !Settings.canDrawOverlays(this@MainActivity))
        }
        handle.webView.post {
            val script = "window.PermissionDialog && window.PermissionDialog.render(${JSONObject.quote(payload.toString())});"
            handle.webView.evaluateJavascript(script, null)
        }
    }

    private fun showMainMenuDialog() {
        val labels = arrayOf("导出配置", "导入配置", "更新日志", "GitHub", "高级设置")
        MaterialAlertDialogBuilder(this)
            .setTitle("更多操作")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> exportLauncher.launch("aimbot_config.json")
                    1 -> importLauncher.launch(arrayOf("application/json"))
                    2 -> showChangelogDialog()
                    3 -> openGithub()
                    4 -> openSettings()
                }
            }
            .show()
    }

    private fun showModelPickerDialog() {
        if (modelList.isEmpty()) {
            Toast.makeText(this, "当前没有可用模型", Toast.LENGTH_SHORT).show()
            return
        }
        modelPickerDialog?.dialog?.dismiss()
        modelPickerDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_model_picker.html",
            bridgeName = "ModelPickerHost",
            bridge = ModelPickerDialogBridge(),
            cancelable = true,
            heightRatio = 0.72f,
            onDismiss = { modelPickerDialog = null }
        )
    }

    private fun openGithub() {
        openExternalUrl(
            url = GITHUB_URL,
            chooserTitle = "选择浏览器打开 GitHub",
            notFoundMessage = "未找到可打开链接的应用",
            failureMessage = "打开 GitHub 失败"
        )
    }

    private fun openQqGroup() {
        val groupIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$QQ_GROUP_NUMBER&card_type=group&source=qrcode")
        )
        try {
            startActivity(groupIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "未安装 QQ，无法自动加群", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "打开 QQ 群失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun showChangelogDialog() {
        changelogDialog?.dialog?.dismiss()
        changelogDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_changelog.html",
            bridgeName = "DialogHost",
            bridge = SimpleDialogBridge { changelogDialog?.dialog?.dismiss() },
            cancelable = true,
            heightRatio = 0.84f,
            onDismiss = { changelogDialog = null }
        )
    }

    private fun showAcknowledgementsDialog() {
        acknowledgementsDialog?.dialog?.dismiss()
        acknowledgementsDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_acknowledgements.html",
            bridgeName = "AcknowledgementsHost",
            bridge = AcknowledgementsDialogBridge(),
            cancelable = true,
            heightRatio = 0.84f,
            onDismiss = { acknowledgementsDialog = null }
        )
    }

    private fun showDeviceInfoDialog() {
        deviceInfoDialog?.dialog?.dismiss()
        deviceInfoDialog = showHtmlDialog(
            assetPath = "file:///android_asset/dialog_device_info.html",
            bridgeName = "DeviceInfoHost",
            bridge = DeviceInfoDialogBridge(),
            cancelable = true,
            heightRatio = 0.58f,
            onDismiss = { deviceInfoDialog = null }
        )
    }

    private fun exitApplication() {
        if (appState != YoloTouchHelpState.STANDBY) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            ProjectionHolder.clearViews(wm)
            stopService(Intent(this, FloatService::class.java))
            ProjectionHolder.updateState(YoloTouchHelpState.STANDBY.ordinal, ProjectionHolder.currentModelName.ifEmpty { "---" })
        }
        finishAffinity()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        }
    }

    private fun openExternalUrl(
        url: String,
        chooserTitle: String = "选择浏览器打开链接",
        notFoundMessage: String = "未找到可打开链接的应用",
        failureMessage: String = "打开链接失败"
    ) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, notFoundMessage, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showHtmlDialog(
        assetPath: String,
        bridgeName: String,
        bridge: Any,
        cancelable: Boolean,
        heightRatio: Float = 0.72f,
        onDismiss: (() -> Unit)? = null
    ): HtmlDialogHandle {
        val dialogHeight = (resources.displayMetrics.heightPixels * heightRatio).toInt()
        val dialogWebView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            addJavascriptInterface(bridge, bridgeName)
            webViewClient = object : WebViewClient() {}
            loadUrl(assetPath)
        }

        val container = FrameLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(
                dialogWebView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dialogHeight
                )
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(container)
            .setCancelable(cancelable)
            .create()

        dialog.setOnDismissListener {
            dialogWebView.removeJavascriptInterface(bridgeName)
            dialogWebView.destroy()
            onDismiss?.invoke()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.96f).toInt(),
            dialogHeight + dp(16)
        )
        return HtmlDialogHandle(dialog, dialogWebView)
    }

    fun setAppState(state: YoloTouchHelpState, modelName: String = ProjectionHolder.currentModelName.ifEmpty { "---" }) {
        appState = state
        ProjectionHolder.currentModelName = modelName
        syncPageState()
    }

    private fun syncStateFromHolder() {
        val stateOrdinal = ProjectionHolder.currentState.coerceIn(0, YoloTouchHelpState.entries.lastIndex)
        appState = YoloTouchHelpState.entries[stateOrdinal]
        syncPageState()
    }

    private fun syncPageState() {
        if (!pageReady || !::webView.isInitialized) return
        val payload = buildPagePayload()
        webView.post {
            if (!pageReady) return@post
            val script = "window.YoloTouchHelpUi && window.YoloTouchHelpUi.render(${JSONObject.quote(payload.toString())});"
            webView.evaluateJavascript(script, null)
        }
    }

    private fun buildPagePayload(): JSONObject {
        val model = modelList.getOrNull(selectedModelIndex)
        return JSONObject().apply {
            put("statusText", statusLabel(appState))
            put("running", appState != YoloTouchHelpState.STANDBY)
            put("versionName", getAppVersionName())
            put("backend", ProjectionHolder.currentModelName.ifEmpty { "---" })
            put("privilegeValue", getPrivilegeStatus())
            put("overlayValue", getOverlayStatus())
            put("touchValue", getTouchStatus())
            put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("deviceName", buildDeviceName())
            put("projectIntro", "本项目是个公益开源项目 会持续长久更新")
            put("model", buildModelJson(model))
            put("models", JSONArray().apply { modelList.forEach { put(it.displayName) } })
        }
    }

    private fun syncModelPickerDialogState() {
        val handle = modelPickerDialog ?: return
        val payload = JSONObject().apply {
            put("selectedIndex", selectedModelIndex)
            put(
                "models",
                JSONArray().apply {
                    modelList.forEachIndexed { index, model ->
                        put(
                            JSONObject().apply {
                                put("index", index)
                                put("displayName", model.displayName)
                                put("precision", model.precision)
                                put("inputSize", model.inputSize)
                                put("description", model.description)
                            }
                        )
                    }
                }
            )
        }
        handle.webView.post {
            val script = "window.ModelPickerDialog && window.ModelPickerDialog.render(${JSONObject.quote(payload.toString())});"
            handle.webView.evaluateJavascript(script, null)
        }
    }

    private fun syncDeviceInfoDialogState() {
        val handle = deviceInfoDialog ?: return
        val payload = JSONObject().apply {
            put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("deviceName", buildDeviceName())
            put("deviceExtra", "${Build.MANUFACTURER.orEmpty()} / ${Build.DEVICE.orEmpty()} / ${Build.PRODUCT.orEmpty()}")
            put("appVersion", getAppVersionName())
        }
        handle.webView.post {
            val script = "window.DeviceInfoDialog && window.DeviceInfoDialog.render(${JSONObject.quote(payload.toString())});"
            handle.webView.evaluateJavascript(script, null)
        }
    }

    private fun buildModelJson(model: ModelInfo?): JSONObject {
        return JSONObject().apply {
            put("displayName", model?.displayName ?: "暂无模型")
            put("precision", model?.precision ?: "-")
            put("inputSize", model?.inputSize ?: JSONObject.NULL)
            put("outputSize", model?.outputSize ?: JSONObject.NULL)
            put("description", model?.description ?: "-")
            put(
                "classesSummary",
                if (model == null || model.classes.isEmpty()) {
                    "-"
                } else {
                    model.classes.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}:${it.value}" }
                }
            )
        }
    }

    private fun statusLabel(state: YoloTouchHelpState): String {
        return when (state) {
            YoloTouchHelpState.STANDBY -> "待机中"
            YoloTouchHelpState.RUNNING -> "运行中"
            YoloTouchHelpState.INFERENCING -> "推理中"
        }
    }

    private fun getAppVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    private fun buildDeviceName(): String {
        val brand = Build.BRAND.orEmpty().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = Build.MODEL.orEmpty()
        return listOf(brand, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Unknown Device" }
    }

    private fun loadModelsFromJson() {
        try {
            val jsonString = assets.open("models.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val modelsArray = jsonObject.getJSONArray("models")
            modelList = (0 until modelsArray.length()).map { index ->
                val model = modelsArray.getJSONObject(index)
                val classesMap = mutableMapOf<Int, String>()
                if (model.has("classes")) {
                    val classesObj = model.getJSONObject("classes")
                    classesObj.keys().forEach { key ->
                        classesMap[key.toInt()] = classesObj.getString(key)
                    }
                }
                ModelInfo(
                    filename = model.getString("filename"),
                    displayName = model.getString("displayName"),
                    precision = model.getString("precision"),
                    inputSize = model.getInt("inputSize"),
                    outputSize = model.getInt("outputSize"),
                    description = model.getString("description"),
                    classes = classesMap
                )
            }
            Log.d("YoloTouchHelp_AI", "Loaded ${modelList.size} models from JSON")
        } catch (e: Exception) {
            Log.e("YoloTouchHelp_AI", "Failed to load models from JSON: ${e.message}", e)
            modelList = emptyList()
        }
    }

    private fun loadModel(filename: String) {
        val modelFile = File(filesDir, filename)
        try {
            val qnnCache = File(cacheDir, "qnn")
            if (qnnCache.exists()) {
                qnnCache.deleteRecursively()
            }
            qnnCache.mkdirs()

            if (!modelFile.exists()) {
                modelFile.parentFile?.mkdirs()
                assets.open(filename).use { input ->
                    FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                }
            }

            if (filename.endsWith(".param")) {
                val binFilename = filename.replace(".param", ".bin")
                val binFile = File(filesDir, binFilename)
                if (!binFile.exists()) {
                    assets.open(binFilename).use { input ->
                        FileOutputStream(binFile).use { output -> input.copyTo(output) }
                    }
                }
            }

            val config = ConfigManager.getConfig()
            JniCallBack.setForceCpu(config.useCpuInference)
            JniCallBack.setCpuThreads(config.cpuThreadCount)
            val success = JniCallBack.init(modelFile.absolutePath)
            if (success) {
                ProjectionHolder.currentModelName = JniCallBack.getBackend()
                syncPageState()
                Toast.makeText(this, "模型加载成功", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("YoloTouchHelp_AI", "模型加载异常: ${e.message}", e)
        }
    }

    private fun syncModelToFloatService() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.getRunningServices(100).any {
            it.service.className == FloatService::class.java.name
        }
        if (running) {
            startForegroundService(Intent(this, FloatService::class.java).apply {
                action = "SYNC_MODEL"
            })
        }
    }

    private fun loadDefaultModel() {
        if (modelList.isEmpty()) return
        try {
            val qnnCache = File(cacheDir, "qnn")
            if (qnnCache.exists()) {
                qnnCache.deleteRecursively()
            }
            qnnCache.mkdirs()

            val model = modelList[selectedModelIndex.coerceIn(0, modelList.lastIndex)]
            val modelFile = File(filesDir, model.filename)
            if (!modelFile.exists()) {
                modelFile.parentFile?.mkdirs()
                assets.open(model.filename).use { input ->
                    FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                }
            }
            if (model.filename.endsWith(".param")) {
                val binFilename = model.filename.replace(".param", ".bin")
                val binFile = File(filesDir, binFilename)
                if (!binFile.exists()) {
                    assets.open(binFilename).use { input ->
                        FileOutputStream(binFile).use { output -> input.copyTo(output) }
                    }
                }
            }

            val config = ConfigManager.getConfig()
            JniCallBack.setForceCpu(config.useCpuInference)
            JniCallBack.setCpuThreads(config.cpuThreadCount)
            if (JniCallBack.init(modelFile.absolutePath)) {
                ProjectionHolder.currentModelName = JniCallBack.getBackend()
                syncPageState()
            }
        } catch (e: Exception) {
            Log.e("YoloTouchHelp_AI", "默认模型加载异常: ${e.message}", e)
        }
    }

    inner class WebAppBridge {
        @JavascriptInterface
        fun onPageReady() {
            runOnUiThread {
                pageReady = true
                syncPageState()
            }
        }

        @JavascriptInterface
        fun toggleAimbot() {
            runOnUiThread { onFabClick() }
        }

        @JavascriptInterface
        fun showModelPicker() {
            runOnUiThread { showModelPickerDialog() }
        }

        @JavascriptInterface
        fun openPermissionHelp() {
            runOnUiThread { showPermissionHelpDialog() }
        }

        @JavascriptInterface
        fun exportConfig() {
            runOnUiThread { exportLauncher.launch("aimbot_config.json") }
        }

        @JavascriptInterface
        fun importConfig() {
            runOnUiThread { importLauncher.launch(arrayOf("application/json")) }
        }

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { this@MainActivity.openSettings() }
        }

        @JavascriptInterface
        fun openGithub() {
            runOnUiThread { this@MainActivity.openGithub() }
        }

        @JavascriptInterface
        fun openQqGroup() {
            runOnUiThread { this@MainActivity.openQqGroup() }
        }

        @JavascriptInterface
        fun openChangelog() {
            runOnUiThread { showChangelogDialog() }
        }

        @JavascriptInterface
        fun openAcknowledgements() {
            runOnUiThread { showAcknowledgementsDialog() }
        }

        @JavascriptInterface
        fun openDeviceInfo() {
            runOnUiThread { showDeviceInfoDialog() }
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { exitApplication() }
        }
    }

    inner class DisclaimerDialogBridge {
        @JavascriptInterface
        fun onPageReady() {
        }

        @JavascriptInterface
        fun accept() {
            runOnUiThread {
                setDisclaimerAccepted()
                disclaimerDialog?.dialog?.dismiss()
                initAfterDisclaimer()
            }
        }

        @JavascriptInterface
        fun reject() {
            runOnUiThread {
                disclaimerDialog?.dialog?.dismiss()
                finish()
            }
        }
    }

    inner class SimpleDialogBridge(
        private val onClose: () -> Unit
    ) {
        @JavascriptInterface
        fun close() {
            runOnUiThread { onClose() }
        }
    }

    inner class PermissionDialogBridge {
        @JavascriptInterface
        fun onPageReady() {
            runOnUiThread { refreshPermissionDialog() }
        }

        @JavascriptInterface
        fun grantPrivilege() {
            runOnUiThread {
                try {
                    if (!rootAvailable) {
                        Shizuku.requestPermission(REQ_SHIZUKU)
                    }
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Shizuku 不可用", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun grantOverlay() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { permissionDialog?.dialog?.dismiss() }
        }
    }

    inner class ModelPickerDialogBridge {
        @JavascriptInterface
        fun onPageReady() {
            runOnUiThread { syncModelPickerDialogState() }
        }

        @JavascriptInterface
        fun selectModel(index: Int) {
            runOnUiThread {
                if (index !in modelList.indices) return@runOnUiThread
                selectedModelIndex = index
                ProjectionHolder.selectedModelIndex = index
                ConfigManager.updateConfig { modelIndex = index }
                loadModel(modelList[index].filename)
                syncModelToFloatService()
                syncPageState()
                modelPickerDialog?.dialog?.dismiss()
            }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { modelPickerDialog?.dialog?.dismiss() }
        }
    }

    inner class DeviceInfoDialogBridge {
        @JavascriptInterface
        fun onPageReady() {
            runOnUiThread { syncDeviceInfoDialogState() }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { deviceInfoDialog?.dialog?.dismiss() }
        }
    }

    inner class AcknowledgementsDialogBridge {
        @JavascriptInterface
        fun close() {
            runOnUiThread { acknowledgementsDialog?.dialog?.dismiss() }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                openExternalUrl(
                    url = url,
                    chooserTitle = "选择浏览器打开链接",
                    notFoundMessage = "未找到可打开链接的应用",
                    failureMessage = "打开链接失败"
                )
            }
        }
    }
}

