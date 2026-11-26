package com.tlouradio.client.gui.render;

import com.geckolib.model.DefaultedItemGeoModel;
import com.geckolib.renderer.GeoObjectRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tlouradio.TlouRadioMod;
import com.tlouradio.client.RadioClientState;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.item.ModItems;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.Objects;

public final class HandheldRadioModelRenderer {
	public static final float ISOMETRIC_PITCH_DEG = 5.0F;
	public static final float ISOMETRIC_YAW_DEG = -13.0F;

	private static final int PIP_HALF_WIDTH_PX = 156;
	private static final float BASE_PIP_MODEL_SCALE = 178.0F;
	private static final int FULL_BRIGHT_LIGHT = 15728880;
	// Balances extended antenna headroom (15.9 geo units up) vs chassis bottom (8.5 geo units down)
	private static final float PIP_ORIGIN_Y_RATIO = 0.635F;

	private static final Matrix4f STATIC_ISOMETRIC_MATRIX = new Matrix4f()
			.rotateZ(Mth.PI)
			.rotateX(ISOMETRIC_PITCH_DEG * Mth.DEG_TO_RAD)
			.rotateY(ISOMETRIC_YAW_DEG * Mth.DEG_TO_RAD);

	private static final String[] ALL_KEY_BONES = {
			"key_0", "key_1", "key_2", "key_3", "key_4",
			"key_5", "key_6", "key_7", "key_8", "key_9",
			"key_dot", "key_mode", "key_del", "key_ent"
	};

	private HandheldRadioModelRenderer() {
	}

	public static void initialize() {
		PictureInPictureRendererRegistry.register(ctx -> new HandheldRadioPipRenderer());
	}

	public static DeviceLayout computeLayout(int screenWidth, int screenHeight) {
		int centerX = screenWidth / 2;
		int pipY0 = 0;
		int pipY1 = Math.max(120, screenHeight);
		int originY = Math.round(pipY1 * PIP_ORIGIN_Y_RATIO);

		// Guarantee extended antenna tip (y=15.8 geo) never clips against the top edge on any GUI scale
		float availableTopPx = originY - 12.0F;
		float maxScaleForTop = availableTopPx / (16.1F / 16.0F);
		float availableBottomPx = (pipY1 - originY) - 24.0F;
		float maxScaleForBottom = availableBottomPx / (8.6F / 16.0F);
		float modelScale = Mth.clamp(
				Math.min(BASE_PIP_MODEL_SCALE, Math.min(maxScaleForTop, maxScaleForBottom)),
				96.0F,
				BASE_PIP_MODEL_SCALE
		);

		int pipX0 = Math.max(0, centerX - PIP_HALF_WIDTH_PX);
		int pipX1 = Math.min(screenWidth, centerX + PIP_HALF_WIDTH_PX);

		return new DeviceLayout(centerX, originY, modelScale, pipX0, pipY0, pipX1, pipY1);
	}

	public static Vector2f projectGeoPointToScreen(
			DeviceLayout layout,
			float geoX,
			float geoY,
			float geoZ,
			float focusProgress,
			float handSideSign
	) {
		float focus = Mth.clamp(focusProgress, 0.0F, 1.0F);
		float invFocus = 1.0F - focus;
		Vector3f pt = new Vector3f(-geoX / 16.0F, geoY / 16.0F, geoZ / 16.0F);

		if (invFocus <= 0.001F) {
			STATIC_ISOMETRIC_MATRIX.transformPosition(pt);
		} else {
			float s = Mth.lerp(focus, 0.82F, 1.0F);
			Matrix4f dyn = new Matrix4f()
					.translate(handSideSign * 0.28F * invFocus, 0.92F * invFocus, 0.0F)
					.scale(s, s, s)
					.rotateZ(Mth.PI)
					.rotateX((ISOMETRIC_PITCH_DEG + (6.0F * invFocus)) * Mth.DEG_TO_RAD)
					.rotateY(ISOMETRIC_YAW_DEG * Mth.DEG_TO_RAD);
			dyn.transformPosition(pt);
		}

		float pipCenterX = (layout.pipX0() + layout.pipX1()) * 0.5F;
		float pipOriginY = layout.pipY0() + (layout.pipY1() - layout.pipY0()) * PIP_ORIGIN_Y_RATIO;
		return new Vector2f(
				pipCenterX + (pt.x * layout.modelScale()),
				pipOriginY + (pt.y * layout.modelScale())
		);
	}

