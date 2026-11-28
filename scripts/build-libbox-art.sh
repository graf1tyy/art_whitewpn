#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM_DIR="${1:?Pass the sing-box-for-android checkout directory}"
UPSTREAM_REPO="https://github.com/SagerNet/sing-box-for-android.git"
UPSTREAM_COMMIT="f87c2ebdb7e34754be751bb5e58926db2fed8f8c"
CORE_DIR="$ROOT/.sing-box-v1.14.4"
CORE_REPO="https://github.com/SagerNet/sing-box.git"
CORE_TAG="v1.14.4"

# Prepare the Android source checkout before creating app/libs. The later app
# overlay script expects this directory to be a Git repository, and fetches
# the same pinned upstream commit again before applying ART changes.
if [[ ! -d "$UPSTREAM_DIR/.git" ]]; then
  if [[ -e "$UPSTREAM_DIR" ]]; then
    echo "Upstream path exists but is not a Git checkout: $UPSTREAM_DIR" >&2
    exit 1
  fi
  git clone --depth 1 "$UPSTREAM_REPO" "$UPSTREAM_DIR"
fi
git -C "$UPSTREAM_DIR" fetch --depth 1 origin "$UPSTREAM_COMMIT"
git -C "$UPSTREAM_DIR" checkout --detach "$UPSTREAM_COMMIT"

if [[ ! -d "$CORE_DIR/.git" ]]; then
  git clone --depth 1 --branch "$CORE_TAG" "$CORE_REPO" "$CORE_DIR"
else
  git -C "$CORE_DIR" fetch --depth 1 origin "refs/tags/$CORE_TAG:refs/tags/$CORE_TAG"
  git -C "$CORE_DIR" checkout --detach "$CORE_TAG"
fi

# URLTest uses only nodes that have passed VLESS + TLS + HTTP 204.
# Never select the first public-feed entry as an unverified provisional route.
# Failed selected-node dials clear that node's history and trigger healthy-node reselection.
python3 - "$CORE_DIR" "$ROOT/scripts/urltest_art_test.go" <<'PY'
from pathlib import Path
import sys

core = Path(sys.argv[1])
source = core / "protocol/group/urltest.go"
text = source.read_text()

fallback = """\tif minOutbound == nil {
\t\tfor _, detour := range g.outbounds {
\t\t\tif !common.Contains(detour.Network(), network) {
\t\t\t\tcontinue
\t\t\t}
\t\t\treturn detour, false
\t\t}
\t\treturn nil, false
\t}"""
replacement = """\tif minOutbound == nil {
\t\t// A node is not eligible merely because it was first in a public feed.
\t\t// Wait for an actual VLESS + TLS + HTTP 204 health result.
\t\treturn nil, false
\t}"""
assert text.count(fallback) == 1, "sing-box URLTest fallback changed; refusing an unsafe patch"
text = text.replace(fallback, replacement, 1)

# Probe the ART pool in two batches rather than five. This shortens first
# connection setup while preserving full VLESS + TLS + HTTPS health checks.
concurrency = "batch.WithConcurrencyNum[any](10)"
assert text.count(concurrency) == 1, "sing-box URLTest concurrency changed"
text = text.replace(concurrency, "batch.WithConcurrencyNum[any](32)", 1)

# Do not make a dead endpoint cost the full upstream 15-second timeout during
# each initial URLTest wave. Six seconds still allows TLS/HTTPS verification,
# while keeping automatic selection responsive on mobile networks.
probe_timeout = "testCtx, cancel := context.WithTimeout(b.ctx, C.TCPTimeout)"
assert text.count(probe_timeout) == 1, "URLTest probe timeout source changed"
text = text.replace(probe_timeout, "testCtx, cancel := context.WithTimeout(b.ctx, 8*time.Second)", 1)

