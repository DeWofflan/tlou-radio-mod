package com.tlouradio.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.tlouradio.client.RadioClientInputHandler;
import com.tlouradio.client.gui.render.HandheldRadioModelRenderer;
import com.tlouradio.client.renderer.HandheldRadioGeoItemRenderer;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.network.payload.SetRadioAntennaPayload;
import com.tlouradio.network.payload.StepRadioDialPayload;
import com.tlouradio.network.payload.UpdateHandheldRadioPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class HandheldRadioScreen extends Screen {
	private static final long DIAL_SYNC_DEBOUNCE_MS = 150L;
	private static final double DIAL_PIXELS_PER_DETENT = 9.0D;
	private static final double ANTENNA_DRAG_RANGE_PX = 46.0D;

	private enum InputMode {
		FREQUENCY,
		PASSCODE
	}

	private enum DragTarget {
		NONE,
		ANTENNA,
		DIAL_KNOB,
		PTT_BUTTON
	}

	private enum KeyRole {
		DIGIT,
		DOT,
		MODE,
		DELETE,
		ENTER
	}

	private record KeySpec(
			String keyId,
			Component label,
			int baseX,
			int baseY,
			int width,
			int height,
			KeyRole role,
			Runnable action
	) {
	}

	private final InteractionHand hand;
	private final boolean initiallyPaired;
	private final boolean hadInitialPasscode;
	private final String preservedUnmodifiedFreePasscode;

	private boolean isPowered;
	private boolean antennaExtended;
	private String activeSavedFrequency;
	private Optional<UUID> activeNetworkId;

	private InputMode inputMode = InputMode.FREQUENCY;
	private String frequencyBuffer;
	private String passcodeBuffer = "";
	private boolean passcodeModified = false;
	private int invalidFlashTicks = 0;

	// Interactive 3D manipulation state
	private DragTarget activeDragTarget = DragTarget.NONE;
	private double antennaDragStartMouseY = 0.0D;
	private float antennaDragStartRatio = 1.0F;
	private double dialDragResidualPx = 0.0D;
	private boolean pendingDialSync = false;
	private long lastDialStepTimeMs = 0L;

	private final List<KeySpec> keySpecs = new ArrayList<>(14);
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
		this.antennaExtended = initialState.antennaExtended();
		this.activeSavedFrequency = initialState.frequency();
		this.activeNetworkId = initialState.networkId();

		// PIN Masking Security: never expose existing/paired passcode in any plaintext UI buffer
		this.initiallyPaired = initialState.networkId().isPresent();
		this.hadInitialPasscode = !initialState.passcode().isEmpty();
		this.preservedUnmodifiedFreePasscode = this.initiallyPaired ? "" : initialState.passcode();

		this.frequencyBuffer = initialState.frequency();

		this.animationState.setTargetAntennaExtended(this.antennaExtended);
		this.animationState.setDraggingAntenna(false, this.antennaExtended ? 1.0F : 0.0F);
		this.animationState.setInitialDialAngle(
				HandheldRadioGeoItemRenderer.computeDialAngleRad(initialState.frequency()) * Mth.RAD_TO_DEG
		);
	}

	@Override
	protected void init() {
		this.layout = HandheldRadioModelRenderer.computeLayout(this.width, this.height);
		rebuildPhysicalKeySpecs(this.layout);
	}

	private void rebuildPhysicalKeySpecs(HandheldRadioModelRenderer.DeviceLayout d) {
		this.keySpecs.clear();

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
		this.keySpecs.add(new KeySpec("1", Component.literal("1"), col0, row0, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('1')));
		this.keySpecs.add(new KeySpec("2", Component.literal("2"), col1, row0, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('2')));
		this.keySpecs.add(new KeySpec("3", Component.literal("3"), col2, row0, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('3')));
		this.keySpecs.add(new KeySpec(
				"MODE",
				Component.translatable("gui.tlouradio.handheld.key_mode"),
				col3,
				row0,
				funcW,
				rowH,
				KeyRole.MODE,
				this::toggleInputMode
		));

		// Row 1: 4, 5, 6, Sil
		this.keySpecs.add(new KeySpec("4", Component.literal("4"), col0, row1, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('4')));
		this.keySpecs.add(new KeySpec("5", Component.literal("5"), col1, row1, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('5')));
		this.keySpecs.add(new KeySpec("6", Component.literal("6"), col2, row1, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('6')));
		this.keySpecs.add(new KeySpec(
				"DEL",
				Component.translatable("gui.tlouradio.handheld.key_delete"),
				col3,
				row1,
				funcW,
				rowH,
				KeyRole.DELETE,
				this::handleDelete
		));

		// Row 2: 7, 8, 9 + Tall Enter spanning Row 2 & Row 3
		this.keySpecs.add(new KeySpec("7", Component.literal("7"), col0, row2, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('7')));
		this.keySpecs.add(new KeySpec("8", Component.literal("8"), col1, row2, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('8')));
		this.keySpecs.add(new KeySpec("9", Component.literal("9"), col2, row2, digitW, rowH, KeyRole.DIGIT, () -> handleDigit('9')));
		this.keySpecs.add(new KeySpec(
				"ENT",
				Component.translatable("gui.tlouradio.handheld.key_enter"),
				col3,
				row2,
				funcW,
				rowH * 2 + gapY,
				KeyRole.ENTER,
				this::handleSubmit
		));

		// Row 3: Wide 0 + '.'
		this.keySpecs.add(new KeySpec("0", Component.literal("0"), col0, row3, digitW * 2 + gapX, rowH, KeyRole.DIGIT, () -> handleDigit('0')));
		this.keySpecs.add(new KeySpec(".", Component.literal("."), col2, row3, digitW, rowH, KeyRole.DOT, this::handleDot));
	}

	@Override
	public void tick() {
		super.tick();
		if (this.invalidFlashTicks > 0) {
			this.invalidFlashTicks--;
		}

		checkDialDebounceFlush(false);

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
		if (!this.pendingDialSync && this.activeDragTarget != DragTarget.DIAL_KNOB) {
			this.activeSavedFrequency = liveState.frequency();
			this.activeNetworkId = liveState.networkId();
		}
		if (this.activeDragTarget != DragTarget.ANTENNA) {
			this.antennaExtended = liveState.antennaExtended();
			this.animationState.setTargetAntennaExtended(this.antennaExtended);
		}

		boolean pttHeld = RadioClientInputHandler.isPttTransmitting()
				|| this.activeDragTarget == DragTarget.PTT_BUTTON;
		this.animationState.setPttPressed(pttHeld);
	}

	@Override
	public void removed() {
		flushPendingInteractionsBeforeClose();
		super.removed();
	}

	@Override
	public void onClose() {
		flushPendingInteractionsBeforeClose();
		super.onClose();
	}

	private void flushPendingInteractionsBeforeClose() {
		if (this.activeDragTarget == DragTarget.PTT_BUTTON || RadioClientInputHandler.isScreenPttActive()) {
			this.activeDragTarget = DragTarget.NONE;
			RadioClientInputHandler.releaseScreenPtt();
		}
		checkDialDebounceFlush(true);
	}

	private void checkDialDebounceFlush(boolean forceNow) {
		if (!this.pendingDialSync) {
			return;
		}
		long elapsed = System.currentTimeMillis() - this.lastDialStepTimeMs;
		if (forceNow || (this.activeDragTarget != DragTarget.DIAL_KNOB && elapsed >= DIAL_SYNC_DEBOUNCE_MS) || elapsed >= DIAL_SYNC_DEBOUNCE_MS) {
			this.pendingDialSync = false;
			if (RadioStateComponent.isValidFrequency(this.activeSavedFrequency)
					&& ClientPlayNetworking.canSend(StepRadioDialPayload.TYPE)) {
				ClientPlayNetworking.send(new StepRadioDialPayload(this.activeSavedFrequency, this.hand));
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.layout != null) {
			double mx = event.x();
			double my = event.y();

			// 1. Check 3D Telescopic Antenna drag zone (top-left)
			if (isOverAntenna(mx, my)) {
				this.activeDragTarget = DragTarget.ANTENNA;
				this.antennaDragStartMouseY = my;
				this.antennaDragStartRatio = this.animationState.antennaExtension();
				playMechanicalClick(1.25F, 0.20F);
				return true;
			}

			// 2. Check 3D Rotary Dial Knob drag zone (top-right)
			if (isOverDialKnob(mx, my)) {
				this.activeDragTarget = DragTarget.DIAL_KNOB;
				this.dialDragResidualPx = 0.0D;
				playMechanicalClick(1.45F, 0.22F);
				return true;
			}

			// 3. Check 3D Side PTT Latch hold zone (upper-left side)
			if (isOverPttButton(mx, my)) {
				this.activeDragTarget = DragTarget.PTT_BUTTON;
				this.animationState.setPttPressed(true);
				RadioClientInputHandler.setScreenPttActive(true);
				playMechanicalClick(0.95F, 0.32F);
				return true;
			}

			// 4. Check 14 protruding 3D physical keycaps (parallax-compensated)
			float pxOff = HandheldRadioModelRenderer.computeParallaxOffsetX(this.animationState);
			float pyOff = HandheldRadioModelRenderer.computeParallaxOffsetY(this.animationState);
			for (KeySpec spec : this.keySpecs) {
				if (isInsideKey(spec, mx, my, pxOff, pyOff)) {
					spec.action().run();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.activeDragTarget != DragTarget.NONE) {
			if (this.activeDragTarget == DragTarget.ANTENNA) {
				// Dragging UP (negative deltaY) extends; dragging DOWN (positive deltaY) retracts
				double totalDeltaY = this.antennaDragStartMouseY - event.y();
				float liveRatio = Mth.clamp(
						this.antennaDragStartRatio + (float) (totalDeltaY / ANTENNA_DRAG_RANGE_PX),
						0.0F,
						1.0F
				);
				this.animationState.setDraggingAntenna(true, liveRatio);

				boolean shouldExtend = liveRatio >= 0.5F;
				if (shouldExtend != this.antennaExtended) {
					this.antennaExtended = shouldExtend;
					playMechanicalClick(shouldExtend ? 1.65F : 0.78F, 0.35F);
					if (ClientPlayNetworking.canSend(SetRadioAntennaPayload.TYPE)) {
						ClientPlayNetworking.send(new SetRadioAntennaPayload(this.antennaExtended, this.hand));
					}
				}
				return true;
			}

			if (this.activeDragTarget == DragTarget.DIAL_KNOB) {
				this.dialDragResidualPx += dragX;
				int steps = (int) (this.dialDragResidualPx / DIAL_PIXELS_PER_DETENT);
				if (steps != 0) {
					this.dialDragResidualPx -= steps * DIAL_PIXELS_PER_DETENT;
					applyDialFrequencySteps(steps);
				}
				return true;
			}

			if (this.activeDragTarget == DragTarget.PTT_BUTTON) {
				return true;
			}
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.activeDragTarget != DragTarget.NONE) {
			DragTarget released = this.activeDragTarget;
			this.activeDragTarget = DragTarget.NONE;

			if (released == DragTarget.ANTENNA) {
				this.animationState.setDraggingAntenna(false, this.animationState.antennaExtension());
				this.animationState.setTargetAntennaExtended(this.antennaExtended);
			} else if (released == DragTarget.DIAL_KNOB) {
				checkDialDebounceFlush(true);
			} else if (released == DragTarget.PTT_BUTTON) {
				this.animationState.setPttPressed(RadioClientInputHandler.PTT_KEY.isDown());
				RadioClientInputHandler.releaseScreenPtt();
				playMechanicalClick(1.18F, 0.22F);
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	private void applyDialFrequencySteps(int steps) {
		String baseFreq = RadioStateComponent.isValidFrequency(this.frequencyBuffer)
				? this.frequencyBuffer
				: this.activeSavedFrequency;
		String nextFreq = RadioStateComponent.stepFrequencyTenths(baseFreq, steps);
		if (nextFreq.equals(this.activeSavedFrequency) && nextFreq.equals(this.frequencyBuffer)) {
			playMechanicalClick(0.65F, 0.18F);
			return;
		}

		this.activeSavedFrequency = nextFreq;
		this.frequencyBuffer = nextFreq;
		this.activeNetworkId = Optional.empty();
		this.inputMode = InputMode.FREQUENCY;

		this.animationState.stepDialAngle(steps * 18.0F);
		float clickPitch = steps > 0 ? 1.62F : 1.38F;
		playMechanicalClick(clickPitch, 0.28F);

		this.pendingDialSync = true;
		this.lastDialStepTimeMs = System.currentTimeMillis();
	}

	private boolean isOverAntenna(double mx, double my) {
		if (this.layout == null) {
			return false;
		}
		float px = HandheldRadioModelRenderer.computeParallaxOffsetX(this.animationState);
		float py = HandheldRadioModelRenderer.computeParallaxOffsetY(this.animationState);
		double x0 = this.layout.centerX() - 54 + px;
		double x1 = this.layout.centerX() - 28 + px;
		double topExtent = Mth.lerp(this.animationState.antennaExtension(), 26.0F, 76.0F);
		double y0 = this.layout.bodyTop() - topExtent + py;
		double y1 = this.layout.bodyTop() + 4 + py;
		return mx >= x0 && mx <= x1 && my >= y0 && my <= y1;
	}

	private boolean isOverDialKnob(double mx, double my) {
		if (this.layout == null) {
			return false;
		}
		float px = HandheldRadioModelRenderer.computeParallaxOffsetX(this.animationState);
		float py = HandheldRadioModelRenderer.computeParallaxOffsetY(this.animationState);
		double x0 = this.layout.centerX() + 22 + px;
		double x1 = this.layout.centerX() + 52 + px;
		double y0 = this.layout.bodyTop() - 26 + py;
		double y1 = this.layout.bodyTop() + 4 + py;
		return mx >= x0 && mx <= x1 && my >= y0 && my <= y1;
	}

	private boolean isOverPttButton(double mx, double my) {
		if (this.layout == null) {
			return false;
		}
		float px = HandheldRadioModelRenderer.computeParallaxOffsetX(this.animationState);
		float py = HandheldRadioModelRenderer.computeParallaxOffsetY(this.animationState);
		double x0 = this.layout.bodyLeft() - 14 + px;
		double x1 = this.layout.bodyLeft() + 3 + px;
		double y0 = this.layout.bodyCenterY() - 68 + py;
		double y1 = this.layout.bodyCenterY() - 18 + py;
		return mx >= x0 && mx <= x1 && my >= y0 && my <= y1;
	}

	private static boolean isInsideKey(KeySpec spec, double mx, double my, float pxOff, float pyOff) {
		double x0 = spec.baseX() + pxOff;
		double y0 = spec.baseY() + pyOff;
		return mx >= x0 && mx <= (x0 + spec.width()) && my >= y0 && my <= (y0 + spec.height());
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

		// UpdateHandheldRadioPayload carries full state, so cancel any redundant pending dial packet
		this.pendingDialSync = false;
		this.animationState.triggerKeyPress("ENT", 24.0F);
		playTactileBeep(1.78F, 0.42F);

		if (ClientPlayNetworking.canSend(UpdateHandheldRadioPayload.TYPE)) {
			ClientPlayNetworking.send(new UpdateHandheldRadioPayload(
					targetFreq,
					targetPass,
					this.antennaExtended,
					this.hand
			));
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

	private void playMechanicalClick(float pitch, float volume) {
		Minecraft.getInstance().getSoundManager()
				.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, volume));
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
		graphics.fillGradient(0, 0, this.width, this.height, 0x22070B09, 0x56050807);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (this.layout == null) {
			this.layout = HandheldRadioModelRenderer.computeLayout(this.width, this.height);
			rebuildPhysicalKeySpecs(this.layout);
		}

		checkDialDebounceFlush(false);
		this.animationState.updateFrame(mouseX, mouseY, this.layout, this.width, this.height);

		boolean transmitting = RadioClientInputHandler.isPttTransmitting() && this.isPowered && this.antennaExtended;

		// 1. Render 3D GeckoLib model (body, 3D protruding keycaps, draggable antenna, rotary dial, side PTT)
		HandheldRadioModelRenderer.extractModel(
				graphics,
				this.layout,
				this.isPowered,
				transmitting,
				this.animationState
		);

		float pxOff = HandheldRadioModelRenderer.computeParallaxOffsetX(this.animationState);
		float pyOff = HandheldRadioModelRenderer.computeParallaxOffsetY(this.animationState);

		// 2. Render parallax-synced recessed Digital LCD telemetry
		extractDigitalLcd(graphics, this.layout, pxOff, pyOff, transmitting);

		// 3. Render crisp front-face labels & subtle interactive hover highlights onto the 3D keycaps & controls
		extract3DKeycapLabelsAndHighlights(graphics, mouseX, mouseY, pxOff, pyOff);

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

	private void extractDigitalLcd(
			GuiGraphicsExtractor graphics,
			HandheldRadioModelRenderer.DeviceLayout d,
			float pxOff,
			float pyOff,
			boolean transmitting
	) {
		int x = Math.round(d.lcdX() + pxOff);
		int y = Math.round(d.lcdY() + pyOff);
		int w = d.lcdWidth();
		int h = d.lcdHeight();

		int lcdBg = !this.isPowered
				? 0xEA141916
				: (!this.antennaExtended ? 0xEA282016 : 0xEA1B2B1F);
		int lcdBorder = (this.invalidFlashTicks > 0)
				? 0xFFD9534F
				: (!this.antennaExtended && this.isPowered ? 0xFFD08C3F : (this.isPowered ? 0xFF4F7A5B : 0xFF333D36));

		graphics.fill(x, y, x + w, y + h, lcdBg);
		graphics.outline(x, y, w, h, lcdBorder);

		int dividerY = y + 30;
		graphics.horizontalLine(x + 3, x + w - 4, dividerY, this.isPowered ? 0x663B5E45 : 0x5529312B);

		boolean effectiveHasPin = this.passcodeModified ? !this.passcodeBuffer.isEmpty() : this.hadInitialPasscode;

		Component pwrText = transmitting
				? Component.translatable("gui.tlouradio.handheld.lcd_tx")
				: Component.translatable(
						this.isPowered ? "gui.tlouradio.handheld.lcd_power_on" : "gui.tlouradio.handheld.lcd_power_off"
				);

		Component rightStatusText;
		int rightStatusColor;
		if (!this.antennaExtended) {
			rightStatusText = Component.translatable("gui.tlouradio.handheld.lcd_no_signal");
			rightStatusColor = this.isPowered ? 0xFFF29F4B : 0xFF8A6744;
		} else if (this.activeNetworkId.isPresent()) {
			rightStatusText = Component.translatable(
					"gui.tlouradio.handheld.lcd_net_paired",
					RadioStateComponent.shortNetworkId(this.activeNetworkId.get()).substring(0, 4)
			);
			rightStatusColor = this.isPowered ? 0xFF74C488 : 0xFF556359;
		} else {
			rightStatusText = Component.translatable("gui.tlouradio.handheld.lcd_net_open");
			rightStatusColor = this.isPowered ? 0xFF74C488 : 0xFF556359;
		}

		Component pinStatusText = Component.translatable(
				effectiveHasPin ? "gui.tlouradio.handheld.lcd_pin_set" : "gui.tlouradio.handheld.lcd_pin_none"
		);

		int primaryInk = !this.isPowered
				? 0xFF6B7D70
				: (!this.antennaExtended ? 0xFFE8B974 : 0xFF9BF0AE);
		int secondaryInk = this.isPowered ? 0xFF74C488 : 0xFF556359;
		int pwrInk = transmitting
				? 0xFFFFAE42
				: (this.isPowered ? 0xFF66EE88 : 0xFFC85A5A);

		// Line 1: Power/TX + Network / NO SIGNAL (ANT)
		graphics.pose().pushMatrix();
		graphics.pose().translate(x + 4, y + 4);
		graphics.pose().scale(0.68F, 0.68F);
		graphics.text(this.font, pwrText, 0, 0, pwrInk, false);
		int netTextWidth = this.font.width(rightStatusText);
		int scaledInnerWidth = (int) ((w - 8) / 0.68F);
		graphics.text(this.font, rightStatusText, Math.max(0, scaledInnerWidth - netTextWidth), 0, rightStatusColor, false);
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

	private void extract3DKeycapLabelsAndHighlights(
			GuiGraphicsExtractor graphics,
			int mouseX,
			int mouseY,
			float pxOff,
			float pyOff
	) {
		// Subtle hover indicator on physical 3D Antenna, Dial Knob, or Side PTT Latch
		if (this.activeDragTarget == DragTarget.ANTENNA || (this.activeDragTarget == DragTarget.NONE && isOverAntenna(mouseX, mouseY))) {
			int ax = Math.round(this.layout.centerX() - 41 + pxOff);
			int ay = Math.round(this.layout.bodyTop() - Mth.lerp(this.animationState.antennaExtension(), 24.0F, 72.0F) + pyOff);
			graphics.centeredText(this.font, this.antennaExtended ? "▼" : "▲", ax, ay - 9, 0xCC9BF0AE);
		} else if (this.activeDragTarget == DragTarget.DIAL_KNOB || (this.activeDragTarget == DragTarget.NONE && isOverDialKnob(mouseX, mouseY))) {
			int dx = Math.round(this.layout.centerX() + 37 + pxOff);
			int dy = Math.round(this.layout.bodyTop() - 28 + pyOff);
			graphics.centeredText(this.font, "◄►", dx, dy, 0xCCF2C97D);
		} else if (this.activeDragTarget == DragTarget.PTT_BUTTON || (this.activeDragTarget == DragTarget.NONE && isOverPttButton(mouseX, mouseY))) {
			int pttX = Math.round(this.layout.bodyLeft() - 24 + pxOff);
			int pttY = Math.round(this.layout.bodyCenterY() - 48 + pyOff);
			graphics.centeredText(this.font, "PTT", pttX, pttY, 0xFFFFAE52);
		}

		// Crisp front-face legends glued onto each protruding 3D keycap cube
		for (KeySpec spec : this.keySpecs) {
			float depression = this.animationState.getKeyDepressionFor(spec.keyId());
			boolean depressed = depression > 0.05F;
			boolean hovered = this.activeDragTarget == DragTarget.NONE && isInsideKey(spec, mouseX, mouseY, pxOff, pyOff);
			boolean dotDisabled = (spec.role() == KeyRole.DOT && this.inputMode == InputMode.PASSCODE);

			// Keycap front face sits slightly forward (-2.58 geo Z -> 1.1x parallax) and sinks inward when pressed
			float depthFactor = 1.10F - (0.16F * depression);
			float keyX = spec.baseX() + (pxOff * depthFactor);
			float keyY = spec.baseY() + (pyOff * depthFactor) + (depression * 1.2F);
			int ix = Math.round(keyX);
			int iy = Math.round(keyY);

			if (hovered && !dotDisabled) {
				graphics.outline(ix, iy, spec.width(), spec.height(), 0x889BF0AE);
			} else if (depressed) {
				graphics.outline(ix, iy, spec.width(), spec.height(), 0xAA8BE8A8);
			} else if (spec.role() == KeyRole.MODE && this.inputMode == InputMode.PASSCODE) {
				graphics.outline(ix, iy, spec.width(), spec.height(), 0x8864B5F6);
			}

			int labelColor = dotDisabled
					? 0x88727A75
					: (depressed ? 0xFFFFFFFF : (hovered ? 0xFFF2FFF6 : 0xFFE6ECE8));

			Component label = spec.label();
			int textW = this.font.width(label);
			float maxInnerW = spec.width() - 4.0F;

			graphics.pose().pushMatrix();
			if (textW > maxInnerW && textW > 0) {
				float scale = maxInnerW / (float) textW;
				graphics.pose().translate(keyX + spec.width() / 2.0F, keyY + spec.height() / 2.0F - (4.0F * scale));
				graphics.pose().scale(scale, scale);
				graphics.text(this.font, label, -textW / 2, 0, labelColor, true);
			} else {
				graphics.pose().translate(keyX + (spec.width() - textW) / 2.0F, keyY + (spec.height() - 8) / 2.0F);
				graphics.text(this.font, label, 0, 0, labelColor, true);
			}
			graphics.pose().popMatrix();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
