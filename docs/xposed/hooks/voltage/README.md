# Voltage

## Призначення

Voltage коригує програмне значення напруги акумулятора, яке Android отримує від MCU магнітоли.

- Змінюється лише значення, яке зберігає Android MCU service.
- Фізична напруга, вимірювання MCU та робота заряджання не змінюються.
- Модуль за замовчуванням вимкнений.

## Точка інтеграції

| Параметр | Значення |
|---|---|
| Процес / package | `android` (`system_server`) |
| Сервіс | `android.carsource.McuService` |
| Конструктор | `McuService(Context)` |
| Метод | `onBattery(McuPacket)` |
| Descriptor | `(Landroid/carsource/McuService$McuPacket;)V` |
| Тип hook | LSPosed after-hook |
| Поле результату | `McuService.mBatteryVoltage` |
| LSPosed scope | `android` |

Hook обробляє пакет **після виконання оригінального** `onBattery()`. Тому штатна перевірка низької напруги та логіка MCU працюють з реальними даними до програмної корекції.

## Отримання напруги з MCU

Обробляються лише battery-пакети:

1. `subCmd() == 3`.
2. `paramLen() >= 1`.
3. Значення береться з `param(0)`.
4. Byte інтерпретується як unsigned і переводиться з десятих вольта:

```kotlin
val rawVoltage = (parameter.toInt() and 0xFF) / 10.0f
```

Приклад: `0x78` → `120` → **12.0 V**.

Некоректні, відсутні або неповні дані не повинні змінювати значення сервісу.

## Алгоритм корекції

Параметри:

- Offset: від **−2.0 до +2.0 V**.
- Крок налаштування: **0.1 V**.
- Початковий offset: **0.0 V**.

```text
enabled:  effectiveVoltage = rawBatteryVoltage + voltageOffset
disabled: effectiveVoltage = rawBatteryVoltage
```

Сире значення `rawBatteryVoltage` зберігається окремо. Offset завжди застосовується до сирого значення — повторного додавання до вже скоригованої напруги немає.

Після зміни конфігурації поле `mBatteryVoltage` перераховується з останнього сирого значення. При вимкненні модуля відновлюється остання реальна напруга.

## Конфігурація між процесами

App передає повну конфігурацію в процес `android` через package-scoped broadcast:

| Поле | Значення |
|---|---|
| Action | `com.mt8163tools.action.VOLTAGE_CONFIG_CHANGED` |
| Target package | `android` |
| Extra | `module_enabled` |
| Extra | `voltage_offset` |

Публікація конфігурації виконується:
- під час запуску App;
- після зміни стану Voltage;
- після зміни offset або Reset.

Receiver реєструється через Context сервісу після конструктора `McuService`.

Для початкового читання hook-конфігурації використовується окреме сховище `voltage_hook_config`. На Android 9 застосовується `MODE_WORLD_READABLE` із fallback на `MODE_PRIVATE`, якщо доступ заборонений. UI-значення offset зберігається як `Float` у `voltage_settings/voltage_offset`. Загальний стан увімкнення модуля належить спільному механізму стану модулів.

## Синхронізація та обмеження

- Оновлення MCU-пакета й конфігурації синхронізовані одним lock.
- Offset приймається лише якщо він скінченний і перебуває в межах `−2.0..+2.0 V`.
- Hook використовує in-memory конфігурацію; disk I/O не виконується в обробці MCU-пакетів.
- Оригінальний `onBattery()` не підміняється і не обходиться.
- Не використовуються polling, timers, worker threads, executors або періодичні callback.

## Важливий принцип

**Коригується лише Android-представлення напруги.** MCU продовжує передавати реальне значення, а штатна логіка захисту від низької напруги виконується до застосування offset.
