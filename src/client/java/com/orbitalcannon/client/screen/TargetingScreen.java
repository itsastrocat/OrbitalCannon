package com.orbitalcannon.client.screen;

import com.orbitalcannon.network.FireStrikePayload;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * Coordinate entry for the orbital cannon: three integer fields and a FIRE button that only enables once all
 * three hold valid integers inside the world's build height.
 */
public class TargetingScreen extends Screen {
	/** How far the Targeting Remote's rangefinder looks when pre-filling coordinates. */
	private static final double RANGEFINDER_DISTANCE = 256.0;
	private static final int PANEL_WIDTH = 220;
	private static final int PANEL_HEIGHT = 168;
	private static final int FIELD_WIDTH = 150;
	private static final int ROW_HEIGHT = 26;

	private static final int COLOR_PANEL = 0xE0101418;
	private static final int COLOR_PANEL_BORDER = 0xFFB02020;
	private static final int COLOR_TITLE = 0xFFFF5040;
	private static final int COLOR_LABEL = 0xFFE0E0E0;
	private static final int COLOR_VALID = 0xFFE0E0E0;
	private static final int COLOR_INVALID = 0xFFFF5555;
	private static final int COLOR_STATUS_OK = 0xFF55FF55;
	private static final int COLOR_STATUS_BAD = 0xFFFF5555;
	private static final int COLOR_HINT = 0xFF808080;

	private static final Component[] AXIS_LABELS = {Component.literal("X"), Component.literal("Y"), Component.literal("Z")};

	/** Remembered across screen openings so a second strike on the same spot is quick. */
	private static String @Nullable [] lastFired;

	private final String[] initialValues;
	private final IntegerField[] fields = new IntegerField[3];
	private Button fireButton;
	private Component status = Component.empty();
	private boolean statusOk;

	private TargetingScreen(String[] initialValues) {
		super(Component.translatable("orbitalcannon.screen.title"));
		this.initialValues = initialValues;
	}

	/**
	 * @param fromRemote when true the fields are pre-filled with the block the player is looking at (up to
	 *                   {@link #RANGEFINDER_DISTANCE} blocks away), falling back to the last fired coordinates
	 */
	public static TargetingScreen create(Minecraft minecraft, boolean fromRemote) {
		if (fromRemote && minecraft.player != null) {
			HitResult hit = minecraft.player.pick(RANGEFINDER_DISTANCE, 1.0F, false);
			if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
				BlockPos pos = blockHit.getBlockPos();
				return new TargetingScreen(new String[] {Integer.toString(pos.getX()), Integer.toString(pos.getY()), Integer.toString(pos.getZ())});
			}
		}

