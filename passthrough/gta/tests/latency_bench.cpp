// Measures the real round-trip latency of the IPC channel through the real Java side:
//   GTA writes PING into ring B  ->  Minecraft's watcher thread notices  ->  server.execute() runs the drain on the
//   server thread  ->  PONG written to ring A  ->  GTA reads it.
// The GTA side busy-polls (as a dedicated reader thread would); a real GTA frame loop adds up to one frame on top.
//
//   latency_bench <shm-name-or-path> [pings] [period-us]
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>

#include "../src/core/bridge.h"
#include "../src/core/log.h"

using Clock = std::chrono::steady_clock;

static uint64_t nowNs() {
	return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now().time_since_epoch()).count());
}

int main(int argc, char** argv) {
	if (argc < 2) {
		std::fprintf(stderr, "usage: latency_bench <shm> [pings] [period-us]\n");
		return 2;
	}
	const int pings = argc > 2 ? std::atoi(argv[2]) : 5000;
	const int periodUs = argc > 3 ? std::atoi(argv[3]) : 1000;
	opt::setLogSink([](const char*) {});
	opt::Bridge b;
	std::string err;
	if (!b.attach(argv[1], &err)) {
		std::fprintf(stderr, "attach failed: %s\n", err.c_str());
		return 1;
	}
	// Wait for Minecraft's heartbeat.
	const auto start = Clock::now();
	while (!b.mcAlive()) {
		b.beginFrame(nowNs() / 1000);
		if (Clock::now() - start > std::chrono::seconds(30)) {
			std::fprintf(stderr, "Minecraft did not appear\n");
			return 1;
		}
		std::this_thread::sleep_for(std::chrono::milliseconds(5));
	}

	std::vector<double> rtt;
	rtt.reserve(static_cast<size_t>(pings));
	uint64_t outstanding = 0, sentAt = 0;
	int lost = 0;
	bool got = false;
	for (int i = 0; i < pings; ++i) {
		b.beginFrame(nowNs() / 1000);
		b.drainWorld([](uint16_t, const uint8_t*, uint32_t) {}, 100000);  // discard session traffic from the handshake
		outstanding = sentAt = nowNs();
		b.sendPing(outstanding);
		b.flush();
		got = false;
		while (!got) {
			b.drainWorld(
				[&](uint16_t type, const uint8_t* p, uint32_t len) {
					if (type != ocp::kMsgPong || len < 8) return;
					uint64_t token;
					std::memcpy(&token, p, 8);
					if (token == outstanding) {
						rtt.push_back(static_cast<double>(nowNs() - sentAt) / 1000.0);
						got = true;
					}
				},
				1000);
			if (!got && nowNs() - sentAt > 50'000'000ull) {
				++lost;
				break;
			}
		}
		const auto until = Clock::now() + std::chrono::microseconds(periodUs);
		while (Clock::now() < until) std::this_thread::yield();
	}
	if (rtt.empty()) {
		std::fprintf(stderr, "no replies\n");
		return 1;
	}
	std::sort(rtt.begin(), rtt.end());
	auto pct = [&](double p) { return rtt[std::min(rtt.size() - 1, static_cast<size_t>(p * static_cast<double>(rtt.size())))]; };
	double mean = 0;
	for (double v : rtt) mean += v;
	mean /= static_cast<double>(rtt.size());
	size_t over16 = 0;
	for (double v : rtt) over16 += v > 16000.0;
	std::printf("LATENCY round trip GTA -> Minecraft(server thread) -> GTA, %zu samples, %d lost, %d us spacing\n", rtt.size(), lost, periodUs);
	std::printf("LATENCY   min %.0f us   p50 %.0f us   p90 %.0f us   p99 %.0f us   p99.9 %.0f us   max %.0f us   mean %.0f us\n", rtt.front(), pct(0.50),
	            pct(0.90), pct(0.99), pct(0.999), rtt.back(), mean);
	std::printf("LATENCY   %zu of %zu round trips exceeded 16 ms\n", over16, rtt.size());
	b.detach();
	// Gate on the 99th percentile, not on one outlier: Minecraft runs queued tasks only between ticks, so a request that
	// lands during a heavy tick (chunk generation, GC) waits for it. That tail is reported above, not hidden.
	return pct(0.99) < 5000.0 && lost == 0 ? 0 : 1;
}
