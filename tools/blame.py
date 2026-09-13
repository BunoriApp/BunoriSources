#!/usr/bin/env python3
"""
blame.py - HTTP journey / latency profiler for crawler debugging.

For a given URL, this walks through the sequence of HTTP requests a crawler
would issue (metadata fetch, chapter-list fetch, sample content fetch, etc.),
times each request at the socket level (DNS, TCP connect, TLS handshake,
TTFB, body transfer), and prints a plain, greppable report of where time
actually went.

Site-specific request sequences are registered as SiteProfile subclasses.
Anything not matching a registered profile falls back to a single direct
GET against the URL, so the tool degrades gracefully instead of failing.

Usage:
    python3 blame.py <url>
    python3 blame.py <url> --json
    python3 blame.py <url> --timeout 15 --verbose

Exit codes:
    0  profiling completed (regardless of individual step failures)
    1  invalid arguments / no URL provided
"""

from __future__ import annotations

import argparse
import gzip
import json
import logging
import re
import socket
import ssl
import sys
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import Dict, List, Optional

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)
DEFAULT_TIMEOUT = 25.0
SLOW_STEP_THRESHOLD_MS = 2000.0

log = logging.getLogger("blame")


# --------------------------------------------------------------------------
# Formatting helpers
# --------------------------------------------------------------------------

def fmt_size(n: int) -> str:
    if n < 1024:
        return f"{n}B"
    if n < 1024 * 1024:
        return f"{n / 1024:.1f}KB"
    return f"{n / (1024 * 1024):.2f}MB"


def fmt_ms(ms: float) -> str:
    return f"{ms:.1f}ms" if ms < 1000 else f"{ms / 1000:.2f}s"


def dechunk(data: bytes) -> bytes:
    """Decode HTTP chunked transfer-encoding bodies."""
    out = bytearray()
    idx = 0
    while idx < len(data):
        crlf = data.find(b"\r\n", idx)
        if crlf == -1:
            break
        size_hex = data[idx:crlf].split(b";")[0].strip()
        if not size_hex:
            break
        try:
            size = int(size_hex, 16)
        except ValueError:
            break
        if size == 0:
            break
        start = crlf + 2
        end = start + size
        out.extend(data[start:end])
        idx = end + 2
    return bytes(out) if out else data


# --------------------------------------------------------------------------
# Core timing model
# --------------------------------------------------------------------------

@dataclass
class Timing:
    dns_ms: float = 0.0
    tcp_ms: float = 0.0
    tls_ms: float = 0.0
    ttfb_ms: float = 0.0
    transfer_ms: float = 0.0
    total_ms: float = 0.0


@dataclass
class HttpResult:
    step: int
    label: str
    method: str
    url: str
    timing: Timing
    status_code: int = 0
    status_line: str = ""
    headers: Dict[str, str] = field(default_factory=dict)
    body: bytes = b""
    error: Optional[str] = None
    note: str = ""          # what the step extracted / parsed
    preview: str = ""       # short content sample

    @property
    def size(self) -> int:
        return len(self.body)

    @property
    def ok(self) -> bool:
        return self.error is None and 200 <= self.status_code < 300

    def text(self) -> str:
        return self.body.decode("utf-8", errors="ignore")

    def to_dict(self) -> dict:
        t = self.timing
        return {
            "step": self.step,
            "label": self.label,
            "method": self.method,
            "url": self.url,
            "status_code": self.status_code,
            "size_bytes": self.size,
            "error": self.error,
            "note": self.note,
            "preview": self.preview,
            "timing_ms": {
                "dns": round(t.dns_ms, 1),
                "tcp": round(t.tcp_ms, 1),
                "tls": round(t.tls_ms, 1),
                "ttfb": round(t.ttfb_ms, 1),
                "transfer": round(t.transfer_ms, 1),
                "total": round(t.total_ms, 1),
            },
        }