# A failed DialContext deleted the health history but left the group's selected
# pointer intact. URLTest.DialContext then reused that stale pointer forever.
# Re-run selection after a dial failure and clear stale pointers when no tested
# outbound remains; this is the repeated-same-IP failure seen in ART's log.
dial_selection = """\ts.group.Touch()
\tvar outbound adapter.Outbound
\tswitch N.NetworkName(network) {
\tcase N.NetworkTCP:
\t\toutbound = s.group.selectedOutboundTCP
\tcase N.NetworkUDP:
\t\toutbound = s.group.selectedOutboundUDP
\tdefault:
\t\treturn nil, E.Extend(N.ErrUnknownNetwork, network)
\t}
\tif outbound == nil {
\t\toutbound, _ = s.group.Select(network)
\t}"""
dial_selection_replacement = """\ts.group.Touch()
\tvar outbound adapter.Outbound
\ts.group.updateAccess.Lock()
\tswitch N.NetworkName(network) {
\tcase N.NetworkTCP:
\t\toutbound = s.group.selectedOutboundTCP
\tcase N.NetworkUDP:
\t\toutbound = s.group.selectedOutboundUDP
\tdefault:
\t\ts.group.updateAccess.Unlock()
\t\treturn nil, E.Extend(N.ErrUnknownNetwork, network)
\t}
\tif outbound == nil {
\t\toutbound, _ = s.group.Select(network)
\t\tswitch N.NetworkName(network) {
\t\tcase N.NetworkTCP:
\t\t\ts.group.selectedOutboundTCP = outbound
\t\tcase N.NetworkUDP:
\t\t\ts.group.selectedOutboundUDP = outbound
\t\t}
\t}
\ts.group.updateAccess.Unlock()"""
assert text.count(dial_selection) == 1, "URLTest dial selection block changed"
text = text.replace(dial_selection, dial_selection_replacement, 1)

# Packet/UDP routing has a separate first-selection path. Persist its provisional
# candidate too, so a failed QUIC/UDP server is not selected again indefinitely.
listen_select = """\toutbound := s.group.selectedOutboundUDP
\tif outbound == nil {
\t\toutbound, _ = s.group.Select(N.NetworkUDP)
\t}"""
listen_select_replacement = """\ts.group.updateAccess.Lock()
\toutbound := s.group.selectedOutboundUDP
\tif outbound == nil {
\t\toutbound, _ = s.group.Select(N.NetworkUDP)
\t\ts.group.selectedOutboundUDP = outbound
\t}
\ts.group.updateAccess.Unlock()"""
assert text.count(listen_select) == 1, "URLTest UDP first candidate selection changed"
text = text.replace(listen_select, listen_select_replacement, 1)

# The stock sing-box dial deadline is 15 seconds. A TCP port can accept
# connections and still never complete a VLESS/TLS handshake, so the user would
# wait 15 seconds before failover. Bound each actual candidate attempt to 5s.
dial_call = """\tconn, err := outbound.DialContext(ctx, network, destination)"""
dial_call_replacement = """\tdialContext, cancelDial := context.WithTimeout(ctx, 5*time.Second)
\tconn, err := outbound.DialContext(dialContext, network, destination)
\tcancelDial()"""
assert text.count(dial_call) == 1, "URLTest TCP dial anchor changed"
text = text.replace(dial_call, dial_call_replacement, 1)

packet_call = """\tconn, err := outbound.ListenPacket(ctx, destination)"""
packet_call_replacement = """\tpacketContext, cancelPacket := context.WithTimeout(ctx, 5*time.Second)
\tconn, err := outbound.ListenPacket(packetContext, destination)
\tcancelPacket()"""
assert text.count(packet_call) == 1, "URLTest UDP dial anchor changed"
text = text.replace(packet_call, packet_call_replacement, 1)

dial_failure = """\ts.logger.ErrorContext(ctx, err)
\ts.group.history.DeleteURLTestHistory(outbound.Tag())
\treturn nil, err"""
dial_replacement_tcp = """\ts.logger.ErrorContext(ctx, err)
\ts.group.history.DeleteURLTestHistory(outbound.Tag())
\ts.group.performFailover(N.NetworkTCP, outbound)
\treturn nil, err"""
dial_replacement_udp = """\ts.logger.ErrorContext(ctx, err)
\ts.group.history.DeleteURLTestHistory(outbound.Tag())
\ts.group.performFailover(N.NetworkUDP, outbound)
\treturn nil, err"""
assert text.count(dial_failure) == 2, "Unexpected URLTest dial failure handling"
text = text.replace(dial_failure, dial_replacement_tcp, 1)
text = text.replace(dial_failure, dial_replacement_udp, 1)

