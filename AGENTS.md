# GboardGloss Agent Notes

## Workspace and toolchain

- Repository root: `/home/nano/code/lsp/gboard-gloss`.
- Use JDK 17 (`JAVA_HOME=/usr/local/jdk/jdk-17.0.18` in the current environment).
- Android SDK: `.android-sdk/` in the repository root.
  - `platforms/android-34`, `build-tools/34.0.0`, and `platform-tools` are installed.
  - `local.properties` contains the absolute `sdk.dir` for Android Studio/AGP.
- Gradle user home and dependency cache: `.gradle-home/`.
  - `./gradlew` defaults `GRADLE_USER_HOME` to this directory when it is unset.
  - It also defaults `ANDROID_HOME` and `ANDROID_SDK_ROOT` to `.android-sdk/`.
  - An explicitly set environment variable takes precedence.
- Keep both toolchain directories inside the repository. Do not move them back to `/tmp` or
  replace them with machine-global caches. They, `local.properties`, and build outputs are
  git-ignored and must not be committed.
- `gradle.properties` sets a 4 GB Gradle JVM heap because packaging the dictionary assets
  otherwise runs out of memory.

## Build and verification

Run from the repository root:

```bash
./gradlew assembleRelease --no-daemon
```

The APK is written to:

```text
app/build/outputs/apk/release/app-release.apk
```

After a build, verify the artifact is a non-empty ZIP/APK and, when relevant, run:

```bash
.android-sdk/build-tools/34.0.0/apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Gradle needs loopback networking for its daemon. If it fails in the workspace sandbox with
`Operation not permitted`, rerun the build outside the sandbox. Dependencies are already cached
in `.gradle-home`; if Maven Central times out during a clean dependency download, use an
accessible mirror for that command rather than changing the project repositories permanently.

## Install and reload Gboard

Install the current APK, then force-stop Gboard so its process reloads the LSPosed module on the
next launch. When using the Windows ADB binary from WSL, convert the APK path first:

```bash
ADB="/mnt/d/shuaji/刷机/刷机/adb-fastboot/adb.exe"
APK_WIN=$(wslpath -w "$PWD/app/build/outputs/apk/release/app-release.apk")
"$ADB" -s "$SERIAL" install -r "$APK_WIN"
```

If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop and ask the user before uninstalling
the existing package because uninstalling clears its settings and imported data.

After every successful installation, run:

```bash
"$ADB" -s "$SERIAL" shell am force-stop com.google.android.inputmethod.latin
"$ADB" -s "$SERIAL" shell "pidof com.google.android.inputmethod.latin || echo Gboard_not_running"
```

The second command must print `Gboard_not_running`, confirming that the old Gboard process has
exited. Gboard may restart automatically the next time the keyboard is opened.

## Android device and ADB

There are two ADB binaries:

- Project SDK ADB: `.android-sdk/platform-tools/adb` (ADB 37.0.1).
- User-specified Windows ADB: `/mnt/d/shuaji/刷机/刷机/adb-fastboot/adb.exe`
  (ADB 28 / 1.0.40). Quote the path because it contains Chinese characters and use it when
  the user requests this specific tool.

ADB and Gradle daemons need loopback access, which the command sandbox blocks. Request
unsandboxed execution when either reports `Operation not permitted` while opening a socket.
Linux and Windows ADB share port 5037 and may kill each other's server when their versions
differ. Choose one ADB binary for a debugging session and do not alternate between them.

Check devices with:

```bash
"/mnt/d/shuaji/刷机/刷机/adb-fastboot/adb.exe" devices -l
```

Treat `device` as usable, `unauthorized` as waiting for the user to approve USB debugging on
the phone, and `offline` as a connection that should be reconnected. Device serials and IP
addresses are runtime values; read them from `adb devices -l` instead of hard-coding the
currently connected device.

## Wireless debugging

When wireless ADB is requested:

1. Connect USB, run `adb devices -l`, and wait until the target is `device`.
2. Read the serial from `adb devices -l`.
3. Read the phone's Wi-Fi IPv4 address, for example:

   ```bash
   ADB="/mnt/d/shuaji/刷机/刷机/adb-fastboot/adb.exe"
   "$ADB" -s "$SERIAL" shell ip -4 addr show wlan0
   ```

4. Enable TCP mode (this restarts adbd):

   ```bash
   "$ADB" -s "$SERIAL" tcpip 5555
   ```

5. Connect over Wi-Fi and confirm both the transport and target device:

   ```bash
   "$ADB" connect "$PHONE_IP:5555"
   "$ADB" devices -l
   "$ADB" -s "$PHONE_IP:5555" shell getprop ro.product.model
   ```

For Android 11+ pairing-code mode, the user must provide the displayed pairing IP/port and
six-digit code:

```bash
"$ADB" pair "$PAIRING_IP:$PAIRING_PORT" "$PAIRING_CODE"
"$ADB" connect "$PHONE_IP:$DEBUG_PORT"
```

Do not infer or bypass pairing codes. If USB authorization or a wireless pairing code is
required, ask the user to complete it on the device and then rerun the connection check.

## Installed SDK packages

If a package is missing, install it into the project SDK rather than another location:

```bash
SDKMANAGER=.android-sdk/cmdline-tools/latest/bin/sdkmanager
"$SDKMANAGER" --sdk_root="$PWD/.android-sdk" \
  "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

Accept SDK licenses only when the user has authorized SDK installation.
