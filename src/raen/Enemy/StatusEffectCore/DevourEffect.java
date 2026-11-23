package raen.Enemy.StatusEffectCore;

import arc.struct.IntMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.entities.Effect;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 吞噬：周期性进入 1 秒吞噬窗口，吃掉身边的敌方子弹并按其伤害回血 / 回盾。
 *
 * <p>和浴血 / 进攻 / 行者一样，per-unit 数值放在实例级 {@link IntMap} 里，
 * 世界加载时由 {@link #clearAll()} 清空；**不在运行时 new StatusEffect**
 * （构造会把名字永久登记进内容表，还会把 {@code short id} 推到负数）。
 */
public class DevourEffect extends StatusEffect {

    /** 周期（秒）：每这么久进入一次吞噬窗口。 */
    public float cycleSeconds = 10f;
    /** 吞噬窗口时长（秒）。 */
    public float activeSeconds = 1f;
    /** 窗口开始时给自己挂缴械 / 静止的时长（秒），比窗口长 0.5 秒。 */
    public float disableSeconds = 1.5f;
    /** 吞噬半径 = 自身碰撞体积 + 这个额外值（8 = 1 格）。 */
    public float radiusExtra = 8f;
    /** 回血比例：被吞子弹伤害总和 × 这个系数。 */
    public float healRatio = 0.75f;
    /** 单次吞噬的固定回血上限。 */
    public float healCapFlat = 360f;
    /** 单次吞噬上限中「已损失生命」的系数。 */
    public float healCapLostRatio = 0.25f;
    /** 护盾上限 = 最大生命 × 这个系数。 */
    public float shieldCapRatio = 0.5f;
    /** 进入吞噬窗口时在自身位置播放的特效（用效果自身配色）。 */
    public Effect burstEffect = Fx.shieldWave;
    /** 吞掉一颗子弹时在其位置播放的特效。 */
    public Effect eatEffect = Fx.absorb;

    private static final class State {
        float timer;
        boolean wasActive;
        /** 本窗口吞掉的子弹伤害累计。 */
        float damage;
        /** 本窗口已发出去的恢复量（回血 + 转盾），受 cap 限制。 */
        float applied;
        /** 本窗口开始时的恢复上限。 */
        float cap;
    }

    private final IntMap<State> states = new IntMap<>();
    private static final Seq<DevourEffect> instances = new Seq<>();

    public DevourEffect(String name, float cycleSeconds, float healRatio) {
        super(name);
        this.cycleSeconds = cycleSeconds;
        this.healRatio = healRatio;
        this.damage = 0f;
        this.transitionDamage = 0f;
        instances.add(this);
    }

    /** 世界加载时调用：清空按单位 id 记录的运行时状态。 */
    public static void clearAll() {
        for (DevourEffect effect : instances) effect.states.clear();
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            forget(unit);
            return;
        }

        float period = cycleSeconds;
        if (period <= 0f) return;

        State st = states.get(unit.id);
        if (st == null) {
            st = new State();
            states.put(unit.id, st);
        }

        float timer = st.timer + Time.delta / 60f;
        if (timer >= period) timer %= period;
        st.timer = timer;

        boolean active = timer < activeSeconds;
        if (active && !st.wasActive) {
            // 窗口刚开始：结算基准取这一刻的生命，回血过程中 cap 不再缩水。
            st.damage = 0f;
            st.applied = 0f;
            st.cap = healCapFlat + healCapLostRatio * Math.max(0f, unit.maxHealth - unit.health);
            unit.apply(StatusEffects.disarmed, disableSeconds * 60f);
            unit.apply(StatusEffects.unmoving, disableSeconds * 60f);
            burstEffect.at(unit.x, unit.y, color);
        }
        st.wasActive = active;
        if (!active) return;

        float radius = unit.hitSize + radiusExtra;
        if (radius <= 0f) return;
        final float r2 = radius * radius;

        // 先收集再删：边遍历 quadtree 边 remove 会漏掉同一格里的子弹。
        Seq<Bullet> eaten = new Seq<>();
        Groups.bullet.intersect(unit.x - radius, unit.y - radius, radius * 2f, radius * 2f, b -> {
            if (b == null || !b.isAdded()) return;
            if (b.team == unit.team) return;
            if (b.dst2(unit.x, unit.y) > r2) return;
            eaten.add(b);
        });

        for (Bullet b : eaten) {
            st.damage += Math.max(0f, b.damage);
            eatEffect.at(b.x, b.y);
            b.remove();
        }

        float target = Math.min(st.damage * healRatio, st.cap);
        float delta = target - st.applied;
        if (delta <= 0f) return;
        st.applied += delta;
        healWithShieldOverflow(unit, delta);
    }

    /** 先回血，回不进去的部分转护盾；护盾不超过 maxHealth × shieldCapRatio。 */
    private void healWithShieldOverflow(Unit unit, float amount) {
        float before = unit.health;
        unit.heal(amount);
        float overflow = amount - (unit.health - before);
        if (overflow <= 0f) return;

        float shieldMax = shieldCapRatio * unit.maxHealth;
        if (unit.shield >= shieldMax) return;
        unit.shield = Math.min(shieldMax, unit.shield + overflow);
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
