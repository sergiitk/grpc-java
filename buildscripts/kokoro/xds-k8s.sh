#!/usr/bin/env bash
set -eo pipefail

readonly GITHUB_REPOSITORY="grpc-java"
readonly CURRENT_DIR=$(dirname "$0")
# Intentional: source from the same dir as xds-k8s.sh
# shellcheck disable=SC1090
source "${CURRENT_DIR}/xds-k8s-install-test-driver.sh"

# Java test app
readonly TEST_APP_BUILD_OUT_DIR="${SRC_DIR}/interop-testing/build/install/grpc-interop-testing"
readonly IMAGE_BUILD_DIR="${SRC_DIR}/buildscripts/xds-k8s"
readonly IMAGE_BUILD_SKIP="${IMAGE_BUILD_SKIP:-0}"
if [ "${IMAGE_BUILD_SKIP}" -eq "0" ]; then
  echo "Building Java test app"
  cd "${SRC_DIR}"
  ./gradlew --no-daemon grpc-interop-testing:installDist -x test -PskipCodegen=true -PskipAndroid=true --console=plain
  # Test-run binaries
  run_safe "${TEST_APP_BUILD_OUT_DIR}/bin/xds-test-client" --help
  run_safe "${TEST_APP_BUILD_OUT_DIR}/bin/xds-test-server" --help

  # Build image
  cd "${IMAGE_BUILD_DIR}"
  gcloud -q components install skaffold
  gcloud -q auth configure-docker
  cp -rv "${TEST_APP_BUILD_OUT_DIR}" "${IMAGE_BUILD_DIR}"
  export IMAGE_TAG="sergiitk-test"
  skaffold build -v info
else
  echo "Skipping Java test app build"
fi

# Test driver usage:
# https://github.com/grpc/grpc/tree/master/tools/run_tests/xds_k8s_test_driver#basic-usage
echo "Running tests"
cd "${TEST_DRIVER_DIR}"

# Run baseline tests
#python -m tests.baseline_test \
#  --flagfile="config/grpc-testing.cfg" \
#  --kube_context="${KUBE_CONTEXT}" \
#  --server_image="gcr.io/grpc-testing/xds-k8s-test-server-java:latest" \
#  --client_image="gcr.io/grpc-testing/xds-k8s-test-client-java:latest" \
#  --xml_output_file="${TEST_XML_OUTPUT_DIR}/baseline_test/sponge_log.xml" \
#  --force_cleanup

# Run security tests
#python -m tests.security_test \
#  --flagfile="config/grpc-testing.cfg" \
#  --kube_context="${KUBE_CONTEXT}" \
#  --server_image="gcr.io/grpc-testing/xds-k8s-test-server-java:latest" \
#  --client_image="gcr.io/grpc-testing/xds-k8s-test-client-java:latest" \
#  --xml_output_file="${TEST_XML_OUTPUT_DIR}/security_test/sponge_log.xml" \
#  --force_cleanup
