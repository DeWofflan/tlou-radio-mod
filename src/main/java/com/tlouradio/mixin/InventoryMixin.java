package com.tlouradio.mixin;

import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.radio.RadioNetworkManager;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Inventory.class)
public abstract class InventoryMixin {
	@Shadow
	@Final
	public Player player;

	@Shadow
	@Final
	private NonNullList<ItemStack> items;

	@Unique
	private boolean tlouradio$oldSlotWasRadio;

	@Unique
	private boolean tlouradio$addingRadio;

	@Unique
	private boolean tlouradio$removingRadio;

	@Unique
	private boolean tlouradio$droppingAnyRadio;

	@Unique
	private static boolean tlouradio$isRadio(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() instanceof HandheldRadioItem;
	}

	@Inject(method = "setSelectedItem", at = @At("RETURN"))
	private void tlouradio$onSetSelectedItem(ItemStack newStack, CallbackInfoReturnable<ItemStack> cir) {
		if (this.player instanceof ServerPlayer serverPlayer) {
			ItemStack oldStack = cir.getReturnValue();
			if (tlouradio$isRadio(oldStack) || tlouradio$isRadio(newStack)) {
				RadioNetworkManager.syncPlayer(serverPlayer);
			}
		}
	}

	@Inject(method = "setItem", at = @At("HEAD"))
	private void tlouradio$beforeSetItem(int slot, ItemStack newStack, CallbackInfo ci) {
		if (this.player instanceof ServerPlayer && slot >= 0 && slot < this.items.size()) {
			this.tlouradio$oldSlotWasRadio = tlouradio$isRadio(this.items.get(slot));
		} else {
			this.tlouradio$oldSlotWasRadio = false;
		}
	}

	@Inject(method = "setItem", at = @At("RETURN"))
	private void tlouradio$afterSetItem(int slot, ItemStack newStack, CallbackInfo ci) {
		if (this.player instanceof ServerPlayer serverPlayer && slot >= 0 && slot < this.items.size()) {
			boolean oldWasRadio = this.tlouradio$oldSlotWasRadio;
			this.tlouradio$oldSlotWasRadio = false;
			if (oldWasRadio || tlouradio$isRadio(newStack)) {
				RadioNetworkManager.syncPlayer(serverPlayer);
			}
		}
	}

	@Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"))
	private void tlouradio$beforeAdd(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		this.tlouradio$addingRadio = (this.player instanceof ServerPlayer) && tlouradio$isRadio(stack);
	}

	@Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("RETURN"))
	private void tlouradio$afterAdd(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		boolean wasAddingRadio = this.tlouradio$addingRadio;
		this.tlouradio$addingRadio = false;
		if (wasAddingRadio && cir.getReturnValueZ() && this.player instanceof ServerPlayer serverPlayer) {
			RadioNetworkManager.syncPlayer(serverPlayer);
		}
	}

	@Inject(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
	private void tlouradio$afterRemoveItemSlot(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
		if (this.player instanceof ServerPlayer serverPlayer && tlouradio$isRadio(cir.getReturnValue())) {
			RadioNetworkManager.syncPlayer(serverPlayer);
		}
	}

	@Inject(method = "removeItem(Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
	private void tlouradio$beforeRemoveItemStack(ItemStack stack, CallbackInfo ci) {
		this.tlouradio$removingRadio = (this.player instanceof ServerPlayer) && tlouradio$isRadio(stack);
	}

	@Inject(method = "removeItem(Lnet/minecraft/world/item/ItemStack;)V", at = @At("RETURN"))
	private void tlouradio$afterRemoveItemStack(ItemStack stack, CallbackInfo ci) {
		boolean wasRemovingRadio = this.tlouradio$removingRadio;
		this.tlouradio$removingRadio = false;
		if (wasRemovingRadio && this.player instanceof ServerPlayer serverPlayer) {
			RadioNetworkManager.syncPlayer(serverPlayer);
		}
	}

	@Inject(method = "removeItemNoUpdate", at = @At("RETURN"))
	private void tlouradio$afterRemoveItemNoUpdate(int slot, CallbackInfoReturnable<ItemStack> cir) {
		if (this.player instanceof ServerPlayer serverPlayer && tlouradio$isRadio(cir.getReturnValue())) {
			RadioNetworkManager.syncPlayer(serverPlayer);
		}
	}

	@Inject(method = "dropAll", at = @At("HEAD"))
	private void tlouradio$beforeDropAll(CallbackInfo ci) {
		if (!(this.player instanceof ServerPlayer serverPlayer)) {
			this.tlouradio$droppingAnyRadio = false;
			return;
		}
		boolean found = tlouradio$isRadio(serverPlayer.getOffhandItem());
		if (!found) {
			for (ItemStack stack : this.items) {
				if (tlouradio$isRadio(stack)) {
					found = true;
					break;
				}
			}
		}
		this.tlouradio$droppingAnyRadio = found;
	}

	@Inject(method = "dropAll", at = @At("RETURN"))
	private void tlouradio$afterDropAll(CallbackInfo ci) {
		boolean hadRadio = this.tlouradio$droppingAnyRadio;
		this.tlouradio$droppingAnyRadio = false;
		if (hadRadio && this.player instanceof ServerPlayer serverPlayer) {
			RadioNetworkManager.syncPlayer(serverPlayer);
		}
	}

	@Inject(method = "clearContent", at = @At("RETURN"))
	private void tlouradio$afterClearContent(CallbackInfo ci) {
		if (this.player instanceof ServerPlayer serverPlayer) {
			RadioNetworkManager.syncPlayer(serverPlayer);
		}
	}

	@Inject(method = "replaceWith", at = @At("RETURN"))
	private void tlouradio$afterReplaceWith(Inventory other, CallbackInfo ci) {
		if (this.player instanceof ServerPlayer serverPlayer) {
			RadioNetworkManager.syncPlayer(serverPlayer);
		}
	}
}
