#!/usr/bin/env python3
"""
Bunori Extension Packager
Converts compiled extension sources into standalone .bext archives and manages repository index.json.

Release Rule:
- A crawler is packaged and released ONLY when its `version` (SemVer x.x.x) is increased compared to
  the index.json published on the LATEST GitHub Release (not a local file — CI runners are ephemeral
  and never carry state between runs).
- Unchanged crawlers are completely skipped (zero compilation / d8 overhead).
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path


def ensure_java_in_path():
    if shutil.which("java"):
        return
    java_home = os.environ.get("JAVA_HOME")
    if java_home and (Path(java_home) / "bin" / "java").exists():
        os.environ["PATH"] = f"{Path(java_home) / 'bin'}:{os.environ.get('PATH', '')}"
        return
    for candidate in [
        Path("/usr/bin/java"),
        Path("/usr/local/bin/java"),
    ] + list(Path("/usr/lib/jvm").glob("*/bin/java")):
        if candidate.exists():
            os.environ["PATH"] = f"{candidate.parent}:{os.environ.get('PATH', '')}"
            return


def find_android_sdk(project_root: Path) -> Path:
    for env_var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        val = os.environ.get(env_var)
        if val and Path(val).exists():
            return Path(val)

    local_props = project_root / "local.properties"
    if local_props.exists():
        with open(local_props, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("sdk.dir="):
                    sdk_path = Path(line.split("=", 1)[1].strip())
                    if sdk_path.exists():
                        return sdk_path

    home = Path.home()
    fallbacks = [
        home / "Android" / "Sdk",
        home / "Library" / "Android" / "sdk",
        Path("/usr/lib/android-sdk"),
        Path("/opt/android-sdk"),
    ]
    for fb in fallbacks:
        if fb.exists():
            return fb

    raise RuntimeError("Android SDK could not be located. Set ANDROID_HOME or sdk.dir in local.properties.")


def find_d8(sdk_path: Path) -> Path:
    build_tools_dir = sdk_path / "build-tools"
    if build_tools_dir.exists():
        versions = sorted(build_tools_dir.iterdir(), key=lambda p: p.name, reverse=True)
        for ver_dir in versions:
            d8_bin = ver_dir / "d8"
            if d8_bin.exists() and os.access(d8_bin, os.X_OK):
                return d8_bin
            d8_bat = ver_dir / "d8.bat"
            if d8_bat.exists():
                return d8_bat

    which_d8 = shutil.which("d8")
    if which_d8:
        return Path(which_d8)

    raise RuntimeError(f"Could not find 'd8' tool in {build_tools_dir} or system PATH.")


def find_android_jar(sdk_path: Path) -> Path:
    platforms_dir = sdk_path / "platforms"
    if platforms_dir.exists():
        platforms = sorted(platforms_dir.iterdir(), key=lambda p: p.name, reverse=True)
        for p in platforms:
            jar = p / "android.jar"
            if jar.exists():
                return jar

    raise RuntimeError(f"Could not find android.jar in {platforms_dir}.")


def parse_semver(v: str) -> tuple:
    """Parse a version string like '1.2.3' into a comparable tuple of integers (1, 2, 3)."""
    if not v:
        return (0,)
    parts = []
    for part in re.findall(r"\d+", str(v)):
        parts.append(int(part))
    return tuple(parts) if parts else (0,)


def fetch_remote_index(github_repo: str, timeout: int = 15):
    """
    Fetch the currently-published index.json from the *latest* GitHub Release.
    This is the real source of truth for "what versions are already released" —
    CI runners are ephemeral and a local repo/index.json never survives between runs,
    so it must never be used as the comparison baseline.

    Returns a dict {id: entry} on success, or None if there's no prior release yet
    (e.g. very first run) or the fetch fails for any reason.
    """
    if not github_repo:
        return None
    url = f"https://github.com/{github_repo}/releases/latest/download/index.json"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "bunori-packager"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            entries = data if isinstance(data, list) else data.get("extensions", [])
            index = {e["id"]: e for e in entries}
            print(f"Fetched published baseline index.json from latest release ({len(index)} extension(s)).")
            return index
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print("No prior release found (first run) — treating all extensions as new.")
        else:
            print(f"Warning: could not fetch published index.json (HTTP {e.code}). Treating baseline as empty.")
        return None
    except (urllib.error.URLError, json.JSONDecodeError, KeyError, TimeoutError) as e:
        print(f"Warning: could not fetch published index.json ({e}). Treating baseline as empty.")
        return None


def discover_crawlers(crawler_dir: Path):
    crawlers = []
    for kt_file in sorted(crawler_dir.glob("*.kt")):
        content = kt_file.read_text(encoding="utf-8")

        pkg_match = re.search(r"package\s+([\w\.]+)", content)
        cls_match = re.search(r"class\s+(\w+)", content)
        id_match = re.search(r"id\s*=\s*\"([^\"]+)\"", content)
        name_match = re.search(r"name\s*=\s*\"([^\"]+)\"", content)
        version_match = re.search(r"version\s*=\s*\"([^\"]+)\"", content)
        if not version_match:
            int_match = re.search(r"version\s*=\s*(\d+)", content)
            version_str = f"{int_match.group(1)}.0.0" if int_match else "1.0.0"
        else:
            version_str = version_match.group(1)

        api_version_match = re.search(r"apiVersion\s*=\s*(\d+)", content)
        lang_match = re.search(r"lang\s*=\s*\"([^\"]+)\"", content)
        url_match = re.search(r"baseUrl\s*=\s*\"([^\"]+)\"", content)

        webview_match = re.search(r"webviewNeeded\s*=\s*(true|false)", content, re.IGNORECASE)
        webview_needed = (webview_match.group(1).lower() == "true") if webview_match else False

        concurrency_match = re.search(r"runnerConcurrency\s*=\s*(\d+)", content)
        runner_concurrency = int(concurrency_match.group(1)) if concurrency_match else 3

        cooldown_match = re.search(r"runnerCooldown\s*=\s*(\d+)", content)
        runner_cooldown = int(cooldown_match.group(1)) if cooldown_match else 1000

        max_attempts_match = re.search(r"maxAttempts\s*=\s*(\d+)", content)
        max_attempts = int(max_attempts_match.group(1)) if max_attempts_match else 3

        if cls_match and id_match and name_match:
            pkg = pkg_match.group(1) if pkg_match else "com.halovoid.bunorisources.crawler"
            cls_name = cls_match.group(1)
            crawlers.append({
                "id": id_match.group(1),
                "name": name_match.group(1),
                "version": version_str,
                "apiVersion": int(api_version_match.group(1)) if api_version_match else 1,
                "lang": lang_match.group(1) if lang_match else "en",
                "baseUrl": url_match.group(1) if url_match else "",
                "entryClass": f"{pkg}.{cls_name}",
                "classPrefix": cls_name,
                "sourceFile": kt_file,
                "webviewNeeded": webview_needed,
                "runnerConcurrency": runner_concurrency,
                "runnerCooldown": runner_cooldown,
                "maxAttempts": max_attempts,
            })
    return crawlers


def build_bext(crawler, classes_dir: Path, output_dir: Path, d8_cmd: Path, android_jar: Path, icons_dir: Path, release_tag: str = None, github_repo: str = None):
    crawler_id = crawler["id"]
    class_prefix = crawler["classPrefix"]

    class_files = list(classes_dir.rglob(f"{class_prefix}.class")) + list(classes_dir.rglob(f"{class_prefix}$*.class"))
    if not class_files:
        raise RuntimeError(f"No compiled class files found for {class_prefix} in {classes_dir}. Run compilation first.")

    temp_dex_dir = output_dir / ".tmp" / crawler_id
    if temp_dex_dir.exists():
        shutil.rmtree(temp_dex_dir)
    temp_dex_dir.mkdir(parents=True, exist_ok=True)

    cmd = [
        str(d8_cmd),
        "--min-api", "24",
        "--lib", str(android_jar),
        "--output", str(temp_dex_dir),
    ] + [str(cf) for cf in class_files]

    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"d8 compilation failed for {crawler_id}:\n{result.stderr}")

    dex_file = temp_dex_dir / "classes.dex"
    if not dex_file.exists():
        raise RuntimeError(f"d8 did not produce classes.dex in {temp_dex_dir}")

    icon_path = None
    icon_file = None
    for ext in (".png", ".webp", ".jpg"):
        candidate = icons_dir / f"{crawler_id}{ext}"
        if candidate.exists():
            icon_file = candidate
            icon_path = f"assets/icon{ext}"
            break

    manifest = {
        "id": crawler_id,
        "name": crawler["name"],
        "version": crawler["version"],
        "apiVersion": crawler["apiVersion"],
        "lang": crawler["lang"],
        "baseUrl": crawler["baseUrl"],
        "entryClass": crawler["entryClass"],
        "iconPath": icon_path,
        "webviewNeeded": crawler.get("webviewNeeded", False),
        "runnerConcurrency": crawler.get("runnerConcurrency", 3),
        "runnerCooldown": crawler.get("runnerCooldown", 1000),
        "maxAttempts": crawler.get("maxAttempts", 3)
    }

    bext_filename = f"{crawler_id}.bext"
    bext_path = output_dir / bext_filename

    with zipfile.ZipFile(bext_path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("manifest.json", json.dumps(manifest, indent=2))
        zf.write(dex_file, "classes.dex")
        if icon_file and icon_path:
            zf.write(icon_file, icon_path)

    file_bytes = bext_path.read_bytes()
    file_size = len(file_bytes)
    sha256_hash = hashlib.sha256(file_bytes).hexdigest()

    shutil.rmtree(temp_dex_dir)

    bext_download_url = bext_filename
    if release_tag and github_repo:
        bext_download_url = f"https://github.com/{github_repo}/releases/download/{release_tag}/{bext_filename}"

    print(f"  ✓ Packaged {crawler['name']} (v{crawler['version']}) -> {bext_filename} ({file_size / 1024:.1f} KB)")

    return {
        "id": crawler_id,
        "name": crawler["name"],
        "version": crawler["version"],
        "apiVersion": crawler["apiVersion"],
        "lang": crawler["lang"],
        "baseUrl": crawler["baseUrl"],
        "entryClass": crawler["entryClass"],
        "iconPath": icon_path,
        "bextUrl": bext_download_url,
        "size": file_size,
        "sha256": sha256_hash,
        "webviewNeeded": crawler.get("webviewNeeded", False),
        "runnerConcurrency": crawler.get("runnerConcurrency", 3),
        "runnerCooldown": crawler.get("runnerCooldown", 1000),
        "maxAttempts": crawler.get("maxAttempts", 3),
    }


def main():
    ensure_java_in_path()
    parser = argparse.ArgumentParser(description="Package Bunori extensions into .bext archives and build repository index.")
    parser.add_argument("--out-dir", default="repo", help="Output directory for .bext packages and index.json (default: repo)")
    parser.add_argument("--single", help="ID of single extension to package")
    parser.add_argument("--compile", action="store_true", help="Run ./gradlew compileReleaseKotlin before packaging")
    default_repo = os.environ.get("GITHUB_REPOSITORY", "LNCrawler/LNCrawlerSources")
    default_tag = os.environ.get("RELEASE_TAG")
    parser.add_argument("--release-tag", default=default_tag, help="Release tag (e.g. v42) for permanent asset URLs")
    parser.add_argument("--github-repo", default=default_repo, help="GitHub repo in owner/name format")
    parser.add_argument("--no-remote-baseline", action="store_true",
                         help="Skip fetching the baseline from the latest GitHub Release "
                              "(use only a local repo/index.json if present). Mainly for offline/local dev runs.")
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parent.parent
    os.chdir(project_root)

    if args.compile:
        print("Compiling Kotlin sources...")
        cmd = ["./gradlew", "compileReleaseKotlin"]
        if sys.platform == "win32":
            cmd = ["gradlew.bat", "compileReleaseKotlin"]
        res = subprocess.run(cmd)
        if res.returncode != 0:
            print("Compilation failed.")
            sys.exit(1)

    sdk_path = find_android_sdk(project_root)
    d8_cmd = find_d8(sdk_path)
    android_jar = find_android_jar(sdk_path)

    crawler_src_dir = project_root / "app" / "src" / "main" / "java" / "com" / "halovoid" / "bunorisources" / "crawler"
    classes_dir = project_root / "app" / "build" / "intermediates" / "built_in_kotlinc" / "release" / "compileReleaseKotlin" / "classes"
    icons_dir = project_root / "icons"
    output_dir = project_root / args.out_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    crawlers = discover_crawlers(crawler_src_dir)
    print(f"Found {len(crawlers)} extension(s) in source tree.")

    if args.single:
        crawlers = [c for c in crawlers if c["id"] == args.single]
        if not crawlers:
            print(f"Error: No extension found with id '{args.single}'")
            sys.exit(1)

    # 1. Load the version-comparison baseline.
    #
    # IMPORTANT: this must come from the *published* index.json on the latest GitHub Release,
    # not from a local repo/index.json. CI runners start from a clean checkout every time, so a
    # local file is never a reliable "what's already been released" record — using it as the
    # baseline caused every extension to look "new" on every run, rebuilding and re-releasing
    # everything regardless of whether the version actually changed.
    existing_index = None
    if not args.no_remote_baseline:
        existing_index = fetch_remote_index(args.github_repo)

    if existing_index is None:
        # Fall back to a local file only if one happens to exist and NOT running in CI.
        # On CI, if fetch_remote_index returned 404 (no remote release exists yet),
        # existing_index must remain empty so that all extensions are packaged for the initial release!
        is_ci = bool(os.environ.get("CI") or os.environ.get("GITHUB_ACTIONS"))
        local_index_file = output_dir / "index.json"
        existing_index = {}
        if not is_ci and local_index_file.exists():
            try:
                with open(local_index_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    entries = data if isinstance(data, list) else data.get("extensions", [])
                    existing_index = {e["id"]: e for e in entries}
                print(f"Using local repo/index.json as baseline ({len(existing_index)} extension(s)).")
            except Exception:
                pass

    # 2. Check each crawler's version: only build if version bumped or new.
    #    (No local ".bext exists?" fallback here anymore — on an ephemeral CI runner a missing
    #    local .bext tells you nothing about whether it was already released; the version
    #    comparison against the published baseline is the only signal that matters.)
    final_entries = {}
    changed_or_new_entries = []

    print("\nChecking extension versions...")
    for crawler in crawlers:
        crawler_id = crawler["id"]
        declared_version = crawler["version"]
        old_entry = existing_index.get(crawler_id)

        should_package = False
        if old_entry is None:
            print(f"  + {crawler['name']} (v{declared_version}) - NEW extension")
            should_package = True
        elif parse_semver(declared_version) > parse_semver(old_entry.get("version", "0.0.0")):
            print(f"  ▲ {crawler['name']}: v{old_entry.get('version')} -> v{declared_version} (BUMPED)")
            should_package = True
        elif parse_semver(declared_version) < parse_semver(old_entry.get("version", "0.0.0")):
            print(f"  ⚠ {crawler['name']}: source declares v{declared_version} but published version is "
                  f"v{old_entry.get('version')} (lower than published — skipping; bump the version to re-release)")
            final_entries[crawler_id] = old_entry
        else:
            print(f"  • {crawler['name']} (v{declared_version}) - Up-to-date (skipped)")
            final_entries[crawler_id] = old_entry

        if should_package:
            entry = build_bext(crawler, classes_dir, output_dir, d8_cmd, android_jar, icons_dir, args.release_tag, args.github_repo)
            final_entries[crawler_id] = entry
            changed_or_new_entries.append(entry)

    all_entries = sorted(final_entries.values(), key=lambda x: x["name"])

    # 3. Write repo catalog index.json and index.min.json
    repo_catalog = {
        "repoName": "BunoriSources",
        "version": 1,
        "extensions": all_entries
    }

    with open(output_dir / "index.json", "w", encoding="utf-8") as f:
        json.dump(repo_catalog, f, indent=2)

    with open(output_dir / "index.min.json", "w", encoding="utf-8") as f:
        json.dump(all_entries, f, separators=(',', ':'))

    # Clean up temp files
    tmp_dir = output_dir / ".tmp"
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)

    # 4. Write CI control files
    has_release = len(changed_or_new_entries) > 0
    with open(output_dir / "has_release.txt", "w", encoding="utf-8") as f:
        f.write("true" if has_release else "false")

    with open(output_dir / "changed_files.txt", "w", encoding="utf-8") as f:
        if has_release:
            f.write(f"{args.out_dir}/index.json\n")
            f.write(f"{args.out_dir}/index.min.json\n")
            for e in all_entries:
                bext_file = output_dir / f"{e['id']}.bext"
                if bext_file.exists():
                    f.write(f"{args.out_dir}/{e['id']}.bext\n")

    print(f"\nPackaging summary:")
    print(f"  Total extensions: {len(all_entries)}")
    print(f"  Bumped or new:    {len(changed_or_new_entries)}")
    print(f"  Unchanged:        {len(all_entries) - len(changed_or_new_entries)}")
    print(f"  Release required: {has_release}")


if __name__ == "__main__":
    main()