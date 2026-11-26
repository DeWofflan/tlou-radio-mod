package com.tlouradio.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.tlouradio.client.RadioClientInputHandler;
import com.tlouradio.client.gui.render.HandheldRadioModelRenderer;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.network.payload.UpdateHandheldRadioPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

public class HandheldRadioScreen extends Screen {
	private enum InputMode {
		FREQUENCY,
		PASSCODE
	}

	private final InteractionHand hand;
	private final boolean initiallyPaired;
	private final boolean hadInitialPasscode;
	private final String preservedUnmodifiedFreePasscode;

	private boolean isPowered;
	private String activeSavedFrequency;
	private Optional<UUID> activeNetworkId;

	private InputMode inputMode = InputMode.FREQUENCY;
	private String frequencyBuffer;
	private String passcodeBuffer = "";
	private boolean passcodeModified = false;
	private int invalidFlashTicks = 0;

	private final HandheldRadioModelRenderer.AnimationState animationState =
			new HandheldRadioModelRenderer.AnimationState();
	private HandheldRadioModelRenderer.DeviceLayout layout;

	public HandheldRadioScreen(InteractionHand hand, ItemStack initialStack) {
		super(Component.translatable("gui.tlouradio.handheld.title"));
		this.hand = hand;

		RadioStateComponent initialState = initialStack.getOrDefault(
				ModDataComponents.RADIO_STATE,
				RadioStateComponent.DEFAULT
		);
		this.isPowered = initialState.isPowered();
		this.activeSavedFrequency = initialState.frequency();
		this.activeNetworkId = initialState.networkId();

		// PIN Masking Security: never expose existing/paired passcode in any plaintext UI buffer
		this.initiallyPaired = initialState.networkId().isPresent();
		this.hadInitialPasscode = !initialState.passcode().isEmpty();
		this.preservedUnmodifiedFreePasscode = this.initiallyPaired ? "" : initialState.passcode();

		this.frequencyBuffer = initialState.frequency();
	}

	@Override
	protected void init() {
		this.layout = HandheldRadioModelRenderer.computeLayout(this.width, this.height);
		buildAlignedKeypad(this.layout);
	}

	private void buildAlignedKeypad(HandheldRadioModelRenderer.DeviceLayout d) {
		int startX = d.keypadX();
		int startY = d.keypadY();

		int digitW = 20;
		int funcW = 40;
		int rowH = 22;
		int gapX = 2;
		int gapY = 3;

		int col0 = startX;
		int col1 = col0 + digitW + gapX;
		int col2 = col1 + digitW + gapX;
		int col3 = col2 + digitW + gapX;

		int row0 = startY;
		int row1 = row0 + rowH + gapY;
		int row2 = row1 + rowH + gapY;
		int row3 = row2 + rowH + gapY;

		// Row 0: 1, 2, 3, FRQ/PIN
		addKeyButton("1", Component.literal("1"), col0, row0, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('1'));
		addKeyButton("2", Component.literal("2"), col1, row0, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('2'));
		addKeyButton("3", Component.literal("3"), col2, row0, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('3'));
		addKeyButton(
				"MODE",
				Component.translatable("gui.tlouradio.handheld.key_mode"),
				col3,
				row0,
				funcW,
				rowH,
				KeyRole.MODE,
				this::toggleInputMode
		);

		// Row 1: 4, 5, 6, Sil
		addKeyButton("4", Component.literal("4"), col0, row1, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('4'));
		addKeyButton("5", Component.literal("5"), col1, row1, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('5'));
		addKeyButton("6", Component.literal("6"), col2, row1, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('6'));
		addKeyButton(
				"DEL",
				Component.translatable("gui.tlouradio.handheld.key_delete"),
				col3,
				row1,
				funcW,
				rowH,
				KeyRole.DELETE,
				this::handleDelete
		);

		// Row 2: 7, 8, 9 + Tall Enter spanning Row 2 & Row 3
		addKeyButton("7", Component.literal("7"), col0, row2, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('7'));
		addKeyButton("8", Component.literal("8"), col1, row2, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('8'));
		addKeyButton("9", Component.literal("9"), col2, row2, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('9'));
		addKeyButton(
				"ENT",
				Component.translatable("gui.tlouradio.handheld.key_enter"),
				col3,
				row2,
				funcW,
				rowH * 2 + gapY,
				KeyRole.ENTER,
				this::handleSubmit
		);

		// Row 3: Wide 0 + '.'
		addKeyButton("0", Component.literal("0"), col0, row3, digitW * 2 + gapX, rowH, KeyRole.DIGIT, () -> handleDigit('0'));
		addKeyButton(".", Component.literal("."), col2, row3, digitW, rowH, KeyRole.DOT, this::handleDot);
	}

