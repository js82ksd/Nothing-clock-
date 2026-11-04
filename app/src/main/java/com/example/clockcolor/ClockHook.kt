package com.example.clockcolor

import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier

class ClockHook : IXposedHookLoadPackage {

    private val prefs by lazy { XSharedPreferences(BuildConfigLike.PKG, BuildConfigLike.PREFS) }
    private val hooked = HashSet<String>()
    private var cached = 0
    private var cachedAt = 0L
    private var activePaint: Paint? = null
    private var depth = 0
    private var logged = false

    private fun log(s: String) = XposedBridge.log("ClockColor: $s")

    // Цвет из настроек, перечитываем не чаще раза в секунду
    private fun color(): Int {
        val now = System.currentTimeMillis()
        if (now - cachedAt > 1000) {
            cachedAt = now
            prefs.reload()
            cached = if (prefs.getBoolean("enabled", false)) prefs.getInt("color", 0) else 0
        }
        return cached
    }

    private fun paintOf(v: View): Paint? {
        var k: Class<*>? = v.javaClass
        while (k != null && k != View::class.java && k != Any::class.java) {
            val cls: Class<*> = k
            val f = runCatching { cls.getDeclaredField("paint") }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                val o = f.get(v)
                if (o is Paint) return o
            }
            k = cls.superclass
        }
        return null
    }

    private fun recolorPaints(v: View, c: Int) {
        var k: Class<*>? = v.javaClass
        while (k != null && k != View::class.java && k != ViewGroup::class.java && k != Any::class.java) {
            val cls: Class<*> = k
            for (f in cls.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                runCatching {
                    f.isAccessible = true
                    val o = f.get(v)
                    if (o is Paint) o.color = c
                }
            }
            k = cls.superclass
        }
    }

    private fun recolorAll(v: View, c: Int) {
        if (v is TextView) v.setTextColor(c)
        recolorPaints(v, c)
        if (v is ViewGroup) for (i in 0 until v.childCount) recolorAll(v.getChildAt(i), c)
    }

    // Вокруг drawClock/onDraw: помечаем Paint часов как "активный"
    private val drawHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            val v = p.thisObject as? View ?: return
            val c = color()
            if (c == 0) return
            recolorPaints(v, c)
            if (depth == 0) activePaint = paintOf(v)
            depth++
            p.setObjectExtra("on", true)
        }

        override fun afterHookedMethod(p: MethodHookParam) {
            if (p.getObjectExtra("on") == true) {
                depth--
                if (depth <= 0) {
                    depth = 0
                    activePaint = null
                }
            }
        }
    }

    // После refreshColor часы сами выставляют цвет, перекрашиваем обратно
    private val afterColorHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            val v = p.thisObject as? View ?: return
            val c = color()
            if (c != 0) {
                recolorPaints(v, c)
                v.invalidate()
            }
        }
    }

    private val textHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            val c = color()
            if (c != 0) (p.thisObject as? TextView)?.setTextColor(c)
        }
    }

    private fun hookClockView(v: View) {
        var k: Class<*>? = v.javaClass
        while (k != null && k != View::class.java && k != ViewGroup::class.java && k != Any::class.java) {
            val cls: Class<*> = k
            if (hooked.add(cls.name)) {
                for (m in cls.declaredMethods) {
                    if (Modifier.isAbstract(m.modifiers)) continue
                    if (m.name == "onDraw" || m.name == "drawClock") {
                        runCatching {
                            XposedBridge.hookMethod(m, drawHook)
                            log("hooked ${cls.simpleName}.${m.name}")
                        }
                    } else if (m.name == "refreshColor") {
                        runCatching {
                            XposedBridge.hookMethod(m, afterColorHook)
                            log("hooked ${cls.simpleName}.${m.name}")
                        }
                    }
                }
            }
            k = cls.superclass
        }
    }

    private fun hookTextClock(v: TextView) {
        val cls = v.javaClass
        if (!hooked.add("T:" + cls.name)) return
        runCatching { XposedBridge.hookAllMethods(cls, "onDarkChanged", textHook) }
        runCatching { XposedBridge.hookAllMethods(cls, "onColorsChanged", textHook) }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        log("loaded in SystemUI")

        // Пока активен drawClock, подменяем цвет у Paint часов (альфа сохраняется)
        runCatching {
            XposedHelpers.findAndHookMethod(
                Paint::class.java, "setColor", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val ap = activePaint ?: return
                        if (p.thisObject !== ap) return
                        val c = cached
                        if (c == 0) return
                        val orig = p.args[0] as Int
                        p.args[0] = (orig and 0xFF000000.toInt()) or (c and 0x00FFFFFF)
                        if (!logged) {
                            logged = true
                            log("intercepted Paint.setColor in clock draw")
                        }
                    }
                }
            )
        }

        XposedBridge.hookAllMethods(View::class.java, "dispatchAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val v = p.thisObject as View
                val name = v.javaClass.name
                if (!name.contains("clock", ignoreCase = true)) return
                val c = color()
                log("clock view $name color=$c")
                if (v is TextView) hookTextClock(v) else hookClockView(v)
                if (c != 0) {
                    recolorAll(v, c)
                    v.invalidate()
                }
            }
        })
    }
}
