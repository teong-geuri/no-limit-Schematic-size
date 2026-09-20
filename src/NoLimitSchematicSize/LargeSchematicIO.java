package NoLimitSchematicSize;

import arc.files.Fi;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.io.Reads;
import mindustry.content.*;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.game.Schematic;
import mindustry.game.Schematic.Stile;
import mindustry.io.*;
import mindustry.io.TypeIO.*;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.power.LightBlock;
import mindustry.world.blocks.sandbox.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.legacy.LegacyBlock;

import java.io.*;
import java.util.zip.InflaterInputStream;

import static mindustry.Vars.content;

/**
 * 게임의 Schematics.read()를 건드리지 않고(바이트코드 교체 없음),
 * 이 클래스에서 독자적으로 .msch 파일을 읽는다.
 * 원본과 동일한 포맷이지만 128x128 크기 검사만 하지 않는다.
 */
public class LargeSchematicIO{
    private static final byte[] header = {'m', 's', 'c', 'h'};

    /** 크기 제한 없이 msch 파일 하나를 읽는다. */
    public static Schematic read(Fi file) throws IOException{
        try(InputStream in = file.read(1024)){
            Schematic s = read(in);
            s.file = file;
            return s;
        }
    }

    public static Schematic read(InputStream input) throws IOException{
        for(byte b : header){
            if(input.read() != b) throw new IOException("Not a schematic file (missing header).");
        }

        int ver = input.read();

        try(DataInputStream stream = new DataInputStream(new InflaterInputStream(input))){
            //원본 read()에는 여기서 width > 128 || height > 128 검사가 있다. 그 검사만 생략한다.
            short width = stream.readShort(), height = stream.readShort();
            if(width <= 0 || height <= 0) throw new IOException("Invalid schematic: bad size " + width + "x" + height);

            StringMap map = new StringMap();
            int tags = stream.readUnsignedByte();
            for(int i = 0; i < tags; i++){
                map.put(stream.readUTF(), stream.readUTF());
            }

            ContentMapper mapper = null;
            if(map.containsKey("contentMap")){
                IntMap<ObjectIntMap<String>> nameMap =
                    JsonIO.json.fromJson(IntMap.class, ObjectIntMap.class, map.get("contentMap", "{}"));
                IntMap<IntMap<Content>> contentMap = new IntMap<>();
                for(var entry : nameMap){
                    var inner = new IntMap<Content>();
                    contentMap.put(entry.key, inner);
                    for(var ce : entry.value){
                        inner.put(ce.value, content.getByName(ContentType.all[entry.key], ce.key));
                    }
                }
                mapper = (type, id) -> contentMap.get(type.ordinal(), IntMap::new).get(id);
            }

            String[] labels = null;
            try{
                labels = JsonIO.read(String[].class, map.get("labels", "[]"));
            }catch(Exception ignored){}

            IntMap<Block> blocks = new IntMap<>();
            int length = stream.readUnsignedByte();
            for(int i = 0; i < length; i++){
                String name = stream.readUTF();
                Block block = content.getByName(ContentType.block, SaveFileReader.fallback.get(name, name));
                blocks.put(i, block == null || block instanceof LegacyBlock ? Blocks.air : block);
            }

            int total = stream.readInt();

            Reads read = new Reads(stream);
            Seq<Stile> tiles = new Seq<>(total);
            for(int i = 0; i < total; i++){
                Block block = blocks.get(stream.readByte());
                int position = stream.readInt();
                Object config = ver == 0 ? mapConfig(block, stream.readInt(), position) : TypeIO.readObject(read, false, mapper);
                byte rotation = stream.readByte();
                if(block != Blocks.air){
                    tiles.add(new Stile(block, Point2.x(position), Point2.y(position), config, rotation));
                }
            }

            Schematic out = new Schematic(tiles, map, width, height);
            if(labels != null) out.labels.addAll(labels);
            return out;
        }
    }

    private static Object mapConfig(Block block, int value, int position){
        if(block instanceof Sorter || block instanceof Unloader || block instanceof ItemSource) return content.item(value);
        if(block instanceof LiquidSource) return content.liquid(value);
        if(block instanceof MassDriver || block instanceof ItemBridge) return Point2.unpack(value).sub(Point2.x(position), Point2.y(position));
        if(block instanceof LightBlock) return value;
        return null;
    }
}
