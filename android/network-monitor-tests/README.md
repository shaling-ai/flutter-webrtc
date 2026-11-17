# Passive network monitor tests

This standalone Java test project runs the detector against the Android 10
Robolectric framework and the real `144.7559.09` WebRTC classes. It does not need
a Flutter application or a connected device.

With JDK 17, Gradle 8 and Android SDK platform 36 installed, run from the repository:

```sh
gradle -p android/network-monitor-tests test --no-daemon --max-workers=1
```

Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to the SDK. The first run downloads
the pinned test dependencies. Tests cover passive callback registration, quota
and permission rejection, broadcast fallback, initial network loss, failed
rollback/release, and the real `NetworkMonitor` factory and observer lifecycle.
They do not establish physical-device connectivity or audio acceptance.
