#!/usr/bin/env bash
#
# release-signed.sh — one command to produce fully-signed OpenShare Engine
# installers on the Linux signing machine (eToken inserted).
#
# It orchestrates the whole pipeline so you don't run the steps by hand:
#   1. build the branded engine (Ant, unsigned jars)
#   2. EV-sign the engine client jars via OpenSC/PKCS#11   (tools/sign-engine-jars.sh)
#   3. bundle the already-EV-signed admin-console plugin into extensions/
#   4. build + Authenticode-sign the installers/launchers  (install4jc)
#   5. verify a built Windows installer's signature
#
# You are prompted for the token PIN ONCE; it is reused for steps 2 and 4.
#
# The plugin source is either a published release tag (pulled from GitHub, the
# authoritative signed artifact) or a local zip:
#   tools/release-signed.sh v0.22.0            # pull admin-console-*-signed.zip from the release
#   tools/release-signed.sh ./admin-console.zip # use a local build (dev)
# A provenance marker (plugin version + sha256) is written to server/build/BUNDLED_PLUGIN.txt.
#
# Usage:
#   tools/release-signed.sh <plugin-tag|admin-console.zip> [media-ids]
#
# Env overrides:
#   MEDIA_IDS           install4j media ids (default: Windows + Linux, no-JRE + with-JRE)
#   INSTALL4JC          path to install4jc (default: ~/opt/install4j13.0/bin/install4jc)
#   PLUGIN_REPO         plugin GitHub repo (default: SagaHealthcareIT/mirthsync-gateway-plugin)
#   SKIP_ENGINE_BUILD=1 reuse an existing server/setup (skip the Ant build)
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL4JC="${INSTALL4JC:-$HOME/opt/install4j13.0/bin/install4jc}"
CONFIG="$REPO_ROOT/tools/install4j/oie-installer-config.install4j"
SETUP="$REPO_ROOT/server/setup"
BUILD_OUT="$REPO_ROOT/server/build"
OPENSC="/usr/lib64/opensc-pkcs11.so"

# Windows x64/arm64 + Linux, no-JRE and with-JRE. macOS (43,116,130,133) is omitted
# until Apple notarization is set up — add those ids once it is.
MEDIA_IDS="${2:-${MEDIA_IDS:-27,119,42,121,136,125}}"
PLUGIN_SOURCE="${1:-}"
PLUGIN_REPO="${PLUGIN_REPO:-SagaHealthcareIT/mirthsync-gateway-plugin}"

die() { echo "ERROR: $*" >&2; exit 1; }

# --- Preflight -------------------------------------------------------------
[ -n "$PLUGIN_SOURCE" ] || die "usage: tools/release-signed.sh <plugin-tag|admin-console.zip> [media-ids]
       plugin-tag e.g. v0.22.0 pulls admin-console-*-signed.zip from $PLUGIN_REPO;
       or pass a path to a local admin-console.zip"
[ -x "$INSTALL4JC" ] || die "install4jc not found/executable: $INSTALL4JC (set INSTALL4JC=)"
[ -f "$CONFIG" ]     || die "install4j config missing: $CONFIG"
for t in ant jarsigner keytool jar unzip pkcs11-tool openssl curl python3; do
  command -v "$t" >/dev/null 2>&1 || die "'$t' not on PATH"
done
pkcs11-tool --module "$OPENSC" --list-slots 2>&1 | grep -q "Saga IT, LLC" \
  || die "eToken not detected via OpenSC — insert + unlock it (pcscd running?)"

# --- Resolve the plugin: a local zip, or a published release tag -----------
WORKDIR="$(mktemp -d)"; trap 'rm -rf "$WORKDIR"' EXIT
if [ -f "$PLUGIN_SOURCE" ]; then
  PLUGIN_ZIP="$PLUGIN_SOURCE"
  PLUGIN_VERSION="local:$(basename "$PLUGIN_SOURCE")"
  echo "==> Plugin: local zip $PLUGIN_ZIP"
elif printf '%s' "$PLUGIN_SOURCE" | grep -qE '^v?[0-9]+\.[0-9]+\.[0-9]+$'; then
  command -v gh >/dev/null 2>&1 || die "'gh' (GitHub CLI) is required to pull a plugin release"
  TAG="$PLUGIN_SOURCE"; case "$TAG" in v*) ;; *) TAG="v$TAG" ;; esac
  echo "==> Plugin: downloading $TAG (admin-console-*-signed.zip) from $PLUGIN_REPO"
  gh release download "$TAG" --repo "$PLUGIN_REPO" \
     --pattern 'admin-console-*-signed.zip' --dir "$WORKDIR" 2>/dev/null \
     || die "could not download $TAG from $PLUGIN_REPO (release or signed asset missing?)"
  PLUGIN_ZIP="$(ls -1 "$WORKDIR"/admin-console-*-signed.zip 2>/dev/null | head -1)"
  [ -n "$PLUGIN_ZIP" ] || die "release $TAG has no admin-console-*-signed.zip asset"
  PLUGIN_VERSION="$TAG"
