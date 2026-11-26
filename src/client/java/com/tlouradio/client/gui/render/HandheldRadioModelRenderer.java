package com.tlouradio.client.gui.render;

import com.geckolib.model.DefaultedItemGeoModel;
import com.geckolib.renderer.GeoObjectRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tlouradio.TlouRadioMod;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.item.ModItems;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;

import java.util.Objects;

/**
 * Renders the actual GeckoLib 5 {@code handheld_radio.geo.json} 3D model inside
 * {@code HandheldRadioScreen} using Minecraft 26.3's {@link PictureInPictureRenderer} pipeline,
 * with live ±6° mouse parallax, 3D physical keycap plunger depression, draggable telescopic
 * antenna travel, rotary {@code dial_knob}, and pressable side {@code ptt_button}.
 */
public final class HandheldRadioModelRenderer {
	public static final float MAX_PARALLAX_DEG = 6.0F;

	private static final int BODY_WIDTH_PX = 124;
	private static final int BODY_HEIGHT_PX = 184;
	private static final int PIP_HALF_WIDTH_PX = 96;
	private static final int PIP_TOP_EXTENT_PX = 176;
	private static final int PIP_BOTTOM_EXTENT_PX = 108;
	private static final float PIP_ORIGIN_Y_RATIO =
			(float) PIP_TOP_EXTENT_PX / (float) (PIP_TOP_EXTENT_PX + PIP_BOTTOM_EXTENT_PX);
	private static final float PIP_MODEL_SCALE = 160.0F;
	private static final int FULL_BRIGHT_LIGHT = 15728880;
	private static final float FRONT_SURFACE_Z_PX = 23.5F;

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
		int bodyCenterY = Mth.clamp(screenHeight / 2 + 22, 122, Math.max(122, screenHeight - 98));

		int bodyLeft = centerX - (BODY_WIDTH_PX / 2);
		int bodyTop = bodyCenterY - (BODY_HEIGHT_PX / 2);

		int lcdWidth = 106;
		int lcdHeight = 46;
		int lcdX = centerX - (lcdWidth / 2);
		int lcdY = bodyTop + 18;

		int keypadWidth = 106;
		int keypadHeight = 97;
		int keypadX = centerX - (keypadWidth / 2);
		int keypadY = bodyTop + 73;

		int pipX0 = centerX - PIP_HALF_WIDTH_PX;
		int pipX1 = centerX + PIP_HALF_WIDTH_PX;
		int pipY0 = bodyCenterY - PIP_TOP_EXTENT_PX;
		int pipY1 = bodyCenterY + PIP_BOTTOM_EXTENT_PX;

