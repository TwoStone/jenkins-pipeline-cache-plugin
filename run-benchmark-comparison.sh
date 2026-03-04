#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Benchmark Comparison: AWS SDK v1 vs v2
# =============================================================================
# This script:
#   1. Copies VV1 benchmark to the v1 commit, runs it, captures results
#   2. Returns to the current branch, runs v2 benchmark, captures results
#   3. Prints a side-by-side comparison table
# =============================================================================

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

V1_COMMIT="08e18e4"
V2_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
V1_RESULTS="/tmp/benchmark-v1-results.txt"
V2_RESULTS="/tmp/benchmark-v2-results.txt"
V1_BENCH_SRC="src/test/java/io/jenkins/plugins/pipeline/cache/CacheBenchmarkV1.java"

echo "========================================="
echo "  SDK v1 vs v2 Benchmark Comparison"
echo "========================================="
echo ""
echo "Current branch: $V2_BRANCH"
echo "V1 commit:      $V1_COMMIT"
echo ""

# ---- Phase 1: Run v1 benchmarks ----
echo "[Phase 1] Preparing to run AWS SDK v1 benchmarks..."

# Save benchmark file to temp
cp "$V1_BENCH_SRC" /tmp/CacheBenchmarkV1.java

# Stash all current changes (including untracked files)
echo "  Stashing current changes..."
git stash push --include-untracked -m "benchmark-comparison-temp" >/dev/null 2>&1 || true

# Checkout v1 commit
echo "  Checking out v1 commit ($V1_COMMIT)..."
git checkout "$V1_COMMIT" --quiet 2>/dev/null

# Copy v1 benchmark into place
echo "  Copying v1 benchmark into source tree..."
cp /tmp/CacheBenchmarkV1.java "$V1_BENCH_SRC"

echo "  Compiling and running v1 benchmarks (this may take a few minutes)..."
mvn test -Dtest=CacheBenchmarkV1 -pl . 2>&1 | tee /tmp/benchmark-v1-full.log | grep -E "^\[BENCHMARK\]|Tests run:|BUILD" || true

# Extract benchmark lines
grep "^\[BENCHMARK\]" /tmp/benchmark-v1-full.log > "$V1_RESULTS" 2>/dev/null || true

# Clean up the copied file
rm -f "$V1_BENCH_SRC"

echo ""
echo "  V1 benchmarks complete. $(wc -l < "$V1_RESULTS" | tr -d ' ') results captured."
echo ""

# ---- Phase 2: Run v2 benchmarks ----
echo "[Phase 2] Preparing to run AWS SDK v2 benchmarks..."

# Return to original branch
echo "  Checking out $V2_BRANCH..."
git checkout "$V2_BRANCH" --quiet 2>/dev/null

# Restore stashed changes
echo "  Restoring stashed changes..."
git stash pop --quiet 2>/dev/null || true

echo "  Compiling and running v2 benchmarks (this may take a few minutes)..."
mvn surefire:test -Pbenchmarks 2>&1 | tee /tmp/benchmark-v2-full.log | grep -E "^\[BENCHMARK\]|Tests run:|BUILD" || true

# Extract benchmark lines
grep "^\[BENCHMARK\]" /tmp/benchmark-v2-full.log > "$V2_RESULTS" 2>/dev/null || true

echo ""
echo "  V2 benchmarks complete. $(wc -l < "$V2_RESULTS" | tr -d ' ') results captured."
echo ""

# ---- Phase 3: Comparison ----
echo "========================================="
echo "  COMPARISON: AWS SDK v1 vs v2"
echo "========================================="
echo ""

# Use Python for the comparison table (available on macOS)
python3 - "$V1_RESULTS" "$V2_RESULTS" << 'PYEOF'
import sys
import re

def parse_results(filepath):
    results = {}
    with open(filepath) as f:
        for line in f:
            line = line.strip()
            if not line.startswith("[BENCHMARK]"):
                continue
            rest = line[len("[BENCHMARK]"):].strip()

            # Throughput result: name  123.4 ms  456.7 MB/s
            m = re.match(r'^(.+?)\s{2,}([\d.]+)\s+ms\s+([\d.]+)\s+MB/s$', rest)
            if m:
                name = m.group(1).strip()
                ms = float(m.group(2))
                mbps = float(m.group(3))
                results[name] = {"ms": ms, "mbps": mbps, "type": "throughput"}
                continue

            # Latency with iterations: name  1.2 ms avg (50 iterations, 60.0 ms total)
            m = re.match(r'^(.+?)\s{2,}([\d.]+)\s+ms\s+avg\s+\((\d+)\s+iterations', rest)
            if m:
                name = m.group(1).strip()
                avg_ms = float(m.group(2))
                results[name] = {"ms": avg_ms, "type": "latency"}
                continue

            # Single latency: name  1.2 ms
            m = re.match(r'^(.+?)\s{2,}([\d.]+)\s+ms$', rest)
            if m:
                name = m.group(1).strip()
                ms = float(m.group(2))
                results[name] = {"ms": ms, "type": "latency"}
                continue

    return results

