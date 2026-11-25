package com.tlouradio.mixin;

import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.radio.RadioNetworkManager;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Unique
	private boolean tlouradio$containerClickInvolvedRadio;

	@Unique
	private static boolean tlouradio$isRadio(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() instanceof HandheldRadioItem;
	}

	@Inject(method = "handleSetCarriedItem", at = @At("RETURN"))
	private void tlouradio$afterSetCarriedItem(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
		RadioNetworkManager.revalidateHeldPtt(this.player);
	}

	@Inject(method = "handleContainerClick", at = @At("HEAD"))
	private void tlouradio$beforeContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
		if (this.player == null) {
			this.tlouradio$containerClickInvolvedRadio = false;
			return;
		}
		if (RadioNetworkManager.isTrackingPlayer(this.player.getUUID())) {
			this.tlouradio$containerClickInvolvedRadio = true;
			return;
		}
		AbstractContainerMenu menu = this.player.containerMenu;
		if (menu == null) {
			this.tlouradio$containerClickInvolvedRadio = false;
			return;
		}
		boolean involved = tlouradio$isRadio(menu.getCarried());
		int slotNum = packet.slotNum();
		if (!involved && slotNum >= 0 && slotNum < menu.slots.size()) {
			involved = tlouradio$isRadio(menu.slots.get(slotNum).getItem());
		}
		this.tlouradio$containerClickInvolvedRadio = involved;
	}

	@Inject(method = "handleContainerClick", at = @At("RETURN"))
	private void tlouradio$afterContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
		boolean shouldSync = this.tlouradio$containerClickInvolvedRadio;
		this.tlouradio$containerClickInvolvedRadio = false;
		if (!shouldSync && this.player != null && this.player.containerMenu != null) {
			shouldSync = tlouradio$isRadio(this.player.containerMenu.getCarried());
		}
		if (shouldSync && this.player != null) {
			RadioNetworkManager.syncPlayer(this.player);
		}
	}
}
