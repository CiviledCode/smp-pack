package dev.grxm.teams;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.LevelChunk;

/** Random-block/fluid portion of 1.20.1 ServerLevel.tickChunk only: no weather or spawning. */
public final class SelectedChunkRandomTicks {
    private SelectedChunkRandomTicks() {}
    /** Implemented by the level mixin to record actual vanilla calls, not estimated player distance. */
    public interface VanillaTicks { boolean grxmteams$wasChunkTicked(long chunk); }

    public static void tick(ServerLevel level, LevelChunk chunk) {
        if(level.isDebug() || !level.isNaturalSpawningAllowed(chunk.getPos())
                || ((VanillaTicks)level).grxmteams$wasChunkTicked(chunk.getPos().toLong()))return;
        int speed=level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        if(speed<=0)return;
        int x=chunk.getPos().getMinBlockX(), z=chunk.getPos().getMinBlockZ();
        var sections=chunk.getSections();
        for(int index=0;index<sections.length;index++) {
            var section=sections[index];
            if(!section.isRandomlyTicking())continue;
            int y=SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for(int sample=0;sample<speed;sample++) {
                var pos=level.getBlockRandomPos(x,y,z,15);
                var state=section.getBlockState(pos.getX()-x,pos.getY()-y,pos.getZ()-z);
                if(state.isRandomlyTicking())state.randomTick(level,pos,level.random);
                var fluid=state.getFluidState();
                if(fluid.isRandomlyTicking())fluid.randomTick(level,pos,level.random);
            }
        }
    }
}
