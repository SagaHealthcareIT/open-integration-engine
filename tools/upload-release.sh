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
# that every medium the build made is there (one per media id in the marker),
# belongs to this version and matches its .sha256, and that an existing tag
# points at the commit the media were built from.
#
# A new release is created as a DRAFT (tag openshare-v<engine_version>, at that
# commit) and published only once every medium, its .sha256 and the marker are
# on it with the right digests. A draft is invisible to the publish workflows
# and to anything that takes the newest openshare-v* release, so nobody sees a
# half-uploaded release. Each medium goes up before its .sha256, the marker
# last. A file whose digest on the release already matches the local file is
# skipped, so re-running after a dropped connection resumes with the file that
# was in flight. The release is published with --latest=false so it never takes
# the "Latest" badge from the upstream-mirror tooling in this shared repo.
#
# Re-uploading to a release that is already published replaces its assets in
# place: gh's --clobber deletes an asset before uploading its replacement.
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
MEDIA_IDS="$(prov media_ids)"

[[ "$OPENSHARE_COMMIT" =~ ^[0-9a-f]{40}$ ]] || die "$MARKER has no valid openshare_commit"
[ -n "$ENGINE_VERSION" ] && [ -n "$UPSTREAM_TAG" ] || die "$MARKER has no engine_version or upstream_tag"
[ -n "$MEDIA_IDS" ] || die "$MARKER has no media_ids"
[ "$WORKTREE_CLEAN" = true ] \
  || die "this build was made from a tree with uncommitted changes or untracked files (worktree_clean=${WORKTREE_CLEAN:-missing}); commit, rebuild, then upload"
[ "$ENGINE_VERSION" != "${UPSTREAM_TAG#v}" ] \
  || die "this is a dev build ($ENGINE_VERSION, no RELEASE_VERSION); rebuild with e.g. RELEASE_VERSION=${UPSTREAM_TAG#v}-1"
TAG="openshare-v$ENGINE_VERSION"

# --- The media: all of them, this version's, unchanged since the build -----
shopt -s nullglob
MEDIA=( "$BUILD_OUT"/openshare_*.exe "$BUILD_OUT"/openshare_*.sh "$BUILD_OUT"/openshare_*.dmg \
        "$BUILD_OUT"/openshare_*.zip "$BUILD_OUT"/openshare_*.tar.gz )
shopt -u nullglob
# install4j makes one file per media id, so a missing one (moved away to test
# it, say) must stop the upload rather than publish a release without it.
IFS=, read -ra IDS <<< "$MEDIA_IDS"
[ ${#MEDIA[@]} -eq ${#IDS[@]} ] \
  || die "$BUILD_OUT has ${#MEDIA[@]} media, but the build made ${#IDS[@]} (media_ids=$MEDIA_IDS); put the missing ones back or rebuild"
# install4j names media openshare_<platform>_<version with dots as underscores>
# plus -aarch/-x86 (macOS) and/or -with-JRE.
VER_IN_NAME="${ENGINE_VERSION//./_}"
for m in "${MEDIA[@]}"; do
  name="$(basename "$m")"
  case "$name" in
    *_"$VER_IN_NAME".* | *_"$VER_IN_NAME"-with-JRE.* | \
    *_"$VER_IN_NAME"-aarch.* | *_"$VER_IN_NAME"-aarch-with-JRE.* | \
    *_"$VER_IN_NAME"-x86.* | *_"$VER_IN_NAME"-x86-with-JRE.*) ;;
    *) die "$name is not a $ENGINE_VERSION medium (left over from another build?)" ;;
  esac
  [ -f "$m.sha256" ] || die "$name has no .sha256 beside it"
  ( cd "$BUILD_OUT" && sha256sum --quiet -c "$name.sha256" ) >/dev/null 2>&1 \
    || die "$name no longer matches its .sha256; it changed after the build"
done

# --- The release: at the commit the media were built from ------------------
# Any failed lookup stops the upload; only a definite "no such tag" lets it go on.
gh api "repos/$ENGINE_REPO/commits/$OPENSHARE_COMMIT" --jq .sha >/dev/null \
  || die "could not find commit ${OPENSHARE_COMMIT:0:9} on $ENGINE_REPO; push it first"
