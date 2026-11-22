package dev.grxm.radar;

import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Persist the item UUID before a city can be reserved for it. Server thread only. */
final class PlayerIdentity {
    private PlayerIdentity() {}
    static void persist(ServerPlayer player) throws IOException {
        Path dir=player.server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        Files.createDirectories(dir);
        Path target=dir.resolve(player.getUUID()+".dat"), temporary=Files.createTempFile(dir,"radar-identity-",".dat");
        try {
            CompoundTag tag=player.saveWithoutId(new CompoundTag());NbtUtils.addCurrentDataVersion(tag);
            try(FileOutputStream out=new FileOutputStream(temporary.toFile())) {
                NbtIo.writeCompressed(tag,out);
                // NbtIo closes the stream, so force through a fresh file descriptor.
            }
            try(FileChannel file=FileChannel.open(temporary,StandardOpenOption.WRITE)){file.force(true);}
            if(Files.exists(target))Files.copy(target,dir.resolve(player.getUUID()+".dat_old"),StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(FileChannel directory=FileChannel.open(dir,StandardOpenOption.READ)){directory.force(true);}
        } finally {Files.deleteIfExists(temporary);}
    }
}
