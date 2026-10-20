# Ghostty Android — build notes (J7 Prime, armv7)

Built 2026-10-20 from `tapthaker/ghostty-android` @ `b68e170`
("feat(renderer): Improve tint overlay...", 2026-01-16).

## Result

- Ghostty core: **v1.3.1** (tag `332b2aefc`, 2026-03-13)
- Installed APK (release, R8): `android/app/build/outputs/apk/release/app-release-profile-signed.apk`
  (7,624,059 bytes), signed with the debug key
- Release SHA-256: `4b6e80d3b4c1cc7c7820851a60ce34b3c4abea54a8987d04778c3773defc2b33`
- Installed as `com.ghostty.android` 0.1.0 on `3300b66131e37483`
  (release: 19 MB on disk; debug: 87 MB)
- Native: `lib/armeabi-v7a/libghostty_renderer.so` (11 MB stripped),
  `libghostty-vt.so` (214 KB stripped), `libpty_shell.so` (5.6 KB)
- Renderer initializes on Mali-T830, 118x16 grid, 420 DPI; 256-color palette
  and SGR attributes render correctly.
- The app now boots into an **interactive terminal**: `/system/bin/sh -i` on a
  PTY, with echo, backspace, Ctrl+C and job control. Typed commands, `uname -a`
  and `id` were verified on-device.
- The visual-regression harness is still compiled in, but it is no longer the
  entry screen. It was run before the terminal wiring: **35 tests, 33 passed,
  2 failed**. The two failures (`replay_dab597_instant` and
  `replay_dab597_30ms`, tests 34–35) are
  `FileNotFoundException: replay/dab597.log`: the replay asset was never
  committed to the repo (there is no `assets/` directory). All 33 actual
  rendering tests pass.

## Security review

Reviewed all 119 tracked files before building:

- **No `INTERNET` permission** in either manifest; the app cannot open sockets
  (only a generated DYNAMIC_RECEIVER_NOT_EXPORTED permission is present).
- No telemetry, analytics, ad SDKs, hard-coded endpoints, or remote config.
- No `Runtime.exec` beyond `TerminalSession`, which now runs the locally built
  PTY helper and `/system/bin/sh` as the app's own UID. With no `INTERNET`
  permission, that shell is unprivileged and cannot open network sockets.
- Gradle uses only `google()`, `mavenCentral()`, `gradlePluginPortal()`.
  Dependencies are standard AndroidX/Compose/Kotlin.
- No CI workflows, no git hooks, no post-checkout scripts.
- `gradle-wrapper.jar` was not used; the build used a separately downloaded
  Gradle 8.11 distribution.
- The submodule points at `https://github.com/ghostty-org/ghostty.git`.

**Reproducibility caveat:** the pinned submodule commit
`3c98f1fe7f2771906533d1d5686e496ce8012f51` does **not exist** in the upstream
Ghostty repository (not in any branch, tag, or PR ref), so
`git submodule update` fails. The current build uses the latest official
release, **v1.3.1** (`332b2aefc`, 2026-03-13), with two local fixes. The
earlier Jan-2026 build used `4e5c1dcdc` (2026-01-16).

## Toolchain

| Component | Version / path |
|---|---|
| JDK | Temurin 17.0.20.1 (`/home/jotav/toolchain/jdk`) |
| Gradle | 8.11 (`/home/jotav/toolchain/gradle-8.11`) |
| Android SDK | platform 35, build-tools 35.0.0 (`/home/jotav/android-sdk`) |
| NDK | r29 `29.0.14206865` (`/home/jotav/toolchain/android-ndk-r29`) |
| Zig | 0.15.2 (`/home/jotav/toolchain/zig`) |
| patchelf | 0.18.0 (`/home/jotav/toolchain/patchelf`) |

## Local patches

`patches/ghostty-v1.3.1-android.patch` (applied by
`scripts/apply-ghostty-patch.sh`):

1. `src/lib_vt.zig` — re-export `src/renderer/cursor.zig` as
   `RendererCursorStyleOptions`, `RendererCursorStyle`, `rendererCursorStyle`.
   The renderer expects these from the `ghostty-vt` module, but upstream
   `lib_vt.zig` does not export them.
2. `src/terminal/formatter.zig` — four `discarding.count` (u64) to `usize`
   casts, required for 32-bit ARM.

`build/android-local.patch`:

