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
v = re.sub(r"(?m)^VERSION_CODE=.*$", "VERSION_CODE=752", v)
v = re.sub(r"(?m)^VERSION_NAME=.*$", "VERSION_NAME=1.0.4", v)
version.write_text(v)

# Keep the ART package and its stable icon, but use the official SFA native VPN
# service, libbox wrapper, profile handling and Android network integration.
icon_src = art / "app/src/main/res/drawable/ic_vpn.xml"
icon_dst = up / "app/src/main/res/drawable/ic_vpn.xml"
icon_dst.parent.mkdir(parents=True, exist_ok=True)
icon_dst.write_bytes(icon_src.read_bytes())

manifest = up / "app/src/main/AndroidManifest.xml"
m = manifest.read_text()

# ART queries ConnectivityManager before importing the generated profile.
# The upstream manifest does not include this permission by default.
network_permission = '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />'
internet_permission = '    <uses-permission android:name="android.permission.INTERNET" />'
if network_permission not in m:
    assert m.count(internet_permission) == 1, "Unexpected upstream INTERNET permission marker"
    m = m.replace(internet_permission, internet_permission + "\n" + network_permission, 1)

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
    needle = """            runCatching {
                setupLibbox()
            }.onFailure {
                Log.w("Application", "setup libbox: ${it.message}")
            }"""
    assert a.count(needle) == 1, "Unexpected upstream libbox bootstrap location"
    replacement = """            val artLibboxSetup = runCatching { setupLibbox() }
            if (artLibboxSetup.isSuccess) {
                Log.i("ART-BOOT", "libbox setup completed")
                runCatching {
                    Log.i("ART-BOOT", "starting initial ART Auto profile discovery")
                    io.nekohasekai.sfa.artvpn.ArtAutoProfileManager.installOrRefresh(this@Application)
                }.onSuccess {
                    Log.i("ART-BOOT", "initial ART Auto profile discovery completed")
                }.onFailure {
                    Log.e("ART-BOOT", "initial ART Auto profile discovery failed", it)
                }
                runCatching {
                    val artRefresh = androidx.work.PeriodicWorkRequestBuilder<
                        io.nekohasekai.sfa.artvpn.ArtAutoProfileWorker
                    >(1, java.util.concurrent.TimeUnit.HOURS).build()
                    androidx.work.WorkManager.getInstance(this@Application).enqueueUniquePeriodicWork(
                        "art-auto-profile-refresh",
                        androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                        artRefresh
                    )
                    // A physical network can appear shortly after Application.onCreate.
                    // Retry bootstrap once; revision checks make successful installs a no-op.
                    val artBootstrapRetry = androidx.work.OneTimeWorkRequestBuilder<
                        io.nekohasekai.sfa.artvpn.ArtAutoProfileWorker
                    >().setInitialDelay(20, java.util.concurrent.TimeUnit.SECONDS).build()
                    androidx.work.WorkManager.getInstance(this@Application).enqueueUniqueWork(
                        "art-auto-profile-bootstrap-retry",
                        androidx.work.ExistingWorkPolicy.KEEP,
                        artBootstrapRetry
                    )
                    Log.i("ART-BOOT", "automatic hourly refresh and startup retry scheduled")
                }.onFailure {
                    Log.e("ART-BOOT", "failed to schedule ART profile refresh", it)
                }
            } else {
                val error = artLibboxSetup.exceptionOrNull()
                if (error != null) Log.e("ART-BOOT", "libbox setup failed; ART discovery skipped", error)
            }""";
    a = a.replace(needle, replacement, 1)
application.write_text(a)

# Schedule automatic server-list refresh every six hours, with the first import
# already performed by Application on first start.
if "ArtAutoProfileWorker" not in a:
    raise SystemExit("ART profile bootstrap was not injected")

