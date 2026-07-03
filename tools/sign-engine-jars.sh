#!/usr/bin/env bash
#
# sign-engine-jars.sh — EV-sign the OpenShare Engine client-side jars with the
# SafeNet eToken (Sectigo EV) over PKCS#11, so Swing-admin launchers (MCAL, etc.)
# that verify jar signatures against their bundled truststore will trust them.
#
# Mirrors the proven signing flow in mirthsync-gateway-plugin/build.sh (same
# token, alias, TSA, cert-chain assembly). Run this on the Linux signing machine
# with the eToken inserted, AFTER the Ant build produces server/setup and BEFORE
# the admin-console plugin is bundled into extensions/ (the plugin is already
# EV-signed by its own build.sh — don't re-sign it here).
#
# Usage:  tools/sign-engine-jars.sh [SETUP_DIR]
#         SETUP_DIR defaults to server/setup (relative to repo root).
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Signing configuration (keep in sync with mirthsync-gateway-plugin/build.sh)
# ---------------------------------------------------------------------------
SAFENET_PKCS11_LIB="/usr/lib64/libIDPrimePKCS11.so"
SAFENET_ALIAS="Sectigo_20260129130704"
SECTIGO_TSA_URL="http://timestamp.sectigo.com"
SECTIGO_INTERMEDIATE_URL="http://crt.sectigo.com/SectigoPublicCodeSigningCAEVR36.crt"
SECTIGO_CROSS_ROOT_URL="https://sectigo.tbs-certificats.com/SectigoPublicCodeSigningRootCrossAAA.crt"
SIG_ALG="SHA256withRSA"
DIGEST_ALG="SHA-256"

# BouncyCastle jars ship pre-signed by BC and already carry the security
# manifest attributes — do NOT modify their manifests (matches the Ant build).
BC_EXCLUDE_GLOB='bc*.jar'

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SETUP_DIR="${1:-$REPO_ROOT/server/setup}"
CLIENT_LIB="$SETUP_DIR/client-lib"
EXTENSIONS="$SETUP_DIR/extensions"

PKCS11_CFG=""
CERTCHAIN_FILE=""
MANIFEST_FILE=""
cleanup() { rm -f "$PKCS11_CFG" "$CERTCHAIN_FILE" "$MANIFEST_FILE" 2>/dev/null || true; }
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
[ -d "$CLIENT_LIB" ] || { echo "ERROR: $CLIENT_LIB not found — run the Ant build first." >&2; exit 1; }
for tool in jarsigner keytool jar openssl curl pkcs11-tool; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: '$tool' not on PATH." >&2; exit 1; }
done
if [ ! -f "$SAFENET_PKCS11_LIB" ] || \
   ! pkcs11-tool --module "$SAFENET_PKCS11_LIB" --list-slots 2>&1 | grep -q "token label"; then
  echo "ERROR: SafeNet eToken not detected ($SAFENET_PKCS11_LIB). Insert + unlock the token." >&2
  exit 1
fi

# If the admin-console plugin has already been bundled, refuse — it is signed by
# its own build and must not be re-signed here.
if [ -d "$EXTENSIONS/admin-console" ]; then
  echo "ERROR: extensions/admin-console is present. Sign engine jars BEFORE bundling" >&2
  echo "       the plugin (it is already EV-signed by mirthsync-gateway-plugin/build.sh)." >&2
  exit 1
fi

# ---------------------------------------------------------------------------
# Unlock token + assemble the full cert chain (leaf + intermediate + cross-root)
# ---------------------------------------------------------------------------
PKCS11_CFG="$(mktemp /tmp/pkcs11-safenet.XXXXXX.cfg)"
cat > "$PKCS11_CFG" <<EOF
name = SafeNet
library = $SAFENET_PKCS11_LIB
slot = 1
EOF

read -rsp "Enter SafeNet token PIN: " PKCS11_STOREPASS; echo
keytool -list -keystore NONE -storetype PKCS11 \
  -providerClass sun.security.pkcs11.SunPKCS11 -providerArg "$PKCS11_CFG" \
  -storepass "$PKCS11_STOREPASS" >/dev/null 2>&1 \
  || { echo "ERROR: token PIN rejected or token inaccessible." >&2; exit 1; }