else
  die "plugin source '$PLUGIN_SOURCE' is neither an existing file nor a version tag (vX.Y.Z)"
fi

echo "==> Building media: $MEDIA_IDS"
read -rsp "eToken PIN: " TOKEN_PIN; echo
export TOKEN_PIN

# --- 1. Engine build (unsigned jars) --------------------------------------
if [ "${SKIP_ENGINE_BUILD:-0}" = 1 ]; then
  echo "==> [1/5] Skipping engine build (SKIP_ENGINE_BUILD=1); reusing $SETUP"
  [ -d "$SETUP/client-lib" ] || die "no existing server/setup — cannot skip the build"
else
  echo "==> [1/5] Building engine (ant -DdisableSigning=true)"
  ( cd "$REPO_ROOT/server" && ant -f mirth-build.xml -DdisableSigning=true )
fi

# --- 2. EV-sign the engine jars (before bundling the plugin) ---------------
echo "==> [2/5] EV-signing engine client + extension jars (OpenSC/PKCS#11)"
"$REPO_ROOT/tools/sign-engine-jars.sh" "$SETUP"

# --- 3. Bundle the already-signed plugin ----------------------------------
echo "==> [3/5] Bundling plugin from $PLUGIN_ZIP"
rm -rf "$SETUP/extensions/admin-console"
unzip -oq "$PLUGIN_ZIP" -d "$SETUP/extensions/"   # zip has top-level admin-console/
[ -d "$SETUP/extensions/admin-console" ] || die "plugin did not unpack to extensions/admin-console"
# Safety: the plugin's client jar must already be EV-signed, not self-signed.
_pcj="$SETUP/extensions/admin-console/admin-console-client.jar"
if [ -f "$_pcj" ]; then
  if jarsigner -verify -certs "$_pcj" 2>/dev/null | grep -q "Saga IT"; then
    echo "    plugin client jar is EV-signed (Saga IT) — good"
  else
    die "plugin client jar is NOT EV-signed (self-signed?). Rebuild the plugin with the token."
  fi
fi

# --- 4. Build + sign installers -------------------------------------------
echo "==> [4/5] Building + signing installers"
"$INSTALL4JC" --win-keystore-password "$TOKEN_PIN" -b "$MEDIA_IDS" "$CONFIG"
unset TOKEN_PIN

# --- 5. Verify a built Windows installer's Authenticode signature ----------
echo "==> [5/5] Verifying signature"
_exe="$(ls -1 "$BUILD_OUT"/openshare_windows-x64_*.exe 2>/dev/null | grep -v with-JRE | head -1 || true)"
if [ -n "$_exe" ]; then
  if python3 - "$_exe" > /tmp/_authcode.$$.der 2>/dev/null <<'PY'
import sys,struct
d=open(sys.argv[1],'rb').read()
pe=struct.unpack_from('<I',d,0x3C)[0]
opt=pe+24; magic=struct.unpack_from('<H',d,opt)[0]
rva,size=struct.unpack_from('<II',d,opt+(112 if magic==0x20b else 96)+4*8)
assert size>0, "unsigned"
cert=d[rva:rva+size]
sys.stdout.buffer.write(cert[8:8+struct.unpack_from('<I',cert,0)[0]-8])
PY
  then
    signer="$(openssl pkcs7 -inform DER -in /tmp/_authcode.$$.der -print_certs -noout 2>/dev/null | grep -m1 'Saga IT' || true)"
    ts="$(openssl asn1parse -inform DER -in /tmp/_authcode.$$.der 2>/dev/null | grep -c countersignature || true)"
    [ -n "$signer" ] && echo "    signed by: ${signer#subject=}" || echo "    WARNING: EV signer not found in signature"
    [ "${ts:-0}" -ge 1 ] && echo "    RFC-3161 timestamp present" || echo "    WARNING: no timestamp counter-signature"
  else
    echo "    WARNING: could not parse signature on $_exe (install4j reported signing OK)"
  fi
  rm -f /tmp/_authcode.$$.der
else
  echo "    (no Windows x64 media built — skipping signature check)"
fi

# --- Provenance marker: which plugin version this release bundled ----------
mkdir -p "$BUILD_OUT"
MARKER="$BUILD_OUT/BUNDLED_PLUGIN.txt"
ENGINE_VER="$(grep '<application ' "$CONFIG" | grep -oE ' version="[^"]*"' | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
{
  echo "engine_version=$ENGINE_VER"
  echo "plugin_version=$PLUGIN_VERSION"
  echo "plugin_repo=$PLUGIN_REPO"
  echo "plugin_zip=$(basename "$PLUGIN_ZIP")"
  echo "plugin_sha256=$(sha256sum "$PLUGIN_ZIP" | cut -d' ' -f1)"
  echo "media_ids=$MEDIA_IDS"
  echo "built_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
} > "$MARKER"
echo "==> Provenance written to $MARKER:"
sed 's/^/    /' "$MARKER"

echo
echo "==> Done. Signed artifacts in $BUILD_OUT:"
ls -1sh "$BUILD_OUT"/openshare_*.{exe,sh,dmg} 2>/dev/null || true
