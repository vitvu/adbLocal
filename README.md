# ADB Local

Android app that shares the phone's own ADB daemon on a **fixed LAN port**, so a PC/laptop on the same network can run `adb connect <ip>:<port>` and use it exactly like a USB cable — shell, install, logcat, and smooth screen mirroring with [scrcpy](https://github.com/Genymobile/scrcpy).

## How it works (no root, no PC)

Android 11+ only. Once:

1. Enable Developer options and **Wireless debugging**.
2. Tap **Pair** in the app, open *Pair device with pairing code*, type the 6-digit code into the ADB Local notification.

The app pairs with the phone's own adbd (like a PC would), self-grants `WRITE_SECURE_SETTINGS`, then runs `adb tcpip <port>` on itself. adbd listens on the fixed port; when a PC runs `adb connect ip:port` the phone shows the normal **"Allow USB debugging?"** prompt.

After that it is automatic: on Wi-Fi connect / after reboot the app turns Wireless debugging on and reopens the port.

Android 10 and older have no Wireless debugging, so without root a one-time `adb tcpip` from a PC is unavoidable there.

## Connect

```sh
adb connect 192.168.1.20:5555
scrcpy -s 192.168.1.20:5555 --video-bit-rate 8M --max-fps 60
```

Built on [libadb-android](https://github.com/MuntashirAkon/libadb-android).

## Security

Anyone on the LAN can reach the port, but adbd still requires the RSA key prompt (or TLS pairing). Only use it on networks you trust, and stop sharing when you are done.

## Build

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```
