package dev.grxm.radar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RadarContractsTest {
    @TempDir Path temp;
    private static History.Binding binding(int x,int z){return new History.Binding(UUID.randomUUID(),x,z,0,50,0,20,100,20,100);}
    @Test void negativeCoordinatesAndExactDistanceOrder() {
        var points=DistanceOrder.within(-17.25,-1025.5,1024);
        assertTrue(points.size()>12000 && points.size()<14000);
        Set<String> seen=new HashSet<>();double last=-1;
        for(var p:points) {
            assertTrue(p.squared()>=last);last=p.squared();assertTrue(last<=1024.0*1024);
            assertTrue(seen.add(p.x()+","+p.z()));
            assertEquals(Math.pow(p.x()*16+8+17.25,2)+Math.pow(p.z()*16+8+1025.5,2),p.squared());
        }
        assertEquals(-2,points.get(0).x());assertEquals(-65,points.get(0).z());
        // Independent exhaustive oracle: all eligible points, including negative region boundaries.
        var eligible=points.stream().filter(p->p.x()%7==0 && p.z()%11==0).toList();
        double best=Double.POSITIVE_INFINITY;for(int x=-80;x<80;x++)for(int z=-150;z<40;z++)if(x%7==0 && z%11==0){double d=Math.pow(16*x+8+17.25,2)+Math.pow(16*z+8+1025.5,2);if(d<=1024.0*1024)best=Math.min(best,d);}
        assertEquals(best,eligible.get(0).squared());
        assertTrue(DistanceOrder.message(best).matches("End City: [0-9]+ horizontal blocks away\\."));
    }
    @Test void durableGlobalUniquenessAndCorruptionFailClosed() throws Exception {
        Path file=temp.resolve("world/data/history");
        UUID id;
        try(History h=new History(file)){var b=binding(-12,3);id=b.radar;h.reserve(b);assertThrows(IOException.class,()->h.reserve(binding(-12,3)));h.reserve(binding(3,-12));h.reading(id,25);h.complete(id);}
        try(History h=new History(file)){assertEquals(2,h.size());assertTrue(h.contains(-12,3));assertTrue(h.contains(3,-12));assertTrue(h.binding(id).completed);assertEquals(25,h.binding(id).previous);assertThrows(IOException.class,()->h.reserve(binding(-12,3)));}
        Files.writeString(file,"broken",StandardOpenOption.APPEND);
        assertThrows(IOException.class,()->new History(file));assertTrue(Files.readString(file).endsWith("broken"));
    }
    @Test void failedWriteDoesNotPublishReservation() throws Exception {
        History h=new History(temp.resolve("history"));h.close();
        assertThrows(IOException.class,()->h.reserve(binding(1,2)));assertFalse(h.contains(1,2));
        assertThrows(IOException.class,()->h.reserve(binding(3,4)));assertFalse(h.contains(3,4));
    }
}
