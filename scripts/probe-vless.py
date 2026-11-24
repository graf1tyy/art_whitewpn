#!/usr/bin/env python3
"""Build gate: verify real VLESS + HTTPS traffic through sing-box before APK publication."""
import concurrent.futures
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.parse import unquote, urlsplit
from urllib.request import Request, urlopen

MAX_NODES = 48
PORT = 10808
PROBE_URL = "https://www.gstatic.com/generate_204"
UUID_RE = re.compile(r"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
SUPPORTED_FINGERPRINTS = {
    "chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
    "chrome_pq", "chrome_pq_psk", "firefox", "edge", "safari", "360",
    "qq", "ios", "android", "random", "randomized"
}
RESULT_RE = re.compile(r"outbound\s+(art-[0-9a-f]{16})\s+(available|unavailable)")
FEEDS = [
 ["https://raw.githubusercontent.com/mehrtat/vless-collector/main/vless.txt","https://cdn.jsdelivr.net/gh/mehrtat/vless-collector@main/vless.txt"],
 ["https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/best.txt","https://cdn.jsdelivr.net/gh/morpheusadam/v2ray-config@main/subs/bundles/best.txt"],
 ["https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt","https://codeberg.org/zieng2/wl/raw/branch/main/vless_universal.txt","https://gitlab.com/zieng2/wl/raw/main/vless_universal.txt"],
 ["https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt","https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/Vless-Reality-White-Lists-Rus-Mobile.txt","https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt"],
 ["https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt","https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/WHITE-CIDR-RU-all.txt","https://raw.githack.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt"],
]

def decode(value):
    return unquote(value.replace("+", "%2B"), encoding="utf-8", errors="strict")

def parse(link):
    try:
        u = urlsplit(link.strip())
        if u.scheme.lower() != "vless" or not u.hostname or u.port is None:
            return None
        uid = u.username or ""
        if not UUID_RE.fullmatch(uid):
            return None
        q = {}
        for part in u.query.split("&"):
            if part:
                k, _, v = part.partition("=")
                q[decode(k)] = decode(v)
        return u, uid, q
    except (ValueError, UnicodeError):
        return None

def identity(link):
    item = parse(link)
    if not item:
        return None
    u, uid, q = item
    transport = q.get("type", "").lower()
    if transport in ("tcp", "raw"):
        transport = ""
    security = q.get("security", "").lower()
    if security == "none":
        security = ""
    fields = [uid.lower(), u.hostname.lower(), str(u.port), security, transport,
              q.get("flow", ""), q.get("sni", "").lower(), q.get("pbk", ""),
              q.get("sid", "").lower(), q.get("alpn", "").lower(),
              q.get("host", "").lower(), q.get("path", ""),
              q.get("serviceName", q.get("service_name", "")),
              q.get("insecure", ""), q.get("allowInsecure", "")]
    return "\x1f".join(fields)

def compile_node(link):
    item = parse(link)
    if not item:
        return None
    u, uid, q = item
    sec = q.get("security", "").lower()
    kind = q.get("type", "").lower()
    transport = "tcp" if kind == "raw" else kind
    flow = q.get("flow", "")
    if sec not in ("", "none", "tls", "reality") or transport not in ("", "tcp", "ws", "grpc", "http", "httpupgrade"):
        return None
    if flow and (flow != "xtls-rprx-vision" or sec not in ("tls", "reality")):
        return None
    ident = identity(link)
    if not ident:
        return None
    out = {"type":"vless","tag":"art-"+hashlib.sha256(ident.encode()).hexdigest()[:16],
           "server":u.hostname,"server_port":u.port,"uuid":uid,
           "packet_encoding":"xudp","domain_resolver":"local"}
    if flow:
        out["flow"] = flow
    if sec in ("tls", "reality"):
        tls = {"enabled":True}
        if q.get("sni"):
            tls["server_name"] = q["sni"]
        fingerprint = (q.get("fp") or "chrome").lower()
        if fingerprint not in SUPPORTED_FINGERPRINTS:
            return None
        tls["utls"] = {"enabled":True,"fingerprint":fingerprint}
        if q.get("alpn"):
            tls["alpn"] = [x.strip() for x in q["alpn"].split(",") if x.strip()]
        if sec == "reality":
            if not q.get("pbk"):
                return None
            tls["reality"] = {"enabled":True,"public_key":q["pbk"],"short_id":q.get("sid","")}
        if q.get("allowInsecure") == "1" or q.get("insecure") == "1":
            tls["insecure"] = True
        out["tls"] = tls
    if transport == "ws":
        t = {"type":"ws","path":q.get("path") or "/"}
        if q.get("host"):
            t["headers"] = {"Host":q["host"]}
        out["transport"] = t
    elif transport == "grpc":
        t = {"type":"grpc"}
        service = q.get("serviceName",q.get("service_name",""))
        if service:
            t["service_name"] = service
        out["transport"] = t
    elif transport == "http":
        t = {"type":"http","path":q.get("path") or "/"}
        if q.get("host"):
            t["host"] = [q["host"]]
        out["transport"] = t
    elif transport == "httpupgrade":
        t = {"type":"httpupgrade","path":q.get("path") or "/"}
        if q.get("host"):
            t["headers"] = {"Host":q["host"]}
        out["transport"] = t
    return out

def fetch_group(mirrors):
    for source in mirrors:
        try:
            req = Request(source, headers={"User-Agent":"ART-VPN-HealthCheck/1.0","Accept":"text/plain,*/*"})
            with urlopen(req, timeout=12) as response:
                body = response.read(4*1024*1024+1)
            if len(body) > 4*1024*1024:
                continue
            text = body.decode("utf-8",errors="replace").replace("&amp;","&")
            links = []
            for match in re.finditer(r"""(?i)vless://[^\s"'<>]+""", text):
                link = match.group(0).rstrip(",;)] .")
                if compile_node(link):
                    links.append(link)
            if links:
                print(f"Feed loaded: {source.rsplit('/',1)[-1]} ({len(links)} supported links)")
                return links
        except Exception as error:
            print(f"Mirror failed: {source} ({type(error).__name__})")
    print(f"Feed unavailable: {mirrors[0]}")
    return []

def reachable(link):
    item = parse(link)
    if not item:
        return False
    u = item[0]
    try:
        addresses = socket.getaddrinfo(u.hostname,u.port,type=socket.SOCK_STREAM)
    except OSError:
        return False
    for family, socktype, proto, _, addr in addresses:
        try:
            with socket.socket(family,socktype,proto) as s:
                s.settimeout(1.8)
                s.connect(addr)
            return True
        except OSError:
            pass
    return False

def main():
    binary = str(Path(sys.argv[1]).resolve())
    feeds = [fetch_group(group) for group in FEEDS]
    merged = [feeds[j][i] for i in range(max((len(x) for x in feeds),default=0)) for j in range(len(feeds)) if i < len(feeds[j])]
    seen, candidates = set(), []
    for link in sorted(merged,key=lambda x: {"":0,"chrome":0,"edge":1,"firefox":2,"safari":3,"android":4}.get((parse(x) or (None,None,{}))[2].get("fp","").lower(),5)):
        key = identity(link)
        if key and key not in seen and compile_node(link):
            seen.add(key)
            candidates.append(link)
            if len(candidates) >= 320:
                break
    print(f"Pool: {len(merged)} entries, {len(candidates)} unique supported configurations")
    ok = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=32) as pool:
        for start in range(0,len(candidates),64):
            batch = candidates[start:start+64]
            ok.extend(x for x, good in zip(batch,pool.map(reachable,batch)) if good)
            if len(ok) >= MAX_NODES:
                break
    nodes = [compile_node(x) for x in ok[:MAX_NODES]]
    nodes = [x for x in nodes if x]
    if not nodes:
        print("FAIL: no server passed the TCP pre-filter")
        return 1
    tags = [x["tag"] for x in nodes]
    hosts = {x["tag"]:f'{x["server"]}:{x["server_port"]}' for x in nodes}
    config = {
      "log":{"level":"debug","timestamp":True},
      "dns":{"servers":[{"type":"local","tag":"local"}],"final":"local"},
      "inbounds":[{"type":"socks","tag":"probe-in","listen":"127.0.0.1","listen_port":PORT}],
      "outbounds":nodes+[{"type":"urltest","tag":"art-auto","outbounds":tags,"url":PROBE_URL,"interval":"30s","tolerance":60,"interrupt_exist_connections":True},{"type":"direct","tag":"direct"}],
      "route":{"final":"art-auto"}
    }
    with tempfile.TemporaryDirectory(prefix="art-vless-probe-") as temp:
        cfg, log = Path(temp)/"config.json", Path(temp)/"probe.log"
        cfg.write_text(json.dumps(config),encoding="utf-8")
        with log.open("w",encoding="utf-8") as output:
            proc = subprocess.Popen([binary,"run","-c",str(cfg)],stdout=output,stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic()+110
                while time.monotonic()<deadline and proc.poll() is None:
                    content = log.read_text(encoding="utf-8",errors="replace")
                    result = {m.group(1):m.group(2) for m in RESULT_RE.finditer(content)}
                    if len(result)>=len(tags):
                        break
                    time.sleep(1)
                time.sleep(2)
                content = log.read_text(encoding="utf-8",errors="replace")
                result = {m.group(1):m.group(2) for m in RESULT_RE.finditer(content)}
                passed = [tag for tag,state in result.items() if state=="available"]
                print(f"Full VLESS/TLS/Reality + HTTPS check: {len(passed)}/{len(tags)} passed; {len(result)}/{len(tags)} completed")
                if len(result)<len(tags) or not passed:
                    print("FAIL: end-to-end health check did not prove a working server")
                    print("\n".join(content.splitlines()[-80:]))
                    return 1
                curl = subprocess.run(["curl","--silent","--show-error","--output",os.devnull,"--write-out","%{http_code}","--max-time","20","--socks5-hostname",f"127.0.0.1:{PORT}",PROBE_URL],text=True,capture_output=True)
                print(f"HTTPS through auto-selected VLESS: exit={curl.returncode}, HTTP={curl.stdout.strip() or 'none'}")
                if curl.returncode or curl.stdout.strip()!="204":
                    print("FAIL: selected outbound did not carry the real HTTPS request")
                    print(curl.stderr.strip())
                    print("\n".join(content.splitlines()[-80:]))
                    return 1
                print("PASS: real HTTPS traffic passed through ART Auto")
                for tag in passed:
                    print(f"  healthy {tag} at {hosts.get(tag,'unknown')}")
                summary = os.environ.get("GITHUB_STEP_SUMMARY")
                if summary:
                    with open(summary,"a",encoding="utf-8") as stream:
                        stream.write(f"\n### ART VPN server check\n\n- Candidate nodes: {len(tags)}\n- End-to-end healthy: {len(passed)}\n- HTTPS through auto-selection: **HTTP 204 — PASS**\n")
                return 0
            finally:
                if proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait(timeout=5)

if __name__ == "__main__":
    raise SystemExit(main())
