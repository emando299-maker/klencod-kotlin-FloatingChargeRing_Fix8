package com.floatingchargering

import android.graphics.Color

/**
 * Colours of one ring theme.
 * primary/secondary: ring glow and arcs, core: bright inner line of the ring,
 * highlightA/B: the two moving dots, accent: power text and charging bolt.
 */
class RingTheme(
    val id: String,
    val name: String,
    val primary: Int,
    val secondary: Int,
    val core: Int,
    val highlightA: Int,
    val highlightB: Int,
    val accent: Int
)

object Themes {
    val all: List<RingTheme> = listOf(
        RingTheme("neon_blue", "أزرق نيون",
            Color.rgb(45, 120, 255), Color.rgb(125, 55, 255), Color.rgb(205, 218, 255),
            Color.CYAN, Color.MAGENTA, Color.rgb(255, 200, 50)),
        RingTheme("emerald", "زمردي",
            Color.rgb(30, 220, 110), Color.rgb(0, 150, 130), Color.rgb(200, 255, 225),
            Color.rgb(150, 255, 200), Color.rgb(80, 255, 255), Color.rgb(230, 255, 120)),
        RingTheme("crimson", "قرمزي",
            Color.rgb(255, 60, 70), Color.rgb(200, 20, 100), Color.rgb(255, 215, 215),
            Color.rgb(255, 160, 120), Color.rgb(255, 80, 160), Color.rgb(255, 200, 70)),
        RingTheme("gold", "ذهبي",
            Color.rgb(255, 170, 30), Color.rgb(255, 100, 20), Color.rgb(255, 236, 200),
            Color.rgb(255, 240, 150), Color.rgb(255, 150, 60), Color.rgb(255, 245, 200)),
        RingTheme("violet", "بنفسجي",
            Color.rgb(170, 80, 255), Color.rgb(255, 60, 200), Color.rgb(240, 215, 255),
            Color.rgb(220, 150, 255), Color.rgb(255, 100, 220), Color.rgb(255, 200, 50)),
        RingTheme("ice", "جليدي",
            Color.rgb(60, 215, 255), Color.rgb(30, 120, 255), Color.rgb(225, 248, 255),
            Color.rgb(200, 250, 255), Color.rgb(100, 180, 255), Color.WHITE),
        RingTheme("sunset", "غروب",
            Color.rgb(255, 110, 60), Color.rgb(200, 50, 180), Color.rgb(255, 225, 215),
            Color.rgb(255, 190, 90), Color.rgb(255, 90, 170), Color.rgb(255, 220, 120)),
        RingTheme("silver", "فضي",
            Color.rgb(230, 230, 235), Color.rgb(130, 130, 150), Color.WHITE,
            Color.WHITE, Color.rgb(190, 190, 205), Color.WHITE)
    )

    fun byId(id: String?): RingTheme = all.firstOrNull { it.id == id } ?: all[0]
}