	private void addKeyButton(
			String keyId,
			Component label,
			int x,
			int y,
			int w,
			int h,
			KeyRole role,
			Runnable action
	) {
		addRenderableWidget(new RadioKeypadButton(keyId, x, y, w, h, label, role, action));
	}

	@Override
	public void tick() {
		super.tick();
		if (this.invalidFlashTicks > 0) {
			this.invalidFlashTicks--;
		}

		if (this.minecraft.player == null
				|| !this.minecraft.player.isAlive()
				|| this.minecraft.player.isSpectator()) {
			onClose();
			return;
		}

		ItemStack heldStack = this.minecraft.player.getItemInHand(this.hand);
		if (heldStack.isEmpty() || !(heldStack.getItem() instanceof HandheldRadioItem)) {
			onClose();
			return;
		}

		RadioStateComponent liveState = heldStack.getOrDefault(
				ModDataComponents.RADIO_STATE,
				RadioStateComponent.DEFAULT
		);
		this.isPowered = liveState.isPowered();
		this.activeSavedFrequency = liveState.frequency();
		this.activeNetworkId = liveState.networkId();
		this.animationState.setPttPressed(RadioClientInputHandler.PTT_KEY.isDown());
	}

	private void handleDigit(char digit) {
		if (digit < '0' || digit > '9') {
			return;
		}
		String keyId = String.valueOf(digit);
		int digitIndex = digit - '0';
		float pitch = 1.12F + (digitIndex * 0.048F);

		if (this.inputMode == InputMode.FREQUENCY) {
			if (this.frequencyBuffer.length() >= RadioStateComponent.MAX_FREQUENCY_LENGTH) {
				rejectKeyPress(keyId);
				return;
			}
			this.frequencyBuffer = this.frequencyBuffer + digit;
			this.animationState.triggerKeyPress(keyId, 16.0F);
			playTactileBeep(pitch, 0.35F);
		} else {
			if (!this.passcodeModified) {
				// Start fresh from scratch without exposing the old PIN
				this.passcodeModified = true;
				this.passcodeBuffer = keyId;
				this.animationState.triggerKeyPress(keyId, 16.0F);
				playTactileBeep(pitch, 0.35F);
				return;
			}
			if (this.passcodeBuffer.length() >= RadioStateComponent.MAX_PASSCODE_LENGTH) {
				rejectKeyPress(keyId);
				return;
			}
			this.passcodeBuffer = this.passcodeBuffer + digit;
			this.animationState.triggerKeyPress(keyId, 16.0F);
			playTactileBeep(pitch, 0.35F);
		}
	}

	private void handleDot() {
		if (this.inputMode != InputMode.FREQUENCY) {
			rejectKeyPress(".");
			return;
		}
		if (this.frequencyBuffer.isEmpty()
				|| this.frequencyBuffer.indexOf('.') >= 0
				|| this.frequencyBuffer.length() >= RadioStateComponent.MAX_FREQUENCY_LENGTH) {
			rejectKeyPress(".");
			return;
		}
		this.frequencyBuffer = this.frequencyBuffer + '.';
		this.animationState.triggerKeyPress(".", 12.0F);
		playTactileBeep(1.62F, 0.32F);
	}

