package com.tlouradio.item;

import com.tlouradio.TlouRadioMod;
import com.tlouradio.block.ModBlocks;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Function;

public final class ModItems {
	public static final Item HANDHELD_RADIO = registerItem(
			"handheld_radio",
			HandheldRadioItem::new,
			new Item.Properties().stacksTo(1)
	);

	public static final CreativeModeTab CREATIVE_TAB = Registry.register(
			BuiltInRegistries.CREATIVE_MODE_TAB,
			TlouRadioMod.id("general"),
			FabricCreativeModeTab.builder()
					.title(Component.translatable("itemGroup.tlouradio"))
					.icon(() -> new ItemStack(HANDHELD_RADIO))
					.displayItems((parameters, output) -> {
						output.accept(HANDHELD_RADIO);
						output.accept(ModBlocks.RADIO_BASE_BOX);
					})
					.build()
	);

	private ModItems() {
	}

	private static Item registerItem(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, TlouRadioMod.id(name));
		Item item = factory.apply(properties.setId(itemKey));
		return Registry.register(BuiltInRegistries.ITEM, itemKey, item);
	}

	public static void initialize() {
	}
}
