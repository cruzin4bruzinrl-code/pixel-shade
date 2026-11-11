package se.rob.pixelpanel

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Simple placeholder glyphs drawn in a unit box. Real Material Symbols come in the next build. */
enum class Glyph { WIFI, BLUETOOTH, DND, FLASHLIGHT, AIRPLANE, BATTERY, ROTATE, LOCATION, SUN }

object Icons {
    private val path = Path()
    private val rect = RectF()

    fun draw(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size
        val l = cx - s / 2
        val t = cy - s / 2
        fun x(f: Float) = l + f * s
        fun y(f: Float) = t + f * s

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = s * 0.1f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        path.reset()
        when (g) {
            Glyph.WIFI -> {
                // Filled wedge, like the Material wifi glyph
                rect.set(x(0.02f), y(0.12f), x(0.98f), y(1.08f))
                path.moveTo(x(0.5f), y(0.88f))
                path.arcTo(rect, 225f, 90f)
                path.close()
                c.drawPath(path, fill)
            }
            Glyph.BLUETOOTH -> {
                path.moveTo(x(0.28f), y(0.32f)); path.lineTo(x(0.72f), y(0.68f))
                path.lineTo(x(0.5f), y(0.88f)); path.lineTo(x(0.5f), y(0.12f))
                path.lineTo(x(0.72f), y(0.32f)); path.lineTo(x(0.28f), y(0.68f))
                c.drawPath(path, stroke)
            }
            Glyph.DND -> {
                val cut = Paint(fill).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
                val sc = c.saveLayer(l, t, l + s, t + s, null)
                c.drawCircle(cx, cy, s * 0.42f, fill)
                rect.set(x(0.27f), y(0.44f), x(0.73f), y(0.56f))
                c.drawRoundRect(rect, s * 0.06f, s * 0.06f, cut)
                c.restoreToCount(sc)
            }
            Glyph.FLASHLIGHT -> {
                rect.set(x(0.3f), y(0.08f), x(0.7f), y(0.24f))
                c.drawRoundRect(rect, s * 0.04f, s * 0.04f, fill)
                path.moveTo(x(0.3f), y(0.3f)); path.lineTo(x(0.7f), y(0.3f))
                path.lineTo(x(0.6f), y(0.5f)); path.lineTo(x(0.6f), y(0.92f))
                path.lineTo(x(0.4f), y(0.92f)); path.lineTo(x(0.4f), y(0.5f)); path.close()
                c.drawPath(path, fill)
            }
            Glyph.AIRPLANE -> {
                path.moveTo(x(0.5f), y(0.06f))
                path.lineTo(x(0.56f), y(0.14f)); path.lineTo(x(0.56f), y(0.4f))
                path.lineTo(x(0.94f), y(0.62f)); path.lineTo(x(0.94f), y(0.7f))
                path.lineTo(x(0.56f), y(0.58f)); path.lineTo(x(0.56f), y(0.8f))
                path.lineTo(x(0.68f), y(0.9f)); path.lineTo(x(0.68f), y(0.95f))
                path.lineTo(x(0.5f), y(0.9f))
                path.lineTo(x(0.32f), y(0.95f)); path.lineTo(x(0.32f), y(0.9f))
                path.lineTo(x(0.44f), y(0.8f)); path.lineTo(x(0.44f), y(0.58f))
                path.lineTo(x(0.06f), y(0.7f)); path.lineTo(x(0.06f), y(0.62f))
                path.lineTo(x(0.44f), y(0.4f)); path.lineTo(x(0.44f), y(0.14f)); path.close()
                c.drawPath(path, fill)
            }
            Glyph.BATTERY -> {
                rect.set(x(0.4f), y(0.06f), x(0.6f), y(0.14f))
                c.drawRect(rect, fill)
                rect.set(x(0.28f), y(0.12f), x(0.72f), y(0.94f))
                c.drawRoundRect(rect, s * 0.06f, s * 0.06f, stroke.apply { strokeWidth = s * 0.08f })
                // plus sign
                c.drawLine(x(0.5f), y(0.38f), x(0.5f), y(0.7f), stroke)
                c.drawLine(x(0.34f), y(0.54f), x(0.66f), y(0.54f), stroke)
            }
            Glyph.ROTATE -> {
                rect.set(x(0.16f), y(0.16f), x(0.84f), y(0.84f))
                c.drawArc(rect, -60f, 300f, false, stroke)
                path.moveTo(x(0.66f), y(0.12f)); path.lineTo(x(0.86f), y(0.18f)); path.lineTo(x(0.8f), y(0.38f))
                c.drawPath(path, stroke)
            }
            Glyph.LOCATION -> {
                path.moveTo(x(0.5f), y(0.94f))
                rect.set(x(0.2f), y(0.06f), x(0.8f), y(0.66f))
                path.arcTo(rect, 140f, 260f)
                path.close()
                val hole = Paint(fill).apply { this.color = 0; xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
                val sc = c.saveLayer(l, t, l + s, t + s, null)
                c.drawPath(path, fill)
                c.drawCircle(x(0.5f), y(0.36f), s * 0.11f, hole)
                c.restoreToCount(sc)
            }
            Glyph.SUN -> {
                c.drawCircle(cx, cy, s * 0.2f, fill)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val r1 = s * 0.32f; val r2 = s * 0.44f
                    c.drawLine(
                        cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat(),
                        cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat(),
                        stroke.apply { strokeWidth = s * 0.08f }
                    )
                }
            }
        }
    }
}