	private void handleDelete() {
		if (this.inputMode == InputMode.FREQUENCY) {
			if (this.frequencyBuffer.isEmpty()) {
				rejectKeyPress("DEL");
				return;
			}
			this.frequencyBuffer = this.frequencyBuffer.substring(0, this.frequencyBuffer.length() - 1);
			this.animationState.triggerKeyPress("DEL", -16.0F);
			playTactileBeep(0.88F, 0.34F);
		} else {
			if (!this.passcodeModified) {
				if (this.hadInitialPasscode) {
					this.passcodeModified = true;
					this.passcodeBuffer = "";
					this.animationState.triggerKeyPress("DEL", -16.0F);
					playTactileBeep(0.88F, 0.34F);
				} else {
					rejectKeyPress("DEL");
				}
				return;
			}
			if (this.passcodeBuffer.isEmpty()) {
				rejectKeyPress("DEL");
				return;
			}
			this.passcodeBuffer = this.passcodeBuffer.substring(0, this.passcodeBuffer.length() - 1);
			this.animationState.triggerKeyPress("DEL", -16.0F);
			playTactileBeep(0.88F, 0.34F);
		}
	}

	private void toggleInputMode() {
		this.inputMode = (this.inputMode == InputMode.FREQUENCY) ? InputMode.PASSCODE : InputMode.FREQUENCY;
		float dialStep = (this.inputMode == InputMode.PASSCODE) ? 28.0F : -28.0F;
		float pitch = (this.inputMode == InputMode.PASSCODE) ? 1.36F : 1.50F;
		this.animationState.triggerKeyPress("MODE", dialStep);
		playTactileBeep(pitch, 0.34F);
	}

	private void handleSubmit() {
		String targetFreq = this.frequencyBuffer;
		String targetPass = this.passcodeModified ? this.passcodeBuffer : this.preservedUnmodifiedFreePasscode;

		if (!RadioStateComponent.isValidFrequency(targetFreq) || !RadioStateComponent.isValidPasscode(targetPass)) {
			this.invalidFlashTicks = 12;
			rejectKeyPress("ENT");
			return;
		}

		this.animationState.triggerKeyPress("ENT", 24.0F);
		playTactileBeep(1.78F, 0.42F);

		if (ClientPlayNetworking.canSend(UpdateHandheldRadioPayload.TYPE)) {
			ClientPlayNetworking.send(new UpdateHandheldRadioPayload(targetFreq, targetPass, this.hand));
		}
		onClose();
	}

	private void rejectKeyPress(String keyId) {
		this.animationState.triggerKeyPress(keyId, 0.0F);
		this.invalidFlashTicks = Math.max(this.invalidFlashTicks, 8);
		playTactileBeep(0.55F, 0.30F);
	}

