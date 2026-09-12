package unlimitedschem;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.io.Reads;
import mindustry.content.*;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.io.*;
import mindustry.io.TypeIO.*;
import mindustry.game.Schematic;
import mindustry.game.Schematic.Stile;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.power.LightBlock;
import mindustry.world.blocks.sandbox.*;
import mindustry.world.blocks.storage.*;

import java.io.*;
import java.util.zip.InflaterInputStream;

import static mindustry.Vars.content;

/**
 * Schematics.read(InputStream) / readBase64(String)을 통째로 대체하는 구현.
 * 원본 Schematics.java 570~665, 699~706번 줄과 동일한 로직이되
 * 584, 625번 줄의 크기 제한 검사만 제거함.
 *
 * import 수정 이력: Unloader는 distribution이 아니라 storage 패키지
 * (mindustry.world.blocks.storage.Unloader, 공식 javadoc 확인).
 */
public class SchematicPatch{
    private static final byte[] HEADER = {'m', 's', 'c', 'h'};

    public static Schematic interceptRead(InputStream input) throws IOException{
        for(byte b : HEADER){
            if(input.read() != b) throw new IOException("Not a schematic file (missing header).");
        }

        int ver = input.read();

        try(DataInputStream stream = new DataInputStream(new InflaterInputStream(input))){
            short width = stream.readShort(), height = stream.readShort();
            // 크기 제한 검사 없음 (원본 584번 줄 삭제됨)

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
                blocks.put(i, block == null ? Blocks.air : block);
            }

            int total = stream.readInt();
            // 블록 개수 제한 검사 없음 (원본 625번 줄 삭제됨)

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

    public static Schematic interceptReadBase64(String schematic){
        try{
            return interceptRead(new ByteArrayInputStream(arc.util.serialization.Base64Coder.decode(schematic.trim())));
        }catch(IOException e){
            throw new RuntimeException(e);
        }
    }

    /** 원본 Schematics.java 699~706번 줄과 동일 (구버전 포맷 config 매핑). */
    private static Object mapConfig(Block block, int value, int position){
        if(block instanceof Sorter || block instanceof Unloader || block instanceof ItemSource) return content.item(value);
        if(block instanceof LiquidSource) return content.liquid(value);
        if(block instanceof MassDriver || block instanceof ItemBridge) return Point2.unpack(value).sub(Point2.x(position), Point2.y(position));
        if(block instanceof LightBlock) return value;
        return null;
    }
}