# Surface health-check failures at WARN level because the ART profile normally
# runs with sing-box log level=warn. This reveals why candidates were rejected.
unavailable_log = 'b.logger.Debug("outbound ", tag, " unavailable: ", testResult.err)'
unavailable_warning = 'b.logger.Warn("outbound ", tag, " unavailable: ", testResult.err)'
assert text.count(unavailable_log) == 1, "URLTest failure log line changed"
text = text.replace(unavailable_log, unavailable_warning, 1)

update_check = """func (g *URLTestGroup) performUpdateCheck() {
\tg.updateAccess.Lock()
\tdefer g.updateAccess.Unlock()
\tvar (
\t\tupdated  bool
\t\tselected bool
\t)
\tif outbound, exists := g.Select(N.NetworkTCP); outbound != nil && (g.selectedOutboundTCP == nil || (exists && outbound != g.selectedOutboundTCP)) {
\t\tif g.selectedOutboundTCP != nil {
\t\t\tupdated = true
\t\t}
\t\tg.selectedOutboundTCP = outbound
\t\tselected = true
\t}
\tif outbound, exists := g.Select(N.NetworkUDP); outbound != nil && (g.selectedOutboundUDP == nil || (exists && outbound != g.selectedOutboundUDP)) {
\t\tif g.selectedOutboundUDP != nil {
\t\t\tupdated = true
\t\t}
\t\tg.selectedOutboundUDP = outbound
\t\tselected = true
\t}
\tif updated {
\t\tg.interruptGroup.Interrupt(g.interruptExternalConnections)
\t}
\tif selected {
\t\tg.history.NotifyUpdated()
\t}
}"""
update_replacement = """func (g *URLTestGroup) performUpdateCheck() {
\tg.updateAccess.Lock()
\tdefer g.updateAccess.Unlock()
\tvar (
\t\tupdated  bool
\t\tselected bool
\t)
\tif outbound, _ := g.Select(N.NetworkTCP); outbound != nil && outbound != g.selectedOutboundTCP {
\t\tif g.selectedOutboundTCP != nil {
\t\t\tupdated = true
\t\t}
\t\tg.selectedOutboundTCP = outbound
\t\tselected = true
\t} else if outbound == nil && g.selectedOutboundTCP != nil {
\t\tg.selectedOutboundTCP = nil
\t\tupdated = true
\t\tselected = true
\t}
\tif outbound, _ := g.Select(N.NetworkUDP); outbound != nil && outbound != g.selectedOutboundUDP {
\t\tif g.selectedOutboundUDP != nil {
\t\t\tupdated = true
\t\t}
\t\tg.selectedOutboundUDP = outbound
\t\tselected = true
\t} else if outbound == nil && g.selectedOutboundUDP != nil {
\t\tg.selectedOutboundUDP = nil
\t\tupdated = true
\t\tselected = true
\t}
\tif updated {
\t\tg.interruptGroup.Interrupt(g.interruptExternalConnections)
\t}
\tif selected {
\t\tg.history.NotifyUpdated()
\t}
\thealthy := 0
\tfor _, detour := range g.outbounds {
\t\tif g.history.LoadURLTestHistory(RealTag(detour, N.NetworkTCP)) != nil {
\t\t\thealthy++
\t\t}
\t}
\tg.logger.Info("ART URLTest health: ", healthy, "/", len(g.outbounds), " VLESS nodes")
}"""
assert text.count(update_check) == 1, "URLTest selection update implementation changed"
text = text.replace(update_check, update_replacement, 1)

