package dev.grxm.density;

import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import java.nio.file.*;

/** Disposable fixture only; never packaged inside the resource mod. */
public class Acceptance implements ModInitializer {
    public void onInitialize() {
        if (!Files.exists(Path.of("density-fixture-ALLOW"))) throw new IllegalStateException("Not a disposable fixture");
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var level = server.getLevel(Level.END);
                var sets = server.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
                var placement = (RandomSpreadStructurePlacement) sets.get(new ResourceLocation("minecraft:end_cities")).placement();
                int expected = Integer.getInteger("density.expected", 20);
                var encoded = RandomSpreadStructurePlacement.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, placement).result().orElseThrow();
                if (placement.spacing()!=expected || placement.separation()!=(expected==14?7:11) || encoded.getAsJsonObject().get("salt").getAsInt()!=10387313 || placement.spreadType()!=RandomSpreadType.TRIANGULAR)
                    throw new AssertionError("Wrong active placement: " + placement.spacing());
                var id = new ResourceLocation("minecraft:worldgen/structure_set/end_cities.json");
                var resource = server.getResourceManager().getResource(id).orElseThrow();
                JsonObject result = new JsonObject();
                var packs=server.getPackRepository();
                packs.reload();
                packs.setSelected(java.util.List.of());
                if(!packs.getSelectedIds().contains("fabric") || !packs.getPack("fabric").isRequired())
                    throw new AssertionError("Fabric server resources are not required");
                result.addProperty("fabric_required_after_empty_selection",true);
                result.add("active_placement", encoded);
                result.addProperty("seed", level.getSeed());
                result.addProperty("spacing", placement.spacing());
                result.addProperty("separation", placement.separation());
                result.addProperty("resource_source", resource.sourcePackId());
                result.addProperty("generator", level.getChunkSource().getGenerator().getClass().getName());
                result.addProperty("biome_source", level.getChunkSource().getGenerator().getBiomeSource().getClass().getName());
                JsonArray allowed = new JsonArray();
                var city = server.registryAccess().registryOrThrow(Registries.STRUCTURE).get(new ResourceLocation("minecraft:end_city"));
                city.biomes().forEach(b -> allowed.add(b.unwrapKey().orElseThrow().location().toString()));
                result.add("allowed_biomes", allowed);
                JsonArray terrain = new JsonArray();
                var gen=level.getChunkSource().getGenerator();
                var random=level.getChunkSource().randomState();
                for(int bx=1280;bx<3200;bx+=128) for(int bz=1280;bz<3200;bz+=128) {
                    JsonObject point=new JsonObject();point.addProperty("x",bx);point.addProperty("z",bz);
                    point.addProperty("base_height",gen.getBaseHeight(bx,bz,net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,level,random));
                    point.addProperty("biome",gen.getBiomeSource().getNoiseBiome(bx>>2,64>>2,bz>>2,random.sampler()).unwrapKey().orElseThrow().location().toString());
                    terrain.add(point);
                }
                result.add("terrain_control",terrain);
                int candidates=0, valid=0;
                JsonArray rows = new JsonArray();
                // Same finite area, not equal grid indices: chunks [80,200) on each axis.
                for (int x=80;x<200;x++) for(int z=80;z<200;z++) {
                    ChunkPos candidate=placement.getPotentialStructureChunk(level.getSeed(),x,z);
                    if(candidate.x!=x || candidate.z!=z) continue;
                    candidates++;
                    var chunk=level.getChunk(x,z,ChunkStatus.STRUCTURE_STARTS);
                    var start=chunk.getStartForStructure(city);
                    boolean ok=start!=null && start.isValid();
                    JsonObject row=new JsonObject(); row.addProperty("x",x);row.addProperty("z",z);row.addProperty("valid",ok);
                    if(ok) {
                        valid++;row.addProperty("pieces",start.getPieces().size()); row.addProperty("box",start.getBoundingBox().toString());
                        // Complete the candidate chunk, not just its placement calculation.
                        var full=level.getChunk(x,z,ChunkStatus.FULL);
                        int purpur=0;
                        var pos=new net.minecraft.core.BlockPos.MutableBlockPos();
                        for(int bx=x*16;bx<x*16+16;bx++) for(int bz=z*16;bz<z*16+16;bz++)
                            for(int y=start.getBoundingBox().minY();y<=start.getBoundingBox().maxY();y++) {
                                var block=full.getBlockState(pos.set(bx,y,bz)).getBlock();
                                if(block==net.minecraft.world.level.block.Blocks.PURPUR_BLOCK || block==net.minecraft.world.level.block.Blocks.PURPUR_PILLAR || block==net.minecraft.world.level.block.Blocks.PURPUR_STAIRS || block==net.minecraft.world.level.block.Blocks.PURPUR_SLAB) purpur++;
                            }
                        if(purpur==0) throw new AssertionError("Valid start has no city blocks at "+x+","+z);
                        row.addProperty("purpur_blocks_in_start_chunk",purpur);
                    }
                    rows.add(row);
                }
                result.addProperty("candidates",candidates);result.addProperty("valid_starts",valid);result.add("rows",rows);
                Files.writeString(Path.of("density-results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));
                System.out.println("DENSITY ACCEPTANCE spacing="+expected+" candidates="+candidates+" valid="+valid);
            } catch(Throwable failure) {
                failure.printStackTrace();
                try { Files.writeString(Path.of("density-FAIL.txt"), failure.toString()); } catch(Exception ignored) {}
            } finally { server.halt(false); }
        });
    }
}
