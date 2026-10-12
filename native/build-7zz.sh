#!/usr/bin/env bash
# Build 7-Zip console (7zz) for Android and drop it into jniLibs as lib7zz.so.
# Packaged as a "native library" so Android extracts it to nativeLibraryDir, the
# only app location the OS lets us exec from.
set -euo pipefail

VER=26.04
TAG=${VER/./}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$ROOT/native/build
OUT=$ROOT/app/src/main/jniLibs
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
NDK=${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}
BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
API=26

mkdir -p "$WORK"
SRC=$WORK/7z$TAG-src
if [ ! -d "$SRC" ]; then
  curl -fsSL "https://github.com/ip7z/7zip/releases/download/$VER/7z$TAG-src.tar.xz" -o "$WORK/src.tar.xz"
  mkdir -p "$SRC" && tar xf "$WORK/src.tar.xz" -C "$SRC"
fi

build() { # abi target extra-make-args...
  local abi=$1 target=$2; shift 2
  local obj=$WORK/obj-$abi
  make -C "$SRC/CPP/7zip/Bundles/Alone2" -f makefile.gcc -j"$(nproc)" \
    O="$obj" CC="$BIN/$target$API-clang" CXX="$BIN/$target$API-clang++" USE_CLANG=1 \
    CFLAGS_WARN_WALL= CFLAGS_WARN= LIB2=-ldl \
    LD_arch="-static-libstdc++ -Wl,-z,max-page-size=16384" "$@"
  mkdir -p "$OUT/$abi"
  "$BIN/llvm-strip" -o "$OUT/$abi/lib7zz.so" "$obj/7zz"
}

build arm64-v8a aarch64-linux-android IS_ARM64=1 USE_ASM=1
build armeabi-v7a armv7a-linux-androideabi
ls -l "$OUT"/*/lib7zz.so
