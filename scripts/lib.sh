# Shared helpers for setup.sh and uninstall.sh. Sourced, not run.

PKG=com.lkn.firehome
SVC="$PKG/$PKG.HomeKeyService"
AMAZON_HOME=com.amazon.tv.launcher/.ui.HomeActivity_vNext
DEFAULT_DNS=dns.adguard-dns.com

say()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[31mxx\033[0m %s\n' "$*" >&2; exit 1; }

# adb from $ADB, the PATH, or the winget / Android SDK install locations.
find_adb() {
  [ -n "${ADB:-}" ] && return
  if command -v adb >/dev/null 2>&1; then ADB=adb; return; fi
  for a in "${LOCALAPPDATA:-}"/Microsoft/WinGet/Packages/Google.PlatformTools_*/platform-tools/adb.exe \
           "${ANDROID_HOME:-/nonexistent}"/platform-tools/adb* "$HOME"/Library/Android/sdk/platform-tools/adb \
           "$HOME"/Android/Sdk/platform-tools/adb; do
    [ -x "$a" ] && { ADB="$a"; return; }
  done
  die "adb introuvable. Installe les platform-tools Android (Windows : winget install Google.PlatformTools)."
}

# Connects to the Fire TV and checks it really is one.
connect() {
  [ -n "${IP:-}" ] || die "Indique l'adresse IP du Fire TV : $0 192.168.1.42 (Paramètres > Mon Fire TV > À propos > Réseau)."
  find_adb
  D="$IP:5555"
  "$ADB" connect "$D" >/dev/null 2>&1 || true
  local state
  state=$("$ADB" -s "$D" get-state 2>&1 || true)
  case "$state" in
    device) ;;
    *unauthorized*) die "Accepte « Autoriser le débogage USB ? » sur la télé (coche « Toujours autoriser »), puis relance." ;;
    *) die "Impossible de joindre $D. Le débogage ADB est-il activé (Paramètres > Mon Fire TV > Options pour les développeurs) et l'IP correcte ?" ;;
  esac
  [ "$(sh_ getprop ro.product.manufacturer)" = Amazon ] || warn "Ça ne ressemble pas à un Fire TV ; on continue quand même."
  say "Connecté à $(sh_ getprop ro.product.model) ($(sh_ getprop ro.build.version.name))"
}

# Runs a shell command on the TV, without the carriage returns adb adds on Windows.
sh_() { "$ADB" -s "$D" shell "$@" | tr -d '\r'; }

# Package names in a debloat list, without comments (whole-line or after a name) and blank lines.
# Packages Fire OS protects stay commented out in the lists, so check.sh never counts them as undone.
list_packages() { sed -e 's/#.*//' -e 's/[[:space:]]//g' "$1" | tr -d '\r' | grep -v '^$'; }

# Applies "disable" or "enable" to every package in a debloat list; Fire OS refuses a few.
apply_list() {
  local action=$1 list=$2 p out
  for p in $(list_packages "$list"); do
    if [ "$action" = disable ]; then out=$(sh_ pm disable-user --user 0 "$p" 2>&1 || true)
    else out=$(sh_ pm enable --user 0 "$p" 2>&1 || true); fi
    case "$out" in
      *"new state"*) printf '   %-50s %s\n' "$p" "$( [ "$action" = disable ] && echo désactivé || echo réactivé )" ;;
      *) printf '   %-50s %s\n' "$p" "refusé par Fire OS" ;;
    esac
  done
}
