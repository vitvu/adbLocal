# ADB Local

Opens your phone's ADB on a fixed port, so a PC on the same network can connect without a cable. No root needed.

```sh
adb connect <phone-ip>:5555
```

The phone shows **Allow USB debugging?** the first time. Tap **Allow**, then use adb or `scrcpy` as usual.

## Setup

**Android 11+** (once):
1. Turn on **Wireless debugging** in Developer options.
2. Tap **Pair** in the app and choose *Pair device with pairing code*. Type the code into the app's notification.

After that the app reopens the port by itself on Wi-Fi and after a reboot.

**Android 10 and older:** these versions have no Wireless debugging, so the port can only be opened from a PC:
```sh
adb tcpip 5555
```
Repeat this after every reboot.

## Build
```sh
./gradlew assembleRelease
```
