package dev.grxm.teams.mixin;

import dev.grxm.teams.*;
import dev.ithundxr.createnumismatics.content.backend.BankSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=BankSavedData.class, remap=false)
public abstract class BankSavedDataMixin implements TeamDataAccess {
    @Unique private String grxmteams$json;
    @Unique private String grxmteams$error;
    @Unique private Tag grxmteams$original;
    @Override public String grxmteams$getJson() { return grxmteams$json; }
    @Override public String grxmteams$getError() { return grxmteams$error; }
    @Override public void grxmteams$setJson(String json) { if(grxmteams$error!=null) throw new IllegalStateException(grxmteams$error); grxmteams$json=json; }

    @Inject(method="load(Lnet/minecraft/server/MinecraftServer;)Ldev/ithundxr/createnumismatics/content/backend/BankSavedData;", at=@At("HEAD"), remap=false)
    private static void grxmteams$preflight(net.minecraft.server.MinecraftServer server, CallbackInfoReturnable<BankSavedData> cir) { BankPersistence.preflight(server); }

    // Both overloads deliberately match: only the NBT overload imports the attached document.
    @Inject(method="load", at=@At("RETURN"), remap=false)
    private static void grxmteams$read(@Coerce Object input, CallbackInfoReturnable<BankSavedData> cir) {
        if(!(input instanceof CompoundTag root) || !root.contains("grxmteams")) return;
        BankSavedDataMixin self=(BankSavedDataMixin)(Object)cir.getReturnValue();
        self.grxmteams$original=root.get("grxmteams").copy();
        try {
            if(!root.contains("grxmteams",10)) throw new IllegalArgumentException("Team bank attachment is not a compound");
            CompoundTag data=root.getCompound("grxmteams");
            if(!data.contains("version",3) || data.getInt("version")!=1 || !data.contains("json",7) || !data.contains("sha256",8)) throw new IllegalArgumentException("Invalid team bank envelope");
            byte[] bytes=data.getByteArray("json");
            if(bytes.length>8_000_000)throw new IllegalArgumentException("Oversize team attachment");
            String json=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            if(!StateCodec.digest(json).equals(data.getString("sha256"))) throw new IllegalArgumentException("Team bank attachment checksum mismatch");
            StateCodec.decode(json);
            self.grxmteams$json=json;
        } catch(RuntimeException e) { self.grxmteams$error="Invalid persisted teams: "+e.getMessage(); }
    }
    // Exact installed 1.0.15: intermediary method_75; Loom development named save.
    @Inject(method={"save","method_75"}, at=@At("RETURN"), remap=false, require=1)
    private void grxmteams$write(CompoundTag input, CallbackInfoReturnable<CompoundTag> cir) {
        if(grxmteams$error!=null) {
            // Preserve the exact bad attachment while allowing unrelated native bank saves.
            if(grxmteams$original!=null)cir.getReturnValue().put("grxmteams",grxmteams$original.copy());
            return;
        }
        if(grxmteams$json==null) return;
        CompoundTag data=new CompoundTag(); data.putInt("version",1); data.putByteArray("json",grxmteams$json.getBytes(java.nio.charset.StandardCharsets.UTF_8)); data.putString("sha256",StateCodec.digest(grxmteams$json));
        cir.getReturnValue().put("grxmteams",data);
    }
}