		return new DeviceLayout(
				centerX,
				bodyCenterY,
				bodyLeft,
				bodyTop,
				BODY_WIDTH_PX,
				BODY_HEIGHT_PX,
				lcdX,
				lcdY,
				lcdWidth,
				lcdHeight,
				keypadX,
				keypadY,
				keypadWidth,
				keypadHeight,
				pipX0,
				pipY0,
				pipX1,
				pipY1
		);
	}

	public static float computeParallaxOffsetX(AnimationState animState) {
		return -Mth.sin(animState.parallaxYawDeg() * Mth.DEG_TO_RAD) * FRONT_SURFACE_Z_PX;
	}

	public static float computeParallaxOffsetY(AnimationState animState) {
		return Mth.sin(animState.parallaxPitchDeg() * Mth.DEG_TO_RAD) * FRONT_SURFACE_Z_PX;
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
			AnimationState animationState
	) {
		ScreenRectangle scissor = graphics.scissorStack.peek();
		float qDial = Math.round(animationState.dialKnobAngleDeg() * 2.0F) / 2.0F;
		float qKeyDepth = Math.round(animationState.keyPressDepth() * 25.0F) / 25.0F;
		float qPttDepth = Math.round(animationState.pttPressDepth() * 25.0F) / 25.0F;
		float qAntExt = Math.round(animationState.antennaExtension() * 40.0F) / 40.0F;
		float qPitch = Math.round(animationState.parallaxPitchDeg() * 10.0F) / 10.0F;
		float qYaw = Math.round(animationState.parallaxYawDeg() * 10.0F) / 10.0F;
		float swayPhase = isTransmitting ? ((System.currentTimeMillis() % 4000L) / 1000.0F) : 0.0F;
		float qSway = Math.round(swayPhase * 20.0F) / 20.0F;

		String depressedBone = qKeyDepth > 0.01F ? mapKeyIdToBoneName(animationState.depressedKeyId()) : "";

		graphics.guiRenderState.addPicturesInPictureState(new HandheldRadioRenderState(
				isPowered,
				isTransmitting,
				qAntExt,
				qDial,
				qPttDepth,
				depressedBone,
				qKeyDepth,
				qPitch,
				qYaw,
				qSway,
				layout.pipX0(),
				layout.pipY0(),
				layout.pipX1(),
				layout.pipY1(),
				PIP_MODEL_SCALE,
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

	/**
	 * Mutable interactive 3D state driving the GeckoLib inspection model.
	 */
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
		private float parallaxPitchDeg = 0.0F;
		private float parallaxYawDeg = 0.0F;
		private long lastUpdateNanos = System.nanoTime();

		public void triggerKeyPress(String keyId, float dialStepDeg) {
			this.depressedKeyId = Objects.requireNonNullElse(keyId, "");
			this.keyPressDepth = 1.0F;
			if (dialStepDeg != 0.0F) {
				this.targetDialKnobAngleDeg = Mth.wrapDegrees(this.targetDialKnobAngleDeg + dialStepDeg);
			}
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

		public void updateFrame(int mouseX, int mouseY, DeviceLayout layout, int screenWidth, int screenHeight) {
			long now = System.nanoTime();
			float dt = Mth.clamp((now - this.lastUpdateNanos) / 1_000_000_000.0F, 0.0F, 0.1F);
			this.lastUpdateNanos = now;

			// ±6° mouse parallax relative to device center
			float normX = screenWidth > 0
					? Mth.clamp((mouseX - layout.centerX()) / (screenWidth * 0.38F), -1.0F, 1.0F)
					: 0.0F;
			float normY = screenHeight > 0
					? Mth.clamp((mouseY - layout.bodyCenterY()) / (screenHeight * 0.38F), -1.0F, 1.0F)
					: 0.0F;
			float targetYaw = -normX * MAX_PARALLAX_DEG;
			float targetPitch = normY * MAX_PARALLAX_DEG;
			float parallaxBlend = Math.min(1.0F, dt * 12.0F);
			this.parallaxYawDeg += (targetYaw - this.parallaxYawDeg) * parallaxBlend;
			this.parallaxPitchDeg += (targetPitch - this.parallaxPitchDeg) * parallaxBlend;

			if (this.keyPressDepth > 0.0F) {
				this.keyPressDepth = Math.max(0.0F, this.keyPressDepth - dt * 7.2F);
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

		public float parallaxPitchDeg() {
			return this.parallaxPitchDeg;
		}

		public float parallaxYawDeg() {
			return this.parallaxYawDeg;
		}
	}

	public record DeviceLayout(
			int centerX,
			int bodyCenterY,
			int bodyLeft,
			int bodyTop,
			int bodyWidth,
			int bodyHeight,
			int lcdX,
			int lcdY,
			int lcdWidth,
			int lcdHeight,
			int keypadX,
			int keypadY,
			int keypadWidth,
			int keypadHeight,
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
			float parallaxPitchDeg,
			float parallaxYawDeg,
			float swayPhase,
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
			// Keep model centered at (0, 0, 0) in PiP space instead of shifting by (0.5, 0.51, 0.5)
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

			Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);

			// Orient GeckoLib (+Y up, -Z front) cleanly into GUI PiP space with ±6° live mouse parallax
			poseStack.rotateDegrees(Axis.ZP, 180.0F);
			poseStack.rotateDegrees(Axis.XP, state.parallaxPitchDeg());
			poseStack.rotateDegrees(Axis.YP, state.parallaxYawDeg());

			RenderPassInfo.BoneUpdater<GeoRenderState> boneUpdater = (renderPassInfo, snapshots) -> {
				snapshots.ifPresent("led_on", bone -> bone.skipRender(!state.isPowered()));
				snapshots.ifPresent("led_off", bone -> bone.skipRender(state.isPowered()));

				snapshots.ifPresent("dial_knob", bone ->
						bone.setRotY(state.dialKnobAngleDeg() * Mth.DEG_TO_RAD)
				);

				snapshots.ifPresent("ptt_button", bone ->
						bone.setTranslateX(-0.52F * state.pttPressDepth())
				);

				snapshots.ifPresent("antenna", bone -> {
					float ext = Mth.clamp(state.antennaExtension(), 0.0F, 1.0F);
					bone.setTranslateY(-4.4F * (1.0F - ext));
					bone.setScaleY(Mth.lerp(ext, 0.30F, 1.0F));
					if (state.isTransmitting() && ext > 0.6F) {
						float sway = Mth.sin(state.swayPhase() * Mth.TWO_PI) * 1.8F * Mth.DEG_TO_RAD;
						bone.setRotZ(sway);
					}
				});

				for (String keyBone : ALL_KEY_BONES) {
					boolean pressed = keyBone.equals(state.depressedKeyBone()) && state.keyPressDepth() > 0.01F;
					snapshots.ifPresent(keyBone, bone ->
							bone.setTranslateZ(pressed ? (0.42F * state.keyPressDepth()) : 0.0F)
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
		}
	}
}
