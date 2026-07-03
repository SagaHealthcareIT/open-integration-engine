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
# Signing configuration
#
# The Sectigo EV key lives on a SafeNet eToken 5110. We drive it through the
# OpenSC PKCS#11 module (packaged: `opensc`), NOT the proprietary SafeNet client
# — OpenSC enumerates the token, exposes the key to Java's SunPKCS11 provider as
# alias "Certificate 1" in slot 0x4, and signs SHA256withRSA (verified).
# To use the vendor lib instead, set PKCS11_MODULE=/usr/lib64/libIDPrimePKCS11.so,
# PKCS11_SLOT=1, SIGNING_ALIAS=Sectigo_20260129130704.
# ---------------------------------------------------------------------------
PKCS11_MODULE="/usr/lib64/opensc-pkcs11.so"
PKCS11_SLOT="4"                       # SafeNet eToken cert slot (0x4 = "Saga IT, LLC")
SIGNING_ALIAS="Certificate 1"         # SunPKCS11-OpenSC alias for the EV key
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
if [ ! -f "$PKCS11_MODULE" ] || \
   ! pkcs11-tool --module "$PKCS11_MODULE" --list-slots 2>&1 | grep -q "token label"; then
  echo "ERROR: token not detected via $PKCS11_MODULE. Insert the eToken (and check pcscd)." >&2
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
PKCS11_CFG="$(mktemp /tmp/pkcs11-opensc.XXXXXX.cfg)"
cat > "$PKCS11_CFG" <<EOF
name = OpenSC
library = $PKCS11_MODULE
slot = $PKCS11_SLOT
EOF

# PIN from the environment (set by release-signed.sh so it prompts only once),
# otherwise prompt interactively.
if [ -n "${TOKEN_PIN:-}" ]; then
  PKCS11_STOREPASS="$TOKEN_PIN"
else
  read -rsp "Enter eToken PIN: " PKCS11_STOREPASS; echo
fi
keytool -list -keystore NONE -storetype PKCS11 \
  -providerClass sun.security.pkcs11.SunPKCS11 -providerArg "$PKCS11_CFG" \
  -storepass "$PKCS11_STOREPASS" >/dev/null 2>&1 \
  || { echo "ERROR: token PIN rejected or token inaccessible." >&2; exit 1; }

echo "Assembling certificate chain..."
CERTCHAIN_FILE="$(mktemp /tmp/certchain.XXXXXX.pem)"
keytool -exportcert -keystore NONE -storetype PKCS11 \
  -providerClass sun.security.pkcs11.SunPKCS11 -providerArg "$PKCS11_CFG" \
  -storepass "$PKCS11_STOREPASS" -alias "$SIGNING_ALIAS" -rfc > "$CERTCHAIN_FILE" 2>/dev/null
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
          "$jar" "$SIGNING_ALIAS" >/dev/null 2>&1; do
    if [ "$attempt" -ge 5 ]; then echo "  FAILED: $jar" >&2; return 1; fi
    attempt=$((attempt+1)); sleep 2
  done
}

# ---------------------------------------------------------------------------
# Run: manifest-mod + sign every client-lib and (built-in) extension jar
# ---------------------------------------------------------------------------
mapfile -d '' JARS < <(find "$CLIENT_LIB" "$EXTENSIONS" -name '*.jar' -print0 2>/dev/null)
echo "Signing ${#JARS[@]} jars with '$SIGNING_ALIAS' ..."
count=0
for jar in "${JARS[@]}"; do
  add_manifest_attrs "$jar"
  sign_one "$jar"
  count=$((count+1))
  printf '\r  signed %d/%d' "$count" "${#JARS[@]}"
done
echo

# ---------------------------------------------------------------------------
# Verify: our EV certificate is a valid signer on every jar.
#
# Deliberately NOT `jarsigner -verify -strict`. Third-party jars — notably
# BouncyCastle's bc*.jar — ship pre-signed by their own publisher, and (exactly
# like the upstream Ant build) we add our signature ON TOP rather than stripping
# theirs: bcprov's original BC signature is what the JVM's JCE framework
# validates at runtime, so stripping it would break the crypto provider. Those
# jars are therefore legitimately dual-signed. `-strict` insists on validating
# EVERY signer's certificate chain against the local truststore and fails on
# BouncyCastle's foreign chain — which is not ours to vouch for. That is a false
# failure: our signature on those jars is valid.
#
# So we assert exactly what matters for the Swing/Web Start gate: each jar
# verifies cryptographically (integrity intact) AND carries a valid signature
# from our EV certificate. A jar left unsigned, or signed only by a foreign
# cert, still fails hard here.
# ---------------------------------------------------------------------------
echo "Verifying signatures (our EV cert is a valid signer on every jar)..."
fail=0
for jar in "${JARS[@]}"; do
  # Non-strict verify: exit 0 iff every signed entry's signature is intact.
  # (Chain-trust warnings for a foreign co-signer do not fail this.)
  # -verbose:summary is required for -certs to actually print the signer
  # subjects we grep for below.
  if ! out="$(jarsigner -verify -verbose:summary -certs "$jar" 2>/dev/null)"; then
    echo "  NOT VERIFIED (signature integrity): $jar" >&2; fail=1; continue
  fi
  case "$out" in
    *"jar verified"*) : ;;
    *) echo "  NOT VERIFIED (jarsigner did not confirm): $jar" >&2; fail=1; continue ;;
  esac
  # Require OUR EV leaf (subject O="Saga IT, LLC") among the signers, so an
  # unsigned or foreign-only-signed jar can never slip through.
  echo "$out" | grep -q "Saga IT" \
    || { echo "  MISSING EV SIGNER (not signed by our cert): $jar" >&2; fail=1; }
done
[ "$fail" -eq 0 ] || { echo "ERROR: one or more jars are not validly signed by the EV certificate." >&2; exit 1; }
echo "OK: all ${#JARS[@]} jars carry a valid signature from the EV certificate."
