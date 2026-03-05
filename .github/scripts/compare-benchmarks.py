#!/usr/bin/env python3
"""Compare two benchmark result files and produce a Markdown table for PR comments.

Usage:
    python3 compare-benchmarks.py <base-results.txt> <pr-results.txt>

Each input file contains lines of the form printed by CacheBenchmark:
    [BENCHMARK] name  123.4 ms  456.7 MB/s          (throughput)
    [BENCHMARK] name  1.2 ms avg (50 iterations, …)  (latency, multi)
    [BENCHMARK] name  1.2 ms                          (latency, single)
"""

import os
import re
import sys
from pathlib import Path


def parse_results(filepath: str) -> dict:
    results: dict = {}
    path = Path(filepath)
    if not path.exists() or path.stat().st_size == 0:
        return results

    with open(filepath) as f:
        for line in f:
            line = line.strip()
            if not line.startswith("[BENCHMARK]"):
                continue
            rest = line[len("[BENCHMARK]"):].strip()

            # Throughput: name  123.4 ms  456.7 MB/s
            m = re.match(r"^(.+?)\s{2,}([\d.]+)\s+ms\s+([\d.]+)\s+MB/s$", rest)
            if m:
                results[m.group(1).strip()] = {
                    "ms": float(m.group(2)),
                    "mbps": float(m.group(3)),
                    "type": "throughput",
                }
                continue

            # Latency with iterations: name  1.2 ms avg (50 iterations, 60.0 ms total)
            m = re.match(
                r"^(.+?)\s{2,}([\d.]+)\s+ms\s+avg\s+\((\d+)\s+iterations", rest
            )
            if m:
                results[m.group(1).strip()] = {
                    "ms": float(m.group(2)),
                    "type": "latency",
                }
                continue

            # Single latency: name  1.2 ms
            m = re.match(r"^(.+?)\s{2,}([\d.]+)\s+ms$", rest)
            if m:
                results[m.group(1).strip()] = {
                    "ms": float(m.group(2)),
                    "type": "latency",
                }
                continue

    return results


def delta_str(old_val: float, new_val: float) -> str:
    """Return a formatted delta string with emoji indicator."""
    if old_val == 0:
        return "N/A"
    pct = ((new_val - old_val) / old_val) * 100
    sign = "+" if pct >= 0 else ""
    text = f"{sign}{pct:.1f}%"
    return text


def delta_indicator(old_ms: float, new_ms: float, threshold: float = 5.0) -> str:
    """Return an emoji indicator: lower ms = faster = good."""
    if old_ms == 0:
        return ""
    pct = ((new_ms - old_ms) / old_ms) * 100
    if pct < -threshold:
        return " :rocket:"  # faster
    elif pct > threshold:
        return " :warning:"  # slower
    return ""


def mbps_indicator(old_mbps: float, new_mbps: float, threshold: float = 5.0) -> str:
    """Return an emoji indicator: higher MB/s = faster = good."""
    if old_mbps == 0:
        return ""
    pct = ((new_mbps - old_mbps) / old_mbps) * 100
    if pct > threshold:
        return " :rocket:"
    elif pct < -threshold:
        return " :warning:"
    return ""


def build_markdown(base: dict, pr: dict) -> str:
    lines: list[str] = []

    # Stable ordering: base keys first, then any new ones from PR
    all_names: list[str] = []
    seen: set[str] = set()
    for name in list(base.keys()) + list(pr.keys()):
        if name not in seen:
            all_names.append(name)
            seen.add(name)

    throughput = [
        (n, base.get(n), pr.get(n))
        for n in all_names
        if base.get(n, {}).get("type") == "throughput"
        or pr.get(n, {}).get("type") == "throughput"
    ]
    latency = [
        (n, base.get(n), pr.get(n))
        for n in all_names
        if base.get(n, {}).get("type") == "latency"
        or pr.get(n, {}).get("type") == "latency"
    ]

    base_sha = os.environ.get("BASE_SHA", "base")
    head_sha = os.environ.get("HEAD_SHA", "head")

    lines.append("## :bar_chart: Benchmark Comparison")
    lines.append("")
    lines.append(
        f"> Comparing **base** (`{base_sha[:10]}`) "
        f"vs **PR** (`{head_sha[:10]}`)"
    )
    lines.append(">")
    lines.append(
        "> :rocket: = faster than base (>5%) &nbsp; :warning: = slower than base (>5%)"
    )
    lines.append("")

    if not base and not pr:
        lines.append("_No benchmark results found in either base or PR run._")
        return "\n".join(lines)

    # ---- Throughput table ----
    if throughput:
        lines.append("### Throughput")
        lines.append("")
        lines.append(
            "| Benchmark | Base (ms) | PR (ms) | Delta (time) | Base (MB/s) | PR (MB/s) | Delta (throughput) |"
        )
        lines.append(
            "|:----------|----------:|--------:|:-------------|------------:|----------:|:-------------------|"
        )

        for name, r_base, r_pr in throughput:
            b_ms = f"{r_base['ms']:,.1f}" if r_base else "—"
            p_ms = f"{r_pr['ms']:,.1f}" if r_pr else "—"
            b_mbps = f"{r_base['mbps']:,.1f}" if r_base else "—"
            p_mbps = f"{r_pr['mbps']:,.1f}" if r_pr else "—"

            if r_base and r_pr:
                d_ms = delta_str(r_base["ms"], r_pr["ms"])
                d_ms += delta_indicator(r_base["ms"], r_pr["ms"])
                d_mbps = delta_str(r_base["mbps"], r_pr["mbps"])
                d_mbps += mbps_indicator(r_base["mbps"], r_pr["mbps"])
            else:
                d_ms = "—"
                d_mbps = "—"

            lines.append(
                f"| {name} | {b_ms} | {p_ms} | {d_ms} | {b_mbps} | {p_mbps} | {d_mbps} |"
            )

        lines.append("")

    # ---- Latency table ----
    if latency:
        lines.append("### Latency")
        lines.append("")
        lines.append("| Benchmark | Base (ms) | PR (ms) | Delta |")
        lines.append("|:----------|----------:|--------:|:------|")

        for name, r_base, r_pr in latency:
            b_ms = f"{r_base['ms']:,.1f}" if r_base else "—"
            p_ms = f"{r_pr['ms']:,.1f}" if r_pr else "—"

            if r_base and r_pr:
                d_ms = delta_str(r_base["ms"], r_pr["ms"])
                d_ms += delta_indicator(r_base["ms"], r_pr["ms"])
            else:
                d_ms = "—"

            lines.append(f"| {name} | {b_ms} | {p_ms} | {d_ms} |")

        lines.append("")

    lines.append(
        "<sub>Benchmarks run on GitHub Actions (`ubuntu-latest`). "
        "Single-run results have inherent variance — focus on large-file patterns "
        "and consistent directional changes.</sub>"
    )

    return "\n".join(lines)


def main() -> None:
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <base-results.txt> <pr-results.txt>", file=sys.stderr)
        sys.exit(1)

    base = parse_results(sys.argv[1])
    pr = parse_results(sys.argv[2])
    print(build_markdown(base, pr))


if __name__ == "__main__":
    main()
