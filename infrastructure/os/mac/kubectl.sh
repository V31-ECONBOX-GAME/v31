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

# Installs kubectl for the Kubernetes cluster.
set -euo pipefail

readonly CONTROL_PLANE=control-plane1
readonly ADMIN_CONF=/etc/kubernetes/admin.conf
readonly SERVER=https://10.200.0.1:6443
readonly NAME=v31-prod
readonly CONFIG=$HOME/.kube/config

usage() {
  cat <<EOF
Usage: $(basename "$0") <command>

Commands:
  status     show kubectl, whether $CONFIG matches the cluster, and when its certificate expires
  install    brew install kubectl, write $CONFIG from $CONTROL_PLANE pointing at $SERVER
  uninstall  remove both
EOF
}

config() {
  limactl shell "$CONTROL_PLANE" sudo cat "$ADMIN_CONF" | sed -E \
    -e "s#^( *server:).*#\1 $SERVER#" \
    -e "s#kubernetes-admin@cluster\.local#$NAME#" \
    -e "s#cluster\.local#$NAME#" \
    -e "s#kubernetes-admin#$NAME-admin#"
}

status() {
  local version fresh state expires
  version=$(kubectl version --client 2>/dev/null | head -1) || version=missing
  printf '%-15s %s\n' kubectl "$version"
  if [ ! -f "$CONFIG" ]; then
    state=missing
  elif ! fresh=$(config 2>/dev/null); then
    state="cluster unreachable"
  elif [ "$fresh" = "$(cat "$CONFIG")" ]; then
    state="up to date"
  else
    state=differs
  fi
  printf '%-15s %s\n' kubeconfig "$state"
  expires=$(awk '/client-certificate-data/ {print $2}' "$CONFIG" 2>/dev/null |
    base64 -d | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2) || expires=missing
  printf '%-15s %s\n' expires "${expires:-missing}"
}

install() {
  local tmp
  brew install kubernetes-cli
  tmp=$(mktemp)
  trap 'rm -f "$tmp"; trap - RETURN' RETURN
  config >"$tmp"
  mkdir -p "$(dirname "$CONFIG")"
  chmod 600 "$tmp"
  mv "$tmp" "$CONFIG"
  echo "installed"
}

uninstall() {
  rm -f "$CONFIG"
  brew uninstall kubernetes-cli
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
