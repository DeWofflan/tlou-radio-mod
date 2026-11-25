package com.tlouradio.block;

import com.tlouradio.block.entity.RadioBaseBoxBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class RadioBaseBoxBlock extends Block implements EntityBlock {
	public RadioBaseBoxBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new RadioBaseBoxBlockEntity(pos, state);
	}
}
