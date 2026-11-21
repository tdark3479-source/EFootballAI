package com.example.efootballai

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Team {
    USER,
    OPPONENT
}

enum class AttackDirection {
    UP,
    DOWN
}

data class PlayerTrack(
    val id: String,
    val team: Team,
    val x: Float,
    val y: Float,
    val confidence: Float = 1f
)

data class BallTrack(
    val x: Float,
    val y: Float,
    val confidence: Float = 1f
)

data class TacticalSnapshot(
    val players: List<PlayerTrack>,
    val ball: BallTrack,
    val attackDirection: AttackDirection = AttackDirection.UP
)

data class TacticalInsight(
    val title: String,
    val explanation: String,
    val priority: Int
)

data class TacticalResult(
    val valid: Boolean,
    val insights: List<TacticalInsight>,
    val pressureCount: Int,
    val bestPassingTarget: String?,
    val passingLaneClearance: Float?
)

class TacticalEngine {

    companion object {
        private const val MIN_CONFIDENCE = 0.45f
        private const val PRESSURE_RADIUS = 0.13f
        private const val MIN_PROGRESS = 0.025f
    }

    fun analyze(snapshot: TacticalSnapshot): TacticalResult {
        val players = snapshot.players.filter {
            it.confidence >= MIN_CONFIDENCE &&
            it.x in 0f..1f &&
            it.y in 0f..1f
        }

        val ball = snapshot.ball

        if (ball.confidence < MIN_CONFIDENCE ||
            ball.x !in 0f..1f ||
            ball.y !in 0f..1f
        ) {
            return invalidResult()
        }

        val teammates = players.filter { it.team == Team.USER }
        val opponents = players.filter { it.team == Team.OPPONENT }

        if (teammates.size < 2 || opponents.isEmpty()) {
            return invalidResult()
        }

        val carrier = teammates.minByOrNull {
            distance(it.x, it.y, ball.x, ball.y)
        } ?: return invalidResult()

        val pressureCount = opponents.count {
            distance(it.x, it.y, ball.x, ball.y) <= PRESSURE_RADIUS
        }

        val insights = mutableListOf<TacticalInsight>()

        if (pressureCount >= 2) {
            insights.add(
                TacticalInsight(
                    "High ball pressure",
                    "$pressureCount opponents are close to the ball. Consider a quick release or a supporting pass.",
                    3
                )
            )
        } else if (pressureCount == 1) {
            insights.add(
                TacticalInsight(
                    "Opponent closing down",
                    "One opponent is close to the ball. Check nearby support before advancing.",
                    2
                )
            )
        }

        val candidates = teammates
            .filter { it.id != carrier.id }
            .map { receiver ->
                val progress = forwardProgress(
                    carrier.y,
                    receiver.y,
                    snapshot.attackDirection
                )

                val clearance = opponents.minOfOrNull {
                    pointToSegmentDistance(
                        it.x, it.y,
                        carrier.x, carrier.y,
                        receiver.x, receiver.y
                    )
                } ?: 0f

                Triple(receiver, progress, clearance)
            }
            .filter { it.second > MIN_PROGRESS }
            .sortedByDescending {
                (it.second * 0.55f) + (it.third * 0.45f)
            }

        val best = candidates.firstOrNull()

        if (best != null) {
            val receiver = best.first
            val clearance = best.third

            if (clearance >= 0.075f) {
                insights.add(
                    TacticalInsight(
                        "Potential forward pass",
                        "Player ${receiver.id} is ahead of the ball with a relatively clear passing lane.",
                        3
                    )
                )
            } else {
                insights.add(
                    TacticalInsight(
                        "Passing lane congested",
                        "The forward option to player ${receiver.id} may be intercepted. Check another route.",
                        2
                    )
                )
            }
        } else {
            insights.add(
                TacticalInsight(
                    "No clear forward option",
                    "No detected teammate offers sufficient forward progress. Retain possession or reposition.",
                    1
                )
            )
        }

        return TacticalResult(
            valid = true,
            insights = insights.sortedByDescending { it.priority },
            pressureCount = pressureCount,
            bestPassingTarget = best?.first?.id,
            passingLaneClearance = best?.third
        )
    }

    private fun forwardProgress(
        fromY: Float,
        toY: Float,
        direction: AttackDirection
    ): Float {
        return when (direction) {
            AttackDirection.UP -> fromY - toY
            AttackDirection.DOWN -> toY - fromY
        }
    }

    private fun distance(
        x1: Float, y1: Float,
        x2: Float, y2: Float
    ): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun pointToSegmentDistance(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy

        if (lengthSquared == 0f) {
            return distance(px, py, ax, ay)
        }

        val projection = (
            ((px - ax) * dx + (py - ay) * dy) /
                lengthSquared
        ).coerceIn(0f, 1f)

        val nearestX = ax + projection * dx
        val nearestY = ay + projection * dy

        return distance(px, py, nearestX, nearestY)
    }

    private fun invalidResult() = TacticalResult(
        valid = false,
        insights = emptyList(),
        pressureCount = 0,
        bestPassingTarget = null,
        passingLaneClearance = null
    )
}
