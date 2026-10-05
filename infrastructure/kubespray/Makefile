# V31 Kubernetes cluster - control-machine entry points.
#
# Every target runs on the macOS host; nothing is installed on the nodes by hand.
# versions.env is the only place a version is written down, so no number appears
# below. `make` with no target prints the list.
#
# Written for the make Apple ships (GNU make 3.81), so nothing here uses
# .ONESHELL, .SHELLFLAGS or $(file): every recipe line is one shell invocation
# and multi-statement lines chain with `&&` or start with `set -e`.

SHELL := /bin/bash

ROOT := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))

# versions.env is bash, not make syntax, so it is sourced once per key instead of
# included. An empty result means the key is gone; the guard below catches it.
env_of = $(shell . '$(ROOT)/versions.env' >/dev/null 2>&1 && printf '%s' "$$$(strip $(1))")

KUBESPRAY_VERSION := $(call env_of,KUBESPRAY_VERSION)
KUBESPRAY_SRC     := $(call env_of,KUBESPRAY_SRC)
KUBESPRAY_VENV    := $(call env_of,KUBESPRAY_VENV)
KUBE_VERSION      := $(call env_of,KUBE_VERSION)
KUBE_VIP_ADDRESS  := $(call env_of,KUBE_VIP_ADDRESS)
ISTIO_VERSION     := $(call env_of,ISTIO_VERSION)
HELM_VERSION      := $(call env_of,HELM_VERSION)

ifeq ($(strip $(KUBESPRAY_VERSION)),)
$(error cannot read $(ROOT)/versions.env; run make from inside the kubespray tree)
endif

