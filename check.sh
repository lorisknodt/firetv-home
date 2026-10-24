#!/usr/bin/env bash
# Checks that the Fire TV is still set up (a Fire OS update can undo things) and runs setup.sh
# again only if something was undone. Does nothing if the TV is off or unreachable.
# Meant for a scheduled task: see scripts/install-task.ps1. Log: logs/check.log
#
#   ./check.sh <fire-tv-ip> [the same options you give setup.sh]
set -uo pipefail
cd "$(dirname "$0")"
. scripts/lib.sh
mkdir -p logs
exec >>logs/check.log 2>&1
echo "--- $(date '+%Y-%m-%d %H:%M:%S')"

IP=${1:-}
[ -n "$IP" ] || { echo "Usage : $0 <ip-du-fire-tv> [options de setup.sh]"; exit 1; }
shift
SETUP_ARGS=("$@")
KEEP=" " LAUNCHER=1 DEBLOAT=1 TWEAKS=1 DNS=1 DNS_HOST=$DEFAULT_DNS
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-launcher) LAUNCHER=0 ;;
    --skip-debloat) DEBLOAT=0 ;;
    --skip-tweaks) TWEAKS=0 ;;
    --skip-dns) DNS=0 ;;
    --keep) KEEP="$KEEP$2 "; shift ;;
    --dns) DNS_HOST=$2; shift ;;
  esac
  shift
done

find_adb
D="$IP:5555"
if ! "$ADB" connect "$D" 2>&1 | grep -qE "connected to"; then echo "Fire TV injoignable (éteinte ?)"; exit 0; fi
[ "$("$ADB" -s "$D" get-state 2>/dev/null)" = device ] || { echo "ADB pas autorisé sur la télé"; exit 0; }

drift=()
if [ $LAUNCHER = 1 ]; then
  sh_ pm list packages $PKG | grep -q "$PKG" || drift+=("launcher désinstallé")
  sh_ settings get secure enabled_accessibility_services | grep -q "$SVC" || drift+=("service Home désactivé")
fi
if [ $DEBLOAT = 1 ]; then
  enabled=$(sh_ pm list packages -e)
  for list in debloat/*.txt; do
    case "$KEEP" in *" $(basename "$list" .txt) "*) continue ;; esac
    for p in $(grep -vE '^\s*(#|$)' "$list" | tr -d '\r'); do
      # Packages Fire OS refuses to disable stay enabled for good; don't count them.
      case "$p" in com.amazon.device.metrics|com.amazon.ftvads.deeplinking) continue ;; esac
      grep -qx "package:$p" <<<"$enabled" && drift+=("$p réactivé")
    done
  done
fi
[ $TWEAKS = 1 ] && [ "$(sh_ settings get global window_animation_scale)" != 0.5 ] && drift+=("animations")
[ $DNS = 1 ] && [ "$(sh_ settings get global private_dns_specifier)" != "$DNS_HOST" ] && drift+=("DNS")

if [ ${#drift[@]} -eq 0 ]; then echo "Tout est en place."; exit 0; fi
echo "Défait : ${drift[*]}. On relance setup.sh."
./setup.sh "$IP" "${SETUP_ARGS[@]}"
