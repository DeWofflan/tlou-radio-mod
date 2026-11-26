package com.tlouradio.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tlouradio.client.RadioClientInputHandler;
import com.tlouradio.client.gui.HandheldRadioScreen;
import com.tlouradio.item.HandheldRadioItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {
	@Shadow
	private void renderPlayerArm(
			PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector,
			int packedLight,
			float equipProgress,
			float swingProgress,
			HumanoidArm arm,
			PlayerRenderState playerState
	) {
	}

	@Shadow
	private void applyItemArmTransform(PoseStack poseStack, HumanoidArm arm, float equipProgress) {
	}

	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void tlouradio$hideHandsDuringInspection(
			float partialTick,
			PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector,
			PlayerRenderState playerState,
			FirstPersonHandsAndItemsRenderState handsState,
			CallbackInfo ci
	) {
		if (Minecraft.getInstance().gui.screen() instanceof HandheldRadioScreen) {
			ci.cancel();
		}
	}

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void tlouradio$renderGrippingArmWithRadio(
			PlayerRenderState playerState,
			FirstPersonHandsAndItemsRenderState handsState,
			float partialTick,
			float xRot,
			InteractionHand hand,
			float swingProgress,
			ItemStack stack,
			float equipProgress,
			PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector,
			int packedLight,
			CallbackInfo ci
	) {
		if (!(stack.getItem() instanceof HandheldRadioItem)) {
			return;
		}
		if (handsState.isScoping) {
			ci.cancel();
			return;
		}
		AvatarRenderState avatarState = playerState.avatarRenderState;
		if (avatarState == null) {
			ci.cancel();
			return;
		}

		boolean isMainHand = (hand == InteractionHand.MAIN_HAND);
		HumanoidArm arm = isMainHand ? avatarState.mainArm : avatarState.mainArm.getOpposite();
		float armSign = (arm == HumanoidArm.RIGHT) ? 1.0F : -1.0F;

		RadioClientInputHandler.updateRenderAnimationFrame();
		float flick = RadioClientInputHandler.getFlickProgress(hand);
		float ptt = RadioClientInputHandler.getPttRaiseProgress();

		poseStack.pushPose();

		// 1. SmoothPTT raise-to-mouth transition
		if (ptt > 0.001F) {
			float smoothPtt = ptt * ptt * (3.0F - 2.0F * ptt);
			poseStack.translate(-armSign * 0.24F * smoothPtt, 0.14F * smoothPtt, 0.14F * smoothPtt);
			poseStack.rotateDegrees(Axis.XP, 11.0F * smoothPtt);
			poseStack.rotateDegrees(Axis.YP, -armSign * 8.0F * smoothPtt);
			poseStack.rotateDegrees(Axis.ZP, armSign * 7.5F * smoothPtt);
		}

		// 2. Flip-open wrist flick curve (cock back -> snap forward -> settle)
		if (flick > 0.001F) {
			float wristPitch;
			float wristLift;
			if (flick < 0.28F) {
				float t = flick / 0.28F;
				wristPitch = Mth.sin(t * Mth.HALF_PI) * 24.0F;
				wristLift = -0.05F * t;
			} else if (flick < 0.62F) {
				float t = (flick - 0.28F) / 0.34F;
				wristPitch = Mth.lerp(t, 24.0F, -14.0F);
				wristLift = Mth.lerp(t, -0.05F, 0.04F);
			} else {
				float t = (flick - 0.62F) / 0.38F;
				wristPitch = Mth.lerp(t, -14.0F, 0.0F);
				wristLift = Mth.lerp(t, 0.04F, 0.0F);
			}
			poseStack.translate(0.0F, wristLift, 0.0F);
			poseStack.rotateDegrees(Axis.XP, wristPitch);
			poseStack.rotateDegrees(Axis.ZP, -armSign * wristPitch * 0.22F);
		}

		// 3. Render player's own skin arm gripping the lower chassis of the handheld radio
		poseStack.pushPose();
		poseStack.translate(armSign * -0.08F, -0.14F, -0.10F);
		poseStack.rotateDegrees(Axis.XP, 8.0F);
		poseStack.rotateDegrees(Axis.ZP, armSign * -6.0F);
		renderPlayerArm(poseStack, submitNodeCollector, packedLight, equipProgress, 0.0F, arm, playerState);
		poseStack.popPose();

		// 4. Submit the 3D GeckoLib handheld radio held in the palm
		poseStack.pushPose();
		applyItemArmTransform(poseStack, arm, equipProgress);
		ItemStackRenderState itemRenderState = isMainHand
				? handsState.mainHandRenderState
				: handsState.offHandRenderState;
		itemRenderState.submit(poseStack, submitNodeCollector, packedLight, OverlayTexture.NO_OVERLAY, 0);
		poseStack.popPose();

		poseStack.popPose();
		ci.cancel();
	}
}
