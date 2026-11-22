package dev.grxm.radar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;


/** Append-only, force-before-publish global reservations. Any uncertain write fails closed. */
public final class History implements AutoCloseable {
    public static final int LIMIT=100_000;
    public static final class Binding {
        public final UUID radar;
        public final int x,z,minX,minY,minZ,maxX,maxY,maxZ;
        public double previous;
        public boolean completed;
        public Binding(UUID radar,int x,int z,int minX,int minY,int minZ,int maxX,int maxY,int maxZ,double previous) {
            this.radar=radar;this.x=x;this.z=z;this.minX=minX;this.minY=minY;this.minZ=minZ;this.maxX=maxX;this.maxY=maxY;this.maxZ=maxZ;this.previous=previous;
        }
        public boolean contains(double px,double py,double pz){return px>=minX&&px<maxX+1.0&&py>=minY&&py<maxY+1.0&&pz>=minZ&&pz<maxZ+1.0;}
    }
    private final Set<Long> used=new HashSet<>();
    private final Map<UUID,Binding> bindings=new HashMap<>();
    private final FileChannel channel;
    private boolean failed;
    public History(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        if(Files.exists(file)) {
            if(Files.size(file)>64_000_000) throw new IOException("Radar history exceeds limit");
            String all=Files.readString(file,StandardCharsets.US_ASCII);
            if(!all.isEmpty() && !all.endsWith("\n")) throw new IOException("Incomplete radar history: manual recovery required");
            for(String line:all.split("\n")) {
                if(line.isEmpty()) continue;
                String[] fields=line.split(",",-1);
                try {
                    int split=line.lastIndexOf(',');
                    if(split<0||!line.substring(split+1).equals(checksum(line.substring(0,split))))throw new IllegalArgumentException();
                    apply(fields);
                } catch(RuntimeException e) { throw new IOException("Invalid radar history: refusing to reset",e); }
            }
        }
        channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND);
        channel.force(true);
        try(FileChannel dir=FileChannel.open(file.getParent(),StandardOpenOption.READ)){dir.force(true);}
    }
    public static long key(int x,int z){return ((long)x<<32) | (z & 0xffffffffL);}
    private static String checksum(String s){CRC32 crc=new CRC32();crc.update(s.getBytes(StandardCharsets.US_ASCII));return Long.toHexString(crc.getValue());}
    public boolean contains(int x,int z){return used.contains(key(x,z));}
    public int size(){return used.size();}
    public Binding binding(UUID id){return bindings.get(id);}
    private void apply(String[] f) {
        UUID id=UUID.fromString(f[1]);
        if(f[0].equals("B") && f.length==13) {
            int[] v=new int[8];for(int i=0;i<8;i++)v[i]=Integer.parseInt(f[i+2]);
            double d=Double.parseDouble(f[10]);
            // f[11] is format version; checksum is the final field.
            if(!f[11].equals("1")||!Double.isFinite(d)||d<0||bindings.containsKey(id)||used.contains(key(v[0],v[1]))||used.size()>=LIMIT||v[2]>v[5]||v[3]>v[6]||v[4]>v[7])throw new IllegalArgumentException();
            Binding b=new Binding(id,v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],d);bindings.put(id,b);used.add(key(b.x,b.z));
        } else if(f[0].equals("R") && f.length==4) {
            Binding b=Objects.requireNonNull(bindings.get(id));double d=Double.parseDouble(f[2]);if(b.completed||!Double.isFinite(d)||d<0)throw new IllegalArgumentException();b.previous=d;
        } else if(f[0].equals("C") && f.length==3) {
            Binding b=Objects.requireNonNull(bindings.get(id));if(b.completed)throw new IllegalArgumentException();b.completed=true;
        } else throw new IllegalArgumentException();
    }
    private void append(String data) throws IOException {
        if(failed || channel.size()+data.length()+32>64_000_000)throw new IOException("History unavailable/full");
        ByteBuffer bytes=StandardCharsets.US_ASCII.encode(data+","+checksum(data)+"\n");
        try {
            while(bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
            apply((data+",checksum").split(","));
        } catch(IOException | RuntimeException e){failed=true;throw e;}
    }
    public void reserve(Binding b) throws IOException {
        if(bindings.containsKey(b.radar)||used.size()>=LIMIT||contains(b.x,b.z))throw new IOException("Already bound/reserved/full");
        if(b.minX>b.maxX||b.minY>b.maxY||b.minZ>b.maxZ||!Double.isFinite(b.previous)||b.previous<0)throw new IOException("Invalid binding");
        append("B,"+b.radar+","+b.x+","+b.z+","+b.minX+","+b.minY+","+b.minZ+","+b.maxX+","+b.maxY+","+b.maxZ+","+b.previous+",1");
    }
    public void reading(UUID id,double squared)throws IOException {if(!bindings.containsKey(id)||bindings.get(id).completed||!Double.isFinite(squared)||squared<0)throw new IOException("Invalid reading");append("R,"+id+","+squared);}
    public void complete(UUID id)throws IOException {if(!bindings.containsKey(id)||bindings.get(id).completed)throw new IOException("Invalid completion");append("C,"+id);}
    @Override public void close() throws IOException {channel.close();}
}
