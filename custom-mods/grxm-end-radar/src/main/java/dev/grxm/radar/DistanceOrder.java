package dev.grxm.radar;

import java.util.*;

/** Distance is to the center of the structure-start chunk; not to its bounding box. */
public final class DistanceOrder {
    public record Point(int x, int z, double squared) {}
    private DistanceOrder() {}
    public static List<Point> within(double x, double z, int radius) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || radius < 0 || radius > 1024
                || Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) throw new IllegalArgumentException("Search bounds");
        List<Point> result = new ArrayList<>();
        int minX = (int)Math.ceil((x-radius-8)/16), maxX = (int)Math.floor((x+radius-8)/16);
        int minZ = (int)Math.ceil((z-radius-8)/16), maxZ = (int)Math.floor((z+radius-8)/16);
        for (int cx=minX;cx<=maxX;cx++) for(int cz=minZ;cz<=maxZ;cz++) {
            double dx=cx*16.0+8-x, dz=cz*16.0+8-z, d=dx*dx+dz*dz;
            if(d <= (double)radius*radius) result.add(new Point(cx,cz,d));
        }
        result.sort(Comparator.comparingDouble(Point::squared).thenComparingInt(Point::x).thenComparingInt(Point::z));
        return result;
    }
    public static String message(double squared) {
        return "End City: " + Math.round(Math.sqrt(squared)) + " horizontal blocks away.";
    }
}
