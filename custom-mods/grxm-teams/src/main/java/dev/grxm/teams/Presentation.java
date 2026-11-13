package dev.grxm.teams;

import com.mojang.authlib.GameProfile;
import dev.grxm.teams.TeamState.Team;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.*;
import java.util.*;

/** Vanilla scoreboard formatting, leaving signed player chat untouched. */
public final class Presentation {
    private static final String MARKER="grxmteams:";
    private Presentation() {}
    public static String key(UUID id) { return "gt"+id.toString().replace("-","").substring(0,14); }
    private static boolean owned(PlayerTeam t) {
        if(t==null || !t.getDisplayName().getString().startsWith(MARKER))return false;
        try { UUID id=UUID.fromString(t.getDisplayName().getString().substring(MARKER.length())); return t.getDisplayName().getString().equals(MARKER+id) && t.getName().equals(key(id)); }
        catch(IllegalArgumentException e) { return false; }
    }
    private static String playerName(MinecraftServer server,UUID id) {
        var online=server.getPlayerList().getPlayer(id); if(online!=null)return online.getGameProfile().getName();
        return server.getProfileCache()==null?null:server.getProfileCache().get(id).map(GameProfile::getName).orElse(null);
    }
    public static void checkPlayerAvailable(MinecraftServer server,TeamState state,UUID id) {
        String name=playerName(server,id); if(name==null)return;
        PlayerTeam t=server.getScoreboard().getPlayersTeam(name);
        if(t!=null && !owned(t))throw new IllegalArgumentException("Player belongs to an unrelated vanilla scoreboard team; remove that membership explicitly first");
    }
    public static void checkKeyAvailable(MinecraftServer server,UUID id) {
        PlayerTeam t=server.getScoreboard().getPlayerTeam(key(id));
        if(t!=null && !t.getDisplayName().getString().equals(MARKER+id))throw new IllegalArgumentException("Scoreboard identifier collision; refusing to overwrite existing team");
    }
    public static void refresh(MinecraftServer server,TeamState state,TeamsConfig config) {
        Scoreboard board=server.getScoreboard(); Set<String> current=new HashSet<>();
        for(Team t:state.teams.values()) { checkKeyAvailable(server,t.id); current.add(key(t.id)); }
        for(PlayerTeam t:new ArrayList<>(board.getPlayerTeams())) if(owned(t) && !current.contains(t.getName()))board.removePlayerTeam(t);
        for(Team t:state.teams.values()) {
            String key=key(t.id); PlayerTeam scoreboard=board.getPlayerTeam(key); if(scoreboard==null)scoreboard=board.addPlayerTeam(key);
            scoreboard.setDisplayName(Component.literal(MARKER+t.id));
            scoreboard.setColor(ChatFormatting.getByName(t.color));
            scoreboard.setPlayerPrefix(Component.empty()
                .append(Component.literal(config.presentation.badges.get(t.badge)).withStyle(Style.EMPTY.withFont(new ResourceLocation(config.presentation.badgeFont)).withColor(ChatFormatting.WHITE)))
                .append(Component.literal(" "+config.presentation.prefixBefore+t.name+config.presentation.prefixAfter)));
            Set<String> wanted=new HashSet<>(); for(UUID member:t.members.keySet()) { String name=playerName(server,member); if(name!=null)wanted.add(name); }
            for(String name:new HashSet<>(scoreboard.getPlayers()))if(!wanted.contains(name))board.removePlayerFromTeam(name,scoreboard);
            for(String name:wanted) {
                PlayerTeam existing=board.getPlayersTeam(name);
                if(existing==null || owned(existing))board.addPlayerToTeam(name,scoreboard);
                else TeamsMod.LOGGER.warn("Not overwriting unrelated scoreboard membership for {}",name);
            }
        }
    }
}
