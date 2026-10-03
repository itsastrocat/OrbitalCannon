package com.orbitalcannon.passthrough;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /passthrough status} and {@code /passthrough resync}. */
final class PassthroughCommand {
	private PassthroughCommand() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
			Commands.literal("passthrough")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("status").executes(ctx -> {
					PassthroughService s = PassthroughMod.service();
					String text = s == null ? "Passthrough is not running (see the server log)." : s.statusText();
					ctx.getSource().sendSuccess(() -> Component.literal(text), false);
					return 1;
				}))
				.then(Commands.literal("resync").executes(ctx -> {
					PassthroughService s = PassthroughMod.service();
					if (s == null) {
						ctx.getSource().sendFailure(Component.literal("Passthrough is not running."));
						return 0;
					}
					s.requestResync();
					ctx.getSource().sendSuccess(() -> Component.literal("Resync requested: GTA V will receive the whole world again."), true);
					return 1;
				}))
		);
	}
}
