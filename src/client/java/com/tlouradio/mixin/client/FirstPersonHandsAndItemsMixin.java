package com.tlouradio.mixin.client;

import com.tlouradio.item.HandheldRadioItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FirstPersonHandsAndItems.class)
public abstract class FirstPersonHandsAndItemsMixin {
	@Shadow
	private ItemStack mainHandItem;
	@Shadow
	private ItemStack offHandItem;
	@Shadow
	private float mainHandHeight;
	@Shadow
	private float oMainHandHeight;
	@Shadow
	private float offHandHeight;
	@Shadow
	private float oOffHandHeight;

	@Unique
	private int tlouradio$lastSelectedSlot = -1;
	@Unique
	private boolean tlouradio$preserveMainRadioHeight = false;
	@Unique
	private float tlouradio$preTickMainHeight = 1.0F;
	@Unique
	private boolean tlouradio$preserveOffRadioHeight = false;
	@Unique
	private float tlouradio$preTickOffHeight = 1.0F;

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

	@Inject(method = "itemUsed", at = @At("HEAD"), cancellable = true)
	private void tlouradio$preventRightClickUseDrop(InteractionHand hand, CallbackInfo ci) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && player.getItemInHand(hand).getItem() instanceof HandheldRadioItem) {
			ci.cancel();
		}
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void tlouradio$captureSameSlotRadioState(LocalPlayer player, CallbackInfo ci) {
		int currentSlot = player.getInventory().getSelectedSlot();
		this.tlouradio$preserveMainRadioHeight = (currentSlot == this.tlouradio$lastSelectedSlot)
				&& (this.mainHandItem.getItem() instanceof HandheldRadioItem)
				&& (player.getMainHandItem().getItem() instanceof HandheldRadioItem);
		this.tlouradio$preTickMainHeight = this.mainHandHeight;

		this.tlouradio$preserveOffRadioHeight = (this.offHandItem.getItem() instanceof HandheldRadioItem)
				&& (player.getOffhandItem().getItem() instanceof HandheldRadioItem);
		this.tlouradio$preTickOffHeight = this.offHandHeight;

		this.tlouradio$lastSelectedSlot = currentSlot;
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void tlouradio$suppressSwapScaleResetDip(LocalPlayer player, CallbackInfo ci) {
		if (this.tlouradio$preserveMainRadioHeight) {
			this.mainHandItem = player.getMainHandItem();
			// Prevent LocalPlayer.getItemSwapScale() reset on RadioStateComponent updates from dipping the held radio
			float nextHeight = Mth.clamp(this.tlouradio$preTickMainHeight + 0.4F, 0.0F, 1.0F);
			this.mainHandHeight = nextHeight;
			if (this.tlouradio$preTickMainHeight >= 0.99F) {
				this.oMainHandHeight = 1.0F;
			}
		}

		if (this.tlouradio$preserveOffRadioHeight) {
			this.offHandItem = player.getOffhandItem();
			float nextOffHeight = Mth.clamp(this.tlouradio$preTickOffHeight + 0.4F, 0.0F, 1.0F);
			this.offHandHeight = nextOffHeight;
			if (this.tlouradio$preTickOffHeight >= 0.99F) {
				this.oOffHandHeight = 1.0F;
			}
		}
	}
}