	public static ProjectedBounds projectGeoBoxToScreen(
			DeviceLayout layout,
			float geoX0,
			float geoY0,
			float geoZ0,
			float geoX1,
			float geoY1,
			float geoZ1
	) {
		float minX = Float.POSITIVE_INFINITY;
		float minY = Float.POSITIVE_INFINITY;
		float maxX = Float.NEGATIVE_INFINITY;
		float maxY = Float.NEGATIVE_INFINITY;

		Vector3f tmp = new Vector3f();
		float[] xs = {geoX0, geoX1};
		float[] ys = {geoY0, geoY1};
		float[] zs = {geoZ0, geoZ1};
		float scale = layout.modelScale();
		float pipCenterX = (layout.pipX0() + layout.pipX1()) * 0.5F;
		float pipOriginY = layout.pipY0() + (layout.pipY1() - layout.pipY0()) * PIP_ORIGIN_Y_RATIO;

		for (float gx : xs) {
			for (float gy : ys) {
				for (float gz : zs) {
					tmp.set(-gx / 16.0F, gy / 16.0F, gz / 16.0F);
					STATIC_ISOMETRIC_MATRIX.transformPosition(tmp);
					float sx = pipCenterX + (tmp.x * scale);
					float sy = pipOriginY + (tmp.y * scale);
					if (sx < minX) minX = sx;
					if (sx > maxX) maxX = sx;
					if (sy < minY) minY = sy;
					if (sy > maxY) maxY = sy;
				}
			}
		}
		return new ProjectedBounds(minX, minY, maxX, maxY);
	}

	public static String mapKeyIdToBoneName(String keyId) {
		if (keyId == null || keyId.isEmpty()) {
			return "";
		}
		return switch (keyId) {
			case "." -> "key_dot";
			case "MODE" -> "key_mode";
			case "DEL" -> "key_del";
			case "ENT" -> "key_ent";
			default -> (keyId.length() == 1 && keyId.charAt(0) >= '0' && keyId.charAt(0) <= '9')
					? ("key_" + keyId)
					: "";
		};
	}

	public static void extractModel(
			GuiGraphicsExtractor graphics,
			DeviceLayout layout,
			boolean isPowered,
			boolean isTransmitting,
			float handSideSign,
			float focusProgress,
			LcdContent lcdContent,
			AnimationState animationState
	) {
		ScreenRectangle scissor = graphics.scissorStack.peek();
		float qDial = Math.round(animationState.dialKnobAngleDeg() * 2.0F) / 2.0F;
		float qKeyDepth = Math.round(animationState.keyPressDepth() * 25.0F) / 25.0F;
		float qPttDepth = Math.round(animationState.pttPressDepth() * 25.0F) / 25.0F;
		float qAntExt = Math.round(animationState.antennaExtension() * 40.0F) / 40.0F;
		float qFocus = Math.round(Mth.clamp(focusProgress, 0.0F, 1.0F) * 100.0F) / 100.0F;
		float swayPhase = isTransmitting ? ((System.currentTimeMillis() % 4000L) / 1000.0F) : 0.0F;
		float qSway = Math.round(swayPhase * 20.0F) / 20.0F;
		float rxIntensity = (isPowered && qAntExt > 0.5F) ? RadioClientState.getRxIntensity() : 0.0F;
		float qRx = Math.round(rxIntensity * 20.0F) / 20.0F;
		float rxPhase = qRx > 0.01F ? ((System.currentTimeMillis() % 1000L) / 1000.0F) : 0.0F;
		float qRxPhase = Math.round(rxPhase * 30.0F) / 30.0F;

		String depressedBone = qKeyDepth > 0.01F ? mapKeyIdToBoneName(animationState.depressedKeyId()) : "";

		graphics.guiRenderState.addPicturesInPictureState(new HandheldRadioRenderState(
				isPowered,
				isTransmitting,
				qAntExt,
				qDial,
				qPttDepth,
				depressedBone,
				qKeyDepth,
				handSideSign,
				qFocus,
				qSway,
				qRx,
				qRxPhase,
				lcdContent,
				layout.pipX0(),
				layout.pipY0(),
				layout.pipX1(),
				layout.pipY1(),
				layout.modelScale(),
				scissor,
				PictureInPictureRenderState.getBounds(
						layout.pipX0(),
						layout.pipY0(),
						layout.pipX1(),
						layout.pipY1(),
						scissor
				)
		));
	}

