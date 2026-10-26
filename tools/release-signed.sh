#!/usr/bin/env bash
#
# release-signed.sh — one command to produce fully-signed OpenShare Engine
# installers on the Linux signing machine (eToken inserted).
#
# It orchestrates the whole pipeline so you don't run the steps by hand:
#   1. build the branded engine (Gradle, unsigned jars)
#   2. EV-sign the engine client jars via OpenSC/PKCS#11   (tools/sign-engine-jars.sh)
#   3. build + Authenticode-sign the installers/launchers  (install4jc)
#   4. verify a built Windows installer's signature
#
# You are prompted for the token PIN twice: once up front for the jar signing
# (step 2 reads it from the environment), and once by install4jc itself for
# the installer signing (step 3). The PIN is never passed as a command-line
# argument, where any local user could read it from ps.
#
# The admin-console plugin is NOT baked into the media. The console's engine
# download delivers the current signed plugin ZIP alongside the installer in
# the wrapper ZIP, and the installer unpacks it into extensions/ at install
# time — so plugin releases never require rebuilding these installers. The
# provenance marker (server/build/BUNDLED_PLUGIN.txt) records
# plugin_sidecar=true, which the publish workflows turn into the manifest's
# pluginSidecar flag that tells the API to include the plugin sidecar.
#
# Versioning tracks upstream releases. Our release branches (`openshare` for
# 4.7.0-x, `release/4.6` for 4.6.0-x) are BASED ON an upstream release tag
# (vX.Y.Z[-rcN]) + our commits — never on upstream main — so the nearest
# reachable v-tag names this build. The release version (e.g. 4.7.0-1) is
# stamped into the media via `install4jc --release`, mirroring upstream's own
# process: their in-repo version strings stay plain X.Y.Z too (which is also
# what the engine reports at runtime, so the plugin's exact mirthVersion gating
# is unaffected). Never pass Gradle's -Pversion: it changes the version the
# engine reports and breaks that gating. Our release tag is
# openshare-v<version>, and the provenance marker records the upstream tag +
# commit it corresponds to.
#
# Usage:
#   tools/release-signed.sh [media-ids]
#
# Env overrides:
#   MEDIA_IDS           install4j media ids (default: Windows + Linux, no-JRE + with-JRE)
#   INSTALL4JC          path to install4jc (default: ~/opt/install4j13.1.1/bin/install4jc). It
#                       must be at least the install4j version that saved the config
#   ALLOW_OLDER_INSTALL4J=1  build with an older install4jc anyway
#   RELEASE_VERSION     the version to ship, e.g. RELEASE_VERSION=4.7.0-1. Required
#                       with PUBLISH_RELEASE=1; a dev build defaults to the
#                       upstream tag's plain X.Y.Z
#   SKIP_ENGINE_BUILD=1 reuse an existing server/setup (skip the Gradle build)
#   SKIP_TESTS=1        build without running the unit tests (-PdisableTests=true)
#   PUBLISH_RELEASE=1   after signing, attach media + .sha256 + provenance to a
#                       GitHub release (tag openshare-v<engine-version>) that the
#                       publish-engine-to-s3{,-prod}.yml and
#                       publish-engine-to-github-public.yml workflows pull from
#   ENGINE_REPO         release repo (default: SagaHealthcareIT/open-integration-engine-internal)
#   RELEASE_TAG         override the release tag (default: openshare-v<engine-version>)
#   RELEASE_NOTES       path to a release-notes markdown file (default: a one-line stub)
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL4JC="${INSTALL4JC:-$HOME/opt/install4j13.1.1/bin/install4jc}"
CONFIG="$REPO_ROOT/tools/install4j/oie-installer-config.install4j"
SETUP="$REPO_ROOT/server/setup"
BUILD_OUT="$REPO_ROOT/server/build"
OPENSC="/usr/lib64/opensc-pkcs11.so"

