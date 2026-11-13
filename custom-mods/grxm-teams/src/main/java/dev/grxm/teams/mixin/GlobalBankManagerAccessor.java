package dev.grxm.teams.mixin;

import dev.ithundxr.createnumismatics.content.backend.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=GlobalBankManager.class, remap=false)
public interface GlobalBankManagerAccessor {
    @Accessor(value="savedData", remap=false) BankSavedData grxmteams$savedData();
}
