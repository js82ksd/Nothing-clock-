package com.example.clockcolor

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier

class ClockHook : IXposedHookLoadPackage {

    private val prefs by lazy { XSharedPreferences(BuildConfigLike.PKG, BuildConfigLike.PREFS) }
    private val dumped = HashSet<String>()
    private val drawHooked = HashSet<String>()

    private fun color(): Int {
        prefs.reload()
        return if (prefs.getBoolean("enabled", false)) prefs.getInt("color", 0) else 0
    }

    private fun log(s: String) = XposedBridge.log("ClockColor: $s")

    // Логируем структуру класса (один раз на класс)
    private fun dump(v: View) {
        val name = v.javaClass.name
        if (!dumped.add(name)) return
        var k: Class<*>? = v.javaClass
        while (k != null && k != View::class.java && k != ViewGroup::class.java && k != Any::class.java) {
            val fields = k.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                .joinToString(", ") { "${it.name}:${it.type.simpleName}" }
            val methods = k.declaredMethods.joinToString(", ") { it.name }
            log("DUMP class=${k.name}")
            fields.chunked(1500).forEach { log("DUMP fields: $it") }
            methods.chunked(1500).forEach { log("DUMP methods: $it") }
            k = k.superclass
        }
        if (v is ViewGroup) {
            val kids = (0 until v.childCount).joinToString(", ") { v.getChildAt(it).javaClass.name }
            log("DUMP children(${v.childCount}): $kids")
        }
    }

    private fun recolorPaints(v: View, c: Int) {
        var k: Class<*>? = v.javaClass
        while (k != null && k != View::class.java && k != ViewGroup::class.java && k != Any::class.java) {
            for (f in k.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                runCatching {
                    f.isAccessible = true
                    when (val o = f.get(v)) {
                        is Paint -> o.color = c
                        is Array<*> -> o.forEach { if (it is Paint) it.color = c }
                        is Collection<*> -> o.forEach { if (it is Paint) it.color = c }
                    }
                }
            }
            k = k.superclass
        }
    }

    private fun recolorAll(v: View, c: Int) {
        if (v is TextView) v.setTextColor(c)
        recolorPaints(v, c)
        if (v is ViewGroup) for (i in 0 until v.childCount) recolorAll(v.getChildAt(i), c)
    }

    // Хук onDraw во всех классах цепочки, где он объявлен
    private fun hookDraw(v: View) {
        var k: Class<*>? = v.javaClass
        while (k != null && k != View::class.java && k != ViewGroup::class.java && k != Any::class.java) {
            val cls: Class<*> = k
            if (drawHooked.add(cls.name)) {
                runCatching {
                    val m = cls.getDeclaredMethod("onDraw", Canvas::class.java)
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: MethodHookParam) {
                            val c = color()
                            if (c != 0) recolorPaints(p.thisObject as View, c)
                        }
                    })
                    log("onDraw hooked in ${cls.name}")
                }
            }
            k = cls.superclass
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        log("loaded in SystemUI")

        XposedBridge.hookAllMethods(View::class.java, "dispatchAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val v = p.thisObject as View
                val name = v.javaClass.name
                if (!name.contains("clock", ignoreCase = true)) return
                val c = color()
                log("clock view $name color=$c isTextView=${v is TextView}")
                dump(v)
                if (v !is TextView) hookDraw(v)
                if (c != 0) {
                    recolorAll(v, c)
                    v.invalidate()
                }
            }
        })
    }
}
