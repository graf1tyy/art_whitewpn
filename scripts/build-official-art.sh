#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM_DIR="${1:-$ROOT/upstream}"
UPSTREAM_REPO="https://github.com/SagerNet/sing-box-for-android.git"
UPSTREAM_COMMIT="f87c2ebdb7e34754be751bb5e58926db2fed8f8c"

if [[ ! -d "$UPSTREAM_DIR/.git" ]]; then
  git clone "$UPSTREAM_REPO" "$UPSTREAM_DIR"
fi
git -C "$UPSTREAM_DIR" fetch --depth 1 origin "$UPSTREAM_COMMIT"
git -C "$UPSTREAM_DIR" checkout --detach "$UPSTREAM_COMMIT"

python3 - "$UPSTREAM_DIR" "$ROOT" <<'PY'
from pathlib import Path
import re, sys

up = Path(sys.argv[1])
art = Path(sys.argv[2])

gradle = up / "app/build.gradle.kts"
text = gradle.read_text()
old = 'applicationId = "io.nekohasekai.sfa"'
assert text.count(old) == 1, "Upstream applicationId layout changed"
text = text.replace(old, 'applicationId = "com.artvpn.app"', 1)

# Give ART a deterministic signing identity. The workflow creates and caches
# this keystore before Gradle runs; all future APKs then use the same signer.
signing_marker = '    signingConfigs {'
assert text.count(signing_marker) == 1, "Unexpected upstream signingConfigs layout"
text = text.replace(
    signing_marker,
    signing_marker + '''
        create("artDebug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
''',
    1
)
debug_original = '''        debug {
            if (getProps("KEYSTORE_PASS").isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }'''
debug_replacement = '''        debug {
            signingConfig = signingConfigs.getByName("artDebug")
        }'''
assert text.count(debug_original) == 1, "Unexpected upstream debug signing block"
text = text.replace(debug_original, debug_replacement, 1)
gradle.write_text(text)

version = up / "version.properties"
v = version.read_text()
v = re.sub(r"(?m)^VERSION_CODE=.*$", "VERSION_CODE=749", v)
v = re.sub(r"(?m)^VERSION_NAME=.*$", "VERSION_NAME=1.0.1", v)
version.write_text(v)

# Keep the ART package and its stable icon, but use the official SFA native VPN
# service, libbox wrapper, profile handling and Android network integration.
icon_src = art / "app/src/main/res/drawable/ic_vpn.xml"
icon_dst = up / "app/src/main/res/drawable/ic_vpn.xml"
icon_dst.parent.mkdir(parents=True, exist_ok=True)
icon_dst.write_bytes(icon_src.read_bytes())

manifest = up / "app/src/main/AndroidManifest.xml"
m = manifest.read_text()
m = m.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/ic_vpn"')
if 'android:roundIcon=' not in m:
    m = m.replace('android:icon="@drawable/ic_vpn"', 'android:icon="@drawable/ic_vpn"\n        android:roundIcon="@drawable/ic_vpn"', 1)
manifest.write_text(m)

for file in (up / "app/src/main/res").glob("values*/strings.xml"):
    s = file.read_text()
    s = re.sub(r'(<string\s+name="app_name"[^>]*>).*?(</string>)', r'\1ART VPN\2', s, flags=re.S)
    file.write_text(s)

# Add an ART-managed VLESS auto profile to the official app without replacing
# any of its native sing-box service or platform code.
dest = up / "app/src/main/java/io/nekohasekai/sfa/artvpn"
dest.mkdir(parents=True, exist_ok=True)
(dest / "ArtAutoProfileManager.kt").write_bytes((art / "upstream-overlay/ArtAutoProfileManager.kt").read_bytes())
(dest / "ArtAutoProfileWorker.kt").write_bytes((art / "upstream-overlay/ArtAutoProfileWorker.kt").read_bytes())

application = up / "app/src/main/java/io/nekohasekai/sfa/Application.kt"
a = application.read_text()
if "ArtAutoProfileManager.installOrRefresh" not in a:
    needle = "                setupLibbox()"
    assert a.count(needle) == 1, "Unexpected setupLibbox location"
    a = a.replace(
        needle,
        needle + '''
                runCatching {
                    io.nekohasekai.sfa.artvpn.ArtAutoProfileManager.installOrRefresh(this@Application)
                }.onFailure {
                    Log.w("ART VPN", "Unable to install automatic server profile: ${it.message}")
                }
                val artRefresh = androidx.work.PeriodicWorkRequestBuilder<
                    io.nekohasekai.sfa.artvpn.ArtAutoProfileWorker
                >(6, java.util.concurrent.TimeUnit.HOURS).build()
                androidx.work.WorkManager.getInstance(this@Application).enqueueUniquePeriodicWork(
                    "art-auto-profile-refresh",
                    androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                    artRefresh
                )''',
        1
    )
application.write_text(a)

# Schedule automatic server-list refresh every six hours, with the first import
# already performed by Application on first start.
if "ArtAutoProfileWorker" not in a:
    raise SystemExit("ART profile bootstrap was not injected")
PY

mkdir -p "$UPSTREAM_DIR/app/libs"
curl --fail --location --retry 3 \
  "https://github.com/singbox-android/libbox/releases/download/1.14.4/libbox.aar" \
  -o "$UPSTREAM_DIR/app/libs/libbox.aar"

cd "$UPSTREAM_DIR"
./gradlew :app:assembleOtherDebug --no-daemon --stacktrace

mkdir -p "$ROOT/dist"
mapfile -t APKS < <(find app/build/outputs/apk/other/debug -type f -name '*.apk' | sort)
printf 'Built APK candidates:\n%s\n' "${APKS[*]}"
APK=""
for candidate in "${APKS[@]}"; do
  if [[ "$candidate" == *arm64-v8a* ]]; then APK="$candidate"; break; fi
done
if [[ -z "$APK" ]]; then
  for candidate in "${APKS[@]}"; do
    if [[ "$candidate" == *universal* ]]; then APK="$candidate"; break; fi
  done
fi
if [[ -z "$APK" ]]; then
  echo "Could not find ARM64 or universal APK" >&2
  exit 1
fi
cp "$APK" "$ROOT/dist/ART-VPN-arm64.apk"
