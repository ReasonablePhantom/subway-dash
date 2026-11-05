package com.brucelyu.subwaydash

enum class ObstacleType {
    TRAIN,        // Large subway car, must change lanes to avoid
    HURDLE,       // Low barricade, must jump over or change lanes
    HIGH_BARRIER  // Overhead gantry, must slide under or change lanes
}

data class Obstacle(
    val lane: Int,       // -1 = Left, 0 = Center, 1 = Right
    var z: Float,        // Distance ahead of the player
    val type: ObstacleType
)

data class Coin(
    val lane: Int,
    var z: Float,
    val y: Float = 20f,
    var collected: Boolean = false
)

data class Particle(
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    var life: Float = 1.0f,
    val color: Int
)
