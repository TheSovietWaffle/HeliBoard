// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.suggestions

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Spanned
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import java.text.BreakIterator
import kotlin.math.abs

/**
 * Fork: suggestion word that morphs letter by letter, like the Samsung keyboard.
 *
 * When the text changes, letters both words have in common (longest common subsequence) stay solid and
 * slide from their old spot to the new one, letters only in the old word fade out, letters only in the new
 * word fade in, and everything drifts along so the word re-centers. ~200 ms, decelerating.
 *
 * The geometry of the last drawn frame is remembered, so this works even though the strip removes,
 * re-adds and re-styles the view on every update.
 */
class MorphTextView(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : TextView(context, attrs, defStyleAttr) {

    private class Geometry(
        val text: String,
        val clusters: List<String>,
        val xs: FloatArray, // left x of each cluster, in view coordinates
        val widths: FloatArray,
        val baseline: Float,
        val paint: TextPaint,
        val color: Int,
    ) {
        val center: Float get() = if (xs.isEmpty()) 0f else (xs.first() + xs.last() + widths.last()) / 2f
    }

    private var last: Geometry? = null // what is on screen (or was, when the view was last drawn)
    private var from: Geometry? = null
    private var to: Geometry? = null
    private var matchOldToNew = IntArray(0) // old cluster index -> new cluster index, or -1
    private var matchNewToOld = IntArray(0)
    private var startTime = 0L
    private val argb = ArgbEvaluator()
    private val workPaint = TextPaint()

    override fun onDraw(canvas: Canvas) {
        val current = text?.toString().orEmpty()
        val previous = last
        if (to == null && previous != null && previous.text != current && animationsEnabled()) {
            val target = computeGeometry()
            if (target != null && (previous.clusters.isNotEmpty() || target.clusters.isNotEmpty())) {
                startMorph(previous, target)
            }
        } else if (to != null && to!!.text != current) {
            // changed again mid-morph: continue from the target we were heading to
            val target = computeGeometry()
            if (target != null) startMorph(to!!, target) else to = null
        }

        val target = to
        if (target == null) {
            super.onDraw(canvas)
            last = computeGeometry() ?: last
            return
        }
        val p = ((SystemClock.uptimeMillis() - startTime).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        if (p >= 1f) {
            to = null
            from = null
            last = target
            super.onDraw(canvas)
            return
        }
        drawMorph(canvas, from!!, target, p)
        postInvalidateOnAnimation()
    }

    private fun startMorph(old: Geometry, new: Geometry) {
        from = old
        to = new
        val (o2n, n2o) = lcsMatch(old.clusters, new.clusters)
        matchOldToNew = o2n
        matchNewToOld = n2o
        startTime = SystemClock.uptimeMillis()
    }

    private fun drawMorph(canvas: Canvas, old: Geometry, new: Geometry, p: Float) {
        val move = MOVE.getInterpolation(p)
        // new letters lead, old letters follow a bit later (as in the Samsung recording)
        val inAlpha = (p / 0.6f).coerceIn(0f, 1f)
        val outAlpha = 1f - ((p - 0.15f) / 0.7f).coerceIn(0f, 1f)
        val baseline = old.baseline + (new.baseline - old.baseline) * move
        val noMatchShift = new.center - old.center

        // old letters: matched ones slide to their new place, the rest drift with their neighbours and fade out
        for (i in old.clusters.indices) {
            val j = matchOldToNew[i]
            if (j >= 0) {
                val x = old.xs[i] + (new.xs[j] - old.xs[i]) * move
                val color = argb.evaluate(move, old.color, new.color) as Int
                drawCluster(canvas, new.clusters[j], x, baseline, new.paint, color, 1f)
            } else {
                val dx = shiftNearOld(i, old, new) ?: noMatchShift
                drawCluster(canvas, old.clusters[i], old.xs[i] + dx * move, baseline, old.paint, old.color, outAlpha)
            }
        }
        // new letters that weren't there before fade in, travelling with their neighbours
        for (j in new.clusters.indices) {
            if (matchNewToOld[j] >= 0) continue
            val dx = shiftNearNew(j, old, new) ?: noMatchShift
            drawCluster(canvas, new.clusters[j], new.xs[j] - dx * (1f - move), baseline, new.paint, new.color, inAlpha)
        }
    }

    /** how far the nearest matched letter (left first, then right) of old cluster [i] travels */
    private fun shiftNearOld(i: Int, old: Geometry, new: Geometry): Float? {
        for (d in 1..old.clusters.size) {
            val l = i - d
            if (l >= 0 && matchOldToNew[l] >= 0) return new.xs[matchOldToNew[l]] - old.xs[l]
            val r = i + d
            if (r < old.clusters.size && matchOldToNew[r] >= 0) return new.xs[matchOldToNew[r]] - old.xs[r]
            if (l < 0 && r >= old.clusters.size) break
        }
        return null
    }

    private fun shiftNearNew(j: Int, old: Geometry, new: Geometry): Float? {
        for (d in 1..new.clusters.size) {
            val l = j - d
            if (l >= 0 && matchNewToOld[l] >= 0) return new.xs[l] - old.xs[matchNewToOld[l]]
            val r = j + d
            if (r < new.clusters.size && matchNewToOld[r] >= 0) return new.xs[r] - old.xs[matchNewToOld[r]]
            if (l < 0 && r >= new.clusters.size) break
        }
        return null
    }

    private fun drawCluster(canvas: Canvas, s: String, x: Float, baseline: Float, paint: TextPaint, color: Int, alpha: Float) {
        if (alpha <= 0.01f) return
        workPaint.set(paint)
        workPaint.color = color
        workPaint.alpha = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        canvas.drawText(s, x, baseline, workPaint)
    }

    /** where every letter of the current text is drawn by the normal TextView layout */
    private fun computeGeometry(): Geometry? {
        val layout = layout ?: return null
        val content = text ?: ""
        val str = content.toString()
        if (str.isEmpty()) {
            return Geometry("", emptyList(), FloatArray(0), FloatArray(0), baseline.toFloat(), TextPaint(paint), currentTextColor)
        }
        if (layout.lineCount != 1 || layout.getEllipsisCount(0) > 0) return null // keep it simple: no morph

        val p = TextPaint(paint)
        if (content is Spanned) {
            if (content.getSpans(0, content.length, StyleSpan::class.java).any { it.style and Typeface.BOLD != 0 })
                p.typeface = Typeface.create(p.typeface, Typeface.BOLD)
            if (content.getSpans(0, content.length, UnderlineSpan::class.java).isNotEmpty())
                p.isUnderlineText = true
        }
        val clusters = splitClusters(str)
        val widths = FloatArray(clusters.size) { p.measureText(clusters[it]) }
        // scale measured widths to the real line width, so the end state matches TextView's own drawing
        val measured = widths.sum()
        val lineWidth = layout.getLineWidth(0)
        if (measured > 0f && abs(measured - lineWidth) < measured * 0.25f) {
            val f = lineWidth / measured
            for (k in widths.indices) widths[k] *= f
        }
        val xs = FloatArray(clusters.size)
        var x = layout.getLineLeft(0) + totalPaddingLeft - scrollX
        for (k in clusters.indices) {
            xs[k] = x
            x += widths[k]
        }
        return Geometry(str, clusters, xs, widths, baseline.toFloat(), p, currentTextColor)
    }

    companion object {
        private const val DURATION_MS = 200f
        private val MOVE = DecelerateInterpolator(1.5f)

        private fun animationsEnabled() = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

        /** grapheme clusters, so emoji and combined letters move as one */
        private fun splitClusters(s: String): List<String> {
            val it = BreakIterator.getCharacterInstance()
            it.setText(s)
            val result = ArrayList<String>()
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                result.add(s.substring(start, end))
                start = end
                end = it.next()
            }
            return result
        }

        /** longest common subsequence, returned as index maps in both directions (-1 = unmatched) */
        private fun lcsMatch(a: List<String>, b: List<String>): Pair<IntArray, IntArray> {
            val n = a.size
            val m = b.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            val aToB = IntArray(n) { -1 }
            val bToA = IntArray(m) { -1 }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    a[i] == b[j] -> { aToB[i] = j; bToA[j] = i; i++; j++ }
                    dp[i + 1][j] >= dp[i][j + 1] -> i++
                    else -> j++
                }
            }
            return aToB to bToA
        }
    }
}
