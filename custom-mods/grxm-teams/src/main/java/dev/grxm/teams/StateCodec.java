package dev.grxm.teams;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

public final class StateCodec {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private StateCodec() {}
    public static String encode(TeamState state) { state.validate(); String json=GSON.toJson(state); if(json.getBytes(StandardCharsets.UTF_8).length>8_000_000)throw new IllegalArgumentException("Oversize team state"); return json; }
    public static TeamState decode(String json) {
        if(json==null || json.length()>8_000_000) throw new IllegalArgumentException("Missing/oversize team state");
        // Required top-level fields prevent Gson's defaults from masking an incomplete document.
        JsonObject root=JsonParser.parseString(json).getAsJsonObject();
        if(!root.has("version") || !root.has("teams")) throw new IllegalArgumentException("Incomplete team state");
        int version=root.get("version").getAsInt();
        if(version==2) {
            if(!root.has("bazaar")||!root.get("bazaar").isJsonObject())throw new IllegalArgumentException("Missing bazaar aggregate");
            JsonObject market=root.getAsJsonObject("bazaar");
            for(String key:new String[]{"generation","start","end","offers","counters","used","journal","denomination"})if(!market.has(key))throw new IllegalArgumentException("Incomplete market: "+key);
        } else if(version!=1)throw new IllegalArgumentException("Unsupported state schema");
        TeamState state=GSON.fromJson(root,TeamState.class);
        if(version==1) { if(root.has("bazaar"))throw new IllegalArgumentException("Unexpected market in legacy schema");state.version=2; }
        state.validate(); return state;
    }
    public static String digest(String json) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
