package com.tlouradio.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tlouradio.item.HandheldRadioItem;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
	@Unique
	private static final RenderStateDataKey<Float> TLOURADIO_GROUND_YAW =
			RenderStateDataKey.create(() -> "tlouradio:ground_resting_yaw");

	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL")
	)
	private void tlouradio$captureStaticGroundRadioYaw(
			ItemEntity entity,
			ItemEntityRenderState state,
			float partialTick,
			CallbackInfo ci
	) {
		if (entity.getItem().getItem() instanceof HandheldRadioItem) {
			float restingYawDeg = entity.getYRot() + (entity.bobOffs * 57.295776F);
			state.setData(TLOURADIO_GROUND_YAW, restingYawDeg);
		}
	}

	@Inject(
			method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void tlouradio$renderStaticGroundedRadio(
			ItemEntityRenderState state,
			PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector,
			CameraRenderState cameraState,
			CallbackInfo ci
	) {
		Float restingYawDeg = state.getData(TLOURADIO_GROUND_YAW);
		if (restingYawDeg == null || state.item.isEmpty()) {
			return;
		}

		poseStack.pushPose();
		// Half thickness of flat-lying scaled radio (~0.053) + minimal +0.012 clearance to prevent Z-fighting
		poseStack.translate(0.0F, 0.065F, 0.0F);
		poseStack.rotateDegrees(Axis.YP, restingYawDeg);
		state.item.submit(
				poseStack,
				submitNodeCollector,
				state.lightCoords,
				OverlayTexture.NO_OVERLAY,
				state.outlineColor
		);
		poseStack.popPose();
		ci.cancel();
	}
}