		return new TargetingScreen(lastFired != null ? lastFired.clone() : new String[] {"", "", ""});
	}

	@Override
	protected void init() {
		int left = (this.width - PANEL_WIDTH) / 2;
		int top = (this.height - PANEL_HEIGHT) / 2;
		int fieldX = left + PANEL_WIDTH - FIELD_WIDTH - 16;

		for (int i = 0; i < 3; i++) {
			String value = this.fields[i] != null ? this.fields[i].getValue() : this.initialValues[i];
			IntegerField field = new IntegerField(this.font, fieldX, top + 30 + i * ROW_HEIGHT, FIELD_WIDTH, 20, AXIS_LABELS[i]);
			field.setMaxLength(11); // "-2147483648"
			field.setHint(Component.translatable("orbitalcannon.screen.hint", AXIS_LABELS[i]).withStyle(ChatFormatting.DARK_GRAY));
			field.setValue(value);
			field.setResponder(text -> this.validate());
			this.fields[i] = this.addRenderableWidget(field);
		}

		int buttonY = top + PANEL_HEIGHT - 30;
		int buttonWidth = (PANEL_WIDTH - 16 * 2 - 8) / 2;
		this.fireButton = this.addRenderableWidget(
			Button.builder(Component.translatable("orbitalcannon.screen.fire").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), button -> this.fire())
				.bounds(left + 16, buttonY, buttonWidth, 20)
				.build()
		);
		this.addRenderableWidget(
			Button.builder(CommonComponents.GUI_CANCEL, button -> this.onClose()).bounds(left + 16 + buttonWidth + 8, buttonY, buttonWidth, 20).build()
		);

		this.validate();
	}

	@Override
	protected void setInitialFocus() {
		this.setInitialFocus(this.fields[0]);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation() && this.fireButton.active) {
			this.fire();
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Re-checks all three fields, colours the bad ones red, and only enables FIRE when everything is valid. */
	private void validate() {
		boolean allValid = true;
		OptionalInt y = OptionalInt.empty();
		for (int i = 0; i < 3; i++) {
			OptionalInt parsed = this.fields[i].parsedValue();
			this.fields[i].setTextColor(parsed.isPresent() ? COLOR_VALID : COLOR_INVALID);
			allValid &= parsed.isPresent();
			if (i == 1) {
				y = parsed;
			}
		}

		if (!allValid) {
			this.setStatus(Component.translatable("orbitalcannon.screen.status.invalid"), false);
			return;
		}

		Level level = this.minecraft.level;
		if (level != null && (y.getAsInt() < level.getMinY() || y.getAsInt() > level.getMaxY())) {
			this.fields[1].setTextColor(COLOR_INVALID);
			this.setStatus(Component.translatable("orbitalcannon.screen.status.height", level.getMinY(), level.getMaxY()), false);
			return;
		}

		this.setStatus(Component.translatable("orbitalcannon.screen.status.ready"), true);
	}

	private void setStatus(Component status, boolean ok) {
		this.status = status;
		this.statusOk = ok;
		this.fireButton.active = ok;
	}

	private void fire() {
		OptionalInt x = this.fields[0].parsedValue();
		OptionalInt y = this.fields[1].parsedValue();
		OptionalInt z = this.fields[2].parsedValue();
		if (!this.fireButton.active || x.isEmpty() || y.isEmpty() || z.isEmpty()) {
			return;
		}

		ClientPlayNetworking.send(new FireStrikePayload(x.getAsInt(), y.getAsInt(), z.getAsInt()));
		lastFired = new String[] {this.fields[0].getValue(), this.fields[1].getValue(), this.fields[2].getValue()};
		this.onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		int left = (this.width - PANEL_WIDTH) / 2;
		int top = (this.height - PANEL_HEIGHT) / 2;

		// Panel with a 1px red border.
		graphics.fill(left - 1, top - 1, left + PANEL_WIDTH + 1, top + PANEL_HEIGHT + 1, COLOR_PANEL_BORDER);
		graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, COLOR_PANEL);

		graphics.centeredText(this.font, this.title, this.width / 2, top + 10, COLOR_TITLE);
		for (int i = 0; i < 3; i++) {
			IntegerField field = this.fields[i];
			graphics.text(this.font, AXIS_LABELS[i], left + 20, field.getY() + 6, COLOR_LABEL);
		}

		graphics.centeredText(this.font, this.status, this.width / 2, top + 30 + 3 * ROW_HEIGHT + 2, this.statusOk ? COLOR_STATUS_OK : COLOR_STATUS_BAD);
		graphics.centeredText(this.font, Component.translatable("orbitalcannon.screen.warning"), this.width / 2, top + 30 + 3 * ROW_HEIGHT + 14, COLOR_HINT);

		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	/** An edit box that only accepts characters that can appear in an integer: digits and a leading minus sign. */
	private static final class IntegerField extends EditBox {
		IntegerField(Font font, int x, int y, int width, int height, Component narration) {
			super(font, x, y, width, height, narration);
		}

		@Override
		public void insertText(String input) {
			StringBuilder filtered = new StringBuilder(input.length());
			for (int i = 0; i < input.length(); i++) {
				char c = input.charAt(i);
				if ((c >= '0' && c <= '9') || c == '-') {
					filtered.append(c);
				}
			}

			if (!filtered.isEmpty()) {
				super.insertText(filtered.toString());
			}
		}

		/** The field's value if it is a valid 32-bit integer (e.g. not empty, not "-", not "1-2", not overflowing). */
		OptionalInt parsedValue() {
			try {
				return OptionalInt.of(Integer.parseInt(this.getValue().trim()));
			} catch (NumberFormatException e) {
				return OptionalInt.empty();
			}
		}
	}
}