def raw_request(
    step: int,
    label: str,
    url: str,
    method: str = "GET",
    headers: Optional[Dict[str, str]] = None,
    body: Optional[bytes] = None,
    timeout: float = DEFAULT_TIMEOUT,
) -> HttpResult:
    """
    Issues a single HTTP request over a raw socket, measuring each phase of
    the connection lifecycle independently. Deliberately bypasses libraries
    like requests/urllib3 so pooling/keep-alive behavior in the app under
    test can't mask what a cold connection actually costs.
    """
    parsed = urllib.parse.urlparse(url)
    host = parsed.hostname or ""
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    path = (parsed.path or "/") + (f"?{parsed.query}" if parsed.query else "")

    req_headers = {
        "Host": host,
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Sec-Ch-Ua": '"Not_A Brand";v="8", "Chromium";v="120", "Google Chrome";v="120"',
        "Sec-Ch-Ua-Mobile": "?0",
        "Sec-Ch-Ua-Platform": '"Windows"',
        "Sec-Fetch-Dest": "document",
        "Sec-Fetch-Mode": "navigate",
        "Sec-Fetch-Site": "none",
        "Sec-Fetch-User": "?1",
        "Upgrade-Insecure-Requests": "1",
        "Connection": "close",
        "Accept-Encoding": "gzip, deflate",
    }
    req_headers.update(headers or {})

    timing = Timing()
    t_start = time.perf_counter()
    sock: Optional[socket.socket] = None

    try:
        t0 = time.perf_counter()
        addr = socket.getaddrinfo(host, port, socket.AF_INET, socket.SOCK_STREAM)
        ip = addr[0][4][0]
        timing.dns_ms = (time.perf_counter() - t0) * 1000.0

        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        t0 = time.perf_counter()
        sock.connect((ip, port))
        timing.tcp_ms = (time.perf_counter() - t0) * 1000.0

        if parsed.scheme == "https":
            ctx = ssl.create_default_context()
            t0 = time.perf_counter()
            sock = ctx.wrap_socket(sock, server_hostname=host)
            timing.tls_ms = (time.perf_counter() - t0) * 1000.0

        lines = [f"{method} {path} HTTP/1.1"]
        lines += [f"{k}: {v}" for k, v in req_headers.items()]
        if body:
            lines.append(f"Content-Length: {len(body)}")
        lines.append("\r\n")
        payload = "\r\n".join(lines).encode("utf-8") + (body or b"")

        t_send = time.perf_counter()
        sock.sendall(payload)

        chunk = sock.recv(4096)
        timing.ttfb_ms = (time.perf_counter() - t_send) * 1000.0

        t_transfer = time.perf_counter()
        raw = bytearray(chunk)
        while True:
            try:
                chunk = sock.recv(16384)
            except socket.timeout:
                break
            if not chunk:
                break
            raw.extend(chunk)
        timing.transfer_ms = (time.perf_counter() - t_transfer) * 1000.0
        timing.total_ms = (time.perf_counter() - t_start) * 1000.0

        header_end = raw.find(b"\r\n\r\n")
        head, body_bytes = (raw[:header_end], bytes(raw[header_end + 4:])) if header_end != -1 else (raw, b"")

        head_lines = head.decode("latin1", errors="ignore").split("\r\n")
        status_line = head_lines[0] if head_lines else "HTTP/1.1 0 UNKNOWN"
        status_match = re.search(r"HTTP/\S+\s+(\d+)", status_line)
        status_code = int(status_match.group(1)) if status_match else 0

        resp_headers = {}
        for line in head_lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                resp_headers[k.strip().lower()] = v.strip()

        if resp_headers.get("transfer-encoding", "").lower() == "chunked":
            body_bytes = dechunk(body_bytes)
        if resp_headers.get("content-encoding", "").lower() == "gzip":
            try:
                body_bytes = gzip.decompress(body_bytes)
            except Exception:
                pass

        return HttpResult(
            step=step, label=label, method=method, url=url, timing=timing,
            status_code=status_code, status_line=status_line,
            headers=resp_headers, body=body_bytes,
        )

    except Exception as exc:
        timing.total_ms = (time.perf_counter() - t_start) * 1000.0
        return HttpResult(
            step=step, label=label, method=method, url=url, timing=timing,
            status_code=0, status_line="0 FAILED", error=str(exc),
        )
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass


# --------------------------------------------------------------------------
# Site profiles (pluggable request-sequence definitions)
#
# Each profile knows: (a) whether it applies to a given URL, and (b) how to
# walk the sequence of requests a real crawler would make, using data
# extracted from each response to build the next request. New sources are
# added by writing a SiteProfile subclass and registering an instance below
# -- nothing else in this file needs to change.
# --------------------------------------------------------------------------

class Journey:
    """Accumulates HttpResult steps for a single profiling run."""

    def __init__(self, timeout: float):
        self.timeout = timeout
        self.results: List[HttpResult] = []
        self._n = 0

    def request(self, label: str, url: str, headers: Optional[Dict[str, str]] = None) -> HttpResult:
        self._n += 1
        result = raw_request(self._n, label, url, headers=headers, timeout=self.timeout)
        self.results.append(result)
        return result


class SiteProfile:
    name = "generic"

    def matches(self, url: str) -> bool:
        raise NotImplementedError

    def run(self, url: str, journey: Journey) -> None:
        raise NotImplementedError