failover_helper = """\n\n// Only the currently selected outbound may advance the provisional fallback.
// Late failures from earlier in-flight requests must not skip a healthy next node.
func (g *URLTestGroup) performFailover(network string, failed adapter.Outbound) {
\tg.updateAccess.Lock()
\tdefer g.updateAccess.Unlock()
\tvar current adapter.Outbound
\tswitch network {
\tcase N.NetworkTCP:
\t\tcurrent = g.selectedOutboundTCP
\tcase N.NetworkUDP:
\t\tcurrent = g.selectedOutboundUDP
\tdefault:
\t\treturn
\t}
\tif current != failed {
\t\treturn
\t}
\tnext, _ := g.Select(network)
\tif next == failed {
\t\treturn
\t}
\tswitch network {
\tcase N.NetworkTCP:
\t\tg.selectedOutboundTCP = next
\tcase N.NetworkUDP:
\t\tg.selectedOutboundUDP = next
\t}
\tg.interruptGroup.Interrupt(g.interruptExternalConnections)
\tg.history.NotifyUpdated()
}"""
assert text.count(update_replacement) == 1, "URLTest update helper anchor changed"
text = text.replace(update_replacement, update_replacement + failover_helper, 1)

# Emit lifecycle checkpoints on Android so a stuck or unstarted URLTest group
# is distinguishable from a pool containing no healthy servers.
start_anchor = """		s.group = group
	case adapter.StartStateStarted:
		s.group.PostStart()
		scope.Add(s.group.Close)"""
start_replacement = """		s.group = group
		s.logger.Info("ART URLTest group created: candidates=", len(s.tags), " healthURL=", s.link)
	case adapter.StartStateStarted:
		s.logger.Info("ART URLTest lifecycle reached Started; scheduling initial health check")
		s.group.PostStart()
		s.logger.Info("ART URLTest PostStart returned")
		scope.Add(s.group.Close)"""
assert text.count(start_anchor) == 1, "URLTest lifecycle start anchor changed"
text = text.replace(start_anchor, start_replacement, 1)

post_start_old = """func (g *URLTestGroup) PostStart() {
	g.access.Lock()
	defer g.access.Unlock()
	g.started = true
	g.lastActive.Store(time.Now())
	go g.CheckOutbounds(g.ctx, false)
}"""
post_start_new = """func (g *URLTestGroup) PostStart() {
	g.access.Lock()
	if g.started {
		g.access.Unlock()
		return
	}
	g.started = true
	g.lastActive.Store(time.Now())
	g.logger.Warn("ART URLTest health scheduler started: candidates=", len(g.outbounds), " interval=", g.interval)
	g.access.Unlock()
	go func() {
		startedAt := time.Now()
		g.CheckOutbounds(g.ctx, false)
		g.logger.Info("ART URLTest initial check goroutine completed after ", time.Since(startedAt))
	}()
}"""
assert text.count(post_start_old) == 1, "URLTest PostStart anchor changed"
text = text.replace(post_start_old, post_start_new, 1)

# As a defensive fallback, the first route lookup starts URLTest even if an
# upstream lifecycle callback was missed. PostStart is idempotent, so this is
# safe when the normal StartStateStarted callback also runs.
selected_old = """func (s *URLTest) Selected(network string) adapter.Outbound {
	var outbound adapter.Outbound"""
selected_new = """func (s *URLTest) Selected(network string) adapter.Outbound {
	if s.group == nil {
		s.logger.Warn("ART URLTest selected before group initialization")
		return nil
	}
	s.group.PostStart()
	var outbound adapter.Outbound"""
assert text.count(selected_old) == 1, "URLTest Selected method anchor changed"
text = text.replace(selected_old, selected_new, 1)
assert "s.group.PostStart()\n\tvar outbound adapter.Outbound" in text

