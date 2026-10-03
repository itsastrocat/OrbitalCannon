#include "log.h"

#include <cstdarg>
#include <cstdio>

namespace opt {

namespace {
void stderrSink(const char* line) { std::fprintf(stderr, "%s\n", line); }
LogSink g_sink = stderrSink;
}  // namespace

void setLogSink(LogSink sink) { g_sink = sink ? sink : stderrSink; }

void logf(const char* fmt, ...) {
	char buf[1024];
	va_list ap;
	va_start(ap, fmt);
	std::vsnprintf(buf, sizeof buf, fmt, ap);
	va_end(ap);
	g_sink(buf);
}

}  // namespace opt