	public record ProjectedBounds(float minX, float minY, float maxX, float maxY) {
		public boolean contains(double mx, double my) {
			return mx >= this.minX && mx <= this.maxX && my >= this.minY && my <= this.maxY;
		}

		public int centerX() {
			return Math.round((this.minX + this.maxX) * 0.5F);
		}

		public int centerY() {
			return Math.round((this.minY + this.maxY) * 0.5F);
		}
	}

	public record LcdContent(
			String topLeft,
			int topLeftColor,
			String topRight,
			int topRightColor,
			String midFrequency,
			int midFrequencyColor,
			String bottomBuffer,
			int bottomColor
	) {
	}

	public static final class AnimationState {
		private String depressedKeyId = "";
		private float keyPressDepth = 0.0F;
		private float dialKnobAngleDeg = 0.0F;
		private float targetDialKnobAngleDeg = 0.0F;
		private float pttPressDepth = 0.0F;
		private float targetPttPressDepth = 0.0F;
		private float antennaExtension = 1.0F;
		private float targetAntennaExtension = 1.0F;
		private boolean draggingAntenna = false;
		private long lastUpdateNanos = System.nanoTime();

		public void triggerKeyPress(String keyId) {
			this.depressedKeyId = Objects.requireNonNullElse(keyId, "");
			this.keyPressDepth = 1.0F;
		}

		public void stepDialAngle(float deltaDeg) {
			this.targetDialKnobAngleDeg = Mth.wrapDegrees(this.targetDialKnobAngleDeg + deltaDeg);
		}

		public void setInitialDialAngle(float deg) {
			this.dialKnobAngleDeg = Mth.wrapDegrees(deg);
			this.targetDialKnobAngleDeg = this.dialKnobAngleDeg;
		}

		public void setPttPressed(boolean active) {
			this.targetPttPressDepth = active ? 1.0F : 0.0F;
		}

		public void setTargetAntennaExtended(boolean extended) {
			if (!this.draggingAntenna) {
				this.targetAntennaExtension = extended ? 1.0F : 0.0F;
			}
		}

		public void setDraggingAntenna(boolean dragging, float liveExtension) {
			this.draggingAntenna = dragging;
			this.antennaExtension = Mth.clamp(liveExtension, 0.0F, 1.0F);
			this.targetAntennaExtension = this.antennaExtension;
		}

		public void updateFrame() {
			long now = System.nanoTime();
			float dt = Mth.clamp((now - this.lastUpdateNanos) / 1_000_000_000.0F, 0.0F, 0.1F);
			this.lastUpdateNanos = now;

			if (this.keyPressDepth > 0.0F) {
				this.keyPressDepth = Math.max(0.0F, this.keyPressDepth - dt * 7.5F);
				if (this.keyPressDepth == 0.0F) {
					this.depressedKeyId = "";
				}
			}

			float pttBlend = Math.min(1.0F, dt * 18.0F);
			this.pttPressDepth += (this.targetPttPressDepth - this.pttPressDepth) * pttBlend;

			if (!this.draggingAntenna) {
				float antBlend = Math.min(1.0F, dt * 14.0F);
				this.antennaExtension += (this.targetAntennaExtension - this.antennaExtension) * antBlend;
			}

			float deltaAngle = Mth.wrapDegrees(this.targetDialKnobAngleDeg - this.dialKnobAngleDeg);
			if (Math.abs(deltaAngle) > 0.1F) {
				float dialBlend = Math.min(1.0F, dt * 18.0F);
				this.dialKnobAngleDeg = Mth.wrapDegrees(this.dialKnobAngleDeg + deltaAngle * dialBlend);
			} else {
				this.dialKnobAngleDeg = this.targetDialKnobAngleDeg;
			}
		}

