package dev.grxm.teams;

import java.math.*;

/** Exact prices in the native bank's smallest integer unit. No floating point. */
public final class Prices {
    private Prices() {}
    private static BigDecimal exact(int chunks,int members,TeamsConfig.Billing c) {
        if(chunks<0 || members<1) throw new IllegalArgumentException("Invalid price counts");
        return TeamsConfig.decimal(c.scalingFactor).multiply(TeamsConfig.decimal(c.teamMemberScaling)).multiply(BigDecimal.valueOf(chunks)).multiply(BigDecimal.valueOf(members));
    }
    public static int total(int chunks,int members,TeamsConfig.Billing c) { return bounded(exact(chunks,members,c),c.maximumBill); }
    public static int addition(int added,int members,long remainingMillis,long termMillis,TeamsConfig.Billing c) {
        if(termMillis<=0 || remainingMillis<0 || remainingMillis>termMillis) throw new IllegalArgumentException("Invalid proration interval");
        BigDecimal numerator=exact(added,members,c).multiply(BigDecimal.valueOf(remainingMillis));
        return bounded(numerator.divide(BigDecimal.valueOf(termMillis),0,RoundingMode.CEILING),c.maximumBill);
    }
    private static int bounded(BigDecimal n,int cap) { int result=n.setScale(0,RoundingMode.CEILING).intValueExact(); if(result<0 || result>cap) throw new IllegalArgumentException("Bill exceeds configured/native bank cap"); return result; }
    public static long nextEnd(long now,long seconds) { if(now<0 || seconds<=0) throw new IllegalArgumentException("Invalid lease clock"); return Math.addExact(now,Math.multiplyExact(seconds,1000)); }
    public static boolean active(long now,long start,long end) { return start>=0 && now>=start && now<end; }
}