# Discovered the same way scripts/lib/common.sh discovers it, so the two cannot
# disagree about which inventory is live.
INVENTORY_DIR := $(firstword $(patsubst %/hosts.yaml,%,$(wildcard $(ROOT)/inventory/*/hosts.yaml)))

ifeq ($(strip $(INVENTORY_DIR)),)
$(error no inventory/*/hosts.yaml under $(ROOT))
endif

INVENTORY       := $(INVENTORY_DIR)/hosts.yaml
KUBECONFIG_FILE := $(INVENTORY_DIR)/artifacts/admin.conf

BIN          := $(ROOT)/bin
SCRIPTS      := $(ROOT)/scripts
KUBESPRAY_SH := $(BIN)/kubespray.sh

ANSIBLE_PLAYBOOK := $(KUBESPRAY_VENV)/bin/ansible-playbook

# A pinned client if `make tools` has run, otherwise whatever brew installed.
# check-kubectl turns the second case into a readable error when there is none.
KUBECTL := $(if $(wildcard $(BIN)/kubectl),$(BIN)/kubectl,kubectl)

# Kubespray hard-codes kube_resolv_conf: /run/systemd/resolve/resolv.conf in
# roles/kubernetes/node/vars/ubuntu-26.yml. OrbStack guests run no systemd-resolved,
# so that file does not exist and EVERY static pod fails with
#   "Failed to generate sandbox config for pod:
#    open /run/systemd/resolve/resolv.conf: no such file or directory"
# -- apiserver included, so kubeadm init times out at wait-control-plane with an
# empty `crictl ps`. /etc/resolv.conf here is a symlink to
# /opt/orbstack-guest/etc/resolv.conf and is readable.
#
# This has to be an extra-var: role vars outrank group_vars, so setting it in
# inventory/v31/ would be silently ignored. Every playbook that applies the
# kubernetes/node role needs it.
ORBSTACK_EXTRA := -e kube_resolv_conf=/etc/resolv.conf

# Raised from the default because etcd bootstrap on 2-vCPU nodes sharing an
# oversubscribed macOS host regularly exceeds the default window.
DEPLOY_EXTRA := -e etcd_retries=10 $(ORBSTACK_EXTRA)

# docs/runbook.md section 5: the edge gateway's PDB can deadlock a drain, and the
# setup roles must re-run or changed binary versions are skipped as already-present.
UPGRADE_EXTRA := -e etcd_retries=10 -e drain_timeout=300s -e drain_grace_period=60 \
                 -e drain_fallback_enabled=true -e upgrade_cluster_setup=true \
                 $(ORBSTACK_EXTRA)

# make scale LIMIT=w4 / make deploy EXTRA='-vv'
LIMIT ?=
# Cluster traffic must never go through a local HTTP proxy. Measured 2026-09-27:
# with HTTP_PROXY=http://127.0.0.1:1082 set and NO_PROXY listing only localhost,
# every call to the API server died after exactly 5s with "Service Unavailable" --
# a 503 from the PROXY, not from Kubernetes. TCP connected in 0.3ms and TLS never
# started. Bypassing it returned 200 in 0.04s. Exported for every target here, so
# a developer's proxy cannot silently break the cluster path.
V31_NO_PROXY := 192.168.139.0/24,192.168.138.0/24,.orb.local,localhost,127.0.0.1,::1
export NO_PROXY = $(V31_NO_PROXY)
export no_proxy = $(V31_NO_PROXY)

# check-apiserver polls /readyz this many times, 10s apart, before giving up.
APISERVER_WAIT_TRIES ?= 30

EXTRA ?=
CONFIRM ?=

limit_arg = $(if $(strip $(LIMIT)),--limit '$(strip $(LIMIT))',)

.PHONY: help bootstrap fetch tools tools-pin preflight ip-drift prepare deploy \
        upgrade scale reset kubeconfig istio verify postflight clean versions \
        check-apiserver etcd-health \
        check-venv check-kubespray check-kubectl check-kubeconfig

.DEFAULT_GOAL := help

help: ## List the targets
	@printf '%s\n' 'V31 cluster - Kubespray $(KUBESPRAY_VERSION), Kubernetes $(KUBE_VERSION), Istio $(ISTIO_VERSION)' ''
	@awk -F':.*## ' '/^[a-z][a-z-]*:.*## /{printf "  \033[1m%-12s\033[0m %s\n", $$1, $$2}' \
	  $(firstword $(MAKEFILE_LIST))
	@printf '%s\n' '' 'Variables: LIMIT=<host pattern>  EXTRA=<extra ansible args>  CONFIRM=yes' \
	  '           V31_PYTHON=<interpreter for the venv>' \
	  '' 'First run: make bootstrap && make preflight && make deploy' ''

versions: ## Print every pinned version
	@. '$(ROOT)/versions.env' && printf '%-20s %s\n' \
	  kubespray "$$KUBESPRAY_VERSION" kubernetes "$$KUBE_VERSION" calico "$$CALICO_VERSION" \
	  kube-vip "$$KUBE_VIP_VERSION" istio "$$ISTIO_VERSION" gateway-api "$$GATEWAY_API_VERSION" \
	  helm "$$HELM_VERSION"

## ------------------------------------------------------------------ setup ----

fetch: ## Clone or move the Kubespray checkout to KUBESPRAY_VERSION
	$(SCRIPTS)/bootstrap.sh fetch

bootstrap: ## fetch, then the Python venv with the pinned Ansible (macOS)
	$(SCRIPTS)/bootstrap.sh all

tools: ## Download the pinned helm, kubectl and istioctl into bin/
	$(SCRIPTS)/tools.sh fetch

tools-pin: ## Record the sha256 of those downloads in bin/checksums.txt
	$(SCRIPTS)/tools.sh pin

## ------------------------------------------------------------------ install --

preflight: ## Whole-cluster readiness check; nothing is installed
	$(SCRIPTS)/ip-drift.sh
	$(SCRIPTS)/preflight.sh
	$(SCRIPTS)/kube-vip.sh preflight

# No kubeconfig guard: the point is that this also answers before the cluster
# exists, when a stale hosts.yaml is the thing that would break the install.
ip-drift: ## Compare every node's live address with what the cluster believes
	KUBECONFIG='$(KUBECONFIG_FILE)' V31_KUBECTL='$(KUBECTL)' $(SCRIPTS)/ip-drift.sh $(EXTRA)

prepare: check-venv ## Durable swapoff and the node-IP assertions, no cluster change
	$(KUBESPRAY_SH) prepare $(limit_arg) $(EXTRA)

deploy: check-venv check-kubespray ## prepare, then Kubespray cluster.yml (25-45 min)
	$(KUBESPRAY_SH) prepare $(EXTRA)
	$(KUBESPRAY_SH) cluster.yml $(DEPLOY_EXTRA) $(EXTRA)
	@printf '%s\n' 'next: make kubeconfig && make istio && make verify' >&2

upgrade: check-venv check-kubespray ## Kubespray upgrade-cluster.yml, one minor version
	@test -n '$(strip $(LIMIT))' || printf '%s\n' \
	  'note: upgrading every node in one run. LIMIT=cp1 goes node by node.' >&2
	$(KUBESPRAY_SH) upgrade-cluster.yml $(UPGRADE_EXTRA) $(limit_arg) $(EXTRA)

scale: check-venv check-kubespray ## Join new workers (make scale LIMIT=w4)
	@test -n '$(strip $(LIMIT))' || { printf '%s\n' \
	  'scale needs the new host: make scale LIMIT=w4' >&2; exit 2; }
	$(KUBESPRAY_SH) prepare $(limit_arg)
	$(KUBESPRAY_SH) scale.yml $(ORBSTACK_EXTRA) $(limit_arg) $(EXTRA)

reset: check-venv check-kubespray ## Destroy the cluster (needs CONFIRM=yes)
	@test '$(CONFIRM)' = yes || { printf '%s\n' \
	  'reset.yml destroys the cluster, every etcd data directory and the CNI state.' \
	  'Back up first - docs/runbook.md section 7 - then: make reset CONFIRM=yes' >&2; exit 2; }
	$(KUBESPRAY_SH) reset.yml -e reset_confirmation=yes $(ORBSTACK_EXTRA) $(EXTRA)
	@printf '%s\n' 'reset.yml leaves residue; check it with docs/runbook.md section 9.' >&2

istio: check-venv check-kubeconfig check-apiserver ## Gateway API CRDs, the Istio charts and the edge
	$(KUBESPRAY_SH) istio $(EXTRA)

## ------------------------------------------------------------------- verify --

etcd-health: ## Per-endpoint etcd health and latency - what check-apiserver is guarding against
	@ssh -o BatchMode=yes -o ConnectTimeout=10 cp1 'sudo etcdctl \
	  --endpoints=https://127.0.0.1:2379 \
	  --cacert=/etc/ssl/etcd/ssl/ca.pem \
	  --cert=/etc/ssl/etcd/ssl/node-cp1.pem \
	  --key=/etc/ssl/etcd/ssl/node-cp1-key.pem \
	  endpoint health --cluster -w table' 2>&1 | grep -v unrecognized

kubeconfig: check-kubeconfig ## Print the export line for the admin kubeconfig
	@printf 'export KUBECONFIG=%s\n' '$(KUBECONFIG_FILE)'
	@printf '%s\n' 'eval "$$(make -s kubeconfig)" to use it in this shell' >&2

postflight: check-kubectl check-kubeconfig ## Post-install smoke test only
	KUBECONFIG='$(KUBECONFIG_FILE)' V31_KUBECTL='$(KUBECTL)' $(SCRIPTS)/postflight.sh

verify: check-kubectl check-kubeconfig check-venv ## postflight, the VIP and the mesh
	KUBECONFIG='$(KUBECONFIG_FILE)' V31_KUBECTL='$(KUBECTL)' $(SCRIPTS)/postflight.sh
	$(SCRIPTS)/kube-vip.sh verify
	$(KUBESPRAY_SH) istio-verify

## -------------------------------------------------------------------- clean --

clean: ## Remove the venv, the Kubespray checkout and the downloaded bin/ tools
	rm -rf '$(KUBESPRAY_VENV)' '$(KUBESPRAY_SRC)'
	rm -f '$(BIN)/helm' '$(BIN)/kubectl' '$(BIN)/istioctl'
	@printf '%s\n' 'kept: $(INVENTORY_DIR)/artifacts and credentials - they hold cluster secrets' >&2

## ------------------------------------------------------------------- guards --

check-venv:
	@test -x '$(ANSIBLE_PLAYBOOK)' || { printf '%s\n' \
	  'no ansible-playbook at $(ANSIBLE_PLAYBOOK)' \
	  'run: make bootstrap' >&2; exit 2; }

check-kubespray:
	@test -f '$(KUBESPRAY_SRC)/cluster.yml' || { printf '%s\n' \
	  'no Kubespray checkout at $(KUBESPRAY_SRC)' \
	  'run: make fetch' >&2; exit 2; }

check-kubectl:
	@command -v '$(KUBECTL)' >/dev/null 2>&1 || { printf '%s\n' \
	  'kubectl not found ($(KUBECTL))' \
	  'run: make tools    or: brew install kubernetes-cli' >&2; exit 2; }

check-kubeconfig:
	@test -f '$(KUBECONFIG_FILE)' || { printf '%s\n' \
	  'no admin kubeconfig at $(KUBECONFIG_FILE)' \
	  'kubeconfig_localhost writes it during cluster.yml; run: make deploy' >&2; exit 2; }

# etcd on these 2-vCPU control planes needs minutes to drain the write backlog a
# full cluster.yml leaves behind. Measured immediately after one: cp1 answered an
# etcd health probe in 4.9ms while cp2 and cp3 took 1.05s -- 200x -- and the very
# next `make istio` died on
#   rpc error: code = Unavailable desc = error reading from server:
#   read tcp ...:2379: read: connection timed out
# Helm had simply out-waited the API server. Probe first rather than burn a
# 10-minute chart timeout against a cluster that cannot answer yet.
check-apiserver: check-kubectl
	@printf "%s" "waiting for the API server to answer /readyz "; \
	n=0; \
	until "$(KUBECTL)" --kubeconfig "$(KUBECONFIG_FILE)" get --raw=/readyz >/dev/null 2>&1; do \
	  n=`expr $$n + 1`; \
	  if [ $$n -ge $(APISERVER_WAIT_TRIES) ]; then \
	    printf "\\n%s\\n" \
	      "error /readyz never became healthy after $(APISERVER_WAIT_TRIES) tries." \
	      "      TCP to the VIP can succeed while the API server answers 503: under" \
	      "      load it sheds requests even though kube-vip forwards fine." \
	      "      Node-local kubectl usually still works - compare with:" \
	      "        ssh cp1 sudo kubectl get nodes" \
	      "      then: make etcd-health" >&2; \
	    exit 2; \
	  fi; \
	  printf "."; sleep 10; \
	done; \
	printf " ok\\n"