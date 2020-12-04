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
RUNNER_SKIP_BUILD="${RUNNER_SKIP_BUILD:-1}"

# Language-specific repo
SRC_DIR="${GITHUB_DIR}/grpc-java"
TEST_APP_BUILD_DIR="${SRC_DIR}/interop-testing/build"
TEST_APP_DIR="${TEST_APP_BUILD_DIR}/install/grpc-interop-testing"

# Runner
# todo(sergiitk): replace with real values
RUNNER_REPO="https://github.com/sergiitk/grpc.git"
RUNNER_REPO_BRANCH="xds_test_driver-wip"
RUNNER_REPO_DIR="${GITHUB_DIR}/grpc"
RUNNER_DIR="${RUNNER_REPO_DIR}/tools/run_tests/xds_test_driver"
RUNNER_SKAFFOLD_DIR="${RUNNER_DIR}/docker"

# Create artifacts
GIT_ORIGIN_URL=$(git -C "${SRC_DIR}" remote get-url origin)

mkdir -p "${ARTIFACTS_DIR}"
# Add Sponge properties
cat > "${ARTIFACTS_DIR}/custom_sponge_config.csv" << EOF
"TESTS_FORMAT_VERSION","0"
"GIT_ORIGIN_URL","${GIT_ORIGIN_URL}"
"TESTGRID_EXCLUDE","1"
EOF
echo "Added sponge properties:"
cat "${ARTIFACTS_DIR}/custom_sponge_config.csv"

# Checkout driver source
echo "Downloading test runner source"
git clone -b "${RUNNER_REPO_BRANCH}" --depth=1 "${RUNNER_REPO}" "${RUNNER_REPO_DIR}"

# Building lang-specific interop tests
if [ "${RUNNER_SKIP_BUILD}" -eq "0" ]; then
  echo "Building Java test app"
  cd "${SRC_DIR}"
  ./gradlew --no-daemon grpc-interop-testing:installDist -x test -PskipCodegen=true -PskipAndroid=true --console=plain
  # Test test app binaries
  run_safe "${TEST_APP_DIR}/bin/xds-test-client" --help
  run_safe "${TEST_APP_DIR}/bin/xds-test-server" --help
else
  echo "Skipping Java test app build"
fi

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

# Build image
if [ "${RUNNER_SKIP_BUILD}" -eq "0" ]; then
  echo "Building test app Docker image"
  cd "${RUNNER_SKAFFOLD_DIR}"
  gcloud -q components install skaffold
  gcloud -q auth configure-docker
  cp -rv "${TEST_APP_BUILD_DIR}" "${RUNNER_SKAFFOLD_DIR}"
  skaffold build -v info
  echo "Docker images:"
  docker images list
else
  echo "Skipping test app Docker image build"
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
# todo(sergiitk): replace cluster name
echo "Authenticating on K8S cluster"
gcloud container clusters get-credentials sergiitk-interop-dev --zone us-central1-a

# Run the test
echo "Running tests"
cd "${RUNNER_DIR}"
ARTIFACTS_TEST_NAME='xds-security-test'
mkdir -p "${ARTIFACTS_XML_DIR}/${ARTIFACTS_TEST_NAME}"
python -m tests.security_test \
  --flagfile="${RUNNER_DIR}/config/grpc-testing.cfg" \
  --kube_context_name="$(kubectl config current-context)" \
  --namespace=sergii-psm-test \
  --server_image="gcr.io/grpc-testing/xds-k8s-test-server-java:latest" \
  --client_image="gcr.io/grpc-testing/xds-k8s-test-client-java:latest" \
  -v 0 --logger_levels=framework:DEBUG,__main__:DEBUG \
  --xml_output_file="${ARTIFACTS_XML_DIR}/${ARTIFACTS_TEST_NAME}/sponge_log.xml"
