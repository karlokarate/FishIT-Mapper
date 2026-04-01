#!/usr/bin/env bash
set -euo pipefail

# Ensure user-local CLI tools are discoverable during this process.
export PATH="$HOME/.local/bin:$PATH"

WITH_PLAYWRIGHT_BROWSER=1
WITH_MITM=1
WITH_DUCKDB=1
ASSUME_YES=0
VERIFY_ONLY=0

MISSING_CORE_CMDS=()
MISSING_PY_MODULES=()
MISSING_PY_CMDS=()
MISSING_PIP=0
MISSING_PLAYWRIGHT_BROWSER=0

usage() {
  cat <<'USAGE'
Mapper-Toolkit bootstrap

Usage:
  scripts/device/toolkit/bootstrap.sh [options]

Options:
  --with-playwright-browser    Ensure Playwright Chromium browser is installed (default: on)
  --without-playwright-browser Skip Playwright Chromium browser installation/check
  --without-mitm               Skip mitmproxy dependency checks/installation
  --without-duckdb             Skip duckdb/pyarrow dependency checks/installation
  --verify-only                Only verify dependencies and exit with error if missing
  --yes, -y                    Non-interactive system package installation
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-playwright-browser) WITH_PLAYWRIGHT_BROWSER=1; shift ;;
    --without-playwright-browser) WITH_PLAYWRIGHT_BROWSER=0; shift ;;
    --without-mitm) WITH_MITM=0; shift ;;
    --without-duckdb) WITH_DUCKDB=0; shift ;;
    --verify-only) VERIFY_ONLY=1; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown arg: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

need_cmd() {
  command -v "$1" >/dev/null 2>&1
}

log() {
  echo "[mapper-toolkit/bootstrap] $*"
}

on_error() {
  local code="$?"
  log "FAILSAFE: bootstrap aborted (exit=$code)."
  log "Review errors above. If needed, rerun with --verify-only for a clean missing-dependency report."
  exit "$code"
}
trap on_error ERR

detect_pkg_mgr() {
  if need_cmd apt-get; then echo apt; return; fi
  if need_cmd dnf; then echo dnf; return; fi
  if need_cmd pacman; then echo pacman; return; fi
  if need_cmd zypper; then echo zypper; return; fi
  echo none
}

need_py_module() {
  local module="$1"
  python3 - "$module" <<'PY' >/dev/null 2>&1
import importlib.util
import sys
name = sys.argv[1]
sys.exit(0 if importlib.util.find_spec(name) is not None else 1)
PY
}

have_playwright_chromium() {
  python3 - <<'PY' >/dev/null 2>&1
import os
import sys

try:
    from playwright.sync_api import sync_playwright
except Exception:
    sys.exit(1)

try:
    with sync_playwright() as p:
        path = p.chromium.executable_path
except Exception:
    sys.exit(1)

sys.exit(0 if (path and os.path.exists(path)) else 1)
PY
}

collect_missing_dependencies() {
  MISSING_CORE_CMDS=()
  MISSING_PY_MODULES=()
  MISSING_PY_CMDS=()
  MISSING_PIP=0
  MISSING_PLAYWRIGHT_BROWSER=0

  local core_cmds=(adb jq tmux dialog python3 git curl unzip rg)
  for cmd in "${core_cmds[@]}"; do
    if ! need_cmd "$cmd"; then
      MISSING_CORE_CMDS+=("$cmd")
    fi
  done

  if ! need_cmd python3; then
    # Cannot verify python modules without python3.
    return 0
  fi

  if ! python3 -m pip --version >/dev/null 2>&1; then
    MISSING_PIP=1
  fi

  if ! need_py_module textual; then
    MISSING_PY_MODULES+=("textual")
  fi
  if ! need_py_module playwright; then
    MISSING_PY_MODULES+=("playwright")
  fi

  if [[ "$WITH_MITM" -eq 1 ]]; then
    if ! need_py_module mitmproxy; then
      MISSING_PY_MODULES+=("mitmproxy")
    fi
    if ! need_cmd mitmdump; then
      MISSING_PY_CMDS+=("mitmdump")
    fi
  fi

  if [[ "$WITH_DUCKDB" -eq 1 ]]; then
    if ! need_py_module duckdb; then
      MISSING_PY_MODULES+=("duckdb")
    fi
    if ! need_py_module pyarrow; then
      MISSING_PY_MODULES+=("pyarrow")
    fi
  fi

  if [[ "$WITH_PLAYWRIGHT_BROWSER" -eq 1 ]] && need_py_module playwright; then
    if ! have_playwright_chromium; then
      MISSING_PLAYWRIGHT_BROWSER=1
    fi
  fi
}

