package com.brucelyu.subwaydash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Size
import android.view.View
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

/**
 * Overlay view rendered above PreviewView inside the PiP box,
 * drawing mirrored face bounds, eye/nose landmarks, and active gesture indicator.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile
    private var mirroredBox: RectF? = null
    @Volatile
    private var mirroredNose: PointF? = null
    @Volatile
    private var mirroredLeftEye: PointF? = null
    @Volatile
    private var mirroredRightEye: PointF? = null
    @Volatile
    private var currentLane: Int = 0
    @Volatile
    private var activeAction: String = ""

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(180, 34, 211, 238)
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(34, 211, 238)
    }

    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun updateOverlay(
        box: RectF?,
        nose: PointF?,
        leftEye: PointF?,
        rightEye: PointF?,
        lane: Int,
        action: String
    ) {
        mirroredBox = box
        mirroredNose = nose
        mirroredLeftEye = leftEye
        mirroredRightEye = rightEye
        currentLane = lane
        activeAction = action
        postInvalidate()
    }

    fun clear() {
        mirroredBox = null
        mirroredNose = null
        mirroredLeftEye = null
        mirroredRightEye = null
        activeAction = ""
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Top 3-lane bar inside PiP
        val barH = 8f
        val segW = (w - 16f) / 3f
        for (i in 0..2) {
            val laneIdx = i - 1
            zonePaint.color = if (laneIdx == currentLane) {
                Color.rgb(34, 211, 238)
            } else {
                Color.argb(65, 148, 163, 184)
            }
            val left = 6f + i * (segW + 2f)
            canvas.drawRoundRect(left, 6f, left + segW, 6f + barH, 4f, 4f, zonePaint)
        }

        val highlightColor = when (activeAction) {
            "JUMP" -> Color.rgb(255, 212, 0)
            "SLIDE" -> Color.rgb(251, 146, 60)
            else -> Color.rgb(34, 211, 238)
        }

        mirroredBox?.let { b ->
            boxPaint.color = Color.argb(180, Color.red(highlightColor), Color.green(highlightColor), Color.blue(highlightColor))
            canvas.drawRoundRect(b.left * w, b.top * h, b.right * w, b.bottom * h, 14f, 14f, boxPaint)
        }

        dotPaint.color = highlightColor
        mirroredLeftEye?.let { canvas.drawCircle(it.x * w, it.y * h, 5f, dotPaint) }
        mirroredRightEye?.let { canvas.drawCircle(it.x * w, it.y * h, 5f, dotPaint) }
        mirroredNose?.let { canvas.drawCircle(it.x * w, it.y * h, 7f, dotPaint) }
    }
}

/**
 * Plan A: CameraX + Google ML Kit Face Detection controller.
 * Maps horizontal head yaw + lean into 3 absolute tracks (with hysteresis to prevent overshooting),
 * and head pitch / mouth-open into Jump & Slide triggers.
 */