class GenericProfile(SiteProfile):
    """Fallback: profile the URL directly, no site-specific knowledge."""

    name = "generic"

    def matches(self, url: str) -> bool:
        return True

    def run(self, url: str, journey: Journey) -> None:
        r = journey.request("direct GET", url)
        if r.ok:
            html = r.text()
            m = re.search(r"<title>(.*?)</title>", html, re.IGNORECASE | re.DOTALL)
            title = m.group(1).strip() if m else "unknown"
            r.note = f'title="{title}" html_chars={len(html)}'


class NovelArchiveProfile(SiteProfile):
    name = "novelarchive.cc"

    def matches(self, url: str) -> bool:
        return "novelarchive.cc" in url.lower()

    @staticmethod
    def _extract_id(url: str) -> str:
        if "id=" in url:
            return url.split("id=")[1].split("&")[0]
        if "/novel/" in url:
            return url.split("/novel/")[1].split("/")[0]
        return ""

    def run(self, url: str, journey: Journey) -> None:
        base = "https://novelarchive.cc"
        novel_id = self._extract_id(url)

        meta = journey.request("fetch novel metadata", f"{base}/api/novels/{novel_id}")
        sources = []
        if meta.ok:
            try:
                data = json.loads(meta.text())
                novel = data.get("novel", {})
                total = int(novel.get("total_chapters") or 0)
                meta.note = f'title="{novel.get("title", "unknown")}" primary_chapters={total}'
                for s in novel.get("sources") or []:
                    if isinstance(s, dict) and s.get("id"):
                        sources.append((s["id"], s.get("label") or s["id"]))
            except Exception as exc:
                meta.note = f"json parse error: {exc}"

        if not sources:
            disc = journey.request("discover external sources", f"{base}/api/novels/{novel_id}/sources")
            if disc.ok:
                try:
                    data = json.loads(disc.text())
                    for s in data.get("sources", []):
                        if isinstance(s, dict) and s.get("id"):
                            sources.append((s["id"], s.get("label") or s["id"]))
                    disc.note = f"found {len(sources)} source(s): {', '.join(s[1] for s in sources)}"
                except Exception as exc:
                    disc.note = f"json parse error: {exc}"

        for src_id, src_label in sources:
            r = journey.request(
                f"fetch chapter list [{src_label}]",
                f"{base}/api/novels/{novel_id}/sources/{src_id}/chapters",
            )
            if r.ok:
                try:
                    chaps = json.loads(r.text()).get("chapters", [])
                    r.note = f"{len(chaps)} chapters from '{src_label}'"
                except Exception as exc:
                    r.note = f"json parse error: {exc}"

        content = journey.request("fetch sample chapter 1", f"{base}/api/novels/{novel_id}/chapters/1")
        if content.ok:
            try:
                chap = json.loads(content.text()).get("chapter", {})
                body_text = chap.get("content") or chap.get("body") or ""
                content.note = f'title="{chap.get("title", "Chapter 1")}" text_chars={len(body_text)}'
            except Exception as exc:
                content.note = f"json parse error: {exc}"


class NovelFireProfile(SiteProfile):
    name = "novelfire.net"

    def matches(self, url: str) -> bool:
        return "novelfire.net" in url.lower()

    def run(self, url: str, journey: Journey) -> None:
        clean = url.rstrip("/")
        base = "https://novelfire.net"

        page = journey.request("fetch novel page + post_id", clean)
        post_id = ""
        if page.ok:
            html = page.text()
            m = re.search(r'report-post_id=["\']?(\d+)["\']?', html)
            post_id = m.group(1) if m else ""
            page.note = f"post_id={post_id or 'NOT FOUND'}"

        if post_id:
            ajax_url = (
                f"{base}/ajax/listChapterDataAjax?draw=1&start=0&length=-1&post_id={post_id}"
                f"&order[0][column]=0&order[0][dir]=asc&order[0][name]=cmm_posts_detail.n_sort"
                f"&columns[0][data]=n_sort&columns[0][name]=cmm_posts_detail.n_sort&columns[0][searchable]=true&columns[0][orderable]=true"
                f"&columns[0][search][value]=&columns[0][search][regex]=false"
                f"&columns[1][data]=bookmark_created_at&columns[1][name]=bookmark_chapters.created_at&columns[1][searchable]=false&columns[1][orderable]=true"
                f"&columns[1][search][value]=&columns[1][search][regex]=false"
                f"&search[value]=&search[regex]=false&only_bookmark=false&_={int(time.time() * 1000)}"
            )
            r = journey.request(
                "fetch chapter list (ajax)", ajax_url,
                headers={
                    "Referer": clean,
                    "X-Requested-With": "XMLHttpRequest",
                    "Accept": "application/json, text/javascript, */*; q=0.01",
                    "Sec-Fetch-Dest": "empty",
                    "Sec-Fetch-Mode": "cors",
                    "Sec-Fetch-Site": "same-origin"
                },
            )
            if r.ok:
                try:
                    chapters = json.loads(r.text()).get("data", [])
                    r.note = f"{len(chapters)} chapters in single batch"
                except Exception as exc:
                    r.note = f"json parse error: {exc}"

        r = journey.request("fetch sample chapter 1", f"{clean}/chapter-1", headers={"Referer": clean})
        if r.ok:
            html = r.text()
            m = re.search(r'<div[^>]*id=["\']content["\'][^>]*>(.*?)</div>', html, re.DOTALL)
            r.note = f"content_chars={len(re.sub(r'<[^>]+>', ' ', m.group(1)))}" if m else "content selector not found"


