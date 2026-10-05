#!/usr/bin/env bash
# The one way Ansible is run against this cluster: the pinned venv, the pinned
# Kubespray checkout, and this repo's inventory by absolute path.
#
#   bin/kubespray.sh prepare                  # playbooks/orbstack-prepare.yml
#   bin/kubespray.sh dhcp-guard               # playbooks/kube-vip-dhcp-guard.yml
#   bin/kubespray.sh istio | istio-verify     # istio/install.yml | istio/verify.yml
#   bin/kubespray.sh cluster.yml -e etcd_retries=10
#   bin/kubespray.sh playbooks/orbstack-prepare.yml --limit=w4
#
# A playbook inside this tree runs from the tree; anything else runs from
# $KUBESPRAY_SRC, because Kubespray's own ansible.cfg has to apply.
set -euo pipefail

. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../scripts/lib" && pwd)/common.sh"
. "$V31_ROOT/versions.env"

ANSIBLE_PLAYBOOK=${V31_ANSIBLE_PLAYBOOK:-$KUBESPRAY_VENV/bin/ansible-playbook}

usage() {
  sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

resolve() { # alias or path -> absolute playbook path
  case $1 in
    prepare)      printf '%s\n' "$V31_ROOT/playbooks/orbstack-prepare.yml" ;;
    dhcp-guard)   printf '%s\n' "$V31_ROOT/playbooks/kube-vip-dhcp-guard.yml" ;;
    istio)        printf '%s\n' "$V31_ROOT/istio/install.yml" ;;
    istio-verify) printf '%s\n' "$V31_ROOT/istio/verify.yml" ;;
    /*)           printf '%s\n' "$1" ;;
    *)
      if [ -f "$V31_ROOT/$1" ]; then printf '%s\n' "$V31_ROOT/$1"
      elif [ -f "$KUBESPRAY_SRC/$1" ]; then printf '%s\n' "$KUBESPRAY_SRC/$1"
      else printf '%s\n' "$1"
      fi
      ;;
  esac
}

[ $# -ge 1 ] || usage
case $1 in -h | --help | help) usage ;; esac

target=$1; shift
playbook=$(resolve "$target")

[ -x "$ANSIBLE_PLAYBOOK" ] ||
  die "no ansible-playbook at $ANSIBLE_PLAYBOOK; run: make bootstrap"
[ -f "$playbook" ] ||
  die "no playbook for '$target' in this tree or in $KUBESPRAY_SRC (run: make fetch)"

inventory_load
inventory="$V31_INVENTORY_DIR/hosts.yaml"

# Kubespray's playbooks depend on the ansible.cfg beside them; this tree's do not,
# and running them from here keeps relative paths such as istio/values/ working.
# KUBESPRAY_SRC is $V31_ROOT/.kubespray, i.e. INSIDE V31_ROOT, so it has to be
# tested first -- otherwise every Kubespray playbook matches "$V31_ROOT"/* and runs
# from this tree, where Kubespray's ansible.cfg (roles_path = roles:..., relative)
# is never read and its internal roles cannot be resolved.
case $playbook in
  "$KUBESPRAY_SRC"/*) workdir=$KUBESPRAY_SRC ;;
  *)                  workdir=$V31_ROOT ;;
esac

# macOS aborts a forked child once an Objective-C class has been initialised in the
# parent (objc_initializeAfterForkError). Ansible forks one child per host and the
# ssh connection plugin reaches CoreFoundation through getaddrinfo, so every run
# with forks > 1 trips it. YES restores the pre-10.13 behaviour. `no_proxy='*'` is
# the other lever for the same crash, if a task ever goes through CFNetwork.
export OBJC_DISABLE_INITIALIZE_FORK_SAFETY=YES

# This host is the constraint, not the network. Measured 2026-09-27: the OrbStack
# VM is 10 vCPU / 43008 MiB carrying 18 guest vCPU and 3x4096 + 3x10240 MiB of
# guests -- committed to the last byte. Under a full cluster.yml the OrbStack
# Helper process sits above 60% CPU and the guests are starved, so SSH probes time
# out and Ansible looks hung when it is only waiting.
#
# ControlPersist stays LONG on purpose: a short persist makes Ansible tear down and
# re-establish a connection per task, and the connection churn through OrbStack's
# single SSH gateway costs more than it saves. Keepalives are what bound a wedged
# channel (~60s), not a short persist -- 2026-09-27 an `echo ~user && sleep 0` to
# cp3 hung 29 minutes because Kubespray's ssh_args carry neither.
export ANSIBLE_SSH_ARGS="-o ControlMaster=auto -o ControlPersist=10m \
-o ServerAliveInterval=15 -o ServerAliveCountMax=4 -o ConnectTimeout=60 \
-o ConnectionAttempts=5 -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no"

# Kubespray does not set forks, so Ansible's default of 5 applies -- and on a host
# this oversubscribed even that spikes the Helper process. Three keeps the run
# moving without starving the guests it is configuring. Override with V31_FORKS.
export ANSIBLE_FORKS="${V31_FORKS:-3}"

# Keep cluster traffic off any local HTTP proxy. Tasks delegated to localhost run
# kubectl/helm on this Mac, and a developer proxy intercepts them: measured
# 2026-09-27, every API call died after exactly 5s with "Service Unavailable" --
# a 503 from the proxy, not from Kubernetes, with TLS never starting.
V31_NO_PROXY="192.168.139.0/24,192.168.138.0/24,.orb.local,localhost,127.0.0.1,::1"
export NO_PROXY="${NO_PROXY:+$NO_PROXY,}$V31_NO_PROXY"
export no_proxy="$NO_PROXY"

info "$(basename "$playbook") from $workdir"
cd "$workdir"
exec "$ANSIBLE_PLAYBOOK" -i "$inventory" "$playbook" "$@"
