#!/usr/bin/env bash

set -x

# A placeholder for xDS interop tests executed on GKE
echo "Coming soon. Work in progress"

# Debug info
if [[ -f /VERSION ]]; then
  cat /VERSION
fi
lsb_release -a

env | grep KOKORO

cd github
ls -la