1. Both Gradle modules: `abiFilters` reduced to `armeabi-v7a`.

## Build commands

```bash
export JAVA_HOME=/home/jotav/toolchain/jdk
export ANDROID_HOME=/home/jotav/android-sdk
export ANDROID_NDK_ROOT=/home/jotav/toolchain/android-ndk-r29
export ANDROID_NDK_HOME=/home/jotav/toolchain/android-ndk-r29  # v1.3.1 detection
export PATH=$JAVA_HOME/bin:/home/jotav/toolchain/zig:/home/jotav/toolchain/gradle-8.11/bin:$PATH

cd work/ghostty-android
git -C libghostty-vt checkout -f v1.3.1
./scripts/apply-ghostty-patch.sh

./scripts/generate-android-libc.sh armeabi-v7a build/android-armeabi-v7a-libc.txt
# NDK r29 ships lib/clang/21/lib/linux/arm, while the script looks for armv7:
ln -s arm $ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/21/lib/linux/armv7

cd libghostty-vt
zig build lib-vt -Dtarget=arm-linux-androideabi.24 -Doptimize=ReleaseFast \
  -Dapp-runtime=none -Dsimd=false -Dcpu=baseline \
  --libc ../build/android-armeabi-v7a-libc.txt

cd ../android/renderer
zig build -Dtarget=arm-linux-androideabi.24 -Doptimize=ReleaseFast \
  --cache-dir ../../build/renderer-cache-armeabi-v7a \
  --libc ../../build/android-armeabi-v7a-libc.txt
```

Note: targeting `arm-linux-android.24` makes Zig pass `-mfloat-abi=hard` to
C dependencies, which Clang rejects for Android. The traditional
`arm-linux-androideabi.24` triple builds correctly (softfp).

Then stage and patch the libraries:

```bash
DEST=android/terminal-library/src/main/jniLibs/armeabi-v7a
cp libghostty-vt/zig-out/lib/libghostty-vt.so.0.1.0 $DEST/libghostty-vt.so
cp android/renderer/zig-out/lib/libghostty_renderer.so $DEST/
patchelf --add-needed libGLESv3.so $DEST/libghostty_renderer.so
patchelf --add-needed liblog.so     $DEST/libghostty_renderer.so
llvm-strip --strip-unneeded $DEST/*.so
```

APK:

```bash
cd android
gradle assembleDebug -x buildNativeLibs --no-daemon --console=plain
```

The `buildNativeLibs` task is skipped because it requires `nix-shell` and
defaults to arm64 for debug builds.

## Terminal wiring (local changes)

The upstream app had a renderer and a test harness, but no live terminal.
The following local changes connect them:

- `MainActivity` now shows `TerminalScreen` and starts the session when the GL
  surface reports its grid size.
- `TerminalSession` exposes an incremental `chunks: SharedFlow<String>`; the
  activity forwards each chunk to `GhosttyRenderer.processInput` on the GL
  thread.
- A PTY is required for echo/line editing. `android/app/src/main/cpp/pty_shell.c`
  allocates a PTY with `forkpty`, runs `sh -i`, and relays stdin/stdout.
  It is compiled as `android/app/src/main/jniLibs/armeabi-v7a/libpty_shell.so`
  and executed from `nativeLibraryDir`.
- `packaging { jniLibs { useLegacyPackaging = true } }` forces extraction of
  native libs so the helper exists as an executable file.
- `MainActivity.dispatchKeyEvent` maps hardware keys (scrcpy) to shell input:
  printable characters, Enter, Backspace, Tab, Esc, arrows, Home/End,
  Page Up/Down, Delete, Ctrl+A..Z and Alt+char. This replaces the unfinished
  JNI key encoder for now.
- The PTY is sized from the renderer grid via `COLUMNS`/`LINES`
  (120x29 on this device with the app bar removed).
- `RENDERMODE_WHEN_DIRTY`: every terminal chunk, resize and resume requests a
  render, so the GL thread stays idle when nothing changes. Idle CPU dropped
  from ~13.5% to **0.0%**.
- The shell starts in the app's private home directory:
  `getDir("home", MODE_PRIVATE)` → `/data/data/com.ghostty.android/app_home`.
  `ProcessBuilder.directory()` sets the cwd and `HOME` is exported, matching
  TermOne Plus's `app_HOME` behaviour.
