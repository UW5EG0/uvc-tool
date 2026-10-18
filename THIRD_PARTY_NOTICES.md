# Third-party components

UVC Tool links the following libraries into its native code (`libuvctool.so`).
Their sources are included as git submodules under `third_party/`.

## libusb — LGPL-2.1-or-later
https://github.com/libusb/libusb — see `third_party/libusb/COPYING`.

libusb is linked statically. As required by the LGPL, the complete source of
this application and the exact libusb revision used are published in this
repository, so anyone can rebuild the app with a modified libusb
(`cd android && ./gradlew assembleRelease`).

## libuvc — BSD-3-Clause
https://github.com/libuvc/libuvc — Copyright (C) 2010-2015 Ken Tossell.
See `third_party/libuvc/LICENSE.txt`. `android/app/src/main/cpp/config/libuvc/libuvc_config.h`
is libuvc's generated config header (v0.0.8, JPEG disabled); `config/version_describe.h`
replaces libusb's generated version header.

## AndroidX / Material Components — Apache-2.0
Pulled from Google's Maven repository at build time.
