#!/bin/bash
#
# Copyright 2026-present the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Installs helmfile for the Kubernetes cluster.
set -euo pipefail

readonly HELM_DIFF_VERSION=v3.15.13
readonly HELM_DIFF=https://github.com/databus23/helm-diff/releases/download/$HELM_DIFF_VERSION

usage() {
  cat <<EOF
Usage: $(basename "$0") <command>

Commands:
  status     show helmfile, kustomize and the helm-diff plugin
  install    brew install helmfile and kustomize, install helm-diff $HELM_DIFF_VERSION verified by its release key
  uninstall  remove all three

install needs helm from kubectl.sh install.
EOF
}

status() {
  local version
  version=$(helmfile --version 2>/dev/null) || version=missing
  printf '%-15s %s\n' helmfile "$version"
  version=$(kustomize version 2>/dev/null) || version=missing
  printf '%-15s %s\n' kustomize "$version"
  version=$(helm plugin list 2>/dev/null | awk '$1 == "diff" {print $2}') || version=
  printf '%-15s %s\n' helm-diff "${version:-missing}"
}

install() {
  local keyring
  brew install helmfile kustomize
  keyring=$(mktemp)
  trap 'rm -f "$keyring"; trap - RETURN' RETURN
  curl -fsSL -o "$keyring" "$HELM_DIFF/pubkey.asc"
  helm plugin install "$HELM_DIFF/helm-diff-macos-arm64.tgz" --keyring "$keyring"
  echo "installed"
}

uninstall() {
  helm plugin uninstall diff
  brew uninstall helmfile kustomize
  echo "uninstalled"
}

main() {
  case ${1:-} in
    status) status ;;
    install) install ;;
    uninstall) uninstall ;;
    -h | --help) usage ;;
    *)
      usage >&2
      return 2
      ;;
  esac
}

main "$@"
