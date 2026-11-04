# ClockColor (LSPosed)

1. Откройте папку в Android Studio (Gradle sync, wrapper создастся сам), Build > Build APK.
2. Установите APK, в LSPosed включите модуль, область: System UI.
3. Откройте приложение, выберите цвет, "Применить", затем "Перезапустить SystemUI".
4. Логи: adb logcat | grep -i ClockColor  (или вкладка Logs в LSPosed).

Если в логе "известные классы не найдены": вытащите SystemUI.apk
(adb shell pm path com.android.systemui; adb pull ...), откройте в jadx,
найдите класс часов локскрина и добавьте его имя в `candidates` в ClockHook.kt.
