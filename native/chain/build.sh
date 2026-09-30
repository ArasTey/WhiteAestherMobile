#!/usr/bin/env bash
# Bash port of native/chain/setup.ps1 + native/chain/build.ps1.
#
# The .ps1 originals are the source of truth for what gets built and why;
# this exists only because pwsh is not installed on this machine. It reads the
# same pins out of setup.ps1 rather than restating them, so the two cannot
# drift into disagreeing about what "the pinned revision" means.
#
#   ./native/chain/build.sh [abi ...]      (default: arm64-v8a x86_64 armeabi-v7a)

set -euo pipefail

# Go only falls through to the next GOPROXY entry on 404 and 410, never on 403,
# and proxy.golang.org answers 403 for individual zips on this network -- lz4 is
# one. An unreachable mirror therefore has to be left out of the list rather than
# ranked low in it, or the whole graph stops on the first bad zip. Set before
# anything runs go, including the tidy below.
export GOPROXY="https://goproxy.cn,https://mirrors.aliyun.com/goproxy,https://proxy.golang.org,direct"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
core="$here/third_party/flclash/core"
out="$here/build"

# Pull the pins out of the PowerShell so they are defined in exactly one place.
setup="$here/setup.ps1"
MetaRev="$(sed -n "s/^\\\$MetaRev *= *'\\([^']*\\)'.*/\\1/p" "$setup")"
CoreRev="$(sed -n "s/^\\\$CoreRev *= *'\\([^']*\\)'.*/\\1/p" "$setup")"
if [ -z "$MetaRev" ] || [ -z "$CoreRev" ]; then
    echo "could not read the pins out of setup.ps1" >&2
    exit 1
fi
echo "pins: core=$CoreRev meta=$MetaRev"

if [ ! -f "$core/go.mod" ]; then
    echo "core missing at $core" >&2
    exit 1
fi

# --- setup.ps1: pin Clash.Meta and apply the REALITY patch -----------------
meta="$core/Clash.Meta"
if [ ! -f "$meta/go.mod" ]; then
    echo "fetching Clash.Meta ..."
    rm -rf "$meta"
    git clone --filter=blob:none https://github.com/chen08209/Clash.Meta.git "$meta"
fi
cd "$meta"
git checkout --quiet "$MetaRev"
echo "Clash.Meta now at $(git rev-parse HEAD)"

patch="$here/patches/0001-reality-client-version.patch"
if [ -f "$patch" ]; then
    if git apply --reverse --check "$patch" 2>/dev/null; then
        echo "REALITY patch already applied"
    else
        git apply "$patch"
        echo "REALITY patch applied"
    fi
fi

# --- setup.ps1: reconcile modules ------------------------------------------
# The checked-in go.sum was written against a different Clash.Meta and is
# missing sing-shadowtls; without this the first build fails on it.
cd "$core"
echo "reconciling modules ..."
GOFLAGS=-mod=mod go mod tidy

# build.ps1 pulls the whole module graph up front with retries before building
# anything, because a release does not have time to lose a half-fetched graph.
for attempt in 1 2 3; do
    echo "fetching modules (attempt $attempt/3) ..."
    if go mod download all; then
        break
    fi
    if [ "$attempt" = 3 ]; then
        echo "go mod download failed after 3 attempts" >&2
        exit 1
    fi
    sleep $((5 * attempt))
done

# --- build.ps1: resolve the SDK the way Gradle does ------------------------
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f "$here/../../local.properties" ]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$here/../../local.properties")"
fi
if [ -z "$sdk" ]; then
    echo "set ANDROID_HOME, or sdk.dir in local.properties" >&2
    exit 1
fi
ndk="$sdk/ndk/29.0.14206865"
[ -d "$ndk" ] || { echo "NDK not found at $ndk" >&2; exit 1; }

# The NDK ships one toolchain per host; development is macOS here, CI is Linux,
# which is exactly the reason build.ps1 reads this off the host.
host="$(uname -s | tr '[:upper:]' '[:lower:]')"
case "$host" in
    darwin) hosttag="darwin-x86_64" ;;
    linux)  hosttag="linux-x86_64" ;;
    *)      echo "unsupported host $host" >&2; exit 1 ;;
esac
bin="$ndk/toolchains/llvm/prebuilt/$hosttag/bin"
[ -d "$bin" ] || { echo "NDK toolchain not found: $bin" >&2; exit 1; }

abis=("$@")
[ ${#abis[@]} -eq 0 ] && abis=(arm64-v8a x86_64 armeabi-v7a)

# build.ps1:98-116, including the clang driver per ABI and GOARM=7 for 32-bit.
for name in "${abis[@]}"; do
    case "$name" in
        arm64-v8a)   arch=arm64; cc="$bin/aarch64-linux-android26-clang" ;;
        x86_64)      arch=amd64; cc="$bin/x86_64-linux-android26-clang" ;;
        armeabi-v7a) arch=arm;   cc="$bin/armv7a-linux-androideabi26-clang" ;;
        *) echo "unknown ABI: $name" >&2; exit 1 ;;
    esac
    [ -x "$cc" ] || { echo "NDK clang not found: $cc" >&2; exit 1; }

    mkdir -p "$out/$name"
    echo "building $name -> $out/$name/libwhiteaestherchain.so"
    (
        export CGO_ENABLED=1 GOOS=android GOARCH="$arch" CC="$cc"
        # minSdk is 26 for every ABI; 16KB pages because Android 15+ requires
        # them on some devices and a 4KB-aligned library will not load there.
        export GOARM=7
        [ "$arch" = arm ] || unset GOARM
        go build -tags=with_gvisor -buildmode=c-shared -trimpath \
            -ldflags="-w -s -extldflags=-Wl,-z,max-page-size=16384" \
            -o "$out/$name/libwhiteaestherchain.so" .
    )
done

echo
echo "built: ${abis[*]}"