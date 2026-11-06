package com.oliver.pet

/** Body parts of Oliver that react to touch. */
enum class Zone(val pleasure: Float) {
    FOREHEAD(1.0f),
    EARS(0.8f),
    CHEEKS(1.1f),
    CHIN(1.15f),
    EYES(0f),
    CHEST(0.85f),
    BACK(0.9f),
    BELLY(0.75f),
    PAWS(0.3f),
    TAIL(0f),
    NONE(0f);

    val isPleasant get() = pleasure > 0.5f
}

/**
 * Polygons measured by hand on oliver.webp, in pixels of the 1080x1920 reference image.
 * All lookups take reference-pixel coordinates.
 */
object BodyZones {
    const val REF_W = 1080f
    const val REF_H = 1920f

    class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float) {
        fun contains(x: Float, y: Float): Boolean {
            val dx = (x - cx) / rx
            val dy = (y - cy) / ry
            return dx * dx + dy * dy <= 1f
        }
    }

    class Poly(vararg pts: Int) {
        val xs = FloatArray(pts.size / 2) { pts[it * 2].toFloat() }
        val ys = FloatArray(pts.size / 2) { pts[it * 2 + 1].toFloat() }
        val cx = xs.average().toFloat()
        val cy = ys.average().toFloat()

        fun contains(x: Float, y: Float): Boolean {
            var inside = false
            var j = xs.size - 1
            for (i in xs.indices) {
                if ((ys[i] > y) != (ys[j] > y) &&
                    x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]
                ) inside = !inside
                j = i
            }
            return inside
        }

        /** Distance from (x,y) to the closest edge. */
        fun edgeDistance(x: Float, y: Float): Float {
            var best = Float.MAX_VALUE
            var j = xs.size - 1
            for (i in xs.indices) {
                val ax = xs[j]; val ay = ys[j]; val bx = xs[i]; val by = ys[i]
                val vx = bx - ax; val vy = by - ay
                val len2 = vx * vx + vy * vy
                val t = if (len2 == 0f) 0f else (((x - ax) * vx + (y - ay) * vy) / len2).coerceIn(0f, 1f)
                val px = ax + t * vx - x; val py = ay + t * vy - y
                val d = px * px + py * py
                if (d < best) best = d
                j = i
            }
            return kotlin.math.sqrt(best)
        }
    }

    val SILHOUETTE = Poly(
        470, 360, 560, 355, 640, 400, 720, 450, 800, 480, 760, 520, 745, 600, 700, 660, 660, 720,
        640, 790, 660, 860, 690, 960, 670, 1080, 640, 1120, 560, 1170, 565, 1240, 520, 1278,
        440, 1255, 400, 1170, 270, 1240, 250, 1320, 170, 1345, 130, 1300, 160, 1190, 165, 1140,
        120, 1135, 85, 1105, 80, 1050, 115, 1022, 175, 1035, 190, 900, 200, 760, 240, 640,
        300, 600, 385, 540, 390, 420
    )

    val TAIL = Poly(80, 1050, 115, 1020, 175, 1030, 185, 1100, 165, 1140, 120, 1138, 85, 1105)
    /** Pivot where the tail joins the body (used for swishing). */
    const val TAIL_PIVOT_X = 190f
    const val TAIL_PIVOT_Y = 1110f

    private val PAWS_L = Poly(135, 1250, 170, 1210, 260, 1230, 255, 1320, 170, 1345, 130, 1300)
    private val PAWS_R = Poly(440, 1200, 560, 1195, 568, 1245, 520, 1280, 440, 1258)
    val EAR_L = Poly(425, 385, 470, 358, 545, 358, 535, 398, 455, 412)
    val EAR_R = Poly(690, 450, 745, 462, 815, 478, 770, 522, 720, 535, 688, 505)
    private val FOREHEAD = Poly(445, 412, 540, 385, 640, 420, 700, 490, 700, 560, 620, 560, 570, 525, 480, 515, 430, 500)
    private val CHEEK_L = Poly(388, 500, 480, 515, 500, 590, 525, 650, 470, 705, 400, 650, 385, 560)
    private val CHEEK_R = Poly(700, 560, 745, 565, 735, 630, 690, 690, 650, 700, 640, 640, 695, 620)
    private val CHIN = Poly(525, 625, 645, 640, 665, 700, 625, 750, 545, 750, 500, 700)
    val CHEST = Poly(320, 720, 520, 755, 645, 745, 665, 830, 625, 930, 470, 960, 330, 905, 300, 800)
    private val BELLY = Poly(300, 915, 470, 962, 628, 932, 655, 1050, 625, 1150, 470, 1175, 330, 1150, 290, 1050)

    val EYE_L = Ellipse(528f, 557f, 36f, 27f)
    val EYE_R = Ellipse(658f, 595f, 36f, 27f)

    /** Head region (for "leaning into the hand" / anger icon placement). */
    const val HEAD_CX = 575f
    const val HEAD_CY = 560f
    /** Center used for breathing deformation. */
    const val BREATH_CY = 950f

    private val ordered: List<Pair<Zone, (Float, Float) -> Boolean>> = listOf(
        Zone.EYES to { x, y -> EYE_L.contains(x, y) || EYE_R.contains(x, y) },
        Zone.TAIL to TAIL::contains,
        Zone.CHIN to CHIN::contains,
        Zone.EARS to { x, y -> EAR_L.contains(x, y) || EAR_R.contains(x, y) },
        Zone.CHEEKS to { x, y -> CHEEK_L.contains(x, y) || CHEEK_R.contains(x, y) },
        Zone.FOREHEAD to FOREHEAD::contains,
        Zone.PAWS to { x, y -> PAWS_L.contains(x, y) || PAWS_R.contains(x, y) },
        Zone.CHEST to CHEST::contains,
        Zone.BELLY to BELLY::contains,
    )

    /** Zone at reference-pixel coordinates. */
    fun zoneAt(x: Float, y: Float): Zone {
        for ((zone, test) in ordered) if (test(x, y)) return zone
        if (!SILHOUETTE.contains(x, y)) return Zone.NONE
        // Unlabeled parts of the head count as forehead, the rest of the body as back/sides.
        return if (y < 740f && x > 380f) Zone.FOREHEAD else Zone.BACK
    }

    /** 0 outside the cat, ramps to 1 within [feather] px inside the silhouette. */
    fun catWeight(x: Float, y: Float, feather: Float = 40f): Float {
        val d = SILHOUETTE.edgeDistance(x, y)
        return if (SILHOUETTE.contains(x, y)) (0.35f + 0.65f * (d / feather)).coerceAtMost(1f)
        else (0.35f * (1f - d / feather)).coerceAtLeast(0f)
    }
}
