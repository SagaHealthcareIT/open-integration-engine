#!/usr/bin/env bash
#
# make-zulu-fx-bundles.sh: build the install4j JRE bundles for Azul Zulu 21.0.13.1 with JavaFX
# (Zulu21.54+201-CA) that the installer project uses with jreBundleSource="preCreated".
#
# Why this exists: 21.0.13.1 is the Java release that fixes CVE-2026-114938. Azul published its FX
# packages of that build on its download server, but its metadata catalogue lists only the packages
# without JavaFX, and the catalogue is all install4j's Zulu JDK provider sees: with
# release="21/21.0.13.1" install4j quietly bundles a runtime without JavaFX. Until Azul lists the FX
# packages, this script builds the bundles from the CDN files instead, in the same shape install4j
# generates (checked against the bundles install4j generated for 21.0.12):
#   linux-amd64          jlink of every module of the FX JDK (byte-identical to install4j's
#                        createbundle --add-module-set=all)
#   windows-amd64        jlink of every module of the Windows FX JDK, run with the Linux FX JDK's jlink
#                        (same build); then Azul's original .dll/.exe files are copied back over jlink's
#                        copies, as install4j does, so their Authenticode signatures are kept
#   macos-amd64/aarch64  Azul's whole .jdk Contents/ tree, minus lib/server/classes.jsa, as install4j does
# Each download is checked against the sha256 pinned below before it is used. The FX files are the
# same build as the catalogued non-FX packages (same version, implementor version and source commit).
#
# Run it before every installer build that uses these bundles (release builds included); it reuses
# downloads and rebuilds the bundles in a few seconds. Remove it, and go back to the plain jdkProvider
# config (jreBundleSource unset), once Azul's catalogue lists the FX packages of a fixed release:
#   curl -s 'https://api.azul.com/metadata/v1/zulu/packages/?java_version=21&java_package_type=jdk&javafx_bundled=true&latest=true&release_status=ga&availability_types=CA&page_size=100'
#
# Usage: tools/install4j/make-zulu-fx-bundles.sh [platform ...]
#   platforms: linux-amd64 windows-amd64 macos-amd64 macos-aarch64 (default: DEFAULT_PLATFORMS below)
# Env:
#   ZULU_FX_CACHE   download directory (default: ${XDG_CACHE_HOME:-~/.cache}/openshare/zulu-fx)
#   OUT_DIR         bundle directory (default: tools/install4j/jre-bundles, gitignored; the install4j
#                   project refers to the bundles there)
#
# Needs: bash, curl, sha256sum, tar, gzip, unzip. Runs on Linux x64 only (it executes the Linux JDK's jlink).
set -euo pipefail

DEFAULT_PLATFORMS="linux-amd64 windows-amd64"

ZULU=zulu21.54.201-ca-fx-jdk21.0.13.1
BUNDLE_VERSION=21.0.13.1
EXPECT_IMPLEMENTOR='Zulu21.54+201-CA'
CDN=https://cdn.azul.com/zulu/bin
# Pinned 2026-11-20 from Azul's CDN; release metadata and code signatures checked against Azul's
# catalogued non-FX packages of the same build.
declare -A SHA256=(
  [linux_x64.tar.gz]=0666c4f91f6bf39f25e8b3ef784f2905a3cb6c1a6a9411d668e589077317ebb5
  [win_x64.zip]=a2b6764a9762633dd66b0435685f36fa37f4dffacf1322545027f276d3654a99
  [macosx_x64.zip]=4aff1cdab291bd757635c73f26e10d1e154f608bc0c86ca8b45f937304083875
  [macosx_aarch64.zip]=efd832085677853eeb569a649832f68c16ff943c3964372acd78fcd3e2fbab6e
)

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CACHE="${ZULU_FX_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/openshare/zulu-fx}"
OUT="${OUT_DIR:-$REPO_ROOT/tools/install4j/jre-bundles}"

die() { echo "ERROR: $*" >&2; exit 1; }

[ "$(uname -s)-$(uname -m)" = Linux-x86_64 ] || die "runs on Linux x64 only (it executes the Linux JDK's jlink)"
for t in curl sha256sum tar gzip unzip; do command -v "$t" >/dev/null || die "$t not found"; done

read -r -a PLATFORMS <<< "${*:-$DEFAULT_PLATFORMS}"
for p in "${PLATFORMS[@]}"; do
  case "$p" in linux-amd64|windows-amd64|macos-amd64|macos-aarch64) ;; *) die "unknown platform: $p";; esac
done

