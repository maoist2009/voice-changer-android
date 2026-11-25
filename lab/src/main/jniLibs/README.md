# liblab_capture.so

Built from `../../cpp/lab_capture.c` with the NDK's own host toolchain. The
`lab` module has no Gradle native integration on purpose: one file, one
command, no CMake version to negotiate.

```sh
NDK=$ANDROID_HOME/ndk/<version>
CC=$NDK/toolchains/llvm/prebuilt/linux-arm64/bin/aarch64-linux-android31-clang
$CC -shared -fPIC -O2 -Wall \
    -o lab/src/main/jniLibs/arm64-v8a/liblab_capture.so \
    lab/src/main/cpp/lab_capture.c \
    -laaudio -lOpenSLES -llog -landroid
```

`linux-arm64` is the prebuilt host for an aarch64 machine; use
`linux-x86_64` on a normal desktop. Rebuild and reinstall the APK after
changing the C file — Gradle only packages the `.so`.
