#!/usr/bin/env bash
# Sets up a Fire TV over Wi-Fi: clean home screen, debloat, faster animations, ad-blocking DNS.
# Every step is reversible with ./uninstall.sh. Safe to run again, e.g. after a Fire OS update.
#
#   ./setup.sh <fire-tv-ip> [--skip-launcher] [--skip-debloat] [--skip-tweaks] [--skip-dns]
#                           [--keep <list>] [--dns <hostname>] [--rebuild] [--apk <file>]
#   --keep alexa   leaves the packages of debloat/alexa.txt alone (repeatable, any list name)
#   --apk <file>   installs that APK (e.g. downloaded from GitHub) instead of building one
set -euo pipefail
cd "$(dirname "$0")"
. scripts/lib.sh

KEEP=" " IP="" LAUNCHER=1 DEBLOAT=1 TWEAKS=1 DNS=1 DNS_HOST=$DEFAULT_DNS REBUILD=0 APK=launcher/build/firehome.apk PREBUILT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-launcher) LAUNCHER=0 ;;
    --skip-debloat) DEBLOAT=0 ;;
    --keep) KEEP="$KEEP$2 "; shift ;;
    --skip-tweaks) TWEAKS=0 ;;
    --skip-dns) DNS=0 ;;
    --dns) DNS_HOST=$2; shift ;;
    --rebuild) REBUILD=1 ;;
    --apk) APK=$2; PREBUILT=1; shift ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) IP=$1 ;;
  esac
  shift
done
connect

if [ $LAUNCHER = 1 ]; then
  if [ $PREBUILT = 1 ]; then [ -f "$APK" ] || die "APK introuvable : $APK"
  elif [ $REBUILD = 1 ] || [ ! -f "$APK" ]; then say "Compilation du launcher"; launcher/build.sh; fi
  say "Installation du launcher"
  # adb install over Wi-Fi sometimes fails without a reason; one retry fixes it.
  if ! out=$("$ADB" -s "$D" install -r "$APK" 2>&1) && ! out=$("$ADB" -s "$D" install -r "$APK" 2>&1); then
    # Signed with another key (e.g. a GitHub release over a local build): Android refuses the
    # update, so reinstall. The launcher only keeps its weather cache, nothing is lost.
    case "$out" in
      *UPDATE_INCOMPATIBLE*|*SIGNATURES*)
        warn "Launcher signé avec une autre clé : réinstallation."
        sh_ pm uninstall $PKG >/dev/null
        "$ADB" -s "$D" install "$APK" >/dev/null ;;
      *) die "Installation impossible : $out" ;;
    esac
  fi
  # Location: Android 11 only shows the Wi-Fi name to apps holding it.
  # READ_LOGS: how the launcher sees Home presses and the refused YouTube button (see README).
  # WRITE_SECURE_SETTINGS: lets it pause the encrypted DNS while the clock is wrong after a restart.
  sh_ pm grant $PKG android.permission.ACCESS_FINE_LOCATION
  sh_ pm grant $PKG android.permission.READ_LOGS
  sh_ pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
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
