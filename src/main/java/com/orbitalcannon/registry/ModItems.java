package com.orbitalcannon.registry;

import com.orbitalcannon.OrbitalCannon;
import com.orbitalcannon.item.TargetingRemoteItem;
import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

public final class ModItems {
	public static final Item ORBITAL_TERMINAL = register(
		"orbital_terminal",
		properties -> new BlockItem(ModBlocks.ORBITAL_TERMINAL, properties),
		new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.EPIC)
	);

	public static final Item TARGETING_REMOTE = register(
		"targeting_remote",
		TargetingRemoteItem::new,
		new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)
	);

	private ModItems() {
	}

	private static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, OrbitalCannon.id(name));
		Item item = factory.apply(properties.setId(key));
		if (item instanceof BlockItem blockItem) {
			blockItem.registerBlocks(Item.BY_BLOCK, item);
		}

		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	public static void initialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(output -> output.accept(ORBITAL_TERMINAL));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.accept(TARGETING_REMOTE));
	}
}
