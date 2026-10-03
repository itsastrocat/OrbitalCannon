package com.orbitalcannon.passthrough;

import com.orbitalcannon.passthrough.ipc.Protocol;
import com.orbitalcannon.passthrough.ipc.SharedMemory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Settings, read from {@code config/orbital-passthrough.properties} (written with defaults on first run). */
public final class PassthroughConfig {
	// ---- ipc ----
	public String ipcName = Protocol.DEFAULT_NAME;
	public SharedMemory.Backend ipcBackend = SharedMemory.Backend.AUTO;

	// ---- world ----
	public String dimension = "minecraft:overworld";
	/** Never let the dedicated server auto-pause for lack of players while a GTA client is attached. */
	public boolean keepServerAwake = true;
	/** When false the anchor is the world spawn standing position; otherwise {@code anchorX/Y/Z}. */
	public boolean fixedAnchor = false;
	public double anchorX, anchorY = 64, anchorZ;

	// ---- streaming ----
	public float maxViewRadiusH = 48f;
	public float maxViewRadiusV = 32f;
	/** Wall-clock budget for streaming work per server tick. */
	public long streamBudgetMicros = 2500;
	public int maxSectionsPerTick = 12;

	// ---- interaction ----
	/** A block breaks once accumulated GTA damage reaches hardness x this. A 26-damage pistol round breaks dirt in 1 hit, stone in 2. */
	public float damagePerHardness = 30f;
	public double reachBullet = 350, reachTool = 12, reachExplosive = 350, reachMelee = 8, reachVehicle = 20;
	public boolean dropItems = false;
	public int maxRequestsPerTick = 64;
	public float explosiveRadius = 3.5f;
	public int explosiveMaxBlocks = 300;
	/** Joules of impact energy needed per point of block hardness for a vehicle to break it. */
	public float vehicleEnergyPerHardness = 2500f;
	public int vehicleMaxDepth = 3;
	public boolean pedImpactsBreak = false;
	public double playerEyeHeight = 1.6;

	public static PassthroughConfig load(Path file) {
		PassthroughConfig c = new PassthroughConfig();
		Properties p = new Properties();
		if (Files.isRegularFile(file)) {
			try (InputStream in = Files.newInputStream(file)) {
				p.load(in);
			} catch (IOException e) {
				PassthroughMod.LOGGER.warn("Could not read {}: {}", file, e.toString());
			}
		}
		c.ipcName = p.getProperty("ipc.name", c.ipcName);
		c.ipcBackend = SharedMemory.Backend.parse(p.getProperty("ipc.backend", "auto"));
		c.dimension = p.getProperty("world.dimension", c.dimension);
		c.keepServerAwake = bool(p, "world.keepServerAwake", c.keepServerAwake);
		c.fixedAnchor = bool(p, "world.fixedAnchor", c.fixedAnchor);
		c.anchorX = dbl(p, "world.anchorX", c.anchorX);
		c.anchorY = dbl(p, "world.anchorY", c.anchorY);
		c.anchorZ = dbl(p, "world.anchorZ", c.anchorZ);
		c.maxViewRadiusH = (float) dbl(p, "stream.maxViewRadiusH", c.maxViewRadiusH);
		c.maxViewRadiusV = (float) dbl(p, "stream.maxViewRadiusV", c.maxViewRadiusV);
		c.streamBudgetMicros = (long) dbl(p, "stream.budgetMicrosPerTick", c.streamBudgetMicros);
		c.maxSectionsPerTick = (int) dbl(p, "stream.maxSectionsPerTick", c.maxSectionsPerTick);
		c.damagePerHardness = (float) dbl(p, "interact.damagePerHardness", c.damagePerHardness);
		c.reachBullet = dbl(p, "interact.reach.bullet", c.reachBullet);
		c.reachTool = dbl(p, "interact.reach.tool", c.reachTool);
		c.reachExplosive = dbl(p, "interact.reach.explosive", c.reachExplosive);
		c.reachMelee = dbl(p, "interact.reach.melee", c.reachMelee);
		c.reachVehicle = dbl(p, "interact.reach.vehicle", c.reachVehicle);
		c.dropItems = bool(p, "interact.dropItems", c.dropItems);
		c.maxRequestsPerTick = (int) dbl(p, "interact.maxRequestsPerTick", c.maxRequestsPerTick);
		c.explosiveRadius = (float) dbl(p, "interact.explosiveRadius", c.explosiveRadius);
		c.explosiveMaxBlocks = (int) dbl(p, "interact.explosiveMaxBlocks", c.explosiveMaxBlocks);
		c.vehicleEnergyPerHardness = (float) dbl(p, "interact.vehicleEnergyPerHardness", c.vehicleEnergyPerHardness);
		c.vehicleMaxDepth = (int) dbl(p, "interact.vehicleMaxDepth", c.vehicleMaxDepth);
		c.pedImpactsBreak = bool(p, "interact.pedImpactsBreak", c.pedImpactsBreak);
		c.playerEyeHeight = dbl(p, "interact.playerEyeHeight", c.playerEyeHeight);
		// Environment overrides win, so tests and launchers can redirect the mapping without touching the file.
		String sysName = System.getProperty("ocp.shm");
		if (sysName != null && !sysName.isBlank()) c.ipcName = sysName;
		String sysAwake = System.getProperty("ocp.keepAwake");
		if (sysAwake != null && !sysAwake.isBlank()) c.keepServerAwake = Boolean.parseBoolean(sysAwake.trim());
		String sysBackend = System.getProperty("ocp.backend");
		if (sysBackend != null && !sysBackend.isBlank()) c.ipcBackend = SharedMemory.Backend.parse(sysBackend);
		return c;
	}

