package com.tlouradio.mixin.client;

import com.tlouradio.client.RadioClientState;
import com.tlouradio.item.HandheldRadioItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(
			method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
			at = @At("HEAD"),
			cancellable = true
	)
	private static void tlouradio$applyThirdPersonRadioPttPose(
			Avatar avatar,
			ItemStack stack,
			InteractionHand hand,
			CallbackInfoReturnable<HumanoidModel.ArmPose> cir
	) {
		if (avatar == null || stack == null || stack.isEmpty()) {
			return;
		}
		if (!(stack.getItem() instanceof HandheldRadioItem)) {
			return;
		}
		if (!RadioClientState.isTransmitting(avatar.getUUID())) {
			return;
		}
		// If holding a radio in both hands, raise only the primary hand to avoid unnatural double-spyglass pose
		if (hand == InteractionHand.OFF_HAND && avatar.getMainHandItem().getItem() instanceof HandheldRadioItem) {
			return;
		}
		cir.setReturnValue(HumanoidModel.ArmPose.SPYGLASS);
	}
}
