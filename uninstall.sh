#!/usr/bin/env bash
# Undoes setup.sh: Amazon home screen back, packages re-enabled, default animations and DNS.
#
#   ./uninstall.sh <fire-tv-ip> [--skip-launcher] [--skip-debloat] [--skip-tweaks] [--skip-dns] [--keep <list>]
set -euo pipefail
cd "$(dirname "$0")"
. scripts/lib.sh

KEEP=" " IP="" LAUNCHER=1 DEBLOAT=1 TWEAKS=1 DNS=1
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-launcher) LAUNCHER=0 ;;
    --skip-debloat) DEBLOAT=0 ;;
    --keep) KEEP="$KEEP$2 "; shift ;;
    --skip-tweaks) TWEAKS=0 ;;
    --skip-dns) DNS=0 ;;
    -h|--help) sed -n '2,4p' "$0"; exit 0 ;;
    *) IP=$1 ;;
  esac
  shift
done
connect

if [ $LAUNCHER = 1 ]; then
  say "Désinstallation du launcher"
  rest=$(sh_ settings get secure enabled_accessibility_services | tr ':' '\n' | grep -v "$SVC" | paste -sd: - || true)
  if [ -z "$rest" ] || [ "$rest" = null ]; then
    sh_ settings delete secure enabled_accessibility_services >/dev/null
    sh_ settings put secure accessibility_enabled 0
  else
    sh_ settings put secure enabled_accessibility_services "$rest"
  fi
  sh_ cmd package set-home-activity --user 0 $AMAZON_HOME >/dev/null 2>&1 || true
  sh_ pm uninstall $PKG >/dev/null 2>&1 || warn "Le launcher n'était pas installé."
fi

if [ $DEBLOAT = 1 ]; then
  for list in debloat/*.txt; do
    case "$KEEP" in *" $(basename "$list" .txt) "*) continue ;; esac
    say "Réactivation : $(basename "$list" .txt)"
    apply_list enable "$list"
  done
  sh_ settings put secure limit_ad_tracking 0
fi

if [ $TWEAKS = 1 ]; then
  say "Animations remises à 1x"
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    sh_ settings put global $k 1
  done
fi

if [ $DNS = 1 ]; then
  say "DNS privé remis en automatique"
  sh_ settings delete global private_dns_specifier >/dev/null
  sh_ settings delete global private_dns_mode >/dev/null
fi

sh_ input keyevent KEYCODE_HOME
say "Terminé."
