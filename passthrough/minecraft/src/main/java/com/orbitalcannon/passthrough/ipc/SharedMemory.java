package com.orbitalcannon.passthrough.ipc;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Locale;

/**
 * The mapped shared-memory region, however the platform provides it.
 *
 * <ul>
 *   <li>Windows: a named, pagefile-backed section {@code Local\<name>} created through kernel32 with the Foreign
 *       Function &amp; Memory API (no JNI, no native library to ship), plus a named event the GTA side signals after
 *       it publishes events so the reader thread wakes in microseconds instead of polling.
 *   <li>Everywhere else (Linux CI, development): a {@code MAP_SHARED} file mapped with {@code FileChannel.map}. The
 *       GTA-side C++ uses the identical path, so the complete system can be tested end to end without GTA V.
 * </ul>
 */
public interface SharedMemory extends AutoCloseable {
	enum Backend {
		AUTO, WINDOWS, FILE;

		public static Backend parse(String s) {
			if (s == null || s.isBlank()) return AUTO;
			return switch (s.trim().toLowerCase(Locale.ROOT)) {
				case "windows", "win", "named" -> WINDOWS;
				case "file" -> FILE;
				default -> AUTO;
			};
		}
	}

	/** The whole mapped region, {@link Protocol#TOTAL_SIZE} bytes, page aligned. */
	MemorySegment segment();

	/** True if {@link #awaitSignal} blocks on a real kernel event the peer sets (Windows), false if it can only poll. */
	boolean hasSignal();

	/**
	 * Blocks up to {@code timeoutMicros} waiting for the peer to signal that it published something. Returns true if
	 * it was signalled, false on timeout. Backends without a kernel event simply pause briefly and return false.
	 */
	boolean awaitSignal(long timeoutMicros);

	String describe();

	@Override
	void close();

	static SharedMemory open(String name, Backend backend) throws IOException {
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
		Backend b = backend == Backend.AUTO ? (windows ? Backend.WINDOWS : Backend.FILE) : backend;
		if (b == Backend.WINDOWS) {
			if (!windows) throw new IOException("the Windows shared-memory backend was requested on a non-Windows OS");
			return new WindowsMemory(name);
		}
		return new FileBackedMemory(name);
	}
}