class NovelFullProfile(SiteProfile):
    name = "novelfull.com"

    def matches(self, url: str) -> bool:
        return "novelfull.com" in url.lower()

    def run(self, url: str, journey: Journey) -> None:
        clean = url.rstrip("/")
        base = "https://novelfull.com"

        page = journey.request("fetch novel page + novel_id", clean)
        novel_id = ""
        if page.ok:
            html = page.text()
            m = re.search(r'data-novel-id=["\'](\d+)["\']', html)
            novel_id = m.group(1) if m else ""
            page.note = f"novel_id={novel_id or 'NOT FOUND'}"

        if novel_id:
            r = journey.request("fetch chapter options (ajax)", f"{base}/ajax-chapter-option?novelId={novel_id}")
            if r.ok:
                options = re.findall(r'<option[^>]*value=["\']([^"\']+)["\']', r.text())
                r.note = f"{len(options)} chapter options in dropdown payload"

        chapter_url = clean.replace(".html", "") + "/chapter-1-nightmare-begins.html"
        r = journey.request("fetch sample chapter 1", chapter_url)
        if r.ok:
            html = r.text()
            m = re.search(r'<div[^>]*id=["\']chapter-content["\'][^>]*>(.*?)</div>', html, re.DOTALL)
            r.note = f"content_chars={len(re.sub(r'<[^>]+>', ' ', m.group(1)))}" if m else f"html_bytes={len(html)}"


class RoyalRoadProfile(SiteProfile):
    name = "royalroad.com"

    def matches(self, url: str) -> bool:
        return "royalroad.com" in url.lower()

    def run(self, url: str, journey: Journey) -> None:
        clean = url.rstrip("/")
        page = journey.request("fetch novel page + embedded chapter json", clean)
        sample_url = ""
        if page.ok:
            html = page.text()
            m = re.search(r"window\.chapters\s*=\s*(\[.*?\]);", html, re.DOTALL)
            if m:
                try:
                    chaps = json.loads(m.group(1))
                    page.note = f"{len(chaps)} chapters parsed from window.chapters"
                    if chaps:
                        sample_url = "https://www.royalroad.com" + chaps[0].get("url", "")
                except Exception as exc:
                    page.note = f"json parse error: {exc}"
            else:
                page.note = "window.chapters not found, fallback table parse required"

        if sample_url:
            r = journey.request("fetch sample chapter 1", sample_url)
            if r.ok:
                html = r.text()
                m = re.search(r'<div[^>]*class=["\'][^"\']*chapter-content[^"\']*["\'][^>]*>(.*?)</div>', html, re.DOTALL)
                r.note = f"content_chars={len(re.sub(r'<[^>]+>', ' ', m.group(1)))}" if m else f"html_bytes={len(html)}"


PROFILES: List[SiteProfile] = [
    NovelArchiveProfile(),
    NovelFireProfile(),
    NovelFullProfile(),
    RoyalRoadProfile(),
    GenericProfile(),  # must stay last: always matches
]


def resolve_profile(url: str) -> SiteProfile:
    for profile in PROFILES:
        if profile.matches(url):
            return profile
    return PROFILES[-1]


def run_journey(url: str, timeout: float) -> List[HttpResult]:
    profile = resolve_profile(url)
    log.debug("using profile %r for %s", profile.name, url)
    journey = Journey(timeout)
    profile.run(url, journey)
    return journey.results


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

