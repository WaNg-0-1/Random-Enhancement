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
 * 动能反击：每 1 秒快照自身「生命 + 护盾」，有衰减就在原地发射动能导弹。
 *
 * <p>导弹伤害 = 这一秒的衰减量；数量与最高伤害倍率随残血程度上升。
 * 导弹用 {@link KineticBulletType}：飞得越久越快、伤害越高（1 → max 倍率），
 * 40 格射程、追踪范围按 {@link #homingTiles}（当前 40 格）。
 * 落点在自身碰撞体积的 {@link #spawnRadiusFraction} 倍范围内散开，逐枚累加随机延迟。
 *
 * <p>和浴血 / 进攻 / 行者 / 吞噬一样，per-unit 快照放实例级 {@link IntMap}，
 * 换图时由 {@link #clearAll()} 清空；不运行时 new StatusEffect。
 * 子弹类型是 {@code BulletType}（不是内容对象），且自绘几何图形，运行时 new 安全。
 */
public class KineticCounterEffect extends StatusEffect {

    /** 快照间隔（tick）。 */
    public float snapshotTicks = 60f;
    /** 满血时的最低数量，残血时最多生成 maxMissiles 枚。 */
    public int minMissiles = 1;
    /** 残血时的最大数量：低级 3 / 中级 5 / 高级 7。 */
    public int maxMissiles = 3;
    /** 满血时的最低伤害倍率。 */
    public float minMultiplier = 1.5f;
    /** 残血时的最高伤害倍率：低级 3 / 中级 4.5 / 高级 6。 */
    public float maxMultiplier = 3f;
    /** 导弹射程（格）。 */
    public float rangeTiles = 40f;
    /** 导弹追踪范围（格）。 */
    public float homingTiles = 40f;
    /** 导弹初速（世界单位/tick）。 */
    public float missileSpeed = 1f;
    /** 导弹存活时长（tick）。 */
    public float missileLifetime = 120f;
    /** 导弹负 drag：越到末期加速越猛。 */
    public float missileDrag = -0.006f;
    /** 散布半径 = 自身碰撞体积 × 这个系数（当前为 2 倍碰撞体积）。 */
    public float spawnRadiusFraction = 2f;
    /** 散布半径的下限比例（同一圈上再做径向抖动，避免所有子弹叠在一个点）。 */
    public float spawnRadiusMinFraction = 0.6f;
    /** 每颗导弹之间的随机延迟下限（秒）。 */
    public float spawnDelayMinSeconds = 0.3f;
    /** 每颗导弹之间的随机延迟上限（秒）。 */
    public float spawnDelayMaxSeconds = 1f;

    /** 一枚待发射导弹：延迟按累计值排队，位置存相对偏移（发射时跟随单位当前位置）。 */
    private static final class Pending {
        KineticBulletType type;
        float delay;
        float dx, dy;
        float angle;
    }

    private static final class State {
        float timer;
        float last;
        boolean hasLast;
        final Seq<Pending> pending = new Seq<>();
    }

    private final IntMap<State> states = new IntMap<>();
    private static final Seq<KineticCounterEffect> instances = new Seq<>();

    public KineticCounterEffect(String name, int maxMissiles, float maxMultiplier) {
        super(name);
        this.maxMissiles = maxMissiles;
        this.maxMultiplier = maxMultiplier;
        this.damage = 0f;
        this.transitionDamage = 0f;
        instances.add(this);
    }

    /** 世界加载时调用：清空按单位 id 记录的快照。 */
    public static void clearAll() {
        for (KineticCounterEffect effect : instances) effect.states.clear();
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

        // 待发射队列每帧都要推进（不是只有快照帧），单位移动时落点跟着一起走。
        float seconds = Time.delta / 60f;
        for (int i = st.pending.size - 1; i >= 0; i--) {
            Pending p = st.pending.get(i);
            p.delay -= seconds;
            if (p.delay > 0f) continue;
            p.type.create(unit, unit.team, unit.x + p.dx, unit.y + p.dy, p.angle);
            st.pending.remove(i);
        }

        st.timer += Time.delta;
        if (st.timer < snapshotTicks) return;
        st.timer %= snapshotTicks;

        float now = unit.health + unit.shield;
        float last = st.hasLast ? st.last : now;
        st.last = now;
        st.hasLast = true;

        float decay = last - now;
        if (decay <= 0f) return;

        // 残血程度：1 = 满血，0 = 空血；数量与倍率都随它上升。
        float hpFrac = Mathf.clamp(unit.healthf(), 0f, 1f);
        int count = Mathf.clamp(minMissiles + Math.round((maxMissiles - minMissiles) * (1f - hpFrac)),
                minMissiles, maxMissiles);
        float multiplier = minMultiplier + (maxMultiplier - minMultiplier) * (1f - hpFrac);

        KineticBulletType type = new KineticBulletType(decay, multiplier,
                missileSpeed, rangeTiles, missileLifetime, missileDrag);
        type.homingRange = homingTiles * Vars.tilesize;

        // 均匀角度 + 径向抖动，落点落在自身碰撞体积的 spawnRadiusFraction 倍范围内；
        // 每颗之间累加一个 0.3~1 秒的随机延迟，避免一次性糊成一团。
        float radius = Math.max(1f, unit.hitSize) * spawnRadiusFraction;
        float offset = Mathf.random(360f);
        float step = 360f / count;
        float delay = 0f;
        for (int i = 0; i < count; i++) {
            float angle = offset + step * i;
            float dist = radius * Mathf.random(spawnRadiusMinFraction, 1f);
            delay += Mathf.random(spawnDelayMinSeconds, spawnDelayMaxSeconds);

            Pending p = new Pending();
            p.type = type;
            p.delay = delay;
            p.dx = Mathf.cosDeg(angle) * dist;
            p.dy = Mathf.sinDeg(angle) * dist;
            p.angle = angle;
            st.pending.add(p);
        }
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
