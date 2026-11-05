# Nothing clock+

Xposed-модуль (LSPosed / Vector) для смены цвета часов на экране блокировки **Nothing OS 4.1**.
Цвет выбирается в приложении модуля и применяется к стилям часов Nothing: точечным, сегментным, графическим и другим.

> **Статус: beta.** Модуль не связан с компанией Nothing Technology Limited. Используйте на свой риск.

<!-- Сюда можно добавить скриншоты:
![Приложение](screenshots/app.png)
![Экран блокировки](screenshots/lockscreen.png)
-->

## Возможности

- Выбор любого цвета ползунками R, G, B с предпросмотром.
- Работает с разными стилями часов Nothing OS (у каждого стиля свой способ отрисовки, модуль обрабатывает их по-разному).
- Красит часы в статус-баре.
- Возврат к стандартному цвету одной кнопкой («Сбросить»).
- Кнопка перезапуска SystemUI (нужен root).

## Требования

- Телефон Nothing с **Nothing OS 4.1** (на других версиях не проверялось, названия классов могут отличаться).
- Root (Magisk, KernelSU или аналог).
- LSPosed или Vector (форк LSPosed).

## Установка

1. Скачайте APK бета-версии на странице [Releases](../../releases) (файл `app-debug.apk` в последнем релизе с пометкой *Pre-release*).
2. Установите APK.
3. **Выдайте root-права вручную** в вашем менеджере root (Magisk, KernelSU и т. д.): откройте список приложений с доступом суперпользователя, найдите **Nothing clock+** и разрешите доступ. Запрос может не появиться сам, поэтому проверьте это вручную. Root нужен для кнопки перезапуска SystemUI.
4. Откройте LSPosed / Vector → **Модули** → включите **Nothing clock+** и отметьте область **System UI**.

## Использование

1. Откройте приложение **Nothing clock+**.
2. Выберите цвет ползунками.
3. Нажмите **Применить**.
4. Нажмите **Перезапустить SystemUI** (нужен root) или заблокируйте и снова включите экран.

Цвет читается из настроек модуля не чаще раза в секунду. Новый цвет вступает в силу, когда часы перерисовываются или появляются на экране.

## Как это работает

Часы на экране блокировки в Nothing OS рисует SystemUI собственными view (`com.nothing.systemui.shared.clocks.view.*`), а не обычным `TextView`, поэтому `setTextColor` не помогает.

Модуль хукает SystemUI и:

1. Находит view часов и перехватывает `drawClock` / `onDraw`.
2. На время отрисовки подменяет цвет у `Paint` часов (`Paint.setColor`).
3. Для стилей, где цифры нарисованы готовыми картинками (`Bitmap`), ставит цветовой фильтр `PorterDuffColorFilter` (режим SRC_IN).
4. После `refreshColor`, `onThemeChanged` и подобных методов перекрашивает вложенные текстовые view.

Настройки передаются из приложения в SystemUI через `XSharedPreferences` (поддерживается LSPosed / Vector).

## Сборка

**Android Studio:** откройте папку проекта, дождитесь синхронизации Gradle и выберите Build → Build APK(s).
APK будет в `app/build/outputs/apk/debug/app-debug.apk`.

**GitHub Actions:** при каждом пуше workflow `.github/workflows/build.yml` собирает APK и выкладывает его артефактом `ClockColor-apk` (вкладка [Actions](../../actions)).

## Решение проблем

Смотрите логи (на ПК с adb):

```
adb logcat | findstr ClockColor
```

Нужные строки:

| Строка в логе | Что значит |
|---|---|
| `loaded in SystemUI` | модуль загрузился в SystemUI |
| `clock view <класс>` | найден view часов |
| `draw hook active in <класс>, paints=N` | хук отрисовки работает, найдено N объектов `Paint` |
| `intercepted Paint.setColor ...` / `setColorFilter ...` | цвет успешно подменяется |

Если цвет не меняется:

- Проверьте, что модуль включён в LSPosed / Vector и отмечена область **System UI**.
- Перезапустите SystemUI или перезагрузите телефон после установки новой версии.
- Если кнопка «Перезапустить SystemUI» ничего не делает, проверьте, что приложению выдан root вручную (см. «Установка», шаг 3).
- Если для какого-то стиля нет строк `draw hook active`, откройте issue и приложите лог со строками `ClockColor`.

## Ограничения

- Названия внутренних классов Nothing OS могут меняться при обновлениях системы, и тогда часть стилей придётся доработать.
- Цвет не применяется мгновенно, пока часы не перерисуются.
- Некоторые стили могут красить не все элементы (например, дату или погоду).

## Лицензия

 `LICENSE`.

---

## English summary

**Nothing clock+** (beta) is an Xposed module (LSPosed / Vector) that changes the lock screen clock color on **Nothing OS 4.1**.

Nothing OS draws its lock screen clocks with custom views instead of a plain `TextView`, so the module hooks SystemUI, overrides the clock `Paint` color during `drawClock`, and applies a `PorterDuffColorFilter` for faces that render digits from bitmaps.

**Requirements:** Nothing OS 4.1, root, LSPosed or Vector.
**Install:** download the beta APK from [Releases](../../releases), install it, **grant root access manually in your root manager** (Magisk / KernelSU), enable the module in LSPosed / Vector with **System UI** scope, reboot.
**Use:** open the app, pick a color, tap *Apply*, then restart SystemUI.

Not affiliated with Nothing Technology Limited. Use at your own risk.