	/** Writes a commented default file if none exists, so the options are discoverable. */
	public static void writeDefaultsIfMissing(Path file) {
		if (Files.exists(file)) return;
		try {
			Files.createDirectories(file.getParent());
			try (OutputStream out = Files.newOutputStream(file)) {
				out.write(DEFAULT_FILE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			PassthroughMod.LOGGER.warn("Could not write default config {}: {}", file, e.toString());
		}
	}

	private static boolean bool(Properties p, String k, boolean d) {
		String v = p.getProperty(k);
		return v == null ? d : v.trim().equalsIgnoreCase("true") || v.trim().equals("1");
	}

	private static double dbl(Properties p, String k, double d) {
		String v = p.getProperty(k);
		if (v == null) return d;
		try {
			return Double.parseDouble(v.trim());
		} catch (NumberFormatException e) {
			PassthroughMod.LOGGER.warn("Ignoring invalid number for {}: {}", k, v);
			return d;
		}
	}

	private static final String DEFAULT_FILE = """
		# Orbital Passthrough: Minecraft side. Restart the server after editing.
		# The GTA V side has its own OrbitalPassthrough.ini.

		# Shared-memory object name; must match [bridge] name in the GTA V INI.
		ipc.name=OrbitalCannonPassthrough_v1
		# auto = named Windows section on Windows, mapped file elsewhere. Also: windows, file.
		ipc.backend=auto

		# Dimension that is mirrored into GTA V.
		world.dimension=minecraft:overworld
		# Stop the dedicated server from pausing after "pause-when-empty-seconds" while GTA V is attached.
		world.keepServerAwake=true
		# false = anchor on the world spawn's standing position; true = use anchorX/Y/Z below.
		world.fixedAnchor=false
		world.anchorX=0
		world.anchorY=64
		world.anchorZ=0

		# Upper bounds on what GTA V may ask to stream (it requests its own radius in its HELLO).
		stream.maxViewRadiusH=48
		stream.maxViewRadiusV=32
		stream.budgetMicrosPerTick=2500
		stream.maxSectionsPerTick=12

		# A block breaks once accumulated GTA damage reaches hardness x damagePerHardness.
		interact.damagePerHardness=30
		interact.reach.bullet=350
		interact.reach.tool=12
		interact.reach.explosive=350
		interact.reach.melee=8
		interact.reach.vehicle=20
		interact.dropItems=false
		interact.maxRequestsPerTick=64
		interact.explosiveRadius=3.5
		interact.explosiveMaxBlocks=300
		interact.vehicleEnergyPerHardness=2500
		interact.vehicleMaxDepth=3
		interact.pedImpactsBreak=false
		interact.playerEyeHeight=1.6
		""";
}