class FaceControlManager(
    private val context: Context,
    private val previewView: PreviewView,
    private val overlayView: FaceOverlayView,
    private val onLaneChanged: (lane: Int) -> Unit,
    private val onJump: () -> Unit,
    private val onSlide: () -> Unit,
    private val onStatusText: (text: String) -> Unit,
    private val onError: (msg: String) -> Unit
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.18f)
            .build()
        FaceDetection.getClient(options)
    }

    @Volatile
    private var isStarted = false
    @Volatile
    private var needsCalibration = true

    // Calibration baseline
    private var baseYaw = 0f
    private var baseOffsetX = 0f
    private var basePitch = 0f
    private var baseMouthRatio = 0.18f

    // Smoothed state & hysteresis
    private var smoothedSteer = 0f
    @Volatile
    var mappedLane: Int = 0
        private set
    private var prevJumpActive = false
    private var prevSlideActive = false
    private var lastJumpTimeMs = 0L
    private var lastSlideTimeMs = 0L

    fun start(lifecycleOwner: LifecycleOwner) {
        if (isStarted) return
        isStarted = true
        needsCalibration = true
        smoothedSteer = 0f
        mappedLane = 0
        prevJumpActive = false
        prevSlideActive = false

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!isStarted) return@addListener
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(480, 640),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, ::analyzeImage) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis
                )
                onStatusText("👀 找人脸")
            } catch (e: Exception) {
                isStarted = false
                onError(e.localizedMessage ?: "前置摄像头启动失败")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        isStarted = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        overlayView.clear()
    }

    fun recalibrate() {
        needsCalibration = true
        smoothedSteer = 0f
        mappedLane = 0
        prevJumpActive = false
        prevSlideActive = false
        onLaneChanged(0)
        onStatusText("🎯 已校准")
    }

    fun release() {
        stop()
        try {
            faceDetector.close()
        } catch (_: Exception) {}
        cameraExecutor.shutdown()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeImage(imageProxy: androidx.camera.core.ImageProxy) {
        if (!isStarted) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rot = imageProxy.imageInfo.rotationDegrees
        val uprightW = (if (rot == 90 || rot == 270) imageProxy.height else imageProxy.width).toFloat()
        val uprightH = (if (rot == 90 || rot == 270) imageProxy.width else imageProxy.height).toFloat()
        val inputImage = InputImage.fromMediaImage(mediaImage, rot)

        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (!isStarted) return@addOnSuccessListener
                if (faces.isEmpty()) {
                    overlayView.clear()
                    onStatusText("👀 找人脸")
                    return@addOnSuccessListener
                }
                // Pick largest detected face
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    ?: return@addOnSuccessListener
                handleDetectedFace(face, uprightW, uprightH)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun handleDetectedFace(face: Face, uprightW: Float, uprightH: Float) {
        // ML Kit positive EulerY = face turned toward right side of unmirrored image (= user's physical left).
        // Negating EulerY aligns positive with user's physical right (screen right in mirrored preview).
        val yawDeg = -face.headEulerAngleY
        val mirroredOffsetX = 0.5f - (face.boundingBox.centerX().toFloat() / uprightW)
        val pitchDeg = face.headEulerAngleX
        val mouthRatio = computeMouthOpenRatio(face)

        if (needsCalibration) {
            baseYaw = yawDeg
            baseOffsetX = mirroredOffsetX
            basePitch = pitchDeg
            baseMouthRatio = mouthRatio ?: 0.18f
            needsCalibration = false
        }

        // Combined steering signal: light head turn (~13 deg) + subtle lean
        val rawSteer = ((yawDeg - baseYaw) / 13.0f) * 0.72f +
            ((mirroredOffsetX - baseOffsetX) / 0.14f) * 0.28f
        smoothedSteer = smoothedSteer * 0.52f + rawSteer * 0.48f

        // 3-lane mapping with hysteresis (prevents jitter or skipping center lane)
        val newLane = when (mappedLane) {
            0 -> when {
                smoothedSteer < -0.46f -> -1
                smoothedSteer > 0.46f -> 1
                else -> 0
            }
            -1 -> when {
                smoothedSteer > 0.62f -> 1
                smoothedSteer > -0.24f -> 0
                else -> -1
            }
            1 -> when {
                smoothedSteer < -0.62f -> -1
                smoothedSteer < 0.24f -> 0
                else -> 1
            }
            else -> 0
        }

        if (newLane != mappedLane) {
            mappedLane = newLane
            onLaneChanged(newLane)
        }

        // Jump & Slide via pitch + mouth opening landmark ratio
        val pitchDelta = pitchDeg - basePitch
        val mouthDelta = if (mouthRatio != null) mouthRatio - baseMouthRatio else 0f
        val wantJump = pitchDelta > 7.5f || mouthDelta > 0.15f
        val wantSlide = !wantJump && pitchDelta < -7.5f

        val nowMs = SystemClock.elapsedRealtime()
        if (wantJump && !prevJumpActive && nowMs - lastJumpTimeMs > 380L) {
            lastJumpTimeMs = nowMs
            onJump()
        } else if (wantSlide && !prevSlideActive && nowMs - lastSlideTimeMs > 380L) {
            lastSlideTimeMs = nowMs
            onSlide()
        }
        prevJumpActive = wantJump
        prevSlideActive = wantSlide

        val actionStr = when {
            wantJump -> "JUMP"
            wantSlide -> "SLIDE"
            else -> ""
        }
        val statusText = when {
            wantJump -> "😮 跳跃!"
            wantSlide -> "⬇ 滑铲!"
            mappedLane == -1 -> "◀ 左道"
            mappedLane == 1 -> "▶ 右道"
            else -> "😐 中道"
        }
        onStatusText(statusText)

        // Mirrored normalized overlay coordinates
        val b = face.boundingBox
        val mirroredBox = RectF(
            1f - (b.right.toFloat() / uprightW),
            b.top.toFloat() / uprightH,
            1f - (b.left.toFloat() / uprightW),
            b.bottom.toFloat() / uprightH
        )
        val mapLandmark = { type: Int ->
            face.getLandmark(type)?.position?.let { pt ->
                PointF(1f - (pt.x / uprightW), pt.y / uprightH)
            }
        }
        overlayView.updateOverlay(
            box = mirroredBox,
            nose = mapLandmark(FaceLandmark.NOSE_BASE),
            leftEye = mapLandmark(FaceLandmark.LEFT_EYE),
            rightEye = mapLandmark(FaceLandmark.RIGHT_EYE),
            lane = mappedLane,
            action = actionStr
        )
    }

    private fun computeMouthOpenRatio(face: Face): Float? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return null
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null

        val eyeDist = hypot(
            (rightEye.x - leftEye.x).toDouble(),
            (rightEye.y - leftEye.y).toDouble()
        ).toFloat().coerceAtLeast(16f)
        val mouthCornersY = (mouthLeft.y + mouthRight.y) * 0.5f
        return (mouthBottom.y - mouthCornersY) / eyeDist
    }
}
