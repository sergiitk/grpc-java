#!/usr/bin/env bash

set -eo pipefail

# TODO(sergiitk): Set in job/build
export GITHUB_REPOSITORY="${GITHUB_REPOSITORY:-grpc-java}"

run_safe() {
  # Run command end report its exit code.
  # Don't terminate the script if the code is negative.
  local exit_code=-1
  "$@" || exit_code=$?
  echo "Exit code: ${exit_code}"
}

# Capture VM version info in the log.
if [[ -f /VERSION ]]; then
  cat /VERSION
fi
run_safe lsb_release -a

# Export secrets needed for the test driver.
readonly PRIVATE_API_KEY=$(cat "${KOKORO_KEYSTORE_DIR}/73836_grpc_xds_interop_tests_gcp_alpha_apis_key")
export PRIVATE_API_KEY

# Kokoro provides pyenv, so use it instead `python -m venv`
echo "Setup pyenv environment"
eval "$(pyenv init -)"
eval "$(pyenv virtualenv-init -)"
echo "Activating python virtual environment"
pyenv virtualenv 3.6.1 k8s_xds_test_runner
pyenv local k8s_xds_test_runner
pyenv activate k8s_xds_test_runner

# Enable debug output after secrets exported and noisy pyenv activated
set -x

# Kokoro clones repo to ${KOKORO_ARTIFACTS_DIR}/github/${GITHUB_REPOSITORY}
readonly GITHUB_DIR="${KOKORO_ARTIFACTS_DIR}/github"
readonly SRC_DIR="${GITHUB_DIR}/${GITHUB_REPOSITORY}"

# Test artifacts dir: xml reports, logs, etc.
readonly ARTIFACTS_DIR="${KOKORO_ARTIFACTS_DIR}/artifacts"
# Folders after $ARTIFACTS_DIR reported as target name
readonly TEST_XML_OUTPUT_DIR="${ARTIFACTS_DIR}/${KOKORO_JOB_NAME}"
mkdir -p "${ARTIFACTS_DIR}" "${TEST_XML_OUTPUT_DIR}"

# Report extra information about the job via sponge properties
readonly GIT_ORIGIN_URL=$(git -C "${SRC_DIR}" remote get-url origin)
readonly GIT_COMMIT_SHORT=$(git -C "${SRC_DIR}" rev-parse --short HEAD)
# CSV format: "property_name","property_value"
# Bump TESTS_FORMAT_VERSION when reported test name changed enough to when it
# makes more sense to discard previous test results from a testgrid board.
# Use GIT_ORIGIN_URL to exclude test runs executed against repo forks from
# testgrid reports.
cat >"${KOKORO_ARTIFACTS_DIR}/custom_sponge_config.csv" <<-EOF
  "TESTS_FORMAT_VERSION","2"
  "TESTGRID_EXCLUDE","1"
  "GIT_ORIGIN_URL","${GIT_ORIGIN_URL}"
  "GIT_COMMIT_SHORT","${GIT_COMMIT_SHORT}"
EOF
echo "Sponge properties:"
cat "${KOKORO_ARTIFACTS_DIR}/custom_sponge_config.csv"

# gcloud requires python, so this should be executed after pyenv setup
echo "Update gcloud components:"
gcloud -q components update

# Java test app
# TODO(sergiitk): this will be different for each language
readonly TEST_APP_BUILD_OUT_DIR="${SRC_DIR}/interop-testing/build/install/grpc-interop-testing"
readonly IMAGE_BUILD_DIR="${SRC_DIR}/buildscripts/xds-k8s"
readonly IMAGE_BUILD_SKIP="${IMAGE_BUILD_SKIP:-0}"
if [ "${IMAGE_BUILD_SKIP}" -eq "0" ]; then
  echo "Building Java test app"
  cd "${SRC_DIR}"
  ./gradlew --no-daemon grpc-interop-testing:installDist -x test -PskipCodegen=true -PskipAndroid=true --console=plain
  # Test test app binaries
  run_safe "${TEST_APP_BUILD_OUT_DIR}/bin/xds-test-client" --help
  run_safe "${TEST_APP_BUILD_OUT_DIR}/bin/xds-test-server" --help

  # Build image
  cd "${IMAGE_BUILD_DIR}"
  gcloud -q components install skaffold
  gcloud -q auth configure-docker
  cp -rv "${TEST_APP_BUILD_OUT_DIR}" "${IMAGE_BUILD_DIR}"
  skaffold build -v info
