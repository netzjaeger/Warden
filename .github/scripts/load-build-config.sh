#!/usr/bin/env bash

set -euo pipefail

CONFIG_PATH="${CONFIG_PATH:-warden/config}"

: "${GITHUB_ENV:=/dev/null}"

: "${BUILD_REPO_DIR:?BUILD_REPO_DIR is unset — point it at a local checkout of your build repository}"

echo "Reading build configuration from ${BUILD_REPO_DIR}..."
config="$BUILD_REPO_DIR/$CONFIG_PATH"
repo_desc="$BUILD_REPO_DIR"

if [ ! -d "$config" ]; then
    echo "FATAL: ${repo_desc} has no ${CONFIG_PATH}/ directory." >&2
    echo "       See the README for the expected layout." >&2
    exit 1
fi

normalize() {
    sed -e 's/\r$//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$1" \
        | grep -v -e '^#' -e '^$' || true
}

readonly MIN_MASKABLE_LENGTH=4

emit() {
    local name=$1 value=$2
    if [ "${#value}" -ge "$MIN_MASKABLE_LENGTH" ]; then
        echo "::add-mask::${value}"
    fi
    printf '%s=%s\n' "$name" "$value" >> "$GITHUB_ENV"
}

missing=0

load_list() {
    local name=$1 file=$2 sep=${3:-,}
    local path="$config/$file" value count
    if [ ! -f "$path" ]; then
        echo "FATAL: missing ${CONFIG_PATH}/${file} (needed for ${name})." >&2
        missing=1
        return
    fi
    value=$(normalize "$path" | paste -sd"$sep" -)
    if [ -z "$value" ]; then
        echo "FATAL: ${CONFIG_PATH}/${file} has no entries (needed for ${name})." >&2
        missing=1
        return
    fi
    count=$(normalize "$path" | wc -l)
    emit "$name" "$value"
    echo "  ${name}: ${count} entr$([ "$count" -eq 1 ] && echo y || echo ies) loaded"
}

load_scalar() {
    local name=$1 file=$2
    local path="$config/$file" value
    if [ ! -f "$path" ]; then
        echo "FATAL: missing ${CONFIG_PATH}/${file} (needed for ${name})." >&2
        missing=1
        return
    fi
    value=$(normalize "$path" | head -n 1)
    if [ -z "$value" ]; then
        echo "FATAL: ${CONFIG_PATH}/${file} has no value (needed for ${name})." >&2
        missing=1
        return
    fi
    emit "$name" "$value"
    echo "  ${name}: set"
}

load_optional_scalar() {
    local name=$1 file=$2
    local path="$config/$file" value=""
    [ -f "$path" ] && value=$(normalize "$path" | head -n 1)
    if [ -z "$value" ]; then
        echo "  ${name}: not set (policy left unmanaged)"
        return
    fi
    emit "$name" "$value"
    echo "  ${name}: set"
}

load_optional_joined() {
    local name=$1 file=$2 sep=$3
    local path="$config/$file" value="" count
    [ -f "$path" ] && value=$(normalize "$path" | paste -sd"$sep" -)
    if [ -z "$value" ]; then
        echo "  ${name}: not set (policy left unmanaged)"
        return
    fi
    count=$(normalize "$path" | wc -l)
    emit "$name" "$value"
    echo "  ${name}: ${count} entr$([ "$count" -eq 1 ] && echo y || echo ies) loaded"
}

load_optional_night_light() {
    local file=night-light.txt
    local path="$config/$file" value=""
    [ -f "$path" ] && value=$(normalize "$path" | head -n 1)
    if [ -z "$value" ]; then
        echo "  WARDEN_NIGHT_LIGHT: not set (policy left unmanaged)"
        return
    fi
    if [[ ! "$value" =~ ^([0-9]{1,2}):([0-9]{2})-([0-9]{1,2}):([0-9]{2})[[:space:]]+([0-9]+)$ ]]; then
        echo "FATAL: ${CONFIG_PATH}/${file} must read 'HH:MM-HH:MM TEMP', e.g. '22:00-06:00 2500'." >&2
        missing=1
        return
    fi
    local start_h=$((10#${BASH_REMATCH[1]})) start_m=$((10#${BASH_REMATCH[2]}))
    local end_h=$((10#${BASH_REMATCH[3]}))   end_m=$((10#${BASH_REMATCH[4]}))
    local temp=$((10#${BASH_REMATCH[5]}))
    if [ "$start_h" -gt 23 ] || [ "$end_h" -gt 23 ] || [ "$start_m" -gt 59 ] || [ "$end_m" -gt 59 ]; then
        echo "FATAL: ${CONFIG_PATH}/${file} has an out-of-range time." >&2
        missing=1
        return
    fi
    if [ "$temp" -lt 1000 ] || [ "$temp" -gt 10000 ]; then
        echo "FATAL: ${CONFIG_PATH}/${file} colour temperature must be 1000-10000 K." >&2
        missing=1
        return
    fi
    emit WARDEN_NIGHT_LIGHT_START_MS "$(( (start_h * 60 + start_m) * 60000 ))"
    emit WARDEN_NIGHT_LIGHT_END_MS   "$(( (end_h * 60 + end_m) * 60000 ))"
    emit WARDEN_NIGHT_LIGHT_TEMP     "$temp"
    echo "  WARDEN_NIGHT_LIGHT: set"
}

load_list   WARDEN_WHITELIST         app-whitelist.txt
load_list   WARDEN_URL_BLOCKLIST     url-blocklist.txt
load_list   WARDEN_CHROMIUM_PACKAGES chromium-packages.txt
load_list   WARDEN_CHROMIUM_POLICIES chromium-policies.txt ';'
load_scalar WARDEN_BATTERY_FLAGS     battery-saver-flags.txt
load_scalar WARDEN_SYSTEM_DNS        system-dns.txt
load_scalar WARDEN_BROWSER_DNS       browser-dns.txt

load_optional_joined WARDEN_URL_ALLOWLIST    url-allowlist.txt ','
load_optional_scalar WARDEN_LOCALE_LOCK locale-lock.txt
load_optional_joined WARDEN_MANAGED_SETTINGS settings-enforce.txt ';'
load_optional_night_light

if [ "$missing" -ne 0 ]; then
    echo "Refusing to build a release against incomplete lockdown configuration." >&2
    exit 1
fi

echo "Build configuration loaded."
