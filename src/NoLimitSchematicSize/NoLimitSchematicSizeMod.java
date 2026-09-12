package unlimitedschem;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.struct.*;
import arc.util.Log;
import arc.util.io.Reads;
import arc.util.serialization.Base64Coder;
import arc.util.serialization.JsonIO;
import mindustry.content.Blocks;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.Schematic;
import mindustry.game.Schematic.Stile;
import mindustry.io.SaveFileReader;
import mindustry.io.TypeIO;
import mindustry.mod.Mod;
import mindustry.world.Block;

import java.io.*;
import java.util.zip.InflaterInputStream;

import static mindustry.Vars.*;

/**
 * 게임 시작 시(ClientLoadEvent, 세션당 정확히 1회) 스테이징 폴더의 대형 설계도를
 * 128x128 제한 없이 읽어 schematics 목록에 등록한다.
 * 이 이벤트 자체가 1회성이므로 별도의 "실행 후 종료" 처리가 필요 없다 —
 * 등록된 설계도는 schematics.all()에 계속 남아 설계도 창에 표시된다.
 *
 * 검증 근거:
 *  - Vars.platform/ui/schematics/dataDirectory 필드: Vars.java 원문(위 인용) 확인
 *  - ClientLoadEvent가 1회성 로드 완료 이벤트로 쓰이는 패턴: 업로드된 Schematics.java 68~74번 줄
 *    (Schematics() 생성자에서 동일하게 Events.on(ClientLoadEvent.class, ...) 사용)
 *  - 크기 제한 로직(width/height>128, total>128*128) 우회 대상: Schematics.java 52, 584, 625번 줄
 *  - schematics.add()가 all 목록 등록 + 디스크 저장까지 하는 것: Schematics.java 358~372번 줄
 *    (public void add(Schematic schematic){ all.add(schematic); ...write(schematic, file); })
 */
public class NoLimitSchematicSizeMod extends Mod{

    private static final byte[] HEADER = {'m', 's', 'c', 'h'};
    // 사용자가 대형 .msch 파일을 미리 넣어두는 폴더. 데이터 폴더 하위에 자동 생성.
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

                // 이미 같은 이름으로 등록돼 있으면 중복 저장 방지(재실행/재시작 대비)
                boolean already = schematics.all().contains(existing -> existing.name().equals(s.name()));
                if(already) continue;

                s.removeSteamID();
                schematics.add(s); // all 목록 등록 + schematicDirectory에 사본 저장
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
        // 여기서 별도 훅/루프를 걸지 않으므로 이 시점 이후 모드는 아무것도 하지 않는다("꺼짐").
        // 단, 위에서 schematics.add()로 등록된 설계도는 schematics.all()에 계속 남아 있다.
    }

    // ---- Schematics.java의 read(InputStream)을 재현하되
    //      584, 625번 줄의 크기/블록수 제한만 제거한 버전 ----

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
            // 원본 584번 줄의 "width/height > 128" 검사를 의도적으로 생략

            StringMap map = new StringMap();
            int tags = stream.readUnsignedByte();
            for(int i = 0; i < tags; i++){
                map.put(stream.readUTF(), stream.readUTF());
            }

            SaveFileReader.ContentMapper mapper = null;
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
            // 원본 625번 줄의 "total > 128*128" 검사를 의도적으로 생략

            Reads read = new Reads(stream);
            Seq<Stile> tiles = new Seq<>(total);
            for(int i = 0; i < total; i++){
                Block block = blocks.get(stream.readByte());
                int position = stream.readInt();
                Object config = ver == 0 ? null : TypeIO.readObject(read, false, mapper);
                byte rotation = stream.readByte();
                if(block != Blocks.air){
                    tiles.add(new Stile(block, arc.math.geom.Point2.x(position), arc.math.geom.Point2.y(position), config, rotation));
                }
            }

            Schematic out = new Schematic(tiles, map, width, height);
            if(labels != null) out.labels.addAll(labels);
            return out;
        }
    }
}
