// ScriptHookV script thread: owns the App, pumps it once per rendered frame and forwards key presses to it.
#include "script.h"

#include <windows.h>

#include <cstdio>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "natives.h"
#include "../core/app.h"
#include "../core/config.h"
#include "../core/log.h"

namespace shv {

namespace {
HMODULE g_module = nullptr;
FILE* g_log = nullptr;
std::mutex g_keyMutex;
std::vector<int> g_keys;

std::string moduleDirectory() {
	char path[MAX_PATH] = {};
	GetModuleFileNameA(g_module, path, MAX_PATH);
	std::string p(path);
	const size_t slash = p.find_last_of("\\/");
	return slash == std::string::npos ? std::string(".") : p.substr(0, slash);
}

void fileSink(const char* line) {
	if (!g_log) return;
	SYSTEMTIME t;
	GetLocalTime(&t);
	std::fprintf(g_log, "%02d:%02d:%02d.%03d %s\n", t.wHour, t.wMinute, t.wSecond, t.wMilliseconds, line);
	std::fflush(g_log);
}
}  // namespace

void SetModule(HMODULE module) { g_module = module; }

void OnKeyboard(DWORD key, WORD /*repeats*/, BYTE /*scanCode*/, BOOL /*isExtended*/, BOOL /*isWithAlt*/, BOOL wasDownBefore,
                BOOL isUpNow) {
	if (isUpNow || wasDownBefore) return;  // act on the initial key-down only, not auto-repeat or release
	std::lock_guard<std::mutex> lock(g_keyMutex);
	if (g_keys.size() < 32) g_keys.push_back(static_cast<int>(key));
}

void ScriptMain() {
	const std::string dir = moduleDirectory();
	g_log = std::fopen((dir + "\\OrbitalPassthrough.log").c_str(), "w");
	opt::setLogSink(fileSink);
	opt::logf("OrbitalPassthrough starting (protocol v%u)", ocp::kVersion);

	opt::Config cfg;
	std::string warnings;
	if (opt::loadConfigFile(dir + "\\OrbitalPassthrough.ini", cfg, &warnings)) {
		opt::logf("loaded OrbitalPassthrough.ini");
		if (!warnings.empty()) opt::logf("config warnings:\n%s", warnings.c_str());
	} else {
		opt::logf("no OrbitalPassthrough.ini next to the .asi: using defaults");
	}

	ShvGame game;
	opt::App app(game, cfg);
	int consecutiveFailures = 0;
	std::vector<int> keys;
	for (;;) {
		{
			std::lock_guard<std::mutex> lock(g_keyMutex);
			keys.swap(g_keys);
		}
		try {
			for (int k : keys) app.onKey(k);
			app.frame();
			consecutiveFailures = 0;
		} catch (const std::exception& e) {
			// An exception must never unwind into the game's fiber scheduler. Log it and keep the game alive; if the
			// failure is persistent, stop touching the game altogether.
			opt::logf("exception in frame: %s", e.what());
			if (++consecutiveFailures >= 30) {
				opt::logf("too many consecutive failures: shutting the passthrough down");
				app.shutdown();
				for (;;) scriptWait(1000);
			}
		}
		keys.clear();
		scriptWait(0);  // yield to the game; resumes next frame
	}
}

}  // namespace shv
