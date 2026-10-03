#!/usr/bin/env bash
# End-to-end test: a real headless Minecraft 26.3 dedicated server running the Fabric mod, and the real GTA-side App
# (compiled from gta/src/core, driven by a mock GTA) exchanging voxels, interactions and collisions over shared memory.
#
# Requires: JAVA_HOME pointing at a JDK 25, g++ (C++20). Network access the first time (Gradle fetches Minecraft).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 25 (Minecraft 26.x requires Java 25)}"
export PATH="$JAVA_HOME/bin:$PATH"
OUT="${OCP_OUT:-$ROOT/build}"
mkdir -p "$OUT"
SHM="${OCP_SHM:-/dev/shm/ocp_e2e_$$}"
RUN="$ROOT/minecraft/run"

echo "== building the GTA-side end-to-end client and latency benchmark"
CORE=$(ls "$ROOT"/gta/src/core/*.cpp)
g++ -std=c++20 -O2 -pthread -Wall -Wextra "$ROOT/gta/tests/fake_gta.cpp" $CORE -o "$OUT/fake_gta"
g++ -std=c++20 -O2 -pthread -Wall -Wextra "$ROOT/gta/tests/latency_bench.cpp" $CORE -o "$OUT/latency_bench"

echo "== preparing a fresh server world"
mkdir -p "$RUN"
echo "eula=true" > "$RUN/eula.txt"
cat > "$RUN/server.properties" <<PROPS
online-mode=false
level-seed=7
view-distance=4
simulation-distance=4
spawn-protection=0
max-tick-time=-1
motd=ocp-e2e
spawn-monsters=false
spawn-animals=false
generate-structures=false
pause-when-empty-seconds=3
PROPS
rm -rf "$RUN/world" "$SHM"

FIFO="$OUT/mc_stdin.$$"
rm -f "$FIFO"; mkfifo "$FIFO"
exec 9<>"$FIFO"          # keeps the server console's stdin open; writing "stop" into it shuts the server down gracefully

echo "== starting the headless dedicated server (log: $OUT/server.log)"
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Docp.shm=$SHM -Docp.keepAwake=${OCP_KEEP_AWAKE:-true} --enable-native-access=ALL-UNNAMED" \
	"$ROOT/minecraft/gradlew" -p "$ROOT/minecraft" --no-daemon runServer <&9 >"$OUT/server.log" 2>&1 &
SERVER_PID=$!

cleanup() {
	echo "stop" >&9 2>/dev/null || true
	for _ in $(seq 1 40); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
	kill "$SERVER_PID" 2>/dev/null || true
	exec 9>&- || true
	rm -f "$FIFO" "$SHM"
}
trap cleanup EXIT

for i in $(seq 1 240); do
	if grep -q "Passthrough ready" "$OUT/server.log" 2>/dev/null; then break; fi
	if ! kill -0 "$SERVER_PID" 2>/dev/null; then echo "server exited early:"; tail -40 "$OUT/server.log"; exit 1; fi
	sleep 1
done
grep -q "Passthrough ready" "$OUT/server.log" || { echo "server never became ready"; tail -40 "$OUT/server.log"; exit 1; }
echo "== server ready after ~${i}s: $(grep -m1 'Passthrough ready' "$OUT/server.log" | sed 's/.*(orbitalpassthrough) //')"

echo "== IPC round-trip latency through the real Java side (idle server)"
set +e
"$OUT/latency_bench" "$SHM" 6000 1000
BENCH_RC=$?
echo "== running the GTA-side scenario against it"
"$OUT/fake_gta" "$SHM"
RC=$?
set -e
if [ "$RC" -eq 0 ] && [ "$BENCH_RC" -ne 0 ]; then RC=$BENCH_RC; fi

echo "== server's own view (/passthrough status)"
echo "passthrough status" >&9
sleep 2
grep -A12 "Orbital Passthrough$" "$OUT/server.log" | tail -13 | sed 's/^\[[^]]*\] \[[^]]*\] ([^)]*) //' || true
echo "== server log: errors/warnings from the mod"
grep -E "ERROR|WARN" "$OUT/server.log" | grep -iE "passthrough|Exception|Mixin" | head -10 || true
exit $RC