# Windows x64/arm64 + Linux installers (no-JRE and with-JRE), plus the archive
# media for manual installs (44 = Unix tar.gz, 153/167 = Windows x64/ARM zip —
# upstream OIE ships these too). Archives run no installer actions, so the
# console wrapper README gives them manual plugin/bootstrap steps instead.
# macOS (43,116,130,133) is omitted until Apple notarization is set up — add
# those ids once it is.
MEDIA_IDS="${1:-${MEDIA_IDS:-27,119,42,121,136,125,44,153,167}}"

# GitHub-release handoff (opt-in). When PUBLISH_RELEASE=1, the signed media +
# their .sha256 sidecars + the provenance marker are attached to a GitHub
# release on ENGINE_REPO under an `openshare-v<engine-version>` tag. The
# publish-engine-to-s3{,-prod}.yml workflows then pull from that release. The
# tag is namespaced so it never collides with the upstream mirror's
# `X.Y.Z-<sha>` prereleases in the same repo. Off by default so a plain build
# (dev iteration) never creates a release.
PUBLISH_RELEASE="${PUBLISH_RELEASE:-0}"
ENGINE_REPO="${ENGINE_REPO:-SagaHealthcareIT/open-integration-engine-internal}"
RELEASE_TAG="${RELEASE_TAG:-}"   # default: openshare-v$RELEASE_VERSION

die() { echo "ERROR: $*" >&2; exit 1; }

# --- Preflight -------------------------------------------------------------
[ -x "$INSTALL4JC" ] || die "install4jc not found/executable: $INSTALL4JC (set INSTALL4JC=)"
[ -f "$CONFIG" ]     || die "install4j config missing: $CONFIG"
# The config records the install4j version that last saved it. An older install4jc
# still builds it, with a warning, but may not understand what the newer version
# wrote, and signing and JRE bundling were never tested that way. Compare
# major.minor only: install4jc 13.0 (build 13007) reports itself as plain "13.0".
CONFIG_I4J_VER="$(grep -m1 -oE '<install4j version="[0-9]+\.[0-9]+' "$CONFIG" | grep -oE '[0-9]+\.[0-9]+$' || true)"
I4JC_VER="$("$INSTALL4JC" --version 2>/dev/null | sed -nE 's/^install4j version ([0-9]+\.[0-9]+).*/\1/p' | head -1 || true)"
[ -n "$CONFIG_I4J_VER" ] && [ -n "$I4JC_VER" ] \
  || die "cannot read the install4j versions (config: ${CONFIG_I4J_VER:-?}, install4jc: ${I4JC_VER:-?})"
if [ "$(printf '%s\n' "$CONFIG_I4J_VER" "$I4JC_VER" | sort -V | head -1)" != "$CONFIG_I4J_VER" ]; then
  [ "${ALLOW_OLDER_INSTALL4J:-0}" = 1 ] \
    || die "install4jc is $I4JC_VER, but the install4j config was saved by $CONFIG_I4J_VER: install $CONFIG_I4J_VER or newer and set INSTALL4JC=, or set ALLOW_OLDER_INSTALL4J=1"
  echo "WARNING: building with install4jc $I4JC_VER; the config was saved by $CONFIG_I4J_VER (ALLOW_OLDER_INSTALL4J=1)" >&2
fi
[ -x "$REPO_ROOT/gradlew" ] || die "Gradle wrapper missing: $REPO_ROOT/gradlew"
for t in java jarsigner keytool jar pkcs11-tool openssl curl python3; do
  command -v "$t" >/dev/null 2>&1 || die "'$t' not on PATH"
done
pkcs11-tool --module "$OPENSC" --list-slots 2>&1 | grep -q "Saga IT, LLC" \
  || die "eToken not detected via OpenSC — insert + unlock it (pcscd running?)"

