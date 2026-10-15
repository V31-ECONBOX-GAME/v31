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

# Installs socket_vmnet for Lima.
set -euo pipefail

readonly VERSION=1.2.2
readonly RELEASE=https://github.com/lima-vm/socket_vmnet/releases/download/v$VERSION
readonly PREFIX=/opt/socket_vmnet
readonly HERE=$(cd "$(dirname "$0")" && pwd)
readonly NETWORKS=$HOME/.lima/_config/networks.yaml
readonly SUDOERS=/etc/sudoers.d/lima

usage() {
  cat <<EOF
Usage: $(basename "$0") <command>

Commands:
  status     show whether socket_vmnet, networks.yaml and sudoers match this directory
  install    install socket_vmnet $VERSION, copy networks.yaml, write sudoers
  uninstall  remove all three

install and uninstall ask for sudo.
EOF
}

status() {
  local version
  version=$("$PREFIX/bin/socket_vmnet" --version 2>/dev/null) || version=missing
  printf '%-14s %s\n' socket_vmnet "$version"
  if cmp -s "$HERE/networks.yaml" "$NETWORKS"; then
    printf '%-14s %s\n' networks.yaml "up to date"
  else
    printf '%-14s %s\n' networks.yaml "differs"
  fi
  if limactl sudoers 2>/dev/null | cmp -s - "$SUDOERS"; then
    printf '%-14s %s\n' sudoers "up to date"
  else
    printf '%-14s %s\n' sudoers "differs"
  fi
}

install() {
  local file=socket_vmnet-$VERSION-$(uname -m).tar.gz tmp
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"; trap - RETURN' RETURN
  curl -fsSL --retry 3 --retry-all-errors -o "$tmp/$file" "$RELEASE/$file"
  curl -fsSL --retry 3 --retry-all-errors -o "$tmp/SHA256SUMS" "$RELEASE/SHA256SUMS"
  (cd "$tmp" && grep " $file\$" SHA256SUMS | shasum -a 256 -c -)
  sudo tar -C / -xzf "$tmp/$file" opt/socket_vmnet
  sudo chown -R root:wheel "$PREFIX"
  mkdir -p "$(dirname "$NETWORKS")"
  cp "$HERE/networks.yaml" "$NETWORKS"
  limactl sudoers | sudo tee "$SUDOERS" >/dev/null
  echo "installed"
}

uninstall() {
  sudo rm -f "$SUDOERS"
  sudo rm -rf "$PREFIX"
  rm -f "$NETWORKS"
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
