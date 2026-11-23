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
 * 行者系：持续移动时逐步蓄力，停步立刻归零。
 *
 * <p>和 {@link BloodbathEffect} / {@link OffenseEffect} 一样，倍率直接乘进单位的
 * 每帧聚合倍率字段（{@code unit.speedMultiplier()} 等）。<b>绝对不要</b>给每个单位
 * 运行时 {@code new} 一个子 StatusEffect —— 构造即把名字永久登记进
 * {@code Vars.content.contentNameMap}（无反注册 API，重名抛
 * {@code IllegalArgumentException}），还会往内容表追加条目并把 {@code short id}
 * 推到负数，最终 OOM。
 *
 * <p>蓄力 / 停步状态放在实例级 {@link IntMap} 里，换图时由 {@link #clearAll()} 统一清空
 * （单位 id 会重用）。
 */
public class WalkerEffect extends StatusEffect {

    public enum Kind {
        /** 疾步：只强化移速。 */
        SPRINT,
        /** 动能攻击：强化攻击与移速。 */
        KINETIC,
        /** 追击者：强化攻速与移速。 */
        PURSUER
    }

    /** 这个变体强化哪一项（移速三种都强化）。 */
    public Kind kind = Kind.SPRINT;
    /** 蓄满时的加成：{@code 2f} = +200%，即最终倍率 3 倍。 */
    public float maxBonus = 2f;
    /** 蓄满需要的连续移动时长（秒）。 */
    public float rampSeconds = 30f;
    /** 蓄满时攻击倍率降到的值（0.5 = 50%）；1 = 无代价。 */
    public float minDamageMultiplier = 1f;
    /** 蓄满时攻速倍率降到的值（0.5 = 50%）；1 = 无代价。 */
    public float minReloadMultiplier = 1f;
    /** 蓄满时血量倍率降到的值（0.45 = 45%）；1 = 无代价。 */
    public float minHealthMultiplier = 1f;
    /** 单帧最小位移（世界单位，约等于 0.01 像素），小于该值视为停步。 */
    public static float moveEpsilon = 0.01f;

    /** 每个单位一份运行时状态。 */
    private static final class State {
        float ticks;
        float x, y;
        boolean hasLast;
    }

    private final IntMap<State> states = new IntMap<>();
    /** 所有实例：世界加载时统一清空（单位 id 会重用）。 */
    private static final Seq<WalkerEffect> instances = new Seq<>();

    public WalkerEffect(String name, Kind kind, float maxBonus,
                        float minDamageMultiplier, float minReloadMultiplier, float minHealthMultiplier) {
        super(name);
        this.kind = kind;
        this.maxBonus = maxBonus;
        this.minDamageMultiplier = minDamageMultiplier;
        this.minReloadMultiplier = minReloadMultiplier;
        this.minHealthMultiplier = minHealthMultiplier;
        this.damage = 0f;
        this.transitionDamage = 0f;
        instances.add(this);
    }

    /** 世界加载时调用：清空按单位 id 记录的蓄力状态。 */
    public static void clearAll() {
        for (WalkerEffect effect : instances) effect.states.clear();
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            forget(unit);
            return;
        }

        State st = states.get(unit.id);
        if (st == null) {
            st = new State();
            states.put(unit.id, st);
        }

        // 用「上一帧到这一帧的实际位移」判移动，而不是 vel：
        // vel 在被墙挡住时仍可能非 0，位置不动却算在移动。
        boolean moving = false;
        if (st.hasLast) {
            float dx = unit.x - st.x;
            float dy = unit.y - st.y;
            moving = dx * dx + dy * dy >= moveEpsilon * moveEpsilon;
        }
        st.x = unit.x;
        st.y = unit.y;
        st.hasLast = true;

        if (!moving) {
            st.ticks = 0f;
            // 聚合倍率每帧会被 StatusComp 重置成 1，这里不写就等于没有加成 / 没有代价。
            return;
        }

        st.ticks += Time.delta;
        float progress = rampSeconds <= 0f
                ? 1f
                : Mathf.clamp(st.ticks / (rampSeconds * 60f), 0f, 1f);

        // 加成：1 → 1 + maxBonus（+0% → +200% / +400% / +600%）。
        float bonus = 1f + maxBonus * progress;
        unit.speedMultiplier(unit.speedMultiplier() * bonus);
        if (kind == Kind.KINETIC) {
            unit.damageMultiplier(unit.damageMultiplier() * bonus);
        } else if (kind == Kind.PURSUER) {
            unit.reloadMultiplier(unit.reloadMultiplier() * bonus);
        }

        // 代价：随进度从 100% 线性降到各自的倍率下限。
        float damage = 1f - (1f - minDamageMultiplier) * progress;
        if (damage != 1f) unit.damageMultiplier(unit.damageMultiplier() * damage);
        float reload = 1f - (1f - minReloadMultiplier) * progress;
        if (reload != 1f) unit.reloadMultiplier(unit.reloadMultiplier() * reload);
        float health = 1f - (1f - minHealthMultiplier) * progress;
        if (health != 1f) unit.healthMultiplier(unit.healthMultiplier() * health);
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        forget(unit);
    }

    private void forget(Unit unit) {
        if (unit == null) return;
        states.remove(unit.id);
    }
}