url_test_old = """func (g *URLTestGroup) urlTest(ctx context.Context, force bool) (map[string]uint16, error) {
	if g.checking.Swap(true) {
		return make(map[string]uint16), nil
	}
	defer g.checking.Store(false)
	result := URLTestOutbounds(ctx, g.outbound, g.history, g.logger, g.outbounds, g.link, g.interval, force)
	g.performUpdateCheck()
	return result, nil
}"""
url_test_new = """func (g *URLTestGroup) urlTest(ctx context.Context, force bool) (map[string]uint16, error) {
	if g.checking.Swap(true) {
		g.logger.Warn("ART URLTest check skipped: another check is already running")
		return make(map[string]uint16), nil
	}
	defer g.checking.Store(false)
	startedAt := time.Now()
	g.logger.Info("ART URLTest check started: candidates=", len(g.outbounds), " force=", force)
	result := URLTestOutbounds(ctx, g.outbound, g.history, g.logger, g.outbounds, g.link, g.interval, force)
	g.performUpdateCheck()
	g.logger.Info("ART URLTest check finished: healthy=", len(result), "/", len(g.outbounds), " elapsed=", time.Since(startedAt))
	return result, nil
}"""
assert text.count(url_test_old) == 1, "URLTest batch entry anchor changed"
text = text.replace(url_test_old, url_test_new, 1)

batch_old = """func URLTestOutbounds(ctx context.Context, outboundManager adapter.OutboundManager, history *urltest.HistoryStorage, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, force bool) map[string]uint16 {
	b, _ := batch.New(ctx, batch.WithConcurrencyNum[any](32))"""
batch_new = """func URLTestOutbounds(ctx context.Context, outboundManager adapter.OutboundManager, history *urltest.HistoryStorage, logger log.Logger, outbounds []adapter.Outbound, link string, interval time.Duration, force bool) map[string]uint16 {
	startedAt := time.Now()
	logger.Info("ART URLTest batch scheduling: candidates=", len(outbounds), " concurrency=32 force=", force)
	b, _ := batch.New(ctx, batch.WithConcurrencyNum[any](32))"""
assert text.count(batch_old) == 1, "URLTest batch scheduling anchor changed"
text = text.replace(batch_old, batch_new, 1)

wait_old = """	testBatch.test(outbounds, link, interval, force)
	b.Wait()
	for _, outboundGroup := range testBatch.groups {"""
wait_new = """	testBatch.test(outbounds, link, interval, force)
	logger.Info("ART URLTest batch waiting for ", len(testBatch.checked), " scheduled outbound checks")
	b.Wait()
	logger.Info("ART URLTest batch completed: checked=", len(testBatch.checked), " healthy=", len(testBatch.result), " elapsed=", time.Since(startedAt))
	for _, outboundGroup := range testBatch.groups {"""
assert text.count(wait_old) == 1, "URLTest batch completion anchor changed"
text = text.replace(wait_old, wait_new, 1)

assert text.count("performFailover(N.NetworkTCP, outbound)") == 1
assert text.count("performFailover(N.NetworkUDP, outbound)") == 1
assert "context.WithTimeout(ctx, 5*time.Second)" in text, "Fast VLESS dial timeout patch missing"
assert "A node is not eligible merely because it was first in a public feed." in text, "Untested public-feed fallback is not disabled"
assert "return nil, false" in text, "URLTest still has an unsafe default candidate"
assert "if outbound, _ := g.Select(N.NetworkTCP); outbound != nil && outbound != g.selectedOutboundTCP {" in text
assert "if outbound, _ := g.Select(N.NetworkUDP); outbound != nil && outbound != g.selectedOutboundUDP {" in text
assert "context.WithTimeout(b.ctx, 8*time.Second)" in text
assert "s.group.updateAccess.Lock()" in dial_selection_replacement

