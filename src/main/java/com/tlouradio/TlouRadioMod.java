package com.tlouradio;

import com.tlouradio.block.ModBlocks;
import com.tlouradio.block.entity.ModBlockEntities;
import com.tlouradio.component.ModDataComponents;
import com.tlouradio.item.HandheldRadioItem;
import com.tlouradio.item.ModItems;
import com.tlouradio.network.ModNetworking;
import com.tlouradio.radio.RadioNetworkManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TlouRadioMod implements ModInitializer {
	public static final String MOD_ID = "tlouradio";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModDataComponents.initialize();
		ModBlocks.initialize();
		ModBlockEntities.initialize();
		ModItems.initialize();
		ModNetworking.initialize();
		RadioNetworkManager.initialize();
		registerServerInteractionGuards();

		LOGGER.info("TLOU2 Radio Mod ({}) baslatildi.", MOD_ID);
	}

	private static void registerServerInteractionGuards() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (hand == InteractionHand.MAIN_HAND && player.getMainHandItem().getItem() instanceof HandheldRadioItem) {
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (hand == InteractionHand.MAIN_HAND && player.getMainHandItem().getItem() instanceof HandheldRadioItem) {
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
