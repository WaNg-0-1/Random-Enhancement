package raen.Enemy.StatusEffectCore;

import arc.util.Time;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 有盾、且血量不满时，持续把护盾按比例转成血量。
 *
 * shieldPerSecond 是每秒消耗的护盾，healthPerSecond 是每秒换回的血量，
 * 两者之比就是「一点盾换多少血」；护盾见底时会按剩余量等比折算，不会透支。
 */
public class ShieldToHealthEffect extends StatusEffect {

    public float shieldPerSecond;
    public float healthPerSecond;

    public ShieldToHealthEffect(String name, float shieldPerSecond, float healthPerSecond) {
        super(name);
        this.shieldPerSecond = shieldPerSecond;
        this.healthPerSecond = healthPerSecond;
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (unit == null || unit.dead() || !unit.isValid()) return;
        // 有盾 + 血量不满，才转换
        if (unit.shield <= 0f || unit.health >= unit.maxHealth) return;
        if (shieldPerSecond <= 0f || healthPerSecond <= 0f) return;

        float seconds = Time.delta / 60f;
        float perShield = healthPerSecond / shieldPerSecond;   // 每点盾换到的血
        float missing   = unit.maxHealth - unit.health;

        // 本帧最多消耗：每秒速率 / 剩余护盾 / 刚好补满所需，三者取最小
        float use = Math.min(unit.shield, Math.min(shieldPerSecond * seconds, missing / perShield));
        if (use <= 0f) return;

        unit.shield -= use;
        unit.heal(use * perShield);
    }
}