# --- Derive the version from the upstream release we're based on -----------
UPSTREAM_TAG="$(git -C "$REPO_ROOT" describe --tags --abbrev=0 --match 'v[0-9]*' HEAD 2>/dev/null || true)"
[ -n "$UPSTREAM_TAG" ] || die "cannot derive the upstream release tag (git describe --match 'v[0-9]*' found nothing) —
       fetch upstream tags (git fetch upstream --tags) and make sure openshare is based on a release tag"
UPSTREAM_COMMIT="$(git -C "$REPO_ROOT" rev-list -n1 "$UPSTREAM_TAG")"
OPENSHARE_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD)"

# A published release records OPENSHARE_COMMIT as the source it was built
# from, and the public GitHub publisher checks the public tag against it. That
# only holds if the tree has no uncommitted changes and no untracked files (an
# untracked source, conf or html file is built into the media just like a
# committed one; build output is gitignored). Dev builds may be dirty.
if [ "$PUBLISH_RELEASE" = 1 ] && [ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=all)" ]; then
  die "uncommitted changes or untracked files — a published release must be built from a clean commit:
$(git -C "$REPO_ROOT" status --short --untracked-files=all)"
fi
# Gradle puts every jar under a module's lib/ directory on the classpath and
# copies server/lib into server/setup, so a stray jar there (for example one
# left over from a 4.6.0-x checkout, which vendored its jars) would ship in the
# media. For every build, refuse anything under the lib/ trees that is not
# committed as is, including gitignored files, except the build's own copies
# (mirth-*/donkey-model jars, extension *-shared jars, server/lib/donkey).
STRAY_LIBS="$(git -C "$REPO_ROOT" status --porcelain --ignored --untracked-files=all -- \
    client/lib command/lib donkey/lib generator/lib server/lib \
  | grep -vE '^!! (client/lib/(donkey-model|mirth-client-core|mirth-crypto|mirth-vocab|[^/]+-shared)\.jar|command/lib/(donkey-model|mirth-client-core|mirth-crypto)\.jar|server/lib/donkey/.*)$' || true)"
[ -z "$STRAY_LIBS" ] || die "files under a lib/ directory that are not committed as is would be built into the media:
$STRAY_LIBS"
if [ "$PUBLISH_RELEASE" = 1 ] && [ -z "${RELEASE_VERSION:-}" ]; then
  die "set RELEASE_VERSION (e.g. RELEASE_VERSION=${UPSTREAM_TAG#v}-1) for a published release"
fi
RELEASE_VERSION="${RELEASE_VERSION:-${UPSTREAM_TAG#v}}"

# The in-repo install4j version must be the plain X.Y.Z prefix of what we ship
# (a mismatch means the branch base and config diverged — fix before releasing).
CONFIG_VER="$(grep '<application ' "$CONFIG" | grep -oE ' version="[^"]*"' | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
case "$RELEASE_VERSION" in
  "$CONFIG_VER"|"$CONFIG_VER"-*) ;;
  *) die "RELEASE_VERSION '$RELEASE_VERSION' does not extend the install4j config version '$CONFIG_VER'";;
esac

echo "==> Version: $RELEASE_VERSION (upstream $UPSTREAM_TAG @ ${UPSTREAM_COMMIT:0:9}, openshare @ ${OPENSHARE_COMMIT:0:9})"
echo "==> Building media: $MEDIA_IDS"
read -rsp "eToken PIN: " TOKEN_PIN; echo
export TOKEN_PIN

# --- 1. Engine build (unsigned jars) --------------------------------------
if [ "${SKIP_ENGINE_BUILD:-0}" = 1 ]; then
  echo "==> [1/4] Skipping engine build (SKIP_ENGINE_BUILD=1); reusing $SETUP"
  [ -d "$SETUP/client-lib" ] || die "no existing server/setup — cannot skip the build"