- mksh personalisation: `ENV` is exported as `$HOME/.mkshrc` (Android's mksh
  does not read `~/.mkshrc` on its own) and `PWD` is seeded. On first run the
  app writes a default profile with `HISTFILE`, `HISTSIZE`, aliases
  (`ll`, `la`, `l`, `home`) and a `basename("$PWD") $` prompt. Users can edit
  `app_home/.mkshrc`; changes apply on the next shell start.
- **Back/relaunch ANR fixed.** `TerminalSession.stop()` used to call
  `BufferedReader.close()` on the main thread while the reader coroutine held
  the stream lock, deadlocking `onDestroy`. Cleanup now runs on the IO scope
  and destroys the process before closing the pipes. Verified: Back to KISS,
  reopen, fresh prompt and working shell.
- **Home/resume keeps the screen.** `preserveEGLContextOnPause = true` on the
  `GLSurfaceView` prevents Android from destroying the EGL context when the
  app is backgrounded. Without it, the native renderer reinitialized on resume
  and the terminal buffer was empty. Verified: switch to KISS and back shows
  the same text, cursor and prompt.

Build the helper with:

```bash
NDK=/home/jotav/toolchain/android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64
$NDK/bin/armv7a-linux-androideabi24-clang -O2 -fPIE -pie \
  -o android/app/src/main/jniLibs/armeabi-v7a/libpty_shell.so \
  android/app/src/main/cpp/pty_shell.c
```

## UI changes

- Full-screen terminal: app bar, TEST button and the ESC/TAB/CTRL/ALT/arrow
  toolbar are removed.
- Launcher icon generated from Ghostty's `images/icons/icon_1024.png`: legacy
  icons for all densities plus an adaptive icon (`mipmap-anydpi-v26`).
  The adaptive foreground is scaled to 80% so KISS fills its icon tile instead
  of showing the 66% foreground with a large dark border. Manifest sets
  `android:icon` and `android:roundIcon`.
- Launch splash, implemented as the API 27 starting window:
  `Theme.Ghostty.Splash` sets `windowBackground` to a layer-list of
  `#0A1A33` plus a 360 px Ghostty icon (`drawable-nodpi/ghostty_splash.png`).
  `MainActivity` calls `setTheme(R.style.Theme_Ghostty)` before the content,
  and the normal theme is now dark/black so there is no white flash.
- Mouse wheel: `GhosttyGLSurfaceView.onGenericMotionEvent` and
  `MainActivity.dispatchGenericMotionEvent` call `handleWheelScroll`
  (3 rows per notch). Android 8.1's `input` command cannot position a pointer,
  so this could not be exercised via adb; verify with scrcpy.

## Benchmark vs TermOne Plus 5.7.1

Same device, same conditions. PSS from `dumpsys meminfo`, CPU from `top`.

| Metric | Ghostty v1.3.1 | Ghostty Jan-2026 | TermOne Plus |
|---|---|---|---|
| APK | 7.0 MiB | 7.0 MiB | 5.45 MiB |
| Installed | 19 MB | 19 MB | 15 MB |
| Cold start (median of 3) | 774 ms | 777 ms | 711 ms |
| Idle PSS | 54–57 MB | 45–62 MB | 47.8 MB |
| Idle CPU | **0.0%** | 13.3–13.7% | 0.0% |
| 20k lines | 0.17 s | 0.18 s | 0.18 s |
| 2 MB (~1M lines) | **6.91 s** | >36.8 s, killed | 7.18 s |

Two changes closed the gap:

1. v1.3.1 fixed the pathological output case: the 2 MB burst went from
   "still running at 100% CPU after 36.8 s" to 8.05 s.
2. Switching `GhosttyGLSurfaceView` to `RENDERMODE_WHEN_DIRTY` brought idle
   CPU from ~13.5% to 0.0% and improved the same burst to 6.91 s.

The first `top` sample immediately after launch can still show ~13% while the
renderer/shell initializes; it settles to 0.0% within a few seconds.

Caveats: single device, PSS varies with GL buffer allocation, and the 1M-line
case is deliberately extreme.

Known limitations: no PTY resize after an orientation change, no mouse
reporting (wheel scrolling is implemented), and the key encoder JNI path is
still unused. The test harness is still present but no longer reachable.
Back/relaunch now starts a fresh shell; previous scrollback is not preserved
because the activity and PTY are destroyed.
