package com.orbitalcannon.passthrough.ipc;

import java.nio.file.Path;

/** Test-only bridge to the package-private path resolution, so tests can delete their mapping files. */
public final class FileBackedMemoryAccess {
	private FileBackedMemoryAccess() {
	}

	public static Path resolve(String name) {
		return FileBackedMemory.resolve(name);
	}
}