# URLTest must not depend on a single health-check host: mobile operators
# and networks can filter gstatic while allowing other HTTPS destinations.
health_source = core / "common/urltest/urltest.go"
health_text = health_source.read_text()
import_anchor = 'import (\n\t"context"\n\t"crypto/tls"'
import_replacement = 'import (\n\t"context"\n\t"crypto/tls"\n\t"fmt"'
assert health_text.count(import_anchor) == 1, "URLTest import block changed"
health_text = health_text.replace(import_anchor, import_replacement, 1)
old_health = """func URLTest(ctx context.Context, link string, detour N.Dialer) (uint16, error) {
\tmultiplexOutbound, isMultiplexOutbound := common.Cast[adapter.OutboundWithMultiplex](detour)
\tif isMultiplexOutbound && multiplexOutbound.MultiplexEnabled() {
\t\twarmContext := adapter.ContextWithKeepSession(ctx)
\t\twarmContext = mux.ContextWithKeepSession(warmContext)
\t\twarmContext = contextWithQUICKeepSession(warmContext)
\t\twarmContext = snell.ContextWithKeepSession(warmContext)
\t\t_, err := urlTest(warmContext, link, detour)
\t\tif err != nil {
\t\t\treturn 0, err
\t\t}
\t}
\treturn urlTest(ctx, link, detour)
}"""
new_health = """func URLTest(ctx context.Context, link string, detour N.Dialer) (uint16, error) {
\tif link == "" {
\t\tlink = "https://www.gstatic.com/generate_204"
\t}
\tlinks := []string{link}
\tif link != "https://cp.cloudflare.com/generate_204" {
\t\tlinks = append(links, "https://cp.cloudflare.com/generate_204")
\t}
\tvar lastError error
\tfor _, probeURL := range links {
\t\tprobeContext, cancel := context.WithTimeout(ctx, 2600*time.Millisecond)
\t\tmultiplexOutbound, isMultiplexOutbound := common.Cast[adapter.OutboundWithMultiplex](detour)
\t\tif isMultiplexOutbound && multiplexOutbound.MultiplexEnabled() {
\t\t\twarmContext := adapter.ContextWithKeepSession(probeContext)
\t\t\twarmContext = mux.ContextWithKeepSession(warmContext)
\t\t\twarmContext = contextWithQUICKeepSession(warmContext)
\t\t\twarmContext = snell.ContextWithKeepSession(warmContext)
\t\t\t_, err := urlTest(warmContext, probeURL, detour)
\t\t\tif err != nil {
\t\t\t\tcancel()
\t\t\t\tlastError = err
\t\t\t\tif ctx.Err() != nil {
\t\t\t\t\treturn 0, ctx.Err()
\t\t\t\t}
\t\t\t\tcontinue
\t\t\t}
\t\t}
\t\tdelay, err := urlTest(probeContext, probeURL, detour)
\t\tcancel()
\t\tif err == nil {
\t\t\treturn delay, nil
\t\t}
\t\tlastError = err
\t\tif ctx.Err() != nil {
\t\t\treturn 0, ctx.Err()
\t\t}
\t}
\treturn 0, lastError
}"""
assert "probeContext, cancel := context.WithTimeout(ctx, 2600*time.Millisecond)" in new_health, "Multi-endpoint URLTest deadline missing"
assert health_text.count(old_health) == 1, "sing-box URLTest function changed; refusing unsafe patch"
health_text = health_text.replace(old_health, new_health, 1)

# A successful TCP/TLS handshake is not enough: only the dedicated no-content
# health response is considered proof of usable outbound internet.
status_before = """\tresp.Body.Close()
\tt = uint16(time.Since(start) / time.Millisecond)"""
status_after = """\tresp.Body.Close()
\tif resp.StatusCode != http.StatusNoContent {
\t\treturn 0, fmt.Errorf("health check returned HTTP %d, expected 204", resp.StatusCode)
\t}
\tt = uint16(time.Since(start) / time.Millisecond)"""
assert health_text.count(status_before) == 1, "URLTest HTTP status anchor changed"
health_text = health_text.replace(status_before, status_after, 1)
assert "resp.StatusCode != http.StatusNoContent" in health_text, "Strict HTTP 204 health check missing"
health_source.write_text(health_text)

# Add executable regression tests for success, captive portal and HTTP error responses.
test_source = Path(sys.argv[2])
test_destination = core / "common/urltest/urltest_art_test.go"
test_destination.write_bytes(test_source.read_bytes())

source.write_text(text)
print("Patched URLTest: bounded multi-endpoint HTTPS fallback, guarded failover, 6s total probe deadline, WARN diagnostics")
PY

cd "$CORE_DIR"
echo "[ART-BUILD] Running URLTest regression tests (3 minute limit)"
go test -timeout 3m ./protocol/group ./common/urltest

GOPATH_DIR="$(go env GOPATH)"
mkdir -p "$GOPATH_DIR/bin"
export PATH="$GOPATH_DIR/bin:$PATH"
echo "[ART-BUILD] Installing gomobile (10 minute limit)"
timeout 10m go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
echo "[ART-BUILD] Installing gobind (10 minute limit)"
timeout 10m go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13

