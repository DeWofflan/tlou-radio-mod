package com.tlouradio.client.gui.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tlouradio.TlouRadioMod;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.Objects;

/**
 * Modular 3D inspection model renderer for the Handheld Radio screen.
 *
 * <p>Uses Minecraft 26.3's {@link PictureInPictureRenderer} pipeline and exposes bone-aligned
 * tactile state ({@code dial_knob} rotation, {@code ptt_button} travel, {@code led_indicator} power,
 * and depressed keypad button state) so Phase 5's GeckoLib {@code .geo.json} model can plug directly
 * into {@link HandheldRadioPipRenderer#renderToTexture} without altering screen or keypad code.
 */
public final class HandheldRadioModelRenderer {
	public static final Identifier TEXTURE = TlouRadioMod.id("textures/gui/handheld_radio_3d.png");

	private static final int BODY_WIDTH_PX = 124;
	private static final int BODY_HEIGHT_PX = 184;
	private static final int PIP_HALF_WIDTH_PX = 88;
	private static final int PIP_TOP_EXTENT_PX = 148;
	private static final int PIP_BOTTOM_EXTENT_PX = 104;
	private static final float PIP_ORIGIN_Y_RATIO =
			(float) PIP_TOP_EXTENT_PX / (float) (PIP_TOP_EXTENT_PX + PIP_BOTTOM_EXTENT_PX);
	private static final float PIP_MODEL_SCALE = 160.0F;
	private static final int FULL_BRIGHT_LIGHT = 15728880;

	private HandheldRadioModelRenderer() {
	}

	public static void initialize() {
		PictureInPictureRendererRegistry.register(ctx -> new HandheldRadioPipRenderer());
	}

