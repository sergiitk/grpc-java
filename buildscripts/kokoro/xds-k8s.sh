#!/usr/bin/env bash

set -euo pipefail
# Debugging
set -x

# Debug info
if [[ -f /VERSION ]]; then
  cat /VERSION
fi
lsb_release -a

# Script start
echo "xDS interop tests on GKE"
GITHUB_DIR="${KOKORO_ARTIFACTS_DIR}/github"

# Language-specific repo
SRC_DIR="${GITHUB_DIR}/grpc-java"
TEST_APP_DIR="${SRC_DIR}/interop-testing/build/install/grpc-interop-testing/"

# Runner
# todo(sergiitk): replace with real values
RUNNER_REPO="https://github.com/sergiitk/grpc.git"
RUNNER_REPO_BRANCH="xds_test_driver-wip"
RUNNER_REPO_DIR="${GITHUB_DIR}/grpc"
RUNNER_DIR="${RUNNER_REPO_DIR}/tools/run_tests/xds_test_driver"
RUNNER_SKAFFOLD_DIR="${RUNNER_DIR}/gke"

# Building lang-specific interop tests
cd "${SRC_DIR}"
./gradlew --no-daemon grpc-interop-testing:installDist -x test -PskipCodegen=true -PskipAndroid=true --console=plain
# Testing test app binaries
"${TEST_APP_DIR}/bin/xds-test-client" --help
"${TEST_APP_DIR}/bin/xds-test-server" --help

# Checkout driver source
echo "Downloading test runner source"
git clone -b "${RUNNER_REPO_BRANCH}" --depth=1 "${RUNNER_REPO}" "${RUNNER_REPO_DIR}"

# Install test runner requirements
echo "Installing test runner requirements"
gcloud components update -q
gcloud components install skaffold -q

# Build image
echo "Building test app image"
cd "${RUNNER_SKAFFOLD_DIR}"
skaffold build -v info
docker images list

# Running the driver
cd "${RUNNER_DIR}"
pyenv virtualenv 3.6.1 xds_test_driver
pyenv local xds_test_driver
pip install -r requirements.txt
python -m tests.baseline_test
  --project=grpc-testing \
  --network=default-vpc \
  --logger_levels=infrastructure:DEBUG

