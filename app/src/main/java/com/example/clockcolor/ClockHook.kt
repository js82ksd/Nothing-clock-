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
    private val dumped = HashSet<String>()
    private val interceptLogged = HashSet<String>()
    private var cached = 0
    private var cachedAt = 0L
    private var activePaints: List<Paint> = emptyList()
    private var activeName = ""
    private var depth = 0

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

    // Часы это view с "clock" в имени класса или внутри такого view
    private fun isClockish(v: View): Boolean {
        var cur: Any? = v
        var i = 0
        while (cur is View && i < 8) {
            if (cur.javaClass.name.contains("clock", ignoreCase = true)) return true
            cur = cur.parent
            i++
        }
        return false
    }

    // Все Paint из полей view (включая массивы и списки)
    private fun paintsOf(v: View): List<Paint> {
        val out = ArrayList<Paint>()
        var k: Class<*>? = v.javaClass
        while (k != null && k != View::class.java && k != ViewGroup::class.java && k != Any::class.java) {
            val cls: Class<*> = k
            for (f in cls.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                runCatching {
                    f.isAccessible = true
                    when (val o = f.get(v)) {
                        is Paint -> out.add(o)
                        is Array<*> -> o.forEach { if (it is Paint) out.add(it) }
                        is Collection<*> -> o.forEach { if (it is Paint) out.add(it) }
                        else -> Unit
                    }
                }
            }
            k = cls.superclass
        }
        return out
    }

    private fun recolorPaints(v: View, c: Int) {
        paintsOf(v).forEach { it.color = c }
    }

    private fun recolorAll(v: View, c: Int) {
        if (v is TextView) v.setTextColor(c)
        recolorPaints(v, c)
        if (v is ViewGroup) for (i in 0 until v.childCount) recolorAll(v.getChildAt(i), c)
    }

    // Структура класса в лог, один раз на класс
    private fun dump(v: View) {
        val name = v.javaClass.name
        if (!dumped.add(name)) return
        var k: Class<*>? = v.javaClass
        while (k != null && k != View::class.java && k != ViewGroup::class.java && k != Any::class.java) {
            val cls: Class<*> = k
            if (!cls.name.startsWith("android.")) {
                val fields = cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                    .joinToString(", ") { "${it.name}:${it.type.simpleName}" }
                val methods = cls.declaredMethods.joinToString(", ") { it.name }
                log("DUMP class=${cls.name}")
                fields.chunked(1500).forEach { log("DUMP fields: $it") }
                methods.chunked(1500).forEach { log("DUMP methods: $it") }
            }
            k = cls.superclass
        }
        if (v is ViewGroup) {
            val kids = (0 until v.childCount).joinToString(", ") { v.getChildAt(it).javaClass.name }
            log("DUMP children(${v.childCount}) of $name: $kids")
        }
    }

    // Вокруг drawClock/onDraw: помечаем Paint часов как "активные"
    private val drawHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            val v = p.thisObject as? View ?: return
            val c = color()
            if (c == 0) return
            recolorPaints(v, c)
            if (depth == 0) {
                activePaints = paintsOf(v)
                activeName = v.javaClass.name
            }
            depth++
            p.setObjectExtra("on", true)
        }

        override fun afterHookedMethod(p: MethodHookParam) {
            if (p.getObjectExtra("on") == true) {
                depth--
                if (depth <= 0) {
                    depth = 0
                    activePaints = emptyList()
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
            if (!cls.name.startsWith("android.") && hooked.add(cls.name)) {
                for (m in cls.declaredMethods) {
                    if (Modifier.isAbstract(m.modifiers)) continue
                    if (m.name == "onDraw" || m.name == "drawClock" || m.name == "dispatchDraw") {
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
                        val list = activePaints
                        if (list.isEmpty()) return
                        if (list.none { it === p.thisObject }) return
                        val c = cached
                        if (c == 0) return
                        val orig = p.args[0] as Int
                        p.args[0] = (orig and 0xFF000000.toInt()) or (c and 0x00FFFFFF)
                        if (interceptLogged.add(activeName)) log("intercepted Paint.setColor in $activeName")
                    }
                }
            )
        }

        XposedBridge.hookAllMethods(View::class.java, "dispatchAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val v = p.thisObject as View
                if (!isClockish(v)) return
                val name = v.javaClass.name
                if (name.startsWith("android.")) return
                val c = color()
                log("clock view $name color=$c isTextView=${v is TextView}")
                dump(v)
                if (v is TextView) hookTextClock(v) else hookClockView(v)
                if (c != 0) {
                    recolorAll(v, c)
                    v.invalidate()
                }
            }
        })
    }
}
