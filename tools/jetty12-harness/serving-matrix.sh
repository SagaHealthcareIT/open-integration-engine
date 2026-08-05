#!/usr/bin/env bash
#
# Jetty 12 migration Stage 0 serving matrix (see docs/Marketplace/Jetty12-Migration-Plan.md in the
# project-mdds-llm workspace repo, section 4).
#
# Hits every endpoint MirthWebServer assembles against a RUNNING engine and prints one stable line
# per endpoint: status code, content type, and body size. Capture the output on a Jetty 9.4 install
# as the baseline, re-run after each migration stage, and diff — any drift in status, type, or
# (for static content) size is a serving regression.
#
#   ./serving-matrix.sh https://localhost:8443 > baseline-9.4.txt        # before migration
#   ./serving-matrix.sh https://localhost:8443 > after-stage2.txt        # after
#   diff baseline-9.4.txt after-stage2.txt
#
# Notes:
# - TLS verification is disabled (-k): engine dev installs use a self-signed cert.
# - Some endpoints legitimately 404 on a given install (no installers staged, no javadocs, no
#   webapps). That is fine — the baseline records it, and the diff still catches drift.
# - Sizes of dynamic endpoints (error pages, JNLP with timestamps) can vary run to run; the diff
#   columns that matter everywhere are STATUS and CONTENT-TYPE. SIZE is authoritative only for
#   static resources.

set -u

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <base-url> [extra-path ...]" >&2
    echo "  e.g. $0 https://localhost:8443" >&2
    exit 2
fi

BASE_URL="${1%/}"
shift

PATHS=(
    # public_html root context
    "/"
    # WebStart servlet + client-lib / extension-lib resource contexts
    "/webstart.jnlp"
    "/webstart/client-lib/mirth-client.jar"
    "/webstart/client-lib/mirth-client-core.jar"
    "/webstart/extensions/libs/"
    # javadocs context (directory listing enabled)
    "/javadocs/"
    # Administrator Launcher installer contexts
    "/launcher/macos"
    "/launcher/linux"
    "/launcher/windows"
    "/launcher/windows-x64"
    # API servlet context + Swagger
    "/api/"
    "/api/openapi.json"
    "/api/openapi.yaml"
    "/apiexamples/"
    # a known-bogus path — pins the not-found behavior of the handler chain itself
    "/jetty12-harness-definitely-missing"
)

# Extra paths from the command line (e.g. a deployed webapp, versioned API paths)
PATHS+=("$@")

printf '%-50s %-8s %-40s %s\n' "PATH" "STATUS" "CONTENT-TYPE" "SIZE"

for path in "${PATHS[@]}"; do
    result=$(curl -sk -o /dev/null -w '%{http_code} %{content_type} %{size_download}' --max-time 30 "${BASE_URL}${path}" 2>/dev/null) \
        || result="CONNECT-FAILED - -"
    status="${result%% *}"
    rest="${result#* }"
    content_type="${rest% *}"
    size="${rest##* }"
    printf '%-50s %-8s %-40s %s\n' "$path" "$status" "${content_type:--}" "$size"
done
