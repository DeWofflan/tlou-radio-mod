package com.tlouradio.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class RadioBaseBoxBlockEntity extends BlockEntity {
	public RadioBaseBoxBlockEntity(BlockPos pos, BlockState blockState) {
		super(ModBlockEntities.RADIO_BASE_BOX, pos, blockState);
	}
}
