package com.tlouradio.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.tlouradio.client.gui.render.BaseBoxModelRenderer;
import com.tlouradio.client.gui.render.HandheldRadioModelRenderer;
import com.tlouradio.client.renderer.HandheldRadioGeoItemRenderer;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.network.payload.UpdateBaseBoxConfigPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.UUID;

/**
 * 3D Field Station inspection & configuration screen for {@code RadioBaseBoxBlock}.
 */
public class RadioBaseBoxScreen extends Screen {
	private static final double DIAL_PIXELS_PER_DETENT = 10.0D;

	private enum InputMode {
		FREQUENCY,
		PASSCODE
	}

	private enum DeckRole {
		DIGIT,
		MODE,
		DELETE,
		SAVE,
		CANCEL
	}

	private final BlockPos blockPos;
	private final UUID networkId;
	private final boolean hadInitialPasscode;
	private final String initialPasscode;

	private InputMode inputMode = InputMode.FREQUENCY;
	private String frequencyBuffer;
	private String passcodeBuffer = "";
	private boolean passcodeModified = false;
	private int invalidFlashTicks = 0;

	private boolean draggingMainDial = false;
	private double dialDragResidualPx = 0.0D;
	private float dialAngleDeg;
	private float targetDialAngleDeg;
	private String depressedKeyId = "";
	private float keyPressDepth = 0.0F;
	private long lastAnimNanos = System.nanoTime();

	private final RadioInspectionTransition transition = new RadioInspectionTransition();
	private BaseBoxModelRenderer.StationLayout layout;

	public RadioBaseBoxScreen(BlockPos blockPos, UUID networkId, String initialFrequency, String initialPasscode) {
		super(Component.translatable("gui.tlouradio.base_box.title"));
		this.blockPos = blockPos;
		this.networkId = networkId;
		this.frequencyBuffer = initialFrequency;
		this.initialPasscode = initialPasscode != null ? initialPasscode : "";
		this.hadInitialPasscode = !this.initialPasscode.isEmpty();

		float initDeg = HandheldRadioGeoItemRenderer.computeDialAngleRad(initialFrequency) * Mth.RAD_TO_DEG;
		this.dialAngleDeg = initDeg;
		this.targetDialAngleDeg = initDeg;
	}

	@Override
	protected void init() {
		this.layout = BaseBoxModelRenderer.computeLayout(this.width, this.height);
		buildTacticalControlDeck(this.layout);
	}

	private void buildTacticalControlDeck(BaseBoxModelRenderer.StationLayout d) {
		int padX = d.deckX() + 6;
		int padY = d.deckY() + 6;

		int keyW = 24;
		int funcW = 48;
		int rowH = 19;
		int gapX = 3;
		int gapY = 3;

		int c0 = padX;
		int c1 = c0 + keyW + gapX;
		int c2 = c1 + keyW + gapX;
		int c3 = c2 + keyW + gapX;
		int c4 = c3 + keyW + gapX;

		int r0 = padY;
		int r1 = r0 + rowH + gapY;
		int r2 = r1 + rowH + gapY;
		int r3 = r2 + rowH + gapY;

		// Compact military field-console keypad (4x3 + right function bank)
		addDeckKey("1", Component.literal("1"), c0, r0, keyW, rowH, DeckRole.DIGIT, () -> handleDigit('1'));
		addDeckKey("2", Component.literal("2"), c1, r0, keyW, rowH, DeckRole.DIGIT, () -> handleDigit('2'));
		addDeckKey("3", Component.literal("3"), c2, r0, keyW, rowH, DeckRole.DIGIT, () -> handleDigit('3'));
		addDeckKey("4", Component.literal("4"), c3, r0, keyW, rowH, DeckRole.DIGIT, () -> handleDigit('4'));
		addDeckKey("MODE", Component.translatable("gui.tlouradio.handheld.key_mode"), c4, r0, funcW, rowH, DeckRole.MODE, this::toggleInputMode);

		addDeckKey("5", Component.literal("5"), c0, r1, keyW, rowH, DeckRole.DIGIT, () -> handleDigit('5'));
		addDeckKey("6", Component.literal("6"), c1, r1, keyW, rowH, DeckRole.DIGIT, () -> handleDigit('6'));
		addDeckKey("7", Component.literal("7"), c2, r1, keyW, rowH, DeckRole.DIGIT, () -> handleDigit('7'));
		addDeckKey("8", Component.literal("8"), c3, r1, keyW, rowH, DeckRole.DIGIT, () -> handleDigit('8'));
		addDeckKey("DEL", Component.translatable("gui.tlouradio.handheld.key_delete"), c4, r1, funcW, rowH, DeckRole.DELETE, this::handleDelete);

		addDeckKey("9", Component.literal("9"), c0, r2, keyW, rowH, DeckRole.DIGIT, () -> handleDigit('9'));
		addDeckKey("0", Component.literal("0"), c1, r2, keyW * 2 + gapX, rowH, DeckRole.DIGIT, () -> handleDigit('0'));
		addDeckKey(".", Component.literal("."), c3, r2, keyW, rowH, DeckRole.DIGIT, this::handleDot);
		addDeckKey("SAVE", Component.translatable("gui.tlouradio.base_box.save"), c4, r2, funcW, rowH, DeckRole.SAVE, this::submitAndClose);

		addDeckKey("ESC", Component.translatable("gui.tlouradio.base_box.cancel"), c0, r3, d.deckWidth() - 12, 17, DeckRole.CANCEL, this::initiateSmoothClose);
	}

