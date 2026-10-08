#!/bin/bash
# Toggles /etc/resolv.conf between OrbStack's link and a writable copy.
set -euo pipefail

readonly ORBSTACK_RESOLV_CONF=/opt/orbstack-guest/etc/resolv.conf

usage() {
  cat <<EOF
Usage: $(basename "$0") <command> [machine...]

Commands:
  status   show whether /etc/resolv.conf is OrbStack's link or a writable copy
  unlink   replace OrbStack's read-only link with a writable copy of the same file
  link     restore OrbStack's link, dropping whatever was written into the copy

Without machines, every running OrbStack machine is used.
EOF
}

on_machine() {
  local command=$1 orbstack=$2 conf=/etc/resolv.conf state
  if [[ -L $conf && $(readlink "$conf") == "$orbstack" ]]; then
    state=linked
  elif [[ -f $conf && ! -L $conf ]]; then
    state=copied
  else
    echo "unexpected $conf: $(ls -l "$conf" 2>&1)" >&2
    return 1
  fi
  case $command:$state in
    status:*)
      echo "$state, nameserver $(sed -n 's/^nameserver[[:space:]]*//p' "$conf" | paste -sd ' ')"
      ;;
    unlink:linked)
      install -m 0644 "$orbstack" "$conf.tmp"
      mv -fT "$conf.tmp" "$conf"
      echo "unlinked"
      ;;
    link:copied)
      ln -sfn "$orbstack" "$conf.tmp"
      mv -fT "$conf.tmp" "$conf"
      echo "linked"
      ;;
    *)
      echo "already $state"
      ;;
  esac
}

main() {
  if [[ $# -eq 0 ]]; then
    usage >&2
    return 2
  fi
  local command=$1
  shift
  case $command in
    status | unlink | link) ;;
    -h | --help)
      usage
      return 0
      ;;
    *)
      echo "unknown command: $command" >&2
      usage >&2
      return 2
      ;;
  esac

  local machines=("$@")
  if [[ $# -eq 0 ]]; then
    machines=($(orb list --running --quiet))
    if [[ ${#machines[@]} -eq 0 ]]; then
      echo "no running OrbStack machine" >&2
      return 1
    fi
  fi

  local machine result failed=0
  for machine in "${machines[@]}"; do
    if result=$(orb -m "$machine" -u root bash -c "$(declare -f on_machine); on_machine \"\$@\"" \
      on_machine "$command" "$ORBSTACK_RESOLV_CONF" 2>&1); then
      printf '%-16s %s\n' "$machine" "$result"
    else
      printf '%-16s %s\n' "$machine" "${result#$'\n'}" >&2
      failed=1
    fi
  done
  return $failed
}

main "$@"
