# TacticalVPN (Tactical Uplink) — Android VPN

HUD-панель (`app/src/main/assets/index.html`) + VPN через **Xray-core (libv2ray)**.

- **VLESS**: Reality / TLS / none; transport tcp, ws, grpc, xhttp  
- **WireGuard** (через Xray outbound, ip-only режим для стабильности)  
- Импорт **WireGuard `.conf`**, универсальные пользовательские профили  
- Версия приложения: см. `app/build.gradle` (`versionName`)

## Сборка APK

1. Установи [Android Studio](https://developer.android.com/studio).
2. Скачай ядро libv2ray:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/download-libv2ray.ps1
```

3. `File → Open` → папка `TacticalUplink`.
4. `Build → Build APK(s)`.

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Установка

1. Установи APK на телефон.
2. Нажми переключатель аплинка в HUD.
3. Разреши VPN в системе.

## Ядро

- `app/libs/libv2ray.aar` — Xray-core (~59 МБ), поддерживает **xhttp**
- Старый `libbox.aar` (sing-box) **не нужен** — в нём нет xhttp

## Профили и ключи

В HUD: **+ VLESS** / **+ WIREGUARD** — добавить канал (ссылка `vless://` или текст `.conf` WireGuard).
Удаление: **×** у канала (только если канал выключен).

Профили хранятся на устройстве (`SharedPreferences`). При первом запуске создаются три канала по умолчанию (NX62, Tactical, WireGuard).

DNS: **nx62** → **192.168.2.1** (домашний NX62). **tactical** / **wireguard** → **172.16.6.1** (удалённый роутер 95.31.176.141), не через NX62.

## Диагностика

Журнал внизу HUD + `DiagnosticLog` в logcat (`TacticalUplink/*`). Уровни: DEBUG, INFO, WARN, ERROR.
Кнопка **ОЧИСТИТЬ ЛОГ** / `Android.getDiagnosticLogs(n)` для экспорта в UI.

## Структура

```
app/src/main/java/com/tacticaluplink/vpn/
├── MainActivity.kt
├── TacticalVpnService.java      # VPN + libv2ray (Xray)
├── TacticalApplication.java
├── vless/                       # парсер и конфиг
└── core/
```
