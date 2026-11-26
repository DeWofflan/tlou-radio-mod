package com.tlouradio.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.tlouradio.client.gui.render.BaseBoxModelRenderer;
import com.tlouradio.client.gui.render.HandheldRadioModelRenderer;
import com.tlouradio.client.renderer.HandheldRadioGeoItemRenderer;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.network.payload.UpdateBaseBoxConfigPayload;
import com.tlouradio.radio.RadioBaseChannel;
import com.tlouradio.radio.RadioBaseNetworkSavedData;
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
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 3D Field Station inspection & multi-channel configuration screen for {@code RadioBaseBoxBlock}.
 * Supports up to 8 (frequency, passcode) channels per Base Box, inline conflict feedback without closing,
 * and clickable 3D front-panel channel prev/next buttons.
 */
public class RadioBaseBoxScreen extends Screen {
	private static final double DIAL_PIXELS_PER_DETENT = 10.0D;

	private enum InputMode {
		FREQUENCY,
		PASSCODE
	}

	private enum DeckRole {
		CHANNEL_SLOT,
		CHANNEL_ADD,
		CHANNEL_REMOVE,
		DIGIT,
		MODE,
		DELETE,
		SAVE,
		CANCEL
	}

	private static final class EditableChannel {
		String frequency;
		String passcode;
		boolean hadInitialPasscode;
		boolean passcodeModified;

		EditableChannel(String frequency, String passcode, boolean hadInitialPasscode) {
			this.frequency = frequency;
			this.passcode = passcode != null ? passcode : "";
			this.hadInitialPasscode = hadInitialPasscode;
			this.passcodeModified = !hadInitialPasscode;
		}
	}

	private final BlockPos blockPos;
	private final UUID networkId;
	private final List<EditableChannel> channels = new ArrayList<>();
	private int selectedChannelIndex;

	private InputMode inputMode = InputMode.FREQUENCY;
	private int invalidFlashTicks = 0;

	private boolean awaitingSaveResult = false;
	private Component inlineWarningMessage = null;
	private int inlineWarningTicks = 0;

	private boolean draggingMainDial = false;
	private double dialDragResidualPx = 0.0D;
	private float dialAngleDeg;
	private float targetDialAngleDeg;
	private String depressedKeyId = "";
	private float keyPressDepth = 0.0F;
	private long lastAnimNanos = System.nanoTime();

	private final RadioInspectionTransition transition = new RadioInspectionTransition();
	private BaseBoxModelRenderer.StationLayout layout;

	public RadioBaseBoxScreen(
			BlockPos blockPos,
			UUID networkId,
			List<RadioBaseChannel> initialChannels,
			int initialSelectedIndex
	) {
		super(Component.translatable("gui.tlouradio.base_box.title"));
		this.blockPos = blockPos;
		this.networkId = networkId;

		if (initialChannels != null && !initialChannels.isEmpty()) {
			for (RadioBaseChannel ch : initialChannels) {
				if (this.channels.size() >= RadioBaseNetworkSavedData.MAX_CHANNELS_PER_BOX) {
					break;
				}
				String pass = ch.passcode() != null ? ch.passcode() : "";
				this.channels.add(new EditableChannel(ch.frequency(), pass, !pass.isEmpty()));
			}
		}
		if (this.channels.isEmpty()) {
			this.channels.add(new EditableChannel(RadioStateComponent.DEFAULT_FREQUENCY, "", false));
		}
		this.selectedChannelIndex = Mth.clamp(initialSelectedIndex, 0, this.channels.size() - 1);

		float initDeg = HandheldRadioGeoItemRenderer.computeDialAngleRad(currentChannel().frequency) * Mth.RAD_TO_DEG;
		this.dialAngleDeg = initDeg;
		this.targetDialAngleDeg = initDeg;
	}

	private EditableChannel currentChannel() {
		return this.channels.get(this.selectedChannelIndex);
	}

	/**
	 * Called when the server responds with {@code BaseBoxSaveResultPayload}.
	 */
	public void handleServerSaveResult(boolean success, Component message) {
		this.awaitingSaveResult = false;
		if (success) {
			this.inlineWarningMessage = null;
			this.inlineWarningTicks = 0;
			initiateSmoothClose();
		} else {
			showInlineWarning(message);
		}
	}

