package com.coyotevision.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.util.Locale
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import android.media.AudioManager
import android.media.ToneGenerator

class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val tracker = StandingTracker()
    private val intensity = MotionIntensity()
    private val wavePlayback = WavePlayback()
    private val preparation = SessionPreparation()
    private val sessionClock = SessionClock()
    private val virtualOutput = VirtualOutput()
    private val limbRecovery = LimbRecovery()
    private val curtseySignals = CurtseySignals()
    private var pausedForTracking = false
    private var virtualEnabled = false
    private lateinit var virtualToggle: CheckBox
    private lateinit var modeHint: TextView
    private lateinit var headCoverToggle: CheckBox
    private lateinit var videoFaceCover: VideoFaceCover
    private lateinit var headCoverAdjustments: LinearLayout
    private lateinit var hideSkeletonToggle: CheckBox
    private lateinit var mirrorToggle: CheckBox
    private val mirrorMode = MirrorMode()
    private val outputLog = CompactOutputLog()
    private val sessionLog = SessionLog()
    private lateinit var gameHeader: LinearLayout
    private lateinit var gameFooter: LinearLayout
    private lateinit var liveBadge: TextView
    private lateinit var mirrorExit: View
    private var mirrorStartedAt = 0L
    private var motionAlertUntil = 0L
    private var lastDeviceProblem = ""
    private var waitingStart = false
    private var training: EnduranceTraining? = null
    private lateinit var handWeight: EditText
    private lateinit var footWeight: EditText
    private lateinit var handMappingRange: EditText
    private lateinit var footMappingRange: EditText
    private lateinit var torsoPeak: EditText
    private lateinit var torsoMappingRange: EditText
    private val settingsNumbers = linkedMapOf<String, EditText>()
    private lateinit var importSettingsButton: Button
    private lateinit var exportSettingsButton: Button
    private lateinit var outputPreview: TextView
    private lateinit var riseSpeedField: EditText
    private lateinit var zoneWeight: EditText
    private lateinit var zoneRadius: EditText
    private lateinit var trainingToggle: CheckBox
    private lateinit var trainingPreset: PresetEditor
    private lateinit var trainGapMin: EditText
    private lateinit var trainGapMax: EditText
    private lateinit var trainDurationMin: EditText
    private lateinit var trainDurationMax: EditText
    private val accent = BlushTheme.rose
    private val muted = BlushTheme.muted
    private val bg = BlushTheme.background
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var sectionBody: LinearLayout
    private lateinit var settingsFooter: LinearLayout
    private lateinit var screenHost: FrameLayout
    private lateinit var settingsScreen: LinearLayout
    private val cameraAccess = CameraAccess()
    private lateinit var cameraCard: LinearLayout
    private lateinit var cameraCardStatus: TextView
    private lateinit var cameraOpenButton: Button
    private lateinit var cameraControls: LinearLayout
    private var cameraStatus = "摄像头未开启"
    private lateinit var cameraStage: CameraStage
    private lateinit var gameScreen: FrameLayout
    private lateinit var gameVideoHost: FrameLayout
    private lateinit var sessionBadge: TextView
    private lateinit var playStatus: TextView
    private lateinit var consolePanel: LinearLayout
    private lateinit var consoleText: TextView
    private lateinit var consoleToggle: Button
    private lateinit var startNotice: TextView
    private var hideStartNotice: Runnable? = null
    private var consoleVisible = true
    private var gameHasStarted = false
    private var cameraSlot = 0
    private var analysisResumeAt = 0L
    private var lastHudDraw = 0L
    private var hudTarget = 0
    private var hudCurrent = 0
    private var hudWave = "无"
    private var lastSubmitted: EffectPreset? = null
    private var lastSubmittedUntil = 0L
    private var penaltyDisplay: EffectPreset? = null
    private var penaltyDisplayUntil = 0L
    private lateinit var preview: PreviewView
    private lateinit var overlay: PoseOverlay
    private lateinit var signalOverlay: GameSignalOverlay
    private lateinit var poseText: TextView
    private lateinit var detailText: TextView
    private lateinit var relayText: TextView
    private lateinit var logText: TextView
    private lateinit var armButton: Button
    private lateinit var server: EditText
    private lateinit var limitField: EditText
    private lateinit var normalField: EditText
    private lateinit var motionPreset: PresetEditor
    private lateinit var penaltyPreset: PresetEditor
    private lateinit var curtseyPreset: PresetEditor
    private lateinit var cuePreset: PresetEditor
    private lateinit var gameToggle: CheckBox
    private lateinit var outputToggle: CheckBox
    private lateinit var durationField: EditText
    private lateinit var gapMinField: EditText
    private lateinit var gapMaxField: EditText
    private lateinit var responseField: EditText
    private lateinit var curtseyHoldField: Spinner
    private lateinit var curtseyRangeField: EditText
    private lateinit var wristNearField: EditText
    private val curtseyHoldOptions = SettingsCode.holdOptions
    private lateinit var gameText: TextView
    private var game: CurtseyGame? = null
    private var sessionEndAt = 0L
    private var hardwareEnabled = false
    private var motionOutputLive = false
    private var lastMotionCommand = 0L
    private var cue: ToneGenerator? = null
    private var controller: LifecycleCameraController? = null
    private lateinit var relay: RelayClient
    private lateinit var phoneIp: Spinner
    private val phoneWorker = Executors.newSingleThreadExecutor()
    private var phoneServer: PhoneRelayServer? = null
    @Volatile private var phoneEpoch = 0L
    private var phoneHost = ""
    private var phonePort = 9999
    private var phoneStatus = "两部手机连接同一 Wi-Fi，然后开启连接"
    private var pendingPairing = false
    private var lastNetworkCheck = 0L
    private var detector: PoseDetector? = null
    private var active = false
    private var armed = false
    private var lastPoseProblem = ""
    private var cameraBound = false
    private var cameraBinding = false
    private var lastFrame = 0L
    private var lastTracked = 0L
    private var frameAfterResume = 0L
    private var lastEstimate = StandingMetrics()
    private var outputEnd: Runnable? = null
    private var outputRunning = false
    private val events get() = sessionLog.entries

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val wasRequested = cameraAccess.requested && cameraAccess.permissionPending
        cameraAccess.permissionResult(granted)
        if (wasRequested && granted) {
            cameraStatus = "摄像头权限已允许"
            bindCamera()
        } else if (wasRequested) {
            cameraStatus = "摄像头未授权 · 可重试或打开权限设置"
            toast("还需要摄像头权限喵；可点击功能框里的权限设置")
        }
        updateCameraUi()
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (active) {
                val now = SystemClock.elapsedRealtime()
                if (phoneServer != null && now - lastNetworkCheck >= 2000) {
                    lastNetworkCheck = now
                    if (phoneHost !in LocalAddresses.discover()) {
                        stopPhoneConnection("局域网地址已变化，请重新开启两手机连接")
                        refreshPhoneAddresses()
                    }
                }
                if (armed && (now - lastFrame > 1200 || now - lastTracked > 1200)) {
                    pauseForCalibration(now)
                }
                if (waitingStart && (lastFrame == 0L || now - lastFrame > 400)) {
                    updatePreparation(now, StandingMetrics(), null)
                }
                if (lastFrame > 0 && now - lastFrame > 1200) {
                    overlay.pose = null
                    if (::videoFaceCover.isInitialized) videoFaceCover.clear()
                    poseText.text = "镜头还在准备喵，小公主稍等"
                    lastEstimate = StandingMetrics()
                }
                if (armed) updateSession(now) else renderGameHud(now)
                main.postDelayed(this, 200)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        migrateOutputSettings()
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (gameScreen.visibility == View.VISIBLE) {
                    stopAutomation("返回设置，已停止本轮")
                    leaveGameScreen()
                } else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        relay = RelayClient({ updateRelayUi() }, { reason -> stopAutomation(reason) })
        updateRelayUi()
    }

    private fun requestCamera() {
        if (!active || cameraBinding || cameraBound || cameraAccess.permissionPending) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        cameraAccess.request(granted)
        cameraStatus = if (granted) "镜头准备中喵…" else "等待摄像头权限"
        updateCameraUi()
        if (granted) bindCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun bindCamera() {
        if (!cameraAccess.canStart || cameraBinding || cameraBound || isDestroyed) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraAccess.close(); cameraStatus = "摄像头未授权 · 请点击开启后授权"; updateCameraUi(); return
        }
        cameraBinding = true
        cameraStatus = "镜头准备中喵…"
        updateCameraUi()
        val ticket = cameraAccess.generation
        try {
            // Neither CameraX nor the pose detector is initialized before an explicit tap.
            val currentController = LifecycleCameraController(this).also { controller = it }
            val currentDetector = PoseDetection.getClient(PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()).also { detector = it }
            currentController.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            preview.controller = currentController
            val executor = ContextCompat.getMainExecutor(this)
            val previewDetector = PreviewPoseDetector(currentDetector, executor)
            currentController.setImageAnalysisAnalyzer(executor, MlKitAnalyzer(listOf(previewDetector),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, executor) { result ->
                if (cameraAccess.accepts(ticket) && cameraBound && controller === currentController) {
                    val problem = result.getThrowable(previewDetector)?.message.orEmpty()
                    if (problem != lastPoseProblem) {
                        lastPoseProblem = problem
                        if (problem.isNotEmpty()) log("动作识别错误：$problem", important = true)
                    }
                    handlePose(result.getValue(previewDetector))
                }
            })
            currentController.initializationFuture.addListener({
                if (!cameraAccess.accepts(ticket) || controller !== currentController || isDestroyed) return@addListener
                cameraBinding = false
                try {
                    currentController.initializationFuture.get()
                    currentController.cameraSelector = if (currentController.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    currentController.bindToLifecycle(this)
                    cameraBound = true
                    frameAfterResume = SystemClock.elapsedRealtime()
                    lastFrame = 0; lastTracked = 0
                    poseText.text = "小公主，先让教官看清站姿喵…"
                    detailText.text = "摄像头已开启 · 点击开始罚站后再进行校准"
                    updateCameraUi()
                } catch (error: Exception) { cameraFailed(error) }
            }, executor)
        } catch (error: Exception) { cameraFailed(error) }
    }

    private fun cameraFailed(error: Exception) {
        val reason = error.cause?.message ?: error.message ?: "请检查设备摄像头喵"
        stopAutomation("摄像头启动失败：$reason")
        cameraAccess.close()
        releaseCamera()
        cameraStatus = "摄像头开启失败 · 点击可重试"
        detailText.text = reason
        updateCameraUi()
    }

    private fun releaseCamera() {
        cameraBound = false; cameraBinding = false
        val previousController = controller
        controller = null
        previousController?.clearImageAnalysisAnalyzer()
        previousController?.unbind()
        if (::preview.isInitialized) preview.controller = null
        if (::videoFaceCover.isInitialized) videoFaceCover.clear()
        detector?.close(); detector = null
        lastPoseProblem = ""
        lastFrame = 0; lastTracked = 0; lastEstimate = StandingMetrics()
        tracker.reset(); intensity.reset()
        if (::overlay.isInitialized) { overlay.pose = null; overlay.tracking = null; overlay.curtseyTarget = null }
    }

    private fun closeCamera() {
        stopAutomation("摄像头已关闭，已停止本轮")
        cameraAccess.close()
        releaseCamera()
        if (gameScreen.visibility == View.VISIBLE) leaveGameScreen()
        cameraStatus = "摄像头已关闭"
        updateCameraUi()
    }

    private fun updateCameraUi() {
        if (!::cameraCard.isInitialized) return
        cameraCard.visibility = if (cameraBound) View.GONE else View.VISIBLE
        cameraStage.visibility = if (cameraBound) View.VISIBLE else View.GONE
        cameraControls.visibility = if (cameraBound) View.VISIBLE else View.GONE
        cameraCardStatus.text = cameraStatus
        cameraOpenButton.text = when {
            cameraAccess.permissionPending -> "等待授权…"
            cameraBinding -> "正在开启…"
            else -> "开启摄像头"
        }
        cameraOpenButton.isEnabled = cameraAccess.foreground && !cameraAccess.permissionPending && !cameraBinding
        armButton.isEnabled = cameraBound
    }

    override fun onResume() {
        super.onResume()
        active = true
        cameraAccess.enterForeground()
        frameAfterResume = SystemClock.elapsedRealtime()
        lastFrame = 0
        lastTracked = 0
        lastEstimate = StandingMetrics()
        tracker.reset(); intensity.reset()
        main.removeCallbacks(watchdog)
        main.post(watchdog)
        if (::relay.isInitialized) updateRelayUi()
        // This can only continue the user's pending permission request, never infer intent from permission.
        bindCamera()
        updateCameraUi()
    }

    override fun onPause() {
        active = false
        cameraAccess.leaveForeground()
        stopAutomation("已暂停，回到应用后需重新启用")
        releaseCamera()
        if (gameScreen.visibility == View.VISIBLE) leaveGameScreen()
        cameraStatus = if (cameraAccess.permissionPending) "等待摄像头权限" else "摄像头已关闭 · 点击后重新开启"
        updateCameraUi()
        overlay.pose = null
        tracker.reset(); intensity.reset()
        main.removeCallbacks(watchdog)
        saveSettings()
        super.onPause()
    }

    override fun onDestroy() {
        phoneEpoch++
        phoneServer?.let { local -> phoneWorker.execute { local.shutdown() } }
        phoneServer = null
        phoneWorker.shutdown()
        cue?.release()
        main.removeCallbacksAndMessages(null)
        cameraAccess.close()
        releaseCamera()
        if (::relay.isInitialized) relay.release()
        super.onDestroy()
    }

    private fun handlePose(pose: PreviewPose?) {
        if (!cameraAccess.canStart || !cameraBound) return
        val now = SystemClock.elapsedRealtime()
        // The adapter explicitly applies the frame-to-video matrix once for all consumers.
        if (now >= analysisResumeAt) videoFaceCover.update(pose)
        // Returning to the foreground always starts a fresh baseline.
        if (now - frameAfterResume < 250 || now < analysisResumeAt) return
        lastFrame = now
        overlay.pose = pose
        val points = BodyJoint.entries.mapNotNull { joint -> pose?.getPoseLandmark(joint.id)?.let {
            joint to BodyPoint(it.position.x, it.position.y, it.inFrameLikelihood)
        } }.toMap()
        tracker.zoneRadius = ((zoneRadius.text.toString().toFloatOrNull() ?: 50f) / 100f).coerceIn(.2f, 2f)
        tracker.sensitivity = 1f
        tracker.curtseyRange = (readCurtseyRange().takeIf { it in 10..80 } ?: 30) / 100f
        tracker.motionMapping = readMotionMapping().takeIf { it.valid() } ?: MotionMappingSettings()
        tracker.wristNearPercent = readWristNear().takeIf { WristCalibration.valid(it) } ?: WristCalibration.DEFAULT_NEAR_PERCENT
        val body = PreviewCoordinates.normalize(points, preview.width, preview.height)
        val curtseyCore = (waitingStart && preparation.phase == PreparationPhase.CAPTURE_CURTSEY) ||
            (armed && game?.phase in listOf(GamePhase.CUE, GamePhase.REQUEST, GamePhase.RECOVERY))
        lastEstimate = tracker.observe(body, PreviewCoordinates.WIDTH, PreviewCoordinates.HEIGHT, now, curtseyCore)
        if (lastEstimate.tracked) lastTracked = now
        else if (armed && now - lastTracked <= 1000) lastEstimate = lastEstimate.copy(tracked = true,
            measurementReady = false, trackingMessage = "人物关键点短暂不稳；保留校准，等待清晰画面")
        overlay.tracking = lastEstimate.tracking
        poseText.text = when {
            waitingStart && preparation.phase == PreparationPhase.CAPTURE_CURTSEY && !lastEstimate.measurementReady -> "屈膝礼采集暂停 · 位置暂时保留喵"
            !lastEstimate.tracked -> "看不到小公主啦喵，请全身回到镜头里"
            !lastEstimate.measurementReady -> "小公主仍在镜头里 · 局部测量暂不稳定"
            curtseyCore -> lastEstimate.curtseyMatch?.reason ?: lastEstimate.trackingMessage
            !lastEstimate.calibrated -> "先记好小公主的站姿喵 · ${(lastEstimate.calibrationProgress * 100).toInt()}%"
            lastEstimate.curtsey -> "小公主，屈膝礼姿势对啦喵，继续稳住"
            lastEstimate.neutral -> "小公主站好啦喵，双手也收好啦"
            lastEstimate.tPose -> "双臂横向平举"
            !lastEstimate.legsTogether -> "小公主，罚站时双腿要并拢喵"
            !lastEstimate.handsTogether -> "小公主，双手要收好喵 · 间距偏离记录值"
            lastEstimate.standing -> "双手跑偏啦，小公主喵 · 回到记录的位置"
            else -> "罚站的小动作被发现啦喵 · 站姿偏移 / 动作中"
        }
        detailText.text = lastEstimate.trackingMessage + "\n手部 ${(lastEstimate.hands * 100).toInt()}% / 腿脚 ${(lastEstimate.feet * 100).toInt()}% / ${if (lastEstimate.outside) "已离位" else "范围内"} · " +
            if (lastEstimate.calibrated) "小公主的站姿记好啦喵 · 手机要保持固定" else "点开始罚站后再退到合适位置喵，平举双臂让我确认"
        armButton.isEnabled = cameraBound
        if (waitingStart) updatePreparation(now, lastEstimate, body)
        if (armed) updateSession(now) else renderGameHud(now)
    }

    private fun enableAutomation() {
        if (!cameraBound) { toast("请先开启摄像头；进入游戏后再退到合适位置"); return }
        val settings = readGameSettings()
        if (!WristCalibration.valid(readWristNear())) {
            toast("手腕靠近距离请填写 5–150，表示允许双腕间距的肩宽百分比"); return
        }
        if (!MotionRiseSpeed.valid(readRiseSpeed())) {
            toast("普通动作增量上升速度请填 1–200 协议强度单位/秒喵"); return
        }
        if (!readMotionMapping().valid()) {
            toast("手部、脚步和躯干满强度幅度请填 5–200，表示超出抖动阈值的肩宽百分比"); return
        }
        if (readCurtseyRange() !in 10..80) {
            toast("屈膝礼判定范围请填写 10–80，数值为校准肩宽的百分比"); return
        }
        if (gameToggle.isChecked && settings.curtseyHoldMs + 500 > settings.responseMs) {
            toast("行礼判定期限需至少比下蹲保持时间长 0.5 秒，以留出站姿确认和动作切换时间"); return
        }
        val checkedSettings = if (gameToggle.isChecked) settings else settings.copy(curtseyHoldMs = 500)
        if (!checkedSettings.valid()) { toast("总时长 1–90 分钟；间隔 5–300 秒；行礼期限 3–30 秒"); return }
        hardwareEnabled = outputToggle.isChecked
        virtualEnabled = virtualToggle.isChecked
        if (virtualEnabled) hardwareEnabled = false
        val limit = limitField.text.toString().toIntOrNull() ?: 0
        if (hardwareEnabled && (!relay.ready || phoneServer == null)) {
            toast("请先开启内置两手机连接，并用另一部手机 DGLAB 扫码配对"); return
        }
        if (!readDirectMotion().valid()) {
            toast("全局最高强度需为 0–200；常态不能超过它。各动作最高强度填 0 关闭，或填写常态至全局最高之间的数值"); return
        }
        if (trainingToggle.isChecked && trainingPreset.read().strength !in 0..limit) {
            toast("训练基础强度需在 0 到全局最高之间"); return
        }
        if (penaltyPreset.read().strength !in 0..200) {
            toast("惩罚增量需为 0–200"); return
        }
        if (hardwareEnabled && (limit !in 1..200 || readNormal() > limit ||
                (gameToggle.isChecked && curtseyPreset.read().strength !in 0..limit))) {
            toast("请设置总上限，常态及过程强度不可超过上限；惩罚增量相加后会自动限制到总上限"); return
        }
        if (hardwareEnabled && gameToggle.isChecked && !cuePreset.read().validFor(limit)) {
            toast("请设置行礼前三连脉冲提示强度，范围为 1 到总上限"); return
        }
        if (readRadius() !in 20..200 || !readTrainingSettings().valid()) {
            toast("站立半径需为 20–200% 肩宽；训练间隔 5–300 秒、持续 1–30 秒"); return
        }
        if (trainingToggle.isChecked && hardwareEnabled && !trainingPreset.read().validFor(limit)) {
            toast("请设置忍耐训练基础强度，不超过总上限"); return
        }
        saveSettings()
        sessionClock.reset(); pausedForTracking = false
        curtseySignals.clear()
        limbRecovery.reset()
        sessionLog.clear(); outputLog.reset(); mirrorMode.exit(); logText.text = ""
        virtualOutput.clear()
        waitingStart = true; preparation.begin()
        tracker.reset(); lastEstimate = StandingMetrics(); overlay.tracking = null; intensity.reset(); wavePlayback.reset()
        armButton.text = "取消准备"
        setSettingsEnabled(false)
        gameText.text = "小公主，先站好给教官看看喵 · 全身入镜，双臂横向平举 2 秒确认位置\n当前不输出、不计时"
        gameHasStarted = false
        showGameScreen()
        log("准备校准 · 输出 0", important = true)
    }

    private fun updatePreparation(now: Long, metrics: StandingMetrics, body: FullBody?) {
        if (!waitingStart) return
        val event = preparation.update(now, metrics, body)
        when (event) {
            PreparationEvent.POSITION_CONFIRMED -> {
                tracker.reset()
                log("位置确认 · 输出 0")
                announcePositionConfirmed()
            }
            PreparationEvent.CURTSEY_CONFIRMED -> {
                tracker.beginCalibration(preparation.curtseyReference, preparation.positionReference)
                log("屈膝礼已校准 · 输出 0")
                announcePositionConfirmed(curtseyRecorded = true)
            }
            PreparationEvent.CURTSEY_RETRY -> {
                tracker.reset()
                log("校准重试：屈膝礼与站姿差异不足 · 输出 0")
                announcePositionConfirmed()
            }
            PreparationEvent.POSITION_RESET -> {
                tracker.reset()
                lastEstimate = metrics.copy(calibrated = false, calibrationProgress = 0f)
                hideStartNotice?.let { main.removeCallbacks(it) }; hideStartNotice = null
                startNotice.visibility = View.GONE
                log("校准重试：位置改变或跟踪不足 · 输出 0")
            }
            PreparationEvent.START_READY -> {
                waitingStart = false
                startSession()
                return
            }
            null -> Unit
        }
        gameText.text = when (preparation.phase) {
            PreparationPhase.POSITIONING -> "小公主先站直喵，全身入镜，双臂横向平举 2 秒确认位置\n${lastEstimate.trackingMessage}\n确认进度 ${(preparation.progress * 100).toInt()}% · 不输出、不计时"
            PreparationPhase.CAPTURE_CURTSEY -> "小公主的位置确认啦喵；微屈膝、双手提至裙摆位置，稳住 3 秒\n${preparation.captureMessage}\n已保持 ${DisplayFormat.one(preparation.progress * 3f)} / 3.0 秒 · 不输出、不计时"
            PreparationPhase.RETURN_TO_NEUTRAL -> "双手靠近、放到腹前喵，稳住手腕 5 秒；身体坐标看清后完成记录\n${lastEstimate.trackingMessage} · 已静止 ${DisplayFormat.one(lastEstimate.calibrationProgress * 5f)} / 5.0 秒 · 不输出、不计时"
            PreparationPhase.READY -> "小公主的站姿记住啦喵，罚站要开始啦"
        }
        if (pausedForTracking) gameText.append("\n先暂停等你喵 · 剩余 ${remainingText(now)} · 校准后继续")
        renderGameHud(now, event != null)
    }

    private fun announcePositionConfirmed(curtseyRecorded: Boolean = false) {
        startNotice.text = if (curtseyRecorded) "小公主的屈膝礼记住啦喵\n请起身，双手放腹前静止 5 秒" else "小公主的位置确认啦喵\n请保持屈膝礼姿势 3 秒"
        startNotice.setTextColor(BlushTheme.waiting)
        startNotice.visibility = View.VISIBLE
        if (cue == null) cue = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65) }.getOrNull()
        cue?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        hideStartNotice?.let { main.removeCallbacks(it) }
        hideStartNotice = Runnable { startNotice.visibility = View.GONE; hideStartNotice = null }
            .also { main.postDelayed(it, 1500) }
    }

    private fun startSession() {
        if (preparation.phase != PreparationPhase.READY || !lastEstimate.calibrated || !lastEstimate.neutral || !lastEstimate.curtseyCalibrated) {
            stopAutomation("站姿尚未校准完成，本轮未启动"); return
        }
        val settings = readGameSettings()
        val now = SystemClock.elapsedRealtime()
        val resuming = pausedForTracking
        val remaining = if (resuming) sessionClock.remaining(now) else settings.durationMs
        if (remaining < 100) { stopAutomation("本轮时间已到"); return }
        if (hardwareEnabled && (!relay.ready || !relay.stopAll() || !relay.timedSession(true, remaining))) {
            stopAutomation("本轮启动失败，请检查连接"); return
        }
        if (resuming) {
            if (!sessionClock.resume(now)) { stopAutomation("本轮无法继续"); return }
            val playNow = sessionClock.elapsed(now)
            game?.resumeAfterCalibration(playNow)
            training?.resumeAfterCalibration(playNow)
        } else {
            sessionClock.start(now, settings.durationMs)
            sessionEndAt = settings.durationMs
            game = if (gameToggle.isChecked) CurtseyGame(settings, 0, endAt = sessionEndAt) else null
            training = EnduranceTraining(readTrainingSettings(), 0, sessionEndAt)
        }
        pausedForTracking = false
        armed = true
        gameHasStarted = true
        intensity.reset(); lastMotionCommand = 0
        armButton.text = "结束本轮罚站"
        outputLog.reset()
        mirrorMode.calibrated(mirrorToggle.isChecked)
        mirrorStartedAt = now
        log("${if (resuming) "重新校准，继续" else "校准完成，开始"} · 剩余 ${remainingText(now)} · ${outputMode()}", important = true)
        announceGameStarted(resuming)
        updateSession(now)
    }

    private fun remainingText(now: Long): String {
        return DisplayFormat.duration(sessionClock.remaining(now))
    }

    private fun pauseForCalibration(now: Long) {
        if (!armed) return
        if (sessionClock.remaining(now) <= 0) { stopAutomation("本轮时间已到，已停止"); return }
        val failure = if (now - lastFrame > 400) "摄像头分析帧中断 ${DisplayFormat.seconds(now - lastFrame)}" else lastEstimate.trackingMessage
        exitMirrorMode()
        outputLog.reset()
        sessionClock.pause(now)
        curtseySignals.clear()
        limbRecovery.reset()
        armed = false; pausedForTracking = true; waitingStart = true
        outputEnd?.let { main.removeCallbacks(it) }; outputEnd = null
        outputRunning = false; motionOutputLive = false
        virtualOutput.clear(); intensity.reset(); wavePlayback.reset()
        penaltyDisplay = null; penaltyDisplayUntil = 0
        lastSubmitted = null; lastSubmittedUntil = 0
        hudTarget = 0; hudCurrent = 0; hudWave = "无"
        preparation.begin(); tracker.reset(); lastEstimate = StandingMetrics(trackingMessage = failure)
        overlay.tracking = null
        hideStartNotice?.let { main.removeCallbacks(it) }; hideStartNotice = null
        startNotice.visibility = View.GONE
        if (hardwareEnabled) {
            val stopped = relay.stopAll()
            val sessionStopped = relay.timedSession(false)
            if (!stopped || !sessionStopped) { stopAutomation("暂停停止指令提交失败，本轮结束，请在官方 App 检查输出"); return }
        }
        gameText.text = "看不清小公主啦喵 · 罚站计时已暂停 · 剩余 ${remainingText(now)}\n请全身入镜，平举双臂 2 秒，再保持屈膝礼 3 秒，最后起身静止 5 秒记录后继续"
        log("跟踪丢失：$failure · 输出 0，计时暂停，等待校准", important = true)
        renderGameHud(now, true)
    }

    private fun readDirectMotion() = DirectMotionSettings(readNormal(),
        limitField.text.toString().toIntOrNull() ?: -1, handWeight.text.toString().toIntOrNull() ?: -1,
        footWeight.text.toString().toIntOrNull() ?: -1, torsoPeak.text.toString().toIntOrNull() ?: -1,
        zoneWeight.text.toString().toIntOrNull() ?: -1)
    private fun readWeights() = readDirectMotion().increments()

    private fun suspendMeasurements(now: Long) {
        exitMirrorMode()
        outputLog.reset()
        curtseySignals.clear()
        sessionClock.pause(now); limbRecovery.suspend()
        outputEnd?.let { main.removeCallbacks(it) }; outputEnd = null
        outputRunning = false; motionOutputLive = false
        virtualOutput.clear(); intensity.reset(); wavePlayback.reset()
        penaltyDisplay = null; penaltyDisplayUntil = 0; lastSubmitted = null; lastSubmittedUntil = 0
        hudTarget = 0; hudCurrent = 0; hudWave = "无"
        if (hardwareEnabled) {
            val stop = relay.stopAll(); val cancelled = relay.timedSession(false)
            if (!stop || !cancelled) { stopAutomation("局部测量暂停时未能提交停止指令"); return }
        }
        log("测量不足：${lastEstimate.trackingMessage} · 输出 0，计时暂停", important = true)
    }
    private fun readRadius() = zoneRadius.text.toString().toIntOrNull() ?: -1
    private fun readRiseSpeed() = riseSpeedField.text.toString().toIntOrNull() ?: -1
    private fun readMotionMapping() = MotionMappingSettings(handMappingRange.text.toString().toIntOrNull() ?: -1,
        footMappingRange.text.toString().toIntOrNull() ?: -1,
        torsoMappingRange.text.toString().toIntOrNull() ?: -1)
    private fun readCurtseyRange() = curtseyRangeField.text.toString().toIntOrNull() ?: -1
    private fun readWristNear() = wristNearField.text.toString().toIntOrNull() ?: -1
    private fun readNormal() = normalField.text.toString().toIntOrNull() ?: -1
    private fun readTrainingSettings() = EnduranceSettings(trainingToggle.isChecked,
        (trainGapMin.text.toString().toLongOrNull() ?: 0) * 1000,
        (trainGapMax.text.toString().toLongOrNull() ?: 0) * 1000,
        (trainDurationMin.text.toString().toLongOrNull() ?: 0) * 1000,
        (trainDurationMax.text.toString().toLongOrNull() ?: 0) * 1000)

    private fun currentHoldSeconds() = (curtseyHoldOptions[curtseyHoldField.selectedItemPosition.coerceIn(curtseyHoldOptions.indices)] / 1000f).toString()

    private fun readGameSettings() = GameSettings(
        (durationField.text.toString().toLongOrNull() ?: 0) * 60_000,
        (gapMinField.text.toString().toLongOrNull() ?: 0) * 1000,
        (gapMaxField.text.toString().toLongOrNull() ?: 0) * 1000,
        (responseField.text.toString().toLongOrNull() ?: 0) * 1000,
        penaltyMs = penaltyPreset.read().durationSeconds * 1000L,
        curtseyHoldMs = curtseyHoldOptions[curtseyHoldField.selectedItemPosition.coerceIn(curtseyHoldOptions.indices)],
    )

    private fun updateSession(now: Long) {
        try { updateSessionState(now) } finally { renderGameHud(now) }
    }

    private fun updateSessionState(now: Long) {
        if (!armed) return
        val playNow = sessionClock.elapsed(now)
        hudTarget = 0; hudCurrent = 0; hudWave = "无"
        if (playNow >= sessionEndAt) { stopAutomation("本轮时间已到，已停止"); return }
        val evidence = if (now - lastFrame <= 400) lastEstimate else lastEstimate.copy(measurementReady = false,
            trackingMessage = "等待新的分析画面；保留校准")
        if (!evidence.tracked) { pauseForCalibration(now); return }
        val ready = evidence.measurementReady
        if (!ready || limbRecovery.paused) {
            if (!limbRecovery.paused) suspendMeasurements(now)
            if (!armed) return
            if (!limbRecovery.canResume(lastFrame, ready)) {
                gameText.text = "人物仍在 · ${evidence.trackingMessage}\n输出和计时暂停 · 保留原坐标 · 剩余 ${remainingText(now)}\n测量稳定后自动继续，无需重新平举"
                return
            }
            val remaining = sessionClock.remaining(now)
            if (hardwareEnabled && (!relay.ready || !relay.timedSession(true, remaining))) { stopAutomation("局部测量恢复后无法重启连接"); return }
            if (!sessionClock.resume(now)) { stopAutomation("本轮时间已到"); return }
            limbRecovery.reset(); intensity.reset(); wavePlayback.reset(); lastMotionCommand = 0
            game?.resumeAfterCalibration(playNow); training?.resumeAfterCalibration(playNow)
            log("测量恢复 · 继续计时")
        }
        val busy = game?.phase in listOf(GamePhase.CUE, GamePhase.REQUEST, GamePhase.RECOVERY)
        when (training?.update(playNow, busy)) {
            TrainingEvent.START -> { silenceMotion(); log("忍耐训练开始") }
            TrainingEvent.END -> { silenceMotion(); log("忍耐训练结束") }
            null -> Unit
        }
        val gameEvent = game?.update(playNow, evidence, training?.active == true)
        gameEvent?.let { curtseySignals.onEvent(it, playNow, game?.settings?.penaltyMs ?: 0) }
        when (gameEvent) {
            GameEvent.CUE -> Unit
            GameEvent.CUE_FAILED -> { stopAutomation("三连脉冲提示未能启动，已停止本轮，不计惩罚"); return }
            GameEvent.PROMPT -> {
                silenceMotion()
                log("行礼判定开始")
                if (cue == null) cue = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65) }.getOrNull()
                cue?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            }
            GameEvent.SUCCESS -> { silenceMotion(); log("行礼成功 · 2 秒站姿调整") }
            GameEvent.PENALTY -> triggerPenalty(now)
            GameEvent.FINISHED -> { stopAutomation("本轮罚站时间已到"); return }
            GameEvent.TRACKING_LOST -> { pauseForCalibration(now); return }
            null -> Unit
        }
        if (!armed) return
        val current = game
        val task = if (current?.phase == GamePhase.CUE) {
            if (current.cueStarted) "三连脉冲提示 · 剩余 ${DisplayFormat.seconds(current.cueUntil - playNow)} · 尚未开始判定"
            else "听好三连脉冲喵 · 尚未开始判定"
        } else if (current?.phase == GamePhase.REQUEST) {
            val left = DisplayFormat.seconds(current.responseDeadline - playNow)
            val action = when (current.stage) {
                CurtseyStage.STAND_FIRST -> "小公主先站好喵，教官确认后再行礼"
                CurtseyStage.BEND_AND_LIFT -> "对啦，小公主的屈膝礼就这样保持喵 · 连续保持 ${currentHoldSeconds()} 秒 · ${(current.holdProgress * 100).toInt()}%"
            }
            "$action · 任务剩余 $left · 过程强度 ${curtseyPreset.read().strength}"
        } else if (outputRunning || now < penaltyDisplayUntil) "超时惩罚${if (hardwareEnabled) "" else "演练"} · 常态 ${readNormal()}＋增量 ${penaltyPreset.read().strength}（受总上限限制）"
        else if (current?.successRecoveryActive == true) "行礼合格啦，小公主喵 · 恢复站姿宽限 ${DisplayFormat.seconds(current.recoveryUntil - playNow)} · 暂停动作违规增量"
        else if (current?.phase == GamePhase.RECOVERY) "小公主，起身站直喵 · 保持常态电流，暂停动作违规增量"
        else if (training?.active == true) "忍耐训练 · 剩余 ${DisplayFormat.seconds(training!!.until - playNow)} · 保持本轮记录的手位"
        else "小公主，罚站时双手收好、脚步站稳喵 · ${if (evidence.outside) "已离开站立范围" else "范围内"}"
        gameText.text = "剩余 ${DisplayFormat.duration(sessionEndAt - playNow)} · " +
            (if (hardwareEnabled) "设备联动" else if (virtualEnabled) "虚拟输出调试" else "演练无输出") + "\n$task" +
            (current?.let { "\n完成 ${it.successes} 次 / 超时 ${it.misses} 次" } ?: "")
        gameText.setTextColor(if (current?.phase == GamePhase.REQUEST) BlushTheme.waiting else accent)
        if (outputRunning || now < penaltyDisplayUntil) {
            silenceMotion(); return
        }
        if (current?.phase == GamePhase.CUE) {
            hudTarget = CurrentRules.normal(cuePreset.read().strength, limitField.text.toString().toIntOrNull() ?: 0)
            hudCurrent = hudTarget; hudWave = cuePreset.read().shape.label
            if (!current.cueStarted) {
                if (!hardwareEnabled) {
                    virtualOutput.clear(); motionOutputLive = false
                    val preset = cuePreset.read().copy(strength = hudTarget, durationSeconds = 3)
                    if (virtualEnabled && preset.strength > 0) {
                        virtualOutput.submit(preset, limitField.text.toString().toIntOrNull() ?: 0, now)
                        motionOutputLive = true
                    }
                    current.startCue(playNow)
                    if (virtualEnabled) recordCompactOutput(preset, now, "三连提示")
                } else {
                    if (!relay.ready) { stopAutomation("三连脉冲提示连接已断开"); return }
                    if (!relay.idle) return
                    val preset = cuePreset.read().copy(durationSeconds = 3)
                    if (!relay.sendEffect(preset, limitField.text.toString().toIntOrNull() ?: 0, clearAll = true)) {
                        stopAutomation("三连脉冲提示发送失败"); return
                    }
                    current.startCue(playNow)
                    motionOutputLive = true; lastMotionCommand = now
                    recordSubmitted(preset, now)
                    recordCompactOutput(preset, now, "三连提示")
                }
            }
            detailText.text = "提示阶段暂停常态电流、动作违规增量和行礼判定"
            return
        }
        if (current?.phase == GamePhase.REQUEST) {
            val preset = CurtseyOutputRules.process(current.phase, evidence.tracked, playNow, current.responseDeadline,
                sessionEndAt, curtseyPreset.read(), limitField.text.toString().toIntOrNull() ?: 0)
            hudTarget = preset?.strength ?: 0; hudCurrent = hudTarget; hudWave = curtseyPreset.read().shape.label
            detailText.text = "屈膝礼过程目标强度 ${preset?.strength ?: 0} · 暂停动作违规增量 · ${if (hardwareEnabled) "设备联动" else if (virtualEnabled) "虚拟输出" else "演练无输出"}"
            if (preset == null) { silenceMotion(); return }
            if (!hardwareEnabled) {
                if (virtualEnabled && (!motionOutputLive || now - lastMotionCommand >= 500)) {
                    virtualOutput.submit(wavePlayback.at(preset, now, "curtsey"), limitField.text.toString().toIntOrNull() ?: 0, now)
                    motionOutputLive = true; lastMotionCommand = now
                    recordCompactOutput(preset, now, "行礼过程")
                }
                return
            }
            // A prompt may have just queued the stop for the previous movement effect.
            if (!relay.ready) { stopAutomation("屈膝礼过程连接已断开"); return }
            if (!relay.idle) return
            if (!motionOutputLive || now - lastMotionCommand >= 500) {
                if (!relay.sendEffect(wavePlayback.at(preset, now, "curtsey"), limitField.text.toString().toIntOrNull() ?: 0)) {
                    stopAutomation("屈膝礼过程电流发送失败"); return
                }
                motionOutputLive = true; lastMotionCommand = now
                recordSubmitted(preset, now)
                recordCompactOutput(preset, now, "行礼过程")
            }
            return
        }
        val maximum = (limitField.text.toString().toIntOrNull() ?: 0).coerceIn(0, 200)
        val inTraining = training?.active == true
        val recovering = current?.phase == GamePhase.RECOVERY
        // A frame measured for a bow cannot stand in for the next ordinary full-body measurement.
        if (evidence.curtseyCore && !recovering) { silenceMotion(); return }
        val normal = CurrentRules.normal(readNormal(), maximum)
        val base = if (inTraining) trainingPreset.read().strength else normal
        val target = if (recovering) normal else readDirectMotion().target(evidence,
            if (inTraining) trainingPreset.read().strength else null)
        val floor = if (inTraining) 0 else normal
        val riseSpeed = MotionRiseSpeed.forMode(readRiseSpeed(), inTraining)
        val strength = CurrentRules.withMotionFloor(floor, target, maximum, intensity, now, riseSpeed)
        hudTarget = target; hudCurrent = strength
        hudWave = (if (inTraining) trainingPreset else motionPreset).read().shape.label
        detailText.text = "常态 $normal · 手部 ${(evidence.hands * 100).toInt()}% · 脚步 ${(evidence.feet * 100).toInt()}% · 目标 $target / 本机请求 $strength / 上限 $maximum"
        if (inTraining && training!!.until - playNow < 1100) { hudCurrent = 0; silenceMotion(); return }
        if (strength == 0) {
            silenceMotion(false)
            if (hardwareEnabled || virtualEnabled) {
                // Zero output supplies a baseline for the first real motion rise; no refresh log.
                val zero = (if (inTraining) trainingPreset else motionPreset).read()
                val weights = readWeights()
                outputLog.update(CompactOutputLog.Sample(now, 0, base, evidence.hands, evidence.feet,
                    weights.hands, weights.feet, if (inTraining) "忍耐训练" else "普通罚站", zero.channel, zero.shape.label))
            }
            return
        }
        if (sessionEndAt - playNow < 1100) { hudCurrent = 0; silenceMotion(); return }
        val preset = (if (inTraining) trainingPreset else motionPreset).read().copy(strength = strength, durationSeconds = 1)
        if (!hardwareEnabled) {
            if (virtualEnabled && now - lastMotionCommand >= 500) {
                virtualOutput.submit(wavePlayback.at(preset, now, if (inTraining) "training" else "normal"), maximum, now)
                motionOutputLive = true; lastMotionCommand = now
                recordMotionIncrement(evidence, base, preset, now, inTraining)
            }
            return
        }
        if (!relay.ready) { stopAutomation("常态电流连接已断开"); return }
        // Stopping a previous preset may have just queued its clear/zero batch.
        if (!relay.idle) return
        if (now - lastMotionCommand >= 500) {
            if (!relay.sendEffect(wavePlayback.at(preset, now, if (inTraining) "training" else "normal"), limitField.text.toString().toIntOrNull() ?: 0)) {
                stopAutomation("运动幅度效果发送失败"); return
            }
            motionOutputLive = true; lastMotionCommand = now
            recordSubmitted(preset, now)
            recordMotionIncrement(evidence, base, preset, now, inTraining)
        }
    }

    private fun silenceMotion(resetLimiter: Boolean = true) {
        if (resetLimiter) { intensity.reset(); wavePlayback.reset() }
        if (motionOutputLive) {
            virtualOutput.clear()
            motionOutputLive = false
            lastSubmitted = null; lastSubmittedUntil = 0
            if (hardwareEnabled && !relay.stopAll()) stopAutomation("无法提交幅度停止指令，请在官方 App 或设备上停止")
            else recordOutputZero()
        }
    }

    private fun outputMode() = if (hardwareEnabled) "设备指令" else if (virtualEnabled) "虚拟输出" else "演练无输出"

    private fun recordOutputZero() {
        outputLog.reset()
        motionAlertUntil = 0
        log("${outputMode()} · 强度 0")
    }

    private fun recordCompactOutput(preset: EffectPreset, now: Long, context: String,
        body: StandingMetrics? = null, base: Int = preset.strength) {
        val weights = readWeights()
        val change = outputLog.update(CompactOutputLog.Sample(now, preset.strength, base,
            body?.hands ?: 0f, body?.feet ?: 0f, weights.hands, weights.feet,
            context, preset.channel, preset.shape.label)) ?: return
        val hands = body?.let { " · 手 ${DisplayFormat.one(it.hands * 100)}% · 脚 ${DisplayFormat.one(it.feet * 100)}%" } ?: ""
        val alert = if (change.sharp) "提醒：${change.source}大幅动作，强度 ${change.from}→${preset.strength} · " else ""
        if (change.sharp) motionAlertUntil = now + 1600
        log("$alert${outputMode()} · $context · ${preset.channel} 强度 ${preset.strength}$hands", important = change.sharp)
    }

    private fun recordMotionIncrement(body: StandingMetrics, base: Int, preset: EffectPreset, now: Long, training: Boolean) {
        recordCompactOutput(preset, now, if (training) "忍耐训练" else "普通罚站", body, base)
    }

    private fun triggerPenalty(now: Long) {
        intensity.reset()
        if (!armed) return
        val limit = limitField.text.toString().toIntOrNull() ?: 0
        val increment = penaltyPreset.read()
        val preset = CurrentRules.penalty(readNormal(), increment, limit, sessionClock.remaining(now))
        if (preset == null) {
            silenceMotion()
            log("行礼超时 · 无额外惩罚输出", important = true)
            return
        }
        log("行礼超时 · 惩罚目标 ${preset.strength} / ${preset.durationSeconds} 秒", important = true)
        penaltyDisplay = preset; penaltyDisplayUntil = now + preset.durationSeconds * 1000L
        if (!hardwareEnabled && !virtualEnabled) return
        virtualOutput.clear()
        if (outputRunning || (hardwareEnabled && !relay.sendEffect(preset, limit, clearAll = true))) {
            stopAutomation("超时预设发送失败"); return
        }
        if (virtualEnabled) virtualOutput.submit(preset, limit, now)
        motionOutputLive = false
        outputRunning = true
        if (hardwareEnabled) recordSubmitted(preset, now)
        recordCompactOutput(preset, now, "惩罚")
        val finish = Runnable {
            outputRunning = false
            outputEnd = null
            lastSubmitted = null; lastSubmittedUntil = 0
            penaltyDisplay = null; penaltyDisplayUntil = 0
            virtualOutput.clear()
            if (!hardwareEnabled || relay.stopAll()) {
                recordOutputZero()
                if (armed) updateSession(SystemClock.elapsedRealtime())
            }
            else stopAutomation("无法提交停止指令，请在官方 App 或设备上停止")
        }
        outputEnd = finish
        main.postDelayed(finish, preset.durationSeconds * 1000L)
    }

    private fun stopAutomation(reason: String) {
        exitMirrorMode()
        outputLog.reset()
        curtseySignals.clear()
        val wasActive = armed || outputRunning || waitingStart
        pausedForTracking = false
        limbRecovery.reset()
        sessionClock.reset(); virtualOutput.clear()
        waitingStart = false; preparation.cancel(); training = null
        hideStartNotice?.let { main.removeCallbacks(it) }; hideStartNotice = null
        if (::startNotice.isInitialized) startNotice.visibility = View.GONE
        lastSubmitted = null; lastSubmittedUntil = 0
        penaltyDisplay = null; penaltyDisplayUntil = 0
        hudTarget = 0; hudCurrent = 0; hudWave = "无"
        armed = false
        outputRunning = false
        outputEnd?.let { main.removeCallbacks(it) }
        outputEnd = null
        motionOutputLive = false; intensity.reset(); wavePlayback.reset()
        val summary = game?.let { " · 完成 ${it.successes} 次 / 超时 ${it.misses} 次" } ?: ""
        game?.stop(); game = null
        if (::armButton.isInitialized) {
            armButton.text = "开始罚站"
            armButton.isEnabled = cameraBound
            setSettingsEnabled(true)
        }
        var stopStatus = " · 本机输出 0"
        if (::relay.isInitialized && ((wasActive && hardwareEnabled) || reason == "手动停止")) {
            val submitted = relay.stopAll()
            if (phoneServer != null) relay.timedSession(false)
            stopStatus = if (submitted) " · 停止指令已提交，设备端未确认" else " · 连接不可用，请在官方 App 或设备上停止"
        }
        log(reason + stopStatus + summary, important = true)
        hardwareEnabled = false
        if (::gameText.isInitialized) { gameText.text = reason + summary; gameText.setTextColor(accent) }
        if (::detailText.isInitialized) detailText.text = reason
        renderGameHud(SystemClock.elapsedRealtime(), true)
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        scroll.addView(root)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        settingsScreen = container
        gameText = label("小公主的罚站课 · 本轮尚未开始", 13, accent, true).apply {
            setPadding(dp(18), dp(10), dp(18), dp(10)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        container.addView(gameText)
        container.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        settingsFooter = LinearLayout(this).apply {
            background = LacePaperDrawable(resources.displayMetrics.density, footer = true)
            setPadding(dp(12), dp(18), dp(12), dp(8))
        }
        settingsFooter.addView(button("停止 / 清空", true) { stopAutomation("手动停止") },
            LinearLayout.LayoutParams(0, dp(64), 1f))
        container.addView(settingsFooter)
        screenHost = FrameLayout(this).apply { setBackgroundColor(bg); addView(container, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(screenHost)
        ViewCompat.setOnApplyWindowInsetsListener(screenHost) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        addWelcomeCard()
        gap(16)
        cameraCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(BlushTheme.surface, 24, BlushTheme.line)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        cameraCard.addView(helpTitle("摄像头 · 姿态识别", "camera_access", 18, accent, true))
        cameraCardStatus = label(cameraStatus, 13, muted).also { cameraCard.addView(it) }
        cameraOpenButton = button("开启摄像头", primary = true) { requestCamera() }
        cameraCard.addView(cameraOpenButton)
        cameraCard.addView(button("摄像头权限设置") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(cameraCard, LinearLayout.LayoutParams(-1, -2))
        cameraStage = CameraStage(this).apply {
            visibility = View.GONE
            viewportChanged = { this@MainActivity.onCameraViewportChanged() }
        }
        cameraStage.setBackgroundColor(bg)
        val cameraFrame = cameraStage.video.apply {
            background = rounded(Color.BLACK, 26); clipToOutline = true
        }
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        overlay = PoseOverlay(this)
        cameraFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        cameraFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        videoFaceCover = VideoFaceCover(preview)
        liveBadge = label("  本地识别 / 不录制  ", 11, accent).apply {
            background = rounded(Color.argb(238, 255, 250, 252), 16, BlushTheme.line); setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        cameraFrame.addView(liveBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(12); topMargin = dp(12)
        })
        signalOverlay = GameSignalOverlay(this)
        cameraFrame.addView(signalOverlay, FrameLayout.LayoutParams(-1, -1))
        cameraSlot = root.childCount
        root.addView(cameraStage, LinearLayout.LayoutParams(-1, -2))
        gap(12)
        cameraControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(cameraControls)
        poseText = label("小公主，先让教官看清站姿喵…", 21, BlushTheme.ink, true)
        cameraControls.addView(helpControl(poseText, "preview", "小公主的姿态校准"))
        detailText = label("小公主，先把手机放稳喵；单人全身、双膝和脚踝都要入镜", 12, muted).also { cameraControls.addView(it) }
        cameraControls.addView(button("关闭摄像头") { closeCamera() })
        cameraControls.addView(button("切换前后摄像头") {
            stopAutomation("切换摄像头后需重新启用")
            tracker.reset(); intensity.reset(); lastEstimate = StandingMetrics()
            videoFaceCover.clear()
            try {
                val currentController = controller ?: return@button
                val next = if (currentController.cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
                    CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                if (currentController.hasCamera(next)) { currentController.cameraSelector = next; onCameraViewportChanged() }
                else toast("设备没有该摄像头")
            } catch (_: Exception) { toast("切换失败，请检查摄像头权限") }
        })
        section("01  课前准备 · 两手机配对")
        sectionBody.addView(helpTitle("本机 Wi-Fi 地址", "phone_ip"))
        phoneIp = spinner(listOf("正在获取局域网地址…"), 0)
        sectionBody.addView(phoneIp)
        refreshPhoneAddresses()
        sectionBody.addView(button("开启两手机连接并显示配对码", primary = true) { startPhoneConnection() })
        val localActions = LinearLayout(this)
        localActions.addView(button("刷新地址") {
            if (phoneServer != null) toast("请先关闭连接，再刷新地址") else refreshPhoneAddresses()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        localActions.addView(button("关闭连接") { stopPhoneConnection("两手机连接已关闭") },
            LinearLayout.LayoutParams(0, -2, 1f))
        sectionBody.addView(localActions)
        relayText = label(phoneStatus, 12, accent).also { sectionBody.addView(it) }
        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        sectionBody.addView(button("高级：使用外部服务器") {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        sectionBody.addView(advanced)
        advanced.addView(helpTitle("外部服务器地址", "external"))
        server = edit("例如 wss://你的服务器 或 ws://电脑局域网IP:9999",
            prefs.getString("server", "") ?: "", false)
        server.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        advanced.addView(server)
        val actions = LinearLayout(this)
        actions.addView(button("连接服务") {
            stopAutomation("重新连接服务")
            saveSettings()
            stopPhoneConnection("已切换至外部服务器")
            relay.connect(server.text.toString().trim())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("显示配对码") { showPairing() }, LinearLayout.LayoutParams(0, -2, 1f))
        advanced.addView(actions)
        fun numberField(title: String, key: String, default: Int): EditText {
            sectionBody.addView(helpTitle(title, key))
            return edit(title, prefs.getInt(key, default).toString(), true).also {
                settingsNumbers[key] = it; sectionBody.addView(it)
            }
        }
        section("02  罚站规矩与电流", "规矩先定好，小公主也要认真喵", collapsed = true)
        durationField = numberField("本轮罚站总时长（分钟，1–90）", "minutes", 3)
        wristNearField = numberField("站姿确认 · 手腕靠近距离（肩宽%，5–150）", "wrist_near_percent", WristCalibration.DEFAULT_NEAR_PERCENT)
        sectionBody.addView(helpTitle("全局最高输出强度（0–200）", "limit", 13, BlushTheme.ink))
        limitField = edit("0–200", prefs.getInt("limit", 0).toString(), true).also { sectionBody.addView(it) }
        settingsNumbers["limit"] = limitField
        sectionBody.addView(helpTitle("站稳时强度 · 常态（0–200）", "normal_strength", 13, BlushTheme.ink))
        normalField = edit("0–200，且不超过总上限", prefs.getInt("normal_strength", 0).toString(), true).also { sectionBody.addView(it) }
        settingsNumbers["normal_strength"] = normalField
        motionPreset = PresetEditor("普通罚站通道与波形", "motion", true, showStrength = false)
        penaltyPreset = PresetEditor("惩罚电流：加在常态电流上的增量", "penalty_delta", strengthLabel = "额外增加的强度（0–200，0 为不额外惩罚）")
        curtseyPreset = PresetEditor("屈膝礼过程电流（0 为关闭）", "curtsey", true)
        cuePreset = PresetEditor("行礼前提示：三连脉冲（3 秒）", "cue", motion = true, cue = true)
        sectionBody.addView(motionPreset.view)
        fun weightField(title: String, key: String, default: Int = 0): EditText {
            sectionBody.addView(helpTitle(title, key))
            return edit(title, prefs.getInt(key, default).toString(), true).also {
                settingsNumbers[key] = it; sectionBody.addView(it)
            }
        }
        riseSpeedField = weightField("普通动作增量上升速度（协议强度单位/秒，1–200）", "motion_rise_rate", MotionRiseSpeed.DEFAULT)
        handWeight = weightField("手部动作最高强度（0 关闭）", "hand_peak")
        handMappingRange = weightField("手部达到最高强度的额外幅度（肩宽%，5–200）", "hand_mapping_range", 65)
        footWeight = weightField("脚步动作最高强度（0 关闭）", "foot_peak")
        footMappingRange = weightField("脚步达到最高强度的额外幅度（肩宽%，5–200）", "foot_mapping_range", 35)
        torsoPeak = weightField("躯干移动最高强度（0 关闭）", "torso_peak")
        torsoMappingRange = weightField("躯干达到最高强度的额外幅度（肩宽%，5–200）", "torso_mapping_range", 50)
        zoneWeight = weightField("整体离开站立范围时的强度（0 关闭）", "zone_peak")
        zoneRadius = weightField("站立范围半径（肩宽百分比，20–200）", "zone_radius", 50)
        outputPreview = label("", 12, accent)
        sectionBody.addView(helpControl(outputPreview, "output_preview", "当前强度对应关系"))
        section("03  宫廷礼仪 · 屈膝礼", "站姿端正，行礼也要合格喵")
        gameToggle = CheckBox(this).apply { text = "启用随机屈膝礼任务"; isChecked = prefs.getBoolean("game", true); setTextColor(BlushTheme.ink) }
        sectionBody.addView(helpControl(gameToggle, "game", "随机屈膝礼任务"))
        gapMinField = numberField("随机提示最短间隔（秒，5–300）", "gap_min", 15)
        gapMaxField = numberField("随机提示最长间隔（秒）", "gap_max", 30)
        responseField = numberField("行礼判定期限（秒，3–30）", "response", 8)
        curtseyRangeField = numberField("屈膝礼判定范围（肩宽百分比，10–80）", "curtsey_range", 30)
        sectionBody.addView(helpTitle("屈膝礼下蹲连续保持时间", "curtsey_hold"))
        curtseyHoldField = spinner(curtseyHoldOptions.map { "${it / 1000f} 秒" },
            curtseyHoldOptions.indexOf(prefs.getLong("curtsey_hold_ms", 500L)).coerceAtLeast(0))
        sectionBody.addView(curtseyHoldField)
        sectionBody.addView(cuePreset.view)
        sectionBody.addView(curtseyPreset.view)
        sectionBody.addView(penaltyPreset.view)
        section("04  进阶考验 · 忍耐训练", "教官陪着小公主，站姿也要守好喵", collapsed = !prefs.getBoolean("training", false))
        trainingToggle = CheckBox(this).apply { text = "启用随机忍耐训练"; isChecked = prefs.getBoolean("training", false); setTextColor(BlushTheme.ink) }
        sectionBody.addView(helpControl(trainingToggle, "training", "随机忍耐训练"))
        sectionBody.addView(helpTitle("忍耐训练上升速度：固定 30 单位/秒", "training_rate"))
        trainGapMin = numberField("训练最短间隔（秒，5–300）", "train_gap_min", 30)
        trainGapMax = numberField("训练最长间隔（秒）", "train_gap_max", 60)
        trainDurationMin = numberField("训练最短持续时间（秒，1–30）", "train_duration_min", 3)
        trainDurationMax = numberField("训练最长持续时间（秒）", "train_duration_max", 8)
        trainingPreset = PresetEditor("训练基础强度与波形", "training", true)
        sectionBody.addView(trainingPreset.view)
        section("05  养成模式", "先选模式，再来上罚站课喵")
        virtualToggle = CheckBox(this).apply {
            text = "虚拟输出调试（无需连接电击器）"; isChecked = true; setTextColor(accent)
        }
        sectionBody.addView(helpControl(virtualToggle, "virtual", "虚拟输出调试"))
        hideSkeletonToggle = CheckBox(this).apply {
            text = "隐藏骨架"; isChecked = prefs.getBoolean("hide_skeleton", false)
            setTextColor(BlushTheme.ink)
        }
        sectionBody.addView(helpControl(hideSkeletonToggle, "hide_skeleton", "隐藏骨架"))
        overlay.visibility = if (hideSkeletonToggle.isChecked || mirrorMode.active) View.INVISIBLE else View.VISIBLE
        hideSkeletonToggle.setOnCheckedChangeListener { _, checked ->
            overlay.visibility = if (checked || mirrorMode.active) View.INVISIBLE else View.VISIBLE
        }
        headCoverToggle = CheckBox(this).apply {
            // The legacy display preference remains compatible; no new detector is initialized.
            text = "实验功能：视频椭圆遮脸"; isChecked = prefs.getBoolean("face_mosaic", false)
            setTextColor(BlushTheme.ink)
        }
        sectionBody.addView(helpControl(headCoverToggle, "face_mosaic", "实验功能：视频椭圆遮脸"))
        headCoverAdjustments = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val coverBody = sectionBody
        sectionBody = headCoverAdjustments
        numberField("遮挡整体大小（%，20–400）", "face_cover_size", 80)
        numberField("遮挡宽度比例（%，20–300）", "face_cover_width", 100)
        numberField("遮挡高度比例（%，20–300）", "face_cover_height", 75)
        numberField("左右微调（画面宽度%，−50–50，右为正）", "face_cover_x", 0).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        numberField("上下微调（画面高度%，−50–50，下为正）", "face_cover_y", 0).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        sectionBody.addView(button("重置遮脸位置与大小") {
            FaceCoverSettings.defaults.forEach { (key, value) -> settingsNumbers.getValue(key).setText(value.toString()) }
        })
        sectionBody = coverBody
        sectionBody.addView(headCoverAdjustments)
        val coverWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { refreshFaceCoverSettings() }
        }
        FaceCoverSettings.defaults.keys.forEach { settingsNumbers.getValue(it).addTextChangedListener(coverWatcher) }
        refreshFaceCoverSettings()
        videoFaceCover.setEnabled(headCoverToggle.isChecked)
        headCoverAdjustments.visibility = if (headCoverToggle.isChecked) View.VISIBLE else View.GONE
        headCoverToggle.setOnCheckedChangeListener { _, checked ->
            videoFaceCover.setEnabled(checked)
            headCoverAdjustments.visibility = if (checked) View.VISIBLE else View.GONE

        }
        mirrorToggle = CheckBox(this).apply {
            text = "镜子模式"; isChecked = prefs.getBoolean("mirror_mode", false)
            setTextColor(BlushTheme.ink)
        }
        sectionBody.addView(helpControl(mirrorToggle, "mirror_mode", "镜子模式"))
        outputToggle = CheckBox(this).apply { text = "启用本轮设备输出（默认关闭）"; isChecked = false; setTextColor(accent) }
        sectionBody.addView(helpControl(outputToggle, "output", "本轮设备输出"))
        modeHint = label("", 13, accent, true).also { sectionBody.addView(it) }
        virtualToggle.setOnCheckedChangeListener { _, checked ->
            if (checked) outputToggle.isChecked = false
            updateModeHint()
        }
        outputToggle.setOnCheckedChangeListener { _, checked ->
            if (checked) virtualToggle.isChecked = false
            updateModeHint()
        }
        updateModeHint()
        sectionBody.addView(helpTitle("设置代码 · 一键保存与恢复", "settings_code", 16, accent, true))
        exportSettingsButton = button("导出全部设置代码（复制）") { exportSettingsCode() }
        importSettingsButton = button("导入设置代码（从剪贴板）") { importSettingsCode() }
        sectionBody.addView(exportSettingsButton); sectionBody.addView(importSettingsButton)
        gap(8)
        armButton = button("开始罚站", primary = true) {
            if (armed || waitingStart) stopAutomation("已关闭自动联动") else enableAutomation()
        }.apply { isEnabled = false }
        settingsFooter.addView(armButton, 0, LinearLayout.LayoutParams(0, dp(64), 1f))
        section("06  教官手册 · 本轮记录", "罚站和行礼，小动作都记在这里喵", collapsed = true)
        logText = label("小公主的本轮记录还是空白喵。", 12, muted).also { sectionBody.addView(it) }
        sectionBody.addView(button("查看 / 复制本轮精简日志") { showSessionLog() })
        gap(12)
        sectionBody.addView(button("摄像头权限") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        bindOutputPreview()
        buildGameScreen()
    }

    private fun refreshFaceCoverSettings() {
        val values = FaceCoverSettings.defaults.mapValues { (key, _) ->
            val field = settingsNumbers.getValue(key)
            val spec = SettingsCode.numbers.getValue(key)
            val value = field.text.toString().toIntOrNull()
            field.error = if (value != null && value in spec.range) null else "范围 ${spec.range.first}–${spec.range.last}"
            value
        }
        val size = values["face_cover_size"] ?: return
        val x = values["face_cover_x"] ?: return
        val y = values["face_cover_y"] ?: return
        val width = values["face_cover_width"] ?: return
        val height = values["face_cover_height"] ?: return
        val settings = FaceCoverSettings(size, x, y, width, height)
        if (settings.valid()) videoFaceCover.settings = settings
    }

    private fun migrateOutputSettings() {
        if (prefs.getBoolean("direct_motion_v1", false)) return
        val normal = prefs.getInt("normal_strength", 0).coerceIn(0, 200)
        val limit = prefs.getInt("limit", 0).coerceIn(0, 200)
        prefs.edit().putInt("hand_peak", DirectMotionSettings.legacyPeak(normal, prefs.getInt("hand_weight", 0), limit))
            .putInt("foot_peak", DirectMotionSettings.legacyPeak(normal, prefs.getInt("foot_weight", 0), limit))
            .putInt("zone_peak", DirectMotionSettings.legacyPeak(normal, prefs.getInt("zone_weight", 0), limit))
            .putInt("torso_peak", 0).putBoolean("direct_motion_v1", true).apply()
    }

    private fun bindOutputPreview() {
        fun refresh() {
            val rules = readDirectMotion()
            if (!rules.valid() || !readMotionMapping().valid()) {
                outputPreview.text = "当前设置未完成：各最高强度需为 0 或在常态至全局最高之间；额外幅度需为 5–200% 肩宽"
                return
            }
            fun peak(value: Int, range: EditText) = if (value == 0) "关闭" else
                "$value · 半幅 ${(rules.normal + value) / 2}（额外幅度 ${range.text}% 肩宽）"
            outputPreview.text = "站稳 ${rules.normal} · 全局最高 ${rules.limit}\n" +
                "手部最高 ${peak(rules.handPeak, handMappingRange)}\n" +
                "脚步最高 ${peak(rules.footPeak, footMappingRange)}\n" +
                "躯干最高 ${peak(rules.torsoPeak, torsoMappingRange)}\n" +
                "离位 ${if (rules.outsidePeak == 0) "关闭" else rules.outsidePeak.toString()}" +
                if (trainingToggle.isChecked) "\n训练基础 ${trainingPreset.read().strength} · 含动作最高 ${rules.trainingMaximum(trainingPreset.read().strength)}" else ""
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { refresh() }
        }
        listOf(limitField, normalField, handWeight, footWeight, torsoPeak, zoneWeight,
            handMappingRange, footMappingRange, torsoMappingRange, trainingPreset.strengthInput()).forEach { it.addTextChangedListener(watcher) }
        trainingToggle.setOnCheckedChangeListener { _, _ -> refresh() }
        refresh()
    }

    private fun buildGameScreen() {
        gameScreen = FrameLayout(this).apply { setBackgroundColor(bg); visibility = View.GONE }
        screenHost.addView(gameScreen, FrameLayout.LayoutParams(-1, -1))
        gameVideoHost = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        gameScreen.addView(gameVideoHost, FrameLayout.LayoutParams(-1, -1))
        gameFooter = LinearLayout(this).apply { setBackgroundColor(BlushTheme.surface); setPadding(dp(8), dp(4), dp(8), dp(6)) }
        gameFooter.addView(button("返回设置（停止）") {
            stopAutomation("返回设置，已停止本轮"); leaveGameScreen()
        }, LinearLayout.LayoutParams(0, dp(60), 1f))
        gameFooter.addView(button("停止罚站", true) { stopAutomation("手动停止") }, LinearLayout.LayoutParams(0, dp(60), 1f))
        gameScreen.addView(gameFooter, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        gameHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(Color.argb(240, 255, 250, 252), 22, BlushTheme.line)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        sessionBadge = label("课前校准 · 罚站尚未开始", 17, accent, true)
        playStatus = label("", 13, BlushTheme.ink)
        gameHeader.addView(sessionBadge); gameHeader.addView(playStatus)
        gameVideoHost.addView(gameHeader, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(10); rightMargin = dp(136); topMargin = dp(10)
        })
        consoleVisible = prefs.getBoolean("console_visible", true)
        consoleToggle = button(if (consoleVisible) "隐藏控制台" else "显示控制台") {
            consoleVisible = !consoleVisible
            consolePanel.visibility = if (consoleVisible) View.VISIBLE else View.GONE
            consoleToggle.text = if (consoleVisible) "隐藏控制台" else "显示控制台"
            prefs.edit().putBoolean("console_visible", consoleVisible).apply()
            renderGameHud(SystemClock.elapsedRealtime(), true)
        }
        gameVideoHost.addView(consoleToggle, FrameLayout.LayoutParams(dp(124), dp(52), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(6); topMargin = dp(6)
        })
        consolePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(Color.argb(243, 255, 250, 252), 24, BlushTheme.line)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = if (consoleVisible) View.VISIBLE else View.GONE
        }
        consolePanel.addView(helpTitle("教官手册 · 实时控制台", "console", 13, accent, true))
        val consoleScroll = ScrollView(this)
        consoleText = label("教官等小公主准备好喵…", 11, BlushTheme.ink).apply { typeface = Typeface.MONOSPACE }
        consoleScroll.addView(consoleText)
        consolePanel.addView(consoleScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        consolePanel.addView(button("本轮精简日志 / 复制") { showSessionLog() }, LinearLayout.LayoutParams(-1, dp(48)))
        gameVideoHost.addView(consolePanel, FrameLayout.LayoutParams(dp(280), dp(280), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(10); bottomMargin = dp(86)
        })
        gameVideoHost.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (right > left && bottom > top) {
                val size = consolePanel.layoutParams as FrameLayout.LayoutParams
                val width = minOf(dp(280), ((right - left) * .75f).toInt())
                val height = minOf(dp(300), ((bottom - top) * .45f).toInt())
                if (size.width != width || size.height != height) {
                    size.width = width; size.height = height; consolePanel.layoutParams = size
                }
            }
        }
        startNotice = label("罚站已开始喵", 28, accent, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(24), dp(22), dp(24), dp(22))
            background = rounded(Color.argb(248, 255, 250, 252), 28, BlushTheme.line); visibility = View.GONE
        }
        gameVideoHost.addView(startNotice, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        mirrorExit = View(this).apply {
            contentDescription = "退出镜子模式，显示控制按钮"; visibility = View.GONE
            setOnClickListener { exitMirrorMode(); renderGameHud(SystemClock.elapsedRealtime(), true) }
        }
        gameVideoHost.addView(mirrorExit, FrameLayout.LayoutParams(-1, -1))
    }

    private fun exitMirrorMode() {
        mirrorMode.exit()
        motionAlertUntil = 0
        applyMirrorUi()
    }

    private fun applyMirrorUi() {
        if (!::mirrorExit.isInitialized) return
        val clean = mirrorMode.active
        cameraStage.setBackgroundColor(if (clean) Color.BLACK else bg)
        val visibility = if (clean) View.INVISIBLE else View.VISIBLE
        // All controls overlay an unchanged camera host: hiding them cannot rescale calibrated points.
        gameHeader.visibility = visibility; gameFooter.visibility = visibility
        overlay.visibility = if (clean || hideSkeletonToggle.isChecked) View.INVISIBLE else View.VISIBLE
        liveBadge.visibility = visibility; consoleToggle.visibility = visibility
        consolePanel.visibility = if (!clean && consoleVisible) View.VISIBLE else View.GONE
        mirrorExit.visibility = if (clean) View.VISIBLE else View.GONE
        if (clean) startNotice.visibility = View.GONE
    }

    private fun onCameraViewportChanged() {
        if (::videoFaceCover.isInitialized) videoFaceCover.clear()
        analysisResumeAt = SystemClock.elapsedRealtime() + 500
        if (::overlay.isInitialized) overlay.pose = null
    }

    private fun showGameScreen() {
        hideStartNotice?.let { main.removeCallbacks(it) }; hideStartNotice = null
        startNotice.visibility = View.GONE
        currentFocus?.let { focused ->
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(focused.windowToken, 0)
        }
        (cameraStage.parent as? ViewGroup)?.removeView(cameraStage)
        cameraStage.fillSpace = true
        gameVideoHost.addView(cameraStage, 0, FrameLayout.LayoutParams(-1, -1))
        settingsScreen.visibility = View.GONE; gameScreen.visibility = View.VISIBLE
        overlay.pose = null
        renderGameHud(SystemClock.elapsedRealtime(), true)
    }

    private fun leaveGameScreen() {
        exitMirrorMode()
        (cameraStage.parent as? ViewGroup)?.removeView(cameraStage)
        cameraStage.fillSpace = false
        root.addView(cameraStage, cameraSlot, LinearLayout.LayoutParams(-1, -2))
        gameScreen.visibility = View.GONE; settingsScreen.visibility = View.VISIBLE
        overlay.pose = null
    }

    private fun announceGameStarted(resuming: Boolean = false) {
        startNotice.setTextColor(BlushTheme.success)
        val title = if (resuming) "罚站已继续喵" else "罚站已开始喵"
        startNotice.text = "$title\n${if (hardwareEnabled) "设备联动已启用" else if (virtualEnabled) "虚拟输出调试" else "演练模式 · 无输出"}"
        startNotice.visibility = View.VISIBLE
        startNotice.announceForAccessibility(title)
        if (cue == null) cue = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65) }.getOrNull()
        cue?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
        hideStartNotice?.let { main.removeCallbacks(it) }
        hideStartNotice = Runnable { startNotice.visibility = View.GONE; hideStartNotice = null }
            .also { main.postDelayed(it, 2200) }
        renderGameHud(SystemClock.elapsedRealtime(), true)
    }

    private fun recordSubmitted(preset: EffectPreset, now: Long) {
        lastSubmitted = preset
        lastSubmittedUntil = now + preset.durationSeconds * 1000L
    }

    private fun renderGameHud(now: Long, force: Boolean = false) {
        if (!::gameScreen.isInitialized || gameScreen.visibility != View.VISIBLE || (!force && now - lastHudDraw < 100)) return
        lastHudDraw = now
        overlay.curtseyTarget = if (armed && !limbRecovery.paused && game?.phase == GamePhase.REQUEST && lastEstimate.measurementReady) {
            if (game?.stage == CurtseyStage.BEND_AND_LIFT) lastEstimate.curtseyMatch else lastEstimate.curtseyReturnMatch
        } else null
        sessionBadge.text = when {
            armed && limbRecovery.paused -> "Ⅱ 人物仍在 · 局部测量暂停"
            armed -> "● 罚站进行中 · ${if (hardwareEnabled) "设备联动" else if (virtualEnabled) "虚拟模拟" else "演练无输出"}"
            pausedForTracking -> "Ⅱ 人物丢失 · 计时暂停 · 等待校准"
            waitingStart && preparation.phase == PreparationPhase.CAPTURE_CURTSEY -> "记录小公主的屈膝礼 · 尚未开始"
            waitingStart && preparation.phase == PreparationPhase.RETURN_TO_NEUTRAL -> "记录罚站姿势 · 尚未开始"
            waitingStart -> "小公主先定位喵 · 尚未开始"
            gameHasStarted -> "■ 本轮罚站结束啦喵"
            else -> "教官等小公主喵 · 尚未开始 / 准备已取消"
        }
        sessionBadge.setTextColor(if (limbRecovery.paused) BlushTheme.waiting else if (armed) BlushTheme.success else if (waitingStart) BlushTheme.waiting else BlushTheme.danger)
        playStatus.text = gameText.text
        applyMirrorUi()
        signalOverlay.frame = curtseySignals.sample(sessionClock.elapsed(now), armed,
            pausedForTracking || limbRecovery.paused, game?.phase, game?.cueUntil ?: 0,
            game?.settings?.cueMs ?: 3000, game?.responseDeadline ?: 0,
            game?.settings?.responseMs ?: 1)
        // A short green border confirms startup even when the text and skeleton are hidden.
        if (mirrorMode.active && now - mirrorStartedAt < 1200 && signalOverlay.frame.kind == CurtseySignalKind.NONE)
            signalOverlay.frame = CurtseySignalFrame(CurtseySignalKind.SUCCESS)
        if (armed && !limbRecovery.paused && now < motionAlertUntil && signalOverlay.frame.kind == CurtseySignalKind.NONE)
            signalOverlay.frame = CurtseySignalFrame(CurtseySignalKind.MOTION_RISE, bright = now % 400 < 200)
        if (mirrorMode.active || !consoleVisible) return
        val tracked = lastEstimate.tracked && lastFrame > 0 && now - lastFrame <= 400
        val phase = game?.phase
        val punishment = armed && (outputRunning || now < penaltyDisplayUntil)
        val phaseText = when {
            limbRecovery.paused -> "保留原校准，等待局部测量稳定"
            pausedForTracking -> "暂停 / 重新定位与站姿校准"
            waitingStart && preparation.phase == PreparationPhase.CAPTURE_CURTSEY -> "记录屈膝礼 ${DisplayFormat.one(preparation.progress * 3f)} / 3.0 秒"
            waitingStart && preparation.phase == PreparationPhase.RETURN_TO_NEUTRAL -> "记录站姿 ${DisplayFormat.one(lastEstimate.calibrationProgress * 5f)} / 5.0 秒"
            waitingStart -> "平举双臂确认位置 ${(preparation.progress * 100).toInt()}%"
            !armed -> "罚站未开始"
            punishment -> "超时惩罚${if (hardwareEnabled) "" else "演练"}"
            phase == GamePhase.CUE -> "三连脉冲提示（尚未判定）"
            phase == GamePhase.REQUEST -> "屈膝礼判定"
            game?.successRecoveryActive == true -> "成功 · 2 秒恢复站姿宽限 / 常态电流"
            phase == GamePhase.RECOVERY -> "恢复站姿 / 常态电流"
            training?.active == true -> "忍耐训练"
            else -> "普通罚站 / 常态电流"
        }
        val penalty = if (punishment) penaltyDisplay else null
        val target = if (!armed || !tracked) 0 else penalty?.strength ?: hudTarget
        val current = if (!armed || !tracked) 0 else penalty?.strength ?: hudCurrent
        val wave = penalty?.shape?.label ?: hudWave
        val submitted = lastSubmitted?.takeIf { armed && hardwareEnabled && now < lastSubmittedUntil }
        val simulated = if (armed && virtualEnabled) virtualOutput.sample(now) else null
        consoleText.text = buildString {
            val mode = when {
                limbRecovery.paused -> "局部测量不足（人物仍在 / 坐标保留 / 输出 0）"
                pausedForTracking -> "人物丢失暂停（输出 0 / 计时冻结）"
                waitingStart -> "定位校准中（不输出、不计时）"
                !armed -> "罚站未开始"
                hardwareEnabled -> "设备联动"
                virtualEnabled -> "虚拟输出调试（本机模拟）"
                else -> "演练 / 无设备输出"
            }
            append("模式：$mode\n")
            append("阶段：$phaseText\n")
            if (sessionClock.started) append("剩余 ${remainingText(now)}${if (pausedForTracking || limbRecovery.paused) "（暂停）" else ""}\n")
            append("手 ${DisplayFormat.one(lastEstimate.hands * 100)}% · 脚 ${DisplayFormat.one(lastEstimate.feet * 100)}%\n")
            if (armed && lastEstimate.measurementReady && !lastEstimate.curtseyCore) {
                fun amplitude(reading: MotionMappingReading?, rangePercent: Int): String = reading?.let {
                    "${DisplayFormat.one(it.excess / it.range * rangePercent)} / $rangePercent% 肩宽"
                } ?: "—"
                val mapping = readMotionMapping()
                append("手额外幅度 ${amplitude(lastEstimate.handMapping, mapping.handRangePercent)}\n")
                append("脚额外幅度 ${amplitude(lastEstimate.footMapping, mapping.footRangePercent)}\n")
            }
            if (!tracked || !lastEstimate.measurementReady || waitingStart) append("${lastEstimate.trackingMessage}\n")
            if (armed && phase == GamePhase.REQUEST) append("行礼保持 ${(game!!.holdProgress * 100).toInt()}%\n")
            append("目标 $target · 本机计算 $current · 上限 ${limitField.text}\n")
            append("波形：$wave\n")
            if (virtualEnabled) {
                append(if (simulated != null) "虚拟输出：${simulated.preset.channel} / ${simulated.preset.strength}\n"
                    else "虚拟输出：0（无有效片段）\n")
            } else append(if (submitted != null) "最近提交：${submitted.channel} / ${submitted.strength}（硬件未确认）\n" else "本机当前无有效设备输出片段\n")
            if (hardwareEnabled) {
                val report = relay.deviceReport
                if (report == null) append("官方 App 回报：尚未收到设备强度与状态\n")
                else {
                    append(report.summary()).append("（非电流测量）\n")
                    append("RPC：${report.rpc} · 待清理 ${report.waitingCleanup}\n")
                }
            }
            if (::relay.isInitialized) append("连接：${relay.status}\n")
            append("\n最近事件\n")
            append(events.take(6).joinToString("\n\n").ifEmpty { "尚无事件" })
        }
    }

    private inner class PresetEditor(title: String, private val key: String, private val motion: Boolean = false,
        strengthLabel: String = "输出强度", private val cue: Boolean = false, private val showStrength: Boolean = true) {
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(8))
            background = rounded(BlushTheme.lilac, 20, BlushTheme.line)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(8) }
        }
        private val strength = edit("强度", prefs.getInt("${key}_strength", 0).toString(), true)
        private val channel = spinner(listOf("A 通道", "B 通道"), prefs.getInt("${key}_channel", 0))
        private val duration = spinner((1..30).map { "$it 秒" }, prefs.getInt("${key}_duration", 0))
        private val options = PulseShape.entries.filter { it.cueOnly == cue }
        private var selectedShape = options.firstOrNull { it.name == prefs.getString("${key}_waveform", "") }
            ?: (if (cue) PulseShape.TRIPLE_SHORT else PulseShape.entries.getOrNull(prefs.getInt("${key}_shape", 1))
                ?.takeIf { !it.cueOnly } ?: PulseShape.RAMP)
        private val shape = button("选择波形：${selectedShape.label}") { showWaveforms() }
        private fun showWaveforms() {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setCustomTitle(dialogHelpTitle(if (cue) "三连脉冲提示形式" else "选择播放波形", "waveform"))
                .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(selectedShape)) { dialog, position ->
                    selectedShape = options[position]
                    shape.text = "选择波形：${selectedShape.label}"
                    dialog.dismiss()
                }.setNegativeButton("取消", null).show()
        }
        init {
            view.addView(helpTitle(title, key, 16, BlushTheme.ink, true))
            if (showStrength) { view.addView(helpTitle(strengthLabel, "${key}_strength", 11)); view.addView(strength) }
            val row = LinearLayout(this@MainActivity)
            for (control in if (motion) listOf(channel) else listOf(channel, duration)) row.addView(control, LinearLayout.LayoutParams(0, dp(50), 1f))
            view.addView(row)
            view.addView(shape)
        }
        fun read() = EffectPreset(if (channel.selectedItemPosition == 0) "A" else "B",
            strength.text.toString().toIntOrNull() ?: 0, if (cue) 3 else if (motion) 1 else duration.selectedItemPosition + 1,
            selectedShape)
        fun writeSnapshot(values: MutableMap<String, Any>) {
            values["${key}_strength"] = if (showStrength) strength.text.toString().toIntOrNull()
                ?: throw SettingsCode.Invalid("$key 强度请填写整数") else 0
            values["${key}_channel"] = channel.selectedItemPosition
            values["${key}_duration"] = read().durationSeconds - 1
            values["${key}_waveform"] = selectedShape.name
        }
        fun applySnapshot(values: Map<String, Any>) {
            strength.setText((values.getValue("${key}_strength") as Int).toString())
            channel.setSelection(values.getValue("${key}_channel") as Int)
            duration.setSelection(values.getValue("${key}_duration") as Int)
            selectedShape = options.first { it.name == values["${key}_waveform"] }
            shape.text = "选择波形：${selectedShape.label}"
        }
        fun strengthInput() = strength
        fun save(editor: android.content.SharedPreferences.Editor) {
            editor.putInt("${key}_strength", read().strength).putInt("${key}_channel", channel.selectedItemPosition)
                .putInt("${key}_duration", duration.selectedItemPosition).putString("${key}_waveform", selectedShape.name)
        }
        fun setEnabled(enabled: Boolean) { listOf(strength, channel, duration, shape).forEach { it.isEnabled = enabled } }
    }

    private fun setSettingsEnabled(enabled: Boolean) {
        if (!::motionPreset.isInitialized) return
        motionPreset.setEnabled(enabled); penaltyPreset.setEnabled(enabled); curtseyPreset.setEnabled(enabled); trainingPreset.setEnabled(enabled); cuePreset.setEnabled(enabled)
        limitField.isEnabled = enabled; normalField.isEnabled = enabled; server.isEnabled = enabled
        importSettingsButton.isEnabled = enabled; exportSettingsButton.isEnabled = enabled
        FaceCoverSettings.defaults.keys.forEach { settingsNumbers.getValue(it).isEnabled = enabled }
        for (index in 0 until headCoverAdjustments.childCount) headCoverAdjustments.getChildAt(index).isEnabled = enabled
        listOf(gameToggle, outputToggle, virtualToggle, headCoverToggle, hideSkeletonToggle, mirrorToggle, durationField, gapMinField, gapMaxField, responseField, curtseyHoldField, curtseyRangeField, wristNearField, torsoPeak, torsoMappingRange, handWeight, footWeight, handMappingRange, footMappingRange, riseSpeedField, zoneWeight, zoneRadius, trainingToggle, trainGapMin, trainGapMax, trainDurationMin, trainDurationMax).forEach { it.isEnabled = enabled }
    }

    private fun refreshPhoneAddresses() {
        val addresses = LocalAddresses.discover()
        phoneIp.adapter = choiceAdapter(addresses.ifEmpty { listOf("未找到 Wi-Fi 地址，请先连接局域网") })
        phoneIp.isEnabled = addresses.isNotEmpty() && phoneServer == null
    }

    private fun startPhoneConnection() {
        if (phoneServer?.hasController == true && relay.ownId.isNotEmpty()) { showPairing(); return }
        val selected = phoneIp.selectedItem?.toString() ?: ""
        if (selected !in LocalAddresses.discover()) {
            refreshPhoneAddresses()
            toast("先让两部手机连上同一 Wi-Fi，再刷新喵")
            return
        }
        stopAutomation("开始两手机连接")
        relay.disconnect()
        val previous = phoneServer
        val epoch = ++phoneEpoch
        phoneHost = selected
        pendingPairing = true
        phoneStatus = "正在开启两手机连接…"
        phoneIp.isEnabled = false
        val local = PhoneRelayServer(InetSocketAddress("0.0.0.0", 9999), { port ->
            main.post {
                if (epoch == phoneEpoch && !isDestroyed) {
                    phonePort = port
                    phoneStatus = "本机连接服务已开启 · $phoneHost:$port"
                    relay.connect("ws://127.0.0.1:$port")
                }
            }
        }, { reason ->
            main.post {
                if (epoch == phoneEpoch && !isDestroyed) {
                    pendingPairing = false
                    stopAutomation(reason)
                    phoneStatus = reason
                    updateRelayUi()
                }
            }
        }, { reason ->
            main.post { if (epoch == phoneEpoch && !isDestroyed) stopPhoneConnection(reason) }
        }, appV4 = true)
        phoneServer = local
        updateRelayUi()
        phoneWorker.execute {
            previous?.shutdown()
            if (epoch == phoneEpoch) local.start() else local.shutdown()
        }
    }

    private fun stopPhoneConnection(reason: String) {
        val local = phoneServer ?: return
        stopAutomation(reason)
        phoneEpoch++
        pendingPairing = false
        phoneServer = null
        relay.disconnect()
        phoneStatus = reason
        phoneWorker.execute { local.shutdown() }
        refreshPhoneAddresses()
        updateRelayUi()
    }

    private fun updateRelayUi() {
        if (!::relay.isInitialized || !::relayText.isInitialized) return
        val local = if (phoneServer != null) "$phoneStatus\n" else ""
        relayText.text = local + relay.status + if (relay.report.isNotBlank()) "\nApp 消息：${relay.report}" else ""
        val problem = relay.deviceReport?.outputProblems().orEmpty()
        if (problem != lastDeviceProblem) {
            lastDeviceProblem = problem
            if (problem.isNotEmpty()) log("官方 App 回报异常：$problem", important = true)
        }
        armButton.isEnabled = cameraBound
        if (armed && !limbRecovery.paused && relay.report == "session-ended") stopAutomation("本轮时间已到，中继已停止输出")
        if (pendingPairing && relay.ownId.isNotEmpty() && active && !isDestroyed) {
            pendingPairing = false
            main.post { if (active && !isDestroyed && phoneServer != null) showPairing() }
        }
    }

    private fun showPairing() {
        stopAutomation("配对前已暂停自动联动")
        if (relay.ownId.isEmpty()) { toast("请先连接 V3 服务并等待分配标识"); return }
        val address = if (phoneServer != null) "ws://$phoneHost:$phonePort" else relay.address
        val link = if (phoneServer != null) V4AppPeer.pairingLink(address, relay.ownId) else V3Protocol.pairingLink(address, relay.ownId)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)) }
        try {
            val matrix = MultiFormatWriter().encode(link, BarcodeFormat.QR_CODE, 720, 720,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
            }
            val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            box.addView(ImageView(this).apply { setImageBitmap(bitmap); contentDescription = "DGLAB 4 的配对二维码" },
                LinearLayout.LayoutParams(-1, dp(280)))
            if (phoneServer != null) {
                box.addView(label("本机地址：$phoneHost:$phonePort", 12, accent))
            }
            box.addView(button("复制配对链接") {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("DGLAB 配对链接", link))
                toast("本次配对链接复制好啦喵")
            })
            android.app.AlertDialog.Builder(this)
                .setCustomTitle(dialogHelpTitle(if (phoneServer != null) "DGLAB 4 配对码" else "V3 兼容配对码",
                    if (phoneServer != null) "pairing" else "pairing_v3"))
                .setView(box).setPositiveButton("完成", null).show()
        } catch (_: Exception) { toast("生成二维码失败，请检查服务器地址长度") }
    }

    private fun saveSettings() {
        if (!::motionPreset.isInitialized) return
        val editor = prefs.edit().putString("server", server.text.toString().trim()).putInt("normal_strength", readNormal())
            .putInt("limit", limitField.text.toString().toIntOrNull() ?: 0)
        motionPreset.save(editor); penaltyPreset.save(editor); curtseyPreset.save(editor); trainingPreset.save(editor); cuePreset.save(editor)
        editor.putInt("hand_peak", handWeight.text.toString().toIntOrNull() ?: 0)
            .putInt("torso_peak", torsoPeak.text.toString().toIntOrNull() ?: 0)
            .putInt("torso_mapping_range", readMotionMapping().torsoRangePercent)
            .putInt("motion_rise_rate", readRiseSpeed())
            .putInt("hand_mapping_range", readMotionMapping().handRangePercent)
            .putInt("foot_mapping_range", readMotionMapping().footRangePercent)
            .putInt("foot_peak", footWeight.text.toString().toIntOrNull() ?: 0)
            .putInt("zone_peak", zoneWeight.text.toString().toIntOrNull() ?: 0)
            .putInt("zone_radius", readRadius()).putBoolean("training", trainingToggle.isChecked)
            .putInt("train_gap_min", trainGapMin.text.toString().toIntOrNull() ?: 30)
            .putInt("train_gap_max", trainGapMax.text.toString().toIntOrNull() ?: 60)
            .putInt("train_duration_min", trainDurationMin.text.toString().toIntOrNull() ?: 3)
            .putInt("train_duration_max", trainDurationMax.text.toString().toIntOrNull() ?: 8)
        FaceCoverSettings.defaults.forEach { (key, default) ->
            val spec = SettingsCode.numbers.getValue(key)
            editor.putInt(key, settingsNumbers.getValue(key).text.toString().toIntOrNull()?.takeIf { it in spec.range } ?: default)
        }
        editor.putBoolean("face_mosaic", headCoverToggle.isChecked)
        editor.putBoolean("hide_skeleton", hideSkeletonToggle.isChecked)
        editor.putBoolean("mirror_mode", mirrorToggle.isChecked)
        editor.putBoolean("game", gameToggle.isChecked).putInt("minutes", durationField.text.toString().toIntOrNull() ?: 3)
            .putInt("curtsey_range", readCurtseyRange()).putInt("wrist_near_percent", readWristNear())
            .putLong("curtsey_hold_ms", curtseyHoldOptions[curtseyHoldField.selectedItemPosition.coerceIn(curtseyHoldOptions.indices)])
            .putInt("gap_min", gapMinField.text.toString().toIntOrNull() ?: 15).putInt("gap_max", gapMaxField.text.toString().toIntOrNull() ?: 30)
            .putInt("response", responseField.text.toString().toIntOrNull() ?: 8).apply()
    }

    private fun settingsTransferAllowed(): Boolean {
        if (armed || waitingStart || outputRunning || pausedForTracking) {
            toast("请先停止本轮，再导入或导出设置喵"); return false
        }
        return true
    }

    private fun collectSettingsSnapshot(): Map<String, Any> {
        val values = SettingsCode.defaults().toMutableMap()
        if (settingsNumbers.keys != SettingsCode.numbers.keys) throw SettingsCode.Invalid("设置字段不完整，请更新应用后重试")
        settingsNumbers.forEach { (key, field) ->
            values[key] = field.text.toString().toIntOrNull()
                ?: throw SettingsCode.Invalid("${SettingsCode.numbers.getValue(key).title}请填写整数")
        }
        listOf(motionPreset, penaltyPreset, curtseyPreset, cuePreset, trainingPreset).forEach { it.writeSnapshot(values) }
        values["server"] = server.text.toString().trim()
        values["curtsey_hold_ms"] = curtseyHoldOptions[curtseyHoldField.selectedItemPosition]
        values["game"] = gameToggle.isChecked; values["training"] = trainingToggle.isChecked
        values["face_mosaic"] = headCoverToggle.isChecked
        values["hide_skeleton"] = hideSkeletonToggle.isChecked
        values["mirror_mode"] = mirrorToggle.isChecked; values["console_visible"] = consoleVisible
        values["mode_virtual"] = virtualToggle.isChecked; values["mode_device"] = outputToggle.isChecked
        return SettingsCode.validate(values)
    }

    private fun exportSettingsCode() {
        if (!settingsTransferAllowed()) return
        try {
            val code = SettingsCode.encode(collectSettingsSnapshot())
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("小公主养成 · 全部设置代码", code))
            saveSettings()
            toast("全部设置代码复制好啦喵，可以保存或发到另一部手机")
        } catch (error: IllegalArgumentException) { toast("无法导出：${error.message}") }
    }

    private fun applySettingsCode(code: String) {
        if (!settingsTransferAllowed()) return
        // Decode and validate everything before touching controls or persisted values.
        val values = SettingsCode.decode(code)
        settingsNumbers.forEach { (key, field) -> field.setText(values.getValue(key).toString()) }
        listOf(motionPreset, penaltyPreset, curtseyPreset, cuePreset, trainingPreset).forEach { it.applySnapshot(values) }
        server.setText(values.getValue("server") as String)
        curtseyHoldField.setSelection(curtseyHoldOptions.indexOf(values.getValue("curtsey_hold_ms") as Long))
        gameToggle.isChecked = values.getValue("game") as Boolean
        trainingToggle.isChecked = values.getValue("training") as Boolean
        hideSkeletonToggle.isChecked = values.getValue("hide_skeleton") as Boolean
        mirrorToggle.isChecked = values.getValue("mirror_mode") as Boolean
        headCoverToggle.isChecked = values.getValue("face_mosaic") as Boolean
        outputToggle.isChecked = false
        virtualToggle.isChecked = values.getValue("mode_virtual") as Boolean
        outputToggle.isChecked = values.getValue("mode_device") as Boolean
        consoleVisible = values.getValue("console_visible") as Boolean
        consoleToggle.text = if (consoleVisible) "隐藏控制台" else "显示控制台"
        prefs.edit().putBoolean("console_visible", consoleVisible).apply()
        saveSettings()
        updateModeHint()
        renderGameHud(SystemClock.elapsedRealtime(), true)
        toast("设置已全部导入喵 · ${if (outputToggle.isChecked) "设备模式已选择" else if (virtualToggle.isChecked) "虚拟模式" else "演练模式"} · 本轮尚未启动")
    }

    private fun importSettingsCode() {
        if (!settingsTransferAllowed()) return
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val code = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty() else ""
        if (code.trimStart().startsWith(SettingsCode.PREFIX)) {
            try { applySettingsCode(code); return }
            catch (error: IllegalArgumentException) { toast("导入失败：${error.message}") }
        }
        val input = edit("粘贴以 CVSET1. 开头的完整设置代码", "", false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4; maxLines = 8
            filters = arrayOf(android.text.InputFilter.LengthFilter(SettingsCode.MAX_CODE_SIZE + 512))
            if (code.trimStart().startsWith(SettingsCode.PREFIX)) setText(code.take(SettingsCode.MAX_CODE_SIZE + 512))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); addView(input)
        }
        val dialog = android.app.AlertDialog.Builder(this).setCustomTitle(dialogHelpTitle("导入全部设置代码", "settings_code"))
            .setView(box).setPositiveButton("导入", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { applySettingsCode(input.text.toString()); dialog.dismiss() }
                catch (error: IllegalArgumentException) { input.error = error.message }
            }
        }
        dialog.show()
    }

    private fun log(text: String, important: Boolean = false) {
        sessionLog.add(text, System.currentTimeMillis(), important)
        if (::logText.isInitialized) logText.text = events.take(12).joinToString("\n")
        renderGameHud(SystemClock.elapsedRealtime(), true)
    }
    private fun showSessionLog() {
        val text = events.reversed().joinToString("\n").ifEmpty { "本轮尚无日志" }
        val view = ScrollView(this).apply {
            addView(label(text, 12, BlushTheme.ink).apply { setPadding(dp(16), dp(12), dp(16), dp(12)); setTextIsSelectable(true) })
        }
        android.app.AlertDialog.Builder(this).setCustomTitle(dialogHelpTitle("本轮精简日志 · 按时间顺序", "logs")).setView(view)
            .setPositiveButton("关闭", null).setNeutralButton("复制全部") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("本轮调试日志", text))
                toast("本轮日志复制好啦喵")
            }.show()
    }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun updateModeHint() {
        if (!::modeHint.isInitialized || !::outputToggle.isInitialized) return
        modeHint.text = when {
            outputToggle.isChecked -> "当前选择：设备联动 · 校准后按设置向设备提交指令"
            virtualToggle.isChecked -> "当前选择：虚拟模拟 · 即使已配对，也不会向设备输出喵"
            else -> "当前选择：演练 · 不产生设备或虚拟输出"
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int = 22, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(5), 0, dp(7))
        setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun helpTitle(title: String, key: String, size: Int = 12, color: Int = muted, bold: Boolean = false): View =
        helpControl(label(title, size, color, bold), key, title)

    private fun helpControl(control: View, key: String, title: String): View {
        if (UiHelp.text(key) == null) return control
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(control, LinearLayout.LayoutParams(0, -2, 1f))
            addView(helpButton(title, key), LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }

    private fun helpButton(title: String, key: String): View = FrameLayout(this).apply {
        contentDescription = "$title，查看说明"
        tooltipText = "查看说明"
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        background = RippleDrawable(ColorStateList.valueOf(0x228E526C), null, rounded(Color.WHITE, 24))
        addView(TextView(this@MainActivity).apply {
            alpha = .55f
            text = "?"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(BlushTheme.pink, 12, BlushTheme.line)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        setOnClickListener { showHelp(title, requireNotNull(UiHelp.text(key))) }
    }

    private fun dialogHelpTitle(title: String, key: String): View = helpTitle(title, key, 18, BlushTheme.ink, true).apply {
        setPadding(dp(20), dp(8), dp(8), 0)
    }

    private fun showHelp(title: String, message: String) {
        val content = ScrollView(this).apply {
            addView(label(message, 14, BlushTheme.ink).apply {
                setPadding(dp(22), dp(12), dp(22), dp(16)); setTextIsSelectable(true)
            })
        }
        android.app.AlertDialog.Builder(this).setTitle(title).setView(content)
            .setPositiveButton("知道啦喵", null).show()
    }
    private fun button(text: String, danger: Boolean = false, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; minHeight = dp(56); textSize = 14f
        setTypeface(typeface, Typeface.BOLD); setPadding(dp(16), dp(10), dp(16), dp(10))
        backgroundTintList = null; stateListAnimator = null; elevation = 0f
        val fill = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(BlushTheme.disabled, 18))
            addState(intArrayOf(), rounded(if (danger) BlushTheme.dangerFill else if (primary) BlushTheme.primary else BlushTheme.pink,
                18, if (primary) null else BlushTheme.line))
        }
        background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(0x228E526C), fill,
            rounded(BlushTheme.surface, 18)), dp(3), dp(4), dp(3), dp(4))
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(BlushTheme.disabledInk, if (danger) BlushTheme.danger else if (primary) Color.WHITE else accent)))
        setOnClickListener { action() }
    }
    private fun edit(hint: String, value: String, numeric: Boolean) = EditText(this).apply {
        this.hint = hint; setText(value); textSize = 14f; setTextColor(BlushTheme.ink); setHintTextColor(muted)
        isSingleLine = true
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        minHeight = dp(56); setPadding(dp(14), dp(10), dp(14), dp(10)); backgroundTintList = null
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(BlushTheme.disabled, 16, BlushTheme.line))
            addState(intArrayOf(android.R.attr.state_focused), rounded(BlushTheme.surface, 16, accent))
            addState(intArrayOf(), rounded(BlushTheme.input, 16, BlushTheme.line))
        }
    }
    private fun spinner(items: List<String>, selection: Int) = Spinner(this).apply {
        adapter = choiceAdapter(items)
        setSelection(selection.coerceIn(items.indices))
        minimumHeight = dp(56); setPadding(dp(4), dp(2), dp(34), dp(2))
        backgroundTintList = null
        background = LayerDrawable(arrayOf(rounded(BlushTheme.input, 16, BlushTheme.line),
            ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_chevron)!!)).apply {
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerWidth(1, dp(24)); setLayerHeight(1, dp(24)); setLayerInsetRight(1, dp(10))
        }
        setPopupBackgroundDrawable(rounded(BlushTheme.surface, 18, BlushTheme.line))
    }
    private fun choiceAdapter(items: List<String>) = object : ArrayAdapter<String>(this,
        android.R.layout.simple_spinner_item, items) {
        init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            styleChoice(super.getView(position, convertView, parent), false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            styleChoice(super.getDropDownView(position, convertView, parent), true)
        private fun styleChoice(view: View, popup: Boolean): View = (view as TextView).apply {
            setTextColor(BlushTheme.ink); textSize = 14f; minHeight = dp(50)
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            if (popup) setBackgroundColor(BlushTheme.surface)
        }
    }
    private fun section(text: String, subtitle: String = "先牵好连接，教官等你喵～", collapsed: Boolean = false) {
        gap(16)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(22))
            background = LacePaperDrawable(resources.displayMetrics.density)
        }
        root.addView(card, LinearLayout.LayoutParams(-1, -2))
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60) }
        val motif = when (text.take(2)) { "01", "05" -> CourtMotif.BOW; "03", "04" -> CourtMotif.CROWN; else -> CourtMotif.GEM }
        val badge = CourtMotifView(this, motif)
        header.addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(8), 0) }
        titles.addView(label(text, 17, BlushTheme.ink, true))
        titles.addView(label(subtitle, 11, muted).apply { setPadding(0, 0, 0, dp(4)) })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(helpButton(text.drop(2).trim(), "section_${text.take(2)}"), LinearLayout.LayoutParams(dp(48), dp(48)))
        val arrow = ImageView(this).apply { setImageResource(R.drawable.ic_chevron); rotation = if (collapsed) 0f else 180f }
        header.addView(arrow, LinearLayout.LayoutParams(dp(24), dp(24)))
        card.addView(header)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = if (collapsed) View.GONE else View.VISIBLE
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(body); sectionBody = body
        fun describe() { header.contentDescription = text.drop(2).trim() + if (body.visibility == View.VISIBLE) "，点击收起" else "，点击展开" }
        header.isFocusable = true; header.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            arrow.rotation = if (body.visibility == View.VISIBLE) 180f else 0f; describe()
        }
        describe()
    }
    private fun addWelcomeCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(22), dp(20), dp(22))
            background = LacePaperDrawable(resources.displayMetrics.density, welcome = true)
        }
        val ornaments = LinearLayout(this).apply { gravity = Gravity.CENTER }
        for (motif in listOf(CourtMotif.GEM, CourtMotif.CROWN, CourtMotif.GEM))
            ornaments.addView(CourtMotifView(this, motif), LinearLayout.LayoutParams(dp(28), dp(28)))
        card.addView(ornaments)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label("PRINCESS · LESSON", 10, accent, true).apply { letterSpacing = .13f })
        text.addView(helpControl(label("小公主养成", 28, BlushTheme.ink, true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD)
        }, "welcome", "小公主养成"))
        text.addView(label("小公主也要守规矩，站好再撒娇喵～", 12, muted))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        root.addView(card, LinearLayout.LayoutParams(-1, -2))
    }
    private fun gap(height: Int) { root.addView(View(this), LinearLayout.LayoutParams(-1, dp(height))) }
}
