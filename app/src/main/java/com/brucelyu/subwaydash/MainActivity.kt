package com.brucelyu.subwaydash

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var modeToggleBtn: TextView
    private lateinit var pipCard: LinearLayout
    private lateinit var previewView: PreviewView
    private lateinit var faceOverlayView: FaceOverlayView
    private lateinit var faceStatusLabel: TextView
    private var faceControlManager: FaceControlManager? = null
    private var isFaceModeActive = false

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableFaceMode()
        } else {
            Toast.makeText(this, "未授予相机权限，继续使用触屏模式", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        gameView = GameView(this)
        root.addView(
            gameView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Top-center Face/Touch Mode Toggle Pill
        modeToggleBtn = TextView(this).apply {
            text = "📷 开启人脸操控"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(226, 232, 240))
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = createPillDrawable(
                fillColor = Color.argb(205, 15, 23, 42),
                strokeColor = Color.argb(140, 148, 163, 184),
                cornerDp = 20
            )
            setOnClickListener { toggleFaceMode() }
        }
        val toggleParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(16)
        }
        root.addView(modeToggleBtn, toggleParams)

        // Top-right Camera PiP Container (below coins HUD)
        previewView = PreviewView(this).apply {
            // COMPATIBLE (TextureView) avoids Z-fighting with GameView's SurfaceView!
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        faceOverlayView = FaceOverlayView(this)

        val cameraBox = FrameLayout(this).apply {
            val radiusPx = dp(10).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            clipToOutline = true
            addView(
                previewView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                faceOverlayView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            setOnClickListener {
                faceControlManager?.recalibrate()
                Toast.makeText(this@MainActivity, "🎯 人脸中心已重新校准", Toast.LENGTH_SHORT).show()
            }
        }

        faceStatusLabel = TextView(this).apply {
            text = "👀 找人脸"
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(226, 232, 240))
        }
        val calibrateBtn = TextView(this).apply {
            text = "🎯校准"
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(103, 232, 249))
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = createPillDrawable(
                fillColor = Color.argb(60, 34, 211, 238),
                strokeColor = Color.argb(140, 34, 211, 238),
                cornerDp = 6
            )
            setOnClickListener {
                faceControlManager?.recalibrate()
            }
        }
        val pipFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(4))
            addView(
                faceStatusLabel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(calibrateBtn)
        }

        pipCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = createPillDrawable(
                fillColor = Color.argb(225, 6, 10, 20),
                strokeColor = Color.argb(180, 34, 211, 238),
                cornerDp = 12
            )
            addView(cameraBox, LinearLayout.LayoutParams(dp(90), dp(120)))
            addView(pipFooter, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val pipParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(56)
            marginEnd = dp(12)
        }
        root.addView(pipCard, pipParams)

        setContentView(root)
        hideSystemUI()

        faceControlManager = FaceControlManager(
            context = this,
            previewView = previewView,
            overlayView = faceOverlayView,
            onLaneChanged = { lane ->
                if (isFaceModeActive) {
                    gameView.setTargetLaneFromFace(lane)
                }
            },
            onJump = {
                if (isFaceModeActive) {
                    gameView.triggerJump()
                }
            },
            onSlide = {
                if (isFaceModeActive) {
                    gameView.triggerSlide()
                }
            },
            onStatusText = { status ->
                mainHandler.post { faceStatusLabel.text = status }
            },
            onError = { errMsg ->
                mainHandler.post {
                    disableFaceMode()
                    Toast.makeText(this, "人脸识别启动失败: $errMsg", Toast.LENGTH_SHORT).show()
                }
            }
        )

        gameView.onGameStateChangedListener = { newState ->
            if (isFaceModeActive) {
                pipCard.visibility = if (newState == GameView.GameState.GAMEOVER) View.GONE else View.VISIBLE
                if (newState == GameView.GameState.PLAYING) {
                    gameView.setTargetLaneFromFace(faceControlManager?.mappedLane ?: 0)
                }
            }
        }

        // Listen for game over to prompt record upload to brucelyu.cc
        gameView.onGameOverListener = { score, coins, speedMultiplier ->
            showUploadDialog(score, coins, speedMultiplier)
        }
    }

    private fun createPillDrawable(fillColor: Int, strokeColor: Int, cornerDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(fillColor)
            setStroke(dp(1) + 1, strokeColor)
        }
    }

    private fun toggleFaceMode() {
        if (isFaceModeActive) {
            disableFaceMode()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                enableFaceMode()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun enableFaceMode() {
        isFaceModeActive = true
        gameView.isFaceControlEnabled = true
        pipCard.visibility = View.VISIBLE
        modeToggleBtn.text = "📷 人脸操控中 · 切触屏"
        modeToggleBtn.setTextColor(Color.rgb(103, 232, 249))
        modeToggleBtn.background = createPillDrawable(
            fillColor = Color.argb(220, 8, 51, 68),
            strokeColor = Color.rgb(34, 211, 238),
            cornerDp = 20
        )
        faceControlManager?.start(this)
    }

    private fun disableFaceMode() {
        isFaceModeActive = false
        gameView.isFaceControlEnabled = false
        pipCard.visibility = View.GONE
        modeToggleBtn.text = "📷 开启人脸操控"
        modeToggleBtn.setTextColor(Color.rgb(226, 232, 240))
        modeToggleBtn.background = createPillDrawable(
            fillColor = Color.argb(205, 15, 23, 42),
            strokeColor = Color.argb(140, 148, 163, 184),
            cornerDp = 20
        )
        faceControlManager?.stop()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        gameView.resume()
        if (isFaceModeActive) {
            faceControlManager?.start(this)
        }
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
        if (isFaceModeActive) {
            faceControlManager?.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceControlManager?.release()
        executor.shutdown()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun showUploadDialog(score: Int, coins: Int, speedMultiplier: Float) {
        val prefs = getSharedPreferences("subway_dash_prefs", Context.MODE_PRIVATE)
        val lastPlayerName = prefs.getString("saved_player_name", "Bruce") ?: "Bruce"

        val now = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateStr = dateFormat.format(now)
        val timeStr = timeFormat.format(now)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 30, 60, 20)
        }

        val infoView = TextView(this).apply {
            text = "本次奔跑: ${score}m  |  极速: ${String.format(Locale.US, "%.1f", speedMultiplier)}x  |  金币: 🪙$coins\n🕒 时间: $dateStr $timeStr"
            textSize = 14f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(infoView)

        val nameInput = EditText(this).apply {
            hint = "输入你的名字 (上榜显示)"
            setText(lastPlayerName)
            selectAll()
            isSingleLine = true
        }
        layout.addView(nameInput)

        AlertDialog.Builder(this)
            .setTitle("🏆 上传战绩到 brucelyu.cc")
            .setView(layout)
            .setPositiveButton("🚀 确认上传") { _, _ ->
                val playerName = nameInput.text.toString().trim().ifEmpty { "神秘跑酷手" }
                prefs.edit().putString("saved_player_name", playerName).apply()
                uploadRecord(playerName, score, coins, speedMultiplier, dateStr, timeStr)
            }
            .setNeutralButton("📊 查看排行榜") { _, _ ->
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.brucelyu.cc/subway-dash"))
                    startActivity(browserIntent)
                } catch (_: Exception) {}
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun uploadRecord(
        playerName: String,
        score: Int,
        coins: Int,
        speedMultiplier: Float,
        gameDate: String,
        gameTime: String
    ) {
        Toast.makeText(this, "正在上传战绩至 brucelyu.cc...", Toast.LENGTH_SHORT).show()

        executor.execute {
            var success = false
            var errorMsg = ""

            try {
                val url = URL("https://www.brucelyu.cc/api/subway-dash/records")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; utf-8")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                val payload = JSONObject().apply {
                    put("playerName", playerName)
                    put("score", score)
                    put("coins", coins)
                    put("speedMultiplier", speedMultiplier.toDouble())
                    put("gameDate", gameDate)
                    put("gameTime", gameTime)
                }

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                success = responseCode in 200..299
                conn.disconnect()
            } catch (e: Exception) {
                errorMsg = e.localizedMessage ?: "网络错误"
            }

            mainHandler.post {
                if (success) {
                    Toast.makeText(this, "🎉 战绩上传成功！已登入全球榜单！", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "⚠️ 上传未完成: $errorMsg (可直接在网页端查看)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hideSystemUI() {
        try {
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {
            try {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            } catch (_: Exception) {}
        }
    }
}
