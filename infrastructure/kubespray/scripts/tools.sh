#!/usr/bin/env bash
# Pinned client tooling for bin/, so the versions the docs quote are the versions
# that run. Downloads are macOS-native; the nodes get their own copies from
# Kubespray and from istio/install.yml.
#
#   scripts/tools.sh fetch      # download, verify against bin/checksums.txt, install
#   scripts/tools.sh pin        # download, verify against upstream, record the sha256
#   scripts/tools.sh list       # what is pinned and what is installed
#
# `fetch` refuses a download that bin/checksums.txt does not cover. `pin` is how a
# new version gets in: it trusts the checksum published beside the artifact once,
# and from then on the committed file is the pin. Commit bin/checksums.txt.
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")/lib" && pwd)/common.sh"
. "$V31_ROOT/versions.env"

V31_BIN=$V31_ROOT/bin
CHECKSUMS=$V31_BIN/checksums.txt
V31_TMP=

[ "$(uname -s)" = Darwin ] ||
  die "this installs macOS binaries into bin/; the nodes are served by Ansible"

case $(uname -m) in
  arm64) ARCH=arm64 ;;
  x86_64) ARCH=amd64 ;;
  *) die "unsupported control-machine architecture $(uname -m)" ;;
esac

# name | version | url | layout | upstream checksum suffix
# layout: `raw` for a bare binary, `tar:<member>` for a member of a .tar.gz.
tools_table() {
  cat <<TABLE
kubectl|v$KUBE_VERSION|https://dl.k8s.io/release/v$KUBE_VERSION/bin/darwin/$ARCH/kubectl|raw|.sha256
helm|$HELM_VERSION|https://get.helm.sh/helm-$HELM_VERSION-darwin-$ARCH.tar.gz|tar:darwin-$ARCH/helm|.sha256sum
helmfile|$HELMFILE_VERSION|https://github.com/helmfile/helmfile/releases/download/v$HELMFILE_VERSION/helmfile_${HELMFILE_VERSION}_darwin_$ARCH.tar.gz|tar:helmfile|.checksums
istioctl|$ISTIO_VERSION|https://github.com/istio/istio/releases/download/$ISTIO_VERSION/istioctl-$ISTIO_VERSION-osx-$ARCH.tar.gz|tar:istioctl|.sha256
TABLE
}

sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }

pinned_sha() { # basename -> sha256 or empty
  [ -f "$CHECKSUMS" ] || return 0
  awk -v f="$1" '$1 !~ /^#/ && $2 == f { print $1; exit }' "$CHECKSUMS"
}

# The installed version, or empty. Every one of these answers without a cluster.
installed_version() { # name
  local bin=$V31_BIN/$1
  [ -x "$bin" ] || return 0
  case $1 in
    kubectl)  "$bin" version --client -o yaml 2>/dev/null | awk '/gitVersion:/{print $2; exit}' ;;
    helm)     "$bin" version --short 2>/dev/null | awk '{print $1}' | sed 's/+.*//' ;;
    istioctl) "$bin" version --remote=false 2>/dev/null | tail -n1 ;;
  esac
}

fetch_one() { # mode name version url layout suffix
  local mode=$1 name=$2 version=$3 url=$4 layout=$5 suffix=$6
  local base=${url##*/} have want tmp member

  have=$(installed_version "$name" || true)
  case $have in
    *"$version"*)
      info "$name $version already in bin/"
      return 0 ;;
  esac

  tmp=$V31_TMP/$name
  mkdir -p "$tmp"

  info "downloading $base"
  curl -fsSL --retry 3 --connect-timeout 10 -o "$tmp/$base" "$url" ||
    die "cannot download $url"

  have=$(sha256_of "$tmp/$base")
  want=$(pinned_sha "$base")

  if [ -n "$want" ]; then
    [ "$have" = "$want" ] ||
      die "$base sha256 is $have, $CHECKSUMS pins $want - refusing to install"
    info "$base matches the pin"
  elif [ "$mode" = pin ]; then
    local upstream
    upstream=$(curl -fsSL --retry 3 --connect-timeout 10 "$url$suffix" 2>/dev/null |
                 awk '{print $1; exit}' || true)
    [ -n "$upstream" ] ||
      die "no checksum published at $url$suffix; pin $base by hand in $CHECKSUMS"
    [ "$have" = "$upstream" ] ||
      die "$base sha256 is $have but upstream publishes $upstream"
    printf '%s  %s\n' "$have" "$base" >>"$CHECKSUMS"
    warn "recorded $base in $CHECKSUMS - review and commit it"
  else
    die "$base is not pinned in $CHECKSUMS; run: make tools-pin"
  fi

  case $layout in
    raw)
      install -m 0755 "$tmp/$base" "$V31_BIN/$name" ;;
    tar:*)
      member=${layout#tar:}
      tar -xzf "$tmp/$base" -C "$tmp" "$member" ||
        die "$base has no member $member"
      install -m 0755 "$tmp/$member" "$V31_BIN/$name" ;;
    *)
      die "unknown layout $layout for $name" ;;
  esac

  have=$(installed_version "$name" || true)
  case $have in
    *"$version"*) info "$name $have installed" ;;
    *) die "bin/$name reports '${have:-nothing}', expected $version" ;;
  esac
}

cmd_fetch() { # mode
  need_cmd curl
  need_cmd shasum
  need_cmd tar
  V31_TMP=$(mktemp -d "${TMPDIR:-/tmp}/v31-tools.XXXXXX")
  trap 'rm -rf "$V31_TMP"' EXIT
  mkdir -p "$V31_BIN"
  [ -f "$CHECKSUMS" ] || die "$CHECKSUMS missing; it is committed, restore it from git"

  local name version url layout suffix rc=0
  while IFS='|' read -r name version url layout suffix; do
    [ -n "$name" ] || continue
    fetch_one "$1" "$name" "$version" "$url" "$layout" "$suffix" || rc=1
  done < <(tools_table)
  return $rc
}

cmd_list() {
  local name version url layout suffix
  printf '%-10s %-12s %s\n' tool pinned installed
  while IFS='|' read -r name version url layout suffix; do
    [ -n "$name" ] || continue
    printf '%-10s %-12s %s\n' "$name" "$version" "$(installed_version "$name" || true)"
  done < <(tools_table)
}

case ${1:-fetch} in
  fetch) cmd_fetch fetch ;;
  pin)   cmd_fetch pin ;;
  list)  cmd_list ;;
  *) log "usage: ${0##*/} {fetch|pin|list}"; exit 2 ;;
esac
