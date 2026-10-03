// Minimal dependency-free test harness.
#pragma once

#include <cstdio>
#include <cstdlib>
#include <functional>
#include <string>
#include <vector>

namespace tst {

struct Case {
	const char* name;
	std::function<void()> fn;
};
inline std::vector<Case>& registry() {
	static std::vector<Case> r;
	return r;
}
inline int& failures() {
	static int f = 0;
	return f;
}
struct Registrar {
	Registrar(const char* n, std::function<void()> f) { registry().push_back({n, std::move(f)}); }
};

inline int runAll(const char* filter = nullptr) {
	int ran = 0;
	for (const Case& c : registry()) {
		if (filter && std::string(c.name).find(filter) == std::string::npos) continue;
		const int before = failures();
		std::printf("[ RUN  ] %s\n", c.name);
		std::fflush(stdout);
		c.fn();
		std::printf("[ %s ] %s\n", failures() == before ? " OK " : "FAIL", c.name);
		++ran;
	}
	std::printf("\n%d test(s) run, %d failed check(s)\n", ran, failures());
	return failures() == 0 ? 0 : 1;
}

}  // namespace tst

#define TEST(name)                                           \
	static void name();                                      \
	static tst::Registrar reg_##name(#name, name);           \
	static void name()

#define CHECK(cond)                                                                           \
	do {                                                                                      \
		if (!(cond)) {                                                                        \
			std::printf("    CHECK failed: %s  (%s:%d)\n", #cond, __FILE__, __LINE__);         \
			++tst::failures();                                                                \
		}                                                                                     \
	} while (0)

#define CHECK_EQ(a, b)                                                                                   \
	do {                                                                                                 \
		const auto va_ = (a);                                                                            \
		const auto vb_ = (b);                                                                            \
		if (!(va_ == vb_)) {                                                                             \
			std::printf("    CHECK_EQ failed: %s == %s  (%lld vs %lld)  (%s:%d)\n", #a, #b,              \
			            static_cast<long long>(va_), static_cast<long long>(vb_), __FILE__, __LINE__);   \
			++tst::failures();                                                                           \
		}                                                                                                \
	} while (0)

#define CHECK_NEAR(a, b, eps)                                                                           \
	do {                                                                                                \
		const double va_ = (a);                                                                         \
		const double vb_ = (b);                                                                         \
		if (!(std::fabs(va_ - vb_) <= (eps))) {                                                         \
			std::printf("    CHECK_NEAR failed: %s ~ %s  (%g vs %g)  (%s:%d)\n", #a, #b, va_, vb_,      \
			            __FILE__, __LINE__);                                                            \
			++tst::failures();                                                                          \
		}                                                                                               \
	} while (0)