	private void showInlineWarning(Component message) {
		this.inlineWarningMessage = message;
		this.inlineWarningTicks = 100; // ~5 seconds
		this.invalidFlashTicks = 16;
		playTactileBeep(0.55F, 0.34F);
	}

	@Override
	protected void init() {
		this.layout = BaseBoxModelRenderer.computeLayout(this.width, this.height);
		buildTacticalControlDeck(this.layout);
	}

	private void buildTacticalControlDeck(BaseBoxModelRenderer.StationLayout d) {
		int padX = d.deckX() + 7;
		int padY = d.deckY() + 7;
		int innerW = d.deckWidth() - 14;

		// Row A: Compact channel selector slots 1..8
		int slotGap = 2;
		int slotW = (innerW - (slotGap * 7)) / 8;
		int slotH = 17;
		for (int i = 0; i < RadioBaseNetworkSavedData.MAX_CHANNELS_PER_BOX; i++) {
			final int slotIdx = i;
			int sx = padX + i * (slotW + slotGap);
			addDeckKey(
					"CH_" + slotIdx,
					Component.literal(String.valueOf(slotIdx + 1)),
					sx,
					padY,
					slotW,
					slotH,
					DeckRole.CHANNEL_SLOT,
					slotIdx,
					() -> selectChannel(slotIdx)
			);
		}

		// Row B: [+ Kanal Ekle] [Kaydet (icon)] [Kanal Sil]
		int actionY = padY + slotH + 4;
		int actionH = 18;
		int gapAct = 3;
		int addW = 76;
		int saveW = 66;
		int remW = innerW - addW - saveW - (gapAct * 2);

		addDeckKey(
				"CH_ADD",
				Component.translatable("gui.tlouradio.base_box.channel_add"),
				padX,
				actionY,
				addW,
				actionH,
				DeckRole.CHANNEL_ADD,
				-1,
				this::addChannel
		);
		addDeckKey(
				"SAVE",
				Component.translatable("gui.tlouradio.base_box.save"),
				padX + addW + gapAct,
				actionY,
				saveW,
				actionH,
				DeckRole.SAVE,
				-1,
				this::submitSaveRequest
		);
		addDeckKey(
				"CH_REM",
				Component.translatable("gui.tlouradio.base_box.channel_remove"),
				padX + addW + gapAct + saveW + gapAct,
				actionY,
				remW,
				actionH,
				DeckRole.CHANNEL_REMOVE,
				-1,
				this::removeSelectedChannel
		);

		// Keypad rows below channel bar
		int keypadTopY = actionY + actionH + 6;
		int gapX = 3;
		int gapY = 3;
		int keyW = 32;
		int funcW = innerW - (keyW * 4 + gapX * 4);
		int rowH = 21;

		int c0 = padX;
		int c1 = c0 + keyW + gapX;
		int c2 = c1 + keyW + gapX;
		int c3 = c2 + keyW + gapX;
		int c4 = c3 + keyW + gapX;

		int r0 = keypadTopY;
		int r1 = r0 + rowH + gapY;
		int r2 = r1 + rowH + gapY;
		int r3 = r2 + rowH + gapY;

		addDeckKey("1", Component.literal("1"), c0, r0, keyW, rowH, DeckRole.DIGIT, -1, () -> handleDigit('1'));
		addDeckKey("2", Component.literal("2"), c1, r0, keyW, rowH, DeckRole.DIGIT, -1, () -> handleDigit('2'));
		addDeckKey("3", Component.literal("3"), c2, r0, keyW, rowH, DeckRole.DIGIT, -1, () -> handleDigit('3'));
		addDeckKey("4", Component.literal("4"), c3, r0, keyW, rowH, DeckRole.DIGIT, -1, () -> handleDigit('4'));

		addDeckKey("5", Component.literal("5"), c0, r1, keyW, rowH, DeckRole.DIGIT, -1, () -> handleDigit('5'));
		addDeckKey("6", Component.literal("6"), c1, r1, keyW, rowH, DeckRole.DIGIT, -1, () -> handleDigit('6'));
		addDeckKey("7", Component.literal("7"), c2, r1, keyW, rowH, DeckRole.DIGIT, -1, () -> handleDigit('7'));
		addDeckKey("8", Component.literal("8"), c3, r1, keyW, rowH, DeckRole.DIGIT, -1, () -> handleDigit('8'));

		addDeckKey("9", Component.literal("9"), c0, r2, keyW, rowH, DeckRole.DIGIT, -1, () -> handleDigit('9'));
		addDeckKey("0", Component.literal("0"), c1, r2, keyW * 2 + gapX, rowH, DeckRole.DIGIT, -1, () -> handleDigit('0'));
		addDeckKey(".", Component.literal("."), c3, r2, keyW, rowH, DeckRole.DIGIT, -1, this::handleDot);

		// Right function column: FRK/ŞFR and Sil expanded vertically to evenly span all 3 keypad rows
		int totalKeypadH = (rowH * 3) + (gapY * 2); // 69px
		int topFuncH = (totalKeypadH - gapY) / 2;   // 33px
		int botFuncH = totalKeypadH - gapY - topFuncH; // 33px
		addDeckKey("MODE", Component.translatable("gui.tlouradio.handheld.key_mode"), c4, r0, funcW, topFuncH, DeckRole.MODE, -1, this::toggleInputMode);
		addDeckKey("DEL", Component.translatable("gui.tlouradio.handheld.key_delete"), c4, r0 + topFuncH + gapY, funcW, botFuncH, DeckRole.DELETE, -1, this::handleDelete);

		addDeckKey("ESC", Component.translatable("gui.tlouradio.base_box.cancel"), c0, r3, innerW, 19, DeckRole.CANCEL, -1, this::initiateSmoothClose);
	}

