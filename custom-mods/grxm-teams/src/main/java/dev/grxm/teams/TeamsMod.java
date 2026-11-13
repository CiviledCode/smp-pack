package dev.grxm.teams;

import me.shedaniel.autoconfig.*;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.*;
import org.slf4j.*;

public final class TeamsMod implements ModInitializer {
    public static final Logger LOGGER=LoggerFactory.getLogger("grxmteams");
    private static TeamService service;
    private static ConfigHolder<TeamsConfig> holder;
    private static java.util.function.LongSupplier timeSource=System::currentTimeMillis;
    /** Trusted embedding/integration hook, before SERVER_STARTED only; never exposed to commands/config. */
    public static void installTimeSource(java.util.function.LongSupplier clock) { if(service!=null)throw new IllegalStateException("Time source must be installed before server startup"); timeSource=java.util.Objects.requireNonNull(clock); }
    public static TeamService service() { if(service==null)throw new IllegalStateException("Teams server not started"); return service; }
    public static void reloadConfiguration() {
        TeamsConfig old=holder.getConfig();
        try { if(!holder.load())throw new IllegalArgumentException("AutoConfig rejected configuration; previous settings retained"); TeamsConfig candidate=holder.getConfig(); candidate.validate(); service().reload(candidate); }
        catch(RuntimeException e) { holder.setConfig(old); throw e; }
    }
    @Override public void onInitialize() {
        AutoConfig.register(TeamsConfig.class,GsonConfigSerializer::new); holder=AutoConfig.getConfigHolder(TeamsConfig.class);
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->TeamCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server->{service=new TeamService(server,holder.getConfig(),timeSource); LOGGER.info("{}",service.billingStatus());});
        ServerTickEvents.START_SERVER_TICK.register(server->{if(service!=null)service.startTick();});
        ServerTickEvents.END_SERVER_TICK.register(server->{if(service!=null)service.tick();});
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{if(service!=null)service.stop();});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->service=null);
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{
            if(service==null)return;
            TeamsConfig config=service.configuration();
            if(config.resourcePack.enabled) handler.player.sendTexturePack(config.resourcePack.url,config.resourcePack.sha1,config.resourcePack.required,Component.literal(config.resourcePack.prompt));
            if(service.failure()==null)service.refreshPresentation();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{if(service!=null)service.clearDisplays(handler.player);});
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer,newPlayer,alive)->{
            if(service!=null)service.clearDisplays(newPlayer);
            ((PlayerReceipts)newPlayer).grxmteams$receipts().merge(((PlayerReceipts)oldPlayer).grxmteams$receipts().copy());
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world,player,pos,state,entity)->{
            if(service!=null&&service.failure()==null&&service.boundCounter(world.dimension().location().toString(),pos)!=null)service.bazaar().invalidate(world.dimension().location().toString(),pos);
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            if(level.isClientSide||service==null)return net.minecraft.world.InteractionResult.PASS;
            var counter=service.boundCounter(level.dimension().location().toString(),hit.getBlockPos());
            if(counter==null)return net.minecraft.world.InteractionResult.PASS;
            if(hand==net.minecraft.world.InteractionHand.MAIN_HAND)try {
                if(!net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock()).toString().equals(counter.blockId()))throw new IllegalArgumentException("Bound block changed; ask operator to rebind");
                service.bazaar().open((net.minecraft.server.level.ServerPlayer)player,counter);
            }catch(RuntimeException e){player.sendSystemMessage(Component.literal("Bazaar: "+e.getMessage()));}
            return net.minecraft.world.InteractionResult.SUCCESS;
        });
        UseItemCallback.EVENT.register((player,level,hand)->{
            ItemStack stack=player.getItemInHand(hand);
            if(level.isClientSide || !stack.is(Items.COMPASS) || stack.getTag()==null || !stack.getTag().getBoolean("grxmteamsSelector") || service==null)return InteractionResultHolder.pass(stack);
            if(!service.configuration().limits.selectorEnabled)return InteractionResultHolder.pass(stack);
            try {
                TeamState.Chunk chunk=new TeamState.Chunk(level.dimension().location().toString(),player.chunkPosition().x,player.chunkPosition().z);
                if(player.isShiftKeyDown())service.removeChunk(player.getUUID(),chunk); else service.addChunk(player.getUUID(),chunk);
                player.sendSystemMessage(Component.literal(service.message("success")));
            } catch(RuntimeException e) { player.sendSystemMessage(Component.literal(service.message("errorPrefix")+e.getMessage())); }
            return InteractionResultHolder.success(stack);
        });
    }
}
