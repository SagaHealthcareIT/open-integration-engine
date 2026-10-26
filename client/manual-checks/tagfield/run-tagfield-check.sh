#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# SPDX-FileCopyrightText: 2026 Innovar Healthcare (BridgeLink, IRT-2431)
#
# Runs TagFieldCheck against a JavaFX-bearing JDK. See README.md.
#
# Usage: ./run-tagfield-check.sh [--classes] [/path/to/jdk-home]
#        Defaults to $JAVA_HOME. The JDK must bundle JavaFX (a "jdk+fx" build); a plain JDK
#        will fail at toolkit startup, which is a broken check, not a failed one.

set -euo pipefail

CHECK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT_DIR="$(cd "${CHECK_DIR}/../.." && pwd)"
# The Gradle build resolves the client's dependencies from Maven Central, so client/lib holds
# only a few of them; the assembled client-lib in server/setup has the full set.
CLIENT_LIB="$(cd "${CLIENT_DIR}/.." && pwd)/server/setup/client-lib"

USE_CLASSES=false
if [[ "${1:-}" == "--classes" ]]; then
    USE_CLASSES=true
    shift
fi
JDK_HOME="${1:-${JAVA_HOME:-}}"
if [[ -z "${JDK_HOME}" ]]; then
    echo "error: pass a JDK home as the first argument, or set JAVA_HOME." >&2
    exit 64
fi

# Azul ships both layouts: some bundles are flat, others nest under Contents/Home.
if [[ -x "${JDK_HOME}/Contents/Home/bin/java" ]]; then
    JDK_HOME="${JDK_HOME}/Contents/Home"
fi
if [[ ! -x "${JDK_HOME}/bin/java" ]]; then
    echo "error: no java executable under ${JDK_HOME}" >&2
    exit 64
fi

# Default to the built mirth-client.jar: every real launch loads the page from inside it, as
# jar: URLs, which is the scheme the July 2026 OpenJFX update stopped loading from loadContent
# pages. --classes runs against the compiled classes and resources instead (file: URLs), which
# does not exercise that.
if [[ "${USE_CLASSES}" == "true" ]]; then
    CLIENT_BUILT="${CLIENT_DIR}/build/classes/java/main"
    CLIENT_CP="${CLIENT_BUILT}:${CLIENT_DIR}/build/resources/main"
else
    CLIENT_BUILT="${CLIENT_DIR}/dist/mirth-client.jar"
    CLIENT_CP="${CLIENT_BUILT}"
fi
if [[ ! -e "${CLIENT_BUILT}" || ! -d "${CLIENT_LIB}" ]]; then
    echo "error: ${CLIENT_BUILT} or ${CLIENT_LIB} not found. Build first, from the repository root:" >&2
    echo "  ./gradlew build -PdisableSigning=true -PdisableTests=true" >&2
    exit 65
fi

CP="${CLIENT_CP}:${CLIENT_LIB}/*"
OUT="${CHECK_DIR}/out"

echo "--- compiling the check"
rm -rf "${OUT}"
mkdir -p "${OUT}"
"${JDK_HOME}/bin/javac" -nowarn -cp "${CP}" -d "${OUT}" "${CHECK_DIR}/TagFieldCheck.java"

echo "--- running against ${JDK_HOME}"
set +e
"${JDK_HOME}/bin/java" -cp "${OUT}:${CP}" TagFieldCheck
status=$?
set -e

echo "--- exit status ${status}"
exit "${status}"
