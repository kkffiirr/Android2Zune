# Android2Zune (ZTransfer)

**Send music from an Android phone to a Microsoft Zune over a USB OTG cable.**

ZTransfer is a small Android app that talks to a Zune the way the Zune desktop software does (MTP with MTPZ
authentication) and uploads tracks, albums, artwork and folders, with no PC involved. It is a thin Android layer
(Kotlin UI + JNI) on top of [XuneSyncLibrary](https://github.com/xune-software/XuneSyncLibrary), with small patches
that let libusb work through Android's USB permission system.

The interface follows the look of the Zune desktop software: white canvas, large thin lowercase title,
bold-caps list headings, and the pink-to-orange accent.

## Status

Early test build. Honest summary of what has and has not been verified:

- Verified: builds for arm64 and x86_64; installs and runs on a POCO F7 (Android 16); connected to a **Zune 30**
  (USB ID `045e:0710`, reported as "Keel (1st Gen)") and uploaded one track with album and artwork in about 4 seconds.
- Not verified: playback of the transferred track on the device after the app finished, folder transfers of many
  files, the Zune HD and other models, and long batches.
- The Zune HD and other models are supported by the upstream library on paper, but nothing here has been tested on them.
- **Risk:** the upstream library warns that it may corrupt a Zune's media library. Try it on a device whose
  library you can afford to reset.

## Features

- Pick individual audio files or a whole folder (searched recursively; MP3, WMA, M4A, FLAC, AAC, OGG).
- Groups tracks by artist and album from their tags and sends artwork.
- Live progress ("track N of M") and a full log tab.
- Connects through Android's USB host permission dialog; works with an OTG adapter on a non-rooted phone.
- Staged copies are removed after a successful send. Your original files are never modified.

## Download

The latest APK is in [`releases/`](releases/) (`ZTransfer-0.4-arm64.apk`, arm64 phones, Android 7+). It is a debug-signed
test build: allow installs from your browser or file manager, and expect a "unknown developer" warning.

## MTPZ credentials (one-time setup)

Zunes require MTPZ authentication before they accept writes. The app uses a credentials file in the `.mtpz-data`
format that libmtp and XuneSyncLibrary read.

**This repository and its APK do not contain that file.** On first launch the app shows a one-time
*initial setup* screen and asks how to get it:

- **download keys online**: after a confirmation dialog, the app fetches the file on your phone from the public
  repository [kbhomes/libmtp-zune](https://github.com/kbhomes/libmtp-zune) (`src/.mtpz-data`), checks that it has the
  expected shape, and stores it privately in the app. It is a third-party file, not covered by this project's
  license; you are responsible for being allowed to use it with your own device.
- **import key file**: pick a `.mtpz-data` you already have.
- **skip**: the app then tries plain MTP, and a Zune will usually answer `AccessDenied (0x200f)` to writes.

You are asked only once. The *settings* button on the main screen reopens the setup if you ever want to change
the key. For a private build you can also bundle a key yourself: put it at `app/src/main/assets/mtpz-data`
(git-ignored); a bundled key is used automatically and the setup screen is skipped.

## How it is built

The app is built from three pieces: this repository (Kotlin app, JNI bridge, CMake), the unmodified upstream
sources at pinned commits, and two small patches in `patches/`.

| Upstream | Pinned commit | Patch |
|---|---|---|
| [xune-software/XuneSyncLibrary](https://github.com/xune-software/XuneSyncLibrary) | `c2a6eeb` | `patches/0001-xune-sync-library-android-fd-connect.patch` (adds `zune_device_connect_usb_fd`) |
| its submodule `vendor/android-file-transfer-linux` ([magicisinthehole fork](https://github.com/magicisinthehole/android-file-transfer-linux)) | `eb36f23` | `patches/0002-aftl-libusb-android-fd-wrap.patch` (libusb over an Android USB fd; bionic `strerror_r`) |

Other pieces in this repo:

- `native/CMakeLists.txt` builds AFTL, libusb, the XuneSyncLibrary core and the JNI library `libxune_sync.so`.
- `native/upload_runner.cpp` is derived from the upstream `tools/upload_test_cli.cpp` (the author's capture-verified
  upload sequence), changed to connect through an Android fd and to skip its interactive research tail.
- `native/shim/curl/curl.h` stubs out libcurl, which upstream uses only for proxying artist images.
- Two build gotchas worth knowing: AFTL's MTPZ code is guarded by `MTPZ_ENABLED` (not `MTPZ`), and libusb's
  `LIBUSB_OPTION_NO_DEVICE_DISCOVERY` is an enum, so it must be set with `libusb_init_context()` and not under `#ifdef`.

### Build steps (as done on Windows)

Requirements: Android SDK with NDK `26.3.11579264`, CMake `3.22.1`, JDK 17+, Gradle 8.7 (AGP 8.5.2).

1. Lay the project out like this (folder names matter, `native/CMakeLists.txt` uses them):
   ```
   Android2Zune/            this repo
   Android2Zune/XuneSyncLibrary/    git clone --recursive, then: git checkout c2a6eeb; git submodule update;
                                    git -C vendor/android-file-transfer-linux checkout eb36f23
   Android2Zune/deps/               libusb-1.0.27/ (from the libusb 1.0.27 release tarball)
                                    openssl/  (unzip Google's prefab AAR:
                                    com.android.ndk.thirdparty:openssl:1.1.1q-beta-1)
                                    taglib-2.0.2/  (+ utfcpp v4.0.5 copied into 3rdparty/utfcpp)
   ```
2. Apply the patches: `git apply patches/0001-*.patch` in `XuneSyncLibrary/`, and `git apply patches/0002-*.patch`
   in `XuneSyncLibrary/vendor/android-file-transfer-linux/`.
3. Build TagLib for each ABI (static, `-DBUILD_SHARED_LIBS=OFF -DWITH_ZLIB=OFF -DBUILD_BINDINGS=OFF`, NDK toolchain)
   and install it to `taglib-install/<abi>/`.
4. For each ABI (`arm64-v8a`, `x86_64`) configure and build `native/` with the NDK toolchain
   (`-DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static`), strip `libxune_sync.so`, and copy it plus
   OpenSSL's `libcrypto.so` into `app/src/main/jniLibs/<abi>/`.
5. `gradle :app:assembleDebug` (add `-PphoneOnly` for an arm64-only APK, about 4.5 MB).

Run it: finish the one-time setup, then *add files* or *add folder*, plug the Zune in over OTG, and tap *send to zune*.
A self-test that needs no Zune: `adb shell am start -n org.xune.ztransfer/.MainActivity --ez selftest true`.

## Credits

- **Developer:** [kkffiirr](https://github.com/kkffiirr)
- **[XuneSyncLibrary](https://github.com/xune-software/XuneSyncLibrary)** by the xune-software team (LGPL-2.1):
  the Zune protocol work, the MTPZ session handling and the upload sequence this app relies on.
- **[Android File Transfer for Linux](https://github.com/whoozle/android-file-transfer-linux)** by Vladimir Menshakov
  (LGPL), and the [magicisinthehole fork](https://github.com/magicisinthehole/android-file-transfer-linux) that
  adds Zune and MTPZ support: the MTP/MTPZ implementation under the hood.
- **[libusb](https://libusb.info)** (LGPL-2.1), **OpenSSL** (via Google's NDK prebuilt package) and
  **[TagLib](https://taglib.org)** (LGPL-2.1 / MPL-1.1).
- The **[libmtp](https://libmtp.sourceforge.net)** project, whose MTPZ work and `.mtpz-data` format the whole
  ecosystem builds on.
- Interface styling inspired by Microsoft's Zune desktop software. This project is not affiliated with or endorsed
  by Microsoft. Zune is a trademark of Microsoft Corporation.
- Developed with the assistance of Claude Code (Anthropic).

## License

This project is licensed under the **GNU Lesser General Public License v2.1** (see `LICENSE`), because it contains
modifications of, and code derived from, LGPL-2.1 projects (XuneSyncLibrary and Android File Transfer for Linux).
Third-party components keep their own licenses.
