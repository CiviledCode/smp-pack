package dev.grxm.teams;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import java.util.*;
import static net.minecraft.commands.Commands.*;

public final class TeamCommands {
    private TeamCommands() {}
    @FunctionalInterface private interface Task { String run(CommandContext<CommandSourceStack> c) throws Exception; }
    private static int run(CommandContext<CommandSourceStack> c,Task task) {
        try { String text=task.run(c); c.getSource().sendSuccess(()->Component.literal(text),false); return 1; }
        catch(Exception e) { c.getSource().sendFailure(Component.literal(s().message("errorPrefix")+e.getMessage())); return 0; }
    }
    private static TeamService s() { return TeamsMod.service(); }
    private static UUID actor(CommandContext<CommandSourceStack> c) throws Exception { return c.getSource().getPlayerOrException().getUUID(); }
    private static UUID uuid(String value) { if(!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("Expected canonical UUID"); return UUID.fromString(value); }
    private static UUID target(CommandContext<CommandSourceStack> c) {
        String value=StringArgumentType.getString(c,"player"); var online=c.getSource().getServer().getPlayerList().getPlayerByName(value); return online==null?uuid(value):online.getUUID();
    }
    private static String ok() { return s().message("success"); }
    private static TeamState.Chunk here(CommandContext<CommandSourceStack> c) throws Exception {
        ServerPlayer p=c.getSource().getPlayerOrException(); return new TeamState.Chunk(p.level().dimension().location().toString(),p.chunkPosition().x,p.chunkPosition().z);
    }
    private static TeamState.Chunk specified(CommandContext<CommandSourceStack> c) { return new TeamState.Chunk(StringArgumentType.getString(c,"dimension"),IntegerArgumentType.getInteger(c,"x"),IntegerArgumentType.getInteger(c,"z")); }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack,String> coords(Task task) {
        return argument("dimension",StringArgumentType.string()).then(argument("x",IntegerArgumentType.integer(-1875000,1875000)).then(argument("z",IntegerArgumentType.integer(-1875000,1875000)).executes(c->run(c,task))));
    }
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> root=literal("smpteam").executes(c->run(c,x->"/smpteam create <name> | invite <online-name/UUID> | accept <team UUID> | leave | transfer <player> | role <player> <officer/member> | style <color> <badge> <quoted-name> | color <color> | badge <badge> | rename <name> | treasury <balance/deposit/withdraw> | chunks <add/remove/list/cost/status> | selector | disband confirm | admin <reload/billingstatus/remove>"));
        root.then(literal("create").then(argument("name",StringArgumentType.greedyString()).executes(c->run(c,x->"Created team "+s().create(actor(c),StringArgumentType.getString(c,"name"))))));
        root.then(literal("invite").then(argument("player",StringArgumentType.word()).executes(c->run(c,x->{s().invite(actor(c),target(c));return ok();}))));
        root.then(literal("accept").then(argument("team",StringArgumentType.word()).executes(c->run(c,x->{s().accept(actor(c),uuid(StringArgumentType.getString(c,"team")));return ok();}))));
        root.then(literal("leave").executes(c->run(c,x->{s().leave(actor(c));return ok();})));
        root.then(literal("transfer").then(argument("player",StringArgumentType.word()).executes(c->run(c,x->{s().transfer(actor(c),target(c));return ok();}))));
        root.then(literal("role").then(argument("player",StringArgumentType.word()).then(argument("role",StringArgumentType.word()).suggests((c,b)->{b.suggest("officer");b.suggest("member");return b.buildFuture();}).executes(c->run(c,x->{String r=StringArgumentType.getString(c,"role");if(!Set.of("officer","member").contains(r))throw new IllegalArgumentException("Role must be officer or member");s().role(actor(c),target(c),TeamState.Role.valueOf(r.toUpperCase(Locale.ROOT)));return ok();})))));
        root.then(literal("disband").then(literal("confirm").executes(c->run(c,x->{s().disband(actor(c));return ok();}))));
        root.then(literal("style").then(argument("color",StringArgumentType.word()).then(argument("badge",StringArgumentType.word()).then(argument("name",StringArgumentType.string()).executes(c->run(c,x->{s().style(actor(c),StringArgumentType.getString(c,"name"),StringArgumentType.getString(c,"color"),StringArgumentType.getString(c,"badge"));return ok();}))))));
        root.then(literal("color").then(argument("color",StringArgumentType.word()).suggests((c,b)->{s().configuration().presentation.colors.forEach(b::suggest);return b.buildFuture();}).executes(c->run(c,x->{var t=s().teamOf(actor(c));s().style(actor(c),t.name,StringArgumentType.getString(c,"color"),t.badge);return ok();}))));
        root.then(literal("badge").then(argument("badge",StringArgumentType.word()).suggests((c,b)->{s().configuration().presentation.badges.keySet().forEach(b::suggest);return b.buildFuture();}).executes(c->run(c,x->{var t=s().teamOf(actor(c));s().style(actor(c),t.name,t.color,StringArgumentType.getString(c,"badge"));return ok();}))));
        root.then(literal("rename").then(argument("name",StringArgumentType.greedyString()).executes(c->run(c,x->{var t=s().teamOf(actor(c));s().style(actor(c),StringArgumentType.getString(c,"name"),t.color,t.badge);return ok();}))));
        root.then(literal("status").executes(c->run(c,x->s().status(actor(c)))));
        root.then(literal("treasury")
            .then(literal("balance").executes(c->run(c,x->s().balance(actor(c))+" "+s().configuration().billing.denomination)))
            .then(literal("deposit").then(argument("amount",IntegerArgumentType.integer(1)).executes(c->run(c,x->{s().deposit(actor(c),IntegerArgumentType.getInteger(c,"amount"));return ok();}))))
            .then(literal("withdraw").then(argument("amount",IntegerArgumentType.integer(1)).executes(c->run(c,x->{s().withdraw(actor(c),IntegerArgumentType.getInteger(c,"amount"));return ok();})))));
        root.then(literal("chunks")
            .then(literal("add").executes(c->run(c,x->{s().addChunk(actor(c),here(c));return ok();})))
            .then(literal("remove").executes(c->run(c,x->{s().removeChunk(actor(c),here(c));return ok();})).then(coords(c->{s().removeChunk(actor(c),specified(c));return ok();})))
            .then(literal("list").executes(c->run(c,x->s().teamOf(actor(c)).chunks.stream().sorted().map(Object::toString).reduce((a,b)->a+"\n"+b).orElse("No selected chunks"))))
            .then(literal("cost").executes(c->run(c,x->"Total next-term bill: "+s().cost(actor(c))+" "+s().configuration().billing.denomination+". Current prepaid membership snapshot: "+s().teamOf(actor(c)).memberSnapshot)))
            .then(literal("status").executes(c->run(c,x->s().status(actor(c))))));
        root.then(literal("selector").executes(c->run(c,x->{
            if(!s().configuration().limits.selectorEnabled)throw new IllegalArgumentException("Selector disabled");
            s().teamOf(actor(c)); ServerPlayer player=c.getSource().getPlayerOrException();
            for(ItemStack existing:player.getInventory().items)if(existing.is(Items.COMPASS) && existing.hasTag() && existing.getTag().getBoolean("grxmteamsSelector"))throw new IllegalArgumentException("You already have a selector");
            ItemStack stack=new ItemStack(Items.COMPASS); stack.getOrCreateTag().putBoolean("grxmteamsSelector",true); stack.setHoverName(Component.literal("Team chunk selector (sneak: remove)"));
            if(!player.getInventory().add(stack))player.drop(stack,false);return ok();
        })));
        root.then(literal("admin").requires(src->src.hasPermission(s().configuration().permissions.adminPermissionLevel))
            .then(literal("reload").executes(c->run(c,x->{TeamsMod.reloadConfiguration();return "Reloaded valid configuration. "+s().billingStatus();})))
            .then(literal("billingstatus").executes(c->run(c,x->s().billingStatus())))
            .then(literal("bazaar")
                .then(literal("bind").then(argument("slot",IntegerArgumentType.integer(1,3)).executes(c->run(c,x->{s().bazaar().bindLook(c.getSource().getPlayerOrException(),IntegerArgumentType.getInteger(c,"slot"));return "Bound looked-at block to weekly offer slot "+IntegerArgumentType.getInteger(c,"slot");}))))
                .then(literal("unbind").then(argument("slot",IntegerArgumentType.integer(1,3)).executes(c->run(c,x->{s().bazaar().unbind(IntegerArgumentType.getInteger(c,"slot"));return ok();}))))
                .then(literal("status").executes(c->run(c,x->{var m=s().bazaar().snapshot();return "bazaar="+s().configuration().bazaar.enabled+", generation="+m.generation+", reset="+m.end+", offers="+StateCodec.GSON.toJson(m.offers)+", counters="+StateCodec.GSON.toJson(m.counters)+", journal="+m.journal.size()+"/"+s().configuration().bazaar.journalCapacity+", pending="+m.journal.values().stream().filter(tx->!tx.committed).count();}))))
            .then(literal("remove").then(argument("team",StringArgumentType.word()).then(coords(c->{s().adminRemoveChunk(uuid(StringArgumentType.getString(c,"team")),specified(c));return ok();})))));
        d.register(root);
        d.register(literal("teams").redirect(d.getRoot().getChild("smpteam")));
    }
}
