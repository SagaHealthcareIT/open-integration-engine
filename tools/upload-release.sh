#!/usr/bin/env bash
#
# upload-release.sh — attach a signed OpenShare Engine build to its GitHub release.
#
# tools/release-signed.sh builds and signs the media into server/build and stops
# there; this is the separate upload step. It needs no token, only gh and
# sha256sum: the upload takes hours on a slow link, and the media are often
# tested before they are published.
#
# It reads the build's provenance marker (server/build/BUNDLED_PLUGIN.txt) and
# refuses a build that can't be a release: a dev version (built without
# RELEASE_VERSION) or one built from a tree with uncommitted changes. It checks
# every media file against its .sha256 and that each one belongs to this
# version, creates the release (tag openshare-v<engine_version>, at the commit
# the media were built from) if it doesn't exist, and refuses a tag that points
# anywhere else. Then it uploads each media file followed by its .sha256, and
# the provenance marker last. A media file whose .sha256 on the release already
# matches (and whose size does) is skipped, so re-running after a dropped
# connection resumes with the file that was in flight.
#
# The release is created with --latest=false so it never takes the "Latest"
# badge from the upstream-mirror tooling in this shared repo.
#
# Usage:
#   tools/upload-release.sh
#
# Env overrides:
#   ENGINE_REPO     release repo (default: SagaHealthcareIT/open-integration-engine-internal)
#   RELEASE_NOTES   release-notes markdown file, used only when the release is
#                   created (default: a one-line stub; edit the release body later)
#   BUILD_OUT       where release-signed.sh left the media (default: server/build)
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_OUT="${BUILD_OUT:-$REPO_ROOT/server/build}"
ENGINE_REPO="${ENGINE_REPO:-SagaHealthcareIT/open-integration-engine-internal}"
MARKER="$BUILD_OUT/BUNDLED_PLUGIN.txt"

die() { echo "ERROR: $*" >&2; exit 1; }
prov() { sed -n "s/^$1=//p" "$MARKER" | head -1; }

for cmd in gh sha256sum; do
  command -v "$cmd" >/dev/null 2>&1 || die "'$cmd' is required"
done
[ -f "$MARKER" ] || die "no build to upload: $MARKER is missing (run tools/release-signed.sh first)"

# --- What was built, and can it be a release? ------------------------------
ENGINE_VERSION="$(prov engine_version)"
UPSTREAM_TAG="$(prov upstream_tag)"
UPSTREAM_COMMIT="$(prov upstream_commit)"
OPENSHARE_COMMIT="$(prov openshare_commit)"
WORKTREE_CLEAN="$(prov worktree_clean)"

[[ "$OPENSHARE_COMMIT" =~ ^[0-9a-f]{40}$ ]] || die "$MARKER has no valid openshare_commit"
[ -n "$ENGINE_VERSION" ] && [ -n "$UPSTREAM_TAG" ] || die "$MARKER has no engine_version or upstream_tag"
[ "$WORKTREE_CLEAN" = true ] \
  || die "this build was made from a tree with uncommitted changes or untracked files (worktree_clean=${WORKTREE_CLEAN:-missing}); commit, rebuild, then upload"
[ "$ENGINE_VERSION" != "${UPSTREAM_TAG#v}" ] \
  || die "this is a dev build ($ENGINE_VERSION, no RELEASE_VERSION); rebuild with e.g. RELEASE_VERSION=${UPSTREAM_TAG#v}-1"
TAG="openshare-v$ENGINE_VERSION"

# --- The media: this version's, and unchanged since the build --------------
shopt -s nullglob
MEDIA=( "$BUILD_OUT"/openshare_*.exe "$BUILD_OUT"/openshare_*.sh "$BUILD_OUT"/openshare_*.dmg \
        "$BUILD_OUT"/openshare_*.zip "$BUILD_OUT"/openshare_*.tar.gz )