def summarize(results: List[HttpResult]) -> dict:
    total_ms = sum(r.timing.total_ms for r in results)
    dns = sum(r.timing.dns_ms for r in results)
    tcp = sum(r.timing.tcp_ms for r in results)
    tls = sum(r.timing.tls_ms for r in results)
    ttfb = sum(r.timing.ttfb_ms for r in results)
    transfer = sum(r.timing.transfer_ms for r in results)
    connect = dns + tcp + tls
    total_bytes = sum(r.size for r in results)
    return {
        "total_ms": total_ms,
        "total_bytes": total_bytes,
        "connect_ms": connect,
        "ttfb_ms": ttfb,
        "transfer_ms": transfer,
        "connect_share": (connect / total_ms * 100) if total_ms else 0.0,
        "ttfb_share": (ttfb / total_ms * 100) if total_ms else 0.0,
        "transfer_share": (transfer / total_ms * 100) if total_ms else 0.0,
    }


def print_report(url: str, results: List[HttpResult]) -> None:
    s = summarize(results)

    print(f"url:            {url}")
    print(f"steps:          {len(results)}")
    print(f"total time:     {fmt_ms(s['total_ms'])}")
    print(f"total transfer: {fmt_size(s['total_bytes'])}")
    print("-" * 78)

    for r in results:
        t = r.timing
        slow = "  [SLOW]" if t.total_ms > SLOW_STEP_THRESHOLD_MS else ""
        print(f"\nstep {r.step}: {r.label}{slow}")
        print(f"  {r.method} {r.url}")
        print(f"  status={r.status_line or '-'} size={fmt_size(r.size)}")
        print(
            f"  dns={fmt_ms(t.dns_ms)} tcp={fmt_ms(t.tcp_ms)} tls={fmt_ms(t.tls_ms)} "
            f"ttfb={fmt_ms(t.ttfb_ms)} transfer={fmt_ms(t.transfer_ms)} total={fmt_ms(t.total_ms)}"
        )
        if r.note:
            print(f"  note: {r.note}")
        if r.error:
            print(f"  error: {r.error}")

    print("\n" + "-" * 78)
    print("latency breakdown:")
    print(f"  connect (dns+tcp+tls): {fmt_ms(s['connect_ms']):>10}  ({s['connect_share']:5.1f}%)")
    print(f"  ttfb (server think):   {fmt_ms(s['ttfb_ms']):>10}  ({s['ttfb_share']:5.1f}%)")
    print(f"  transfer (download):   {fmt_ms(s['transfer_ms']):>10}  ({s['transfer_share']:5.1f}%)")
    print(f"  total:                 {fmt_ms(s['total_ms']):>10}")

    if results:
        slowest = max(results, key=lambda r: r.timing.total_ms)
        pct = (slowest.timing.total_ms / s["total_ms"] * 100) if s["total_ms"] else 0.0
        print("\nverdict:")
        print(f"  slowest step: #{slowest.step} '{slowest.label}' ({fmt_ms(slowest.timing.total_ms)}, {pct:.1f}% of total)")
        if s["ttfb_share"] >= 35:
            print("  dominant cost is server response time (ttfb) -- not client-side code")
        if s["connect_share"] >= 20:
            print("  connection setup is a significant share -- connection reuse/pooling would help")
        if s["transfer_share"] >= 15:
            print("  payload transfer is a significant share -- consider pagination or compression")
        if len(results) > 2:
            avg_connect = s["connect_ms"] / len(results)
            print(f"  {len(results)} sequential requests were made; each adds ~{fmt_ms(avg_connect)} connection overhead")
            print("  if these requests don't depend on each other's output, running them concurrently removes that overhead from wall time")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Profile the HTTP request sequence a crawler issues for a given URL, "
                    "with per-phase timing (dns/tcp/tls/ttfb/transfer) and a latency breakdown."
    )
    parser.add_argument("url", nargs="?", help="URL to profile")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="per-request socket timeout in seconds")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON instead of a text report")
    parser.add_argument("-v", "--verbose", action="store_true", help="enable debug logging to stderr")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.WARNING,
        format="%(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )

    url = args.url
    if not url:
        try:
            url = input("url: ").strip()
        except (KeyboardInterrupt, EOFError):
            print("aborted", file=sys.stderr)
            return 1
    if not url:
        print("error: no url provided", file=sys.stderr)
        return 1

    results = run_journey(url, args.timeout)

    if args.json:
        out = {
            "url": url,
            "profile": resolve_profile(url).name,
            "summary": summarize(results),
            "steps": [r.to_dict() for r in results],
        }
        print(json.dumps(out, indent=2))
    else:
        print_report(url, results)

    return 0


if __name__ == "__main__":
    sys.exit(main())