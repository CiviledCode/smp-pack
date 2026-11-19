package dev.grxm.teams.mixin;

import dev.grxm.teams.SelectedChunkRandomTicks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Observe only; never override vanilla's spawn/player/weather admission. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelRandomTicksMixin implements SelectedChunkRandomTicks.VanillaTicks {
    @Unique private final Set<Long> grxmteams$vanillaChunks=new HashSet<>();
    @Inject(method="tick(Ljava/util/function/BooleanSupplier;)V",at=@At("HEAD"))
    private void grxmteams$beginTick(BooleanSupplier budget,CallbackInfo ci) { grxmteams$vanillaChunks.clear(); }
    @Inject(method="tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V",at=@At("HEAD"))
    private void grxmteams$recordTick(LevelChunk chunk,int speed,CallbackInfo ci) { grxmteams$vanillaChunks.add(chunk.getPos().toLong()); }
    @Override public boolean grxmteams$wasChunkTicked(long chunk) { return grxmteams$vanillaChunks.contains(chunk); }
}