shopt -u nullglob
[ ${#MEDIA[@]} -gt 0 ] || die "no openshare_* media in $BUILD_OUT"
# install4j names media <prefix>_<version with dots as underscores>[-with-JRE].<ext>
VER_IN_NAME="${ENGINE_VERSION//./_}"
for m in "${MEDIA[@]}"; do
  name="$(basename "$m")"
  case "$name" in
    *_"$VER_IN_NAME".* | *_"$VER_IN_NAME"-with-JRE.*) ;;
    *) die "$name is not a $ENGINE_VERSION medium (left over from another build?)" ;;
  esac
  [ -f "$m.sha256" ] || die "$name has no .sha256 beside it"
  ( cd "$BUILD_OUT" && sha256sum --quiet -c "$name.sha256" ) >/dev/null 2>&1 \
    || die "$name no longer matches its .sha256; it changed after the build"
done

# --- The release: at the commit the media were built from ------------------
gh api "repos/$ENGINE_REPO/commits/$OPENSHARE_COMMIT" --jq .sha >/dev/null 2>&1 \
  || die "commit ${OPENSHARE_COMMIT:0:9} is not on $ENGINE_REPO; push it first"
if TAG_COMMIT="$(gh api "repos/$ENGINE_REPO/commits/$TAG" --jq .sha 2>/dev/null)"; then
  [ "$TAG_COMMIT" = "$OPENSHARE_COMMIT" ] \
    || die "tag $TAG points at ${TAG_COMMIT:0:9}, but these media were built from ${OPENSHARE_COMMIT:0:9}"
fi
if ! gh release view "$TAG" --repo "$ENGINE_REPO" >/dev/null 2>&1; then
  NOTES_ARG=( --notes "OpenShare Engine ${ENGINE_VERSION} — Open Integration Engine ${UPSTREAM_TAG} (${UPSTREAM_COMMIT:0:9}) with OpenShare branding. EV-signed installers; admin-console plugin delivered as a download-time sidecar." )
  if [ -n "${RELEASE_NOTES:-}" ]; then
    [ -f "$RELEASE_NOTES" ] || die "RELEASE_NOTES file not found: $RELEASE_NOTES"
    NOTES_ARG=( --notes-file "$RELEASE_NOTES" )
  fi
  echo "==> Creating release $TAG on $ENGINE_REPO at ${OPENSHARE_COMMIT:0:9}"
  gh release create "$TAG" --repo "$ENGINE_REPO" \
    --target "$OPENSHARE_COMMIT" \
    --title "OpenShare Engine ${ENGINE_VERSION}" \
    --latest=false "${NOTES_ARG[@]}" >/dev/null
fi

# --- Upload, skipping what is already there --------------------------------
ASSETS="$(gh release view "$TAG" --repo "$ENGINE_REPO" --json assets --jq '.assets[] | "\(.name)\t\(.size)"')"
echo "==> Uploading ${#MEDIA[@]} media to $ENGINE_REPO @ $TAG"
for m in "${MEDIA[@]}"; do
  name="$(basename "$m")"
  size="$(stat -c %s "$m")"
  remote_size="$(awk -F'\t' -v n="$name" '$1 == n { print $2 }' <<< "$ASSETS")"
  if [ "$remote_size" = "$size" ] \
      && awk -F'\t' -v n="$name.sha256" '$1 == n { found = 1 } END { exit !found }' <<< "$ASSETS"; then
    remote_sum="$(gh release download "$TAG" --repo "$ENGINE_REPO" --pattern "$name.sha256" --output - 2>/dev/null || true)"
    if [ "$remote_sum" = "$(cat "$m.sha256")" ]; then
      echo "    $name: already on the release, skipping"
      continue
    fi
  fi
  echo "    $name ($((size / 1048576)) MB)"
  gh release upload "$TAG" --repo "$ENGINE_REPO" --clobber "$m" \
    || die "upload of $name failed; re-run to resume"
  gh release upload "$TAG" --repo "$ENGINE_REPO" --clobber "$m.sha256" \
    || die "upload of $name.sha256 failed; re-run to resume"
done
gh release upload "$TAG" --repo "$ENGINE_REPO" --clobber "$MARKER" \
  || die "upload of $(basename "$MARKER") failed; re-run to resume"

echo "==> Release $TAG has all ${#MEDIA[@]} media. Next: dispatch publish-engine-to-s3.yml (dev)"
echo "    then, after dev validation, publish-engine-to-s3-prod.yml (prod) with tag=$TAG"
echo "    and publish-engine-to-github-public.yml (public download, no plugin). Make the"
echo "    source public first (the release may have created the tag on origin only):"
echo "      git fetch origin tag $TAG && git push saga-public refs/tags/$TAG"
