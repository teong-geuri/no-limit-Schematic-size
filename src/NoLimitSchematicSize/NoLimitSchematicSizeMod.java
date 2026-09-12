package NoLimitSchematicSize;

import arc.util.Log;
import mindustry.game.Schematic;
import mindustry.mod.Mod;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class NoLimitSchematicSizeMod extends Mod{

    public NoLimitSchematicSizeMod(){
        try{
            patch();
            Log.info("[NoLimitSchem] Schematics.read()를 강제 교체 완료");
        }catch(Throwable t){
            Log.err("[NoLimitSchem] 강제 교체 실패 - 실행 환경이 self-attach를 막고 있을 수 있음", t);
        }
    }

    private void patch(){
        Instrumentation inst = ByteBuddyAgent.install();

        new ByteBuddy()
            .redefine(mindustry.game.Schematics.class)
            .method(named("read").and(takesArguments(InputStream.class)).and(returns(Schematic.class)))
            .intercept(MethodDelegation.to(SchematicPatch.class, "interceptRead"))
            .method(named("readBase64").and(takesArguments(String.class)).and(returns(Schematic.class)))
            .intercept(MethodDelegation.to(SchematicPatch.class, "interceptReadBase64"))
            .make()
            .load(mindustry.game.Schematics.class.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
    }
}
