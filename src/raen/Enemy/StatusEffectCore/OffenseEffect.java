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
 * 进攻：持续攻击越久，倍率越高；停火立刻归零。
 */
public class OffenseEffect extends StatusEffect {

    public enum Kind {
        /** 攻击力倍率。 */
        DAMAGE,
        /** 攻速倍率。 */
        RELOAD
    }

    /** 这个变体强化哪一项。 */
    public Kind kind = Kind.DAMAGE;
    /** 蓄满时的倍率（2 = 200%）。 */
    public float maxMultiplier = 2f;
    /** 蓄满需要的连续攻击时长（秒）。 */
    public float rampSeconds = 60f;
    /** 代价：蓄满时移速 / 有效血量倍率降到的值（0.25 = 25%）。 */
    public float minPenaltyMultiplier = 0.25f;

    /** 单位 id → 连续攻击时长（tick）。 */
    private final IntMap<Float> shootTime = new IntMap<>();
    /** 单位 id → 当前生效的加成倍率（1 = 无加成）。 */
    private final IntMap<Float> bonus = new IntMap<>();
    /** 单位 id → 当前生效的代价倍率（1 = 无代价）。 */
    private final IntMap<Float> penaltyMul = new IntMap<>();

    /** 所有实例：世界加载时统一清空按单位 id 记录的运行时状态（id 会重用）。 */
    private static final Seq<OffenseEffect> instances = new Seq<>();

    public OffenseEffect(String name, Kind kind, float maxMultiplier) {
        super(name);
        this.kind = kind;
        this.maxMultiplier = maxMultiplier;
        this.damage = 0f;
        this.transitionDamage = 0f;
        instances.add(this);
    }

    /** 清空所有实例按单位 id 记录的运行时状态（世界加载时调用；id 会重用，不清会串到新单位上）。 */
    public static void clearAll() {
        for (OffenseEffect effect : instances) {
            effect.shootTime.clear();
            effect.bonus.clear();
            effect.penaltyMul.clear();
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

        // 停火：蓄力与倍率立刻归零（聚合倍率每帧会被 StatusComp 重置成 1，
        // 这里不写就等于没有加成 / 没有代价）。
        if (!unit.isShooting) {
            shootTime.put(unit.id, 0f);
            bonus.put(unit.id, 1f);
            penaltyMul.put(unit.id, 1f);
            return;
        }

        shootTime.put(unit.id, shootTime.get(unit.id, 0f) + Time.delta);

        float progress = rampSeconds <= 0f
                ? 1f
                : Mathf.clamp(shootTime.get(unit.id, 0f) / (rampSeconds * 60f), 0f, 1f);

        // 加成：1 → maxMultiplier
        float multiplier = 1f + (maxMultiplier - 1f) * progress;
        bonus.put(unit.id, multiplier);
        // 代价：1 → minPenaltyMultiplier（随进度线性加深）
        penaltyMul.put(unit.id, penalty(progress));

        // 每帧把加成 / 代价乘进单位的聚合倍率。
        // StatusComp 每帧都会把聚合倍率重置成 1 再按状态重算，所以必须每帧乘一次。
        if (multiplier != 1f) {
            if (kind == Kind.DAMAGE) {
                unit.damageMultiplier(unit.damageMultiplier() * multiplier);
            } else {
                unit.reloadMultiplier(unit.reloadMultiplier() * multiplier);
            }
        }

        float pen = penaltyMul.get(unit.id, 1f);
        if (pen != 1f) {
            unit.speedMultiplier(unit.speedMultiplier() * pen);
            unit.healthMultiplier(unit.healthMultiplier() * pen);
        }
    }

    /** 代价倍率：随进度从 100% 线性降到 minPenaltyMultiplier。 */
    private float penalty(float progress) {
        return 1f - (1f - minPenaltyMultiplier) * progress;
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        forget(unit);
    }

    private void forget(Unit unit) {
        if (unit == null) return;
        shootTime.remove(unit.id);
        bonus.remove(unit.id);
        penaltyMul.remove(unit.id);
    }
}
