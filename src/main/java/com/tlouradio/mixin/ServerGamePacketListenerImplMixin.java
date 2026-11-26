package com.tlouradio.mixin;

import com.tlouradio.component.ModDataComponents;
import com.tlouradio.component.RadioStateComponent;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.radio.RadioNetworkManager;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
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
	private boolean tlouradio$hotMicDropArmed;

	@Unique
	private static boolean tlouradio$isRadio(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() instanceof HandheldRadioItem;
	}

	@Inject(method = "handlePlayerAction", at = @At("HEAD"))
	private void tlouradio$beforePlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
		ServerboundPlayerActionPacket.Action action = packet.getAction();
		if (action != ServerboundPlayerActionPacket.Action.DROP_ITEM
				&& action != ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) {
			return;
		}
		if (this.player == null || this.player.isSpectator()) {
			return;
		}
		if (!RadioNetworkManager.isTransmitting(this.player.getUUID())) {
			return;
		}
		ItemStack mainHand = this.player.getMainHandItem();
		if (!tlouradio$isRadio(mainHand)) {
			return;
		}
		RadioStateComponent state = mainHand.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
		if (!RadioNetworkManager.isRadioStateAuthorized(this.player.level().getServer(), state)) {
			return;
		}
		RadioNetworkManager.beginHotMicDrop(this.player.getUUID());
		this.tlouradio$hotMicDropArmed = true;
		mainHand.set(ModDataComponents.RADIO_STATE, state.withHotMicLocked(true));
	}

	@Inject(method = "handlePlayerAction", at = @At("RETURN"))
	private void tlouradio$afterPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
		if (!this.tlouradio$hotMicDropArmed) {
			return;
		}
		this.tlouradio$hotMicDropArmed = false;
		if (this.player == null) {
			return;
		}
		RadioNetworkManager.endHotMicDrop(this.player.getUUID());
		// Fallback if the drop was cancelled and the item remained in hand
		ItemStack mainHand = this.player.getMainHandItem();
		if (tlouradio$isRadio(mainHand)) {
			RadioStateComponent state = mainHand.getOrDefault(ModDataComponents.RADIO_STATE, RadioStateComponent.DEFAULT);
			if (state.hotMicLocked()) {
				mainHand.set(ModDataComponents.RADIO_STATE, state.withHotMicLocked(false));
				RadioNetworkManager.syncPlayer(this.player);
			}
		}
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