		public float getKeyDepressionFor(String keyId) {
			if (keyId != null && keyId.equals(this.depressedKeyId)) {
				return this.keyPressDepth;
			}
			return 0.0F;
		}

		public String depressedKeyId() {
			return this.depressedKeyId;
		}

		public float keyPressDepth() {
			return this.keyPressDepth;
		}

		public float dialKnobAngleDeg() {
			return this.dialKnobAngleDeg;
		}

		public float pttPressDepth() {
			return this.pttPressDepth;
		}

		public float antennaExtension() {
			return this.antennaExtension;
		}
	}

	public record DeviceLayout(
			int centerX,
			int originY,
			float modelScale,
			int pipX0,
			int pipY0,
			int pipX1,
			int pipY1
	) {
	}

	public record HandheldRadioRenderState(
			boolean isPowered,
			boolean isTransmitting,
			float antennaExtension,
			float dialKnobAngleDeg,
			float pttPressDepth,
			String depressedKeyBone,
			float keyPressDepth,
			float handSideSign,
			float focusProgress,
			float swayPhase,
			float rxIntensity,
			float rxPhase,
			LcdContent lcdContent,
			int x0,
			int y0,
			int x1,
			int y1,
			float scale,
			ScreenRectangle scissorArea,
			ScreenRectangle bounds
	) implements PictureInPictureRenderState {
	}

	private static final class InspectionGeoObjectRenderer extends GeoObjectRenderer<HandheldRadioItem, Void, GeoRenderState> {
		private InspectionGeoObjectRenderer() {
			super(new DefaultedItemGeoModel<>(TlouRadioMod.id("handheld_radio")));
		}

		@Override
		public void adjustRenderPose(RenderPassInfo<GeoRenderState> renderPassInfo) {
		}

		@Override
		public long getInstanceId(HandheldRadioItem animatable, Void relatedObject) {
			return -4242L;
		}
	}

	private static final class HandheldRadioPipRenderer extends PictureInPictureRenderer<HandheldRadioRenderState> {
		private final InspectionGeoObjectRenderer geoRenderer = new InspectionGeoObjectRenderer();
		private final CameraRenderState dummyCameraState = new CameraRenderState();

		@Override
		public Class<HandheldRadioRenderState> getRenderStateClass() {
			return HandheldRadioRenderState.class;
		}

		@Override
		protected float getTranslateY(int height, int guiScale) {
			return height * PIP_ORIGIN_Y_RATIO;
		}

		@Override
		protected String getTextureLabel() {
			return "tlouradio geckolib handheld inspection";
		}

		@Override
		protected void renderToTexture(
				HandheldRadioRenderState state,
				PoseStack poseStack,
				SubmitNodeCollector submitNodeCollector
		) {
			if (!(ModItems.HANDHELD_RADIO instanceof HandheldRadioItem radioItem)) {
				return;
			}

			Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);

			float focus = Mth.clamp(state.focusProgress(), 0.0F, 1.0F);
			float invFocus = 1.0F - focus;
			if (invFocus > 0.001F) {
				poseStack.translate(state.handSideSign() * 0.28F * invFocus, 0.92F * invFocus, 0.0F);
				float s = Mth.lerp(focus, 0.82F, 1.0F);
				poseStack.scale(s, s, s);
			}

			poseStack.rotateDegrees(Axis.ZP, 180.0F);
			poseStack.rotateDegrees(Axis.XP, ISOMETRIC_PITCH_DEG + (6.0F * invFocus));
			poseStack.rotateDegrees(Axis.YP, ISOMETRIC_YAW_DEG);

			if (state.rxIntensity() > 0.01F) {
				float phaseRad = state.rxPhase() * Mth.TWO_PI * 10.0F;
				float vibX = Mth.sin(phaseRad) * 0.0024F * state.rxIntensity();
				float vibY = Mth.cos(phaseRad * 1.3F) * 0.0018F * state.rxIntensity();
				poseStack.translate(vibX, vibY, 0.0F);
				poseStack.rotateDegrees(Axis.ZP, Mth.sin(phaseRad * 1.15F) * 0.28F * state.rxIntensity());
			}

