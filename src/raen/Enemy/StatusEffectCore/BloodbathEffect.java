package raen.Enemy.StatusEffectCore;

import arc.math.Mathf;
import arc.struct.IntMap;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 浴血：开火后激活，每秒扣自身当前生命的百分比，并按已损失生命强化一项属性。
 */
public class BloodbathEffect extends StatusEffect {

    public enum Kind {
        /** 伤害倍率。 */
        DAMAGE,
        /** 攻速倍率（开火速度）。 */
        RELOAD,
        /** 移动速度倍率。 */
        SPEED
    }

    /** 这个变体强化哪一项。 */
    public Kind kind = Kind.DAMAGE;
    /** 血量归零时的加成上限（0.02 → +2% … 2 → +200%）。 */
    public float maxBonus = 2f;
    /** 每秒对自己造成的当前生命比例。 */
    public float selfDamageRatio = 0.01f;
    /** 结算间隔（tick）。 */
    public float interval = 60f;
    /** 子效果持续时间（tick）。 */
    public float childDuration = 60f;

    private final IntMap<Boolean> armed = new IntMap<>();
    private final IntMap<Float> timers = new IntMap<>();
    private final IntMap<StatusEffect> children = new IntMap<>();

    public BloodbathEffect(String name, Kind kind, float maxBonus) {
        super(name);
        this.kind = kind;
        this.maxBonus = maxBonus;
        this.damage = 0f;
        this.transitionDamage = 0f;
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            forget(unit);
            return;
        }

        if (!armed.get(unit.id, false)) {
            if (!unit.isShooting) return;
            armed.put(unit.id, true);
        }

        float timer = timers.get(unit.id, 0f) + Time.delta;
        if (timer < interval) {
            timers.put(unit.id, timer);
            return;
        }
        timer %= interval;
        timers.put(unit.id, timer);

        if (selfDamageRatio > 0f && unit.health > 0f) {
            unit.damage(unit.health * selfDamageRatio);
        }
        if (unit.dead() || !unit.isValid()) {
            forget(unit);
            return;
        }

        float lost = 1f - Mathf.clamp(unit.health / Math.max(unit.maxHealth, 1f), 0f, 1f);
        StatusEffect child = childFor(unit);
        float bonus = maxBonus * lost;
        if (kind == Kind.DAMAGE) {
            child.damageMultiplier = 1f + bonus;
        } else if (kind == Kind.RELOAD) {
            child.reloadMultiplier = 1f + bonus;
        } else {
            child.speedMultiplier = 1f + bonus;
        }
        unit.apply(child, childDuration);
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        forget(unit);
    }

    private StatusEffect childFor(Unit unit) {
        StatusEffect child = children.get(unit.id);
        if (child != null) return child;

        child = new StatusEffect("raen-blood-" + name + "-" + unit.id) {{
            damage = 0f;
            transitionDamage = 0f;
            permanent = false;
        }};
        child.uiIcon = child.fullIcon = uiIcon;
        child.color = color;

        children.put(unit.id, child);
        return child;
    }

    private void forget(Unit unit) {
        if (unit == null) return;
        armed.remove(unit.id);
        timers.remove(unit.id);
        children.remove(unit.id);
    }
}
