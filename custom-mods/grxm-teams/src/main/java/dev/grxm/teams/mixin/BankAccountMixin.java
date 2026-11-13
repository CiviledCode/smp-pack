package dev.grxm.teams.mixin;
import dev.grxm.teams.BankPersistence;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
/** Prevent native ATM/vendor spending of an uncertain in-memory credit before shutdown. */
@Mixin(value=BankAccount.class,remap=false)
public abstract class BankAccountMixin {
    @Inject(method={"deposit(I)V","deposit(Ldev/ithundxr/createnumismatics/content/backend/Coin;I)V","setBalance(I)V"},at=@At("HEAD"),cancellable=true,remap=false)
    private void grxmteams$freezeCredit(CallbackInfo ci){if(BankPersistence.frozen()&&!((BankAccount)(Object)this).isClientSide())ci.cancel();}
    @Inject(method={"deduct(I)Z","deduct(IZ)Z","deduct(Ldev/ithundxr/createnumismatics/content/backend/Coin;I)Z","deduct(Ldev/ithundxr/createnumismatics/content/backend/Coin;IZ)Z"},at=@At("HEAD"),cancellable=true,remap=false)
    private void grxmteams$freezeDebit(CallbackInfoReturnable<Boolean> ci){if(BankPersistence.frozen()&&!((BankAccount)(Object)this).isClientSide())ci.setReturnValue(false);}
}
