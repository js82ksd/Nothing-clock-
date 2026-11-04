package com.example.clockcolor

import android.view.View
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ClockHook : IXposedHookLoadPackage {

    private val prefs by lazy { XSharedPreferences(BuildConfigLike.PKG, BuildConfigLike.PREFS) }
    private val seen = HashSet<String>()

    private fun color(): Int {
        prefs.reload()
        val ok = prefs.file.canRead()
        val en = prefs.getBoolean("enabled", false)
        val c = prefs.getInt("color", 0)
        XposedBridge.log("ClockColor: prefs readable=$ok enabled=$en color=$c")
        return if (en) c else 0
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui") return
        XposedBridge.log("ClockColor: загружен в SystemUI")

        XposedBridge.hookAllMethods(View::class.java, "dispatchAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val v = p.thisObject as View
                val name = v.javaClass.name
                if (!name.contains("clock", ignoreCase = true)) return

                if (seen.add(name)) {
                    XposedBridge.log("ClockColor: найден view $name, super=${v.javaClass.superclass?.name}, isTextView=${v is TextView}")
                }
                val c = color()
                if (c != 0 && v is TextView) v.setTextColor(c)
            }
        })
    }
}
