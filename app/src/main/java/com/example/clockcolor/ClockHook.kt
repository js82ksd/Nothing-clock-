package com.example.clockcolor

import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
import java.util.WeakHashMap

class ClockHook : IXposedHookLoadPackage {

    private val prefs by lazy { XSharedPreferences(BuildConfigLike.PKG, BuildConfigLike.PREFS) }
    private val hooked = HashSet<String>()
    private val dumped = HashSet<String>()
    private val logOnce = HashSet<String>()
    private val originalFilters = WeakHashMap<Paint, ColorFilter?>()
    private val paintCache = WeakHashMap<View, Pair<Long, List<Paint>>>()

    private var cached = 0
    private var cachedAt = 0L
    private var activePaints: List<Paint> = emptyList()
    private var activeName = ""
    private var depth = 0
    private var ownFilter: ColorFilter? = null
    private var ownFilterColor = 0
    private var settingOwn = false

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

    private fun filterFor(c: Int): ColorFilter {
        val f = ownFilter
        if (f != null && ownFilterColor == c) return f
        val n = PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN)
        ownFilter = n
        ownFilterColor = c
        return n
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

    private fun paintsCached(v: View): List<Paint> {
        val now = System.currentTimeMillis()
        val e = paintCache[v]
        if (e != null && now - e.first < 2000) return e.second
        val l = paintsOf(v)
        paintCache[v] = Pair(now, l)
        return l
    }

    // c != 0: ставим цветовой фильтр; c == 0: возвращаем оригинал
    private fun applyFilters(ps: List<Paint>, c: Int) {
        settingOwn = true
        try {
            for (p in ps) {
                if (c != 0) {
                    if (!originalFilters.containsKey(p)) originalFilters[p] = p.getColorFilter()
                    p.setColorFilter(filterFor(c))
                } else if (originalFilters.containsKey(p)) {
                    p.setColorFilter(originalFilters.remove(p))
                }
            }
        } finally {
            settingOwn = false
        }
    }

    private fun applyPaints(v: View, c: Int) {
        val ps = paintsCached(v)
        if (c != 0) ps.forEach { it.color = c }
        applyFilters(ps, c)
    }

    private fun recolorAll(v: View, c: Int) {
        if (v is TextView) v.setTextColor(c)
        applyPaints(v, c)
        if (v is ViewGroup) for (i in 0 until v.childCount) recolorAll(v.getChildAt(i), c)
    }

    // Структура класса в лог, один раз на класс
    private fun dump(v: View) {
        val name = v.javaClass.name
        if (!dumped.add(name)) return
        var k: Class<*>? = v.javaClass
        while (k != null && k != View::class.java && k != ViewGroup::class.java && k != Any::class.java) {
            val cls: Class<*> = k
            if (!cls.name.startsWith("android.") && !cls.name.endsWith(".NTClockView")) {
                val fields = cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                    .joinToString(", ") { "${it.name}:${it.type.simpleName}" }
                log("DUMP class=${cls.name}")
                fields.chunked(1500).forEach { log("DUMP fields: $it") }
            }
            k = cls.superclass
        }
        if (v is ViewGroup) {
            val kids = (0 until v.childCount).joinToString(", ") { v.getChildAt(it).javaClass.name }
            log("DUMP children(${v.childCount}) of $name: $kids")
        }
    }

    // Вокруг drawClock/onDraw: красим Paint и помечаем их как "активные"
    private val drawHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(p: MethodHookParam) {
            val v = p.thisObject as? View ?: return
            val c = color()
            if (c == 0) {
                if (originalFilters.isNotEmpty()) applyFilters(paintsCached(v), 0)
                return
            }
            applyPaints(v, c)
            if (depth == 0) {
                activePaints = paintsCached(v)
                activeName = v.javaClass.name
                if (logOnce.add("D:$activeName")) log("draw hook active in $activeName, paints=${activePaints.size}")
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

    // После refreshColor/onThemeChanged часы выставляют свой цвет, красим обратно
    private val afterColorHook = object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            val v = p.thisObject as? View ?: return
            paintCache.remove(v)
            val c = color()
            if (c != 0) {
                recolorAll(v, c)
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
                    when (m.name) {
                        "onDraw", "drawClock", "dispatchDraw" -> runCatching {
                            XposedBridge.hookMethod(m, drawHook)
                            log("hooked ${cls.simpleName}.${m.name}")
                        }
                        "refreshColor", "onThemeChanged", "refreshUI", "refreshInfo" -> runCatching {
                            XposedBridge.hookMethod(m, afterColorHook)
                            log("hooked ${cls.simpleName}.${m.name}")
                        }
                        else -> Unit
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

        // Paint.setColor: пока идёт отрисовка часов, подменяем цвет (альфа сохраняется)
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
                        if (logOnce.add("C:$activeName")) log("intercepted Paint.setColor in $activeName")
                    }
                }
            )
        }

        // Paint.setColorFilter: если часы сами ставят фильтр, подменяем на наш
        runCatching {
            XposedHelpers.findAndHookMethod(
                Paint::class.java, "setColorFilter", ColorFilter::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (settingOwn) return
                        val list = activePaints
                        if (list.isEmpty()) return
                        if (list.none { it === p.thisObject }) return
                        val c = cached
                        if (c == 0) return
                        p.args[0] = filterFor(c)
                        if (logOnce.add("F:$activeName")) log("intercepted Paint.setColorFilter in $activeName")
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
