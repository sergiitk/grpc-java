#!/usr/bin/env bash

set -euo pipefail

run_safe() {
  local exit_code=-1
  "$@" || exit_code=$?
  echo "Exit code: ${exit_code}"
}

# Debugging
set -x

# Debug info
if [[ -f /VERSION ]]; then
  cat /VERSION
fi
run_safe lsb_release -a

# Script start
echo "xDS interop tests on GKE"
GITHUB_DIR="${KOKORO_ARTIFACTS_DIR}/github"

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
RUNNER_SKAFFOLD_DIR="${RUNNER_DIR}/gke"

# Checkout driver source
echo "Downloading test runner source"
git clone -b "${RUNNER_REPO_BRANCH}" --depth=1 "${RUNNER_REPO}" "${RUNNER_REPO_DIR}"

## Building lang-specific interop tests
#echo "Building Java test app"
#cd "${SRC_DIR}"
#./gradlew --no-daemon grpc-interop-testing:installDist -x test -PskipCodegen=true -PskipAndroid=true --console=plain
## Test test app binaries
#run_safe "${TEST_APP_DIR}/bin/xds-test-client" --help
#run_safe "${TEST_APP_DIR}/bin/xds-test-server" --help

# Install test runner requirements
echo "Installing test runner requirements"
cd "${RUNNER_DIR}"
pyenv global 3.6.1
pyenv virtualenv 3.6.1 xds_test_driver
pyenv local xds_test_driver
which python
python --version
python -c "print('hello'); import contextlib; print(type(contextlib))"
#pip install -r requirements.txt
#pip list
#gcloud components update -q

# Build image
echo "Building test app image"
cd "${RUNNER_SKAFFOLD_DIR}"
pyenv versions
which gcloud
gcloud --version
gcloud components update -q
gcloud --version

gcloud components install skaffold -q
gcloud auth configure-docker
docker images list
cp -rv "${TEST_APP_BUILD_DIR}" "${RUNNER_SKAFFOLD_DIR}"
skaffold build -v info
docker images list

## Prepare generated Python code.
#cd "${RUNNER_REPO_DIR}"
#PROTO_SOURCE_DIR=src/proto/grpc/testing
#python3 -m grpc_tools.protoc \
#    --proto_path=. \
#    --python_out="${RUNNER_DIR}" \
#    --grpc_python_out="${RUNNER_DIR}" \
#    "${PROTO_SOURCE_DIR}/test.proto" \
#    "${PROTO_SOURCE_DIR}/messages.proto" \
#    "${PROTO_SOURCE_DIR}/empty.proto"
#
## Run the test
#cd "${RUNNER_DIR}"
#python -m tests.baseline_test \
#  --project=grpc-testing \
#  --network=default-vpc \
#  --logger_levels=infrastructure:DEBUG
