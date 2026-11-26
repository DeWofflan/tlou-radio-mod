package com.tlouradio.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.tlouradio.client.RadioClientInputHandler;
import com.tlouradio.client.RadioClientState;
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
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class HandheldRadioScreen extends Screen {
	private static final long DIAL_SYNC_DEBOUNCE_MS = 150L;
	private static final double DIAL_PIXELS_PER_DETENT = 10.0D;
	private static final double ANTENNA_DRAG_RANGE_PX = 42.0D;

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
			String boneName,
			Component label,
			float geoX0,
			float geoY0,
			float geoX1,
			float geoY1,
			KeyRole role,
			Runnable action
	) {
		float centerGeoX() {
			return (this.geoX0 + this.geoX1) * 0.5F;
		}

		float centerGeoY() {
			return (this.geoY0 + this.geoY1) * 0.5F;
		}

		float widthGeo() {
			return this.geoX1 - this.geoX0;
		}
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

	private DragTarget activeDragTarget = DragTarget.NONE;
	private double antennaDragStartMouseY = 0.0D;
	private float antennaDragStartRatio = 1.0F;
	private double dialDragResidualPx = 0.0D;
	private boolean pendingDialSync = false;
	private long lastDialStepTimeMs = 0L;

	private final List<KeySpec> keySpecs = new ArrayList<>(14);
	private final RadioInspectionTransition transition = new RadioInspectionTransition();
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

		this.initiallyPaired = initialState.networkId().isPresent();
		this.hadInitialPasscode = !initialState.passcode().isEmpty();
		this.preservedUnmodifiedFreePasscode = this.initiallyPaired ? "" : initialState.passcode();

		this.frequencyBuffer = initialState.frequency();

		this.animationState.setTargetAntennaExtended(this.antennaExtended);
		this.animationState.setDraggingAntenna(false, this.antennaExtended ? 1.0F : 0.0F);
		this.animationState.setInitialDialAngle(
				HandheldRadioGeoItemRenderer.computeDialAngleRad(initialState.frequency()) * Mth.RAD_TO_DEG
		);

		buildStatic3DKeySpecs();
	}

	@Override
	protected void init() {
		this.layout = HandheldRadioModelRenderer.computeLayout(this.width, this.height);
	}

	private void buildStatic3DKeySpecs() {
		this.keySpecs.clear();

		float dW = 1.45F;
		float fW = 2.35F;
		float rH = 1.68F;

		float c0 = -3.65F;
		float c1 = -2.00F;
		float c2 = -0.35F;
		float c3 = 1.30F;

		float r0 = -1.63F;
		float r1 = -3.57F;
		float r2 = -5.51F;
		float r3 = -7.45F;

		addKey("1", "key_1", Component.literal("1"), c0, r0, dW, rH, KeyRole.DIGIT, () -> handleDigit('1'));
		addKey("2", "key_2", Component.literal("2"), c1, r0, dW, rH, KeyRole.DIGIT, () -> handleDigit('2'));
		addKey("3", "key_3", Component.literal("3"), c2, r0, dW, rH, KeyRole.DIGIT, () -> handleDigit('3'));
		addKey("MODE", "key_mode", Component.translatable("gui.tlouradio.handheld.key_mode"), c3, r0, fW, rH, KeyRole.MODE, this::toggleInputMode);

		addKey("4", "key_4", Component.literal("4"), c0, r1, dW, rH, KeyRole.DIGIT, () -> handleDigit('4'));
		addKey("5", "key_5", Component.literal("5"), c1, r1, dW, rH, KeyRole.DIGIT, () -> handleDigit('5'));
		addKey("6", "key_6", Component.literal("6"), c2, r1, dW, rH, KeyRole.DIGIT, () -> handleDigit('6'));
		addKey("DEL", "key_del", Component.translatable("gui.tlouradio.handheld.key_delete"), c3, r1, fW, rH, KeyRole.DELETE, this::handleDelete);

		addKey("7", "key_7", Component.literal("7"), c0, r2, dW, rH, KeyRole.DIGIT, () -> handleDigit('7'));
		addKey("8", "key_8", Component.literal("8"), c1, r2, dW, rH, KeyRole.DIGIT, () -> handleDigit('8'));
		addKey("9", "key_9", Component.literal("9"), c2, r2, dW, rH, KeyRole.DIGIT, () -> handleDigit('9'));
		addKey("ENT", "key_ent", Component.translatable("gui.tlouradio.handheld.key_enter"), c3, r3, fW, 3.62F, KeyRole.ENTER, this::handleSubmit);

		addKey("0", "key_0", Component.literal("0"), c0, r3, 3.10F, rH, KeyRole.DIGIT, () -> handleDigit('0'));
		addKey(".", "key_dot", Component.literal("."), c2, r3, dW, rH, KeyRole.DOT, this::handleDot);
	}

	private void addKey(
			String keyId,
			String boneName,
			Component label,
			float x0,
			float y0,
			float w,
			float h,
			KeyRole role,
			Runnable action
	) {
		this.keySpecs.add(new KeySpec(keyId, boneName, label, x0, y0, x0 + w, y0 + h, role, action));
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
			forceCloseImmediately();
			return;
		}

		ItemStack heldStack = this.minecraft.player.getItemInHand(this.hand);
		if (heldStack.isEmpty() || !(heldStack.getItem() instanceof HandheldRadioItem)) {
			forceCloseImmediately();
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

		boolean pttHeld = !this.transition.isClosing() && this.activeDragTarget == DragTarget.PTT_BUTTON;
		this.animationState.setPttPressed(pttHeld);
	}

	private void initiateSmoothClose() {
		if (this.transition.isClosing()) {
			return;
		}
		flushPendingInteractionsBeforeClose();
		playMechanicalClick(0.92F, 0.16F);
		this.transition.startClosing();
	}

	private void forceCloseImmediately() {
		flushPendingInteractionsBeforeClose();
		RadioInspectionTransition.resetGlobalFovImmediately();
		super.onClose();
	}

	@Override
	public void onClose() {
		initiateSmoothClose();
	}

	@Override
	public void removed() {
		flushPendingInteractionsBeforeClose();
		RadioInspectionTransition.resetGlobalFovImmediately();
		super.removed();
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
		if (forceNow || elapsed >= DIAL_SYNC_DEBOUNCE_MS) {
			this.pendingDialSync = false;
			if (RadioStateComponent.isValidFrequency(this.activeSavedFrequency)
					&& ClientPlayNetworking.canSend(StepRadioDialPayload.TYPE)) {
				ClientPlayNetworking.send(new StepRadioDialPayload(this.activeSavedFrequency, this.hand));
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.transition.isClosing()) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.layout != null) {
			double mx = event.x();
			double my = event.y();

			if (getAntennaHitbox().contains(mx, my)) {
				this.activeDragTarget = DragTarget.ANTENNA;
				this.antennaDragStartMouseY = my;
				this.antennaDragStartRatio = this.animationState.antennaExtension();
				playMechanicalClick(1.25F, 0.20F);
				return true;
			}

			if (getDialKnobHitbox().contains(mx, my)) {
				this.activeDragTarget = DragTarget.DIAL_KNOB;
				this.dialDragResidualPx = 0.0D;
				playMechanicalClick(1.45F, 0.22F);
				return true;
			}

			if (getPttButtonHitbox().contains(mx, my)) {
				this.activeDragTarget = DragTarget.PTT_BUTTON;
				this.animationState.setPttPressed(true);
				if (this.isPowered && this.antennaExtended) {
					RadioClientInputHandler.setScreenPttActive(true);
					playMechanicalClick(0.95F, 0.32F);
				} else {
					this.invalidFlashTicks = 8;
					playTactileBeep(0.55F, 0.26F);
				}
				return true;
			}

			for (KeySpec spec : this.keySpecs) {
				if (getKeyHitbox(spec).contains(mx, my)) {
					spec.action().run();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.transition.isClosing()) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.activeDragTarget != DragTarget.NONE) {
			if (this.activeDragTarget == DragTarget.ANTENNA) {
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
				int detents = (int) (this.dialDragResidualPx / DIAL_PIXELS_PER_DETENT);
				if (detents != 0) {
					this.dialDragResidualPx -= detents * DIAL_PIXELS_PER_DETENT;
					int tenthsPerDetent = event.hasShiftDown() ? 1 : 10;
					applyDialFrequencyTenths(detents * tenthsPerDetent, detents);
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
		if (this.transition.isClosing()) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.activeDragTarget != DragTarget.NONE) {
			DragTarget released = this.activeDragTarget;
			this.activeDragTarget = DragTarget.NONE;

			if (released == DragTarget.ANTENNA) {
				this.animationState.setDraggingAntenna(false, this.animationState.antennaExtension());
				this.animationState.setTargetAntennaExtended(this.antennaExtended);
			} else if (released == DragTarget.DIAL_KNOB) {
				checkDialDebounceFlush(true);
			} else if (released == DragTarget.PTT_BUTTON) {
				this.animationState.setPttPressed(false);
				RadioClientInputHandler.releaseScreenPtt();
				playMechanicalClick(1.18F, 0.22F);
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.transition.isClosing()) {
			return true;
		}
		if (scrollY != 0.0D && this.layout != null && getDialKnobHitbox().contains(mouseX, mouseY)) {
			int detents = scrollY > 0.0D ? 1 : -1;
			int tenthsPerDetent = Minecraft.getInstance().hasShiftDown() ? 1 : 10;
			applyDialFrequencyTenths(detents * tenthsPerDetent, detents);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private void applyDialFrequencyTenths(int deltaTenths, int detentCount) {
		String baseFreq = RadioStateComponent.isValidFrequency(this.frequencyBuffer)
				? this.frequencyBuffer
				: this.activeSavedFrequency;
		String nextFreq = RadioStateComponent.stepFrequencyTenths(baseFreq, deltaTenths);
		if (nextFreq.equals(this.activeSavedFrequency) && nextFreq.equals(this.frequencyBuffer)) {
			playMechanicalClick(0.65F, 0.18F);
			return;
		}

		this.activeSavedFrequency = nextFreq;
		this.frequencyBuffer = nextFreq;
		this.activeNetworkId = Optional.empty();
		this.inputMode = InputMode.FREQUENCY;

		this.animationState.stepDialAngle(detentCount * 18.0F);
		float clickPitch = deltaTenths > 0 ? 1.62F : 1.38F;
		playMechanicalClick(clickPitch, 0.28F);

		this.pendingDialSync = true;
		this.lastDialStepTimeMs = System.currentTimeMillis();
	}

	private HandheldRadioModelRenderer.ProjectedBounds getAntennaHitbox() {
		float maxTopY = Mth.lerp(this.animationState.antennaExtension(), 11.9F, 15.9F);
		return HandheldRadioModelRenderer.projectGeoBoxToScreen(
				this.layout, -3.8F, 8.8F, -0.9F, -1.9F, maxTopY, 0.9F
		);
	}

	private HandheldRadioModelRenderer.ProjectedBounds getDialKnobHitbox() {
		return HandheldRadioModelRenderer.projectGeoBoxToScreen(
				this.layout, 1.7F, 8.7F, -1.0F, 3.6F, 10.6F, 1.0F
		);
	}

	private HandheldRadioModelRenderer.ProjectedBounds getPttButtonHitbox() {
		return HandheldRadioModelRenderer.projectGeoBoxToScreen(
				this.layout, -5.4F, 1.2F, -1.2F, -4.1F, 5.8F, 1.2F
		);
	}

	private HandheldRadioModelRenderer.ProjectedBounds getKeyHitbox(KeySpec spec) {
		return HandheldRadioModelRenderer.projectGeoBoxToScreen(
				this.layout, spec.geoX0(), spec.geoY0(), -2.35F, spec.geoX1(), spec.geoY1(), -1.85F
		);
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
			this.animationState.triggerKeyPress(keyId);
			playTactileBeep(pitch, 0.35F);
		} else {
			if (!this.passcodeModified) {
				this.passcodeModified = true;
				this.passcodeBuffer = keyId;
				this.animationState.triggerKeyPress(keyId);
				playTactileBeep(pitch, 0.35F);
				return;
			}
			if (this.passcodeBuffer.length() >= RadioStateComponent.MAX_PASSCODE_LENGTH) {
				rejectKeyPress(keyId);
				return;
			}
			this.passcodeBuffer = this.passcodeBuffer + digit;
			this.animationState.triggerKeyPress(keyId);
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
		this.animationState.triggerKeyPress(".");
		playTactileBeep(1.62F, 0.32F);
	}

	private void handleDelete() {
		if (this.inputMode == InputMode.FREQUENCY) {
			if (this.frequencyBuffer.isEmpty()) {
				rejectKeyPress("DEL");
				return;
			}
			this.frequencyBuffer = this.frequencyBuffer.substring(0, this.frequencyBuffer.length() - 1);
			this.animationState.triggerKeyPress("DEL");
			playTactileBeep(0.88F, 0.34F);
		} else {
			if (!this.passcodeModified) {
				if (this.hadInitialPasscode) {
					this.passcodeModified = true;
					this.passcodeBuffer = "";
					this.animationState.triggerKeyPress("DEL");
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
			this.animationState.triggerKeyPress("DEL");
			playTactileBeep(0.88F, 0.34F);
		}
	}

	private void toggleInputMode() {
		this.inputMode = (this.inputMode == InputMode.FREQUENCY) ? InputMode.PASSCODE : InputMode.FREQUENCY;
		float pitch = (this.inputMode == InputMode.PASSCODE) ? 1.36F : 1.50F;
		this.animationState.triggerKeyPress("MODE");
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

		this.pendingDialSync = false;
		this.animationState.triggerKeyPress("ENT");
		playTactileBeep(1.78F, 0.42F);

		if (ClientPlayNetworking.canSend(UpdateHandheldRadioPayload.TYPE)) {
			ClientPlayNetworking.send(new UpdateHandheldRadioPayload(
					targetFreq,
					targetPass,
					this.antennaExtended,
					this.hand
			));
		}
		initiateSmoothClose();
	}

	private void rejectKeyPress(String keyId) {
		this.animationState.triggerKeyPress(keyId);
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
			handleSubmit();
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
		int topAlpha = Math.round(0x28 * focus);
		int botAlpha = Math.round(0x64 * focus);
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
			this.layout = HandheldRadioModelRenderer.computeLayout(this.width, this.height);
		}

		checkDialDebounceFlush(false);
		this.animationState.updateFrame();

		float focus = this.transition.easedProgress();
		boolean transmitting = !this.transition.isClosing()
				&& RadioClientInputHandler.isPttTransmitting()
				&& this.isPowered
				&& this.antennaExtended;

		float handSideSign = resolveHandSideSign();
		HandheldRadioModelRenderer.LcdContent lcdContent = buildLcdContent(transmitting);

		HandheldRadioModelRenderer.extractModel(
				graphics,
				this.layout,
				this.isPowered,
				transmitting,
				handSideSign,
				focus,
				lcdContent,
				this.animationState
		);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		// Crisp flat 2D screen-aligned font over each physical 3D keycap face
		extractFlat2DKeyLabels(graphics, mouseX, mouseY, focus, handSideSign);

		if (focus > 0.65F && !this.transition.isClosing()) {
			extractInteractiveHoverArrows(graphics, mouseX, mouseY);
			extractSubtleControlHints(graphics, focus);
		}
	}

	private float resolveHandSideSign() {
		if (this.minecraft.player == null) {
			return 1.0F;
		}
		HumanoidArm mainArm = this.minecraft.player.getMainArm();
		HumanoidArm usedArm = (this.hand == InteractionHand.MAIN_HAND) ? mainArm : mainArm.getOpposite();
		return usedArm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
	}

	private HandheldRadioModelRenderer.LcdContent buildLcdContent(boolean transmitting) {
		float rxIntensity = (this.isPowered && this.antennaExtended && !transmitting)
				? RadioClientState.getRxIntensity()
				: 0.0F;
		boolean receiving = rxIntensity > 0.05F;
		float rxPulse = receiving
				? (0.5F + 0.5F * Mth.sin((System.currentTimeMillis() % 1000L) / 1000.0F * Mth.TWO_PI * 3.0F))
				: 0.0F;

		String pwrStr;
		if (transmitting) {
			pwrStr = Component.translatable("gui.tlouradio.handheld.lcd_tx").getString();
		} else if (receiving) {
			pwrStr = Component.translatable("gui.tlouradio.handheld.lcd_rx").getString();
		} else {
			pwrStr = Component.translatable(
					this.isPowered ? "gui.tlouradio.handheld.lcd_power_on" : "gui.tlouradio.handheld.lcd_power_off"
			).getString();
		}

		String rightStatus;
		int rightColor;
		if (!this.antennaExtended) {
			rightStatus = Component.translatable("gui.tlouradio.handheld.lcd_no_signal").getString();
			rightColor = this.isPowered ? 0xFFF29F4B : 0xFF8A6744;
		} else if (this.activeNetworkId.isPresent()) {
			rightStatus = Component.translatable(
					"gui.tlouradio.handheld.lcd_net_paired",
					RadioStateComponent.shortNetworkId(this.activeNetworkId.get()).substring(0, 4)
			).getString();
			rightColor = receiving ? (rxPulse > 0.5F ? 0xFF7CFFE0 : 0xFF4ADCB8) : (this.isPowered ? 0xFF74C488 : 0xFF556359);
		} else {
			rightStatus = Component.translatable("gui.tlouradio.handheld.lcd_net_open").getString();
			rightColor = receiving ? (rxPulse > 0.5F ? 0xFF7CFFE0 : 0xFF4ADCB8) : (this.isPowered ? 0xFF74C488 : 0xFF556359);
		}

		int primaryInk = !this.isPowered
				? 0xFF6B7D70
				: (!this.antennaExtended ? 0xFFE8B974 : (receiving && rxPulse > 0.5F ? 0xFFB8FFF0 : 0xFF9BF0AE));
		int pwrInk;
		if (transmitting) {
			pwrInk = 0xFFFFAE42;
		} else if (receiving) {
			pwrInk = rxPulse > 0.5F ? 0xFF88FFE4 : 0xFF3EE0B8;
		} else {
			pwrInk = this.isPowered ? 0xFF66EE88 : 0xFFC85A5A;
		}

		boolean blinkCursor = (System.currentTimeMillis() / 450L) % 2L == 0L;
		String cursor = blinkCursor ? "_" : "";
		String bufferLine;
		int bufferColor;

		if (this.inputMode == InputMode.FREQUENCY) {
			boolean freqValid = RadioStateComponent.isValidFrequency(this.frequencyBuffer);
			bufferLine = Component.translatable(
					"gui.tlouradio.handheld.lcd_buf_freq",
					this.frequencyBuffer + cursor
			).getString();
			bufferColor = (this.invalidFlashTicks > 0 || !freqValid) ? 0xFFF0B45A : 0xFFB8FFD4;
		} else {
			if (!this.passcodeModified) {
				bufferLine = this.hadInitialPasscode
						? Component.translatable("gui.tlouradio.handheld.lcd_buf_pin_masked").getString()
						: Component.translatable("gui.tlouradio.handheld.lcd_buf_pin", cursor).getString();
				bufferColor = 0xFF8CD8EE;
			} else if (this.passcodeBuffer.isEmpty()) {
				bufferLine = Component.translatable("gui.tlouradio.handheld.lcd_buf_pin", cursor).getString();
				bufferColor = 0xFF8CD8EE;
			} else {
				bufferLine = Component.translatable(
						"gui.tlouradio.handheld.lcd_buf_pin",
						this.passcodeBuffer + cursor
				).getString();
				bufferColor = 0xFFAEEBFF;
			}
		}

		String freqLabel = Component.translatable(
				"gui.tlouradio.handheld.lcd_freq_unit",
				this.activeSavedFrequency
		).getString();

		return new HandheldRadioModelRenderer.LcdContent(
				pwrStr,
				pwrInk,
				rightStatus,
				rightColor,
				freqLabel,
				primaryInk,
				bufferLine,
				bufferColor
		);
	}

	private void extractFlat2DKeyLabels(
			GuiGraphicsExtractor graphics,
			int mouseX,
			int mouseY,
			float focus,
			float handSideSign
	) {
		if (focus < 0.12F) {
			return;
		}
		boolean canHover = !this.transition.isClosing() && this.activeDragTarget == DragTarget.NONE;
		int alpha = Math.round(Mth.clamp((focus - 0.12F) / 0.88F, 0.0F, 1.0F) * 255.0F);
		if (alpha <= 4) {
			return;
		}

		float pxPerGeo = this.layout.modelScale() / 16.0F;

		for (KeySpec spec : this.keySpecs) {
			boolean dotDisabled = (spec.role() == KeyRole.DOT && this.inputMode == InputMode.PASSCODE);
			float depth = this.animationState.getKeyDepressionFor(spec.keyId());
			boolean depressed = depth > 0.05F;
			boolean hovered = canHover && !dotDisabled && getKeyHitbox(spec).contains(mouseX, mouseY);

			int rgb;
			if (dotDisabled) {
				rgb = 0x6D7670;
			} else if (depressed) {
				rgb = 0xA8FFBE;
			} else if (hovered) {
				rgb = 0xFFFFFF;
			} else if (spec.role() == KeyRole.MODE && this.inputMode == InputMode.PASSCODE) {
				rgb = 0xB6E3FF;
			} else {
				rgb = 0xF0F4F1;
			}
			int argb = (alpha << 24) | rgb;

			float frontGeoZ = -2.30F + (depressed ? (0.52F * depth) : 0.0F);
			Vector2f screenPos = HandheldRadioModelRenderer.projectGeoPointToScreen(
					this.layout,
					spec.centerGeoX(),
					spec.centerGeoY(),
					frontGeoZ,
					focus,
					handSideSign
			);

			Component label = spec.label();
			int tw = Math.max(1, this.font.width(label));
			float maxPxW = Math.max(8.0F, (spec.widthGeo() - 0.22F) * pxPerGeo * 0.96F);
			float fitScale = Math.min(1.0F, maxPxW / (float) tw);

			if (fitScale >= 0.99F) {
				graphics.centeredText(
						this.font,
						label,
						Math.round(screenPos.x),
						Math.round(screenPos.y) - 4,
						argb
				);
			} else {
				graphics.pose().pushMatrix();
				graphics.pose().translate(screenPos.x, screenPos.y);
				graphics.pose().scale(fitScale, fitScale);
				graphics.text(this.font, label, -tw / 2, -4, argb, true);
				graphics.pose().popMatrix();
			}
		}
	}

	private void extractInteractiveHoverArrows(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		HandheldRadioModelRenderer.ProjectedBounds antBox = getAntennaHitbox();
		HandheldRadioModelRenderer.ProjectedBounds dialBox = getDialKnobHitbox();

		if (this.activeDragTarget == DragTarget.ANTENNA
				|| (this.activeDragTarget == DragTarget.NONE && antBox.contains(mouseX, mouseY))) {
			HandheldRadioModelRenderer.extractAntennaHoverArrows(graphics, antBox, this.antennaExtended);
		} else if (this.activeDragTarget == DragTarget.DIAL_KNOB
				|| (this.activeDragTarget == DragTarget.NONE && dialBox.contains(mouseX, mouseY))) {
			HandheldRadioModelRenderer.extractDialHoverArrows(graphics, dialBox);
		}
	}

	private void extractSubtleControlHints(GuiGraphicsExtractor graphics, float focus) {
		int alpha = Math.round(0xB4 * ((focus - 0.65F) / 0.35F));
		int bottomBodyPx = Math.round(this.layout.originY() + (8.6F / 16.0F) * this.layout.modelScale()) + 7;
		int hintY = Math.min(this.height - 11, bottomBodyPx);

		Component hint = Component.translatable("gui.tlouradio.handheld.hint_controls");
		int tw = this.font.width(hint);
		float scale = 0.85F;
		graphics.pose().pushMatrix();
		graphics.pose().translate(this.layout.centerX(), hintY);
		graphics.pose().scale(scale, scale);
		graphics.text(this.font, hint, -tw / 2, 0, (alpha << 24) | 0xC6D0CA, true);
		graphics.pose().popMatrix();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
