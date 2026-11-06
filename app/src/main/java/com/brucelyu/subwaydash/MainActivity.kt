package com.brucelyu.subwaydash

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gameView = GameView(this)
        setContentView(gameView)

        hideSystemUI()

        // Listen for game over to prompt record upload to brucelyu.cc
        gameView.onGameOverListener = { score, coins, speedMultiplier ->
            showUploadDialog(score, coins, speedMultiplier)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        gameView.resume()
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
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
