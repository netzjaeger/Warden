# Warden

Warden is a headless Android Device Owner (Device Admin) daemon that enforces a
strict application whitelist, hardens network settings, reduces the device's visual
stimulus, and repairs its own configuration when it is changed.

It runs without a persistent UI and applies policy through the platform's
`DevicePolicyManager` and Settings providers rather than an Accessibility service,
so enforcement survives reboots and app restarts.

> **⚠️ Warning:** Warden disables Safe Mode, Factory Reset and Developer Options.
> Setting it as Device Owner **may permanently lock your device** if you don't
> understand the provisioning flow. Use at your own risk — a factory reset from
> the bootloader is often the only way to remove it.

## Architecture

* **Headless:** no UI. The launcher activity finishes immediately and its icon is
  disabled once the daemon is active.
* **Whitelist engine:** a coroutine-based engine sweeps installed packages to
  suspend anything not whitelisted, and intercepts install broadcasts to catch
  new packages as they land.
* **Build-time configuration:** the whitelist and the rest of the policy are not
  in this repository. They're read from a separate build repository at build
  time and compiled into `BuildConfig` fields.

## Features

* **App suspension:** suspends and grays out non-whitelisted apps. Only
  non-system packages are touched, so the stock launcher needs no whitelist
  entry — a third-party launcher does, or the device is left without a home screen.
* **Low-stimulus display:** pins whichever display/accessibility Settings the
  build-time config names, plus an
  optional night-light schedule.
* **Network policy:** pins the global Private DNS (DoT) hostname and injects a
  DNS-over-HTTPS template into managed Chromium-based browsers.
* **Browser policy:** injects Chromium's native `URLBlocklist`/`URLAllowlist` via
  application restrictions, so site blocking happens in the browser's networking
  stack without an Accessibility service. Which browsers are managed, and the
  rest of the policy bundle, are configuration — this repo ships none of its own.
* **Settings watchdog:** a `ContentObserver` watches the keys Warden manages and
  writes the enforced value back if Developer Options, ADB, or any pinned
  setting changes.
* **Device restrictions:** disallows Safe Boot, factory reset, user switching,
  and Clear Data / Force Stop against the daemon itself.

---

## Deployment

Warden's policy — the whitelist, DNS resolver, browser rules, and the rest —
comes from a second repository at build time, so this repo alone won't
produce a usable APK.

This repo doesn't track the Gradle wrapper or local build scripts; builds are
meant to run in CI. To build locally, install Gradle and either run
`gradle wrapper` or use your system Gradle (`gradle assembleRelease`).

### 1. Set up a build repository

This repository holds no secrets and no per-user configuration — your build
repo checks it out directly and read-only, builds the signed APK there, and
publishes releases. Policy edits get real commits and diffs this way, instead
of being buried in Actions secrets.

Your build repo must contain:

```
warden/config/app-whitelist.txt        # one package name per line
warden/config/url-blocklist.txt        # one Chromium URLBlocklist pattern per line
warden/config/chromium-packages.txt    # browsers to manage, one package name per line
warden/config/chromium-policies.txt    # browser policy, one [<package>:]Key=value per line
warden/config/battery-saver-flags.txt  # Android battery saver constants, one line
warden/config/system-dns.txt           # OS-wide Private DNS (DoT) hostname
warden/config/browser-dns.txt          # browser DoH template URL, or a bare hostname
```

Optionally:

```
warden/config/url-allowlist.txt        # one Chromium URLAllowlist pattern per line
warden/config/locale-lock.txt          # ISO 639 language code, e.g. "en"
warden/config/night-light.txt          # one line of "HH:MM-HH:MM TEMP"
warden/config/settings-enforce.txt     # Settings rows to pin, one <namespace>:<key>=<value> per line
```

An absent or empty optional file just leaves that policy unmanaged.

* **`url-allowlist.txt`** takes precedence over `url-blocklist.txt` for matching URLs.
* **`locale-lock.txt`** adds `DISALLOW_CONFIG_LOCALE` once the device's language matches the code.
* **`night-light.txt`** sets the night-light window and color temperature; a malformed schedule fails the build.
* **`settings-enforce.txt`** pins arbitrary Settings rows; each row is re-applied automatically when changed.

**Browser policy:** `chromium-packages.txt` lists the managed browsers;
`chromium-policies.txt` holds the policy, one entry per line:

```
Key=value                 # applies to every managed browser
<package>:Key=value       # applies only to that browser
```

The value's shape picks the type — `true`/`false` become booleans, digits
become integers, anything else is a string. Values may not contain `;`.
Unrecognized keys are ignored by Chromium rather than causing harm. Only
`DnsOverHttpsMode`, `DnsOverHttpsTemplates`, `URLBlocklist`, and `URLAllowlist`
are set in code, since they derive from their own dedicated files; everything
else is configuration.

All seven required files must hold at least one value — an empty or missing
one fails the release rather than falling back to the permissive development
defaults in `app/build.gradle.kts` (an empty URL blocklist, for one, makes
Chromium enforcement completely inert).

To check an edit before pushing, run the loader against a local checkout of
your build repo:

```bash
BUILD_REPO_DIR=/path/to/checkout .github/scripts/load-build-config.sh
```

In your build repo, add `.github/workflows/release.yml` using
[`.github/release-workflow.yml.example`](.github/release-workflow.yml.example)
from this repo as the template, then add these secrets and you're set:

* `KEYSTORE_BASE64`: base64-encoded PKCS12 `.keystore` file.
* `KEY_ALIAS`, `KEY_PASSWORD`, `KEYSTORE_PASSWORD`: signing credentials.

Run the **Warden Build** workflow from your build repo. Version name and code
are derived automatically; download the APK from its releases.

> Config changes only take effect once you build and install a new release.

### 2. Device debloat (pre-provisioning)

Remove unwanted preinstalled apps *before* installing Warden, to avoid
maintaining an ever-growing blacklist:

```bash
adb shell pm uninstall -k --user 0 <package.name>
```

### 3. Install & grant permissions

```bash
adb install -r Warden-release.apk
adb shell pm grant app.anonymous.warden android.permission.WRITE_SECURE_SETTINGS
```

`WRITE_SECURE_SETTINGS` must be granted before Device Owner is set, so the DNS
and display enforcements can write to the Settings providers.

### 4. Promote to Device Owner

> This generally only works on a freshly factory-reset device, before any user
> accounts are added.

```bash
adb shell dpm set-device-owner app.anonymous.warden/.Receiver
```

### 5. Final lock

- Disconnect USB; turn off USB/wireless debugging and revoke debug
  authorizations; optionally turn off Developer Options.
- Install your whitelisted apps.
- Reboot. On first boot, `BootReceiver` arms the Developer Options watchdog and
  `DISALLOW_DEBUGGING_FEATURES`. From then on every non-whitelisted app —
  already installed or installed later — stays suspended.

---

## Troubleshooting & removal

* **`dpm set-device-owner` fails:** the device is already provisioned. Factory
  reset, skip adding an account and Wi-Fi in the setup wizard, then retry.
* **Removal:** Warden blocks its own uninstall and disables factory reset from
  Settings. Removing it requires booting into the hardware recovery menu.