# Replace the upstream multi-screen dashboard with a single large ART control.
# The official VPN service, permission flow, libbox runtime and profile manager
# remain untouched; the screen verifies actual HTTPS over the active VPN tunnel.
compose_dir = up / "app/src/main/java/io/nekohasekai/sfa/compose"
compose_dir.mkdir(parents=True, exist_ok=True)
(compose_dir / "ArtHomeScreen.kt").write_bytes(
    (art / "upstream-overlay/ArtHomeScreen.kt").read_bytes()
)

main_activity = compose_dir / "MainActivity.kt"
main_text = main_activity.read_text()
start_marker = "        val scaffoldContent: @Composable (PaddingValues) -> Unit = { paddingValues ->"
end_marker = "        val crashReportUnreadCount by CrashReportManager.unreadCount.collectAsState()"
assert main_text.count(start_marker) == 1, "Unexpected MainActivity scaffoldContent layout"
assert main_text.count(end_marker) == 1, "Unexpected MainActivity scaffold end marker"
start = main_text.index(start_marker)
end = main_text.index(end_marker, start)
simple_content = """        val scaffoldContent: @Composable (PaddingValues) -> Unit = { paddingValues ->
            ArtHomeScreen(
                serviceStatus = currentServiceStatus,
                canConnect = dashboardUiState.selectedProfileId != -1L,
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                onConnect = { startService() },
                onDisconnect = { dashboardViewModel.toggleService() },
                onRetry = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            io.nekohasekai.sfa.artvpn.ArtAutoProfileManager.installOrRefresh(
                                this@MainActivity,
                                force = true
                            )
                        }.onFailure {
                            Log.w("ART VPN", "Manual server search failed: ${it.message}", it)
                        }
                    }
                },
            )
        }

"""
main_text = main_text[:start] + simple_content + main_text[end:]

rail_block = """        val useNavigationRail =
            windowSizeClass.isWidthAtLeastBreakpointCompat(WindowSizeClassCompat.WIDTH_DP_MEDIUM_LOWER_BOUND)"""
assert main_text.count(rail_block) == 1, "Unexpected adaptive navigation layout"
main_text = main_text.replace(rail_block, "        val useNavigationRail = false", 1)

# No navigation tabs on the grandma-friendly single-screen build.
bottom_marker = "bottomBar = {"
bottom_pos = main_text.index(bottom_marker)
hide_condition = "if (!isSubScreen) {"
condition_pos = main_text.find(hide_condition, bottom_pos)
assert condition_pos >= 0, "Could not locate bottom navigation condition"
main_text = main_text[:condition_pos] + "if (false && !isSubScreen) {" + main_text[condition_pos + len(hide_condition):]

# Never interrupt first launch with the upstream update-check questionnaire.
prompt_old = "var showUpdateCheckPrompt by remember { mutableStateOf(!Settings.updateCheckPrompted) }"
assert main_text.count(prompt_old) == 1, "Unexpected update prompt state"
main_text = main_text.replace(
    prompt_old,
    "var showUpdateCheckPrompt by remember { mutableStateOf(false) }",
    1
)
main_activity.write_text(main_text)
PY

mkdir -p "$UPSTREAM_DIR/app/libs"
test -s "$UPSTREAM_DIR/app/libs/libbox.aar" || {
  echo "Patched libbox.aar is missing. Run scripts/build-libbox-art.sh first." >&2
  exit 1
}
test -s "$UPSTREAM_DIR/app/libs/libbox-legacy.aar" || {
  echo "Patched libbox-legacy.aar is missing. Run scripts/build-libbox-art.sh first." >&2
  exit 1
}

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

UNIVERSAL_APK=""
for candidate in "${APKS[@]}"; do
  if [[ "$candidate" == *universal* ]]; then UNIVERSAL_APK="$candidate"; break; fi
done
if [[ -z "$UNIVERSAL_APK" ]]; then
  echo "Could not find the universal APK required for Android VPN emulator testing" >&2
  exit 1
fi
cp "$UNIVERSAL_APK" "$ROOT/dist/ART-VPN-universal.apk"
