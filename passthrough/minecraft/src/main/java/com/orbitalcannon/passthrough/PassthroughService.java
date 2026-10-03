package com.orbitalcannon.passthrough;

import static com.orbitalcannon.passthrough.ipc.Protocol.*;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;

import com.orbitalcannon.passthrough.interact.InteractionExecutor;
import com.orbitalcannon.passthrough.ipc.Bridge;
import com.orbitalcannon.passthrough.ipc.PeerWatcher;
import com.orbitalcannon.passthrough.ipc.PlayerState;
import com.orbitalcannon.passthrough.ipc.RingReader;
import com.orbitalcannon.passthrough.world.BlockChangeTracker;
import com.orbitalcannon.passthrough.world.ChunkLoader;
import com.orbitalcannon.passthrough.world.LevelVoxelSource;
import com.orbitalcannon.passthrough.world.PaletteRegistry;
import com.orbitalcannon.passthrough.world.SectionStreamer;
import com.orbitalcannon.passthrough.world.WorldTransform;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Per-server orchestration. Everything here runs on the server thread, in two places:
 *
 * <ul>
 *   <li>{@link #tickStart()}: heartbeat, handshake, and applying every event GTA sent since the last tick;
 *   <li>{@link #tickEnd()}: streaming and incremental updates, the McState snapshot, and one ring-A commit.
 * </ul>
 *
 * Between ticks, {@link #wakeDrain()} (scheduled by the {@link PeerWatcher} within microseconds of GTA publishing)
 * applies requests immediately and flushes their results, so an interaction does not wait for the next 50 ms tick.
 */
public final class PassthroughService implements AutoCloseable {
	private static volatile PassthroughService instance;
	public static final java.util.concurrent.atomic.AtomicLong mixinCalls = new java.util.concurrent.atomic.AtomicLong();
	public static final java.util.concurrent.atomic.AtomicLong mixinOverrides = new java.util.concurrent.atomic.AtomicLong();

	/**
	 * Read by {@code DedicatedServerMixin} on every server tick, including while the server is paused for lack of
	 * players. It therefore must not depend on tick hooks: it asks the watcher-thread-maintained presence flag.
	 */
	public static boolean keepAwake() {
		PassthroughService s = instance;
		return s != null && s.cfg.keepServerAwake && s.bridge.gtaPresent();
	}

	private final MinecraftServer server;
	private final PassthroughConfig cfg;
	private final ServerLevel level;
	private final Bridge bridge;
	private final PeerWatcher watcher;
	private final WorldTransform tf = new WorldTransform();
	private final PaletteRegistry palette = new PaletteRegistry();
	private final LevelVoxelSource source;
	private final SectionStreamer streamer;
	private final BlockChangeTracker tracker;
	private final InteractionExecutor executor;
	private final ChunkLoader chunks = new ChunkLoader();
	private final PlayerState player = new PlayerState();
	private final RingReader.Handler handler = this::onEvent;
	private final LongOpenHashSet desiredColumns = new LongOpenHashSet();

	private boolean everReadPlayer;
	private long lastPlayerFrame = -1;
	private long lastPlayerFrameNanos;
	private boolean playerFresh;

	private boolean wasAlive;
	private boolean needSession;
	private boolean sessionActive;
	private int sessionSerial;
	private float viewH = 28f, viewV = 24f;
	private double rttMs;
	private float msptEma;
	private long lastTickStartNanos;
	private long hellos, anchors, sessions;

	private PassthroughService(MinecraftServer server, PassthroughConfig cfg, ServerLevel level, Bridge bridge) {
		this.server = server;
		this.cfg = cfg;
		this.level = level;
		this.bridge = bridge;
		this.source = new LevelVoxelSource(level);
		this.streamer = new SectionStreamer(bridge, palette, source);
		this.tracker = new BlockChangeTracker(level, Thread.currentThread());
		this.executor = new InteractionExecutor(level, bridge, tf, palette, cfg, tracker);
		this.watcher = new PeerWatcher(bridge, () -> server.execute(this::wakeDrain));
	}

	public static PassthroughService start(MinecraftServer server, PassthroughConfig cfg) throws IOException {
		ServerLevel level = null;
		for (ServerLevel l : server.getAllLevels()) {
			if (l.dimension().identifier().toString().equals(cfg.dimension)) {
				level = l;
				break;
			}
		}
		if (level == null) throw new IOException("configured dimension " + cfg.dimension + " does not exist");
		Bridge bridge = Bridge.open(cfg.ipcName, cfg.ipcBackend);
		PassthroughService s;
		try {
			s = new PassthroughService(server, cfg, level, bridge);
		} catch (RuntimeException e) {
			bridge.close();
			throw e;
		}
		BlockChangeTracker.install(s.tracker);
		instance = s;
		s.watcher.start(); // last: everything it can call into is fully constructed
		PassthroughMod.LOGGER.info("Passthrough ready: {} (epoch {}), mirroring {}", bridge.describe(), Long.toHexString(bridge.mcEpoch()), cfg.dimension);
		return s;
	}

	@Override
	public void close() {
		BlockChangeTracker.uninstall(tracker);
		if (instance == this) instance = null;
		watcher.close();
		try {
			chunks.releaseAll();
		} catch (RuntimeException e) {
			PassthroughMod.LOGGER.warn("Could not release chunk tickets: {}", e.toString());
		}
		bridge.close();
	}

	public void requestResync() {
		needSession = true;
	}

	// ---- tick hooks ------------------------------------------------------------------------------------------------

	public void tickStart() {
		long now = System.nanoTime();
		lastTickStartNanos = now;
		bridge.beginTick();
		if (bridge.consumeGtaRestarted()) {
			PassthroughMod.LOGGER.info("GTA V client attached: starting a fresh session");
			needSession = true;
		}
		boolean alive = bridge.gtaAlive();
		if (!alive) {
			if (wasAlive) onGtaLost();
			wasAlive = false;
			tracker.setEnabled(false);
			return;
		}
		wasAlive = true;
		tracker.setEnabled(sessionActive);
		int tick = server.getTickCount();
		source.invalidate();
		refreshPlayer();
		executor.beginTick(tick);
		bridge.drainEvents(handler, 512);
		if (needSession) tryStartSession();
		if (tick % 20 == 0) bridge.sendPing(System.nanoTime());
	}

	public void tickEnd() {
		if (!wasAlive) return;
		int tick = server.getTickCount();
		source.invalidate();
		long deadline = System.nanoTime() + cfg.streamBudgetMicros * 1000L;
		applyTrackedChanges();
		if (sessionActive && tf.valid && playerKnown()) {
			double mx = tf.mcX(player.px), my = tf.mcY(player.pz), mz = tf.mcZ(player.py);
			desiredColumns.clear();
			SectionStreamer.desiredColumns(mx, mz, viewH, desiredColumns);
			chunks.update(level, desiredColumns, (int) Math.floor(mx) >> 4, (int) Math.floor(mz) >> 4);
			streamer.update(mx, my, mz, viewH, viewV, deadline, cfg.maxSectionsPerTick);
		}
		streamer.flushUpdates(tick);

		long nowNs = System.nanoTime();
		float thisMs = (nowNs - lastTickStartNanos) / 1_000_000f;
		msptEma = msptEma == 0f ? thisMs : msptEma * 0.95f + thisMs * 0.05f;
		int flags = (tf.valid ? 1 : 0) | (sessionActive ? 2 : 0);
		bridge.publishMcState(tick, level.getDefaultClockTime(), msptEma, flags, streamer.streamedCount(), streamer.pendingLoads());
		bridge.commit();
	}

	/** Runs on the server thread between ticks, moments after GTA published something. */
	public void wakeDrain() {
		try {
			source.invalidate();
			bridge.refreshLiveness();
			refreshPlayer();
			bridge.drainEvents(handler, 512);
			if (needSession && bridge.gtaAlive()) tryStartSession();
			if (sessionActive) {
				applyTrackedChanges();
				streamer.flushUpdates(server.getTickCount());
			}
			bridge.commit();
		} catch (RuntimeException e) {
			PassthroughMod.LOGGER.error("Passthrough wake-up failed", e);
		} finally {
			watcher.drained();
		}
	}

	// ---- session handshake -----------------------------------------------------------------------------------------

	private void onGtaLost() {
		PassthroughMod.LOGGER.info("GTA V client went away: releasing streamed state");
		streamer.reset();
		tf.valid = false;
		sessionActive = false;
		needSession = false;
		chunks.releaseAll();
		tracker.normal().clear();
		tracker.fromGta().clear();
	}

	private void tryStartSession() {
		double[] a = computeAnchor();
		if (a == null) return; // anchor chunk still loading
		palette.resetAnnouncements();
		streamer.reset();
		tf.valid = false;
		tf.setMcAnchor(a[0], a[1], a[2]);
		if (!bridge.sendSessionStart(++sessionSerial, a[0], a[1], a[2], source.minY(), source.maxYExclusive() - 1)) return; // ring full: retry
		bridge.commit();
		sessionActive = true;
		tracker.setEnabled(true); // from this instant block changes matter; do not wait for the next tick hook to notice
		needSession = false;
		sessions++;
		PassthroughMod.LOGGER.info("Session {} started, anchor ({}, {}, {})", sessionSerial, a[0], a[1], a[2]);
	}

	/**
	 * The Minecraft point GTA's anchor is pinned to. It must be stable across sessions: if it followed the terrain (e.g.
	 * the heightmap), a crater at spawn would shift the whole mirrored world vertically the next time GTA reconnects. The
	 * world spawn position never changes by itself, so that is the default; {@code world.fixedAnchor} overrides it.
	 */
	private double[] computeAnchor() {
		if (cfg.fixedAnchor) return new double[] {cfg.anchorX, cfg.anchorY, cfg.anchorZ};
		BlockPos spawn = level.getRespawnData().pos();
		chunks.hold(level, spawn.getX() >> 4, spawn.getZ() >> 4); // keep the anchor area available while GTA anchors
		return new double[] {spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5};
	}

	// ---- events from GTA -------------------------------------------------------------------------------------------

	private void onEvent(int type, MemorySegment seg, long p, int len) {
		try {
			switch (type) {
				case MSG_HELLO -> {
					if (len < HELLO_SIZE) return;
					viewH = clampRadius(seg.get(JAVA_FLOAT_UNALIGNED, p + HELLO_VIEW_H), cfg.maxViewRadiusH, 28f);
					viewV = clampRadius(seg.get(JAVA_FLOAT_UNALIGNED, p + HELLO_VIEW_V), cfg.maxViewRadiusV, 24f);
					hellos++;
					sessionActive = false; // requests are refused until the new session is anchored
					tf.valid = false;
					needSession = true;
					PassthroughMod.LOGGER.info("GTA HELLO: view radius {}x{} blocks", viewH, viewV);
				}
				case MSG_ANCHOR -> {
					if (len < ANCHOR_SIZE || !sessionActive) return;
					double gx = seg.get(JAVA_DOUBLE_UNALIGNED, p + ANCHOR_GX);
					double gy = seg.get(JAVA_DOUBLE_UNALIGNED, p + ANCHOR_GY);
					double gz = seg.get(JAVA_DOUBLE_UNALIGNED, p + ANCHOR_GZ);
					if (!Double.isFinite(gx) || !Double.isFinite(gy) || !Double.isFinite(gz)) return;
					tf.setGtaAnchor(gx, gy, gz);
					tf.valid = true;
					anchors++;
					PassthroughMod.LOGGER.info("GTA anchor ({}, {}, {}) pinned to the Minecraft anchor", gx, gy, gz);
				}
				case MSG_BREAK_REQUEST -> {
					if (len >= BR_SIZE) executor.onBreak(seg, p, player, playerKnown());
				}
				case MSG_PLACE_REQUEST -> {
					if (len >= PR_SIZE) executor.onPlace(seg, p, player, playerKnown());
				}
				case MSG_COLLISION -> {
					if (len >= CE_SIZE) executor.onCollision(seg, p, player, playerKnown());
				}
				case MSG_PING -> {
					if (len >= PING_SIZE) bridge.sendPong(seg.get(JAVA_LONG_UNALIGNED, p));
				}
				case MSG_PONG -> {
					if (len >= PING_SIZE) {
						double ms = (System.nanoTime() - seg.get(JAVA_LONG_UNALIGNED, p)) / 1_000_000.0;
						if (ms >= 0) rttMs = rttMs == 0 ? ms : rttMs * 0.8 + ms * 0.2;
					}
				}
				default -> {
					// Unknown record from a newer GTA build: ignore for forward compatibility.
				}
			}
		} catch (RuntimeException e) {
			PassthroughMod.LOGGER.error("Failed to handle GTA event type {}", type, e);
		}
	}

	private static float clampRadius(float requested, float max, float fallback) {
		if (!(requested > 0f) || Float.isInfinite(requested)) return Math.min(fallback, max);
		return Math.min(requested, max);
	}

	private void applyTrackedChanges() {
		if (!sessionActive) {
			tracker.normal().clear();
			tracker.fromGta().clear();
			return;
		}
		if (!tracker.fromGta().isEmpty()) {
			streamer.applyChanges(tracker.fromGta(), true);
			tracker.fromGta().clear();
		}
		if (!tracker.normal().isEmpty()) {
			streamer.applyChanges(tracker.normal(), false);
			tracker.normal().clear();
		}
	}

	// ---- player state ----------------------------------------------------------------------------------------------

	private void refreshPlayer() {
		if (bridge.readPlayer(player)) {
			everReadPlayer = true;
			long now = System.nanoTime();
			if (player.frame != lastPlayerFrame) {
				lastPlayerFrame = player.frame;
				lastPlayerFrameNanos = now;
			}
			playerFresh = now - lastPlayerFrameNanos < 2_000_000_000L;
		}
	}

	private boolean playerKnown() {
		return everReadPlayer && playerFresh;
	}

	// ---- diagnostics -----------------------------------------------------------------------------------------------

	public String statusText() {
		var st = streamer.stats();
		return String.format(
			"Orbital Passthrough%n  channel: %s%n  GTA: %s, anchored=%s, session=%d (%d sessions, %d HELLO, %d anchors)%n"
				+ "  view: %.0fx%.0f blocks, player frame %d%n"
				+ "  streamed: %d sections (%d pending), %d voxels sent, %d updates, %d unloads, %d palette types%n"
				+ "  scans: %d, avg %.0f us; tickets held: %d columns%n"
				+ "  interaction: %d breaks, %d places, %d collision breaks, %d denied%n"
				+ "  ipc: %d records out, %d in, %d dropped, %d corrupt; rtt %.2f ms; mspt %.1f%n"
				+ "  server: paused=%s, keepAwake=%s, gtaPresent=%s, pause hook calls=%d overrides=%d",
			bridge.describe(), wasAlive ? "connected" : "absent", tf.valid, sessionSerial, sessions, hellos, anchors, viewH, viewV,
			player.frame, streamer.streamedCount(), streamer.pendingLoads(), st.voxelsSent, st.updatesSent, st.unloadsSent,
			palette.size(), st.scans, st.scans == 0 ? 0.0 : st.scanNanos / 1000.0 / st.scans, chunks.heldColumns(), executor.breaks,
			executor.places, executor.collisionBreaks, executor.denied, bridge.recordsOut(), bridge.recordsIn(), bridge.droppedOut(),
			bridge.corruptRecords(), rttMs, msptEma, server.isPaused(), keepAwake(), bridge.gtaPresent(), mixinCalls.get(), mixinOverrides.get());
	}
}
