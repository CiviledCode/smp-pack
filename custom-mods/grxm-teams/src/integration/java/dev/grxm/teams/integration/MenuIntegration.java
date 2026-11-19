package dev.grxm.teams.integration;

import dev.grxm.teams.*;
import dev.grxm.teams.TeamState.*;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import java.nio.file.*;
import java.util.*;

/** Separate opt-in entrypoint; run using scripts/menu_integration_suite.py, never on a real world. */
public final class MenuIntegration implements ModInitializer {
    private boolean done;
    private static final UUID OWNER=UUID.fromString("1c1f79b8-a7a6-43b0-bb88-f2a8124c0001"), MEMBER=UUID.fromString("1c1f79b8-a7a6-43b0-bb88-f2a8124c0002"), ADMIN=UUID.fromString("1c1f79b8-a7a6-43b0-bb88-f2a8124c0003");
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static class Capture extends Connection {
        Capture(){super(PacketFlow.SERVERBOUND);}
        @Override public void send(Packet<?> packet){}
        @Override public void send(Packet<?> packet,PacketSendListener listener){}
        @Override public java.net.SocketAddress getRemoteAddress(){return new java.net.InetSocketAddress("127.0.0.1",0);}
    }
    private static ServerPlayer player(MinecraftServer server,UUID id,String name){ServerPlayer p=new ServerPlayer(server,server.overworld(),new com.mojang.authlib.GameProfile(id,name));new ServerGamePacketListenerImpl(server,new Capture(),p);return p;}
    private static void click(ServerPlayer p,int slot){AbstractContainerMenu menu=p.containerMenu;p.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId,menu.getStateId(),slot,0,ClickType.PICKUP,ItemStack.EMPTY,new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>()));}
    private static void clickNamed(ServerPlayer p,String name){int found=-1;for(int n=0;n<Math.min(54,p.containerMenu.slots.size());n++)if(p.containerMenu.getSlot(n).getItem().getHoverName().getString().equals(name)){found=n;break;}check(found>=0,"Missing UI action: "+name);click(p,found);}
    private static void enter(ServerPlayer p,String value){check(p.containerMenu instanceof AnvilMenu,"Real vanilla AnvilMenu");p.connection.handleRenameItem(new ServerboundRenameItemPacket(value));click(p,2);}
    @Override public void onInitialize(){if(!Boolean.getBoolean("grxmteams.menuIntegration")||!Files.isRegularFile(Path.of("grxmteams-integration-ALLOW")))throw new IllegalStateException("Menu integration requires opt-in and isolated marker");ServerTickEvents.END_SERVER_TICK.register(server->{if(done)return;done=true;try{run(server);Files.writeString(Path.of("grxmteams-menu-PASS.txt"),"PASS vanilla container/rename packets: create, aliases, inventory isolation, invites, role/transfer, style, treasury confirmation, chunks, nested config save/invalid/stale/permission checks; config file preserved on invalid edits");}catch(Throwable e){TeamsMod.LOGGER.error("Menu integration failed",e);try{Files.writeString(Path.of("grxmteams-menu-FAIL.txt"),e.toString());}catch(Exception ignored){}}finally{server.halt(false);}});}
    private static void run(MinecraftServer server)throws Exception{
        TeamService s=TeamsMod.service();check(s.failure()==null,"Service ready");ServerPlayer owner=player(server,OWNER,"MenuOwner"),member=player(server,MEMBER,"MenuMember"),admin=player(server,ADMIN,"MenuAdmin");server.getPlayerList().op(admin.getGameProfile());
        check(Numismatics.BANK.getAccount(OWNER)==null&&Numismatics.BANK.getAccount(MEMBER)==null,"No personal bank exists for menu fixture players");
        check(server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(),"teams")>0,"Bare /teams executes");clickNamed(owner,"Create team");enter(owner,"Menu Team");check(s.teamOf(OWNER).name.equals("Menu Team"),"Create through rename packet");
        owner.getInventory().setItem(0,new ItemStack(Items.DIAMOND,7));AbstractContainerMenu menu=owner.containerMenu;for(ClickType type:ClickType.values())menu.clicked(54,0,type,owner);check(owner.getInventory().getItem(0).getCount()==7&&menu.getCarried().isEmpty(),"All click modes preserve real inventory");
        s.invite(OWNER,MEMBER);
        String code=s.invitationCode(MEMBER,s.teamOf(OWNER).id);boolean outsiderRejected=false;
        try{s.acceptCode(ADMIN,code);}catch(IllegalArgumentException e){outsiderRejected=true;}
        check(outsiderRejected&&!s.teamOf(OWNER).members.containsKey(ADMIN),"Known code cannot bypass personal invitation");
        TeamMenus.open(member);clickNamed(member,"Accept invitation code");enter(member,code.toLowerCase(Locale.ROOT));check(s.teamOf(MEMBER).id.equals(s.teamOf(OWNER).id),"Case-insensitive code accepted through real anvil packet");
        TeamMenus.open(owner);clickNamed(owner,"Members / invitations");clickNamed(owner,MEMBER.toString());clickNamed(owner,"Set officer");clickNamed(owner,"Confirm set officer");check(s.teamOf(MEMBER).members.get(MEMBER)==Role.OFFICER,"Role confirmation");
        TeamMenus.open(owner);clickNamed(owner,"Team style");clickNamed(owner,"Rename: Menu Team");enter(owner,"Renamed Team");check(s.teamOf(OWNER).name.equals("Renamed Team"),"Rename through menu");
        // Stale ownership confirmation must never transfer to a player who has since left.
        TeamMenus.open(owner);clickNamed(owner,"Members / invitations");clickNamed(owner,MEMBER.toString());clickNamed(owner,"Transfer ownership");s.leave(MEMBER);clickNamed(owner,"Confirm transfer ownership");check(s.teamOf(OWNER).members.get(OWNER)==Role.OWNER,"Stale transfer refused");
        s.invite(OWNER,MEMBER);TeamMenus.open(member);clickNamed(member,"Pending invitations");clickNamed(member,"Renamed Team");clickNamed(member,"Confirm join Renamed Team");check(s.teamOf(MEMBER).id.equals(s.teamOf(OWNER).id),"Pending-invitation list accepts without typing a reference");s.leave(MEMBER);
        TeamMenus.open(owner);clickNamed(owner,"Chunk map");clickNamed(owner,"Add current chunk");click(owner,20);check(s.teamOf(OWNER).chunks.size()==1,"Chunk selection confirmed");
        TeamMenus.administration(admin);clickNamed(admin,"Edit all configuration");clickNamed(admin,"billing");clickNamed(admin,"denomination");clickNamed(admin,"Replace value");enter(admin,"Menu units");clickNamed(admin,"Back");clickNamed(admin,"Up");clickNamed(admin,"Validate and save draft");clickNamed(admin,"Confirm save configuration");check(s.configuration().billing.denomination.equals("Menu units"),"Teamless admin config applied");check(Files.readString(Path.of("config/grxmteams.json")).contains("Menu units"),"Config persisted");
        byte[] disk=Files.readAllBytes(Path.of("config/grxmteams.json"));String expected=StateCodec.GSON.toJson(s.configuration());TeamsConfig invalid=s.configuration();invalid.limits.maxTeams=0;boolean rejected=false;try{TeamsMod.saveConfiguration(invalid,expected);}catch(IllegalArgumentException e){rejected=true;}check(rejected&&Arrays.equals(disk,Files.readAllBytes(Path.of("config/grxmteams.json"))),"Invalid edit preserves file");
        // Service-level validation failures must restore the previous exact file too.
        TeamsConfig excludes=s.configuration();excludes.limits.maxNameLength=1;rejected=false;try{TeamsMod.saveConfiguration(excludes,expected);}catch(IllegalArgumentException e){rejected=true;}check(rejected&&Arrays.equals(disk,Files.readAllBytes(Path.of("config/grxmteams.json")))&&StateCodec.GSON.toJson(s.configuration()).equals(expected),"State-incompatible edit rolls disk/live config back");
        TeamsConfig conflict=s.configuration();conflict.limits.maxNameLength=40;TeamsMod.saveConfiguration(conflict,expected);rejected=false;try{TeamsMod.saveConfiguration(s.configuration(),expected);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Stale config draft refused");
        // No personal account: physical spur exchange is confirmed through actual menu packets.
        var spur=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation("numismatics:spur"));
        owner.getInventory().setItem(1,new ItemStack(spur,64));owner.getInventory().setItem(2,new ItemStack(spur,64));owner.getInventory().setItem(3,new ItemStack(spur,64));owner.getInventory().setItem(4,new ItemStack(spur,8));
        TeamsConfig finance=s.configuration();finance.bazaar.enabled=true;TeamsMod.saveConfiguration(finance,StateCodec.GSON.toJson(s.configuration()));TeamMenus.open(owner);clickNamed(owner,"Treasury");clickNamed(owner,"Deposit");enter(owner,"100");check(s.balance(OWNER)==0&&owner.getInventory().countItem(spur)==200,"No spur or balance mutation before confirmation");clickNamed(owner,"Confirm deposit 100");check(s.balance(OWNER)==100&&owner.getInventory().countItem(spur)==100,"Confirmed physical deposit exactly once");
        clickNamed(owner,"Withdraw");enter(owner,"20");clickNamed(owner,"Confirm withdraw 20");check(s.balance(OWNER)==80&&owner.getInventory().countItem(spur)==120,"Confirmed physical withdrawal");clickNamed(owner,"Withdraw");enter(owner,"10");s.deposit(owner,1);clickNamed(owner,"Confirm withdraw 10");check(s.balance(OWNER)==81&&owner.getInventory().countItem(spur)==119,"Changed treasury invalidates pending withdrawal without item mutation");
        check(server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(),"smpteam treasury deposit 1")>0,"Physical command deposit");
        check(server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(),"smpteam treasury withdraw 1")>0,"Physical command withdrawal");
        check(s.balance(OWNER)==81&&owner.getInventory().countItem(spur)==119,"Command spur roundtrip preserves balance and items");
        check(Numismatics.BANK.getAccount(OWNER)==null,"Physical UI/commands neither require nor create a personal bank");
        TeamMenus.administration(admin);clickNamed(admin,"Edit all configuration");clickNamed(admin,"bazaar");clickNamed(admin,"pool");clickNamed(admin,"0");clickNamed(admin,"minItemCount");clickNamed(admin,"Replace value");enter(admin,"70");clickNamed(admin,"Back");clickNamed(admin,"Up");clickNamed(admin,"Up");clickNamed(admin,"Up");clickNamed(admin,"Validate and save draft");clickNamed(admin,"Confirm save configuration");check(s.configuration().bazaar.pool.get(0).minItemCount==70,"Nested pool edit persisted");
        TeamsConfig expanded=s.configuration();expanded.limits.maxChunksPerTeam=100;expanded.limits.maxChunksGlobal=256;expanded.limits.allowedDimensions.add("minecraft:the_nether");TeamsMod.saveConfiguration(expanded,StateCodec.GSON.toJson(s.configuration()));for(int n=0;n<44;n++)s.addChunk(OWNER,new Chunk("minecraft:overworld",100+n,100));s.addChunk(OWNER,new Chunk("minecraft:the_nether",100,100));TeamMenus.open(owner);clickNamed(owner,"Chunk map");clickNamed(owner,"List all dimensions");check(owner.containerMenu.getSlot(0).getItem().is(Items.YELLOW_CONCRETE),"Selected but inactive chunk marked yellow");clickNamed(owner,"Next page");clickNamed(owner,"minecraft:the_nether 100, 100");click(owner,20);check(s.teamOf(OWNER).chunks.size()==45&&!s.teamOf(OWNER).chunks.contains(new Chunk("minecraft:the_nether",100,100)),"Paginated cross-dimension chunk removal");
        admin.absMoveTo(.5,1,.5,0,0);net.minecraft.core.BlockPos counter=new net.minecraft.core.BlockPos(0,2,3);server.overworld().setBlockAndUpdate(counter,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());TeamMenus.administration(admin);clickNamed(admin,"Bind looked-at bazaar block");clickNamed(admin,"Next page");clickNamed(admin,"Offer slot 46");clickNamed(admin,"Bind captured target");clickNamed(admin,"Confirm bind slot 46");check(s.bazaar().snapshot().counters.containsKey(46),"Paginated high counter binding rechecks real POV ray");
        TeamMenus.administration(admin);server.getPlayerList().deop(admin.getGameProfile());AbstractContainerMenu denied=admin.containerMenu;clickNamed(admin,"Edit all configuration");check(admin.containerMenu==denied,"Permission revoked after opening denied on click");
        // Anvil close must not return either fake paper stack.
        TeamMenus.open(owner);clickNamed(owner,"Team style");clickNamed(owner,"Rename: Renamed Team");int before=owner.getInventory().countItem(Items.PAPER);owner.closeContainer();check(owner.getInventory().countItem(Items.PAPER)==before,"No fake anvil paper on close");
        // Last: force an actual bank-write failure after billing may debit the treasury.
        // Both entrypoint opt-in and the disposable marker are required before this test can run.
        TeamsConfig applied=s.configuration();applied.billing.enabled=true;applied.billing.scalingFactor="1";
        String expectedBefore=StateCodec.GSON.toJson(s.configuration());
        var treasury=Numismatics.BANK.getAccount(s.teamOf(OWNER).account);
        int balanceBefore=treasury.getBalance();
        Path bank=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/numismatics_bank.dat");
        Files.copy(bank,bank.resolveSibling("numismatics_bank.before-fault.dat"));
        Files.delete(bank);Files.createDirectory(bank);Files.writeString(bank.resolve("deliberate-blocker"),"isolated test only");
        String outcome="";try{TeamsMod.saveConfiguration(applied,expectedBefore);}catch(IllegalStateException e){outcome=e.getMessage();}
        check(s.failure()!=null,"Real failed commit quarantines Teams");
        check(outcome.contains("Configuration saved, but Teams is quarantined")&&outcome.contains("operator recovery"),"No normal save success after failed-closed reconcile");
        String candidate=StateCodec.GSON.toJson(applied);
        check(StateCodec.GSON.toJson(s.configuration()).equals(candidate),"Applied candidate retained despite quarantine");
        check(StateCodec.GSON.toJson(StateCodec.GSON.fromJson(Files.readString(Path.of("config/grxmteams.json")),TeamsConfig.class)).equals(candidate),"Saved candidate retained on disk");
        check(StateCodec.GSON.toJson(me.shedaniel.autoconfig.AutoConfig.getConfigHolder(TeamsConfig.class).getConfig()).equals(candidate),"Holder agrees with disk and live service");
        check(treasury.getBalance()<balanceBefore,"Real debit before commit fault is not rolled back or retried");
        Files.writeString(Path.of("quarantine-proof.txt"),outcome+"\nBefore debit: "+balanceBefore+"; after: "+treasury.getBalance()+"\nDisk/live/holder candidate retained; no success message.\n");
    }
}
