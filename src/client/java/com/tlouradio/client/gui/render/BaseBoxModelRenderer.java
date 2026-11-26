package com.tlouradio.client.gui.render;

import com.geckolib.model.DefaultedBlockGeoModel;
import com.geckolib.renderer.GeoObjectRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tlouradio.TlouRadioMod;
import com.tlouradio.block.ModBlocks;
import com.tlouradio.item.RadioBaseBoxBlockItem;
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
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class BaseBoxModelRenderer {
	public static final float ISOMETRIC_PITCH_DEG = 7.5F;
	public static final float ISOMETRIC_YAW_DEG = -5.5F;

	private static final int PIP_HALF_WIDTH_PX = 148;
	private static final int PIP_TOP_EXTENT_PX = 212;
	private static final int PIP_BOTTOM_EXTENT_PX = 54;
	private static final float PIP_ORIGIN_Y_RATIO =
			(float) PIP_TOP_EXTENT_PX / (float) (PIP_TOP_EXTENT_PX + PIP_BOTTOM_EXTENT_PX);
	private static final float PIP_MODEL_SCALE = 138.0F;
	private static final int FULL_BRIGHT_LIGHT = 15728880;

	private static final Matrix4f STATIC_ISOMETRIC_MATRIX = new Matrix4f()
			.rotateZ(Mth.PI)
			.rotateX(ISOMETRIC_PITCH_DEG * Mth.DEG_TO_RAD)
			.rotateY(ISOMETRIC_YAW_DEG * Mth.DEG_TO_RAD);

	private BaseBoxModelRenderer() {
	}

	public static void initialize() {
		PictureInPictureRendererRegistry.register(ctx -> new BaseBoxPipRenderer());
	}

	public static StationLayout computeLayout(int screenWidth, int screenHeight) {
		int centerX = screenWidth / 2;
		int deckWidth = 216;
		int deckHeight = 152;
		int modelFloorY = Mth.clamp(screenHeight / 2 - 20, 114, Math.max(114, screenHeight - deckHeight - 24));

		int pipX0 = centerX - PIP_HALF_WIDTH_PX;
		int pipX1 = centerX + PIP_HALF_WIDTH_PX;
		int pipY0 = modelFloorY - PIP_TOP_EXTENT_PX;
		int pipY1 = modelFloorY + PIP_BOTTOM_EXTENT_PX;

		int deckX = centerX - (deckWidth / 2);
		int deckY = modelFloorY + 6;

		return new StationLayout(centerX, modelFloorY, pipX0, pipY0, pipX1, pipY1, deckX, deckY, deckWidth, deckHeight);
	}

	public static HandheldRadioModelRenderer.ProjectedBounds projectGeoBoxToScreen(
			StationLayout layout,
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

		for (float gx : xs) {
			for (float gy : ys) {
				for (float gz : zs) {
					tmp.set(-gx / 16.0F, gy / 16.0F, gz / 16.0F);
					STATIC_ISOMETRIC_MATRIX.transformPosition(tmp);
					float sx = layout.centerX() + (tmp.x * PIP_MODEL_SCALE);
					float sy = layout.modelFloorY() + (tmp.y * PIP_MODEL_SCALE);
					if (sx < minX) minX = sx;
					if (sx > maxX) maxX = sx;
					if (sy < minY) minY = sy;
					if (sy > maxY) maxY = sy;
				}
			}
		}
		return new HandheldRadioModelRenderer.ProjectedBounds(minX, minY, maxX, maxY);
	}

	public static void extractModel(
			GuiGraphicsExtractor graphics,
			StationLayout layout,
			float dialAngleDeg,
			float focusProgress,
			PanelReadout readout
	) {
		ScreenRectangle scissor = graphics.scissorStack.peek();
		float qDial = Math.round(dialAngleDeg * 2.0F) / 2.0F;
		float qFocus = Math.round(Mth.clamp(focusProgress, 0.0F, 1.0F) * 100.0F) / 100.0F;

		graphics.guiRenderState.addPicturesInPictureState(new BaseBoxRenderState(
				qDial,
				qFocus,
				readout,
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

	public record StationLayout(
			int centerX,
			int modelFloorY,
			int pipX0,
			int pipY0,
			int pipX1,
			int pipY1,
			int deckX,
			int deckY,
			int deckWidth,
			int deckHeight
	) {
	}

	public record PanelReadout(
			String networkLabel,
			String frequencyLabel,
			String pinStatusLabel,
			String liveBufferLabel,
			int bufferColor
	) {
	}

	public record BaseBoxRenderState(
			float dialAngleDeg,
			float focusProgress,
			PanelReadout readout,
			int x0,
			int y0,
			int x1,
			int y1,
			float scale,
			ScreenRectangle scissorArea,
			ScreenRectangle bounds
	) implements PictureInPictureRenderState {
	}

	private static final class InspectionBaseBoxGeoRenderer extends GeoObjectRenderer<RadioBaseBoxBlockItem, Void, GeoRenderState> {
		private InspectionBaseBoxGeoRenderer() {
			super(new DefaultedBlockGeoModel<>(TlouRadioMod.id("radio_base_box")));
			withRenderLayer(AutoGlowingGeoLayer::new);
		}

		@Override
		public void adjustRenderPose(RenderPassInfo<GeoRenderState> renderPassInfo) {
		}

		@Override
		public long getInstanceId(RadioBaseBoxBlockItem animatable, Void relatedObject) {
			return -8484L;
		}
	}

	private static final class BaseBoxPipRenderer extends PictureInPictureRenderer<BaseBoxRenderState> {
		private final InspectionBaseBoxGeoRenderer geoRenderer = new InspectionBaseBoxGeoRenderer();
		private final CameraRenderState dummyCameraState = new CameraRenderState();

		@Override
		public Class<BaseBoxRenderState> getRenderStateClass() {
			return BaseBoxRenderState.class;
		}

		@Override
		protected float getTranslateY(int height, int guiScale) {
			return height * PIP_ORIGIN_Y_RATIO;
		}

		@Override
		protected String getTextureLabel() {
			return "tlouradio geckolib base box inspection";
		}

		@Override
		protected void renderToTexture(
				BaseBoxRenderState state,
				PoseStack poseStack,
				SubmitNodeCollector submitNodeCollector
		) {
			if (!(ModBlocks.RADIO_BASE_BOX.asItem() instanceof RadioBaseBoxBlockItem baseBoxItem)) {
				return;
			}

			Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);

			float focus = Mth.clamp(state.focusProgress(), 0.0F, 1.0F);
			float invFocus = 1.0F - focus;
			if (invFocus > 0.001F) {
				poseStack.translate(0.0F, 0.28F * invFocus, 0.0F);
				float s = Mth.lerp(focus, 0.86F, 1.0F);
				poseStack.scale(s, s, s);
			}

			poseStack.rotateDegrees(Axis.ZP, 180.0F);
			poseStack.rotateDegrees(Axis.XP, ISOMETRIC_PITCH_DEG + (4.5F * invFocus));
			poseStack.rotateDegrees(Axis.YP, ISOMETRIC_YAW_DEG);

			RenderPassInfo.BoneUpdater<GeoRenderState> boneUpdater = (renderPassInfo, snapshots) ->
					snapshots.ifPresent("dial_main", knob ->
							knob.setRotZ(state.dialAngleDeg() * Mth.DEG_TO_RAD)
					);

			this.geoRenderer.performRenderPass(
					baseBoxItem,
					null,
					poseStack,
					submitNodeCollector,
					this.dummyCameraState,
					FULL_BRIGHT_LIGHT,
					0.0F,
					boneUpdater
			);

			PanelReadout r = state.readout();
			if (r != null) {
				Font font = Minecraft.getInstance().font;
				OrderedSubmitNodeCollector textLayer = submitNodeCollector.order(1);
				final float panelFrontZ = -6.48F;

				HandheldRadioModelRenderer.submitAligned3DSurfaceText(
						poseStack, textLayer, font,
						Component.literal(r.networkLabel()).getVisualOrderText(),
						-4.88F, 7.66F, panelFrontZ, 5.1F, 0.52F, 0xFF7FD99A, false
				);
				HandheldRadioModelRenderer.submitAligned3DSurfaceText(
						poseStack, textLayer, font,
						Component.literal(r.pinStatusLabel()).getVisualOrderText(),
						4.88F, 7.66F, panelFrontZ, 3.9F, 0.52F, 0xFF8CD8EE, true
				);
				HandheldRadioModelRenderer.submitAligned3DSurfaceText(
						poseStack, textLayer, font,
						Component.literal(r.frequencyLabel()).getVisualOrderText(),
						-4.88F, 6.72F, panelFrontZ, 5.0F, 0.62F, 0xFFA8FFBE, false
				);
				HandheldRadioModelRenderer.submitAligned3DSurfaceText(
						poseStack, textLayer, font,
						Component.literal(r.liveBufferLabel()).getVisualOrderText(),
						-4.88F, 5.78F, panelFrontZ, 9.4F, 0.58F, r.bufferColor(), false
				);
			}
		}
	}
}