has_missing_dependencies() {
  [[ ${#MISSING_CORE_CMDS[@]} -gt 0 || ${#MISSING_PY_MODULES[@]} -gt 0 || ${#MISSING_PY_CMDS[@]} -gt 0 || "$MISSING_PIP" -eq 1 || "$MISSING_PLAYWRIGHT_BROWSER" -eq 1 ]]
}

print_missing_report() {
  local stage="$1"
  log "Dependency check report ($stage):"

  if [[ ${#MISSING_CORE_CMDS[@]} -gt 0 ]]; then
    echo "  missing core commands: ${MISSING_CORE_CMDS[*]}"
  fi

  if [[ "$MISSING_PIP" -eq 1 ]]; then
    echo "  missing python tooling: python3 -m pip"
  fi

  if [[ ${#MISSING_PY_MODULES[@]} -gt 0 ]]; then
    echo "  missing python modules: ${MISSING_PY_MODULES[*]}"
  fi

  if [[ ${#MISSING_PY_CMDS[@]} -gt 0 ]]; then
    echo "  missing python command wrappers in PATH: ${MISSING_PY_CMDS[*]}"
    echo "  hint: ensure ~/.local/bin is in PATH"
  fi

  if [[ "$MISSING_PLAYWRIGHT_BROWSER" -eq 1 ]]; then
    echo "  missing playwright browser runtime: chromium"
  fi

  if ! has_missing_dependencies; then
    echo "  all required dependencies are present"
  fi
}

install_system_packages() {
  local mgr="$1"
  local sudo_cmd=""
  if [[ "$(id -u)" -ne 0 ]]; then
    if need_cmd sudo; then
      sudo_cmd="sudo"
    else
      log "No root and no sudo available; cannot install missing system packages."
      return 1
    fi
  fi

  case "$mgr" in
    apt)
      $sudo_cmd apt-get update
      $sudo_cmd apt-get install -y \
        android-sdk-platform-tools \
        jq tmux dialog python3 python3-pip git curl unzip ripgrep
      ;;
    dnf)
      $sudo_cmd dnf install -y \
        android-tools jq tmux dialog python3 python3-pip git curl unzip ripgrep
      ;;
    pacman)
      $sudo_cmd pacman -Sy --noconfirm \
        android-tools jq tmux dialog python python-pip git curl unzip ripgrep
      ;;
    zypper)
      $sudo_cmd zypper --non-interactive install \
        android-tools jq tmux dialog python3 python3-pip git curl unzip ripgrep
      ;;
    none)
      log "No known package manager detected; cannot auto-install missing core commands."
      return 1
      ;;
  esac
}

install_python_tools() {
  if ! need_cmd python3; then
    log "python3 is missing; cannot install python dependencies."
    return 1
  fi

  python3 -m pip install --user --upgrade pip setuptools wheel

  local packages=(textual playwright)
  if [[ "$WITH_MITM" -eq 1 ]]; then
    packages+=(mitmproxy)
  fi
  if [[ "$WITH_DUCKDB" -eq 1 ]]; then
    packages+=(duckdb pyarrow)
  fi

  python3 -m pip install --user --upgrade "${packages[@]}"

  if [[ "$WITH_PLAYWRIGHT_BROWSER" -eq 1 ]]; then
    # install-deps may require root privileges; do best-effort and validate afterwards.
    if ! python3 -m playwright install-deps chromium; then
      log "WARN: playwright install-deps failed. Continuing with browser install and final verification."
    fi
    python3 -m playwright install chromium
  fi
}

print_post_install() {
  local user_bin="$HOME/.local/bin"
  log "Bootstrap finished successfully."
  echo
  echo "If needed, add user binaries to PATH:"
  echo "  export PATH=\"$user_bin:\$PATH\""
  echo
  echo "Quick checks:"
  echo "  adb version"
  echo "  jq --version"
  echo "  tmux -V"
  echo "  dialog --version"
  if [[ "$WITH_MITM" -eq 1 ]]; then
    echo "  mitmdump --version"
  fi
  echo "  python3 -c 'import textual; print(textual.__version__)'"
  if [[ "$WITH_DUCKDB" -eq 1 ]]; then
    echo "  python3 -c 'import duckdb; print(duckdb.__version__)'"
  fi
  echo "  python3 -m playwright --version"
  if [[ "$WITH_PLAYWRIGHT_BROWSER" -eq 1 ]]; then
    echo "  python3 - <<'PY'"
    echo "from playwright.sync_api import sync_playwright"
    echo "with sync_playwright() as p: print(p.chromium.executable_path)"
    echo "PY"
  fi
  echo "  python3 scripts/device/toolkit/main.py --check"
  echo
  echo "Then connect device and run:"
  echo "  scripts/device/mapper-toolkit.sh connect --device <host:port-or-serial>"
  echo "  scripts/device/mapper-toolkit.sh doctor"
  echo "  scripts/device/mapper-toolkit.sh tui"
}

main() {
  log "Starting bootstrap"

  local mgr
  mgr="$(detect_pkg_mgr)"
  log "Detected package manager: $mgr"

  collect_missing_dependencies

  if [[ "$VERIFY_ONLY" -eq 1 ]]; then
    print_missing_report "verify-only"
    if has_missing_dependencies; then
      log "FAILSAFE: dependencies missing. Run bootstrap without --verify-only to auto-install."
      return 1
    fi
    log "Dependency verification passed."
    return 0
  fi

  if ! has_missing_dependencies; then
    print_missing_report "startup"
    log "No installation needed."
    print_post_install
    return 0
  fi

  print_missing_report "startup"

  if [[ ${#MISSING_CORE_CMDS[@]} -gt 0 || "$MISSING_PIP" -eq 1 ]]; then
    if [[ "$ASSUME_YES" -eq 1 ]]; then
      install_system_packages "$mgr"
    else
      read -r -p "Install missing system packages with '$mgr'? [y/N] " ans
      if [[ "${ans,,}" == "y" || "${ans,,}" == "yes" ]]; then
        install_system_packages "$mgr"
      else
        log "User skipped system package installation."
      fi
    fi
  fi

  if [[ ${#MISSING_PY_MODULES[@]} -gt 0 || ${#MISSING_PY_CMDS[@]} -gt 0 || "$MISSING_PLAYWRIGHT_BROWSER" -eq 1 ]]; then
    install_python_tools
  fi

  collect_missing_dependencies
  print_missing_report "post-install"

  if has_missing_dependencies; then
    log "FAILSAFE: bootstrap could not satisfy all dependencies."
    log "Please resolve the missing items above and rerun bootstrap."
    return 1
  fi

  print_post_install
}

main "$@"
