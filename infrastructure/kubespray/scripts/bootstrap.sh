#!/usr/bin/env bash
# Control-machine bootstrap: the pinned Kubespray checkout and the Python venv
# that runs it. macOS only; the nodes get nothing from here.
#
#   scripts/bootstrap.sh            # fetch, then venv
#   scripts/bootstrap.sh fetch      # clone or move $KUBESPRAY_SRC to $KUBESPRAY_VERSION
#   scripts/bootstrap.sh venv       # create $KUBESPRAY_VENV and install the pins
#
# Idempotent. Re-run after changing KUBESPRAY_VERSION in versions.env.
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")/lib" && pwd)/common.sh"
. "$V31_ROOT/versions.env"

# Kubespray's own requirements.txt is authoritative; this repo's only adds what it
# does not pin. Both in one pip invocation, or pip resolves them independently.
REQUIREMENTS_LOCAL=$V31_ROOT/requirements.txt

# ----------------------------------------------------------------- checkout ----

cmd_fetch() {
  need_cmd git
  local desc=''

  if [ -d "$KUBESPRAY_SRC/.git" ]; then
    desc=$(git -C "$KUBESPRAY_SRC" describe --tags --exact-match 2>/dev/null || true)
    if [ "$desc" = "$KUBESPRAY_VERSION" ]; then
      info "kubespray $KUBESPRAY_VERSION already at $KUBESPRAY_SRC"
      return 0
    fi
    info "checkout is at ${desc:-an untagged commit}; moving to $KUBESPRAY_VERSION"
    git -C "$KUBESPRAY_SRC" diff --quiet HEAD ||
      die "$KUBESPRAY_SRC has local changes; move it aside or discard them first"
    git -C "$KUBESPRAY_SRC" fetch --depth 1 origin \
      "refs/tags/$KUBESPRAY_VERSION:refs/tags/$KUBESPRAY_VERSION" ||
      die "$KUBESPRAY_VERSION is not a tag in $KUBESPRAY_REPO"
    git -C "$KUBESPRAY_SRC" checkout --detach --force "refs/tags/$KUBESPRAY_VERSION"
  else
    [ -e "$KUBESPRAY_SRC" ] &&
      die "$KUBESPRAY_SRC exists but is not a git checkout; remove it (make clean)"
    info "cloning $KUBESPRAY_REPO at $KUBESPRAY_VERSION"
    git clone --depth 1 --branch "$KUBESPRAY_VERSION" "$KUBESPRAY_REPO" "$KUBESPRAY_SRC" ||
      die "clone failed; check that $KUBESPRAY_VERSION is a published tag"
  fi

  # A checkout at the wrong tag installs a different Kubernetes, Calico and etcd
  # than this inventory was written against, so confirm rather than assume.
  desc=$(git -C "$KUBESPRAY_SRC" describe --tags --exact-match 2>/dev/null || true)
  [ "$desc" = "$KUBESPRAY_VERSION" ] ||
    die "$KUBESPRAY_SRC is at '${desc:-unknown}', not $KUBESPRAY_VERSION"
  [ -f "$KUBESPRAY_SRC/cluster.yml" ] ||
    die "$KUBESPRAY_SRC/cluster.yml missing; the checkout is not Kubespray"
  [ -f "$KUBESPRAY_SRC/requirements.txt" ] ||
    die "$KUBESPRAY_SRC/requirements.txt missing; nothing pins Ansible"
  info "kubespray $KUBESPRAY_VERSION at $KUBESPRAY_SRC"
}

# --------------------------------------------------------------------- venv ----

# The Apple-supplied interpreters are a trap: /usr/bin/python3 is a shim onto the
# Command Line Tools, has no working venv/pip story for a build like this one, and
# is replaced wholesale by an Xcode update. Refuse it instead of half-working.
resolve_python() {
  local want=${V31_PYTHON:-python3} path base
  path=$(command -v "$want" 2>/dev/null || true)
  [ -n "$path" ] || die "$want not found; brew install python@3.11 (or set V31_PYTHON)"
  path=$(cd "$(dirname "$path")" && pwd)/$(basename "$path")

  case $path in
    /usr/bin/* | /System/* | /Library/Developer/CommandLineTools/*)
      die "$path is the macOS system python; brew install python@3.11, then
       make bootstrap V31_PYTHON=python3.11" ;;
  esac

  base=$("$path" -c 'import sys; print(sys.base_prefix)' 2>/dev/null || true)
  case $base in
    /System/* | /Applications/Xcode*.app/* | /Library/Developer/CommandLineTools/*)
      die "$path is a wrapper around the Xcode python at $base; use a Homebrew,
       pyenv or uv interpreter instead (set V31_PYTHON)" ;;
  esac

  # ansible-core 2.19, which Kubespray v2.32.0 pins, needs 3.11 on the controller.
  "$path" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)' ||
    die "$path is $("$path" -V 2>&1); Ansible needs Python 3.11 or newer"

  printf '%s\n' "$path"
}

cmd_venv() {
  [ -f "$KUBESPRAY_SRC/requirements.txt" ] ||
    die "no Kubespray checkout at $KUBESPRAY_SRC; run: make fetch"

  local python
  python=$(resolve_python)
  info "control-machine python: $python ($("$python" -V 2>&1))"

  if [ ! -x "$KUBESPRAY_VENV/bin/python" ]; then
    info "creating $KUBESPRAY_VENV"
    "$python" -m venv "$KUBESPRAY_VENV" ||
      die "venv creation failed; is the python3-venv module present in $python?"
  fi

  local pip=$KUBESPRAY_VENV/bin/pip
  [ -x "$pip" ] || die "$pip missing; remove $KUBESPRAY_VENV and re-run"

  info "installing the pinned Ansible from $KUBESPRAY_SRC/requirements.txt"
  "$pip" install --quiet --upgrade pip
  "$pip" install --quiet -r "$KUBESPRAY_SRC/requirements.txt" -r "$REQUIREMENTS_LOCAL"

  local ansible=$KUBESPRAY_VENV/bin/ansible-playbook
  [ -x "$ansible" ] || die "$ansible not installed; read the pip output above"
  info "$("$KUBESPRAY_VENV/bin/ansible" --version | head -n1) at $ansible"

  # Everything else drives Ansible through bin/kubespray.sh, which sets this on
  # every run; the reminder is for anyone activating the venv and typing by hand.
  log ""
  log "  source $KUBESPRAY_VENV/bin/activate"
  log "  export OBJC_DISABLE_INITIALIZE_FORK_SAFETY=YES   # macOS fork safety"
}

case ${1:-all} in
  fetch) cmd_fetch ;;
  venv)  cmd_venv ;;
  all)   cmd_fetch; cmd_venv ;;
  *) log "usage: ${0##*/} {all|fetch|venv}"; exit 2 ;;
esac
