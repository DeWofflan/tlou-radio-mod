package com.tlouradio.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
	@Unique
	private static final RenderStateDataKey<Float> TLOURADIO_GROUND_YAW =
			RenderStateDataKey.create(() -> "tlouradio:ground_resting_yaw");

	@Unique
	private static final DustParticleOptions TLOURADIO_HOT_MIC_PARTICLE =
			new DustParticleOptions(0xFF331A, 0.55F);

	@Unique
	private static final long TLOURADIO_CLIENT_PARTICLE_INTERVAL_MS = 1200L;

	@Unique
	private static final Map<Integer, Long> TLOURADIO_LAST_CLIENT_PARTICLE_MS = new ConcurrentHashMap<>();

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
		ItemStack stack = entity.getItem();
		if (stack.getItem() instanceof HandheldRadioItem) {
			float restingYawDeg = entity.getYRot() + (entity.bobOffs * 57.295776F);
			state.setData(TLOURADIO_GROUND_YAW, restingYawDeg);

			Minecraft mc = Minecraft.getInstance();
			if (!mc.isPaused()) {
				RadioStateComponent radioState = stack.getOrDefault(
						ModDataComponents.RADIO_STATE,
						RadioStateComponent.DEFAULT
				);
				if (radioState.isPowered() && radioState.antennaExtended() && radioState.hotMicLocked()) {
					long now = System.currentTimeMillis();
					int entityId = entity.getId();
					Long prev = TLOURADIO_LAST_CLIENT_PARTICLE_MS.get(entityId);
					if (prev == null || (now - prev) >= TLOURADIO_CLIENT_PARTICLE_INTERVAL_MS) {
						if (TLOURADIO_LAST_CLIENT_PARTICLE_MS.size() > 128) {
							TLOURADIO_LAST_CLIENT_PARTICLE_MS.clear();
						}
						TLOURADIO_LAST_CLIENT_PARTICLE_MS.put(entityId, now);
						entity.level().addParticle(
								TLOURADIO_HOT_MIC_PARTICLE,
								entity.getX(),
								entity.getY() + 0.22D,
								entity.getZ(),
								0.0D,
								0.01D,
								0.0D
						);
					}
				}
			}
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
