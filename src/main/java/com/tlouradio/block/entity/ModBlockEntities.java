package com.tlouradio.block.entity;

import com.tlouradio.TlouRadioMod;
import com.tlouradio.block.ModBlocks;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
	public static final BlockEntityType<RadioBaseBoxBlockEntity> RADIO_BASE_BOX = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			TlouRadioMod.id("radio_base_box"),
			FabricBlockEntityTypeBuilder.create(RadioBaseBoxBlockEntity::new, ModBlocks.RADIO_BASE_BOX).build()
	);

	private ModBlockEntities() {
	}

	public static void initialize() {
	}
}
