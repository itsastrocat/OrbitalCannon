package com.orbitalcannon.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import com.orbitalcannon.client.screen.TargetingScreen;
import com.orbitalcannon.registry.ModItems;
import com.orbitalcannon.strike.OrbitalStrikeManager;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * End-to-end test of the whole mod in a real client: item use -> server-authorized S2C screen open -> GUI input
 * validation -> FIRE (C2S) -> all four strike phases -> crater shape and player restoration.
 * Screenshots of each stage land in build/run/clientGameTest/screenshots.
 */
public class OrbitalStrikeClientGameTest implements FabricClientGameTest {
	private static final int PLATEAU_HALF_WIDTH = 20;
	private static final int PLATEAU_HEIGHT = 20;
	private static final int PLATEAU_OFFSET_X = 40;
	/** Software renderers (e.g. CI under Xvfb) can take minutes to mesh chunks. */
	private static final int CHUNK_RENDER_TIMEOUT_TICKS = 20 * 60 * 10;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(minecraft -> minecraft.options.renderDistance().set(6));

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender(CHUNK_RENDER_TIMEOUT_TICKS);

			// --- Arrange: a stone plateau with a tower in the blast zone and a control tower just outside it.
			Setup setup = singleplayer.getServer().computeOnServer(OrbitalStrikeClientGameTest::buildArena);
			singleplayer.getConnection().waitForChunksRender(CHUNK_RENDER_TIMEOUT_TICKS);

			TestInput input = context.getInput();
			input.lookAt(setup.target().above(4));
			context.waitTicks(5);
			context.takeScreenshot("01_target_area");

			// --- Trigger: right-click with the Targeting Remote opens the GUI via the server's S2C packet.
			input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitForScreen(TargetingScreen.class);
			List<String> prefilled = context.computeOnClient(minecraft -> fieldValues(minecraft.gui.screen()));
			check(prefilled.stream().noneMatch(String::isEmpty), "Remote rangefinder should pre-fill all three fields, got " + prefilled);
			context.takeScreenshot("02_targeting_screen_prefilled");

			// --- GUI validation: letters are filtered out, and a malformed integer keeps FIRE disabled.
			clearAndType(input, "12abc-");
			context.waitTick();
			String filtered = context.computeOnClient(minecraft -> fieldValues(minecraft.gui.screen()).getFirst());
			check(filtered.equals("12-"), "Non-numeric characters should be filtered, got '" + filtered + "'");
			check(!context.computeOnClient(minecraft -> fireButton(minecraft.gui.screen()).active), "FIRE must be disabled for '12-'");
			context.takeScreenshot("03_invalid_input");

			clearAndType(input, Integer.toString(setup.target().getX()));
			input.pressKey(InputConstants.KEY_TAB);
			clearAndType(input, Integer.toString(setup.target().getY()));
			input.pressKey(InputConstants.KEY_TAB);
			clearAndType(input, Integer.toString(setup.target().getZ()));
			context.waitTick();
			List<String> entered = context.computeOnClient(minecraft -> fieldValues(minecraft.gui.screen()));
			check(
				entered.equals(List.of(
					Integer.toString(setup.target().getX()), Integer.toString(setup.target().getY()), Integer.toString(setup.target().getZ())
				)),
				"Typed coordinates should land in the fields, got " + entered
			);
			check(context.computeOnClient(minecraft -> fireButton(minecraft.gui.screen()).active), "FIRE must be enabled for valid integers");
			context.takeScreenshot("04_valid_input");

