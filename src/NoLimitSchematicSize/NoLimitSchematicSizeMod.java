package NoLimitSchematicSize;

import arc.Events;
import arc.files.Fi;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.Schematic;
import mindustry.mod.Mod;

/**
 * 바이트코드 교체(ByteBuddy) 없이, 세션당 정확히 1회만 동작한다.
 *
 * 기본 설계도 폴더(AppData/Roaming/Mindustry/schematics)를 훑어서
 * 게임이 128x128 제한 때문에 읽지 못하고 건너뛴 파일만 골라
 * 자체 리더로 읽고 이번 세션의 설계도 목록에 등록한다.
 */
public class NoLimitSchematicSizeMod extends Mod{
    /** 세션당 1회 보장 플래그 */
    private static boolean loaded;

    @Override
    public void init(){
        //게임이 schematics 폴더를 다 읽은 뒤(ClientLoadEvent) 1회만 실행
        Events.on(ClientLoadEvent.class, e -> loadOnce());
    }

    public static void loadOnce(){
        if(loaded) return;
        loaded = true;

        try{
            Fi dir = Vars.schematicDirectory;
            if(!dir.exists()){
                Log.info("[NoLimitSchem] 설계도 폴더 없음: @", dir.absolutePath());
                return;
            }

            //게임이 이미 성공적으로 읽어들인 파일 목록
            ObjectSet<String> already = new ObjectSet<>();
            for(Schematic s : Vars.schematics.all()){
                if(s.file != null) already.add(s.file.absolutePath());
            }

            Seq<Fi> targets = new Seq<>();
            for(Fi file : dir.list()){
                if(file.isDirectory()) continue;
                if(!file.extEquals(Vars.schematicExtension)) continue;
                if(already.contains(file.absolutePath())) continue; //게임이 정상 로드함 = 제한 이내
                targets.add(file);
            }

            if(targets.isEmpty()){
                Log.info("[NoLimitSchem] 제한을 넘어 누락된 설계도 없음");
                return;
            }

            int ok = 0, fail = 0, other = 0;
            long start = Time.millis();

            for(Fi file : targets){
                try{
                    Schematic s = LargeSchematicIO.read(file);

                    //크기 제한을 넘는 것만 대상으로 한다
                    if(s.width <= 128 && s.height <= 128 && s.tiles.size <= 128 * 128){
                        other++;
                        Log.info("[NoLimitSchem] 크기 제한과 무관한 누락 파일, 건너뜀: @", file.name());
                        continue;
                    }

                    //파일은 건드리지 않고 이번 세션의 목록에만 등록한다(1회성 로드)
                    Vars.schematics.all().add(s);
                    ok++;

                    Log.info("[NoLimitSchem] 로드: @ (@x@, 블록 @)", s.name(), s.width, s.height, s.tiles.size);
                }catch(Throwable t){
                    fail++;
                    Log.err("[NoLimitSchem] 로드 실패: " + file.name(), t);
                }
            }

            if(ok > 0){
                try{
                    Vars.schematics.all().sort();
                }catch(Throwable ignored){}
            }

            Log.info("[NoLimitSchem] 완료 - 대형 @ / 기타 누락 @ / 실패 @ (@ms)", ok, other, fail, Time.timeSinceMillis(start));

            if(ok > 0 && Vars.ui != null){
                Vars.ui.showInfoToast("대형 설계도 " + ok + "개를 이번 세션에 불러왔습니다.", 5f);
            }
        }catch(Throwable t){
            Log.err("[NoLimitSchem] 1회성 로드 중 오류", t);
        }
    }
}
