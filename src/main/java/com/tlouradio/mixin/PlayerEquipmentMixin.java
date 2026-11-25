package com.tlouradio.mixin;

import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.radio.RadioNetworkManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerEquipment;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEquipment.class)
public abstract class PlayerEquipmentMixin {
	@Shadow
	@Final
	private Player player;

	@Unique
	private static boolean tlouradio$isRadio(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() instanceof HandheldRadioItem;
	}

	@Inject(method = "set", at = @At("RETURN"))
	private void tlouradio$afterSetEquipment(EquipmentSlot slot, ItemStack newStack, CallbackInfoReturnable<ItemStack> cir) {
		// MAINHAND delegates to Inventory#setSelectedItem, already handled in InventoryMixin
		if (slot != EquipmentSlot.MAINHAND && this.player instanceof ServerPlayer serverPlayer) {
			ItemStack oldStack = cir.getReturnValue();
			if (tlouradio$isRadio(oldStack) || tlouradio$isRadio(newStack)) {
				RadioNetworkManager.syncPlayer(serverPlayer);
			}
		}
	}
}
