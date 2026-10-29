package com.coyotevision.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.PathParser
import kotlin.math.min

/** Decorative paper edges live in card padding, never over controls or the camera. */
class LacePaperDrawable(private val density: Float, private val welcome: Boolean = false,
    private val footer: Boolean = false) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (welcome) BlushTheme.input else BlushTheme.surface }
    private val pencil = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BlushTheme.lace; style = Paint.Style.STROKE; strokeWidth = density
    }
    private val stitch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BlushTheme.line; style = Paint.Style.STROKE; strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(3 * density, 4 * density), 0f)
    }
    private val pearl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BlushTheme.pink }
    private val rect = RectF()
    private val oval = RectF()
    private val clip = Path()

    override fun draw(canvas: Canvas) {
        val d = density
        rect.set(bounds.left + d, bounds.top + d, bounds.right - d, bounds.bottom - d)
        val radius = if (footer) 0f else 26 * d
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, pencil)
        if (!footer) {
            rect.inset(6 * d, 6 * d)
            canvas.drawRoundRect(rect, 22 * d, 22 * d, stitch)
        }
        if (welcome) {
            rect.set(bounds.left + d, bounds.top + d, bounds.right - d, bounds.bottom - d)
            clip.reset(); clip.addRoundRect(rect, 26 * d, 26 * d, Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip)
            pearl.color = Color.argb(70, 247, 225, 234)
            canvas.drawCircle(bounds.left + 20 * d, bounds.top + 35 * d, 48 * d, pearl)
            canvas.drawCircle(bounds.right - 12 * d, bounds.bottom - 35 * d, 56 * d, pearl)
            canvas.restore()
        }
        pearl.color = BlushTheme.pink
        var x = bounds.left + 26 * d
        while (x < bounds.right - 24 * d) {
            fun scallop(y: Float, lower: Boolean) {
                oval.set(x - 6 * d, y - 4 * d, x + 6 * d, y + 4 * d)
                canvas.drawArc(oval, if (lower) 180f else 0f, 180f, false, pencil)
                canvas.drawCircle(x, y, 1.4f * d, pearl)
                canvas.drawCircle(x, y, 1.4f * d, pencil)
                canvas.drawCircle(x - 3 * d, y + (if (lower) -2 else 2) * d, .55f * d, pearl)
                canvas.drawCircle(x + 3 * d, y + (if (lower) -2 else 2) * d, .55f * d, pearl)
            }
            scallop(bounds.top + 11 * d, false)
            if (!footer) scallop(bounds.bottom - 11 * d, true)
            x += 12 * d
        }
    }
    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, if (footer) 0f else 26 * density) }
    override fun setAlpha(alpha: Int) { fill.alpha = alpha; pencil.alpha = alpha; stitch.alpha = alpha; pearl.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; pencil.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Android drawable opacity compatibility")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

enum class CourtMotif { BOW, GEM, CROWN }

/** Small ink-and-watercolor ornaments. They do not consume touch or accessibility focus. */
class CourtMotifView(context: Context, private val motif: CourtMotif) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BlushTheme.pencil; style = Paint.Style.STROKE; strokeWidth = 1.6f
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val color = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BlushTheme.pink }
    private val rect = RectF()
    private fun path(data: String) = requireNotNull(PathParser.createPathFromPathData(data))
    private val bowTails = path("M40,48 C37,61 30,73 20,79 L33,79 L39,88 C44,74 49,66 50,55 C53,70 61,82 68,87 L68,75 L81,77 C69,66 63,54 60,48 Z")
    private val bowLoops = path("M49,44 C36,27 17,19 12,31 C6,49 23,62 45,52 L55,52 C78,63 93,49 88,32 C84,18 66,27 51,44 Z")
    private val bowFolds = path("M16,34 Q26,37 43,46 M18,48 Q30,51 43,49 M84,34 Q72,38 57,46 M82,49 Q70,52 57,49 M39,58 Q37,70 31,76 M60,58 Q63,68 70,73")
    private val crown = path("M18,66 Q15,47 12,32 Q26,34 32,48 L37,24 Q47,29 50,45 Q54,29 63,24 L69,48 Q80,34 88,32 L82,66 Q50,77 18,66 Z")
    private val crownLines = path("M20,61 Q50,70 80,61 M23,70 Q50,79 77,70 M32,48 L35,60 M69,48 L66,60")
    private val diamond = path("M20,36 L34,20 L66,20 L80,36 L50,80 Z")
    private val facets = path("M20,36 L80,36 M34,20 L39,36 L50,80 L61,36 L66,20 M39,36 L50,20 L61,36")
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false; isFocusable = false }
    private fun shape(canvas: Canvas, path: Path, fillColor: Int) {
        color.color = fillColor; canvas.drawPath(path, color)
        canvas.drawPath(path, ink)
        // A faint, slightly offset second contour gives the ornament a pencil finish.
        ink.alpha = 45; canvas.save(); canvas.translate(.55f, -.35f); canvas.drawPath(path, ink); canvas.restore(); ink.alpha = 255
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width, height) / 100f
        canvas.save(); canvas.translate((width - 100 * scale) / 2, (height - 100 * scale) / 2); canvas.scale(scale, scale)
        when (motif) {
            CourtMotif.BOW -> {
                shape(canvas, bowTails, BlushTheme.pink); shape(canvas, bowLoops, BlushTheme.pink)
                canvas.drawPath(bowFolds, ink)
                rect.set(43f, 40f, 57f, 56f); color.color = BlushTheme.gem
                canvas.drawOval(rect, color); canvas.drawOval(rect, ink)
                color.color = Color.WHITE; canvas.drawCircle(48f, 44f, 1.7f, color)
            }
            CourtMotif.GEM -> {
                shape(canvas, diamond, BlushTheme.pink); canvas.drawPath(facets, ink)
                color.color = Color.WHITE; canvas.drawCircle(31f, 31f, 2.1f, color)
                canvas.drawLine(78f, 15f, 78f, 26f, ink); canvas.drawLine(73f, 20f, 83f, 20f, ink)
            }
            CourtMotif.CROWN -> {
                shape(canvas, crown, BlushTheme.ivoryGold); canvas.drawPath(crownLines, ink)
                for ((x, y) in listOf(12f to 30f, 37f to 22f, 63f to 22f, 88f to 30f)) {
                    color.color = BlushTheme.input; canvas.drawCircle(x, y, 3f, color); canvas.drawCircle(x, y, 3f, ink)
                }
                color.color = BlushTheme.gem; rect.set(45f, 51f, 55f, 63f)
                canvas.drawOval(rect, color); canvas.drawOval(rect, ink)
                color.color = Color.WHITE; canvas.drawCircle(48f, 54f, 1.3f, color)
            }
        }
        canvas.restore()
    }
}
