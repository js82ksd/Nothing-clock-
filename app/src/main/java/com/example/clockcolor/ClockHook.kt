package com.example.clockcolor

import android.widget.TextClock
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ClockHook : IXposedHookLoadPackage {

    // Кандидаты классов часов. ПРОВЕРЬТЕ В JADX на своей прошивке и добавьте нужный.
    private val candidates = listOf(
        "com.android.systemui.shared.clocks.AnimatableClockView",
        "com.android.systemui.animation.AnimatableClockView",
        "com.android.systemui.keyguard.ui.view.layout.sections.AnimatableClockView"
    )

    private val prefs by lazy { XSharedPreferences(BuildConfigLike.PKG, BuildConfigLike.PREFS) }

    private fun color(): Int {
        prefs.reload()
        return if (prefs.getBoolean("enabled", false)) prefs.getInt("color", 0) else 0
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return

        val paintHook = object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val c = color()
                if (c != 0) (p.thisObject as? TextView)?.setTextColor(c)
            }
        }
        // setColors(dozingColor, lockScreenColor) подменяем аргументы до вызова
        val argsHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val c = color()
                if (c != 0 && p.args.size >= 2 && p.args[0] is Int) {
                    p.args[0] = c
                    p.args[1] = c
                }
            }
        }

        var found = false
        for (name in candidates) {
            val cls = XposedHelpers.findClassIfExists(name, lpparam.classLoader) ?: continue
            found = true
            XposedBridge.log("ClockColor: хук на $name")
            runCatching { XposedHelpers.findAndHookMethod(cls, "onAttachedToWindow", paintHook) }
            runCatching { XposedBridge.hookAllMethods(cls, "setColors", argsHook) }
            runCatching { XposedBridge.hookAllMethods(cls, "refreshTime", paintHook) }
        }

        if (!found) {
            XposedBridge.log("ClockColor: известные классы не найдены, запасной хук на TextClock")
            runCatching {
                XposedHelpers.findAndHookMethod(TextClock::class.java, "onAttachedToWindow", paintHook)
            }
        }
    }
}

object BuildConfigLike {
    const val PKG = "com.example.clockcolor"
    const val PREFS = "cfg"
}
