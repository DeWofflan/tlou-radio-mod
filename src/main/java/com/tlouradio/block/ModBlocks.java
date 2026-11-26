package com.tlouradio.block;

import com.tlouradio.TlouRadioMod;
import com.tlouradio.item.RadioBaseBoxBlockItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class ModBlocks {
	public static final Block RADIO_BASE_BOX = registerBlockWithItem(
			"radio_base_box",
			RadioBaseBoxBlock::new,
			BlockBehaviour.Properties.of()
					.strength(3.0F, 6.0F)
					.sound(SoundType.METAL)
					.noOcclusion()
	);

	private ModBlocks() {
	}

	private static Block registerBlockWithItem(
			String name,
			java.util.function.Function<BlockBehaviour.Properties, Block> factory,
			BlockBehaviour.Properties properties
	) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, TlouRadioMod.id(name));
		Block block = factory.apply(properties.setId(blockKey));
		Registry.register(BuiltInRegistries.BLOCK, blockKey, block);

		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, TlouRadioMod.id(name));
		BlockItem blockItem = new RadioBaseBoxBlockItem(
				block,
				new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()
		);
		Registry.register(BuiltInRegistries.ITEM, itemKey, blockItem);

		return block;
	}

	public static void initialize() {
	}
}
