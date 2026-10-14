<div align="center">

# ADB Local

**Wireless ADB for older Android phones without Wireless debugging. Fixed port, no root.**

![Android](https://img.shields.io/badge/Android-7.0%2B-0B57D0?logo=android&logoColor=white)
![Built for](https://img.shields.io/badge/built%20for-Android%207–10-F9AB00)
![No root](https://img.shields.io/badge/root-not%20required-188038)
[![Donate](https://img.shields.io/badge/Donate-PayPal-00457C?logo=paypal&logoColor=white)](https://paypal.me/vudinhviet)

</div>

Android 11 added *Wireless debugging*, but most budget and older phones (Android 7–10) never got it. On those devices ADB over Wi-Fi resets after every reboot, the port is easy to lose, and there is no way to see who is connected.

**ADB Local fixes this for older devices.** It keeps ADB reachable on a port you choose, watches the connection, and shows which PCs are connected. Use it for shell access, installing apps, logcat, or smooth screen mirroring with [scrcpy](https://github.com/Genymobile/scrcpy). It works on Android 11+ as well, where it also handles pairing and re-opening the port for you.

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

### Android 7 – 10 (no Wireless debugging)

1. Turn on **USB debugging** in Developer options.
2. Plug the phone into a PC once and run:
   ```sh
   adb tcpip 5555
   ```
3. Unplug the cable and tap **Start** in ADB Local.

Android only lets a PC (or root) open ADB over the network on these versions. Repeat step 2 after every reboot.

### Android 11 and newer

1. Turn on **Wireless debugging** in Developer options.
2. Tap **Pair** in ADB Local, choose **Pair device with pairing code**, and type the code into the ADB Local notification.

You only do this once. After that, the port reopens automatically on Wi-Fi and after a reboot, with no PC needed.

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