# gomobile needs an explicit NDK path; setup-android only guarantees the SDK
# root, while the NDK is installed in a versioned directory.
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
test -n "$ANDROID_HOME"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.0.13004108}"
test -s "$ANDROID_NDK_HOME/source.properties"
export ANDROID_HOME
export ANDROID_SDK_HOME="$ANDROID_HOME"
export ANDROID_NDK_HOME
export NDK="$ANDROID_NDK_HOME"
echo "[ART-BUILD] Initializing gomobile (3 minute limit)"
timeout 3m gomobile init

# The Go builder copies AARs to ../sing-box-for-android/app/libs when that
# sibling exists. Use a temporary link so its output lands in this app.
mkdir -p "$UPSTREAM_DIR/app/libs"
LINK="$ROOT/sing-box-for-android"
if [[ ! -e "$LINK" ]]; then
  ln -s "$UPSTREAM_DIR" "$LINK"
  LINK_CREATED=1
else
  LINK_CREATED=0
fi
cleanup() {
  if [[ "$LINK_CREATED" == "1" && -L "$LINK" ]]; then rm "$LINK"; fi
}
trap cleanup EXIT

cd "$CORE_DIR"
echo "[ART-BUILD] Building multi-ABI Android libbox AAR (30 minute limit)"
# Do not pass -platform android/arm64 here: gomobile's default android target
# bundles arm, arm64, x86 and x86_64. The APK has an emulator test and a
# universal split, so every ABI it advertises must ship libbox.so.
timeout 30m go run ./cmd/internal/build_libbox -target android

test -s "$UPSTREAM_DIR/app/libs/libbox.aar"
test -s "$UPSTREAM_DIR/app/libs/libbox-legacy.aar"
python3 - "$UPSTREAM_DIR/app/libs/libbox.aar" "$UPSTREAM_DIR/app/libs/libbox-legacy.aar" <<'PY'
import sys
import zipfile

required = {"arm64-v8a", "x86_64"}
for path in sys.argv[1:]:
    with zipfile.ZipFile(path) as aar:
        entries = aar.namelist()
    abis = {
        entry.split("/")[1]
        for entry in entries
        if entry.startswith("jni/") and entry.endswith("/libbox.so")
    }
    print(f"{path}: packaged libbox.so ABIs={sorted(abis)}")
    missing = required - abis
    if missing:
        raise SystemExit(f"{path} is missing native libbox.so for: {sorted(missing)}")
PY

# Verify that the patched Go implementation is actually present in the
# native libraries consumed by the Android app. This prevents a successful
# gomobile invocation from silently packaging an upstream/stale engine.
python3 - "$UPSTREAM_DIR/app/libs/libbox.aar" <<'PY'
import sys
import zipfile

required_abis = {"arm64-v8a", "x86_64"}
required_markers = (
    b"ART URLTest group created: candidates=",
    b"ART URLTest health scheduler started: candidates=",
    b"ART URLTest batch completed: checked=",
)
with zipfile.ZipFile(sys.argv[1]) as aar:
    binaries = {
        entry.split("/")[1]: aar.read(entry)
        for entry in aar.namelist()
        if entry.startswith("jni/") and entry.endswith("/libbox.so")
    }
missing_abis = required_abis - binaries.keys()
if missing_abis:
    raise SystemExit(f"Main libbox AAR is missing ABIs: {sorted(missing_abis)}")
for abi in sorted(required_abis):
    binary = binaries[abi]
    missing_markers = [marker.decode() for marker in required_markers if marker not in binary]
    if missing_markers:
        raise SystemExit(f"{abi} libbox.so does not contain ART URLTest patch markers: {missing_markers}")
    print(f"{abi}: ART URLTest patch markers verified in libbox.so")
PY
echo "Built patched libbox AARs:"
sha256sum "$UPSTREAM_DIR/app/libs/libbox.aar" "$UPSTREAM_DIR/app/libs/libbox-legacy.aar"
