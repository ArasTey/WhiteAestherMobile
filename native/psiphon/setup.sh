#!/usr/bin/env bash
# Bash port of native/psiphon/setup.ps1.
#
# The .ps1 original is the source of truth for what gets installed and what
# counts as a valid answer; this exists only because pwsh is not installed on
# this machine. It reads the pins out of setup.ps1 rather than restating them,
# so the two cannot drift into disagreeing about which list is the pinned one.
#
#   ./native/psiphon/setup.sh [-f|--force]

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
dest="$root/app/src/main/assets/psiphon_server_entries.txt"
keyFile="$here/server_entry_signature_key.txt"
thirdParty="$here/third_party"
checkout="$thirdParty/psiphon-tunnel-core"
# Beside the checkout rather than beside the list: anything next to the list is
# under assets/ and would ship inside the APK.
marker="$thirdParty/server_list.verified"

force=false
[ "${1:-}" = "-f" ] || [ "${1:-}" = "--force" ] && force=true

# Pull the pins out of the PowerShell so they are defined in one place.
setup="$here/setup.ps1"
pin() { sed -n "s/^\\\$$1 *= *'\\([^']*\\)'.*/\\1/p" "$setup"; }
Repo="$(pin Repo)"; Rev="$(pin Rev)"; Path="$(pin Path)"
Sha256="$(pin Sha256)"
TunnelCoreRepo="$(pin TunnelCoreRepo)"; TunnelCoreRev="$(pin TunnelCoreRev)"
if [ -z "$Repo" ] || [ -z "$Sha256" ] || [ -z "$TunnelCoreRev" ]; then
    echo "could not read the pins out of setup.ps1" >&2
    exit 1
fi

file_sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
text_sha() { printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; }

# Checks every entry of a list against the key the app ships with.
#
# The digest pin proves the file has not changed; it proves nothing about the
# entries. Their signatures do, and tunnel-core only checks them when it is
# given the key -- which is what server_entry_signature_key.txt is for. A list
# that does not verify is either a key Psiphon has rotated, which would make the
# app reject every entry at runtime and quietly stop connecting, or a list that
# is not Psiphon's, which is worse. So this refuses rather than warns.
#
# Verified once per list-and-key pair: those are the only two things that
# change between two ordinary builds, and checking every time would make Go and
# a clone of tunnel-core part of every local build.
verify_list() {
    local list="$1"
    local key pair
    key="$(cat "$keyFile")"
    pair="$(file_sha "$list") $(text_sha "$key")"
    if [ -f "$marker" ] && [ "$(cat "$marker")" = "$pair" ]; then
        echo "  server list already verified against this signature key"
        return 0
    fi
    # Gone before the check rather than after it, so an interrupted check can
    # never leave a marker claiming this pair was verified.
    rm -f "$marker"

    if [ ! -f "$checkout/go.mod" ]; then
        echo "cloning psiphon-tunnel-core $TunnelCoreRev for the signature check..."
        mkdir -p "$thirdParty"
        git clone --quiet --depth 1 --branch "$TunnelCoreRev" "$TunnelCoreRepo" "$checkout"
    fi
    # A checkout present but on another revision would answer for a tunnel-core
    # the app does not ship. Refuse rather than check with it.
    local described
    described="$(git -C "$checkout" describe --tags --always)"
    if [ "$described" != "$TunnelCoreRev" ]; then
        echo "$checkout is at $described, not $TunnelCoreRev. Delete it and run this again." >&2
        return 1
    fi

    local probe="$checkout/zz_whiteaesther_verify"
    mkdir -p "$probe"
    cp "$here/verify/main.go" "$probe/main.go"

    # Pinned, and read out of tunnel-core's own go.mod rather than restated
    # here. `GOTOOLCHAIN=auto` is what the PowerShell uses and it is not enough:
    # auto only ever moves forward, so on a machine whose Go is newer than the
    # one tunnel-core was written against it silently keeps that Go, and the
    # verifier then panics in psiphon-tls' layout assertion --
    #
    #   tls: ConnectionState ... struct field count mismatch: 18 vs 17
    #
    # which is the stdlib having gained a field the vendored mirror has not. The
    # verifier is the check that a list is Psiphon's and not merely
    # byte-identical to the pin, so it has to actually run; the pin alone is not
    # the same assurance.
    local toolchain
    toolchain="$(sed -n 's/^toolchain //p' "$checkout/go.mod" | head -1)"
    [ -n "$toolchain" ] || toolchain="local"

    # Go writes progress to stderr; that is not a failure, the exit code is.
    local out="" code=1 attempt
    for attempt in 1 2 3; do
        set +e
        out="$(cd "$checkout" && CGO_ENABLED=0 GOTOOLCHAIN="$toolchain" \
            go run ./zz_whiteaesther_verify "$list" "$keyFile" 2>&1)"
        code=$?
        set -e
        # A verdict is final; anything else is Go failing to get as far as one,
        # most often a toolchain download proxy.golang.org dropped mid-stream.
        case "$out" in
            *"server entries verify against the signature key"*) break ;;
        esac
        [ "$attempt" = 3 ] && break
        echo "  the verifier did not run (attempt $attempt of 3); retrying"
        sleep $((5 * attempt))
    done
    rm -rf "$probe"

    if [ "$code" -ne 0 ]; then
        case "$out" in
            *"server entries verify against the signature key"*)
                echo "  $out" ;;
            *)
                echo "The Psiphon signature check could not run: $out" >&2
                return 1 ;;
        esac
    else
        echo "  $out"
    fi
    printf '%s' "$pair" > "$marker"
}

if [ -f "$dest" ] && [ "$force" = false ]; then
    if [ "$(file_sha "$dest")" = "$Sha256" ]; then
        echo "server list already present and matches the pin"
        # Present is not the same as checked.
        verify_list "$dest"
        exit 0
    fi
    echo "server list present but does not match the pin; replacing"
fi

mkdir -p "$(dirname "$dest")"
url="https://raw.githubusercontent.com/$Repo/$Rev/$Path"
echo "fetching the embedded server list..."
tmp="$dest.part"
curl -fsSL --retry 3 --retry-delay 5 -o "$tmp" "$url"

# Checked before it is moved into place, so a truncated or substituted download
# never becomes the list a client bootstraps from.
have="$(file_sha "$tmp")"
if [ "$have" != "$Sha256" ]; then
    rm -f "$tmp"
    echo "server list checksum mismatch: expected $Sha256, got $have" >&2
    exit 1
fi

# One line that is not hex is a file that is not this format -- an HTML error
# page saved with a 200, most likely -- and tunnel-core would reject the lot
# without saying which line lost it.
bad="$(grep -n -v -m1 -E '^[0-9a-fA-F]+$' "$tmp" | cut -d: -f1 || true)"
if [ -n "$bad" ]; then
    rm -f "$tmp"
    echo "server list is not hex at line $bad" >&2
    exit 1
fi

if ! verify_list "$tmp"; then
    rm -f "$tmp"
    exit 1
fi

mv "$tmp" "$dest"
echo "server list installed: $dest ($(wc -l < "$dest" | tr -d ' ') entries)"