			RenderPassInfo.BoneUpdater<GeoRenderState> boneUpdater = (renderPassInfo, snapshots) -> {
				boolean showTxLed = state.isPowered() && state.isTransmitting();
				snapshots.ifPresent("led_on", bone -> {
					bone.skipRender(!state.isPowered() || showTxLed);
					if (state.isPowered() && !showTxLed && state.rxIntensity() > 0.01F) {
						float pulse = 0.5F + 0.5F * Mth.sin(state.rxPhase() * Mth.TWO_PI * 3.0F);
						float s = 1.0F + 0.25F * pulse * state.rxIntensity();
						bone.setScaleX(s);
						bone.setScaleY(s);
						bone.setScaleZ(s);
					}
				});
				snapshots.ifPresent("led_tx", bone -> bone.skipRender(!showTxLed));
				snapshots.ifPresent("led_off", bone -> bone.skipRender(state.isPowered()));

				snapshots.ifPresent("dial_knob", bone ->
						bone.setRotY(state.dialKnobAngleDeg() * Mth.DEG_TO_RAD)
				);

				snapshots.ifPresent("ptt_button", bone ->
						bone.setTranslateX(0.55F * state.pttPressDepth())
				);

				float ext = Mth.clamp(state.antennaExtension(), 0.0F, 1.0F);
				snapshots.ifPresent("antenna_seg1", seg1 -> seg1.setTranslateY(-1.80F * (1.0F - ext)));
				snapshots.ifPresent("antenna_seg2", seg2 -> seg2.setTranslateY(-2.20F * (1.0F - ext)));

				snapshots.ifPresent("antenna", bone -> {
					if (state.isTransmitting() && ext > 0.6F) {
						float sway = Mth.sin(state.swayPhase() * Mth.TWO_PI) * 1.5F * Mth.DEG_TO_RAD;
						bone.setRotZ(sway);
					}
				});

				for (String keyBone : ALL_KEY_BONES) {
					boolean pressed = keyBone.equals(state.depressedKeyBone()) && state.keyPressDepth() > 0.01F;
					snapshots.ifPresent(keyBone, bone ->
							bone.setTranslateZ(pressed ? (0.52F * state.keyPressDepth()) : 0.0F)
					);
				}
			};

			this.geoRenderer.performRenderPass(
					radioItem,
					null,
					poseStack,
					submitNodeCollector,
					this.dummyCameraState,
					FULL_BRIGHT_LIGHT,
					0.0F,
					boneUpdater
			);

			Font font = Minecraft.getInstance().font;
			OrderedSubmitNodeCollector textLayer = submitNodeCollector.order(1);
			render3DLcdSurface(poseStack, textLayer, font, state.lcdContent());
		}

