package dev.grxm.teams;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;

@Config(name = "grxmteams")
public class TeamsConfig implements ConfigData {
    public Billing billing = new Billing();
    public Bazaar bazaar = new Bazaar();
    public static class PoolEntry {
        public String itemId;
        public int minItemCount=64,maxItemCount=128,minItemPrice=1,maxItemPrice=4;
        public double rarityWeight=1;
        public PoolEntry() {}
        public PoolEntry(String id) { itemId=id; }
    }
    public static class Bazaar {
        public boolean enabled=false, payPersonal=false;
        public int offerCount=3, journalCapacity=2048;
        public double bindReach=6;
        public boolean displaysEnabled=true;
        public int displayRadius=40,displayRefreshTicks=20;
        public double itemHeight=1.3,textHeight=2.2;
        public String displayText="{item}\nTeam remaining: {remaining} / {quota}\n{price} {denomination} / item\nReset: {reset}";
        public String noTeamText="Join a team to sell",closedText="Bazaar closed",inactiveText="Counter inactive this week";
        public long periodSeconds=604800, epochSeconds=345600; // Monday UTC
        public String denomination="spurs", title="Weekly bazaar";
        public java.util.List<PoolEntry> pool=new ArrayList<>();
        public Bazaar() { for(String id:List.of("nether_star","end_rod","shulker_shell","wither_skeleton_skull","ancient_debris","diamond","emerald","bone_block","iron_block"))pool.add(new PoolEntry("minecraft:"+id)); }
        public void validate() {
            if(offerCount<2||offerCount>3||periodSeconds<1||periodSeconds>31536000||epochSeconds<0||epochSeconds>31536000||journalCapacity<1||journalCapacity>4096||!Double.isFinite(bindReach)||bindReach<1||bindReach>8)throw new IllegalArgumentException("Invalid bazaar limits");
            if(!"spurs".equals(denomination))throw new IllegalArgumentException("Bazaar prices are native spurs, not a convertible display denomination");
            if(displayRadius<8||displayRadius>64||displayRefreshTicks<10||displayRefreshTicks>100||!Double.isFinite(itemHeight)||!Double.isFinite(textHeight)||itemHeight<.5||itemHeight>4||textHeight<1||textHeight>6)throw new IllegalArgumentException("Invalid display limits");
            if(displayText==null||displayText.length()>1024)throw new IllegalArgumentException("Invalid display text");for(String line:displayText.split("\\n",-1))plain(line,256);
            plain(noTeamText,128);plain(closedText,128);plain(inactiveText,128);
            plain(denomination,64); plain(title,64);
            if(pool==null||pool.size()>256)throw new IllegalArgumentException("Invalid pool");
            Set<String> ids=new HashSet<>(); int positive=0;
            for(PoolEntry e:pool) {
                if(e==null||e.itemId==null||!e.itemId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")||!ids.add(e.itemId)||e.minItemCount<1||e.maxItemCount<e.minItemCount||e.maxItemCount>1000000||e.minItemPrice<1||e.maxItemPrice<e.minItemPrice||!Double.isFinite(e.rarityWeight)||e.rarityWeight<0||e.rarityWeight>1000000)throw new IllegalArgumentException("Invalid bazaar pool entry");
                if((long)e.maxItemPrice*e.maxItemCount>Integer.MAX_VALUE)throw new IllegalArgumentException("Bazaar maximum payout overflows");
                if(e.rarityWeight>0)positive++;
            }
            if(positive<offerCount)throw new IllegalArgumentException("Not enough positive-weight unique items");
        }
    }
    public Limits limits = new Limits();
    public Permissions permissions = new Permissions();
    public Presentation presentation = new Presentation();
    public ResourcePack resourcePack = new ResourcePack();
    public Map<String, String> messages = new LinkedHashMap<>(Map.of(
        "success", "Done.", "errorPrefix", "Teams: ", "suspended", "Chunk lease suspended: insufficient treasury funds.",
        "renewed", "Chunk lease renewed.", "invite", "You were invited to {team}. Use /smpteam accept {id}"));
    public static class Billing {
        public boolean enabled = false;
        public String scalingFactor = "0";
        public String teamMemberScaling = "1";
        public String denomination = "smallest Numismatics units";
        public long periodSeconds = 604800;
        public int checkEveryTicks = 20;
        public int maximumBill = 1000000000;
    }
    public static class Limits {
        public int maxTeams = 256, maxMembers = 32, maxChunksPerTeam = 16, maxChunksGlobal = 64;
        public int maxInvitesPerTeam = 64, maxNameLength = 24, ticketsPerTick = 2;
        public long inviteSeconds = 86400;
        public List<String> allowedDimensions = new ArrayList<>(List.of("minecraft:overworld"));
        public boolean selectorEnabled = true;
    }
    public static class Permissions {
        public boolean officersManageChunks = true, officersWithdraw = false, officersInvite = true, officersStyle = true;
        public int adminPermissionLevel = 2;
    }
    public static class Presentation {
        public String defaultColor = "white", defaultBadge = "gear";
        public String prefixBefore = "[", prefixAfter = "] ";
        public String badgeFont = "grxmteams:badges";
        public Map<String, String> badges = new LinkedHashMap<>();
        public Presentation() { String[] names={"gear","crown","flame","leaf","anchor","star","diamond","shield"}; for(int i=0;i<names.length;i++) badges.put(names[i], String.valueOf((char)(0xe000+i))); }
        public List<String> colors = new ArrayList<>(List.of("black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray","dark_gray","blue","green","aqua","red","light_purple","yellow","white"));
    }
    public static class ResourcePack {
        public boolean enabled = true, required = true;
        public String url = "https://raw.githubusercontent.com/CiviledCode/smp-pack/master/assets/grxm-team-badges-1.0.0.zip";
        public String sha1 = "bc02f256708eb0d6928705c5f661418b3c98fb74";
        public String prompt = "Team chat badges";
    }
    @Override public void validatePostLoad() throws ValidationException {
        try { validate(); } catch (RuntimeException e) { throw new ValidationException(e.getMessage()); }
    }
    public void validate() {
        Objects.requireNonNull(bazaar).validate();
        Objects.requireNonNull(billing); Objects.requireNonNull(limits); Objects.requireNonNull(permissions); Objects.requireNonNull(presentation); Objects.requireNonNull(resourcePack); Objects.requireNonNull(messages);
        decimal(billing.scalingFactor); decimal(billing.teamMemberScaling);
        if (billing.enabled && (decimal(billing.scalingFactor).signum()==0 || decimal(billing.teamMemberScaling).signum()==0)) fail("Enabled billing requires positive explicit rates");
        if (billing.periodSeconds < 1 || billing.periodSeconds > 31536000 || billing.checkEveryTicks < 1 || billing.checkEveryTicks > 1200 || billing.maximumBill < 1) fail("Invalid billing period/check interval/cap");
        if (limits.maxTeams<1 || limits.maxTeams>10000 || limits.maxMembers<1 || limits.maxMembers>1000 || limits.maxChunksPerTeam<1 || limits.maxChunksGlobal<1 || limits.maxChunksGlobal>4096 || limits.maxInvitesPerTeam<1 || limits.maxInvitesPerTeam>10000 || limits.maxNameLength<1 || limits.maxNameLength>48 || limits.ticketsPerTick<1 || limits.ticketsPerTick>64 || limits.inviteSeconds<1 || limits.inviteSeconds>31536000) fail("Invalid limits");
        if (permissions.adminPermissionLevel<0 || permissions.adminPermissionLevel>4) fail("Invalid permission level");
        if (limits.allowedDimensions==null || limits.allowedDimensions.isEmpty()) fail("No allowed dimensions");
        for(String d:limits.allowedDimensions) if(!d.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) fail("Invalid dimension: "+d);
        if (presentation.badges==null || presentation.badges.isEmpty() || presentation.badges.size()>64 || !presentation.badges.containsKey(presentation.defaultBadge)) fail("Invalid badges");
        presentation.badges.forEach((k,v)->{if(!k.matches("[a-z0-9_-]{1,24}") || v==null || v.codePointCount(0,v.length())!=1 || Character.isISOControl(v.codePointAt(0))) fail("Invalid badge");});
        Set<String> validColors=Set.of("black","dark_blue","dark_green","dark_aqua","dark_red","dark_purple","gold","gray","dark_gray","blue","green","aqua","red","light_purple","yellow","white");
        if(presentation.colors==null || !validColors.containsAll(presentation.colors) || !presentation.colors.contains(presentation.defaultColor)) fail("Invalid colors");
        if(!presentation.badgeFont.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) fail("Invalid font");
        plain(presentation.prefixBefore,32); plain(presentation.prefixAfter,32);
        messages.forEach((k,v)->plain(v,512));
        if(resourcePack.enabled) { URI uri=URI.create(resourcePack.url); if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || resourcePack.url.length()>2048 || !resourcePack.sha1.matches("[0-9a-fA-F]{40}")) fail("Invalid resource pack URL/SHA1"); }
        plain(resourcePack.prompt,256);
        Prices.total(limits.maxChunksPerTeam,limits.maxMembers,billing);
    }
    public static BigDecimal decimal(String s) { if(s==null || !s.matches("[0-9]{1,12}(\\.[0-9]{1,9})?")) throw new IllegalArgumentException("Rates must be nonnegative plain decimals with at most 9 fractional digits"); return new BigDecimal(s); }
    public static void plain(String s,int length) { if(s==null || s.length()>length || s.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x00a7||Character.getType(c)==Character.FORMAT)) fail("Invalid display text"); }
    private static void fail(String s) { throw new IllegalArgumentException(s); }
}
