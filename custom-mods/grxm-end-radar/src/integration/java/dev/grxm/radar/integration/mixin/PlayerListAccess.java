package dev.grxm.radar.integration.mixin;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(PlayerList.class)
public interface PlayerListAccess {
    @Accessor("playersByUUID") Map<UUID,ServerPlayer> radarPlayers();
}
