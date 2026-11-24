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

# Preserve URLTest's verified-fastest selection, but use a rotating provisional
# candidate until the first successful HTTPS test. Failed dials rotate to the
# next candidate instead of pinning the first dead node or black-holing traffic.
python3 - "$CORE_DIR" <<'PY'
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
\t\tcandidates := make([]adapter.Outbound, 0, len(g.outbounds))
\t\tvar current adapter.Outbound
\t\tswitch network {
\t\tcase N.NetworkTCP:
\t\t\tcurrent = g.selectedOutboundTCP
\t\tcase N.NetworkUDP:
\t\t\tcurrent = g.selectedOutboundUDP
\t\t}
\t\tfor _, detour := range g.outbounds {
\t\t\tif common.Contains(detour.Network(), network) {
\t\t\t\tcandidates = append(candidates, detour)
\t\t\t}
\t\t}
\t\tif len(candidates) == 0 {
\t\t\treturn nil, false
\t\t}
\t\t// Skip the outbound that just failed; wrap only after trying the pool.
\t\tif len(candidates) > 1 && current != nil {
\t\t\tif currentIndex := slices.Index(candidates, current); currentIndex >= 0 {
\t\t\t\treturn candidates[(currentIndex+1)%len(candidates)], false
\t\t\t}
\t\t}
\t\treturn candidates[0], false
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
text = text.replace(probe_timeout, "testCtx, cancel := context.WithTimeout(b.ctx, 6*time.Second)", 1)

# A failed DialContext deleted the health history but left the group's selected
# pointer intact. URLTest.DialContext then reused that stale pointer forever.
# Re-run selection after a dial failure and clear stale pointers when no tested
# outbound remains; this is the repeated-same-IP failure seen in ART's log.
dial_select = """\tif outbound == nil {
\t\toutbound, _ = s.group.Select(network)
\t}"""
dial_select_replacement = """\tif outbound == nil {
\t\ts.group.updateAccess.Lock()
\t\tswitch N.NetworkName(network) {
\t\tcase N.NetworkTCP:
\t\t\toutbound = s.group.selectedOutboundTCP
\t\tcase N.NetworkUDP:
\t\t\toutbound = s.group.selectedOutboundUDP
\t\t}
\t\tif outbound == nil {
\t\t\toutbound, _ = s.group.Select(network)
\t\t\tswitch N.NetworkName(network) {
\t\t\tcase N.NetworkTCP:
\t\t\t\ts.group.selectedOutboundTCP = outbound
\t\t\tcase N.NetworkUDP:
\t\t\t\ts.group.selectedOutboundUDP = outbound
\t\t\t}
\t\t}
\t\ts.group.updateAccess.Unlock()
\t}"""
assert text.count(dial_select) == 2, "URLTest first candidate selection changed"
text = text.replace(dial_select, dial_select_replacement)

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

assert text.count("performFailover(N.NetworkTCP, outbound)") == 1
assert text.count("performFailover(N.NetworkUDP, outbound)") == 1
assert "context.WithTimeout(ctx, 5*time.Second)" in text, "Fast VLESS dial timeout patch missing"
assert "currentIndex+1)%len(candidates)" in text, "Fallback does not skip the previous outbound"
assert "if outbound, _ := g.Select(N.NetworkTCP); outbound != nil && outbound != g.selectedOutboundTCP {" in text
assert "if outbound, _ := g.Select(N.NetworkUDP); outbound != nil && outbound != g.selectedOutboundUDP {" in text
assert "context.WithTimeout(b.ctx, 6*time.Second)" in text

# URLTest must not depend on a single health-check host: mobile operators
# and networks can filter gstatic while allowing other HTTPS destinations.
health_source = core / "common/urltest/urltest.go"
health_text = health_source.read_text()
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
\t\tprobeContext, cancel := context.WithTimeout(ctx, 2100*time.Millisecond)
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
assert "context.WithTimeout(ctx, 2100*time.Millisecond)" in new_health, "Multi-endpoint URLTest deadline missing"
assert health_text.count(old_health) == 1, "sing-box URLTest function changed; refusing unsafe patch"
health_text = health_text.replace(old_health, new_health, 1)
health_source.write_text(health_text)

source.write_text(text)
print("Patched URLTest: bounded multi-endpoint HTTPS fallback, guarded failover, 6s total probe deadline, WARN diagnostics")
PY

cd "$CORE_DIR"
go test ./protocol/group ./common/urltest

GOPATH_DIR="$(go env GOPATH)"
mkdir -p "$GOPATH_DIR/bin"
export PATH="$GOPATH_DIR/bin:$PATH"
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13

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
gomobile init

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
go run ./cmd/internal/build_libbox -target android -platform android/arm64

test -s "$UPSTREAM_DIR/app/libs/libbox.aar"
test -s "$UPSTREAM_DIR/app/libs/libbox-legacy.aar"
echo "Built patched libbox AARs:"
sha256sum "$UPSTREAM_DIR/app/libs/libbox.aar" "$UPSTREAM_DIR/app/libs/libbox-legacy.aar"
