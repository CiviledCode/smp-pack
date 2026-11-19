package dev.grxm.teams;

import java.security.SecureRandom;
import java.util.*;
import java.util.function.Supplier;

/** A human-readable team reference, never a substitute for an unexpired personal invitation. */
public final class InvitationCodes {
    private static final SecureRandom RANDOM=new SecureRandom();
    private InvitationCodes() {}
    public static String allocate(Collection<String> used) {
        return allocate(used,()->{
            StringBuilder code=new StringBuilder(6);
            for(int n=0;n<6;n++)code.append((char)('A'+RANDOM.nextInt(26)));
            return code.toString();
        });
    }
    static String allocate(Collection<String> used,Supplier<String> candidate) {
        Set<String> occupied=new HashSet<>(used);
        for(int attempt=0;attempt<128;attempt++) {
            String code=candidate.get();
            if(code==null||!code.matches("[A-Z]{6}"))throw new IllegalArgumentException("Invalid generated invitation code");
            if(!occupied.contains(code))return code;
        }
        throw new IllegalStateException("Unable to allocate a unique invitation code; retry");
    }
    public static String normalize(String code) {
        if(code==null||!code.matches("[a-zA-Z]{6}"))throw new IllegalArgumentException("Expected a six-letter invitation code");
        return code.toUpperCase(Locale.ROOT);
    }
}
