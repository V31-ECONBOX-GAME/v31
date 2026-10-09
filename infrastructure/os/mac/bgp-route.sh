#!/bin/bash
# Routes BGP-announced ranges to the BGP router.
set -euo pipefail

readonly RANGES=(
  10.200.0.0/24
)
readonly BGP_ROUTER=192.168.139.2
readonly LABEL=org.v31bank.bgp-route
readonly PLIST=/Library/LaunchDaemons/$LABEL.plist

usage() {
  cat <<EOF
Usage: $(basename "$0") <command>

Commands:
  status     show where each range is routed and whether launchd keeps the routes
  install    let launchd add the routes at boot and re-add them every minute when missing
  uninstall  stop launchd from keeping the routes and delete them

install and uninstall need sudo.
EOF
}

ensure() {
  local range
  for range in "${RANGES[@]}"; do
    printf "route -n get -net %s 2>/dev/null | grep -q 'gateway: %s' || route -n add -net %s %s; " \
      "$range" "$BGP_ROUTER" "$range" "$BGP_ROUTER"
  done
}

plist() {
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/sh</string>
    <string>-c</string>
    <string>$(ensure)</string>
  </array>
  <key>RunAtLoad</key>
  <true/>
  <key>StartInterval</key>
  <integer>60</integer>
</dict>
</plist>
EOF
}

require_root() {
  if [[ $EUID -ne 0 ]]; then
    echo "run with sudo" >&2
    return 1
  fi
}

status() {
  local range route
  for range in "${RANGES[@]}"; do
    route=$(route -n get -net "$range" 2>/dev/null | awk '/gateway:|interface:/ {printf "%s %s ", $1, $2}')
    printf '%-18s %s\n' "$range" "${route:-none}"
  done
  if launchctl print "system/$LABEL" >/dev/null 2>&1; then
    echo "launchd            installed"
  else
    echo "launchd            not installed"
  fi
}

install_daemon() {
  require_root
  plist >"$PLIST.tmp"
  chown root:wheel "$PLIST.tmp"
  chmod 0644 "$PLIST.tmp"
  mv -f "$PLIST.tmp" "$PLIST"
  launchctl bootout "system/$LABEL" 2>/dev/null || true
  launchctl bootstrap system "$PLIST"
  echo "installed"
}

uninstall_daemon() {
  require_root
  launchctl bootout "system/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"
  local range
  for range in "${RANGES[@]}"; do
    route -n delete -net "$range" "$BGP_ROUTER" >/dev/null 2>&1 || true
  done
  echo "uninstalled"
}

main() {
  case ${1:-} in
    status) status ;;
    install) install_daemon ;;
    uninstall) uninstall_daemon ;;
    -h | --help) usage ;;
    *)
      usage >&2
      return 2
      ;;
  esac
}

main "$@"
