package com.tlouradio.radio;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Environment-safe client UI and renderer bridge so common item/block classes can hook into
 * client-only screens and GeckoLib render providers under splitEnvironmentSourceSets
 * without referencing any client-only classes in src/main.
 */
public final class RadioClientHooks {
	private static volatile BiConsumer<InteractionHand, ItemStack> handheldScreenOpener = (hand, stack) -> {
	};
	private static volatile Supplier<Object> handheldRenderProviderFactory = () -> null;
	private static volatile Supplier<Object> baseBoxRenderProviderFactory = () -> null;

	private RadioClientHooks() {
	}

	public static void registerHandheldScreenOpener(BiConsumer<InteractionHand, ItemStack> opener) {
		handheldScreenOpener = Objects.requireNonNull(opener, "opener");
	}

	public static void openHandheldRadioScreen(InteractionHand hand, ItemStack stack) {
		if (hand == null || stack == null || stack.isEmpty()) {
			return;
		}
		handheldScreenOpener.accept(hand, stack.copy());
	}

	public static void registerRenderProviderFactories(
			Supplier<Object> handheldProviderFactory,
			Supplier<Object> baseBoxProviderFactory
	) {
		handheldRenderProviderFactory = Objects.requireNonNull(handheldProviderFactory, "handheldProviderFactory");
		baseBoxRenderProviderFactory = Objects.requireNonNull(baseBoxProviderFactory, "baseBoxProviderFactory");
	}

	public static Object createHandheldRenderProvider() {
		return handheldRenderProviderFactory.get();
	}

	public static Object createBaseBoxRenderProvider() {
		return baseBoxRenderProviderFactory.get();
	}
}