else
  echo "Skipping Java test app build"
fi

# Test driver
# TODO(sergiitk): to be moved out as it's common for all languages
readonly TEST_DRIVER_REPO="https://github.com/grpc/grpc.git"
readonly TEST_DRIVER_REPO_BRANCH="${TEST_DRIVER_REPO_BRANCH:-master}"
readonly TEST_DRIVER_REPO_DIR="${GITHUB_DIR}/grpc"
readonly TEST_DRIVER_DIR="${TEST_DRIVER_REPO_DIR}/tools/run_tests/xds_k8s_test_driver"

echo "Download test driver source"
git clone -b "${TEST_DRIVER_REPO_BRANCH}" --depth=1 "${TEST_DRIVER_REPO}" "${TEST_DRIVER_REPO_DIR}"

# Test driver installation:
# https://github.com/grpc/grpc/tree/master/tools/run_tests/xds_k8s_test_driver#installation
echo "Configure GKE cluster access"
readonly GKE_CLUSTER_NAME="interop-test-psm-sec1-us-central1"
readonly GKE_CLUSTER_ZONE="us-central1-a"
gcloud container clusters get-credentials "${GKE_CLUSTER_NAME}" --zone "${GKE_CLUSTER_ZONE}"
readonly KUBE_CONTEXT="$(kubectl config current-context)"

echo "Install python dependencies"
cd "${TEST_DRIVER_DIR}"
pip install -r requirements.txt
echo "Installed Python packages:"
pip list

echo "Generate python code from grpc.testing protos:"
cd "${TEST_DRIVER_REPO_DIR}"
readonly PROTO_SOURCE_DIR=src/proto/grpc/testing
python3 -m grpc_tools.protoc \
  --proto_path=. \
  --python_out="${TEST_DRIVER_DIR}" \
  --grpc_python_out="${TEST_DRIVER_DIR}" \
  "${PROTO_SOURCE_DIR}/test.proto" \
  "${PROTO_SOURCE_DIR}/messages.proto" \
  "${PROTO_SOURCE_DIR}/empty.proto"

# Test driver usage:
# https://github.com/grpc/grpc/tree/master/tools/run_tests/xds_k8s_test_driver#basic-usage
echo "Running tests"
cd "${TEST_DRIVER_DIR}"

# Run baseline tests
python -m tests.baseline_test \
  --flagfile="${TEST_DRIVER_DIR}/config/grpc-testing.cfg" \
  --kube_context="${KUBE_CONTEXT}" \
  --server_image="gcr.io/grpc-testing/xds-k8s-test-server-java:latest" \
  --client_image="gcr.io/grpc-testing/xds-k8s-test-client-java:latest" \
  --xml_output_file="${TEST_XML_OUTPUT_DIR}/baseline_test/sponge_log.xml" \
  --force_cleanup

# Run security tests
python -m tests.security_test \
  --flagfile="${TEST_DRIVER_DIR}/config/grpc-testing.cfg" \
  --kube_context="${KUBE_CONTEXT}" \
  --server_image="gcr.io/grpc-testing/xds-k8s-test-server-java:latest" \
  --client_image="gcr.io/grpc-testing/xds-k8s-test-client-java:latest" \
  --xml_output_file="${TEST_XML_OUTPUT_DIR}/security_test/sponge_log.xml" \
  --force_cleanup
