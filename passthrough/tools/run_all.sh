#!/usr/bin/env bash
# Runs every verification step that can run without GTA V:
#   1. GTA-side C++ unit tests (core, full-stack App, ScriptHookV native layer), plain and under ASan+UBSan
#   2. the CMake build (configure + build + ctest)
#   3. Java unit tests, including the layout-parity check against the C++ compiler's own offsetof output
#   4. a cross-compile of OrbitalPassthrough.asi to a real Windows PE DLL with MinGW-w64
#   5. the end-to-end test: real headless Minecraft 26.3 server + real GTA-side App over shared memory
#
# Needs: g++ (C++20), cmake, JAVA_HOME -> JDK 25. Optional: x86_64-w64-mingw32-g++ (step 4), network on first run.
# Skip the slow end-to-end test with OCP_SKIP_E2E=1.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OCP_OUT:-$ROOT/build}"
mkdir -p "$OUT"
GTA="$ROOT/gta"
CORE=$(ls "$GTA"/src/core/*.cpp)
step() { printf '\n\033[1m=== %s\033[0m\n' "$*"; }

step "1/5 GTA-side C++ tests (plain)"
g++ -std=c++20 -O2 -pthread -Wall -Wextra "$GTA/tests/test_core.cpp" $CORE -o "$OUT/test_core"
g++ -std=c++20 -O2 -pthread -Wall -Wextra "$GTA/tests/test_app.cpp" $CORE -o "$OUT/test_app"
g++ -std=c++20 -O2 -Wall -Wextra -I"$GTA/tests/shv_stub" "$GTA/tests/test_natives.cpp" "$GTA/src/shv/natives.cpp" -o "$OUT/test_natives"
"$OUT/test_core" | tail -2
"$OUT/test_app" 2>/dev/null | tail -2
"$OUT/test_natives" | tail -2

step "1b/5 GTA-side C++ tests under AddressSanitizer + UBSan"
SAN="-fsanitize=address,undefined -fno-omit-frame-pointer -O1 -g"
g++ -std=c++20 $SAN -pthread "$GTA/tests/test_core.cpp" $CORE -o "$OUT/test_core_asan"
g++ -std=c++20 $SAN -pthread "$GTA/tests/test_app.cpp" $CORE -o "$OUT/test_app_asan"
"$OUT/test_core_asan" | tail -1
"$OUT/test_app_asan" 2>/dev/null | tail -1

step "2/5 CMake configure + build + ctest"
if command -v cmake >/dev/null; then
	cmake -S "$GTA" -B "$OUT/cmake" -DCMAKE_BUILD_TYPE=Release >/dev/null
	cmake --build "$OUT/cmake" -j"$(nproc)" >/dev/null
	(cd "$OUT/cmake" && ctest --output-on-failure | tail -6)
else
	echo "cmake not found: skipped"
fi

step "3/5 Java unit tests + layout parity against the C++ compiler"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 25}"
export PATH="$JAVA_HOME/bin:$PATH"
g++ -std=c++20 -I"$ROOT/protocol" "$GTA/tests/layout_dump.cpp" -o "$OUT/layout_dump"
"$OUT/layout_dump" > "$OUT/layout.txt"
(cd "$ROOT/minecraft" && OCP_LAYOUT_DUMP="$OUT/layout.txt" ./gradlew --no-daemon test 2>&1 | grep -E "tests completed|BUILD|FAILED" || true)
python3 - "$ROOT/minecraft/build/test-results/test" <<'PY'
import glob, re, sys
tot = fail = skip = 0
for f in glob.glob(sys.argv[1] + "/*.xml"):
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', open(f).read())
    t, s, fl, e = map(int, m.groups()); tot += t; skip += s; fail += fl + e
print(f"Java: {tot} tests, {fail} failed, {skip} skipped")
sys.exit(1 if fail or skip else 0)
PY

step "4/5 Cross-compile OrbitalPassthrough.asi to a Windows PE DLL (MinGW-w64, against the SDK stub)"
if command -v x86_64-w64-mingw32-g++ >/dev/null; then
	mkdir -p "$OUT/win"
	x86_64-w64-mingw32-g++ -std=c++20 -O2 -Wall -Wextra -Wpedantic -Wshadow -DNOMINMAX -DWIN32_LEAN_AND_MEAN -shared \
		-static-libgcc -static-libstdc++ -Wl,-Bstatic,--whole-archive -lwinpthread -Wl,--no-whole-archive \
		-I"$GTA/tests/shv_stub" -I"$GTA/src" $CORE "$GTA/src/shv/natives.cpp" "$GTA/src/shv/script.cpp" "$GTA/src/shv/dllmain.cpp" \
		"$GTA/tests/shv_stub/shv_stub_impl.cpp" -o "$OUT/win/OrbitalPassthrough.asi"
	file "$OUT/win/OrbitalPassthrough.asi"
else
	echo "x86_64-w64-mingw32-g++ not found: skipped"
fi

if [ "${OCP_SKIP_E2E:-0}" = "1" ]; then
	step "5/5 end-to-end: skipped (OCP_SKIP_E2E=1)"
else
	step "5/5 End-to-end: headless Minecraft 26.3 server + GTA-side App over shared memory"
	set +e
	"$ROOT/tools/e2e.sh" > "$OUT/e2e.out" 2>&1
	E2E_RC=$?
	set -e
	grep -E "^E2E|^LATENCY|^==" "$OUT/e2e.out" || true
	if [ "$E2E_RC" -ne 0 ]; then echo "end-to-end: FAILED (exit $E2E_RC; see $OUT/e2e.out and $OUT/server.log)"; exit 1; fi
	echo "end-to-end: PASSED"
fi
echo; echo "All verification steps passed."
