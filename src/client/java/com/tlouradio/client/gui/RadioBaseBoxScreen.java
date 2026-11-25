package com.tlouradio.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.network.payload.UpdateBaseBoxConfigPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public class RadioBaseBoxScreen extends Screen {
	private static final int PANEL_WIDTH = 200;
	private static final int PANEL_HEIGHT = 158;

	private final BlockPos blockPos;
	private final UUID networkId;
	private final String initialFrequency;
	private final String initialPasscode;

	private EditBox frequencyBox;
	private EditBox passcodeBox;
	private Button saveButton;
	private boolean sanitizingInput = false;

	public RadioBaseBoxScreen(BlockPos blockPos, UUID networkId, String initialFrequency, String initialPasscode) {
		super(Component.translatable("gui.tlouradio.base_box.title"));
		this.blockPos = blockPos;
		this.networkId = networkId;
		this.initialFrequency = initialFrequency;
		this.initialPasscode = initialPasscode;
	}

	@Override
	protected void init() {
		int centerX = this.width / 2;
		int topY = (this.height - PANEL_HEIGHT) / 2;

		this.frequencyBox = new EditBox(
				this.font,
				centerX - 80,
				topY + 50,
				160,
				20,
				Component.translatable("gui.tlouradio.base_box.frequency")
		);
		this.frequencyBox.setMaxLength(RadioStateComponent.MAX_FREQUENCY_LENGTH);
		this.frequencyBox.setValue(this.initialFrequency);
		this.frequencyBox.setResponder(this::onFrequencyChanged);

		this.passcodeBox = new EditBox(
				this.font,
				centerX - 80,
				topY + 94,
				160,
				20,
				Component.translatable("gui.tlouradio.base_box.passcode")
		);
		this.passcodeBox.setMaxLength(RadioStateComponent.MAX_PASSCODE_LENGTH);
		this.passcodeBox.setHint(Component.translatable("gui.tlouradio.base_box.passcode_hint"));
		this.passcodeBox.setValue(this.initialPasscode);
		this.passcodeBox.setResponder(this::onPasscodeChanged);

		this.saveButton = Button.builder(
				Component.translatable("gui.tlouradio.base_box.save"),
				button -> submitAndClose()
		).bounds(centerX - 80, topY + 126, 76, 20).build();

		Button cancelButton = Button.builder(
				CommonComponents.GUI_CANCEL,
				button -> onClose()
		).bounds(centerX + 4, topY + 126, 76, 20).build();

		addRenderableWidget(this.frequencyBox);
		addRenderableWidget(this.passcodeBox);
		addRenderableWidget(this.saveButton);
		addRenderableWidget(cancelButton);

		setInitialFocus(this.frequencyBox);
		updateSaveButtonState();
	}

	private void onFrequencyChanged(String raw) {
		if (this.sanitizingInput) {
			return;
		}
		String sanitized = sanitizeFrequencyChars(raw);
		if (!sanitized.equals(raw)) {
			this.sanitizingInput = true;
			this.frequencyBox.setValue(sanitized);
			this.sanitizingInput = false;
		}
		updateSaveButtonState();
	}

	private void onPasscodeChanged(String raw) {
		if (this.sanitizingInput) {
			return;
		}
		String sanitized = sanitizePasscodeChars(raw);
		if (!sanitized.equals(raw)) {
			this.sanitizingInput = true;
			this.passcodeBox.setValue(sanitized);
			this.sanitizingInput = false;
		}
		updateSaveButtonState();
	}

	private static String sanitizeFrequencyChars(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(value.length());
		boolean seenDot = false;
		for (int i = 0; i < value.length() && sb.length() < RadioStateComponent.MAX_FREQUENCY_LENGTH; i++) {
			char c = value.charAt(i);
			if (c >= '0' && c <= '9') {
				sb.append(c);
			} else if (c == '.' && !seenDot) {
				seenDot = true;
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static String sanitizePasscodeChars(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder(value.length());
		for (int i = 0; i < value.length() && sb.length() < RadioStateComponent.MAX_PASSCODE_LENGTH; i++) {
			char c = value.charAt(i);
			if (c >= '0' && c <= '9') {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private void updateSaveButtonState() {
		if (this.saveButton == null || this.frequencyBox == null || this.passcodeBox == null) {
			return;
		}
		this.saveButton.active = RadioStateComponent.isValidFrequency(this.frequencyBox.getValue())
				&& RadioStateComponent.isValidPasscode(this.passcodeBox.getValue());
	}

	private void submitAndClose() {
		String freq = this.frequencyBox.getValue();
		String pass = this.passcodeBox.getValue();
		if (!RadioStateComponent.isValidFrequency(freq) || !RadioStateComponent.isValidPasscode(pass)) {
			return;
		}
		if (ClientPlayNetworking.canSend(UpdateBaseBoxConfigPayload.TYPE)) {
			ClientPlayNetworking.send(new UpdateBaseBoxConfigPayload(this.blockPos, freq, pass));
		}
		onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			if (this.saveButton != null && this.saveButton.active) {
				submitAndClose();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int leftX = (this.width - PANEL_WIDTH) / 2;
		int topY = (this.height - PANEL_HEIGHT) / 2;
		int centerX = this.width / 2;

		graphics.fill(leftX, topY, leftX + PANEL_WIDTH, topY + PANEL_HEIGHT, 0xD0141816);
		graphics.outline(leftX, topY, PANEL_WIDTH, PANEL_HEIGHT, 0xFF4A5A50);

		graphics.centeredText(this.font, this.title, centerX, topY + 10, 0xFFFFFFFF);
		graphics.centeredText(
				this.font,
				Component.translatable("gui.tlouradio.base_box.network_id", RadioStateComponent.shortNetworkId(this.networkId)),
				centerX,
				topY + 23,
				0xFF88B4A0
		);

		graphics.text(
				this.font,
				Component.translatable("gui.tlouradio.base_box.frequency"),
				centerX - 80,
				topY + 39,
				0xFFD0D6D2
		);
		graphics.text(
				this.font,
				Component.translatable("gui.tlouradio.base_box.passcode"),
				centerX - 80,
				topY + 83,
				0xFFD0D6D2
		);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