	private void addDeckKey(
			String keyId,
			Component label,
			int x,
			int y,
			int w,
			int h,
			DeckRole role,
			int slotIndex,
			Runnable action
	) {
		addRenderableWidget(new TacticalDeckButton(keyId, x, y, w, h, label, role, slotIndex, action));
	}

	private void selectChannel(int index) {
		if (index < 0 || index >= this.channels.size()) {
			rejectKey("CH_" + index);
			return;
		}
		if (index == this.selectedChannelIndex) {
			return;
		}
		this.selectedChannelIndex = index;
		this.inputMode = InputMode.FREQUENCY;
		this.targetDialAngleDeg = HandheldRadioGeoItemRenderer.computeDialAngleRad(currentChannel().frequency) * Mth.RAD_TO_DEG;
		triggerDeckKeyAnim("CH_" + index, 0.0F);
		playTactileBeep(1.30F + index * 0.05F, 0.28F);
	}

	private void stepSelectedChannel(int delta) {
		if (this.channels.size() <= 1) {
			playMechanicalClick(0.75F, 0.24F);
			return;
		}
		int next = Math.floorMod(this.selectedChannelIndex + delta, this.channels.size());
		selectChannel(next);
	}

	private void addChannel() {
		if (this.channels.size() >= RadioBaseNetworkSavedData.MAX_CHANNELS_PER_BOX) {
			rejectKey("CH_ADD");
			return;
		}
		Set<String> used = new HashSet<>();
		for (EditableChannel ch : this.channels) {
			used.add(RadioStateComponent.normalizeFrequency(ch.frequency));
		}
		String candidate = RadioStateComponent.isValidFrequency(currentChannel().frequency)
				? RadioStateComponent.normalizeFrequency(currentChannel().frequency)
				: RadioStateComponent.DEFAULT_FREQUENCY;
		for (int i = 0; i < 999; i++) {
			candidate = RadioStateComponent.stepFrequencyTenths(candidate, 10);
			if (!used.contains(candidate)) {
				break;
			}
		}
		this.channels.add(new EditableChannel(candidate, "", false));
		this.selectedChannelIndex = this.channels.size() - 1;
		this.inputMode = InputMode.FREQUENCY;
		this.targetDialAngleDeg = HandheldRadioGeoItemRenderer.computeDialAngleRad(candidate) * Mth.RAD_TO_DEG;
		triggerDeckKeyAnim("CH_ADD", 12.0F);
		playTactileBeep(1.66F, 0.34F);
	}