mkdir -p "$CACHE" "$OUT"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Download (or reuse) one Azul file and check its pinned sha256. Prints the path.
fetch() {
  local suffix="$1" f="$CACHE/$ZULU-$1"
  if [ ! -f "$f" ] || ! echo "${SHA256[$suffix]}  $f" | sha256sum -c --status -; then
    echo "    downloading $ZULU-$suffix" >&2
    curl -fsS --retry 3 -o "$f.part" "$CDN/$ZULU-$suffix"
    mv "$f.part" "$f"
  fi
  echo "${SHA256[$suffix]}  $f" | sha256sum -c --status - \
    || die "$ZULU-$suffix does not match its pinned sha256; do not use it"
  echo "$f"
}

# Unpack an archive into a new directory and print the JDK root inside it (the single top folder).
unpack() {
  local archive="$1" dir="$WORK/$2"
  mkdir -p "$dir"
  case "$archive" in
    *.tar.gz) tar -xzf "$archive" -C "$dir" ;;
    *.zip)    unzip -q "$archive" -d "$dir" ;;
  esac
  local tops=( "$dir"/* )
  [ "${#tops[@]}" -eq 1 ] && [ -d "${tops[0]}" ] || die "unexpected layout in $(basename "$archive")"
  echo "${tops[0]}"
}

# The release file's value for a key, CR-stripped (Windows release files have CRLF line ends).
release_value() { tr -d '\r' < "$1" | sed -n "s/^$2=\"\(.*\)\"$/\1/p"; }

check_release() {
  local rel="$1" what="$2"
  [ "$(release_value "$rel" IMPLEMENTOR_VERSION)" = "$EXPECT_IMPLEMENTOR" ] || die "$what: not $EXPECT_IMPLEMENTOR"
  [ "$(release_value "$rel" JAVA_VERSION)" = "$BUNDLE_VERSION" ] || die "$what: not Java $BUNDLE_VERSION"
  release_value "$rel" MODULES | grep -qw javafx.web || die "$what: no JavaFX modules"
}

# Pack a directory's contents as an install4j bundle: entries at the top level, root-owned,
# reproducible (sorted, fixed mtime, no gzip timestamp).
pack() {
  local src="$1" name="$2"
  ( cd "$src" && tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='2026-11-17 00:00:00Z' -cf - -- * ) \
    | gzip -n -9 > "$OUT/$name.part"
  mv "$OUT/$name.part" "$OUT/$name"
}

# The Linux FX JDK: the linux-amd64 bundle's source, and the jlink that links the Windows bundle.
LINUX_JDK=""
case " ${PLATFORMS[*]} " in
  *" linux-amd64 "*|*" windows-amd64 "*)
    archive="$(fetch linux_x64.tar.gz)"
    LINUX_JDK="$(unpack "$archive" linux)"
    check_release "$LINUX_JDK/release" "linux_x64"
    ;;
esac

jlink_all() {
  local jdk="$1" dest="$2" modules
  modules="$(release_value "$jdk/release" MODULES | tr ' ' ',')"
  "$LINUX_JDK/bin/jlink" --module-path "$jdk/jmods" --add-modules "$modules" --output "$dest" 2>&1 \
    | { grep -v 'Using incubator modules' || true; } >&2
  [ -x "$dest/bin/java" ] || [ -f "$dest/bin/java.exe" ] || die "jlink produced no runtime in $dest"
}

for p in "${PLATFORMS[@]}"; do
  name="$p-$BUNDLE_VERSION.tar.gz"
  echo "==> $name"
  case "$p" in
    linux-amd64)
      jlink_all "$LINUX_JDK" "$WORK/out-$p"
      pack "$WORK/out-$p" "$name"
      ;;
    windows-amd64)
      archive="$(fetch win_x64.zip)"
      jdk="$(unpack "$archive" win)"
      check_release "$jdk/release" "win_x64"
      jlink_all "$jdk" "$WORK/out-$p"
      restored=0
      while IFS= read -r -d '' f; do
        rel="${f#"$WORK/out-$p/"}"
        [ -f "$jdk/$rel" ] || die "windows: $rel has no original in Azul's JDK"
        cp "$jdk/$rel" "$f"
        chmod 755 "$f"
        restored=$((restored + 1))
      done < <(find "$WORK/out-$p" -type f \( -iname '*.dll' -o -iname '*.exe' \) -print0)
      [ "$restored" -gt 0 ] || die "windows: no .dll/.exe files found"
      echo "    restored Azul's signed originals of $restored .dll/.exe files"
      pack "$WORK/out-$p" "$name"
      ;;
    macos-amd64|macos-aarch64)
      arch="${p#macos-}"; [ "$arch" = amd64 ] && arch=x64
      archive="$(fetch "macosx_$arch.zip")"
      jdk="$(unpack "$archive" "mac-$arch")"
      check_release "$jdk/Contents/Home/release" "macosx_$arch"
      rm -f "$jdk/Contents/Home/lib/server/classes.jsa"
      pack "$jdk" "$name"
      ;;
  esac
  echo "    $(sha256sum "$OUT/$name" | cut -d' ' -f1)  $OUT/$name"
done
