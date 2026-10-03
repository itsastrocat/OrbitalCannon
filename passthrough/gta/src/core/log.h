#pragma once

namespace opt {

using LogSink = void (*)(const char* line);

// The ScriptHookV build points this at a log file next to the .asi; tests leave the stderr default.
void setLogSink(LogSink sink);

#if defined(__GNUC__)
void logf(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
#else
void logf(const char* fmt, ...);
#endif

}  // namespace opt