	private void removeSelectedChannel() {
		if (this.channels.size() <= 1) {
			rejectKey("CH_REM");
			return;
		}
		this.channels.remove(this.selectedChannelIndex);
		this.selectedChannelIndex = Mth.clamp(this.selectedChannelIndex, 0, this.channels.size() - 1);
		this.inputMode = InputMode.FREQUENCY;
		this.targetDialAngleDeg = HandheldRadioGeoItemRenderer.computeDialAngleRad(currentChannel().frequency) * Mth.RAD_TO_DEG;
		triggerDeckKeyAnim("CH_REM", -12.0F);
		playTactileBeep(0.84F, 0.34F);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.invalidFlashTicks > 0) {
			this.invalidFlashTicks--;
		}
		if (this.inlineWarningTicks > 0) {
			this.inlineWarningTicks--;
			if (this.inlineWarningTicks == 0) {
				this.inlineWarningMessage = null;
			}
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

	private HandheldRadioModelRenderer.ProjectedBounds getPrevChannel3DHitbox() {
		return BaseBoxModelRenderer.projectGeoBoxToScreen(
				this.layout, -2.5F, 1.6F, -6.65F, -1.0F, 3.1F, -6.05F
		);
	}

	private HandheldRadioModelRenderer.ProjectedBounds getNextChannel3DHitbox() {
		return BaseBoxModelRenderer.projectGeoBoxToScreen(
				this.layout, -0.5F, 1.6F, -6.65F, 1.0F, 3.1F, -6.05F
		);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.transition.isClosing()) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.layout != null) {
			if (getMainDialHitbox().contains(event.x(), event.y())) {
				this.draggingMainDial = true;
				this.dialDragResidualPx = 0.0D;
				playMechanicalClick(1.45F, 0.22F);
				return true;
			}
			if (getPrevChannel3DHitbox().contains(event.x(), event.y())) {
				stepSelectedChannel(-1);
				return true;
			}
			if (getNextChannel3DHitbox().contains(event.x(), event.y())) {
				stepSelectedChannel(1);
				return true;
			}
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
		EditableChannel ch = currentChannel();
		String base = RadioStateComponent.isValidFrequency(ch.frequency)
				? ch.frequency
				: RadioStateComponent.DEFAULT_FREQUENCY;
		ch.frequency = RadioStateComponent.stepFrequencyTenths(base, deltaTenths);
		this.inputMode = InputMode.FREQUENCY;
		this.targetDialAngleDeg = Mth.wrapDegrees(this.targetDialAngleDeg + detents * 18.0F);
		playMechanicalClick(deltaTenths > 0 ? 1.60F : 1.36F, 0.28F);
	}

	private void handleDigit(char digit) {
		EditableChannel ch = currentChannel();
		String keyId = String.valueOf(digit);
		float pitch = 1.12F + ((digit - '0') * 0.048F);

		if (this.inputMode == InputMode.FREQUENCY) {
			if (ch.frequency.length() >= RadioStateComponent.MAX_FREQUENCY_LENGTH) {
				rejectKey(keyId);
				return;
			}
			ch.frequency += digit;
			triggerDeckKeyAnim(keyId, 12.0F);
			playTactileBeep(pitch, 0.34F);
		} else {
			if (!ch.passcodeModified) {
				ch.passcodeModified = true;
				ch.passcode = keyId;
				triggerDeckKeyAnim(keyId, 12.0F);
				playTactileBeep(pitch, 0.34F);
				return;
			}
			if (ch.passcode.length() >= RadioStateComponent.MAX_PASSCODE_LENGTH) {
				rejectKey(keyId);
				return;
			}
			ch.passcode += digit;
			triggerDeckKeyAnim(keyId, 12.0F);
			playTactileBeep(pitch, 0.34F);
		}
	}

	private void handleDot() {
		EditableChannel ch = currentChannel();
		if (this.inputMode != InputMode.FREQUENCY
				|| ch.frequency.isEmpty()
				|| ch.frequency.indexOf('.') >= 0
				|| ch.frequency.length() >= RadioStateComponent.MAX_FREQUENCY_LENGTH) {
			rejectKey(".");
			return;
		}
		ch.frequency += '.';
		triggerDeckKeyAnim(".", 10.0F);
		playTactileBeep(1.62F, 0.32F);
	}

	private void handleDelete() {
		EditableChannel ch = currentChannel();
		if (this.inputMode == InputMode.FREQUENCY) {
			if (ch.frequency.isEmpty()) {
				rejectKey("DEL");
				return;
			}
			ch.frequency = ch.frequency.substring(0, ch.frequency.length() - 1);
			triggerDeckKeyAnim("DEL", -12.0F);
			playTactileBeep(0.88F, 0.34F);
		} else {
			if (!ch.passcodeModified) {
				if (ch.hadInitialPasscode) {
					ch.passcodeModified = true;
					ch.passcode = "";
					triggerDeckKeyAnim("DEL", -12.0F);
					playTactileBeep(0.88F, 0.34F);
				} else {
					rejectKey("DEL");
				}
				return;
			}
			if (ch.passcode.isEmpty()) {
				rejectKey("DEL");
				return;
			}
			ch.passcode = ch.passcode.substring(0, ch.passcode.length() - 1);
			triggerDeckKeyAnim("DEL", -12.0F);
			playTactileBeep(0.88F, 0.34F);
		}
	}

