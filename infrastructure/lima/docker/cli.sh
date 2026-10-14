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

# Installs the Docker CLI for Lima.
set -euo pipefail

readonly CONTEXT=lima-docker
readonly SOCKET=$HOME/.lima/docker/sock/docker.sock
readonly PLUGINS=$HOME/.docker/cli-plugins
readonly BREW_PLUGINS=$(brew --prefix)/lib/docker/cli-plugins

usage() {
  cat <<EOF
Usage: $(basename "$0") <command>

Commands:
  status     show whether the CLI, its plugins and the $CONTEXT context are in place
  install    brew install the CLI and plugins, link the plugins, use the $CONTEXT context
  uninstall  remove all three
EOF
}

status() {
  local version plugin host
  version=$(docker --version 2>/dev/null) || version=missing
  printf '%-15s %s\n' docker "$version"
  for plugin in docker-compose docker-buildx; do
    if [ "$(readlink "$PLUGINS/$plugin" 2>/dev/null)" = "$BREW_PLUGINS/$plugin" ]; then
      printf '%-15s %s\n' "$plugin" linked
    else
      printf '%-15s %s\n' "$plugin" missing
    fi
  done
  host=$(docker context inspect "$CONTEXT" --format '{{.Endpoints.docker.Host}}' 2>/dev/null) || host=missing
  if [ "$host" = "unix://$SOCKET" ]; then
    host="up to date"
  elif [ "$host" != missing ]; then
    host=differs
  fi
  printf '%-15s %s\n' "$CONTEXT" "$host"
  printf '%-15s %s\n' current "$(docker context show 2>/dev/null || echo missing)"
}

install() {
  brew install docker docker-compose docker-buildx
  mkdir -p "$PLUGINS"
  ln -sf "$BREW_PLUGINS/docker-compose" "$BREW_PLUGINS/docker-buildx" "$PLUGINS/"
  if docker context inspect "$CONTEXT" >/dev/null 2>&1; then
    docker context update "$CONTEXT" --docker "host=unix://$SOCKET" >/dev/null
  else
    docker context create "$CONTEXT" --docker "host=unix://$SOCKET" >/dev/null
  fi
  docker context use "$CONTEXT" >/dev/null
  echo "installed"
}

uninstall() {
  docker context use default >/dev/null 2>&1 || true
  docker context rm -f "$CONTEXT" >/dev/null 2>&1 || true
  rm -f "$PLUGINS/docker-compose" "$PLUGINS/docker-buildx"
  brew uninstall docker docker-compose docker-buildx
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
