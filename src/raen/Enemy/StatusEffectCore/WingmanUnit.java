package raen.Enemy.StatusEffectCore;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.gen.UnitEntity;
import mindustry.type.UnitType;
import mindustry.world.meta.Env;

public class WingmanUnit {
    public static Seq<Unit> activeDrones = new Seq<>();
    public static UnitType wingmanA;
    public static void load() {
        wingmanA = new UnitType("wingmanA") {{
            envEnabled = Env.any;
            envDisabled = 0;
            health = 100f;
            constructor = UnitEntity::create;
            playerControllable = false;
            allowedInPayloads = false;
            armor = 1f;
            useUnitCap = false;
            speed = 3f;
            accel = 0.1f;
            range = 240f;
            drag = 0.05f;
            flying = true;
            rotateSpeed = 8f;
            engineSize = 1.5f;
            engineOffset = 3f;
            hitSize = 5f;
            lowAltitude = true;
            isEnemy = false;
            targetAir = true;
        }};
        Vars.content.units().add(wingmanA);
    }
    /** 注销单架僚机：阵亡或被主动清理时调用，避免 activeDrones 无限累积死引用。 */
    public static void unregister(Unit drone) {
        if (drone == null) return;
        for (int i = activeDrones.size - 1; i >= 0; i--) {
            if (activeDrones.get(i) == drone) activeDrones.remove(i);
        }
    }
    /** 兜底清理：移除所有已死亡/失效的僚机引用（例如宿主消失后遗留的孤儿机）。 */
    public static void pruneDrones() {
        for (int i = activeDrones.size - 1; i >= 0; i--) {
            Unit d = activeDrones.get(i);
            if (d == null || d.dead() || !d.isAdded()) activeDrones.remove(i);
        }
    }
}
