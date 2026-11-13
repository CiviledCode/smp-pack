package dev.grxm.teams;

import dev.ithundxr.createnumismatics.content.backend.BankSavedData;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.io.*;

/** Scoped replacement for vanilla's unchecked, destructive SavedData writer. */
public final class BankPersistence {
    private static Path bank, marker;
    private static String failure;
    private static boolean frozen, expectedAttachment;
    public static boolean frozen(){return frozen||failure!=null;}
    public static void freeze(){frozen=true;}
    public static String failure() { return failure; }
    public static void preflight(MinecraftServer server) {
        bank=server.getWorldPath(LevelResource.ROOT).resolve("data/numismatics_bank.dat");
        marker=bank.resolveSibling("grxmteams-initialized.guard"); failure=null; frozen=false; expectedAttachment=Files.exists(marker);
        try {
            if(Files.exists(marker) && !Files.isRegularFile(bank))throw new IOException("Previously initialized bank is missing");
            if(Files.exists(bank)) {
                CompoundTag root=NbtIo.readCompressed(bank.toFile());
                expectedAttachment|=root.getCompound("data").contains("grxmteams");
                if(!root.contains("data",10) || !root.getCompound("data").contains("Accounts",9))throw new IOException("Invalid native bank root");
                if(Files.exists(marker) && !root.getCompound("data").contains("grxmteams",10))throw new IOException("Previously initialized attachment is missing");
            }
        } catch(Exception e) { failure="Bank preflight refused; preserve bank and guard for operator recovery: "+e; throw new IllegalStateException(failure,e); }
    }
    public static void requireAttachment(TeamDataAccess attachment) {
        if(expectedAttachment&&attachment.grxmteams$getJson()==null&&attachment.grxmteams$getError()==null) {
            failure="Native bank loader discarded a previously initialized bank; preserve original for operator recovery";
            throw new IllegalStateException(failure);
        }
    }
    public static void write(BankSavedData data,File file) {
        if(failure!=null)throw new IllegalStateException(failure);
        if(data instanceof TeamDataAccess attached)requireAttachment(attached);
        try {
            CompoundTag root=new CompoundTag(); root.put("data",data.save(new CompoundTag())); root.putInt("DataVersion",net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            atomic(file.toPath(),root); data.setDirty(false);
        } catch(Exception e) { failure="Bank durability uncertain; operator recovery required: "+e; throw new IllegalStateException(failure,e); }
    }
    public static void commit(BankSavedData data) {
        if(bank==null)throw new IllegalStateException("Bank preflight hook unavailable");
        write(data,bank.toFile());
        try {
            if(!Files.exists(marker)) {
                Files.writeString(marker,"GRXM initialized; do not delete to bypass recovery\n",StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                try(FileChannel c=FileChannel.open(marker,StandardOpenOption.WRITE)){c.force(true);}
            }
        } catch(IOException e) { failure="Cannot persist initialization guard: "+e; throw new IllegalStateException(failure,e); }
    }
    static void atomic(Path target,CompoundTag root) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temp=Files.createTempFile(target.toAbsolutePath().getParent(),".grxm-bank-",".nbt");
        // Keep failed temporary images as evidence. Never fall back to non-atomic replacement.
        NbtIo.writeCompressed(root,temp.toFile());
        try(FileChannel c=FileChannel.open(temp,StandardOpenOption.WRITE)){c.force(true);}
        if(!root.equals(NbtIo.readCompressed(temp.toFile())))throw new IOException("Temporary NBT readback mismatch");
        Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        if(!root.equals(NbtIo.readCompressed(target.toFile())))throw new IOException("Replaced NBT readback mismatch");
        try(FileChannel dir=FileChannel.open(target.toAbsolutePath().getParent(),StandardOpenOption.READ)){dir.force(true);}
    }
}
