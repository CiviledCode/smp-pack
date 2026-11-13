package dev.grxm.teams.mixin;
import dev.grxm.teams.PlayerReceipts;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Player.class)
public abstract class PlayerMixin implements PlayerReceipts {
    @Unique private CompoundTag grxmteams$proof=new CompoundTag();
    @Override public CompoundTag grxmteams$receipts(){return grxmteams$proof;}
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"))
    private void grxmteams$write(CompoundTag root,CallbackInfo ci){root.put("grxmteamsSales",grxmteams$proof.copy());}
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"))
    private void grxmteams$read(CompoundTag root,CallbackInfo ci){grxmteams$proof=root.getCompound("grxmteamsSales").copy();}
}
