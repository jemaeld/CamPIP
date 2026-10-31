package com.tvcampip

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Acomoda sus hijos visibles en una cuadrícula que se adapta sola al número de cámaras:
 * elige las columnas que dan los mosaicos más grandes para la forma de la pantalla.
 * La última fila, si queda incompleta, se centra.
 */
class MosaicLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val aspect = 16f / 9f
    private val gap = (3 * resources.displayMetrics.density).toInt()

    private fun visibleChildren(): List<View> =
        (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }

    private fun bestColumns(n: Int, w: Int, h: Int): Int {
        var best = 1
        var bestArea = -1f
        for (cols in 1..n) {
            val rows = (n + cols - 1) / cols
            val cellW = w.toFloat() / cols
            val cellH = h.toFloat() / rows
            val videoW = minOf(cellW, cellH * aspect)
            val area = videoW * (videoW / aspect)
            if (area > bestArea + 1f) {
                bestArea = area
                best = cols
            }
        }
        return best
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)

        val kids = visibleChildren()
        if (kids.isEmpty() || w <= 0 || h <= 0) return
        val cols = bestColumns(kids.size, w, h)
        val rows = (kids.size + cols - 1) / cols
        val cellW = w / cols - 2 * gap
        val cellH = h / rows - 2 * gap
        for (k in kids) {
            k.measure(
                MeasureSpec.makeMeasureSpec(cellW.coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellH.coerceAtLeast(1), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val kids = visibleChildren()
        if (kids.isEmpty() || w <= 0 || h <= 0) return
        val n = kids.size
        val cols = bestColumns(n, w, h)
        val rows = (n + cols - 1) / cols
        val cellW = w / cols
        val cellH = h / rows

        kids.forEachIndexed { i, k ->
            val row = i / cols
            val col = i % cols
            val inRow = if (row == rows - 1) n - row * cols else cols
            val offsetX = (w - inRow * cellW) / 2
            val left = offsetX + col * cellW + gap
            val top = row * cellH + gap
            k.layout(left, top, left + k.measuredWidth, top + k.measuredHeight)
        }
    }
}
