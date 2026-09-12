package unlimitedschem;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.Log;
import arc.util.io.Reads;
import mindustry.content.*;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.Schematic;
import mindustry.game.Schematic.Stile;
import mindustry.io.*;
import mindustry.io.TypeIO.*;
import mindustry.mod.Mod;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.power.LightBlock;
import mindustry.world.blocks.sandbox.*;

import java.io.*;
import java.util.zip.InflaterInputStream;

import static mindustry.Vars.*;

/**
 * 게임 시작 시(ClientLoadEvent, 세션당 정확히 1회) 스테이징 폴더의 대형 설계도를
 * 128x128 제한 없이 읽어 schematics 목록에 등록한다.
 *
 * 이전 빌드 오류 수정 내역:
 *  - JsonIO의 실제 패키지는 arc.util.serialization이 아니라 mindustry.io (공식 문서 확인).
 *  - ContentMapper는 SaveFileReader가 아니라 mindustry.io.TypeIO의 nested interface
 *    (업로드된 Schematics.java 592번 줄: "ContentMapper mapper = null;"과 동일하게 사용).
 *  - ver==0(구버전 포맷) 분기용 mapConfig(...)를 원본 Schematics.java 699~706번 줄에서
 *    실제로 발견하여 그대로 포함시킴.
 */
public class NoLimitSchematicSizeMod extends Mod{

    private static final byte[] HEADER = {'m', 's', 'c', 'h'};
    private static Fi stagingDir;

    public NoLimitSchematicSizeMod(){
        Events.on(ClientLoadEvent.class, e -> runOnce());
    }

    /** 세션당 정확히 1번만 실행된다(ClientLoadEvent 자체가 1회성 이벤트). */
    private void runOnce(){
        stagingDir = dataDirectory.child("unlimited-schematics");
        stagingDir.mkdirs();

        int imported = 0;
        for(Fi file : stagingDir.list()){
            if(!file.extension().equals(schematicExtension)) continue;

            try{
                Schematic s = readUnlimited(file);

                boolean already = schematics.all().contains(existing -> existing.name().equals(s.name()));
                if(already) continue;

                s.removeSteamID();
                schematics.add(s);
                imported++;
            }catch(Exception e){
                Log.err("[UnlimitedSchem] 대형 설계도 불러오기 실패: " + file.name(), e);
            }
        }

        if(imported > 0){
            final int n = imported;
            Core.app.post(() -> ui.showInfoFade("[UnlimitedSchem] 대형 설계도 " + n + "개 불러옴"));
        }
        Log.info("[UnlimitedSchem] 1회성 임포트 완료 (" + imported + "개). 이후 별도 동작 없음.");
    }

    public static Schematic readUnlimited(Fi file) throws IOException{
        Schematic s = readUnlimited(new DataInputStream(file.read(1024)));
        if(!s.tags.containsKey("name")){
            s.tags.put("name", file.nameWithoutExtension());
        }
        s.file = file;
        return s;
    }

    public static Schematic readUnlimited(InputStream input) throws IOException{
        for(byte b : HEADER){
            if(input.read() != b) throw new IOException("Not a schematic file (missing header).");
        }

        int ver = input.read();

        try(DataInputStream stream = new DataInputStream(new InflaterInputStream(input))){
            short width = stream.readShort(), height = stream.readShort();
            // 원본 584번 줄 크기 검사 생략

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
            // 원본 625번 줄 개수 검사 생략

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

    /** 원본 Schematics.java 699~706번 줄과 동일 (구버전 포맷 config 매핑). */
    private static Object mapConfig(Block block, int value, int position){
        if(block instanceof Sorter || block instanceof Unloader || block instanceof ItemSource) return content.item(value);
        if(block instanceof LiquidSource) return content.liquid(value);
        if(block instanceof MassDriver || block instanceof ItemBridge) return Point2.unpack(value).sub(Point2.x(position), Point2.y(position));
        if(block instanceof LightBlock) return value;
        return null;
    }
}