echo "Assembling certificate chain..."
CERTCHAIN_FILE="$(mktemp /tmp/certchain.XXXXXX.pem)"
keytool -exportcert -keystore NONE -storetype PKCS11 \
  -providerClass sun.security.pkcs11.SunPKCS11 -providerArg "$PKCS11_CFG" \
  -storepass "$PKCS11_STOREPASS" -alias "$SAFENET_ALIAS" -rfc > "$CERTCHAIN_FILE" 2>/dev/null
_tmp="$(mktemp)"
if curl -sf "$SECTIGO_INTERMEDIATE_URL" -o "$_tmp"; then
  openssl x509 -in "$_tmp" -inform DER >> "$CERTCHAIN_FILE" 2>/dev/null || true
fi
if curl -sf "$SECTIGO_CROSS_ROOT_URL" -o "$_tmp"; then
  openssl x509 -in "$_tmp" -inform DER >> "$CERTCHAIN_FILE" 2>/dev/null \
    || openssl x509 -in "$_tmp" >> "$CERTCHAIN_FILE" 2>/dev/null || true
fi
rm -f "$_tmp"

# Branded Web Start / applet security manifest (matches OIE's custom_manifest.mf
# shape; Application-Name rebranded).
MANIFEST_FILE="$(mktemp /tmp/openshare-manifest.XXXXXX.mf)"
cat > "$MANIFEST_FILE" <<'EOF'
Permissions: all-permissions
Codebase: *
Application-Name: OpenShare Engine
EOF

# ---------------------------------------------------------------------------
# Sign helpers
# ---------------------------------------------------------------------------
add_manifest_attrs() {   # $1 = jar ; skip BouncyCastle (already has them)
  case "$(basename "$1")" in $BC_EXCLUDE_GLOB) return 0 ;; esac
  jar umf "$MANIFEST_FILE" "$1"
}

sign_one() {             # $1 = jar ; retry for flaky TSA (mirrors Ant retrycount=5)
  local jar="$1" attempt=1
  until jarsigner -keystore NONE -storetype PKCS11 \
          -providerClass sun.security.pkcs11.SunPKCS11 -providerArg "$PKCS11_CFG" \
          -storepass "$PKCS11_STOREPASS" \
          -certchain "$CERTCHAIN_FILE" -tsa "$SECTIGO_TSA_URL" \
          -sigalg "$SIG_ALG" -digestalg "$DIGEST_ALG" \
          "$jar" "$SAFENET_ALIAS" >/dev/null 2>&1; do
    if [ "$attempt" -ge 5 ]; then echo "  FAILED: $jar" >&2; return 1; fi
    attempt=$((attempt+1)); sleep 2
  done
}

# ---------------------------------------------------------------------------
# Run: manifest-mod + sign every client-lib and (built-in) extension jar
# ---------------------------------------------------------------------------
mapfile -d '' JARS < <(find "$CLIENT_LIB" "$EXTENSIONS" -name '*.jar' -print0 2>/dev/null)
echo "Signing ${#JARS[@]} jars with $SAFENET_ALIAS ..."
count=0
for jar in "${JARS[@]}"; do
  add_manifest_attrs "$jar"
  sign_one "$jar"
  count=$((count+1))
  printf '\r  signed %d/%d' "$count" "${#JARS[@]}"
done
echo

# ---------------------------------------------------------------------------
# Verify (fail hard if any jar is not validly signed by our cert)
# ---------------------------------------------------------------------------
echo "Verifying signatures..."
fail=0
for jar in "${JARS[@]}"; do
  if ! jarsigner -verify -strict "$jar" >/dev/null 2>&1; then
    echo "  NOT VERIFIED: $jar" >&2; fail=1
  fi
done
[ "$fail" -eq 0 ] || { echo "ERROR: one or more jars failed verification." >&2; exit 1; }
echo "OK: all ${#JARS[@]} jars signed and verified with the EV certificate."