v1 = parse_results(sys.argv[1])
v2 = parse_results(sys.argv[2])

all_names = []
seen = set()
for name in list(v1.keys()) + list(v2.keys()):
    if name not in seen:
        all_names.append(name)
        seen.add(name)

# Print throughput benchmarks
print(f"{'Benchmark':<45} {'V1 (ms)':>10} {'V2 (ms)':>10} {'Delta':>10} {'V1 MB/s':>10} {'V2 MB/s':>10} {'Delta':>10}")
print("=" * 135)

throughput = [(n, v1.get(n), v2.get(n)) for n in all_names if (v1.get(n, {}).get("type") == "throughput" or v2.get(n, {}).get("type") == "throughput")]
latency = [(n, v1.get(n), v2.get(n)) for n in all_names if (v1.get(n, {}).get("type") == "latency" or v2.get(n, {}).get("type") == "latency")]

for name, r1, r2 in throughput:
    v1_ms = f"{r1['ms']:,.1f}" if r1 else "N/A"
    v2_ms = f"{r2['ms']:,.1f}" if r2 else "N/A"
    v1_mbps = f"{r1['mbps']:,.1f}" if r1 else "N/A"
    v2_mbps = f"{r2['mbps']:,.1f}" if r2 else "N/A"

    if r1 and r2:
        ms_delta = ((r2["ms"] - r1["ms"]) / r1["ms"]) * 100
        mbps_delta = ((r2["mbps"] - r1["mbps"]) / r1["mbps"]) * 100
        ms_delta_str = f"{ms_delta:+.1f}%"
        mbps_delta_str = f"{mbps_delta:+.1f}%"
        # Color: green if faster (negative ms delta), red if slower
        if ms_delta < -5:
            ms_delta_str = f"\033[32m{ms_delta_str}\033[0m"
            mbps_delta_str = f"\033[32m{mbps_delta_str}\033[0m"
        elif ms_delta > 5:
            ms_delta_str = f"\033[31m{ms_delta_str}\033[0m"
            mbps_delta_str = f"\033[31m{mbps_delta_str}\033[0m"
    else:
        ms_delta_str = "---"
        mbps_delta_str = "---"

    print(f"{name:<45} {v1_ms:>10} {v2_ms:>10} {ms_delta_str:>20} {v1_mbps:>10} {v2_mbps:>10} {mbps_delta_str:>20}")

print()
print(f"{'Latency Benchmark':<45} {'V1 (ms)':>10} {'V2 (ms)':>10} {'Delta':>10}")
print("=" * 85)

for name, r1, r2 in latency:
    v1_ms = f"{r1['ms']:,.1f}" if r1 else "N/A"
    v2_ms = f"{r2['ms']:,.1f}" if r2 else "N/A"

    if r1 and r2:
        ms_delta = ((r2["ms"] - r1["ms"]) / r1["ms"]) * 100
        ms_delta_str = f"{ms_delta:+.1f}%"
        if ms_delta < -5:
            ms_delta_str = f"\033[32m{ms_delta_str}\033[0m"
        elif ms_delta > 5:
            ms_delta_str = f"\033[31m{ms_delta_str}\033[0m"
    else:
        ms_delta_str = "---"

    print(f"{name:<45} {v1_ms:>10} {v2_ms:>10} {ms_delta_str:>20}")

print()
print("Legend: Green = v2 faster, Red = v2 slower, no color = within 5%")
print("Note: Single-run benchmarks have inherent variance. Focus on large-file patterns.")
PYEOF

echo ""
echo "Full logs:"
echo "  V1: /tmp/benchmark-v1-full.log"
echo "  V2: /tmp/benchmark-v2-full.log"
echo "  V1 results: $V1_RESULTS"
echo "  V2 results: $V2_RESULTS"
