<div align="center">

# ADB Local

**Wireless ADB on a fixed port. No root, no cable.**

![Android](https://img.shields.io/badge/Android-7.0%2B-0B57D0?logo=android&logoColor=white)
![No root](https://img.shields.io/badge/root-not%20required-188038)
[![Donate](https://img.shields.io/badge/Donate-PayPal-00457C?logo=paypal&logoColor=white)](https://paypal.me/vudinhviet)

</div>

ADB Local opens your phone's ADB daemon on a port you choose. Any PC on the same network can then connect to it as if the phone were plugged in by USB, for shell, app installs, logcat or screen mirroring with [scrcpy](https://github.com/Genymobile/scrcpy).

```sh
adb connect <phone-ip>:5555
```

The first time a PC connects, the phone shows the standard **Allow USB debugging?** prompt.

## Features

- **Fixed port:** the address stays the same every time.
- **Automatic:** reopens the port when Wi-Fi connects and after a reboot.
- **Connected PCs:** lists every PC currently connected.
- **Quick Settings tile** and a persistent notification with a Stop action.
- **Small:** about 3 MB, no ads, no tracking.

## Setup

### Android 11 and newer (one time)

1. In Developer options, turn on **Wireless debugging**.
2. Tap **Pair** in ADB Local, choose **Pair device with pairing code**, and type the code into the ADB Local notification.

After that, everything is automatic.

### Android 10 and older

These versions have no Wireless debugging, so the port has to be opened from a PC over USB:

```sh
adb tcpip 5555
```

Repeat this after every reboot. ADB Local detects the open port and shows which PCs are connected.

## Security

Every PC must be approved on the phone before it gets access. Only share ADB on networks you trust, and tap **Stop** when you are done. Note that the port itself stays open until the next reboot.

## Build

```sh
./gradlew assembleRelease
```

Pairing is powered by [libadb-android](https://github.com/MuntashirAkon/libadb-android).

## Support

If ADB Local saves you time, you can support its development:

[![Donate with PayPal](https://img.shields.io/badge/Donate-PayPal-00457C?logo=paypal&logoColor=white&style=for-the-badge)](https://paypal.me/vudinhviet)