			// --- FIRE: C2S packet -> Phase 1.
			context.clickScreenButton("orbitalcannon.screen.fire");
			singleplayer.getServer().waitFor(server -> player(server).isSpectator(), 100);
			context.waitTicks(30);
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = player(server);
				check(player.gameMode() == GameType.SPECTATOR, "Phase 1: player should be a spectator");
				check(Math.abs(player.getY() - (setup.target().getY() + 60)) < 1.0, "Phase 1: camera should be 60 blocks above target, y=" + player.getY());
				check(player.getXRot() > 85.0F, "Phase 1: camera should look straight down, pitch=" + player.getXRot());
				check(!blockAt(server, setup.target()).isAir(), "Phase 2: nothing should be destroyed during the countdown");
			});
			context.takeScreenshot("05_phase1_target_lock_aerial");

			// --- Phase 3, mid-barrage.
			context.waitTicks(80);
			context.takeScreenshot("06_phase3_annihilation");

			// --- Phase 4: wait for the strike to finish and the player to come home.
			singleplayer.getServer().waitFor(server -> !OrbitalStrikeManager.isStriking(player(server)), 1200);
			singleplayer.getServer().runOnServer(server -> verifyAftermath(server, setup));

			singleplayer.getConnection().waitForChunksRender(CHUNK_RENDER_TIMEOUT_TICKS);
			input.lookAt(setup.target().below(6));
			context.waitTicks(5);
			context.takeScreenshot("07_returned_view_of_crater");

			singleplayer.getServer().runCommand(String.format(
				"tp @p %d %d %d 0 70", setup.target().getX(), setup.target().getY() + 30, setup.target().getZ() - 22
			));
			singleplayer.getConnection().waitForChunksRender(CHUNK_RENDER_TIMEOUT_TICKS);
			context.waitTicks(10);
			context.takeScreenshot("08_crater_overview");
		}
	}

	private record Setup(BlockPos target, BlockPos controlTower, Vec3 origin, GameType originalGameMode) {
	}

	private static Setup buildArena(MinecraftServer server) {
		ServerPlayer player = player(server);
		ServerLevel level = player.level();
		BlockPos origin = player.blockPosition();
		int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, origin.getX() + PLATEAU_OFFSET_X, origin.getZ());
		BlockPos center = new BlockPos(origin.getX() + PLATEAU_OFFSET_X, groundY, origin.getZ());

		for (int dx = -PLATEAU_HALF_WIDTH; dx <= PLATEAU_HALF_WIDTH; dx++) {
			for (int dz = -PLATEAU_HALF_WIDTH; dz <= PLATEAU_HALF_WIDTH; dz++) {
				for (int dy = 0; dy < PLATEAU_HEIGHT; dy++) {
					BlockState state = dy >= PLATEAU_HEIGHT - 1 ? Blocks.GRASS_BLOCK.defaultBlockState()
						: dy >= PLATEAU_HEIGHT - 4 ? Blocks.DIRT.defaultBlockState()
						: dy == 8 ? Blocks.OBSIDIAN.defaultBlockState()
						: Blocks.STONE.defaultBlockState();
					level.setBlockAndUpdate(center.offset(dx, dy, dz), state);
				}
			}
		}

		BlockPos target = center.above(PLATEAU_HEIGHT - 1);
		// Tower in the blast zone, and a control tower 18 blocks out that must survive.
		BlockPos controlTower = target.offset(18, 1, 0);
		for (int dy = 1; dy <= 8; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
						level.setBlockAndUpdate(target.offset(dx, dy, dz), Blocks.OAK_PLANKS.defaultBlockState());
					}
				}
			}

			level.setBlockAndUpdate(controlTower.above(dy - 1), Blocks.GOLD_BLOCK.defaultBlockState());
		}

		// A water pocket inside the blast zone, to check fluids get cleared too.
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				level.setBlockAndUpdate(target.offset(dx + 6, -2, dz), Blocks.WATER.defaultBlockState());
			}
		}

		player.setGameMode(GameType.CREATIVE);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.TARGETING_REMOTE));
		return new Setup(target, controlTower, player.position(), player.gameMode());
	}

	private static void verifyAftermath(MinecraftServer server, Setup setup) {
		ServerPlayer player = player(server);
		ServerLevel level = player.level();
		check(player.gameMode() == setup.originalGameMode(), "Phase 4: game mode should be restored, was " + player.gameMode());
		check(player.position().distanceTo(setup.origin()) < 0.5, "Phase 4: player should be back at " + setup.origin() + ", is at " + player.position());

		Vec3 center = Vec3.atCenterOf(setup.target());
		int sphereBlocks = 0;
		int survivors = 0;
		for (int dx = -15; dx <= 15; dx++) {
			for (int dy = -15; dy <= 15; dy++) {
				for (int dz = -15; dz <= 15; dz++) {
					BlockPos pos = setup.target().offset(dx, dy, dz);
					if (center.distanceToSqr(Vec3.atCenterOf(pos)) <= 15.0 * 15.0) {
						sphereBlocks++;
						if (!level.getBlockState(pos).isAir()) {
							survivors++;
						}
					}
				}
			}
		}

		check(survivors == 0, "Phase 3: " + survivors + " of " + sphereBlocks + " blocks inside the sphere survived");
		check(level.getBlockState(setup.controlTower()).is(Blocks.GOLD_BLOCK), "Control tower outside the sphere must survive");
		check(level.getBlockState(setup.target().below(16)).is(Blocks.STONE), "Plateau just below the sphere must survive");
		check(level.getBlockState(setup.target().offset(PLATEAU_HALF_WIDTH, 0, PLATEAU_HALF_WIDTH)).is(Blocks.GRASS_BLOCK), "Plateau corner must survive");

		AABB zone = AABB.ofSize(center, 40, 40, 40);
		check(level.getEntitiesOfClass(PrimedTnt.class, zone).isEmpty(), "No primed TNT should be left behind");
		check(level.getEntitiesOfClass(ItemEntity.class, zone).isEmpty(), "Debris should be vaporized");
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static BlockState blockAt(MinecraftServer server, BlockPos pos) {
		return player(server).level().getBlockState(pos);
	}

	/** Clears the focused field with real key presses (End, then Backspace) and types the replacement. */
	private static void clearAndType(TestInput input, String text) {
		input.pressKey(InputConstants.KEY_END);
		for (int i = 0; i < 12; i++) {
			input.pressKey(InputConstants.KEY_BACKSPACE);
		}

		input.typeChars(text);
	}

	private static List<String> fieldValues(Screen screen) {
		return screen.children().stream().filter(EditBox.class::isInstance).map(child -> ((EditBox) child).getValue()).toList();
	}

	private static Button fireButton(Screen screen) {
		return screen.children()
			.stream()
			.filter(child -> child instanceof Button button && button.getMessage().getString().equals("FIRE"))
			.map(Button.class::cast)
			.findFirst()
			.orElseThrow();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
