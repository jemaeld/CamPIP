package com.tvcampip

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Permite colocar la ventana flotante con las flechas del control remoto.
 * Muestra un recuadro del mismo tamaño que la ventana; OK guarda la posición y Atrás cancela.
 */
class PositionActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var box: View
    private var fx = 1f
    private var fy = 1f
    private val step = 0.05f
    private val margin by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fx = StreamPolicy.floatingPosX(this)
        fy = StreamPolicy.floatingPosY(this)

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        val help = TextView(this).apply {
            text = "Mueve el recuadro con las flechas  ·  OK: guardar  ·  Atrás: cancelar"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        root.addView(help, FrameLayout.LayoutParams(-1, -1))

        box = TextView(this).apply {
            text = "Ventana flotante"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#883F8CFF"))
                setStroke(4, Color.WHITE)
                cornerRadius = 8f
            }
        }
        val m = resources.displayMetrics
        val w = maxOf((m.widthPixels * StreamPolicy.floatingWidthFraction(this)).toInt(), (240 * m.density).toInt())
        root.addView(box, FrameLayout.LayoutParams(w, w * 9 / 16))
        setContentView(root)
        root.post { place() }
    }

    private fun place() {
        val freeX = (root.width - box.width - 2 * margin).coerceAtLeast(0)
        val freeY = (root.height - box.height - 2 * margin).coerceAtLeast(0)
        box.translationX = margin + freeX * fx
        box.translationY = margin + freeY * fy
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> fx = (fx - step).coerceAtLeast(0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> fx = (fx + step).coerceAtMost(1f)
            KeyEvent.KEYCODE_DPAD_UP -> fy = (fy - step).coerceAtLeast(0f)
            KeyEvent.KEYCODE_DPAD_DOWN -> fy = (fy + step).coerceAtMost(1f)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                StreamPolicy.setFloatingPos(this, fx, fy)
                finish()
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        place()
        return true
    }
}
