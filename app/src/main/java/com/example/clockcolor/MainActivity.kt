package com.example.clockcolor

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {

    private lateinit var preview: TextView
    private val bars = arrayOfNulls<SeekBar>(3)

    @Suppress("DEPRECATION")
    private fun prefs() = try {
        getSharedPreferences(BuildConfigLike.PREFS, Context.MODE_WORLD_READABLE)
    } catch (e: SecurityException) {
        null // модуль не активирован в LSPosed
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        preview = TextView(this).apply {
            text = "12:34"
            textSize = 72f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        root.addView(preview)

        val saved = prefs()?.getInt("color", Color.WHITE) ?: Color.WHITE
        val labels = arrayOf("R", "G", "B")
        val init = intArrayOf(Color.red(saved), Color.green(saved), Color.blue(saved))

        for (i in 0..2) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text = labels[i]; width = dp(24) })
            val sb = SeekBar(this).apply {
                max = 255
                progress = init[i]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = updatePreview()
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            bars[i] = sb
            row.addView(sb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(row)
        }

        root.addView(Button(this).apply {
            text = "Применить"
            setOnClickListener { save(true) }
        })
        root.addView(Button(this).apply {
            text = "Сбросить (цвет по умолчанию)"
            setOnClickListener { save(false) }
        })
        root.addView(Button(this).apply {
            text = "Перезапустить SystemUI (нужен root)"
            setOnClickListener { restartSystemUi() }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        updatePreview()
    }

    private fun current() = Color.rgb(bars[0]!!.progress, bars[1]!!.progress, bars[2]!!.progress)

    private fun updatePreview() = preview.setTextColor(current())

    private fun save(enabled: Boolean) {
        val p = prefs()
        if (p == null) {
            toast("Включите модуль в LSPosed и перезапустите приложение")
            return
        }
        p.edit().putBoolean("enabled", enabled).putInt("color", current()).apply()
        toast(if (enabled) "Сохранено. Перезапустите SystemUI или заблокируйте экран" else "Сброшено")
    }

    private fun restartSystemUi() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "killall com.android.systemui"))
        } catch (e: Exception) {
            toast("Нет root-доступа: ${e.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
