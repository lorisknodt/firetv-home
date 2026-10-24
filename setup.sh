#!/usr/bin/env bash
# Sets up a Fire TV over Wi-Fi: clean home screen, debloat, faster animations, ad-blocking DNS.
# Every step is reversible with ./uninstall.sh. Safe to run again, e.g. after a Fire OS update.
#
#   ./setup.sh <fire-tv-ip> [--skip-launcher] [--skip-debloat] [--skip-tweaks] [--skip-dns]
#                           [--keep <list>] [--dns <hostname>] [--rebuild]
#   --keep alexa   leaves the packages of debloat/alexa.txt alone (repeatable, any list name)
set -euo pipefail
cd "$(dirname "$0")"
. scripts/lib.sh

KEEP=" " IP="" LAUNCHER=1 DEBLOAT=1 TWEAKS=1 DNS=1 DNS_HOST=$DEFAULT_DNS REBUILD=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-launcher) LAUNCHER=0 ;;
    --skip-debloat) DEBLOAT=0 ;;
    --keep) KEEP="$KEEP$2 "; shift ;;
    --skip-tweaks) TWEAKS=0 ;;
    --skip-dns) DNS=0 ;;
    --dns) DNS_HOST=$2; shift ;;
    --rebuild) REBUILD=1 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) IP=$1 ;;
  esac
  shift
done
connect

if [ $LAUNCHER = 1 ]; then
  APK=launcher/build/firehome.apk
  if [ $REBUILD = 1 ] || [ ! -f "$APK" ]; then say "Compilation du launcher"; launcher/build.sh; fi
  say "Installation du launcher"
  "$ADB" -s "$D" install -r "$APK" >/dev/null
  # Location: Android 11 only shows the Wi-Fi name to apps holding it.
  # READ_LOGS: how the launcher sees Home presses and the refused YouTube button (see README).
  sh_ pm grant $PKG android.permission.ACCESS_FINE_LOCATION
  sh_ pm grant $PKG android.permission.READ_LOGS
  sh_ cmd package set-home-activity --user 0 $PKG/.MainActivity >/dev/null 2>&1 || true
  cur=$(sh_ settings get secure enabled_accessibility_services)
  case "$cur" in
    *"$SVC"*) ;;
    null|"") sh_ settings put secure enabled_accessibility_services "$SVC" ;;
    *) sh_ settings put secure enabled_accessibility_services "$cur:$SVC" ;;
  esac
  sh_ settings put secure accessibility_enabled 1
  sh_ am start -n $PKG/.MainActivity >/dev/null
  sleep 2
  if sh_ dumpsys accessibility | grep -q "label=Accueil"; then say "Launcher actif : le bouton Home y mène maintenant"
  else warn "Le service du bouton Home ne tourne pas : vérifie Paramètres > Accessibilité sur la télé."; fi
fi

if [ $DEBLOAT = 1 ]; then
  for list in debloat/*.txt; do
    case "$KEEP" in *" $(basename "$list" .txt) "*) continue ;; esac
    say "Désactivation : $(basename "$list" .txt)"
    apply_list disable "$list"
  done
  sh_ settings put secure limit_ad_tracking 1
fi

if [ $TWEAKS = 1 ]; then
  say "Animations à 0,5x"
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do
    sh_ settings put global $k 0.5
  done
fi

if [ $DNS = 1 ]; then
  say "DNS privé : $DNS_HOST (bloque pubs et traqueurs dans toutes les applis)"
  sh_ settings put global private_dns_mode hostname
  sh_ settings put global private_dns_specifier "$DNS_HOST"
fi

say "Terminé. Appuie sur Home sur la télécommande."