# The commit refs/tags/$TAG points at (dereferencing an annotated tag), or
# nothing when there is no such tag.
tag_commit() {
  local ref type sha
  ref="$(gh api "repos/$ENGINE_REPO/git/matching-refs/tags/$TAG" \
          --jq ".[] | select(.ref == \"refs/tags/$TAG\") | \"\(.object.type) \(.object.sha)\"")" || return 1
  [ -n "$ref" ] || return 0
  read -r type sha <<< "$ref"
  if [ "$type" = tag ]; then
    sha="$(gh api "repos/$ENGINE_REPO/git/tags/$sha" --jq .object.sha)" || return 1
  fi
  echo "$sha"
}
TAG_COMMIT="$(tag_commit)" || die "could not look up tag $TAG on $ENGINE_REPO"
[ -z "$TAG_COMMIT" ] || [ "$TAG_COMMIT" = "$OPENSHARE_COMMIT" ] \
  || die "tag $TAG points at ${TAG_COMMIT:0:9}, but these media were built from ${OPENSHARE_COMMIT:0:9}"

if RELEASE="$(gh release view "$TAG" --repo "$ENGINE_REPO" --json isDraft,targetCommitish \
                --jq '"\(.isDraft) \(.targetCommitish)"' 2>/dev/null)"; then
  read -r IS_DRAFT TARGET <<< "$RELEASE"
  # A draft has no tag yet; publishing it creates the tag at its target.
  if [ "$IS_DRAFT" = true ] && [ "$TARGET" != "$OPENSHARE_COMMIT" ]; then
    die "draft release $TAG targets $TARGET, not the build commit ${OPENSHARE_COMMIT:0:9}; delete the draft and re-run"
  fi
else
  NOTES_ARG=( --notes "OpenShare Engine ${ENGINE_VERSION} — Open Integration Engine ${UPSTREAM_TAG} (${UPSTREAM_COMMIT:0:9}) with OpenShare branding. EV-signed installers; admin-console plugin delivered as a download-time sidecar." )
  if [ -n "${RELEASE_NOTES:-}" ]; then
    [ -f "$RELEASE_NOTES" ] || die "RELEASE_NOTES file not found: $RELEASE_NOTES"
    NOTES_ARG=( --notes-file "$RELEASE_NOTES" )
  fi
  echo "==> Creating draft release $TAG on $ENGINE_REPO at ${OPENSHARE_COMMIT:0:9}"
  gh release create "$TAG" --repo "$ENGINE_REPO" --draft \
    --target "$OPENSHARE_COMMIT" \
    --title "OpenShare Engine ${ENGINE_VERSION}" \
    "${NOTES_ARG[@]}" >/dev/null
  IS_DRAFT=true
fi

# --- Upload, skipping what is already there --------------------------------
# GitHub records a sha256 digest for every asset; compare against it, not a
# remote .sha256, so a skipped file is known to hold these exact bytes.
assets() { gh release view "$TAG" --repo "$ENGINE_REPO" --json assets --jq '.assets[] | "\(.name)\t\(.digest // "")"'; }
digest_of() { awk -F'\t' -v n="$1" '$1 == n { print $2 }' <<< "$ASSETS"; }
local_digest() { echo "sha256:$(sha256sum "$1" | cut -d' ' -f1)"; }
upload() {
  if [ "$(digest_of "$(basename "$1")")" = "$(local_digest "$1")" ]; then
    echo "    $(basename "$1"): already on the release, skipping"
    return 0
  fi
  echo "    $(basename "$1") ($(( $(stat -c %s "$1") / 1048576 )) MB)"
  gh release upload "$TAG" --repo "$ENGINE_REPO" --clobber "$1" \
    || die "upload of $(basename "$1") failed; re-run to resume"
}

ASSETS="$(assets)"
echo "==> Uploading ${#MEDIA[@]} media to $ENGINE_REPO @ $TAG"
for m in "${MEDIA[@]}"; do
  upload "$m"
  upload "$m.sha256"
done
upload "$MARKER"

# --- Every file there, byte for byte, before anyone can see the release ----
ASSETS="$(assets)"
for f in "${MEDIA[@]}" "${MEDIA[@]/%/.sha256}" "$MARKER"; do
  [ "$(digest_of "$(basename "$f")")" = "$(local_digest "$f")" ] \
    || die "$(basename "$f") on release $TAG does not match the local file; re-run to upload it again"
done
if [ "$IS_DRAFT" = true ]; then
  echo "==> Publishing $TAG"
  gh release edit "$TAG" --repo "$ENGINE_REPO" --draft=false --latest=false >/dev/null
fi

echo "==> Release $TAG has all ${#MEDIA[@]} media. Next: dispatch publish-engine-to-s3.yml (dev)"
echo "    then, after dev validation, publish-engine-to-s3-prod.yml (prod) with tag=$TAG"
echo "    and publish-engine-to-github-public.yml (public download, no plugin). Make the"
echo "    source public first (the release may have created the tag on origin only):"
echo "      git fetch origin tag $TAG && git push saga-public refs/tags/$TAG"
