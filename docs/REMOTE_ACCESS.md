# Remote access (J7 Prime, Android 8.1)

Tested with this fork on a Samsung Galaxy J7 Prime (SM-G610M, Android 8.1,
armeabi-v7a). Root is provided by Magisk.

## ADB over Wi-Fi

ADB over TCP is disabled by default. With root, enable it from a local shell
or an existing SSH session:

```sh
setprop service.adb.tcp.port 5555
setprop persist.adb.tcp.port 5555
stop adbd
start adbd
```

Persist it across reboots with `/data/adb/service.d/adb-tcp.sh`:

```sh
#!/system/bin/sh
setprop service.adb.tcp.port 5555
stop adbd
start adbd
```

Install it as `root:root`, mode `755`.

Connect from the host:

```bat
adb connect <device-ip>:5555
adb devices
```

`ro.adb.secure=1` keeps RSA authentication enabled, so only hosts with an
authorized ADB key can connect.

To disable wireless ADB:

```sh
setprop service.adb.tcp.port ""
setprop persist.adb.tcp.port ""
rm /data/adb/service.d/adb-tcp.sh
stop adbd
start adbd
```

## scrcpy over Wi-Fi

scrcpy does **not** install an app on the device. It pushes a temporary
`scrcpy-server` to `/data/local/tmp` and runs it with Android's
`app_process`, then streams video and input over ADB. No root is required.

```bat
adb connect <device-ip>:5555
scrcpy.exe -s <device-ip>:5555 --no-audio --stay-awake
```

- `--no-audio` is required on Android 8.1, which has no audio capture.
- `--stay-awake` keeps the display on while mirroring.
- If scrcpy prints `adb reverse: more than one device/emulator`, it falls
  back to `adb forward`; mirroring still works.

## SSH

SSH is not bundled with this app. The tested setup is the third-party Magisk
module **SSH for Magisk v0.16** by D4rCM4rC and Contributors:

- Module directory: `/data/adb/modules/ssh`
- `service.sh` runs `opensshd.init start` at boot unless
  `/data/ssh/no-autostart` exists
- sshd listens on port 22, IPv4 and IPv6
- Root login, key-only authentication

Install the module through Magisk, then add the host's public key through the
module configuration and restart the daemon.

On this device, non-interactive SSH does not put `magisk` in `PATH`; use
`/data/adb/magisk/magisk` explicitly.
