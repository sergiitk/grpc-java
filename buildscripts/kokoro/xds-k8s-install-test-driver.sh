#!/usr/bin/env bash
# TODO(sergiitk): move to grpc/grpc when implementing support of other languages
set -eo pipefail

if [ -z "$GITHUB_REPOSITORY" ]; then
    echo -e "\$GITHUB_REPOSITORY required"
    exit 1
fi

run_safe() {
  # Run command end report its exit code.
  # Don't terminate the script if the code is negative.
  local exit_code=-1
  "$@" || exit_code=$?
  echo "Exit code: ${exit_code}"
}

# Capture VM version info in the log.
echo "VM version:"
if [[ -f /VERSION ]]; then
  cat /VERSION
fi
run_safe lsb_release -a

# Turn off command trace print, and export secrets needed for the test driver.
set +x
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
cat > "${KOKORO_ARTIFACTS_DIR}/custom_sponge_config.csv" << EOF
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

# Test driver
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
readonly KUBE_CONTEXT

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
