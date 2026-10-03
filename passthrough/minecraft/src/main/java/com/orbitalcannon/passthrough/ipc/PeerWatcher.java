package com.orbitalcannon.passthrough.ipc;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * A daemon thread whose only job is to notice that the GTA side published events and hand the work to the Minecraft
 * server thread immediately, instead of letting it sit until the next 50 ms tick.
 *
 * <p>It never touches game state and never writes shared memory; it only peeks at ring B's cursors and calls the
 * supplied wake action (in practice {@code server.execute(...)}, which the server thread runs between ticks within
 * about a hundred microseconds). On Windows it sleeps on the GTA-signalled kernel event, so an idle watcher costs no
 * CPU and wakes within tens of microseconds; elsewhere it spins briefly and then parks.
 */
public final class PeerWatcher implements AutoCloseable {
	private final Bridge bridge;
	private final Runnable wake;
	private final AtomicBoolean scheduled = new AtomicBoolean();
	private volatile boolean running = true;
	private final Thread thread;

	public PeerWatcher(Bridge bridge, Runnable wake) {
		this.bridge = bridge;
		this.wake = wake;
		this.thread = new Thread(this::run, "orbital-passthrough-rx");
		this.thread.setDaemon(true);
		this.thread.setPriority(Thread.NORM_PRIORITY + 1);
	}

	/** Starts watching. Separate from construction so the owner is fully built before the first wake-up can fire. */
	public void start() {
		thread.start();
	}

	/** Called by the server thread once it has drained, so the next batch can schedule another wake-up. */
	public void drained() {
		scheduled.set(false);
	}

	private void run() {
		int idleSpins = 0;
		while (running) {
			bridge.pollPresence();
			if (bridge.hasPendingEvents()) {
				if (scheduled.compareAndSet(false, true)) {
					try {
						wake.run();
					} catch (RuntimeException e) {
						scheduled.set(false); // e.g. the server is shutting down; try again next time
					}
				}
				idleSpins = 0;
				LockSupport.parkNanos(50_000); // already scheduled: give the server thread a moment to drain
				continue;
			}
			if (idleSpins < 200) {
				idleSpins++;
				Thread.onSpinWait();
			} else if (bridge.hasPeerSignal()) {
				bridge.awaitPeerSignal(2_000); // kernel event: wakes the moment GTA publishes, costs no CPU while idle
			} else {
				LockSupport.parkNanos(40_000); // no event available: poll at ~40 us granularity
			}
		}
	}

	@Override
	public void close() {
		running = false;
		if (!thread.isAlive()) return;
		LockSupport.unpark(thread);
		try {
			thread.join(500);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
