package dev.grxm.teams.mixin;
import dev.grxm.teams.*;
import dev.ithundxr.createnumismatics.content.backend.BankSavedData;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.File;
@Mixin(SavedData.class)
public abstract class SavedDataMixin {
    @Inject(method="save(Ljava/io/File;)V",at=@At("HEAD"),cancellable=true)
    private void grxmteams$atomic(File file,CallbackInfo ci) {
        if((Object)this instanceof BankSavedData bank) { ci.cancel(); if(bank.isDirty())BankPersistence.write(bank,file); }
    }
}