else
  # clean runs first, in its own invocation, so nothing from an earlier build
  # can reach the media. It deletes server/build, where install4jc writes the
  # media, so it must never run after step 3. Jar signing is left to step 2;
  # no -Pversion (see Versioning above). The build never sees the PIN (env -u),
  # and --no-daemon keeps no Gradle process alive after this script ends.
  GRADLE_FLAGS=( --no-daemon -PdisableSigning=true )
  [ "${SKIP_TESTS:-0}" = 1 ] && GRADLE_FLAGS+=( -PdisableTests=true )
  echo "==> [1/4] Building engine (gradlew clean; gradlew build ${GRADLE_FLAGS[*]})"
  ( cd "$REPO_ROOT" && env -u TOKEN_PIN ./gradlew --no-daemon clean \
      && env -u TOKEN_PIN ./gradlew build "${GRADLE_FLAGS[@]}" )
fi

# The plugin is delivered as a download-time sidecar, never baked into the
# media. A reused setup dir (SKIP_ENGINE_BUILD=1) may still carry a bake from
# a pre-sidecar run — strip it so no media ever ships a stale plugin.
if [ -d "$SETUP/extensions/admin-console" ]; then
  echo "    removing previously-baked extensions/admin-console (plugin is a sidecar now)"
  rm -rf "$SETUP/extensions/admin-console"
fi

# --- 2. EV-sign the engine jars --------------------------------------------
# Signing 216 jars (each TSA-timestamped) is slow; SKIP_ENGINE_SIGN=1 reuses the
# signatures already in $SETUP so a failed later step can be resumed without a
# full re-sign. Only safe when the jars really are already EV-signed.
if [ "${SKIP_ENGINE_SIGN:-0}" = 1 ]; then
  echo "==> [2/4] Skipping engine jar signing (SKIP_ENGINE_SIGN=1); reusing signatures in $SETUP"
  _probe="$SETUP/client-lib/mirth-client.jar"
  if [ -f "$_probe" ] && jarsigner -verify -verbose:summary -certs "$_probe" 2>/dev/null | grep -q "Saga IT"; then
    echo "    verified $_probe already carries our EV signature — good"
  else
    die "SKIP_ENGINE_SIGN=1 but $_probe is missing or not EV-signed — run a full sign first"
  fi
else
  echo "==> [2/4] EV-signing engine client + extension jars (OpenSC/PKCS#11)"
  "$REPO_ROOT/tools/sign-engine-jars.sh" "$SETUP"
fi

# --- 3. Build + sign installers -------------------------------------------
# --release stamps the upstream-derived version into media filenames +
# installer metadata (same mechanism upstream uses to get -rcN media out of a
# plain-X.Y.Z config).
# No --win-keystore-password: it would put the PIN in the process list for the
# whole build. Without it, install4jc prompts for the PIN itself.
unset TOKEN_PIN
echo "==> [3/4] Building + signing installers (version $RELEASE_VERSION)"
echo "    install4jc will ask for the eToken PIN again (Windows code signing)"
"$INSTALL4JC" --release "$RELEASE_VERSION" -b "$MEDIA_IDS" "$CONFIG"

# --- 4. Verify a built Windows installer's Authenticode signature ----------
echo "==> [4/4] Verifying signature"
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

# --- Provenance marker ------------------------------------------------------
# plugin_sidecar=true tells the publish workflows (and any human reading the
# release) that this media carries NO baked plugin — the console's download
# pairs it with the current signed admin-console.zip at download time. The
# filename is kept from the bundled era so the workflows can read either
# generation of release from one code path.
mkdir -p "$BUILD_OUT"
MARKER="$BUILD_OUT/BUNDLED_PLUGIN.txt"
{
  echo "engine_version=$RELEASE_VERSION"
  echo "upstream_tag=$UPSTREAM_TAG"
  echo "upstream_commit=$UPSTREAM_COMMIT"
  echo "openshare_commit=$OPENSHARE_COMMIT"
  echo "plugin_sidecar=true"
  echo "media_ids=$MEDIA_IDS"
  echo "built_utc=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
} > "$MARKER"
echo "==> Provenance written to $MARKER:"
sed 's/^/    /' "$MARKER"

