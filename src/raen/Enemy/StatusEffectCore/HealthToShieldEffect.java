package raen.Enemy.StatusEffectCore;

import arc.struct.IntMap;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 血量被削减时，把削减量按固定比例补成护盾。
 *
 * 结构参照 DamageReductionEffect（同样靠 lastHealth 快照检测掉血），
 * 区别是：不乘 lossRatio，比例是固定的；补的是护盾而不是血量；护盾无上限。
 */
public class HealthToShieldEffect extends StatusEffect {

    /** 固定转换比例：削减量的多少变成护盾。 */
    public float convertRatio;

    private final IntMap<Float> lastHealth = new IntMap<>();

    public HealthToShieldEffect(String name, float convertRatio) {
        super(name);
        this.convertRatio = convertRatio;
    }

    @Override
    public void applied(Unit unit, float time, boolean extend) {
        super.applied(unit, time, extend);
        if (!extend && unit != null) {
            lastHealth.put(unit.id, unit.health);
        }
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            if (unit != null) lastHealth.remove(unit.id);
            return;
        }
        float prev = lastHealth.get(unit.id, Float.NaN);
        if (!Float.isNaN(prev) && unit.health < prev) {
            unit.shield += (prev - unit.health) * convertRatio;
        }
        lastHealth.put(unit.id, unit.health);
    }

    @Override
    public void onRemoved(Unit unit) {
        if (unit != null) lastHealth.remove(unit.id);
    }
}
