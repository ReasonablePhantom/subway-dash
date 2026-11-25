package com.brucelyu.subwaydash

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
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
import androidx.camera.core.UseCaseGroup
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
    private var calibrationSamples = 0
    private val calibrationTargetCount = 4

    // Calibration baseline
    private var sumYaw = 0f
    private var sumOffsetX = 0f
    private var sumPitch = 0f
    private var sumMouthRatio = 0f
    private var baseYaw = 0f
    private var baseOffsetX = 0f
    private var basePitch = 0f
    private var baseMouthRatio = 0.18f

    // Smoothed state, hysteresis & anti-overshoot center brake
    private var smoothedSteer = 0f
    @Volatile
    var mappedLane: Int = 0
        private set
    private var centerBrakeUntilMs = 0L
    private var prevJumpActive = false
    private var prevSlideActive = false
    private var lastJumpTimeMs = 0L
    private var lastSlideTimeMs = 0L

    fun start(lifecycleOwner: LifecycleOwner) {
        if (isStarted) return
        isStarted = true
        resetCalibrationState()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!isStarted) return@addListener
            previewView.post {
                if (!isStarted) return@post
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
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
                    val viewPort = previewView.viewPort
                    if (viewPort != null) {
                        val useCaseGroup = UseCaseGroup.Builder()
                            .addUseCase(preview)
                            .addUseCase(analysis)
                            .setViewPort(viewPort)
                            .build()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            useCaseGroup
                        )
                    } else {
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            analysis
                        )
                    }
                    onStatusText("👀 找人脸")
                } catch (e: Exception) {
                    isStarted = false
                    onError(e.localizedMessage ?: "前置摄像头启动失败")
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun resetCalibrationState() {
        calibrationSamples = 0
        sumYaw = 0f
        sumOffsetX = 0f
        sumPitch = 0f
        sumMouthRatio = 0f
        smoothedSteer = 0f
        mappedLane = 0
        centerBrakeUntilMs = 0L
        prevJumpActive = false
        prevSlideActive = false
    }

    fun stop() {
        isStarted = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        overlayView.clear()
    }

    fun recalibrate() {
        resetCalibrationState()
        onLaneChanged(0)
        onStatusText("🎯 校准中")
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
        val rawW = imageProxy.width.toFloat()
        val rawH = imageProxy.height.toFloat()
        val uprightCrop = rotateCropRect(imageProxy.cropRect, rawW, rawH, rot)
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
                handleDetectedFace(face, uprightCrop)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun rotateCropRect(crop: Rect, rawW: Float, rawH: Float, rot: Int): RectF {
        return when (rot) {
            90 -> RectF(rawH - crop.bottom, crop.left.toFloat(), rawH - crop.top, crop.right.toFloat())
            180 -> RectF(rawW - crop.right, rawH - crop.bottom, rawW - crop.left, rawH - crop.top)
            270 -> RectF(crop.top.toFloat(), rawW - crop.right, crop.bottom.toFloat(), rawW - crop.left)
            else -> RectF(crop.left.toFloat(), crop.top.toFloat(), crop.right.toFloat(), crop.bottom.toFloat())
        }
    }

    private fun handleDetectedFace(face: Face, visibleRect: RectF) {
        val visibleW = visibleRect.width().coerceAtLeast(1f)
        val visibleH = visibleRect.height().coerceAtLeast(1f)
        // ML Kit positive EulerY = face turned toward right side of unmirrored image (= user's physical left).
        // Negating EulerY aligns positive with user's physical right (screen right in mirrored preview).
        val yawDeg = -face.headEulerAngleY
        val centerNormX = (face.boundingBox.centerX().toFloat() - visibleRect.left) / visibleW
        val mirroredOffsetX = 0.5f - centerNormX
        val pitchDeg = face.headEulerAngleX
        val mouthRatio = computeMouthOpenRatio(face)

        if (calibrationSamples < calibrationTargetCount) {
            sumYaw += yawDeg
            sumOffsetX += mirroredOffsetX
            sumPitch += pitchDeg
            sumMouthRatio += (mouthRatio ?: 0.18f)
            calibrationSamples++
            baseYaw = sumYaw / calibrationSamples
            baseOffsetX = sumOffsetX / calibrationSamples
            basePitch = sumPitch / calibrationSamples
            baseMouthRatio = sumMouthRatio / calibrationSamples
            if (calibrationSamples < calibrationTargetCount) {
                onStatusText("🎯 校准中")
                return
            }
        }

        // Calm steering range (~22 deg head turn or clear lean) to avoid hair-trigger overshooting
        val rawSteer = ((yawDeg - baseYaw) / 22.0f) * 0.78f +
            ((mirroredOffsetX - baseOffsetX) / 0.24f) * 0.22f
        smoothedSteer = smoothedSteer * 0.62f + rawSteer * 0.38f

        val nowMs = SystemClock.elapsedRealtime()

        // Step-by-step 3-lane mapping with hysteresis + 280ms Center Brake (never flies -1 -> +1 directly!)
        val newLane = when (mappedLane) {
            0 -> {
                if (nowMs < centerBrakeUntilMs) {
                    0
                } else when {
                    smoothedSteer < -0.45f -> -1
                    smoothedSteer > 0.45f -> 1
                    else -> 0
                }
            }
            -1 -> if (smoothedSteer > -0.22f) 0 else -1
            1 -> if (smoothedSteer < 0.22f) 0 else 1
            else -> 0
        }

        if (newLane != mappedLane) {
            if (newLane == 0 && (mappedLane == -1 || mappedLane == 1)) {
                centerBrakeUntilMs = nowMs + 280L
            }
            mappedLane = newLane
            onLaneChanged(newLane)
        }

        // Jump & Slide via pitch + mouth opening landmark ratio
        val pitchDelta = pitchDeg - basePitch
        val mouthDelta = if (mouthRatio != null) mouthRatio - baseMouthRatio else 0f
        val wantJump = pitchDelta > 8.0f || mouthDelta > 0.16f
        val wantSlide = !wantJump && pitchDelta < -8.0f

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

        // Mirrored normalized overlay coordinates relative to visible crop region
        val b = face.boundingBox
        val mirroredBox = RectF(
            1f - ((b.right.toFloat() - visibleRect.left) / visibleW),
            (b.top.toFloat() - visibleRect.top) / visibleH,
            1f - ((b.left.toFloat() - visibleRect.left) / visibleW),
            (b.bottom.toFloat() - visibleRect.top) / visibleH
        )
        val mapLandmark = { type: Int ->
            face.getLandmark(type)?.position?.let { pt ->
                PointF(
                    1f - ((pt.x - visibleRect.left) / visibleW),
                    (pt.y - visibleRect.top) / visibleH
                )
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