		private static void render3DLcdSurface(
				PoseStack poseStack,
				OrderedSubmitNodeCollector textLayer,
				Font font,
				LcdContent lcd
		) {
			if (lcd == null) {
				return;
			}
			final float lcdFrontZ = -1.81F;
			final float leftX = -3.14F;
			final float rightX = 3.14F;

			// Compact LCD typography so the backlit display glass remains clearly visible
			submitAligned3DSurfaceText(
					poseStack, textLayer, font,
					Component.literal(lcd.topLeft()).getVisualOrderText(),
					leftX, 6.84F, lcdFrontZ, 2.55F, 0.43F, lcd.topLeftColor(), false
			);
			submitAligned3DSurfaceText(
					poseStack, textLayer, font,
					Component.literal(lcd.topRight()).getVisualOrderText(),
					rightX, 6.84F, lcdFrontZ, 3.35F, 0.43F, lcd.topRightColor(), true
			);

			submitAligned3DSurfaceText(
					poseStack, textLayer, font,
					Component.literal(lcd.midFrequency()).getVisualOrderText(),
					leftX, 5.92F, lcdFrontZ, 5.60F, 0.54F, lcd.midFrequencyColor(), false
			);

			submitAligned3DSurfaceText(
					poseStack, textLayer, font,
					Component.literal(lcd.bottomBuffer()).getVisualOrderText(),
					leftX, 5.02F, lcdFrontZ, 5.95F, 0.50F, lcd.bottomColor(), false
			);
		}
	}

	public static void submitAligned3DSurfaceText(
			PoseStack poseStack,
			OrderedSubmitNodeCollector textLayer,
			Font font,
			FormattedCharSequence seq,
			float anchorGeoX,
			float centerGeoY,
			float frontGeoZ,
			float maxWidthGeo,
			float desiredHeightGeo,
			int color,
			boolean rightAlign
	) {
		int pixelW = Math.max(1, font.width(seq));
		float pixelToGeo = Math.min(desiredHeightGeo / 8.0F, maxWidthGeo / (float) pixelW);
		float s = pixelToGeo / 16.0F;

		poseStack.pushPose();
		poseStack.translate(-anchorGeoX / 16.0F, centerGeoY / 16.0F, frontGeoZ / 16.0F);
		poseStack.rotateDegrees(Axis.ZP, 180.0F);
		poseStack.scale(s, s, s);
		float drawX = rightAlign ? -pixelW : 0.0F;
		textLayer.submitText(
				poseStack,
				drawX,
				-4.0F,
				seq,
				false,
				Font.DisplayMode.NORMAL,
				FULL_BRIGHT_LIGHT,
				color,
				0,
				0
		);
		poseStack.popPose();
	}

	public static void extractAntennaHoverArrows(
			GuiGraphicsExtractor graphics,
			ProjectedBounds antennaBounds,
			boolean antennaExtended
	) {
		int cx = antennaBounds.centerX();
		int bob = Math.round(Mth.sin((System.currentTimeMillis() % 1100L) / 1100.0F * Mth.TWO_PI) * 1.4F);
		int centerGapY = Math.max(12, Math.round(antennaBounds.minY()) - 7);

		int upColor = antennaExtended ? 0xCCFF8E26 : 0xFFFFA438;
		int downColor = antennaExtended ? 0xFFFFA438 : 0xCCFF8E26;
		int shadow = 0xB0120B05;

		drawUpTriangle(graphics, cx + 1, centerGapY - 5 - bob + 1, shadow);
		drawUpTriangle(graphics, cx, centerGapY - 5 - bob, upColor);

		drawDownTriangle(graphics, cx + 1, centerGapY + 4 + bob + 1, shadow);
		drawDownTriangle(graphics, cx, centerGapY + 4 + bob, downColor);
	}

	public static void extractDialHoverArrows(GuiGraphicsExtractor graphics, ProjectedBounds dialBounds) {
		int cy = dialBounds.centerY();
		int bob = Math.round(Mth.sin((System.currentTimeMillis() % 1100L) / 1100.0F * Mth.TWO_PI) * 1.4F);
		int leftTipX = Math.round(dialBounds.minX()) - 6 - bob;
		int rightTipX = Math.round(dialBounds.maxX()) + 6 + bob;

		int orange = 0xFFFF9E32;
		int shadow = 0xB0120B05;

		drawLeftTriangle(graphics, leftTipX + 1, cy + 1, shadow);
		drawLeftTriangle(graphics, leftTipX, cy, orange);

		drawRightTriangle(graphics, rightTipX + 1, cy + 1, shadow);
		drawRightTriangle(graphics, rightTipX, cy, orange);
	}

	private static void drawUpTriangle(GuiGraphicsExtractor graphics, int cx, int tipY, int color) {
		for (int i = 0; i < 4; i++) {
			graphics.fill(cx - i, tipY + i, cx + i + 1, tipY + i + 1, color);
		}
	}

	private static void drawDownTriangle(GuiGraphicsExtractor graphics, int cx, int tipY, int color) {
		for (int i = 0; i < 4; i++) {
			graphics.fill(cx - i, tipY - i, cx + i + 1, tipY - i + 1, color);
		}
	}

	private static void drawLeftTriangle(GuiGraphicsExtractor graphics, int tipX, int cy, int color) {
		for (int i = 0; i < 4; i++) {
			graphics.fill(tipX + i, cy - i, tipX + i + 1, cy + i + 1, color);
		}
	}

	private static void drawRightTriangle(GuiGraphicsExtractor graphics, int tipX, int cy, int color) {
		for (int i = 0; i < 4; i++) {
			graphics.fill(tipX - i, cy - i, tipX - i + 1, cy + i + 1, color);
		}
	}
}
