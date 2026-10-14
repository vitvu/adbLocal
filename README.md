# ADB Local

Android app that shares the phone's own ADB daemon on a **fixed LAN port**, so a PC/laptop on the same network can run `adb connect <ip>:<port>` and use it exactly like a USB cable — shell, install, logcat, and smooth screen mirroring with [scrcpy](https://github.com/Genymobile/scrcpy).

## Modes

| Mode | Requirements | Networks | How it works |
|---|---|---|---|
| **Root** | root (Magisk/KernelSU) | Wi-Fi, Ethernet, USB tether, hotspot | `setprop service.adb.tcp.port <port>` + restart adbd; adbd listens on the port directly |
| **Wireless** | Android 11+, `WRITE_SECURE_SETTINGS` | Wi-Fi | Turns on Wireless debugging, finds its random port (sysprop / mDNS) and relays it to the fixed port |
| **tcpip** | `adb tcpip 5555` once over USB | any | Relays adbd's 5555 to the fixed port |
| **Auto** | — | — | Root → Wireless → tcpip |

## One-time setup (no root)

```sh
adb shell pm grant com.vvtool.adblocal android.permission.WRITE_SECURE_SETTINGS
```

With this permission the app can switch USB / Wireless debugging on by itself every time Wi-Fi connects.
For Wireless mode, pair the PC once: Settings › Developer options › Wireless debugging › *Pair device with pairing code* → `adb pair <ip>:<pair-port> <code>`.

## Connect

```sh
adb connect 192.168.1.20:5555
scrcpy -s 192.168.1.20:5555 --video-bit-rate 8M --max-fps 60
```

## Features

- Fixed, user-defined port; foreground service with Quick Settings tile and boot start
- Auto-enables ADB when Wi-Fi connects; restarts sharing when the network or adbd port changes
- Low-latency relay (TCP_NODELAY, 512 KB socket buffers), Wi-Fi low-latency lock and wake lock
- Live status: IPs per interface, adbd port, connected PCs, traffic
- Permission panel: root, WRITE_SECURE_SETTINGS, USB/Wireless debugging toggles, notifications, battery optimization, local network (Android 17)

## Security

Anyone on the LAN can reach the port, but adbd still requires the RSA key prompt (or TLS pairing). Only use it on networks you trust, and stop sharing when you are done.

## Build

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```