	public static DeviceLayout computeLayout(int screenWidth, int screenHeight) {
		int centerX = screenWidth / 2;
		int bodyCenterY = Mth.clamp(screenHeight / 2 + 16, 114, Math.max(114, screenHeight - 98));

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

	public static void extractModel(
			GuiGraphicsExtractor graphics,
			DeviceLayout layout,
			boolean isPowered,
			AnimationState animationState
	) {
		ScreenRectangle scissor = graphics.scissorStack.peek();
		float quantizedDial = Math.round(animationState.dialKnobAngleDeg() * 2.0F) / 2.0F;
		float quantizedKeyDepth = Math.round(animationState.keyPressDepth() * 20.0F) / 20.0F;
		float quantizedPttDepth = Math.round(animationState.pttPressDepth() * 20.0F) / 20.0F;

		graphics.guiRenderState.addPicturesInPictureState(new HandheldRadioRenderState(
				isPowered,
				quantizedDial,
				quantizedPttDepth,
				quantizedKeyDepth > 0.01F ? animationState.depressedKeyId() : "",
				quantizedKeyDepth,
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

		// Subtle recessed bezel framing on the front face so LCD & keypad sit cleanly inside the 3D chassis
		graphics.fill(
				layout.lcdX() - 2,
				layout.lcdY() - 2,
				layout.lcdX() + layout.lcdWidth() + 2,
				layout.lcdY() + layout.lcdHeight() + 2,
				0xE0101412
		);
		graphics.outline(
				layout.lcdX() - 2,
				layout.lcdY() - 2,
				layout.lcdWidth() + 4,
				layout.lcdHeight() + 4,
				0xFF35403A
		);

		graphics.fill(
				layout.keypadX() - 3,
				layout.keypadY() - 3,
				layout.keypadX() + layout.keypadWidth() + 3,
				layout.keypadY() + layout.keypadHeight() + 3,
				0xCC121714
		);
		graphics.outline(
				layout.keypadX() - 3,
				layout.keypadY() - 3,
				layout.keypadWidth() + 6,
				layout.keypadHeight() + 6,
				0xFF2F3933
		);
	}

	/**
	 * Mutable tactile animation state held by {@code HandheldRadioScreen}.
	 * Ready to drive GeckoLib bone controllers in Phase 5.
	 */
	public static final class AnimationState {
		private String depressedKeyId = "";
		private float keyPressDepth = 0.0F;
		private float dialKnobAngleDeg = 0.0F;
		private float targetDialKnobAngleDeg = 0.0F;
		private float pttPressDepth = 0.0F;
		private long lastUpdateNanos = System.nanoTime();

		public void triggerKeyPress(String keyId, float dialStepDeg) {
			this.depressedKeyId = Objects.requireNonNullElse(keyId, "");
			this.keyPressDepth = 1.0F;
			this.targetDialKnobAngleDeg = Mth.wrapDegrees(this.targetDialKnobAngleDeg + dialStepDeg);
		}

		public void setPttPressed(boolean active) {
			this.pttPressDepth = active ? 1.0F : 0.0F;
		}

		public void updateFrame() {
			long now = System.nanoTime();
			float dtSeconds = Mth.clamp((now - this.lastUpdateNanos) / 1_000_000_000.0F, 0.0F, 0.1F);
			this.lastUpdateNanos = now;

			if (this.keyPressDepth > 0.0F) {
				this.keyPressDepth = Math.max(0.0F, this.keyPressDepth - dtSeconds * 7.5F);
				if (this.keyPressDepth == 0.0F) {
					this.depressedKeyId = "";
				}
			}

			float deltaAngle = Mth.wrapDegrees(this.targetDialKnobAngleDeg - this.dialKnobAngleDeg);
			if (Math.abs(deltaAngle) > 0.1F) {
				float blend = Math.min(1.0F, dtSeconds * 16.0F);
				this.dialKnobAngleDeg = Mth.wrapDegrees(this.dialKnobAngleDeg + deltaAngle * blend);
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
			float dialKnobAngleDeg,
			float pttPressDepth,
			String depressedKeyId,
			float keyPressDepth,
			int x0,
			int y0,
			int x1,
			int y1,
			float scale,
			ScreenRectangle scissorArea,
			ScreenRectangle bounds
	) implements PictureInPictureRenderState {
	}

	/**
	 * 3D Picture-in-Picture renderer using the standard {@code ARCHITECTURE.md} bone hierarchy:
	 * {@code root -> body -> (antenna, dial_knob, ptt_button, led_indicator)}.
	 */
	private static final class HandheldRadioPipRenderer extends PictureInPictureRenderer<HandheldRadioRenderState> {
		private final HandheldRadioChassisModel chassisModel = new HandheldRadioChassisModel(createChassisLayer().bakeRoot());

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
			return "tlouradio handheld 3d inspection";
		}

		@Override
		protected void renderToTexture(
				HandheldRadioRenderState state,
				PoseStack poseStack,
				SubmitNodeCollector submitNodeCollector
		) {
			Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);

			// Slight tactical 3D bevel angle so side PTT latch, chassis depth, antenna, and top dial are visible
			poseStack.rotateDegrees(Axis.XP, 4.5F);
			poseStack.rotateDegrees(Axis.YP, -9.5F);

			submitNodeCollector.submitModel(
					this.chassisModel,
					state,
					poseStack,
					TEXTURE,
					FULL_BRIGHT_LIGHT,
					OverlayTexture.NO_OVERLAY,
					0
			);
		}

		private static LayerDefinition createChassisLayer() {
			MeshDefinition mesh = new MeshDefinition();
			PartDefinition meshRoot = mesh.getRoot();

			PartDefinition root = meshRoot.addOrReplaceChild(
					"root",
					CubeListBuilder.create(),
					PartPose.ZERO
			);

			// Main rugged polymer body (12.4 x 18.4 x 4.0 units -> 124 x 184 px at scale 160)
			PartDefinition body = root.addOrReplaceChild(
					"body",
					CubeListBuilder.create()
							.texOffs(0, 0)
							.addBox(-6.2F, -9.2F, -2.0F, 12.4F, 18.4F, 4.0F)
							// Top shoulder collar
							.texOffs(0, 24)
							.addBox(-5.6F, -9.8F, -1.7F, 11.2F, 0.8F, 3.4F)
							// Side grip ribs
							.texOffs(0, 28)
							.addBox(-6.45F, 0.5F, -1.5F, 0.35F, 7.6F, 3.0F)
							.texOffs(8, 28)
							.addBox(6.1F, 0.5F, -1.5F, 0.35F, 7.6F, 3.0F),
					PartPose.ZERO
			);

			// Whip antenna (top-left of body)
			body.addOrReplaceChild(
					"antenna",
					CubeListBuilder.create()
							.texOffs(40, 0)
							.addBox(-0.75F, -1.4F, -0.75F, 1.5F, 1.4F, 1.5F)
							.texOffs(46, 0)
							.addBox(-0.42F, -5.2F, -0.42F, 0.84F, 3.8F, 0.84F),
					PartPose.offset(-4.1F, -9.6F, 0.0F)
			);

			// Rotary frequency/volume dial_knob (top-right of body)
			body.addOrReplaceChild(
					"dial_knob",
					CubeListBuilder.create()
							.texOffs(40, 12)
							.addBox(-1.1F, -1.6F, -1.1F, 2.2F, 1.6F, 2.2F)
							// Pointer notch on top of knob so rotation is visually distinct
							.texOffs(0, 40)
							.addBox(-0.2F, -1.75F, -1.05F, 0.4F, 0.25F, 1.0F),
					PartPose.offset(3.7F, -9.6F, 0.0F)
			);

			// Side tactical PTT latch (upper-left edge of body)
			body.addOrReplaceChild(
					"ptt_button",
					CubeListBuilder.create()
							.texOffs(0, 42)
							.addBox(-0.65F, -2.0F, -1.1F, 0.75F, 4.0F, 2.2F),
					PartPose.offset(-6.2F, -4.4F, 0.0F)
			);

			// Power LED indicator (top bezel center-right; swaps visible sub-cube for ON vs OFF UV)
			PartDefinition ledIndicator = body.addOrReplaceChild(
					"led_indicator",
					CubeListBuilder.create(),
					PartPose.offset(0.5F, -9.85F, -0.9F)
			);
			ledIndicator.addOrReplaceChild(
					"led_on",
					CubeListBuilder.create()
							.texOffs(26, 42)
							.addBox(-0.45F, -0.35F, -0.45F, 0.9F, 0.45F, 0.9F),
					PartPose.ZERO
			);
			ledIndicator.addOrReplaceChild(
					"led_off",
					CubeListBuilder.create()
							.texOffs(42, 42)
							.addBox(-0.45F, -0.35F, -0.45F, 0.9F, 0.45F, 0.9F),
					PartPose.ZERO
			);

			// Subtle 3D tactile keypad plunger indicator inside chassis
			body.addOrReplaceChild(
					"keypad_plunger",
					CubeListBuilder.create()
							.texOffs(4, 56)
							.addBox(-5.1F, -1.8F, -2.12F, 10.2F, 9.4F, 0.2F),
					PartPose.ZERO
			);

			return LayerDefinition.create(mesh, 64, 64);
		}
	}

	private static final class HandheldRadioChassisModel extends Model<HandheldRadioRenderState> {
		private final ModelPart dialKnob;
		private final ModelPart pttButton;
		private final ModelPart ledOn;
		private final ModelPart ledOff;
		private final ModelPart keypadPlunger;

		private HandheldRadioChassisModel(ModelPart bakedRoot) {
			super(bakedRoot, RenderTypes::entityCutout);
			ModelPart rootPart = bakedRoot.getChild("root");
			ModelPart bodyPart = rootPart.getChild("body");
			this.dialKnob = bodyPart.getChild("dial_knob");
			this.pttButton = bodyPart.getChild("ptt_button");
			ModelPart ledPart = bodyPart.getChild("led_indicator");
			this.ledOn = ledPart.getChild("led_on");
			this.ledOff = ledPart.getChild("led_off");
			this.keypadPlunger = bodyPart.getChild("keypad_plunger");
		}

		@Override
		public void setupAnim(HandheldRadioRenderState state) {
			super.setupAnim(state);
			this.dialKnob.yRot = state.dialKnobAngleDeg() * Mth.DEG_TO_RAD;
			this.pttButton.x += state.pttPressDepth() * 0.35F;
			this.keypadPlunger.z += state.keyPressDepth() * 0.14F;
			this.ledOn.visible = state.isPowered();
			this.ledOff.visible = !state.isPowered();
		}
	}
}
