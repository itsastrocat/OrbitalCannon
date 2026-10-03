package com.orbitalcannon.passthrough.ipc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.LockSupport;

/** {@code MAP_SHARED} file mapping. Path resolution is identical to the C++ side's {@code resolveMappingName}. */
final class FileBackedMemory implements SharedMemory {
	private final Path path;
	private final FileChannel channel;
	private final Arena arena;
	private final MemorySegment segment;

	FileBackedMemory(String name) throws IOException {
		this.path = resolve(name);
		this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
		this.arena = Arena.ofShared();
		try {
			// Mapping READ_WRITE grows the file to the requested size if it is shorter, zero filled: the same
			// "grow only" behaviour as the C++ side, so either process may be first.
			this.segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, Protocol.TOTAL_SIZE, arena);
		} catch (IOException | RuntimeException e) {
			arena.close();
			channel.close();
			throw e;
		}
	}

	static Path resolve(String name) {
		if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return Path.of(name);
		if (Files.isDirectory(Path.of("/dev/shm"))) return Path.of("/dev/shm", name);
		String tmp = System.getenv("TMPDIR");
		return Path.of(tmp != null && !tmp.isEmpty() ? tmp : "/tmp", name);
	}

	@Override
	public MemorySegment segment() {
		return segment;
	}

	@Override
	public boolean hasSignal() {
		return false;
	}

	@Override
	public boolean awaitSignal(long timeoutMicros) {
		LockSupport.parkNanos(Math.max(1, timeoutMicros) * 1000L);
		return false;
	}

	@Override
	public String describe() {
		return "file mapping " + path;
	}

	@Override
	public void close() {
		try {
			arena.close();
		} catch (RuntimeException ignored) {
			// already closed
		}
		try {
			channel.close();
		} catch (IOException ignored) {
			// nothing useful to do
		}
	}
}
