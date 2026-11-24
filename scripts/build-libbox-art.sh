#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM_DIR="${1:?Pass the sing-box-for-android checkout directory}"
CORE_DIR="$ROOT/.sing-box-v1.14.4"
CORE_REPO="https://github.com/SagerNet/sing-box.git"
CORE_TAG="v1.14.4"

if [[ ! -d "$CORE_DIR/.git" ]]; then
  git clone --depth 1 --branch "$CORE_TAG" "$CORE_REPO" "$CORE_DIR"
else
  git -C "$CORE_DIR" fetch --depth 1 origin "refs/tags/$CORE_TAG:refs/tags/$CORE_TAG"
  git -C "$CORE_DIR" checkout --detach "$CORE_TAG"
fi

# The stock URLTest group falls back to the first configured outbound when it
# has no successful health-check history. That makes a dead first VLESS node
# look selected and sends all app traffic into its timeout. ART instead fails
# closed until at least one outbound completed a real HTTPS URL test.
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
\t\treturn nil, false
\t}"""
assert text.count(fallback) == 1, "sing-box URLTest fallback changed; refusing an unsafe patch"
text = text.replace(fallback, replacement, 1)

# Probe the ART pool in two batches rather than five. This shortens first
# connection setup while preserving full VLESS + TLS + HTTPS health checks.
concurrency = "batch.WithConcurrencyNum[any](10)"
assert text.count(concurrency) == 1, "sing-box URLTest concurrency changed"
text = text.replace(concurrency, "batch.WithConcurrencyNum[any](32)", 1)

source.write_text(text)
print("Patched URLTest: no unverified first-node fallback; probe concurrency=32")
PY

GOPATH_DIR="$(go env GOPATH)"
mkdir -p "$GOPATH_DIR/bin"
export PATH="$GOPATH_DIR/bin:$PATH"
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13
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
go run ./cmd/internal/build_libbox -target android

test -s "$UPSTREAM_DIR/app/libs/libbox.aar"
test -s "$UPSTREAM_DIR/app/libs/libbox-legacy.aar"
echo "Built patched libbox AARs:"
sha256sum "$UPSTREAM_DIR/app/libs/libbox.aar" "$UPSTREAM_DIR/app/libs/libbox-legacy.aar"
