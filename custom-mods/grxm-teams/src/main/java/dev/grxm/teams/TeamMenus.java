package dev.grxm.teams;

import com.google.gson.*;
import dev.grxm.teams.TeamState.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.BlockHitResult;
import java.util.*;
import java.util.function.Consumer;

/** Server-owned vanilla chest/anvil screens. Display items never enter any inventory. */
public final class TeamMenus {
    private TeamMenus() {}
    private static TeamService s(){return TeamsMod.service();}
    private static UUID canonicalUuid(String value){if(!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IllegalArgumentException("Use an online player name or canonical UUID");return UUID.fromString(value);}
    private static UUID id(ServerPlayer p){return p.getUUID();}
    private static void error(ServerPlayer p,Exception e){p.sendSystemMessage(Component.literal("Teams: "+e.getMessage()));}
    private static void admin(ServerPlayer p){if(!p.hasPermissions(s().configuration().permissions.adminPermissionLevel))throw new IllegalArgumentException("Administrator permission required");}
    private static UUID membership(ServerPlayer p){return s().snapshot().teams.values().stream().filter(t->t.members.containsKey(id(p))).map(t->t.id).findFirst().orElse(null);}
    private static Runnable guard(ServerPlayer p){UUID expected=membership(p);return ()->{if(!Objects.equals(expected,membership(p)))throw new IllegalArgumentException("Team changed; reopen /teams");};}
    private static Team team(ServerPlayer p){return s().teamOf(id(p));}
    private static void role(ServerPlayer p,Action action){TeamState.authorize(team(p),id(p),action,s().configuration());}
    private static String name(ServerPlayer p,UUID who){var online=p.server.getPlayerList().getPlayer(who);if(online!=null)return online.getGameProfile().getName();return p.server.getProfileCache().get(who).map(com.mojang.authlib.GameProfile::getName).orElse(who.toString());}
    private static ItemStack icon(Item item,String title,String... lines){ItemStack stack=new ItemStack(item);stack.setHoverName(Component.literal(title));ListTag lore=new ListTag();for(String line:lines)lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(line))));stack.getOrCreateTagElement("display").put("Lore",lore);return stack;}
    private static final class Page extends AbstractContainerMenu {
        final ServerPlayer viewer; final Runnable check; final SimpleContainer display=new SimpleContainer(54);final Map<Integer,Runnable> actions=new HashMap<>();boolean used;
        Page(int sync,ServerPlayer p,Runnable check){super(MenuType.GENERIC_9x6,sync);viewer=p;this.check=check;
            for(int n=0;n<54;n++)addSlot(new Slot(display,n,8+n%9*18,18+n/9*18){@Override public boolean mayPlace(ItemStack stack){return false;}@Override public boolean mayPickup(Player p){return false;}});
            for(int n=0;n<36;n++)addSlot(new Slot(p.getInventory(),n<27?n+9:n-27,8+n%9*18,n<27?139+n/9*18:197){@Override public boolean mayPlace(ItemStack stack){return false;}@Override public boolean mayPickup(Player p){return false;}});
        }
        void button(int slot,Item item,String title,Runnable action,String... lore){display.setItem(slot,icon(item,title,lore));if(action!=null)actions.put(slot,action);}
        @Override public void clicked(int slot,int button,ClickType type,Player p){if(p!=viewer||used||type!=ClickType.PICKUP||button!=0||!actions.containsKey(slot))return;try{check.run();used=true;actions.get(slot).run();}catch(RuntimeException e){used=false;error(viewer,e);broadcastChanges();}}
        @Override public boolean stillValid(Player p){return p==viewer&&viewer.isAlive();}
        @Override public ItemStack quickMoveStack(Player p,int slot){return ItemStack.EMPTY;}
        @Override public boolean canTakeItemForPickAll(ItemStack stack,Slot slot){return false;}
    }
    private static void page(ServerPlayer p,String title,Runnable check,Consumer<Page> populate){p.openMenu(new SimpleMenuProvider((sync,inventory,player)->{Page m=new Page(sync,p,check);populate.accept(m);return m;},Component.literal(title)));}
    private static void home(Page m){m.button(49,Items.COMPASS,"Teams home",()->open(m.viewer));}
    private static void confirm(ServerPlayer p,String title,Runnable check,Runnable action,Runnable back,String... lore){page(p,"Confirm: "+title,check,m->{m.button(20,Items.LIME_CONCRETE,"Confirm "+title,()->{action.run();back.run();},lore);m.button(24,Items.RED_CONCRETE,"Cancel",back);});}
    private static Runnable unchanged(ServerPlayer p){Runnable membership=guard(p);String before=StateCodec.GSON.toJson(team(p)),config=StateCodec.GSON.toJson(s().configuration());return ()->{membership.run();if(!before.equals(StateCodec.GSON.toJson(team(p)))||!config.equals(StateCodec.GSON.toJson(s().configuration())))throw new IllegalArgumentException("Team/configuration changed; reopen this action");};}

    /** Anvil's actual ServerboundRenameItemPacket handler requires instanceof AnvilMenu. */
    private static final class TextInput extends AnvilMenu {
        final ServerPlayer viewer;final Runnable check;final Consumer<String> accept;String value;boolean used;
        TextInput(int sync,ServerPlayer p,String initial,Runnable check,Consumer<String> accept){super(sync,p.getInventory());viewer=p;this.check=check;this.accept=accept;value=initial;inputSlots.setItem(0,icon(Items.PAPER,initial));resultSlots.setItem(0,icon(Items.PAPER,"Apply text","Type in the name box, then click here"));}
        @Override public void createResult() {} // No repair logic, experience, or real items.
        @Override public boolean setItemName(String text){if(text==null||text.length()>50)return false;value=text;return true;}
        @Override public void clicked(int slot,int button,ClickType type,Player p){if(p!=viewer||used||slot!=2||button!=0||type!=ClickType.PICKUP)return;try{check.run();used=true;accept.accept(value);}catch(RuntimeException e){used=false;error(viewer,e);broadcastChanges();}}
        @Override public boolean stillValid(Player p){return p==viewer&&viewer.isAlive();}
        @Override public boolean mayPickup(Player p,boolean present){return false;}
        @Override public ItemStack quickMoveStack(Player p,int slot){return ItemStack.EMPTY;}
        @Override public boolean canTakeItemForPickAll(ItemStack stack,Slot slot){return false;}
        @Override public void removed(Player p){} // Fake paper must not be returned or dropped on close.
    }
    private static void text(ServerPlayer p,String title,String initial,Runnable check,Consumer<String> accept){p.openMenu(new SimpleMenuProvider((sync,inventory,player)->new TextInput(sync,p,initial.length()<=50?initial:"",check,accept),Component.literal(title)));}

    public static void open(ServerPlayer p){Runnable check=guard(p);UUID current=membership(p);page(p,"Teams",check,m->{
        if(current==null){m.button(10,Items.WRITABLE_BOOK,"Create team",()->text(p,"New team name","",check,value->{check.run();s().create(id(p),value);open(p);}));m.button(12,Items.PAPER,"Pending invitations",()->invitations(p,0));m.button(14,Items.NAME_TAG,"Accept invitation code",()->text(p,"Invitation code","",check,value->{check.run();s().acceptCode(id(p),value);open(p);}));}
        else{Team t=team(p);m.button(4,Items.BOOK,t.name,null,"Role: "+t.members.get(id(p)),"Selected chunks: "+t.chunks.size(),"Next rent: "+s().cost(id(p))+" "+s().configuration().billing.denomination,"Lease ends: "+(t.leaseEnd==0?"none":java.time.Instant.ofEpochMilli(t.leaseEnd)),"Suspended: "+t.suspended);
            m.button(10,Items.MAP,"Chunk map",()->chunks(p,0,false,null));m.button(12,Items.PLAYER_HEAD,"Members / invitations",()->members(p,0));m.button(14,Items.NAME_TAG,"Team style",()->style(p));m.button(16,Items.GOLD_INGOT,"Treasury",()->treasury(p));
            m.button(28,Items.OAK_DOOR,"Leave team",()->confirm(p,"leave team",unchanged(p),()->s().leave(id(p)),()->open(p),"Owners must transfer ownership first"));
            m.button(34,Items.TNT,"Disband team",()->{role(p,Action.ADMINISTER);confirm(p,"disband team",unchanged(p),()->s().disband(id(p)),()->open(p),"Selected chunks and team will be removed","Remaining treasury returns to owner");});}
        if(p.hasPermissions(s().configuration().permissions.adminPermissionLevel))m.button(47,Items.COMMAND_BLOCK,"Administration",()->administration(p));
        m.button(51,Items.BOOK,"Command fallback",()->{p.closeContainer();p.sendSystemMessage(Component.literal("/smpteam create | invite | accept | leave | transfer | role | style | rename | badge | color | treasury | chunks | selector | disband confirm | admin"));});
    });}
    private static void invitations(ServerPlayer p,int page){Runnable check=guard(p);List<Team> invited=s().snapshot().teams.values().stream().filter(t->t.invites.getOrDefault(id(p),0L)>s().nowMillis()).toList();page(p,"Pending invitations",check,m->{paginate(m,invited,page,(slot,t)->m.button(slot,Items.PAPER,t.name,()->{Long expiry=s().snapshot().byId(t.id).invites.get(id(p));if(expiry==null||expiry<=s().nowMillis())throw new IllegalArgumentException("Invitation expired");confirm(p,"join "+t.name,check,()->s().accept(id(p),t.id),()->open(p),"Join this team as a member");},"Expires: "+java.time.Instant.ofEpochMilli(t.invites.get(id(p)))) ,n->invitations(p,n));home(m);});}
    @FunctionalInterface private interface Entry<T>{void show(int slot,T value);}
    private static <T> void paginate(Page m,List<T> values,int requested,Entry<T> entry,Consumer<Integer> change){int last=Math.max(0,(values.size()-1)/45),page=Math.max(0,Math.min(requested,last));for(int i=page*45;i<Math.min(values.size(),page*45+45);i++)entry.show(i%45,values.get(i));if(page>0)m.button(45,Items.ARROW,"Previous page",()->change.accept(page-1));if(page<last)m.button(53,Items.ARROW,"Next page",()->change.accept(page+1));m.button(48,Items.PAPER,"Page "+(page+1)+" / "+(last+1),null,"Entries: "+values.size());}
    private static void members(ServerPlayer p,int page){Runnable check=guard(p);Team t=team(p);page(p,"Team members",check,m->{paginate(m,new ArrayList<>(t.members.keySet()),page,(slot,who)->m.button(slot,Items.PLAYER_HEAD,name(p,who),()->member(p,who),"Role: "+t.members.get(who)),n->members(p,n));m.button(46,Items.PAPER,"Invite player",()->{role(p,Action.INVITE);text(p,"Online name or UUID","",check,value->{role(p,Action.INVITE);var online=p.server.getPlayerList().getPlayerByName(value);UUID target=online==null?canonicalUuid(value):online.getUUID();s().invite(id(p),target);members(p,0);});});m.button(50,Items.NAME_TAG,"Invitation code",()->{role(p,Action.INVITE);String code=s().invitationCode(id(p),team(p).id);p.sendSystemMessage(Component.literal("Team invitation code: "+code));members(p,page);},"For players with a pending invitation");home(m);});}
    private static void member(ServerPlayer p,UUID who){Runnable check=guard(p);Team t=team(p);if(!t.members.containsKey(who))throw new IllegalArgumentException("Member left");page(p,name(p,who),check,m->{m.button(4,Items.PLAYER_HEAD,name(p,who),null,"Role: "+t.members.get(who));m.button(20,Items.IRON_INGOT,"Set member",()->{role(p,Action.ADMINISTER);confirm(p,"set member",unchanged(p),()->s().role(id(p),who,Role.MEMBER),()->members(p,0));});m.button(22,Items.GOLD_INGOT,"Set officer",()->{role(p,Action.ADMINISTER);confirm(p,"set officer",unchanged(p),()->s().role(id(p),who,Role.OFFICER),()->members(p,0));});m.button(24,Items.NETHER_STAR,"Transfer ownership",()->{role(p,Action.ADMINISTER);confirm(p,"transfer ownership",unchanged(p),()->s().transfer(id(p),who),()->members(p,0),"You become an officer","New owner: "+name(p,who));});home(m);});}
    private static void style(ServerPlayer p){Runnable check=guard(p);Team t=team(p);page(p,"Team style",check,m->{m.button(20,Items.NAME_TAG,"Rename: "+t.name,()->{role(p,Action.STYLE);text(p,"Team name",t.name,check,value->{role(p,Action.STYLE);Team live=team(p);s().style(id(p),value,live.color,live.badge);style(p);});});m.button(22,Items.RED_DYE,"Color: "+t.color,()->styleOptions(p,false,0));m.button(24,Items.PAINTING,"Badge: "+t.badge,()->styleOptions(p,true,0));home(m);});}
    private static void styleOptions(ServerPlayer p,boolean badge,int page){Runnable check=guard(p);var presentation=s().configuration().presentation;List<String> values=new ArrayList<>(badge?presentation.badges.keySet():presentation.colors);page(p,badge?"Choose badge":"Choose color",check,m->{paginate(m,values,page,(slot,value)->m.button(slot,badge?Items.PAINTING:Items.RED_DYE,value,()->{role(p,Action.STYLE);Team live=team(p);s().style(id(p),live.name,badge?live.color:value,badge?value:live.badge);style(p);}),n->styleOptions(p,badge,n));home(m);});}
    private static void treasury(ServerPlayer p){
        Runnable check=guard(p);
        page(p,"Team treasury",check,m->{
            int balance=s().balance(id(p));
            m.button(4,Items.GOLD_BLOCK,"Balance: "+balance+" spurs",null,
                "Deposits and withdrawals use physical spur items",
                "1 spur item = 1 treasury unit",
                "No personal bank account needed",
                "Transfers require billing or bazaar enabled");
            for(boolean withdraw:new boolean[]{false,true})m.button(withdraw?24:20,withdraw?Items.HOPPER:Items.GOLD_INGOT,withdraw?"Withdraw":"Deposit",
                ()->text(p,withdraw?"Withdraw spur count":"Deposit spur count","",check,value->{
                    check.run();int amount=Integer.parseInt(value);
                    if(amount<=0)throw new IllegalArgumentException("Spur count must be positive");
                    if(withdraw)role(p,Action.WITHDRAW);
                    int before=s().balance(id(p));Runnable unchanged=unchanged(p);
                    confirm(p,(withdraw?"withdraw ":"deposit ")+amount,()->{
                        unchanged.run();if(before!=s().balance(id(p)))throw new IllegalArgumentException("Treasury balance changed; reopen");
                    },()->{if(withdraw)s().withdraw(p,amount);else s().deposit(p,amount);},()->treasury(p),
                        "Physical Numismatics spur items",
                        "Amount: "+amount+" spurs",
                        withdraw?"Delivered to ordinary inventory; needs room":"Taken from ordinary inventory only",
                        "Backpacks, armor and offhand are untouched",
                        "No personal bank account needed");
                }),"Enter a whole number of physical spurs","1 item = 1 treasury unit");
            home(m);
        });
    }

    private static Chunk here(ServerPlayer p){return new Chunk(p.level().dimension().location().toString(),p.chunkPosition().x,p.chunkPosition().z);}
    private static void chunkAction(ServerPlayer p,Chunk chunk,boolean selected){
        role(p,Action.CHUNKS);Runnable check=unchanged(p);Team t=team(p);TeamsConfig config=s().configuration();int balance=s().balance(id(p));boolean prepaid=Prices.active(s().nowMillis(),t.leaseStart,t.leaseEnd);
        int nextCount=t.chunks.size()+(selected?-1:1);int nextRent=Prices.total(Math.max(0,nextCount),t.members.size(),config.billing);
        int addition=0;if(!selected&&prepaid&&t.termRate!=null)addition=Prices.addition(Math.max(0,nextCount-t.paidCapacity),t.memberSnapshot,t.leaseEnd-s().nowMillis(),t.termMillis,t.termRate);
        confirm(p,(selected?"remove ":"select ")+chunk.x()+", "+chunk.z(),()->{check.run();if(balance!=s().balance(id(p))||prepaid!=Prices.active(s().nowMillis(),t.leaseStart,t.leaseEnd))throw new IllegalArgumentException("Treasury or prepaid term changed; reopen chunks");},()->{if(selected)s().removeChunk(id(p),chunk);else s().addChunk(id(p),chunk);},()->chunks(p,0,false,chunk.dimension()),chunk.dimension(),selected?"Removal does not refund prepaid rent":"Prorated addition now (estimate): "+addition,"Next-term rent after change: "+nextRent+" "+config.billing.denomination,"Current treasury: "+balance,"Inactive terms may renew immediately if billing enabled");
    }
    private static void chunks(ServerPlayer p,int requested,boolean list,String dimension){Runnable check=guard(p);Team t=team(p);Chunk current=here(p);List<String> dimensions=new ArrayList<>(s().configuration().limits.allowedDimensions);for(Chunk c:t.chunks)if(!dimensions.contains(c.dimension()))dimensions.add(c.dimension());String dim=dimension==null?current.dimension():dimension;if(!dimensions.contains(dim))dimensions.add(dim);Set<Chunk> active=s().activeTickets();page(p,list?"Selected chunks (all dimensions)":"Chunk map: "+dim,check,m->{
        if(list)paginate(m,t.chunks.stream().sorted().toList(),requested,(slot,c)->m.button(slot,active.contains(c)?Items.LIME_CONCRETE:Items.YELLOW_CONCRETE,c.dimension()+" "+c.x()+", "+c.z(),()->chunkAction(p,c,true),active.contains(c)?"ACTIVE ticket":"SELECTED, not active","Click to remove (confirmation)"),n->chunks(p,n,true,dim));
        else{for(int z=-2;z<=2;z++)for(int x=-3;x<=3;x++){long cx=(long)current.x()+x,cz=(long)current.z()+z;if(Math.abs(cx)>1875000||Math.abs(cz)>1875000)continue;Chunk c=new Chunk(dim,(int)cx,(int)cz);boolean selected=t.chunks.contains(c);boolean other=s().snapshot().teams.values().stream().anyMatch(o->!o.id.equals(t.id)&&o.chunks.contains(c));m.button((z+2)*9+x+4,other?Items.RED_CONCRETE:active.contains(c)?Items.LIME_CONCRETE:selected?Items.YELLOW_CONCRETE:c.equals(current)?Items.COMPASS:Items.GRAY_STAINED_GLASS_PANE,c.x()+", "+c.z()+(c.equals(current)?" (you)":""),other?null:()->chunkAction(p,c,selected),dim,other?"Another team's selection":active.contains(c)?"ACTIVE ticket":selected?"SELECTED, awaiting active ticket":"Not selected",selected?"Click to remove":"Click to select");}}
        m.button(46,Items.COMPASS,"Add current chunk",()->{if(!here(p).equals(current))throw new IllegalArgumentException("You moved chunks; reopen map");if(t.chunks.contains(current))throw new IllegalArgumentException("Current chunk is already selected; click it on the map/list to remove");chunkAction(p,current,false);},current.toString());m.button(47,Items.MAP,list?"Map":"List all dimensions",()->chunks(p,0,!list,dim));m.button(50,Items.ENDER_PEARL,"Dimension: "+dim,()->chunks(p,0,false,dimensions.get((dimensions.indexOf(dim)+1)%dimensions.size())),"Click to cycle dimensions","Map centered on your current X/Z");m.button(51,Items.GOLD_INGOT,"Rent: "+s().cost(id(p)),null,s().configuration().billing.denomination,"Active: "+t.chunks.stream().filter(active::contains).count()+" / selected: "+t.chunks.size(),"Green=active; yellow=selected; red=other team","Member snapshot: "+t.memberSnapshot,"Paid capacity: "+t.paidCapacity);home(m);
    });}

    public static void administration(ServerPlayer p){admin(p);page(p,"Team administration",()->admin(p),m->{m.button(20,Items.COMPARATOR,"Edit all configuration",()->{admin(p);ConfigDraft draft=new ConfigDraft(s().configuration());config(p,draft,List.of(),0);},"Detached draft; save validates all fields","Includes nested lists, maps and bazaar pool");m.button(22,Items.CHEST,"Bind looked-at bazaar block",()->bindings(p,0,capture(p)),"Look at target before opening this menu","Target must still match when binding");m.button(24,Items.BOOK,"Billing status",()->{admin(p);p.sendSystemMessage(Component.literal(s().billingStatus()));administration(p);});home(m);});}
    private record Target(String dimension,net.minecraft.core.BlockPos pos,String block){}
    private static Target capture(ServerPlayer p){admin(p);var hit=p.pick(s().configuration().bazaar.bindReach,1,true);if(!(hit instanceof BlockHitResult block)||hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK)throw new IllegalArgumentException("Look at a block in bind reach before opening menu");return new Target(p.level().dimension().location().toString(),block.getBlockPos().immutable(),BuiltInRegistries.BLOCK.getKey(p.level().getBlockState(block.getBlockPos()).getBlock()).toString());}
    private static void bindings(ServerPlayer p,int page,Target target){admin(p);page(p,"Bazaar counter bindings",()->admin(p),m->{List<Integer> slots=new ArrayList<>();for(int n=1;n<=TeamsConfig.Bazaar.MAX_COUNTERS;n++)slots.add(n);var market=s().bazaar().snapshot();paginate(m,slots,page,(slot,n)->m.button(slot,Items.CHEST,"Offer slot "+n,()->binding(p,n,target),"Bound: "+Objects.toString(market.counters.get(n),"none"),n<=s().configuration().bazaar.offerCount?"Configured offer slot":"Inactive until offer count increases"),n->bindings(p,n,target));home(m);});}
    private static void binding(ServerPlayer p,int slot,Target target){admin(p);var previous=s().bazaar().snapshot().counters.get(slot);Runnable check=()->{admin(p);if(!Objects.equals(previous,s().bazaar().snapshot().counters.get(slot)))throw new IllegalArgumentException("Binding changed; reopen");};page(p,"Counter slot "+slot,check,m->{m.button(20,Items.CHEST,"Bind captured target",()->confirm(p,"bind slot "+slot,check,()->{if(!target.equals(capture(p)))throw new IllegalArgumentException("Looked-at block changed; reopen binding menu");s().bazaar().bindLook(p,slot);},()->administration(p),target.toString()));m.button(24,Items.BARRIER,"Unbind slot",()->confirm(p,"unbind slot "+slot,check,()->s().bazaar().unbind(slot),()->administration(p)));home(m);});}

    static final class ConfigDraft {
        final String expected;final JsonObject root;
        ConfigDraft(TeamsConfig config){expected=StateCodec.GSON.toJson(config);root=JsonParser.parseString(expected).getAsJsonObject();}
        JsonElement get(List<String> path){JsonElement node=root;for(String key:path)node=node.isJsonArray()?node.getAsJsonArray().get(Integer.parseInt(key)):node.getAsJsonObject().get(key);return node;}
        void set(List<String> path,JsonElement value){if(path.isEmpty())throw new IllegalArgumentException("Cannot replace root");JsonElement parent=get(path.subList(0,path.size()-1));String key=path.get(path.size()-1);if(parent.isJsonArray())parent.getAsJsonArray().set(Integer.parseInt(key),value);else parent.getAsJsonObject().add(key,value);}
        void remove(List<String> path){JsonElement parent=get(path.subList(0,path.size()-1));String key=path.get(path.size()-1);if(parent.isJsonArray())parent.getAsJsonArray().remove(Integer.parseInt(key));else parent.getAsJsonObject().remove(key);}
        TeamsConfig candidate(){TeamsConfig c=StateCodec.GSON.fromJson(root,TeamsConfig.class);c.validate();return c;}
    }
    private static List<String> child(List<String> path,String key){List<String> next=new ArrayList<>(path);next.add(key);return List.copyOf(next);}
    private static boolean dynamic(List<String> path){return path.equals(List.of("messages"))||path.equals(List.of("presentation","badges"));}
    private static void config(ServerPlayer p,ConfigDraft draft,List<String> path,int page){admin(p);JsonElement node=draft.get(path);if(!node.isJsonObject()&&!node.isJsonArray()){configLeaf(p,draft,path);return;}List<String> keys=new ArrayList<>();if(node.isJsonArray())for(int n=0;n<node.getAsJsonArray().size();n++)keys.add(Integer.toString(n));else keys.addAll(node.getAsJsonObject().keySet());page(p,"Config: "+(path.isEmpty()?"root":String.join(".",path)),()->admin(p),m->{paginate(m,keys,page,(slot,key)->{JsonElement value=draft.get(child(path,key));m.button(slot,value.isJsonPrimitive()?Items.PAPER:Items.BOOK,key,()->config(p,draft,child(path,key),0),abbreviate(value.toString()),"Click to browse / edit");},n->config(p,draft,path,n));m.button(46,Items.ARROW,"Up",()->{if(path.isEmpty())administration(p);else config(p,draft,path.subList(0,path.size()-1),0);});
        if(node.isJsonArray()||dynamic(path))m.button(47,Items.EMERALD,"Add entry",()->{if(node.isJsonArray()){JsonArray a=node.getAsJsonArray();JsonElement added=path.equals(List.of("bazaar","pool"))?StateCodec.GSON.toJsonTree(new TeamsConfig.PoolEntry("minecraft:stone")):new JsonPrimitive("");a.add(added);config(p,draft,child(path,Integer.toString(a.size()-1)),0);}else text(p,"New map key","",()->admin(p),key->{if(key.isBlank()||node.getAsJsonObject().has(key))throw new IllegalArgumentException("Empty or existing key");node.getAsJsonObject().addProperty(key,"");config(p,draft,child(path,key),0);});});
        if(!path.isEmpty()&&(draft.get(path.subList(0,path.size()-1)).isJsonArray()||dynamic(path.subList(0,path.size()-1))))m.button(50,Items.BARRIER,"Remove this entry",()->confirm(p,"remove draft entry",()->admin(p),()->draft.remove(path),()->config(p,draft,path.subList(0,path.size()-1),0),"Only affects draft until saved"));
        m.button(51,Items.LIME_CONCRETE,"Validate and save draft",()->{admin(p);TeamsConfig candidate=draft.candidate();confirm(p,"save configuration",()->admin(p),()->{TeamsMod.saveConfiguration(candidate,draft.expected);p.sendSystemMessage(Component.literal("Configuration saved and applied."));},()->administration(p),"Applies ALL draft changes","Enabling billing can immediately debit rent","Enabling bazaar permits financial transfers","Stale drafts and invalid settings are rejected");});m.button(49,Items.RED_CONCRETE,"Discard draft",()->administration(p));});}
    private static void configValue(ServerPlayer p,ConfigDraft draft,List<String> path,int page){admin(p);JsonElement node=draft.get(path);String value=node.isJsonPrimitive()&&node.getAsJsonPrimitive().isString()?node.getAsString():node.toString();List<String> lines=new ArrayList<>();for(int start=0;start<value.length();start+=50)lines.add(value.substring(start,Math.min(start+50,value.length())));page(p,"Full value: "+String.join(".",path),()->admin(p),m->{paginate(m,lines,page,(slot,line)->m.button(slot,Items.PAPER,"Text segment "+(page*45+slot+1),null,line.replace("\n"," [newline] ")),n->configValue(p,draft,path,n));m.button(49,Items.ARROW,"Back",()->configLeaf(p,draft,path));});}
    private static String abbreviate(String text){return text.length()>110?text.substring(0,107)+"...":text;}
    private static void configLeaf(ServerPlayer p,ConfigDraft draft,List<String> path){admin(p);JsonElement value=draft.get(path);page(p,"Edit: "+String.join(".",path),()->admin(p),m->{m.button(4,Items.PAPER,"Current draft value",null,abbreviate(value.toString()));m.button(20,Items.WRITABLE_BOOK,value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()?"Toggle boolean":"Replace value",()->{if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean()){draft.set(path,new JsonPrimitive(!value.getAsBoolean()));configLeaf(p,draft,path);}else{String initial=value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()?value.getAsString():value.toString();text(p,"New value (max 50 chars)",initial,()->admin(p),input->{JsonElement replacement=value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()?new JsonPrimitive(input):JsonParser.parseString(input);if(replacement==null||!replacement.isJsonPrimitive())throw new IllegalArgumentException("Expected scalar value");draft.set(path,replacement);configLeaf(p,draft,path);});}});
        m.button(21,Items.BOOK,"View full value",()->configValue(p,draft,path,0),"Long strings are split into text segments");
        if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString())m.button(22,Items.STRING,"Append text",()->text(p,"Append up to 50 chars","",()->admin(p),input->{draft.set(path,new JsonPrimitive(value.getAsString()+input));configLeaf(p,draft,path);}),"For long URLs and display text","Replace with empty text to clear first","Use literal \\n for JSON templates only if intended");
        if(path.equals(List.of("bazaar","displayText")))m.button(23,Items.FEATHER,"Append newline",()->{draft.set(path,new JsonPrimitive(value.getAsString()+"\n"));configLeaf(p,draft,path);},"Adds a line break to display text");
        List<String> parent=path.subList(0,path.size()-1);if(draft.get(parent).isJsonArray()||dynamic(parent))m.button(24,Items.BARRIER,"Remove entry",()->confirm(p,"remove draft entry",()->admin(p),()->draft.remove(path),()->config(p,draft,parent,0)));
        m.button(49,Items.ARROW,"Back",()->config(p,draft,parent,0));});}
}
