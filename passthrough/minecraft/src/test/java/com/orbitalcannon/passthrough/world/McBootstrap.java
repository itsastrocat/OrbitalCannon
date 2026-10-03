package com.orbitalcannon.passthrough.world;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** One-time registry bootstrap so real {@code Blocks.*} states can be used in plain unit tests (no server needed). */
final class McBootstrap {
	private static boolean done;

	private McBootstrap() {
	}

	static synchronized void init() {
		if (done) return;
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		done = true;
	}
}
