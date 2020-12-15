#!/usr/bin/env bash

set -eo pipefail

run_safe() {
  local exit_code=-1
  "$@" || exit_code=$?
  echo "Exit code: ${exit_code}"
}

# Debug info
if [[ -f /VERSION ]]; then
  cat /VERSION
fi
run_safe lsb_release -a

# Export secrets
PRIVATE_API_KEY=$(cat "${KOKORO_KEYSTORE_DIR}/73836_grpc_xds_interop_tests_gcp_alpha_apis_key")
export PRIVATE_API_KEY

echo "Setup pyenv environment"
eval "$(pyenv init -)"
eval "$(pyenv virtualenv-init -)"

# Debugging
set -x

# Script start
echo "xDS interop tests on GKE"
GITHUB_DIR="${KOKORO_ARTIFACTS_DIR}/github"
ARTIFACTS_DIR="${KOKORO_ARTIFACTS_DIR}/artifacts"
ARTIFACTS_XML_DIR="${ARTIFACTS_DIR}/${KOKORO_JOB_NAME}"

# Language-specific repo
SRC_DIR="${GITHUB_DIR}/grpc-java"
TEST_APP_BUILD_OUT_DIR="${SRC_DIR}/interop-testing/build/install/grpc-interop-testing"
IMAGE_BUILD_DIR="${SRC_DIR}/buildscripts/xds-k8s"
IMAGE_BUILD_SKIP="${IMAGE_BUILD_SKIP:-1}"

# Runner
# todo(sergiitk): replace with real values
RUNNER_REPO="https://github.com/sergiitk/grpc.git"
RUNNER_REPO_BRANCH="xds_test_driver"
RUNNER_REPO_DIR="${GITHUB_DIR}/grpc"
RUNNER_DIR="${RUNNER_REPO_DIR}/tools/run_tests/xds_test_driver"

# Create artifacts
GIT_ORIGIN_URL=$(git -C "${SRC_DIR}" remote get-url origin)

mkdir -p "${ARTIFACTS_DIR}"
# Add Sponge properties
cat > "${KOKORO_ARTIFACTS_DIR}/custom_sponge_config.csv" << EOF
"TESTS_FORMAT_VERSION","0"
"GIT_ORIGIN_URL","${GIT_ORIGIN_URL}"
"TESTGRID_EXCLUDE","1"
EOF
echo "Added sponge properties:"
cat "${KOKORO_ARTIFACTS_DIR}/custom_sponge_config.csv"

# Checkout driver source
echo "Downloading test runner source"
git clone -b "${RUNNER_REPO_BRANCH}" --depth=1 "${RUNNER_REPO}" "${RUNNER_REPO_DIR}"

# Install test runner requirements
echo "Installing test runner requirements"
cd "${RUNNER_DIR}"
echo "Activating python virtual environment"
pyenv virtualenv 3.6.1 k8s_test_runner
pyenv local k8s_test_runner
pyenv activate k8s_test_runner
pip install -r requirements.txt
echo "Python packages installed:"
pip list
echo "Updating gcloud components:"
gcloud -q components update

# Building lang-specific interop tests
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

# Prepare generated Python code.
cd "${RUNNER_REPO_DIR}"
PROTO_SOURCE_DIR=src/proto/grpc/testing
python3 -m grpc_tools.protoc \
  --proto_path=. \
  --python_out="${RUNNER_DIR}" \
  --grpc_python_out="${RUNNER_DIR}" \
  "${PROTO_SOURCE_DIR}/test.proto" \
  "${PROTO_SOURCE_DIR}/messages.proto" \
  "${PROTO_SOURCE_DIR}/empty.proto"

# Authenticate on k8s
echo "Authenticating on K8S cluster"
GKE_CLUSTER_NAME="interop-test-psm-sec1-us-central1"
GKE_CLUSTER_ZONE="us-central1-a"
gcloud container clusters get-credentials "${GKE_CLUSTER_NAME}" --zone "${GKE_CLUSTER_ZONE}"
KUBE_CONTEXT="$(kubectl config current-context)"

# Run the test
echo "Running tests"
cd "${RUNNER_DIR}"

# Creating artifact dirs
TEST_OUT_DIR="${ARTIFACTS_XML_DIR}/xds-k8s-test"
mkdir -p "${TEST_OUT_DIR}"

# Run regular tests
python -m tests.baseline_test \
  --flagfile="${RUNNER_DIR}/config/grpc-testing.cfg" \
  --kube_context="${KUBE_CONTEXT}" \
  --server_image="gcr.io/grpc-testing/xds-k8s-test-server-java:latest" \
  --client_image="gcr.io/grpc-testing/xds-k8s-test-client-java:latest" \
  --xml_output_file="${TEST_OUT_DIR}/0_sponge_log.xml"

# Run security tests
python -m tests.security_test \
  --flagfile="${RUNNER_DIR}/config/grpc-testing.cfg" \
  --kube_context="${KUBE_CONTEXT}" \
  --server_image="gcr.io/grpc-testing/xds-k8s-test-server-java:latest" \
  --client_image="gcr.io/grpc-testing/xds-k8s-test-client-java:latest" \
  --xml_output_file="${TEST_OUT_DIR}/1_sponge_log.xml"
