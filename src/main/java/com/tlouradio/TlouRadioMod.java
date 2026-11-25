package com.tlouradio;

import com.tlouradio.block.ModBlocks;
import com.tlouradio.block.entity.ModBlockEntities;
import com.tlouradio.item.ModItems;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TlouRadioMod implements ModInitializer {
	public static final String MOD_ID = "tlouradio";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModBlocks.initialize();
		ModBlockEntities.initialize();
		ModItems.initialize();
		LOGGER.info("TLOU2 Radio Mod ({}) baslatildi.", MOD_ID);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
