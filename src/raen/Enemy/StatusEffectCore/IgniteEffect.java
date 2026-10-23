package raen.Enemy.StatusEffectCore;

import arc.struct.IntMap;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.Damage;
import mindustry.entities.Units;
import mindustry.entities.units.StatusEntry;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 点燃：每 5 秒在自身碰撞体积数倍的范围内，给所有敌方单位与建筑点火。
 */
public class IgniteEffect extends StatusEffect {

    /** 搜索半径 = hitSize × 这个系数。 */
    public float radiusMul = 3f;
    /** 触发间隔（秒）。 */
    public float cycleSeconds = 5f;
    /** 火焰随机散布范围（0 = 正好在目标脚下）。 */
    public float fireSpread = 0f;
    /** 每个目标点几处火。 */
    public int fireAmount = 1;

    private final IntMap<Float> timers = new IntMap<>();

    public IgniteEffect(String name) {
        super(name);
        this.damage = 0f;
        this.transitionDamage = 0f;
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            if (unit != null) timers.remove(unit.id);
            return;
        }

        float timer = timers.get(unit.id, cycleSeconds) + Time.delta / 60f;
        boolean trigger = timer >= cycleSeconds;
        if (trigger) timer %= cycleSeconds;
        timers.put(unit.id, timer);
        if (!trigger) return;

        float radius = unit.hitSize * radiusMul;
        if (radius <= 0f) return;

        Units.nearbyEnemies(unit.team, unit.x, unit.y, radius, other -> {
            if (other != null && other != unit && other.team != Team.derelict) {
                Damage.createIncend(other.x, other.y, fireSpread, fireAmount);
            }
        });
        Units.nearbyBuildings(unit.x, unit.y, radius, build -> {
            if (build != null && build.team() != unit.team && build.team() != Team.derelict) {
                Damage.createIncend(build.x, build.y, fireSpread, fireAmount);
            }
        });
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        if (unit != null) timers.remove(unit.id);
    }
}
