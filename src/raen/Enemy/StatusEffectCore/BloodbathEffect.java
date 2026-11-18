package raen.Enemy.StatusEffectCore;

import arc.math.Mathf;
import arc.struct.IntMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 浴血：开火后激活，每秒扣自身当前生命的百分比，并按已损失生命强化一项属性。
 *
 * <p>倍率直接乘进单位的聚合倍率字段（{@code unit.damageMultiplier()} 等）。
 * <b>不要</b>给每个单位 new 一个子 StatusEffect：运行时 new 内容会往
 * {@code Vars.content} 注册全局名字，重名直接抛
 * {@code IllegalArgumentException: Two content objects defined with the same name}，
 * 而且每次 new 都会往内容表里塞一条（{@code StatusEffect.id} 是 short，还会溢出）。
 *
 * <p>为什么必须每帧乘一次：{@code StatusComp.update()} 每帧开头把单位的聚合倍率
 * 全部重置成 1，再逐条状态相乘。所以只在「结算间隔」写一次是不够的，
 * 每帧都要把当前倍率重新乘进去。
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

    /** 每个单位当前生效的倍率（1 = 无加成），每结算间隔刷新一次。 */
    private final IntMap<Float> bonus = new IntMap<>();
    /** 每个单位是否开过火。 */
    private final IntMap<Boolean> armed = new IntMap<>();
    /** 每个单位的结算计时器。 */
    private final IntMap<Float> timers = new IntMap<>();

    /** 所有实例：世界加载时统一清空按单位 id 记录的运行时状态（id 会重用）。 */
    private static final Seq<BloodbathEffect> instances = new Seq<>();

    public BloodbathEffect(String name, Kind kind, float maxBonus) {
        super(name);
        this.kind = kind;
        this.maxBonus = maxBonus;
        this.damage = 0f;
        this.transitionDamage = 0f;
        instances.add(this);
    }

    /** 清空所有实例按单位 id 记录的运行时状态（世界加载时调用；id 会重用，不清会串到新单位上）。 */
    public static void clearAll() {
        for (BloodbathEffect effect : instances) {
            effect.armed.clear();
            effect.timers.clear();
            effect.bonus.clear();
        }
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            forget(unit);
            return;
        }

        // 没开过火：整套机制都不启动，计时器也不走
        // （原实现是「先判开火、再累加计时」，所以 1 秒周期是从第一次开火开始算的）。
        if (!armed.get(unit.id, false)) {
            if (!unit.isShooting) return;
            armed.put(unit.id, true);
        }

        float timer = timers.get(unit.id, 0f) + Time.delta;
        if (timer < interval) {
            timers.put(unit.id, timer);
        } else {
            timer %= interval;
            timers.put(unit.id, timer);

            // ① 每秒扣当前生命的 1%
            if (selfDamageRatio > 0f && unit.health > 0f) {
                unit.damage(unit.health * selfDamageRatio);
            }
            if (unit.dead() || !unit.isValid()) {
                forget(unit);
                return;
            }

            // ② 按已损失生命比例算本周期的加成
            float lost = 1f - Mathf.clamp(unit.health / Math.max(unit.maxHealth, 1f), 0f, 1f);
            bonus.put(unit.id, 1f + maxBonus * lost);
        }

        // ③ 每帧把加成乘进单位的聚合倍率。
        //    StatusComp 每帧都会把聚合倍率重置成 1 再按状态重算，所以必须每帧乘一次。
        float mul = bonus.get(unit.id, 1f);
        if (mul != 1f) {
            if (kind == Kind.DAMAGE) {
                unit.damageMultiplier(unit.damageMultiplier() * mul);
            } else if (kind == Kind.RELOAD) {
                unit.reloadMultiplier(unit.reloadMultiplier() * mul);
            } else {
                unit.speedMultiplier(unit.speedMultiplier() * mul);
            }
        }
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        forget(unit);
    }

    private void forget(Unit unit) {
        if (unit == null) return;
        armed.remove(unit.id);
        timers.remove(unit.id);
        bonus.remove(unit.id);
    }
}
