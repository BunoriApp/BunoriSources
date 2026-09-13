#!/usr/bin/env python3
"""
benchmark.py - Runs the Kotlin crawler implementations against real novel URLs
via the BenchmarkRunner unit test, and streams the Gradle output.

Usage:
    python3 tools/benchmark.py <url> [url2] [url3] ...

At least one URL must be provided. There are no default URLs.
"""

import sys
import os
import subprocess
from pathlib import Path


def main() -> int:
    project_root = Path(__file__).resolve().parent.parent
    os.chdir(project_root)

    urls = sys.argv[1:]
    if not urls:
        urls = [
            "https://novelfire.net/book/shadow-slave",
            "https://novelfull.com/shadow-slave.html",
            "https://www.royalroad.com/fiction/21220/mother-of-learning",
        ]
        print(f"no urls specified, using {len(urls)} default test novel(s):")
    else:
        print(f"running benchmark on {len(urls)} url(s):")

    for u in urls:
        print(f"  {u}")
    print()

    urls_param = ",".join(urls)
    gradlew = project_root / "gradlew"

    cmd = [
        str(gradlew),
        "cleanTestDebugUnitTest",
        "testDebugUnitTest",
        "--tests",
        "*BenchmarkRunner*",
        f"-Durls={urls_param}",
    ]

    try:
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        for line in proc.stdout:
            print(line, end="")
        proc.wait()
        return proc.returncode
    except KeyboardInterrupt:
        print("\nbenchmark cancelled", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())