	private void playTactileBeep(float pitch, float volume) {
		SoundManager soundManager = Minecraft.getInstance().getSoundManager();
		soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT.value(), pitch, volume));
		soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.35F, 0.12F));
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		int cp = event.codepoint();
		if (cp >= '0' && cp <= '9') {
			handleDigit((char) cp);
			return true;
		}
		if (cp == '.' || cp == ',') {
			handleDot();
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == InputConstants.KEY_BACKSPACE || key == InputConstants.KEY_DELETE) {
			handleDelete();
			return true;
		}
		if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
			handleSubmit();
			return true;
		}
		if (key == InputConstants.KEY_TAB) {
			toggleInputMode();
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && shouldCloseOnEsc()) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		// Light translucent inspection vignette — keeps the live world clearly visible in the background
		graphics.fillGradient(0, 0, this.width, this.height, 0x24070B09, 0x5A050807);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (this.layout == null) {
			this.layout = HandheldRadioModelRenderer.computeLayout(this.width, this.height);
		}
		this.animationState.updateFrame();

		HandheldRadioModelRenderer.extractModel(graphics, this.layout, this.isPowered, this.animationState);
		extractDigitalLcd(graphics, this.layout);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		int hintY = Math.min(this.height - 14, this.layout.bodyTop() + this.layout.bodyHeight() + 10);
		graphics.centeredText(
				this.font,
				Component.translatable("gui.tlouradio.handheld.hint_controls"),
				this.layout.centerX(),
				hintY,
				0xA8C6D0CA
		);
	}

	private void extractDigitalLcd(GuiGraphicsExtractor graphics, HandheldRadioModelRenderer.DeviceLayout d) {
		int x = d.lcdX();
		int y = d.lcdY();
		int w = d.lcdWidth();
		int h = d.lcdHeight();

		int lcdBg = this.isPowered ? 0xF01C2B20 : 0xF0151A17;
		int lcdBorder = (this.invalidFlashTicks > 0) ? 0xFFD9534F : (this.isPowered ? 0xFF4F7A5B : 0xFF333D36);
		graphics.fill(x, y, x + w, y + h, lcdBg);
		graphics.outline(x, y, w, h, lcdBorder);

		// Subtle LCD horizontal scanline divider
		int dividerY = y + 30;
		graphics.horizontalLine(x + 3, x + w - 4, dividerY, this.isPowered ? 0x663B5E45 : 0x5529312B);

		boolean effectiveHasPin = this.passcodeModified ? !this.passcodeBuffer.isEmpty() : this.hadInitialPasscode;

		Component pwrText = Component.translatable(
				this.isPowered ? "gui.tlouradio.handheld.lcd_power_on" : "gui.tlouradio.handheld.lcd_power_off"
		);
		Component netText = this.activeNetworkId.isPresent()
				? Component.translatable(
						"gui.tlouradio.handheld.lcd_net_paired",
						RadioStateComponent.shortNetworkId(this.activeNetworkId.get()).substring(0, 4)
				)
				: Component.translatable("gui.tlouradio.handheld.lcd_net_open");

		Component pinStatusText = Component.translatable(
				effectiveHasPin ? "gui.tlouradio.handheld.lcd_pin_set" : "gui.tlouradio.handheld.lcd_pin_none"
		);

		int primaryInk = this.isPowered ? 0xFF9BF0AE : 0xFF6B7D70;
		int secondaryInk = this.isPowered ? 0xFF74C488 : 0xFF556359;
		int pwrInk = this.isPowered ? 0xFF66EE88 : 0xFFC85A5A;

		// Scaled micro-LCD text (0.75x) so all tactical telemetry fits cleanly inside the LCD bezel
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + 4, y + 4);
		graphics.pose().scale(0.72F, 0.72F);
		graphics.text(this.font, pwrText, 0, 0, pwrInk, false);
		int netTextWidth = this.font.width(netText);
		int scaledInnerWidth = (int) ((w - 8) / 0.72F);
		graphics.text(this.font, netText, Math.max(0, scaledInnerWidth - netTextWidth), 0, secondaryInk, false);
		graphics.pose().popMatrix();

		// Line 2: Active Frequency + Masked PIN indicator
		String savedFreqLabel = this.activeSavedFrequency + " MHz";
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + 4, y + 15);
		graphics.pose().scale(0.80F, 0.80F);
		graphics.text(this.font, savedFreqLabel, 0, 0, primaryInk, false);
		int pinWidth = this.font.width(pinStatusText);
		int scaledRow2Width = (int) ((w - 8) / 0.80F);
		graphics.text(this.font, pinStatusText, Math.max(0, scaledRow2Width - pinWidth), 0, secondaryInk, false);
		graphics.pose().popMatrix();

		// Line 3: Live keypad entry buffer (FRQ> or PIN>)
		boolean blinkCursor = (System.currentTimeMillis() / 450L) % 2L == 0L;
		String cursor = blinkCursor ? "_" : "";
		String bufferLine;
		int bufferColor;

		if (this.inputMode == InputMode.FREQUENCY) {
			boolean freqValid = RadioStateComponent.isValidFrequency(this.frequencyBuffer);
			bufferLine = "FRQ> " + this.frequencyBuffer + cursor;
			bufferColor = (this.invalidFlashTicks > 0 || !freqValid) ? 0xFFF0B45A : 0xFFB8FFD4;
		} else {
			if (!this.passcodeModified) {
				bufferLine = this.hadInitialPasscode ? "PIN> [****]" : ("PIN> " + cursor);
				bufferColor = 0xFF8CD8EE;
			} else if (this.passcodeBuffer.isEmpty()) {
				bufferLine = "PIN> " + cursor;
				bufferColor = 0xFF8CD8EE;
			} else {
				bufferLine = "PIN> " + this.passcodeBuffer + cursor;
				bufferColor = 0xFFAEEBFF;
			}
		}

		graphics.pose().pushMatrix();
		graphics.pose().translate(x + 4, dividerY + 4);
		graphics.pose().scale(0.82F, 0.82F);
		graphics.text(this.font, bufferLine, 0, 0, bufferColor, false);
		graphics.pose().popMatrix();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private enum KeyRole {
		DIGIT,
		DOT,
		MODE,
		DELETE,
		ENTER
	}

	private final class RadioKeypadButton extends AbstractButton {
		private final String keyId;
		private final KeyRole role;
		private final Runnable onTrigger;

		private RadioKeypadButton(
				String keyId,
				int x,
				int y,
				int width,
				int height,
				Component message,
				KeyRole role,
				Runnable onTrigger
		) {
			super(x, y, width, height, message);
			this.keyId = keyId;
			this.role = role;
			this.onTrigger = onTrigger;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			this.onTrigger.run();
		}

		@Override
		public void playDownSound(SoundManager soundManager) {
			// Tactile per-key pitch audio is handled centrally in HandheldRadioScreen
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			float depression = HandheldRadioScreen.this.animationState.getKeyDepressionFor(this.keyId);
			boolean depressed = depression > 0.05F;
			int pressOffsetY = depressed ? 1 : 0;

			boolean dotDisabled = (this.role == KeyRole.DOT && HandheldRadioScreen.this.inputMode == InputMode.PASSCODE);

			int x = getX();
			int y = getY() + pressOffsetY;
			int w = getWidth();
			int h = getHeight() - pressOffsetY;

			int bgColor = resolveBackgroundColor(depressed, isHovered(), dotDisabled);
			int borderColor = resolveBorderColor(depressed, isHovered(), dotDisabled);
			int labelColor = dotDisabled ? 0xFF666E69 : (depressed ? 0xFFE8FFF0 : 0xFFE2E8E4);

			if (!depressed) {
				// Bottom 3D tactile keycap shadow
				graphics.fill(x, getY() + getHeight() - 2, x + w, getY() + getHeight(), 0xCC0A0D0B);
			}

			graphics.fill(x, y, x + w, y + h - (depressed ? 0 : 1), bgColor);
			graphics.outline(x, y, w, h - (depressed ? 0 : 1), borderColor);

			Component label = getMessage();
			int textW = HandheldRadioScreen.this.font.width(label);
			float maxInnerW = w - 4.0F;
			if (textW > maxInnerW && textW > 0) {
				float scale = maxInnerW / (float) textW;
				graphics.pose().pushMatrix();
				graphics.pose().translate(x + w / 2.0F, y + (h - 1) / 2.0F - (4.0F * scale));
				graphics.pose().scale(scale, scale);
				graphics.text(HandheldRadioScreen.this.font, label, -textW / 2, 0, labelColor, false);
				graphics.pose().popMatrix();
			} else {
				graphics.centeredText(
						HandheldRadioScreen.this.font,
						label,
						x + w / 2,
						y + (h - 9) / 2,
						labelColor
				);
			}
		}

		private int resolveBackgroundColor(boolean depressed, boolean hovered, boolean disabled) {
			if (disabled) {
				return 0xD81B201D;
			}
			return switch (this.role) {
				case ENTER -> depressed ? 0xF02E6E46 : (hovered ? 0xF02A5C3D : 0xE8214830);
				case DELETE -> depressed ? 0xF06B342E : (hovered ? 0xF0582B26 : 0xE844221F);
				case MODE -> {
					boolean pinActive = HandheldRadioScreen.this.inputMode == InputMode.PASSCODE;
					if (depressed) {
						yield 0xF0355B73;
					}
					yield pinActive
							? (hovered ? 0xF02D5066 : 0xE8244052)
							: (hovered ? 0xF038453E : 0xE82B3630);
				}
				case DIGIT, DOT -> depressed ? 0xF03C4C43 : (hovered ? 0xF0334039 : 0xE8252E29);
			};
		}

		private int resolveBorderColor(boolean depressed, boolean hovered, boolean disabled) {
			if (disabled) {
				return 0xFF2B322E;
			}
			if (depressed) {
				return 0xFF8BE8A8;
			}
			if (hovered) {
				return 0xFF728C7C;
			}
			return switch (this.role) {
				case ENTER -> 0xFF3E7A55;
				case DELETE -> 0xFF6E3B35;
				case MODE -> HandheldRadioScreen.this.inputMode == InputMode.PASSCODE ? 0xFF4C82A3 : 0xFF48584F;
				case DIGIT, DOT -> 0xFF3F4D45;
			};
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
			defaultButtonNarrationText(narrationElementOutput);
		}
	}
}