	private void addDeckKey(
			String keyId,
			Component label,
			int x,
			int y,
			int w,
			int h,
			DeckRole role,
			Runnable action
	) {
		addRenderableWidget(new TacticalDeckButton(keyId, x, y, w, h, label, role, action));
	}

	@Override
	public void tick() {
		super.tick();
		if (this.invalidFlashTicks > 0) {
			this.invalidFlashTicks--;
		}
		if (this.minecraft.player == null
				|| !this.minecraft.player.isAlive()
				|| this.minecraft.player.isSpectator()
				|| !this.minecraft.player.isWithinBlockInteractionRange(this.blockPos, 4.5D)) {
			RadioInspectionTransition.resetGlobalFovImmediately();
			super.onClose();
		}
	}

	private void initiateSmoothClose() {
		if (!this.transition.isClosing()) {
			playMechanicalClick(0.92F, 0.16F);
			this.transition.startClosing();
		}
	}

	@Override
	public void onClose() {
		initiateSmoothClose();
	}

	@Override
	public void removed() {
		RadioInspectionTransition.resetGlobalFovImmediately();
		super.removed();
	}

	private HandheldRadioModelRenderer.ProjectedBounds getMainDialHitbox() {
		return BaseBoxModelRenderer.projectGeoBoxToScreen(
				this.layout, 2.0F, 1.3F, -7.0F, 4.8F, 4.1F, -6.0F
		);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.transition.isClosing()) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT
				&& this.layout != null
				&& getMainDialHitbox().contains(event.x(), event.y())) {
			this.draggingMainDial = true;
			this.dialDragResidualPx = 0.0D;
			playMechanicalClick(1.45F, 0.22F);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.transition.isClosing()) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.draggingMainDial) {
			this.dialDragResidualPx += dragX;
			int detents = (int) (this.dialDragResidualPx / DIAL_PIXELS_PER_DETENT);
			if (detents != 0) {
				this.dialDragResidualPx -= detents * DIAL_PIXELS_PER_DETENT;
				int tenths = event.hasShiftDown() ? 1 : 10;
				stepFrequencyByDial(detents * tenths, detents);
			}
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.transition.isClosing()) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.draggingMainDial) {
			this.draggingMainDial = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.transition.isClosing()) {
			return true;
		}
		if (scrollY != 0.0D && this.layout != null && getMainDialHitbox().contains(mouseX, mouseY)) {
			int detents = scrollY > 0.0D ? 1 : -1;
			int tenths = Minecraft.getInstance().hasShiftDown() ? 1 : 10;
			stepFrequencyByDial(detents * tenths, detents);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private void stepFrequencyByDial(int deltaTenths, int detents) {
		String base = RadioStateComponent.isValidFrequency(this.frequencyBuffer)
				? this.frequencyBuffer
				: RadioStateComponent.DEFAULT_FREQUENCY;
		String next = RadioStateComponent.stepFrequencyTenths(base, deltaTenths);
		this.frequencyBuffer = next;
		this.inputMode = InputMode.FREQUENCY;
		this.targetDialAngleDeg = Mth.wrapDegrees(this.targetDialAngleDeg + detents * 18.0F);
		playMechanicalClick(deltaTenths > 0 ? 1.60F : 1.36F, 0.28F);
	}

	private void handleDigit(char digit) {
		String keyId = String.valueOf(digit);
		float pitch = 1.12F + ((digit - '0') * 0.048F);

		if (this.inputMode == InputMode.FREQUENCY) {
			if (this.frequencyBuffer.length() >= RadioStateComponent.MAX_FREQUENCY_LENGTH) {
				rejectKey(keyId);
				return;
			}
			this.frequencyBuffer += digit;
			triggerDeckKeyAnim(keyId, 12.0F);
			playTactileBeep(pitch, 0.34F);
		} else {
			if (!this.passcodeModified) {
				this.passcodeModified = true;
				this.passcodeBuffer = keyId;
				triggerDeckKeyAnim(keyId, 12.0F);
				playTactileBeep(pitch, 0.34F);
				return;
			}
			if (this.passcodeBuffer.length() >= RadioStateComponent.MAX_PASSCODE_LENGTH) {
				rejectKey(keyId);
				return;
			}
			this.passcodeBuffer += digit;
			triggerDeckKeyAnim(keyId, 12.0F);
			playTactileBeep(pitch, 0.34F);
		}
	}

	private void handleDot() {
		if (this.inputMode != InputMode.FREQUENCY
				|| this.frequencyBuffer.isEmpty()
				|| this.frequencyBuffer.indexOf('.') >= 0
				|| this.frequencyBuffer.length() >= RadioStateComponent.MAX_FREQUENCY_LENGTH) {
			rejectKey(".");
			return;
		}
		this.frequencyBuffer += '.';
		triggerDeckKeyAnim(".", 10.0F);
		playTactileBeep(1.62F, 0.32F);
	}

	private void handleDelete() {
		if (this.inputMode == InputMode.FREQUENCY) {
			if (this.frequencyBuffer.isEmpty()) {
				rejectKey("DEL");
				return;
			}
			this.frequencyBuffer = this.frequencyBuffer.substring(0, this.frequencyBuffer.length() - 1);
			triggerDeckKeyAnim("DEL", -12.0F);
			playTactileBeep(0.88F, 0.34F);
		} else {
			if (!this.passcodeModified) {
				if (this.hadInitialPasscode) {
					this.passcodeModified = true;
					this.passcodeBuffer = "";
					triggerDeckKeyAnim("DEL", -12.0F);
					playTactileBeep(0.88F, 0.34F);
				} else {
					rejectKey("DEL");
				}
				return;
			}
			if (this.passcodeBuffer.isEmpty()) {
				rejectKey("DEL");
				return;
			}
			this.passcodeBuffer = this.passcodeBuffer.substring(0, this.passcodeBuffer.length() - 1);
			triggerDeckKeyAnim("DEL", -12.0F);
			playTactileBeep(0.88F, 0.34F);
		}
	}

	private void toggleInputMode() {
		this.inputMode = (this.inputMode == InputMode.FREQUENCY) ? InputMode.PASSCODE : InputMode.FREQUENCY;
		triggerDeckKeyAnim("MODE", this.inputMode == InputMode.PASSCODE ? 22.0F : -22.0F);
		playTactileBeep(this.inputMode == InputMode.PASSCODE ? 1.36F : 1.50F, 0.34F);
	}

	private void submitAndClose() {
		String finalFreq = this.frequencyBuffer;
		String finalPass = this.passcodeModified ? this.passcodeBuffer : this.initialPasscode;
		if (!RadioStateComponent.isValidFrequency(finalFreq) || !RadioStateComponent.isValidPasscode(finalPass)) {
			this.invalidFlashTicks = 12;
			rejectKey("SAVE");
			return;
		}
		triggerDeckKeyAnim("SAVE", 20.0F);
		playTactileBeep(1.78F, 0.42F);
		if (ClientPlayNetworking.canSend(UpdateBaseBoxConfigPayload.TYPE)) {
			ClientPlayNetworking.send(new UpdateBaseBoxConfigPayload(this.blockPos, finalFreq, finalPass));
		}
		initiateSmoothClose();
	}

	private void rejectKey(String keyId) {
		triggerDeckKeyAnim(keyId, 0.0F);
		this.invalidFlashTicks = Math.max(this.invalidFlashTicks, 8);
		playTactileBeep(0.55F, 0.30F);
	}

	private void triggerDeckKeyAnim(String keyId, float dialStep) {
		this.depressedKeyId = keyId;
		this.keyPressDepth = 1.0F;
	}

	private void updateLocalAnimations() {
		long now = System.nanoTime();
		float dt = Mth.clamp((now - this.lastAnimNanos) / 1_000_000_000.0F, 0.0F, 0.1F);
		this.lastAnimNanos = now;

		if (this.keyPressDepth > 0.0F) {
			this.keyPressDepth = Math.max(0.0F, this.keyPressDepth - dt * 7.5F);
			if (this.keyPressDepth == 0.0F) {
				this.depressedKeyId = "";
			}
		}

		float delta = Mth.wrapDegrees(this.targetDialAngleDeg - this.dialAngleDeg);
		if (Math.abs(delta) > 0.1F) {
			this.dialAngleDeg = Mth.wrapDegrees(this.dialAngleDeg + delta * Math.min(1.0F, dt * 18.0F));
		} else {
			this.dialAngleDeg = this.targetDialAngleDeg;
		}
	}

	private void playTactileBeep(float pitch, float volume) {
		SoundManager sm = Minecraft.getInstance().getSoundManager();
		sm.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT.value(), pitch, volume));
		sm.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.35F, 0.12F));
	}

	private void playMechanicalClick(float pitch, float volume) {
		Minecraft.getInstance().getSoundManager()
				.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, volume));
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (this.transition.isClosing()) {
			return true;
		}
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
		if (this.transition.isClosing()) {
			return true;
		}
		int key = event.key();
		if (key == InputConstants.KEY_BACKSPACE || key == InputConstants.KEY_DELETE) {
			handleDelete();
			return true;
		}
		if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
			submitAndClose();
			return true;
		}
		if (key == InputConstants.KEY_TAB) {
			toggleInputMode();
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE && shouldCloseOnEsc()) {
			initiateSmoothClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		float focus = this.transition.easedProgress();
		if (focus > 0.35F) {
			graphics.blurBeforeThisStratum();
		}
		int topAlpha = Math.round(0x2A * focus);
		int botAlpha = Math.round(0x68 * focus);
		graphics.fillGradient(
				0, 0, this.width, this.height,
				(topAlpha << 24) | 0x070B09,
				(botAlpha << 24) | 0x050807
		);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		this.transition.updateFrame();
		if (this.transition.isCloseCompleted()) {
			RadioInspectionTransition.resetGlobalFovImmediately();
			super.onClose();
			return;
		}

		if (this.layout == null) {
			this.layout = BaseBoxModelRenderer.computeLayout(this.width, this.height);
		}
		updateLocalAnimations();

		float focus = this.transition.easedProgress();
		BaseBoxModelRenderer.PanelReadout readout = buildPanelReadout();

		// 1. Render 3D GeckoLib Radio Base Box with emissive LEDs + 3D surface LCD readout
		BaseBoxModelRenderer.extractModel(graphics, this.layout, this.dialAngleDeg, focus, readout);

		// 2. Render lower tactical military keypad housing
		int dx = this.layout.deckX();
		int dy = this.layout.deckY();
		int dw = this.layout.deckWidth();
		int dh = this.layout.deckHeight();
		graphics.fill(dx, dy, dx + dw, dy + dh, 0xDF141916);
		graphics.outline(dx, dy, dw, dh, 0xFF46564B);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		if (focus > 0.65F && !this.transition.isClosing()) {
			HandheldRadioModelRenderer.ProjectedBounds dialBox = getMainDialHitbox();
			if (this.draggingMainDial || dialBox.contains(mouseX, mouseY)) {
				HandheldRadioModelRenderer.extractDialHoverArrows(graphics, dialBox);
			}

			int alpha = Math.round(0xB4 * ((focus - 0.65F) / 0.35F));
			int hintY = Math.min(this.height - 11, dy + dh + 6);
			Component hint = Component.translatable("gui.tlouradio.base_box.hint_controls");
			int tw = this.font.width(hint);
			float scale = 0.85F;
			graphics.pose().pushMatrix();
			graphics.pose().translate(this.layout.centerX(), hintY);
			graphics.pose().scale(scale, scale);
			graphics.text(this.font, hint, -tw / 2, 0, (alpha << 24) | 0xC6D0CA, true);
			graphics.pose().popMatrix();
		}
	}

	private BaseBoxModelRenderer.PanelReadout buildPanelReadout() {
		String netLabel = Component.translatable(
				"gui.tlouradio.base_box.lcd_net_id",
				RadioStateComponent.shortNetworkId(this.networkId)
		).getString();
		boolean hasPin = this.passcodeModified ? !this.passcodeBuffer.isEmpty() : this.hadInitialPasscode;
		String pinStatus = Component.translatable(
				hasPin ? "gui.tlouradio.handheld.lcd_pin_set" : "gui.tlouradio.handheld.lcd_pin_none"
		).getString();

		boolean blink = (System.currentTimeMillis() / 450L) % 2L == 0L;
		String cursor = blink ? "_" : "";
		String liveBuf;
		int bufColor;
		if (this.inputMode == InputMode.FREQUENCY) {
			boolean valid = RadioStateComponent.isValidFrequency(this.frequencyBuffer);
			liveBuf = Component.translatable(
					"gui.tlouradio.handheld.lcd_buf_freq",
					this.frequencyBuffer + cursor
			).getString();
			bufColor = (this.invalidFlashTicks > 0 || !valid) ? 0xFFF0B45A : 0xFFB8FFD4;
		} else if (!this.passcodeModified) {
			liveBuf = this.hadInitialPasscode
					? Component.translatable("gui.tlouradio.handheld.lcd_buf_pin_masked").getString()
					: Component.translatable("gui.tlouradio.handheld.lcd_buf_pin", cursor).getString();
			bufColor = 0xFF8CD8EE;
		} else {
			liveBuf = Component.translatable(
					"gui.tlouradio.handheld.lcd_buf_pin",
					this.passcodeBuffer + cursor
			).getString();
			bufColor = 0xFFAEEBFF;
		}

		String freqLabel = Component.translatable(
				"gui.tlouradio.handheld.lcd_freq_unit",
				this.frequencyBuffer
		).getString();

		return new BaseBoxModelRenderer.PanelReadout(
				netLabel,
				freqLabel,
				pinStatus,
				liveBuf,
				bufColor
		);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private final class TacticalDeckButton extends AbstractButton {
		private final String keyId;
		private final DeckRole role;
		private final Runnable action;

		private TacticalDeckButton(
				String keyId,
				int x,
				int y,
				int width,
				int height,
				Component message,
				DeckRole role,
				Runnable action
		) {
			super(x, y, width, height, message);
			this.keyId = keyId;
			this.role = role;
			this.action = action;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			if (!RadioBaseBoxScreen.this.transition.isClosing()) {
				this.action.run();
			}
		}

		@Override
		public void playDownSound(SoundManager soundManager) {
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			boolean depressed = this.keyId.equals(RadioBaseBoxScreen.this.depressedKeyId)
					&& RadioBaseBoxScreen.this.keyPressDepth > 0.05F;
			boolean dotDisabled = ".".equals(this.keyId) && RadioBaseBoxScreen.this.inputMode == InputMode.PASSCODE;

			int offset = depressed ? 1 : 0;
			int x = getX();
			int y = getY() + offset;
			int w = getWidth();
			int h = getHeight() - offset;

			if (!depressed) {
				graphics.fill(x, getY() + getHeight() - 2, x + w, getY() + getHeight(), 0xCC090C0A);
			}

			int bg = resolveBg(depressed, isHovered(), dotDisabled);
			int border = depressed ? 0xFF8BE8A8 : (isHovered() && !dotDisabled ? 0xFF7CA088 : 0xFF3E4C43);
			int textCol = dotDisabled ? 0xFF666E69 : 0xFFE6ECE8;

			graphics.fill(x, y, x + w, y + h - (depressed ? 0 : 1), bg);
			graphics.outline(x, y, w, h - (depressed ? 0 : 1), border);

			Component label = getMessage();
			int tw = RadioBaseBoxScreen.this.font.width(label);
			float maxW = w - 4.0F;
			if (tw > maxW && tw > 0) {
				float s = maxW / (float) tw;
				graphics.pose().pushMatrix();
				graphics.pose().translate(x + w / 2.0F, y + (h - 1) / 2.0F - 4.0F * s);
				graphics.pose().scale(s, s);
				graphics.text(RadioBaseBoxScreen.this.font, label, -tw / 2, 0, textCol, true);
				graphics.pose().popMatrix();
			} else {
				graphics.centeredText(RadioBaseBoxScreen.this.font, label, x + w / 2, y + (h - 9) / 2, textCol);
			}
		}

		private int resolveBg(boolean depressed, boolean hovered, boolean disabled) {
			if (disabled) return 0xD81B201D;
			return switch (this.role) {
				case SAVE -> depressed ? 0xF02E6E46 : (hovered ? 0xF02A5C3D : 0xE8214830);
				case DELETE -> depressed ? 0xF06B342E : (hovered ? 0xF0582B26 : 0xE844221F);
				case MODE -> RadioBaseBoxScreen.this.inputMode == InputMode.PASSCODE
						? (hovered ? 0xF02D5066 : 0xE8244052)
						: (hovered ? 0xF038453E : 0xE82B3630);
				case CANCEL -> hovered ? 0xF0323834 : 0xE8222824;
				case DIGIT -> depressed ? 0xF03C4C43 : (hovered ? 0xF0334039 : 0xE826302A);
			};
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
			defaultButtonNarrationText(narrationElementOutput);
		}
	}
}
