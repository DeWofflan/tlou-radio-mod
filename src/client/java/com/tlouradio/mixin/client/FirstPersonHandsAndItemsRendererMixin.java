package com.tlouradio.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.tlouradio.client.RadioClientInputHandler;
import com.tlouradio.client.RadioClientState;
import com.tlouradio.client.gui.HandheldRadioScreen;
import com.tlouradio.client.gui.RadioBaseBoxScreen;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
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
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {
	@Shadow
	private void renderPlayerHand(
			PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector,
			int packedLight,
			HumanoidArm arm,
			PlayerRenderState playerState
	) {
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
		var activeScreen = Minecraft.getInstance().gui.screen();
		if (activeScreen instanceof HandheldRadioScreen || activeScreen instanceof RadioBaseBoxScreen) {
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
		boolean slimSkin = avatarState.skin != null && avatarState.skin.model() == PlayerModelType.SLIM;
		float armCubeCenterX = -armSign * (slimSkin ? 0.5F : 1.0F);

		RadioClientInputHandler.updateRenderAnimationFrame();
		float flick = RadioClientInputHandler.getFlickProgress(hand);
		float ptt = RadioClientInputHandler.getPttRaiseProgress();

		// Single shared hand-grip PoseStack so the player's hand/arm and the 3D radio are
		// rigidly locked to the exact same palm axis in idle, equip, wrist-flick, PTT, and both hands.
		poseStack.pushPose();
		poseStack.translate(0.0F, equipProgress * -0.6F, 0.0F);
		poseStack.translate(armSign * 0.42F, -0.49F, -0.75F);

		// 1. Natural, relaxed inward turn & raise toward the player's mouth/ear during PTT (exact mirror for left hand)
		if (ptt > 0.001F) {
			float smoothPtt = ptt * ptt * (3.0F - 2.0F * ptt);
			poseStack.translate(-armSign * 0.15F * smoothPtt, 0.105F * smoothPtt, 0.085F * smoothPtt);
			poseStack.rotateDegrees(Axis.YP, -armSign * 16.5F * smoothPtt);
			poseStack.rotateDegrees(Axis.ZP, armSign * 10.5F * smoothPtt);
			poseStack.rotateDegrees(Axis.XP, 9.0F * smoothPtt);
		}

		// 2. Flip-open wrist flick curve around the shared wrist/palm pivot
		if (flick > 0.001F) {
			float wristPitch;
			float wristLift;
			if (flick < 0.28F) {
				float t = flick / 0.28F;
				wristPitch = Mth.sin(t * Mth.HALF_PI) * 20.0F;
				wristLift = -0.035F * t;
			} else if (flick < 0.62F) {
				float t = (flick - 0.28F) / 0.34F;
				wristPitch = Mth.lerp(t, 20.0F, -11.0F);
				wristLift = Mth.lerp(t, -0.035F, 0.025F);
			} else {
				float t = (flick - 0.62F) / 0.38F;
				wristPitch = Mth.lerp(t, -11.0F, 0.0F);
				wristLift = Mth.lerp(t, 0.025F, 0.0F);
			}
			poseStack.translate(0.0F, wristLift, 0.0F);
			poseStack.rotateDegrees(Axis.XP, wristPitch);
			poseStack.rotateDegrees(Axis.ZP, -armSign * wristPitch * 0.18F);
		}

		// 3. Shared ergonomic 1st-person resting orientation
		poseStack.rotateDegrees(Axis.YP, -armSign * 5.0F);
		poseStack.rotateDegrees(Axis.ZP, armSign * 3.0F);
		poseStack.rotateDegrees(Axis.XP, 7.0F);

		// 3b. Subtle speaker micro-vibration when actively receiving incoming radio audio
		RadioStateComponent radioState = stack.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
		float rxIntensity = (radioState.isPowered() && radioState.antennaExtended()) ? RadioClientState.getRxIntensity() : 0.0F;
		if (rxIntensity > 0.01F) {
			double tSec = (System.nanoTime() % 10_000_000_000L) / 1_000_000_000.0D;
			float vibX = Mth.sin((float) (tSec * 72.0D)) * 0.0022F * rxIntensity;
			float vibY = Mth.cos((float) (tSec * 96.0D)) * 0.0016F * rxIntensity;
			float vibRoll = Mth.sin((float) (tSec * 84.0D)) * 0.30F * rxIntensity;
			poseStack.translate(vibX, vibY, 0.0F);
			poseStack.rotateDegrees(Axis.ZP, vibRoll);
		}

		// 4. Render player's hand/forearm algebraically centered on the radio's lower grip (wide 4px & slim 3px)
		if (!avatarState.isInvisible) {
			poseStack.pushPose();
			poseStack.translate(armSign * 0.036F, -0.105F, -0.085F);
			poseStack.rotateDegrees(Axis.ZP, -armSign * 11.0F);
			poseStack.rotateDegrees(Axis.XP, -38.0F);
			poseStack.rotateDegrees(Axis.YP, 180.0F);
			poseStack.translate(-armCubeCenterX / 16.0F, -10.0F / 16.0F, 0.0F);
			poseStack.rotate(new Quaternionf().rotationZ(-armSign * 0.1F));
			poseStack.translate((armSign * 5.0F) / 16.0F, -2.0F / 16.0F, 0.0F);
			renderPlayerHand(poseStack, submitNodeCollector, packedLight, arm, playerState);
			poseStack.popPose();
		}

		// 5. Render the 3D GeckoLib handheld radio directly on the same palm origin
		poseStack.pushPose();
		ItemStackRenderState itemRenderState = isMainHand
				? handsState.mainHandRenderState
				: handsState.offHandRenderState;
		itemRenderState.submit(poseStack, submitNodeCollector, packedLight, OverlayTexture.NO_OVERLAY, 0);
		poseStack.popPose();

		poseStack.popPose();
		ci.cancel();
	}
}
