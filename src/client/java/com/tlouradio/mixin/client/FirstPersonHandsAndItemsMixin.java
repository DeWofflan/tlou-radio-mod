package com.tlouradio.mixin.client;

import com.tlouradio.item.HandheldRadioItem;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FirstPersonHandsAndItems.class)
public abstract class FirstPersonHandsAndItemsMixin {
	@Inject(method = "shouldInstantlyReplaceVisibleItem", at = @At("HEAD"), cancellable = true)
	private void tlouradio$suppressComponentSwapBob(
			ItemStack oldStack,
			ItemStack newStack,
			LocalPlayer player,
			CallbackInfoReturnable<Boolean> cir
	) {
		if (oldStack.getItem() instanceof HandheldRadioItem && newStack.getItem() instanceof HandheldRadioItem) {
			cir.setReturnValue(true);
		}
	}
}
