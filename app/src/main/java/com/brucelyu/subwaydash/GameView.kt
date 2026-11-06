package com.brucelyu.subwaydash

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.*

class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    enum class GameState { START, PLAYING, GAMEOVER }

    private var gameThread: Thread? = null
    @Volatile
    private var isRunning = false

    private val sound = SoundManager()
    private val prefs = context.getSharedPreferences("subway_dash_prefs", Context.MODE_PRIVATE)

    // Game state
    private var state = GameState.START
    private var score = 0
    private var coinsCollected = 0
    private var bestScore = prefs.getInt("best_score", 0)
    private var distance = 0f

    // Callback when game ends: (score, coins, speedMultiplier)
    var onGameOverListener: ((score: Int, coins: Int, speedMultiplier: Float) -> Unit)? = null

    // Progressive Acceleration System
    // Starts comfortably at 5.2f, scales progressively up to 16.5f!
    private val baseSpeed = 5.2f
    private val maxSpeed = 16.5f
    private var currentSpeed = baseSpeed
    private var speedTier = 1 // 1: 1.0x, 2: 1.5x, 3: 2.0x, 4: 2.5x+
    private var speedUpBannerText = ""
    private var speedUpBannerTimer = 0

    // Speed Lines Effect for high velocity
    private data class SpeedLine(var angle: Float, var dist: Float, var len: Float, var speed: Float)
    private val speedLines = ArrayList<SpeedLine>()

    // 3D Perspective Projection parameters
    private var screenWidth = 0f
    private var screenHeight = 0f
    private var horizonY = 0f
    private var groundY = 0f
    private var fov = 350f
    private var laneSpacing = 160f
    private var trackOffset = 0f

    // Player
    private var playerTargetLane = 0
    private var playerCurrentX = 0f
    private var playerY = 0f
    private var playerVy = 0f
    private var isSliding = false
    private var slideTimer = 0
    private var animTime = 0f

    // World Entities
    private val obstacles = ArrayList<Obstacle>()
    private val coins = ArrayList<Coin>()
    private val particles = ArrayList<Particle>()
    private var nextSpawnZ = 950f

    // Paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }

    // Touch Screen Controls
    private var touchOriginX = 0f
    private var touchOriginY = 0f
    private var touchDownTime = 0L
    private var isTouching = false
    private var hasSwipedInGesture = false
    private var swipeThreshold = 55f

    // Virtual Touch Button Rectangles (drawn at bottom of screen)
    private val btnLeftRect = RectF()
    private val btnJumpRect = RectF()
    private val btnSlideRect = RectF()
    private val btnRightRect = RectF()
    private var activeBtn = ""

    init {
        holder.addCallback(this)
        isFocusable = true

        for (i in 0 until 24) {
            speedLines.add(
                SpeedLine(
                    (Math.random() * PI * 2).toFloat(),
                    (Math.random() * 400 + 40).toFloat(),
                    (Math.random() * 40 + 20).toFloat(),
                    (Math.random() * 8 + 12).toFloat()
                )
            )
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
        horizonY = screenHeight * 0.36f
        groundY = screenHeight * 0.86f
        fov = screenHeight * 0.44f
        laneSpacing = screenWidth * 0.34f

        val btnSize = (screenWidth * 0.18f).coerceAtMost(160f)
        val btnY = screenHeight - btnSize - 40f
        val gap = (screenWidth - (btnSize * 4)) / 5f

        btnLeftRect.set(gap, btnY, gap + btnSize, btnY + btnSize)
        btnJumpRect.set(gap * 2 + btnSize, btnY, gap * 2 + btnSize * 2, btnY + btnSize)
        btnSlideRect.set(gap * 3 + btnSize * 2, btnY, gap * 3 + btnSize * 3, btnY + btnSize)
        btnRightRect.set(gap * 4 + btnSize * 3, btnY, gap * 4 + btnSize * 4, btnY + btnSize)
        swipeThreshold = (screenWidth * 0.08f).coerceIn(45f, 95f)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pause()
    }

    fun pause() {
        isRunning = false
        try {
            gameThread?.join()
        } catch (_: InterruptedException) {}
        gameThread = null
    }

    fun resume() {
        if (isRunning) return
        isRunning = true
        gameThread = Thread(this).apply { start() }
    }

    private fun startGame() {
        state = GameState.PLAYING
        score = 0
        coinsCollected = 0
        distance = 0f
        currentSpeed = baseSpeed
        speedTier = 1
        speedUpBannerTimer = 0
        obstacles.clear()
        coins.clear()
        particles.clear()

        playerTargetLane = 0
        playerCurrentX = 0f
        playerY = 0f
        playerVy = 0f
        isSliding = false
        slideTimer = 0

        // Pre-populate initial waves so tracks are not empty from start!
        spawnWaveAt(450f)
        spawnWaveAt(850f)
        nextSpawnZ = 1250f
    }

    private fun gameOver() {
        state = GameState.GAMEOVER
        sound.playCrash()
        if (score > bestScore) {
            bestScore = score
            prefs.edit().putInt("best_score", bestScore).apply()
        }
        val multiplier = currentSpeed / baseSpeed
        post {
            onGameOverListener?.invoke(score, coinsCollected, multiplier)
        }
    }

    private fun moveLeft() {
        if (playerTargetLane > -1) playerTargetLane--
    }

    private fun moveRight() {
        if (playerTargetLane < 1) playerTargetLane++
    }

    private fun jump() {
        if (playerY == 0f || isSliding) {
            playerVy = 12.0f
            isSliding = false
            slideTimer = 0
            sound.playJump()
        }
    }

    private fun slide() {
        if (playerY > 0f) {
            playerVy = -24f
        }
        isSliding = true
        slideTimer = 36
        sound.playSlide()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchOriginX = x
                touchOriginY = y
                touchDownTime = System.currentTimeMillis()
                isTouching = true
                hasSwipedInGesture = false

                if (state == GameState.START || state == GameState.GAMEOVER) {
                    startGame()
                    return true
                }

                if (btnLeftRect.contains(x, y)) {
                    activeBtn = "LEFT"
                    moveLeft()
                    hasSwipedInGesture = true
                    return true
                } else if (btnJumpRect.contains(x, y)) {
                    activeBtn = "JUMP"
                    jump()
                    hasSwipedInGesture = true
                    return true
                } else if (btnSlideRect.contains(x, y)) {
                    activeBtn = "SLIDE"
                    slide()
                    hasSwipedInGesture = true
                    return true
                } else if (btnRightRect.contains(x, y)) {
                    activeBtn = "RIGHT"
                    moveRight()
                    hasSwipedInGesture = true
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (state != GameState.PLAYING || !isTouching || hasSwipedInGesture) return true

                val dx = x - touchOriginX
                val dy = y - touchOriginY
                val absDx = abs(dx)
                val absDy = abs(dy)

                // Trigger EXACTLY ONE swipe action per touch stroke, preventing overshooting!
                if (max(absDx, absDy) >= swipeThreshold) {
                    hasSwipedInGesture = true
                    if (absDx > absDy) {
                        if (dx > 0) moveRight() else moveLeft()
                    } else {
                        if (dy > 0) slide() else jump()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                activeBtn = ""

                val duration = System.currentTimeMillis() - touchDownTime
                val dx = x - touchOriginX
                val dy = y - touchOriginY
                if (!hasSwipedInGesture && duration < 250 && hypot(dx.toDouble(), dy.toDouble()) < 25) {
                    if (y < screenHeight * 0.45f) {
                        jump()
                    } else if (x < screenWidth * 0.33f) {
                        moveLeft()
                    } else if (x > screenWidth * 0.66f) {
                        moveRight()
                    } else {
                        slide()
                    }
                }
            }
        }
        return true
    }

    override fun run() {
        var lastTime = System.nanoTime()

        while (isRunning) {
            val now = System.nanoTime()
            val dt = ((now - lastTime) / 1_000_000_000f).coerceIn(0.005f, 0.05f)
            lastTime = now

            val dtScale = dt * 60f

            update(dtScale)
            drawFrame(dtScale)

            val targetSleep = 16L - ((System.nanoTime() - now) / 1_000_000L)
            if (targetSleep > 0) {
                try {
                    Thread.sleep(targetSleep)
                } catch (_: InterruptedException) {}
            }
        }
    }

    private fun update(dtScale: Float) {
        if (state != GameState.PLAYING) return

        // Progressive Speed Curve: scales smoothly with distance up to 16.5f!
        val safeDistance = distance.coerceAtLeast(0f)
        val progressBoost = (safeDistance / 70.0).pow(0.66) * 1.35
        val unscaledSpeed = (baseSpeed + progressBoost).coerceAtMost(maxSpeed.toDouble()).toFloat()
        currentSpeed = unscaledSpeed * dtScale

        distance += currentSpeed * 0.055f
        score = distance.toInt()
        trackOffset = (trackOffset + currentSpeed) % 60f

        // Milestone Speed Tier checking
        val newTier = when {
            unscaledSpeed < 7.5f -> 1
            unscaledSpeed < 10.5f -> 2
            unscaledSpeed < 13.5f -> 3
            else -> 4
        }
        if (newTier > speedTier) {
            speedTier = newTier
            sound.playSpeedUp()
            speedUpBannerText = when (speedTier) {
                2 -> "⚡ 提速! 进入疾速阶段 ⚡"
                3 -> "🔥 极速狂飙! 神经紧绷 🔥"
                else -> "🚀 超光速极限冲刺! 🚀"
            }
            speedUpBannerTimer = 75 // Frames to show
        }

        if (speedUpBannerTimer > 0) {
            speedUpBannerTimer--
        }

        // Responsive lane transition (Scales with speed so you can dodge in time!)
        val laneSpeed = (0.24f + (unscaledSpeed - baseSpeed) * 0.012f).coerceAtMost(0.42f) * dtScale
        playerCurrentX += (playerTargetLane - playerCurrentX) * laneSpeed

        // Jump and Gravity
        if (playerY > 0f || playerVy != 0f) {
            playerY += playerVy * dtScale
            playerVy -= 0.55f * dtScale
            if (playerY <= 0f) {
                playerY = 0f
                playerVy = 0f
            }
        }

        // Slide duration
        if (isSliding) {
            slideTimer--
            if (slideTimer <= 0) {
                isSliding = false
            }
        }

        animTime += 0.12f * dtScale * (unscaledSpeed / baseSpeed)

        // Speed lines animation
        if (unscaledSpeed > 7.5f) {
            val maxDimension = max(screenWidth, screenHeight) * 0.8f
            for (sl in speedLines) {
                sl.dist += sl.speed * dtScale * (unscaledSpeed / 8f)
                if (sl.dist > maxDimension) {
                    sl.dist = (Math.random() * 80 + 30).toFloat()
                    sl.angle = (Math.random() * PI * 2).toFloat()
                }
            }
        }

        // Spawning chunks ahead (adaptive distance based on speed)
        val spawnTriggerZ = max(900f, currentSpeed * 70f)
        if (nextSpawnZ - spawnTriggerZ < 0f) {
            spawnChunk()
        }
        nextSpawnZ -= currentSpeed

        // Obstacles collision & update
        val obstacleIterator = obstacles.iterator()
        val hitZRange = max(26f, currentSpeed * 1.8f)
        while (obstacleIterator.hasNext()) {
            val ob = obstacleIterator.next()
            ob.z -= currentSpeed

            if (ob.z < hitZRange && ob.z > -hitZRange) {
                if (abs(playerCurrentX - ob.lane) < 0.52f) {
                    var hit = false
                    when (ob.type) {
                        ObstacleType.HURDLE -> if (playerY < 22f) hit = true
                        ObstacleType.HIGH_BARRIER -> if (!isSliding) hit = true
                        ObstacleType.TRAIN -> hit = true
                    }
                    if (hit) {
                        gameOver()
                        return
                    }
                }
            }

            if (ob.z < -fov) {
                obstacleIterator.remove()
            }
        }

        // Coins update
        val coinIterator = coins.iterator()
        val coinHitRange = max(32f, currentSpeed * 2.0f)
        while (coinIterator.hasNext()) {
            val coin = coinIterator.next()
            coin.z -= currentSpeed

            if (!coin.collected && coin.z < coinHitRange && coin.z > -coinHitRange) {
                if (abs(playerCurrentX - coin.lane) < 0.55f) {
                    coin.collected = true
                    coinsCollected++
                    sound.playCoin()

                    val proj = project(coin.lane.toFloat(), coin.y, coin.z)
                    for (i in 0 until 6) {
                        particles.add(
                            Particle(
                                proj.x, proj.y,
                                (Math.random().toFloat() - 0.5f) * 5f,
                                (Math.random().toFloat() - 0.5f) * 5f - 2f,
                                1.0f,
                                Color.rgb(255, 215, 0)
                            )
                        )
                    }
                }
            }

            if (coin.z < -fov || coin.collected) {
                coinIterator.remove()
            }
        }

        // Particles
        val particleIterator = particles.iterator()
        while (particleIterator.hasNext()) {
            val p = particleIterator.next()
            p.x += p.vx * dtScale
            p.y += p.vy * dtScale
            p.life -= 0.04f * dtScale
            if (p.life <= 0f) {
                particleIterator.remove()
            }
        }
    }

    private fun spawnWaveAt(zPos: Float) {
        val lanes = intArrayOf(-1, 0, 1)
        val shuffledLanes = lanes.toMutableList().apply { shuffle() }

        val numObstacles = if (Math.random() < 0.4) 2 else 1
        val types = ObstacleType.values()

        for (i in 0 until numObstacles) {
            val lane = shuffledLanes[i]
            val type = types[(Math.random() * types.size).toInt()]
            obstacles.add(Obstacle(lane, zPos, type))
        }

        val freeLane = shuffledLanes[numObstacles]
        val coinCount = (3..5).random()
        for (c in 0 until coinCount) {
            coins.add(Coin(freeLane, zPos + c * 38f, 18f))
        }
    }

    private fun spawnChunk() {
        spawnWaveAt(nextSpawnZ)
        val reactionDist = max(380f, currentSpeed * 44f)
        nextSpawnZ += reactionDist + (Math.random().toFloat() * 100f)
    }

    private data class ProjectedPoint(val x: Float, val y: Float, val scale: Float)

    private fun project(laneOffset: Float, yOffset: Float, z: Float): ProjectedPoint {
        var safeZ = z
        if (safeZ <= -fov) safeZ = -fov + 0.1f
        val scale = fov / (fov + safeZ)
        val cx = screenWidth / 2f
        val px = cx + (laneOffset * laneSpacing) * scale
        val py = horizonY + (groundY - horizonY) * scale - (yOffset * scale)
        return ProjectedPoint(px, py, scale)
    }

    private fun drawFrame(dtScale: Float) {
        val canvas = holder.lockCanvas() ?: return
        try {
            render(canvas, dtScale)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun render(canvas: Canvas, dtScale: Float) {
        val unscaledSpeed = currentSpeed / dtScale

        // 1. Sky & Horizon
        val skyPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, horizonY,
                Color.rgb(7, 10, 20),
                Color.rgb(255, 102, 0),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, screenWidth, horizonY, skyPaint)

        // Skyline
        paint.color = Color.rgb(15, 20, 34)
        val bldgWidth = screenWidth / 9f
        for (b in 0 until 9) {
            val bHeight = 25f + (sin(b * 3.7f) * 22f)
            canvas.drawRect(b * bldgWidth, horizonY - bHeight, (b + 1) * bldgWidth - 2f, horizonY, paint)
        }

        // 2. Ground ballast
        val groundPaint = Paint().apply {
            shader = LinearGradient(
                0f, horizonY, 0f, screenHeight,
                Color.rgb(45, 48, 58),
                Color.rgb(11, 12, 14),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, horizonY, screenWidth, screenHeight, groundPaint)

        // 3. Tracks & Rails
        val maxZ = 1000f

        // Sleepers
        paint.color = Color.rgb(60, 51, 43)
        var z = 20f
        while (z < maxZ) {
            val renderZ = (z - trackOffset + maxZ) % maxZ
            if (renderZ >= 5f) {
                val pLeft = project(-1.45f, 0f, renderZ)
                val pRight = project(1.45f, 0f, renderZ)
                val tieHeight = (6f * pLeft.scale).coerceAtLeast(2f)
                canvas.drawRect(pLeft.x, pLeft.y, pRight.x, pLeft.y + tieHeight, paint)
            }
            z += 35f
        }

        // Rails
        val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(148, 163, 184)
            strokeCap = Paint.Cap.ROUND
        }
        val lanes = intArrayOf(-1, 0, 1)
        for (lane in lanes) {
            for (offset in floatArrayOf(-0.32f, 0.32f)) {
                val near = project(lane + offset, 0f, 0f)
                val far = project(lane + offset, 0f, maxZ)
                railPaint.strokeWidth = (4f * near.scale).coerceAtLeast(1.5f)
                canvas.drawLine(far.x, far.y, near.x, near.y, railPaint)
            }
        }

        // 4. Render 3D Entities sorted by Z
        data class RenderItem(val z: Float, val draw: () -> Unit)
        val renderList = ArrayList<RenderItem>()

        for (ob in obstacles) {
            if (ob.z in 0f..maxZ) {
                renderList.add(RenderItem(ob.z) { drawObstacle(canvas, ob) })
            }
        }

        for (coin in coins) {
            if (coin.z in 0f..maxZ && !coin.collected) {
                renderList.add(RenderItem(coin.z) { drawCoin(canvas, coin) })
            }
        }

        renderList.add(RenderItem(0f) { drawPlayer(canvas) })

        renderList.sortByDescending { it.z }
        for (item in renderList) {
            item.draw()
        }

        // 5. Particles
        for (p in particles) {
            paint.color = p.color
            paint.alpha = (p.life * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, 5f * p.life, paint)
        }
        paint.alpha = 255

        // 6. High-Speed Wind Lines effect
        if (unscaledSpeed > 7.5f && state == GameState.PLAYING) {
            paint.color = Color.argb(70, 255, 255, 255)
            paint.strokeWidth = 3f
            val cx = screenWidth / 2f
            val cy = horizonY
            for (sl in speedLines) {
                val x1 = cx + cos(sl.angle) * sl.dist
                val y1 = cy + sin(sl.angle) * sl.dist * 0.7f
                val x2 = cx + cos(sl.angle) * (sl.dist + sl.len)
                val y2 = cy + sin(sl.angle) * (sl.dist + sl.len) * 0.7f
                canvas.drawLine(x1, y1, x2, y2, paint)
            }
            paint.strokeWidth = 1f
        }

        // 7. High-Speed Edge Vignette
        if (unscaledSpeed > 10.0f && state == GameState.PLAYING) {
            val alpha = ((unscaledSpeed - 10f) / 10f * 90f).toInt().coerceIn(0, 90)
            paint.color = Color.argb(alpha, 255, 50, 0)
            canvas.drawRect(0f, 0f, screenWidth, 30f, paint)
            canvas.drawRect(0f, screenHeight - 30f, screenWidth, screenHeight, paint)
        }

        // 8. Draw Virtual Touch Buttons (During active gameplay)
        if (state == GameState.PLAYING) {
            drawTouchControls(canvas)
        }

        // 9. HUD / UI Overlays
        when (state) {
            GameState.PLAYING -> {
                drawHud(canvas, unscaledSpeed)
                if (speedUpBannerTimer > 0) {
                    drawSpeedUpBanner(canvas)
                }
            }
            GameState.START -> drawStartScreen(canvas)
            GameState.GAMEOVER -> drawGameOverScreen(canvas, unscaledSpeed)
        }
    }

    private fun drawTouchControls(canvas: Canvas) {
        drawButton(canvas, btnLeftRect, "◀", "左道", activeBtn == "LEFT")
        drawButton(canvas, btnJumpRect, "▲", "跳跃", activeBtn == "JUMP")
        drawButton(canvas, btnSlideRect, "▼", "滑铲", activeBtn == "SLIDE")
        drawButton(canvas, btnRightRect, "▶", "右道", activeBtn == "RIGHT")
    }

    private fun drawButton(canvas: Canvas, rect: RectF, symbol: String, label: String, isActive: Boolean) {
        val corner = rect.width() / 2f
        paint.color = if (isActive) Color.argb(160, 255, 170, 0) else Color.argb(60, 255, 255, 255)
        canvas.drawRoundRect(rect, corner, corner, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = if (isActive) Color.rgb(255, 215, 0) else Color.argb(100, 255, 255, 255)
        canvas.drawRoundRect(rect, corner, corner, paint)
        paint.style = Paint.Style.FILL

        textPaint.color = Color.WHITE
        textPaint.textSize = rect.height() * 0.36f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(symbol, rect.centerX(), rect.centerY() - 2f, textPaint)

        textPaint.textSize = rect.height() * 0.18f
        paint.color = Color.argb(220, 255, 255, 255)
        canvas.drawText(label, rect.centerX(), rect.bottom - 10f, textPaint)
    }

    private fun drawObstacle(canvas: Canvas, ob: Obstacle) {
        val p = project(ob.lane.toFloat(), 0f, ob.z)
        val scale = p.scale

        when (ob.type) {
            ObstacleType.TRAIN -> {
                val trainW = 96f * scale
                val trainH = 135f * scale
                val left = p.x - trainW / 2f
                val top = p.y - trainH

                paint.color = Color.rgb(220, 38, 38)
                canvas.drawRect(left, top, left + trainW, top + trainH, paint)

                paint.color = Color.rgb(148, 163, 184)
                canvas.drawRect(left, top + trainH * 0.72f, left + trainW, top + trainH, paint)

                paint.color = Color.rgb(30, 41, 59)
                canvas.drawRect(left + trainW * 0.12f, top + trainH * 0.15f, left + trainW * 0.88f, top + trainH * 0.5f, paint)

                paint.color = Color.rgb(254, 240, 138)
                canvas.drawCircle(left + trainW * 0.22f, top + trainH * 0.62f, 6.5f * scale, paint)
                canvas.drawCircle(left + trainW * 0.78f, top + trainH * 0.62f, 6.5f * scale, paint)
            }
            ObstacleType.HURDLE -> {
                val w = 88f * scale
                val h = 38f * scale
                val left = p.x - w / 2f
                val top = p.y - h

                paint.color = Color.rgb(71, 85, 105)
                canvas.drawRect(left + 5f * scale, top, left + 11f * scale, top + h, paint)
                canvas.drawRect(left + w - 11f * scale, top, left + w - 5f * scale, top + h, paint)

                paint.color = Color.rgb(234, 179, 8)
                canvas.drawRect(left, top + 6f * scale, left + w, top + 26f * scale, paint)

                paint.color = Color.rgb(30, 41, 59)
                for (s in 0 until 4) {
                    canvas.drawRect(
                        left + s * 22f * scale + 4f * scale,
                        top + 6f * scale,
                        left + s * 22f * scale + 14f * scale,
                        top + 26f * scale,
                        paint
                    )
                }
            }
            ObstacleType.HIGH_BARRIER -> {
                val w = 110f * scale
                val totalH = 125f * scale
                val barH = 46f * scale
                val left = p.x - w / 2f
                val top = p.y - totalH

                paint.color = Color.rgb(100, 116, 139)
                canvas.drawRect(left, top, left + 9f * scale, top + totalH, paint)
                canvas.drawRect(left + w - 9f * scale, top, left + w, top + totalH, paint)

                paint.color = Color.rgb(234, 88, 12)
                canvas.drawRect(left, top, left + w, top + barH, paint)

                paint.color = Color.rgb(254, 240, 138)
                canvas.drawRect(left + 8f * scale, top + 14f * scale, left + w - 8f * scale, top + 24f * scale, paint)
            }
        }
    }

    private fun drawCoin(canvas: Canvas, coin: Coin) {
        val p = project(coin.lane.toFloat(), coin.y, coin.z)
        val scale = p.scale
        val radius = 22f * scale
        val spin = abs(sin((coin.z + distance * 4f) * 0.08f))

        paint.color = Color.rgb(245, 158, 11)
        canvas.drawOval(
            p.x - radius * spin,
            p.y - radius,
            p.x + radius * spin,
            p.y + radius,
            paint
        )

        paint.color = Color.rgb(254, 240, 138)
        canvas.drawOval(
            p.x - radius * 0.7f * spin,
            p.y - radius * 0.7f,
            p.x + radius * 0.7f * spin,
            p.y + radius * 0.7f,
            paint
        )
    }

    private fun drawPlayer(canvas: Canvas) {
        val p = project(playerCurrentX, playerY, 0f)
        val scale = p.scale

        val shadowP = project(playerCurrentX, 0f, 0f)
        paint.color = Color.argb(100, 0, 0, 0)
        val shadowW = (if (isSliding) 58f else 42f) * scale
        val shadowH = 12f * scale
        canvas.drawOval(
            shadowP.x - shadowW / 2f,
            shadowP.y - shadowH / 2f,
            shadowP.x + shadowW / 2f,
            shadowP.y + shadowH / 2f,
            paint
        )

        if (isSliding) {
            val w = 64f * scale
            val h = 30f * scale

            paint.color = Color.rgb(29, 78, 216)
            canvas.drawRect(p.x - w * 0.4f, p.y - h * 0.6f, p.x + w * 0.2f, p.y - h * 0.15f, paint)

            paint.color = Color.rgb(220, 38, 38)
            canvas.drawRect(p.x - w * 0.1f, p.y - h * 0.95f, p.x + w * 0.3f, p.y - h * 0.35f, paint)

            paint.color = Color.rgb(245, 158, 11)
            canvas.drawCircle(p.x + w * 0.38f, p.y - h * 0.75f, 11f * scale, paint)
        } else {
            val bodyW = 32f * scale
            val bodyH = 72f * scale
            val legSwing = if (playerY > 0f) -10f * scale else sin(animTime * 2f) * 12f * scale

            // Legs
            paint.color = Color.rgb(30, 58, 138)
            canvas.drawRect(p.x - bodyW * 0.35f, p.y - bodyH * 0.35f, p.x - bodyW * 0.1f, p.y + legSwing, paint)
            canvas.drawRect(p.x + bodyW * 0.1f, p.y - bodyH * 0.35f, p.x + bodyW * 0.35f, p.y - legSwing, paint)

            // Sneakers
            paint.color = Color.WHITE
            canvas.drawRect(p.x - bodyW * 0.4f, p.y + legSwing - 2f, p.x - bodyW * 0.05f, p.y + legSwing + 5f * scale, paint)
            canvas.drawRect(p.x + bodyW * 0.05f, p.y - legSwing - 2f, p.x + bodyW * 0.4f, p.y - legSwing + 5f * scale, paint)

            // Red Hoodie
            paint.color = Color.rgb(225, 29, 72)
            canvas.drawRoundRect(
                p.x - bodyW * 0.45f,
                p.y - bodyH * 0.8f,
                p.x + bodyW * 0.45f,
                p.y - bodyH * 0.32f,
                6f * scale,
                6f * scale,
                paint
            )

            // Head
            paint.color = Color.rgb(254, 215, 170)
            canvas.drawCircle(p.x, p.y - bodyH * 0.92f, 11f * scale, paint)

            // Cap
            paint.color = Color.rgb(2, 132, 199)
            canvas.drawArc(
                p.x - 12f * scale,
                p.y - bodyH * 0.96f - 12f * scale,
                p.x + 12f * scale,
                p.y - bodyH * 0.96f + 12f * scale,
                180f,
                180f,
                true,
                paint
            )
            canvas.drawRect(p.x - 15f * scale, p.y - bodyH * 0.97f, p.x - 6f * scale, p.y - bodyH * 0.93f, paint)
        }
    }

    private fun drawHud(canvas: Canvas, unscaledSpeed: Float) {
        val multiplier = String.format("%.1f", unscaledSpeed / baseSpeed)

        textPaint.color = Color.WHITE
        textPaint.textSize = screenHeight * 0.034f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("${score}m", 40f, 90f, textPaint)

        // Speed multiplier pill
        textPaint.textSize = screenHeight * 0.018f
        val speedBadgeText = "⚡ ${multiplier}x 速度"
        paint.color = when (speedTier) {
            1 -> Color.argb(120, 0, 180, 216)
            2 -> Color.argb(160, 255, 140, 0)
            3 -> Color.argb(180, 230, 57, 70)
            else -> Color.argb(200, 168, 85, 247)
        }
        canvas.drawRoundRect(40f, 105f, 190f, 145f, 20f, 20f, paint)
        textPaint.color = Color.WHITE
        canvas.drawText(speedBadgeText, 55f, 132f, textPaint)

        textPaint.color = Color.rgb(255, 215, 0)
        textPaint.textSize = screenHeight * 0.032f
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("🪙 $coinsCollected", screenWidth - 40f, 90f, textPaint)
    }

    private fun drawSpeedUpBanner(canvas: Canvas) {
        val bannerY = screenHeight * 0.28f
        paint.color = Color.argb(200, 255, 60, 0)
        canvas.drawRoundRect(
            screenWidth / 2f - 240f,
            bannerY - 35f,
            screenWidth / 2f + 240f,
            bannerY + 35f,
            35f,
            35f,
            paint
        )

        textPaint.color = Color.WHITE
        textPaint.textSize = screenHeight * 0.026f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(speedUpBannerText, screenWidth / 2f, bannerY + 10f, textPaint)
    }

    private fun drawStartScreen(canvas: Canvas) {
        paint.color = Color.argb(210, 6, 9, 18)
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, paint)

        textPaint.color = Color.rgb(255, 170, 0)
        textPaint.textSize = screenHeight * 0.052f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("SUBWAY DASH", screenWidth / 2f, screenHeight * 0.34f, textPaint)

        textPaint.color = Color.rgb(203, 213, 225)
        textPaint.textSize = screenHeight * 0.022f
        canvas.drawText("地铁极速跑酷 · 渐进加速版", screenWidth / 2f, screenHeight * 0.40f, textPaint)

        val btnY = screenHeight * 0.50f
        paint.color = Color.rgb(255, 102, 0)
        canvas.drawRoundRect(
            screenWidth / 2f - 180f,
            btnY - 45f,
            screenWidth / 2f + 180f,
            btnY + 45f,
            45f,
            45f,
            paint
        )

        textPaint.color = Color.WHITE
        textPaint.textSize = screenHeight * 0.028f
        canvas.drawText("开始极速冲刺", screenWidth / 2f, btnY + 10f, textPaint)

        textPaint.color = Color.rgb(255, 190, 80)
        textPaint.textSize = screenHeight * 0.022f
        canvas.drawText("⚡ 动态加速: 随着距离越跑越快，刺激翻倍！", screenWidth / 2f, screenHeight * 0.64f, textPaint)

        textPaint.color = Color.rgb(148, 163, 184)
        textPaint.textSize = screenHeight * 0.019f
        canvas.drawText("📱 触屏: 屏幕任意位置滑动 或 点击底部4大按键", screenWidth / 2f, screenHeight * 0.70f, textPaint)
    }

    private fun drawGameOverScreen(canvas: Canvas, unscaledSpeed: Float) {
        paint.color = Color.argb(220, 6, 9, 18)
        canvas.drawRect(0f, 0f, screenWidth, screenHeight, paint)

        textPaint.color = Color.rgb(255, 68, 68)
        textPaint.textSize = screenHeight * 0.052f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("撞到啦!", screenWidth / 2f, screenHeight * 0.30f, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = screenHeight * 0.028f
        canvas.drawText("本次得分: ${score}m", screenWidth / 2f, screenHeight * 0.38f, textPaint)

        val multiplier = String.format("%.1f", unscaledSpeed / baseSpeed)
        textPaint.color = Color.rgb(255, 170, 0)
        canvas.drawText("极速倍率: ${multiplier}x 速度", screenWidth / 2f, screenHeight * 0.44f, textPaint)

        textPaint.color = Color.rgb(255, 215, 0)
        canvas.drawText("收集金币: 🪙 $coinsCollected", screenWidth / 2f, screenHeight * 0.50f, textPaint)

        textPaint.color = Color.rgb(74, 222, 128)
        canvas.drawText("历史最高: ${bestScore}m", screenWidth / 2f, screenHeight * 0.56f, textPaint)

        val btnY = screenHeight * 0.66f
        paint.color = Color.rgb(255, 102, 0)
        canvas.drawRoundRect(
            screenWidth / 2f - 180f,
            btnY - 45f,
            screenWidth / 2f + 180f,
            btnY + 45f,
            45f,
            45f,
            paint
        )

        textPaint.color = Color.WHITE
        textPaint.textSize = screenHeight * 0.028f
        canvas.drawText("点击屏幕再跑一次", screenWidth / 2f, btnY + 10f, textPaint)
    }
}
