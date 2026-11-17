#!/usr/bin/env bash
# Runs the Spock test suite in test/MatrixZdbSpec.groovy against the three drivers, inside Docker.
#
# The drivers are loaded into hubitat_ci (https://github.com/biocomp/hubitat_ci), a community mirror of the
# Hubitat driver sandbox and API. It compiles each driver with Hubitat-like restrictions and checks every
# Z-Wave class, field and factory method the drivers use against the real Hubitat API surface.
#
# Usage:   test/run-tests.sh
# Options: EXTRA_CA_CERT=/path/to/root-ca.pem  trust an extra CA inside the container
#                                              (for networks that intercept TLS)
set -euo pipefail

HUBITAT_CI_COMMIT=d3405cd11ea7ee967824b191801cf4149ba93f1c
IMAGE=eclipse-temurin:11-jdk

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")"
work="$(mktemp -d)"
container="matrix-zdb-test-$$"
trap 'docker rm -f "$container" >/dev/null 2>&1 || true; rm -rf "$work"' EXIT

echo "Fetching hubitat_ci ${HUBITAT_CI_COMMIT:0:7}"
ci="$work/hubitat_ci"
git init -q "$ci"
git -C "$ci" fetch -q --depth 1 https://github.com/biocomp/hubitat_ci.git "$HUBITAT_CI_COMMIT"
git -C "$ci" checkout -q FETCH_HEAD

# Patches that make hubitat_ci match the hub for these drivers:
# 1. On Hubitat every Z-Wave command class extends hubitat.zwave.Command; most of hubitat_ci's mirrors don't.
find "$ci/src/main/groovy/me/biocomp/hubitat_ci/api/device_api/zwave/commands" -name '*.groovy' -print0 |
    xargs -0 perl -pi -e 's/^trait (\w+) ?\{/trait $1 extends me.biocomp.hubitat_ci.api.device_api.zwave.Command {/'
# 2. Drivers can read `settings` on Hubitat; hubitat_ci only provides it to apps.
script_util="$ci/src/main/groovy/me/biocomp/hubitat_ci/util/ScriptUtil.groovy"
perl -0pi -e 's/(case "metaClass":\n\s*return scriptObject\.getMetaClass\(\)\n)/$1            case "settings":\n                return userSettingsMap\n/' "$script_util"
grep -q 'case "settings"' "$script_util" || { echo "Patching hubitat_ci failed" >&2; exit 1; }
# 3. Hubitat accepts command arguments as maps ([name: "Group*", type: "NUMBER", ...]); hubitat_ci only takes
#    type names. Validate a map argument by its type, so the command must still match a method in the driver.
metadata_reader="$ci/src/main/groovy/me/biocomp/hubitat_ci/device/metadata/DeviceMetadataReader.groovy"
perl -0pi -e 's/(void command\(String commandName, List parameterTypes\) \{\n)/$1        parameterTypes = parameterTypes.collect { it instanceof Map ? ((Map) it).type?.toString()?.toLowerCase() : it?.toString() } as List<String>\n/' "$metadata_reader"
grep -q 'it instanceof Map ?' "$metadata_reader" || { echo "Patching hubitat_ci failed" >&2; exit 1; }
# 4. hubitat_ci's capability list predates Flash and LevelPreset; add them as Hubitat documents them.
caps="$ci/src/main/groovy/me/biocomp/hubitat_ci/capabilities"
cat >> "$caps/AllCapabilities.groovy" <<'EOF'

interface Flash extends Capability
{
    abstract void flash(Number rateToFlash)
}

interface LevelPreset extends Capability
{
    static Map<String, CapabilityAttributeInfo> _internalAttributes = CapabilityAttributeInfo.makeList{
        number("levelPreset", min: 1, max: 100)
    }

    abstract void presetLevel(Number level)
}
EOF
perl -pi -e 's/^(\s*)ChangeLevel,$/$1ChangeLevel,\n$1Flash,\n$1LevelPreset,/' "$caps/Capabilities.groovy"
grep -q 'LevelPreset,' "$caps/Capabilities.groovy" || { echo "Patching hubitat_ci failed" >&2; exit 1; }
# 5. Print each test result.
printf '\ntest { testLogging { events "passed", "failed"; exceptionFormat "full" } }\n' >> "$ci/build.gradle"

mkdir -p "$ci/src/test/groovy/matrix" "$ci/drivers"
cp "$here/MatrixZdbSpec.groovy" "$ci/src/test/groovy/matrix/"
cp "$repo"/matrix_zdb*.groovy "$ci/drivers/"

# Files are copied into the container rather than mounted, so this also works with Colima and remote daemons.
docker create --name "$container" -v hubitat-ci-gradle:/root/.gradle -w /work \
    -e DRIVER_DIR=/work/drivers ${EXTRA_CA_CERT:+-e USE_SYSTEM_CA_CERTS=1} \
    "$IMAGE" ./gradlew test --tests 'matrix.MatrixZdbSpec' --no-daemon --console=plain >/dev/null
docker cp "$ci/." "$container:/work"
if [ -n "${EXTRA_CA_CERT:-}" ]; then
    mkdir "$work/certs"
    cp "$EXTRA_CA_CERT" "$work/certs/extra-ca.crt"
    docker cp "$work/certs/." "$container:/certificates"
fi

set +e
docker start -a "$container"
rc=$?
set -e
rm -rf "$here/report"
docker cp "$container:/work/build/reports/tests/test" "$here/report" >/dev/null 2>&1 && echo "HTML report: test/report/index.html"
exit $rc