# --- Per-media checksum sidecars ------------------------------------------
# The publish workflow verifies each S3 object against a <media>.sha256 sidecar,
# so emit one next to every built installer (relative name inside the file so
# `sha256sum -c` works from the same dir).
echo "==> Writing .sha256 sidecars"
shopt -s nullglob
MEDIA_FILES=( "$BUILD_OUT"/openshare_*.exe "$BUILD_OUT"/openshare_*.sh \
              "$BUILD_OUT"/openshare_*.dmg "$BUILD_OUT"/openshare_*.zip \
              "$BUILD_OUT"/openshare_*.tar.gz )
shopt -u nullglob
[ "${#MEDIA_FILES[@]}" -gt 0 ] || die "no openshare_* media found in $BUILD_OUT"
for m in "${MEDIA_FILES[@]}"; do
  ( cd "$(dirname "$m")" && sha256sum "$(basename "$m")" > "$(basename "$m").sha256" )
done

# --- GitHub-release handoff (opt-in) --------------------------------------
if [ "$PUBLISH_RELEASE" = 1 ]; then
  command -v gh >/dev/null 2>&1 || die "'gh' (GitHub CLI) required for PUBLISH_RELEASE=1"
  TAG="${RELEASE_TAG:-openshare-v${RELEASE_VERSION}}"
  case "$TAG" in
    openshare-v[0-9]*) ;;
    *) die "RELEASE_TAG must be 'openshare-v<semver>' (got '$TAG')";;
  esac

  UPLOADS=( "${MEDIA_FILES[@]}" )
  for m in "${MEDIA_FILES[@]}"; do UPLOADS+=( "${m}.sha256" ); done
  UPLOADS+=( "$MARKER" )   # BUNDLED_PLUGIN.txt provenance

  echo "==> Publishing GitHub release $TAG on $ENGINE_REPO"
  if gh release view "$TAG" --repo "$ENGINE_REPO" >/dev/null 2>&1; then
    echo "    release exists — uploading assets with --clobber"
    gh release upload "$TAG" --repo "$ENGINE_REPO" --clobber "${UPLOADS[@]}"
  else
    # --latest=false so an OpenShare release never steals the "Latest" badge
    # from the upstream-mirror tooling in this shared repo. Promote by hand if
    # desired. Notes come from RELEASE_NOTES (file path) or a stub.
    NOTES_ARG=( --notes "OpenShare Engine ${RELEASE_VERSION} — Open Integration Engine ${UPSTREAM_TAG} (${UPSTREAM_COMMIT:0:9}) with OpenShare branding. EV-signed installers; admin-console plugin delivered as a download-time sidecar." )
    [ -n "${RELEASE_NOTES:-}" ] && [ -f "${RELEASE_NOTES}" ] && NOTES_ARG=( --notes-file "$RELEASE_NOTES" )
    # --target: if the tag doesn't exist yet, create it at the commit these
    # media were built from, not at the tip of the default branch. (That
    # commit must already be pushed to origin.)
    gh release create "$TAG" --repo "$ENGINE_REPO" \
      --target "$OPENSHARE_COMMIT" \
      --title "OpenShare Engine ${RELEASE_VERSION}" \
      --latest=false "${NOTES_ARG[@]}" \
      "${UPLOADS[@]}"
  fi
  echo "==> Release $TAG published. Next: dispatch publish-engine-to-s3.yml (dev)"
  echo "    then, after dev validation, publish-engine-to-s3-prod.yml (prod) with tag=$TAG"
  echo "    and publish-engine-to-github-public.yml (public download, no plugin). Make the"
  echo "    source public first (the release may have created the tag on origin only):"
  echo "      git fetch origin tag $TAG && git push saga-public refs/tags/$TAG"
fi

echo
echo "==> Done. Signed artifacts in $BUILD_OUT:"
ls -1sh "$BUILD_OUT"/openshare_*.{exe,sh,dmg} 2>/dev/null || true