	private void toggleInputMode() {
		this.inputMode = (this.inputMode == InputMode.FREQUENCY) ? InputMode.PASSCODE : InputMode.FREQUENCY;
		triggerDeckKeyAnim("MODE", this.inputMode == InputMode.PASSCODE ? 22.0F : -22.0F);
		playTactileBeep(this.inputMode == InputMode.PASSCODE ? 1.36F : 1.50F, 0.34F);
	}

	private void submitSaveRequest() {
		if (this.awaitingSaveResult) {
			return;
		}

		List<RadioBaseChannel> payloadChannels = new ArrayList<>(this.channels.size());
		Set<String> seenNormalized = new HashSet<>();

		for (int i = 0; i < this.channels.size(); i++) {
			EditableChannel ch = this.channels.get(i);
			if (!RadioStateComponent.isValidFrequency(ch.frequency) || !RadioStateComponent.isValidPasscode(ch.passcode)) {
				this.selectedChannelIndex = i;
				triggerDeckKeyAnim("SAVE", 0.0F);
				showInlineWarning(Component.translatable("message.tlouradio.base_box_invalid_input"));
				return;
			}
			String norm = RadioStateComponent.normalizeFrequency(ch.frequency);
			if (!seenNormalized.add(norm)) {
				this.selectedChannelIndex = i;
				triggerDeckKeyAnim("SAVE", 0.0F);
				showInlineWarning(Component.translatable("message.tlouradio.base_box_freq_duplicate"));
				return;
			}
			payloadChannels.add(new RadioBaseChannel(norm, ch.passcode));
		}

		triggerDeckKeyAnim("SAVE", 20.0F);
		playTactileBeep(1.78F, 0.42F);
		if (ClientPlayNetworking.canSend(UpdateBaseBoxConfigPayload.TYPE)) {
			this.awaitingSaveResult = true;
			ClientPlayNetworking.send(new UpdateBaseBoxConfigPayload(
					this.blockPos,
					payloadChannels,
					this.selectedChannelIndex
			));
		} else {
			initiateSmoothClose();
		}
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
			submitSaveRequest();
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

		// 2. Render lower tactical military keypad + channel bank housing
		int dx = this.layout.deckX();
		int dy = this.layout.deckY();
		int dw = this.layout.deckWidth();
		int dh = this.layout.deckHeight();
		graphics.fill(dx, dy, dx + dw, dy + dh, 0xDF141916);
		graphics.outline(dx, dy, dw, dh, 0xFF46564B);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		if (focus > 0.65F && !this.transition.isClosing()) {
			// Interactive 3D hover overlays (main dial + 2 large front-panel channel buttons)
			HandheldRadioModelRenderer.ProjectedBounds dialBox = getMainDialHitbox();
			if (this.draggingMainDial || dialBox.contains(mouseX, mouseY)) {
				HandheldRadioModelRenderer.extractDialHoverArrows(graphics, dialBox);
			}

			HandheldRadioModelRenderer.ProjectedBounds prevBtn = getPrevChannel3DHitbox();
			if (prevBtn.contains(mouseX, mouseY)) {
				render3DChannelButtonHighlight(graphics, prevBtn, "<");
			}

			HandheldRadioModelRenderer.ProjectedBounds nextBtn = getNextChannel3DHitbox();
			if (nextBtn.contains(mouseX, mouseY)) {
				render3DChannelButtonHighlight(graphics, nextBtn, ">");
			}

			// Inline error/conflict banner (keeps screen open on conflict or invalid input)
			if (this.inlineWarningMessage != null && this.inlineWarningTicks > 0) {
				renderInlineWarningBanner(graphics, dx, dy, dw);
			}

			int alpha = Math.round(0xB4 * ((focus - 0.65F) / 0.35F));
			int hintY = Math.min(this.height - 11, dy + dh + 5);
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

	private void render3DChannelButtonHighlight(
			GuiGraphicsExtractor graphics,
			HandheldRadioModelRenderer.ProjectedBounds bounds,
			String glyph
	) {
		int x0 = Math.round(bounds.minX()) - 1;
		int y0 = Math.round(bounds.minY()) - 1;
		int x1 = Math.round(bounds.maxX()) + 1;
		int y1 = Math.round(bounds.maxY()) + 1;
		graphics.fill(x0, y0, x1, y1, 0x3878FFAE);
		graphics.outline(x0, y0, Math.max(2, x1 - x0), Math.max(2, y1 - y0), 0xE688FFB8);
		graphics.centeredText(this.font, Component.literal(glyph), bounds.centerX(), bounds.centerY() - 4, 0xFFFFFFFF);
	}

	private void renderInlineWarningBanner(GuiGraphicsExtractor graphics, int deckX, int deckY, int deckWidth) {
		int bannerH = 18;
		int bannerW = Math.max(deckWidth, Math.min(this.width - 20, this.font.width(this.inlineWarningMessage) + 18));
		int bx = this.layout.centerX() - (bannerW / 2);
		int by = Math.max(8, deckY - bannerH - 4);

		boolean flash = this.invalidFlashTicks > 0 && ((this.invalidFlashTicks / 2) % 2 == 0);
		int bg = flash ? 0xF05E1B16 : 0xEB3B1410;
		int border = flash ? 0xFFFF8A65 : 0xFFE66A4A;
		int textCol = 0xFFFFE0B2;

		graphics.fill(bx, by, bx + bannerW, by + bannerH, bg);
		graphics.outline(bx, by, bannerW, bannerH, border);

		int tw = this.font.width(this.inlineWarningMessage);
		float maxW = bannerW - 10.0F;
		if (tw > maxW && tw > 0) {
			float s = maxW / (float) tw;
			graphics.pose().pushMatrix();
			graphics.pose().translate(bx + bannerW / 2.0F, by + bannerH / 2.0F - 4.0F * s);
			graphics.pose().scale(s, s);
			graphics.text(this.font, this.inlineWarningMessage, -tw / 2, 0, textCol, true);
			graphics.pose().popMatrix();
		} else {
			graphics.centeredText(this.font, this.inlineWarningMessage, bx + bannerW / 2, by + (bannerH - 8) / 2, textCol);
		}
	}

	private BaseBoxModelRenderer.PanelReadout buildPanelReadout() {
		EditableChannel ch = currentChannel();
		String netLabel = Component.translatable(
				"gui.tlouradio.base_box.lcd_net_channel",
				RadioStateComponent.shortNetworkId(this.networkId).substring(0, 4),
				this.selectedChannelIndex + 1,
				this.channels.size()
		).getString();
		boolean hasPin = ch.passcodeModified ? !ch.passcode.isEmpty() : ch.hadInitialPasscode;
		String pinStatus = Component.translatable(
				hasPin ? "gui.tlouradio.handheld.lcd_pin_set" : "gui.tlouradio.handheld.lcd_pin_none"
		).getString();

		boolean blink = (System.currentTimeMillis() / 450L) % 2L == 0L;
		String cursor = blink ? "_" : "";
		String liveBuf;
		int bufColor;
		if (this.inputMode == InputMode.FREQUENCY) {
			boolean valid = RadioStateComponent.isValidFrequency(ch.frequency);
			liveBuf = Component.translatable(
					"gui.tlouradio.handheld.lcd_buf_freq",
					ch.frequency + cursor
			).getString();
			bufColor = (this.invalidFlashTicks > 0 || !valid) ? 0xFFF0B45A : 0xFFB8FFD4;
		} else if (!ch.passcodeModified) {
			liveBuf = ch.hadInitialPasscode
					? Component.translatable("gui.tlouradio.handheld.lcd_buf_pin_masked").getString()
					: Component.translatable("gui.tlouradio.handheld.lcd_buf_pin", cursor).getString();
			bufColor = 0xFF8CD8EE;
		} else {
			liveBuf = Component.translatable(
					"gui.tlouradio.handheld.lcd_buf_pin",
					ch.passcode + cursor
			).getString();
			bufColor = 0xFFAEEBFF;
		}

		String freqLabel = Component.translatable(
				"gui.tlouradio.handheld.lcd_freq_unit",
				ch.frequency
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
		private final int slotIndex;
		private final Runnable action;

		private TacticalDeckButton(
				String keyId,
				int x,
				int y,
				int width,
				int height,
				Component message,
				DeckRole role,
				int slotIndex,
				Runnable action
		) {
			super(x, y, width, height, message);
			this.keyId = keyId;
			this.role = role;
			this.slotIndex = slotIndex;
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
			boolean disabled = isVisuallyDisabled();
			boolean selectedSlot = this.role == DeckRole.CHANNEL_SLOT
					&& this.slotIndex == RadioBaseBoxScreen.this.selectedChannelIndex;

			int offset = depressed ? 1 : 0;
			int x = getX();
			int y = getY() + offset;
			int w = getWidth();
			int h = getHeight() - offset;

			if (!depressed) {
				graphics.fill(x, getY() + getHeight() - 2, x + w, getY() + getHeight(), 0xCC090C0A);
			}

			int bg = resolveBg(depressed, isHovered(), disabled, selectedSlot);
			int border = selectedSlot
					? 0xFF8DF5B2
					: (depressed ? 0xFF8BE8A8 : (isHovered() && !disabled ? 0xFF7CA088 : 0xFF3E4C43));
			int textCol = disabled ? 0xFF5A635D : (selectedSlot ? 0xFFFFFFFF : 0xFFE6ECE8);

			graphics.fill(x, y, x + w, y + h - (depressed ? 0 : 1), bg);
			graphics.outline(x, y, w, h - (depressed ? 0 : 1), border);

			Component label = getMessage();
			if (this.role == DeckRole.SAVE) {
				// Compact pixel-art floppy/save icon + label
				int iconSize = 8;
				int iconX = x + 6;
				int iconY = y + (h - iconSize) / 2;
				drawSaveIcon(graphics, iconX, iconY, disabled ? 0xFF5A635D : 0xFFB8FFD4);

				int textAreaX = iconX + iconSize + 3;
				int textAreaW = Math.max(8, (x + w - 4) - textAreaX);
				int tw = RadioBaseBoxScreen.this.font.width(label);
				if (tw > textAreaW && tw > 0) {
					float s = (float) textAreaW / (float) tw;
					graphics.pose().pushMatrix();
					graphics.pose().translate(textAreaX + textAreaW / 2.0F, y + (h - 1) / 2.0F - 4.0F * s);
					graphics.pose().scale(s, s);
					graphics.text(RadioBaseBoxScreen.this.font, label, -tw / 2, 0, textCol, true);
					graphics.pose().popMatrix();
				} else {
					graphics.centeredText(RadioBaseBoxScreen.this.font, label, textAreaX + textAreaW / 2, y + (h - 9) / 2, textCol);
				}
				return;
			}

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

		private void drawSaveIcon(GuiGraphicsExtractor graphics, int x, int y, int col) {
			// Outer floppy shell (8x8)
			graphics.fill(x, y, x + 7, y + 8, col);
			graphics.fill(x + 7, y + 1, x + 8, y + 8, col);
			// Top metal shutter cutout
			graphics.fill(x + 2, y + 1, x + 5, y + 3, 0xFF1A3A28);
			// Bottom label window
			graphics.fill(x + 2, y + 5, x + 6, y + 7, 0xFF1A3A28);
		}

		private boolean isVisuallyDisabled() {
			return switch (this.role) {
				case CHANNEL_SLOT -> this.slotIndex >= RadioBaseBoxScreen.this.channels.size();
				case CHANNEL_ADD -> RadioBaseBoxScreen.this.channels.size() >= RadioBaseNetworkSavedData.MAX_CHANNELS_PER_BOX;
				case CHANNEL_REMOVE -> RadioBaseBoxScreen.this.channels.size() <= 1;
				case SAVE -> RadioBaseBoxScreen.this.awaitingSaveResult;
				default -> ".".equals(this.keyId) && RadioBaseBoxScreen.this.inputMode == InputMode.PASSCODE;
			};
		}

		private int resolveBg(boolean depressed, boolean hovered, boolean disabled, boolean selectedSlot) {
			if (disabled) return 0xC8171C19;
			if (selectedSlot) return 0xF02B6A45;
			return switch (this.role) {
				case CHANNEL_SLOT -> hovered ? 0xF0304438 : 0xE8223028;
				case CHANNEL_ADD -> depressed ? 0xF02E6E46 : (hovered ? 0xF027593B : 0xE81E452E);
				case CHANNEL_REMOVE -> depressed ? 0xF06B342E : (hovered ? 0xF0582B26 : 0xE844221F);
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
