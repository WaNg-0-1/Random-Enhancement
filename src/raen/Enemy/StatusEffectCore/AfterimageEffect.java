package raen.Enemy.StatusEffectCore;

import arc.Events;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.types.FlyingAI;
import mindustry.ai.types.GroundAI;
import mindustry.content.Fx;
import mindustry.entities.units.StatusEntry;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import raen.Enemy.raenStatusEffects;

/**
 * 残影：按 {@link #snapshotInterval} 快照一次携带者的血量（低 / 中 2 秒，高级 1.5 秒）。
 * <p>
 * 两次快照之间血量有衰减（后一次快照低于前一次）时，按「两次快照的差值」在自身周围
 * 生成一个<b>同类型</b>的分身（武器 / 能力天然继承，走原版的 {@link GroundAI} / {@link FlyingAI}
 * 正常作战），分身存在 {@link #LIFE_SECONDS} 秒后自然消失。
 * <p>
 * 自然消失时携带者回复「分身剩余生命 × {@link #refundRatio}」；消失前被打死则一分不回。
 * 同时最多存在 {@link #maxClones} 个残影。
 * <p>
 * 残影单位本身：
 * <ul>
 *   <li>不会再被随机强化系统抽到（{@code BuffManager.isTarget} 里显式排除）；</li>
 *   <li>不会再产生残影（{@link #isClone} 兜底）；</li>
 *   <li>自然消失走 {@code remove()} 而不是 {@code kill()}，避免触发死亡结算
 *       （回收抢武器、积焰死亡爆炸、分裂等都不该被残影骗触发）。</li>
 * </ul>
 * <b>存档</b>：单位为 v160 的存档里<b>会写状态</b>（{@code TypeIO.writeStatus}：short 效果 id + float 时长），
 * 所以绑定借两个内部状态做载体：{@link #mark}（time = 剩余寿命）与 {@link #ownerMark}
 * （time = 本体 id 编码）。读档后靠这两个状态把残影与本体重新绑上；
 * 本体找不到（例如存档里本体已死）就让残影按剩余寿命自然消散、不结算回血，<b>不会留下孤儿单位</b>。
 */
public class AfterimageEffect extends StatusEffect {

    /** 默认快照间隔：2 秒（60 tick/秒）；高级档可覆盖为 1.5 秒。 */
    public static final float SNAPSHOT_INTERVAL = 60f * 2f;
    /** 本档的快照 / 生成间隔（tick）。 */
    public float snapshotInterval = SNAPSHOT_INTERVAL;
    /** 残影存在时长：4 秒。 */
    public static final float LIFE_SECONDS = 4f;
    /**
     * 本体 id 的存档编码：{@code time = id * ID_BIN + ID_BIAS}。
     * 状态的 time 每帧都会被引擎扣 {@code Time.delta}，所以留一点容差：
     * 只要读档那一帧的衰减小于 {@code ID_BIAS}（8 tick），{@code (int)(time / ID_BIN)} 仍然精确等于 id。
     * 代价是 id 上限 = 2^24 / 16 ≈ 104 万（超出的单位不写归属，残影读档后退化成「无主残影」，只自然消散）。
     */
    private static final float ID_BIN = 16f;
    private static final float ID_BIAS = 8f;
    private static final int ID_MAX = (1 << 20);   // 2^24 / 16
    /** 状态表的清扫间隔：10 秒（单位 id 会回收，必须定期清死引用）。 */
    private static final float PRUNE_INTERVAL = 60f * 10f;
    /** 落点尝试次数（避开实心地块 / 建筑 / 深液）。 */
    private static final int SPAWN_ATTEMPTS = 8;

    /** 同时存在的残影上限：低级 1 / 中级 2 / 高级 3。 */
    public int maxClones = 1;
    /** 自然消失时按「残影剩余生命」返还给携带者的比例：50% / 75% / 100%。 */
    public float refundRatio = 0.5f;

    /** 存档载体 1：残影标记，time = 剩余寿命（tick）。必须进 content —— 存档按 content id 写状态。 */
    public static StatusEffect mark;
    /** 存档载体 2：时间 = 本体 id 编码，见 {@link #ID_BIN}。 */
    public static StatusEffect ownerMark;

    /** 每个携带者的快照状态（效果实例是全局共享的，按 unit id 分表）。 */
    private final IntMap<State> states = new IntMap<>();

    /** 所有档位实例：清扫状态表时要一起遍历。 */
    private static final Seq<AfterimageEffect> instances = new Seq<>();
    /** 在册残影：寿命 / 回血由全局 tick 统一推进。 */
    private static final Seq<Clone> clones = new Seq<>();
    /** 残影单位集合，供 {@link #isClone} 与随机强化系统快速判定。 */
    private static final ObjectSet<Unit> cloneSet = new ObjectSet<>();

    private static boolean ticked = false;
    private static float pruneTimer = 0f;
    /** 读档后等实体全部读完再重建绑定（WorldLoadEvent 触发时 Groups.unit 还是空的）。 */
    private static boolean rebindPending = false;

    private static class State {
        Unit owner;
        float lastSnapshot;
        float timer;
    }

    private static class Clone {
        Unit unit;
        Unit owner;
        /** 剩余 tick。 */
        float life;
        float refund;
    }

    public AfterimageEffect(String name, int maxClones, float refundRatio) {
        super(name);
        this.damage = 0f;
        this.transitionDamage = 0f;
        this.maxClones = maxClones;
        this.refundRatio = refundRatio;
        instances.add(this);
    }

    /** 由 raenStatusEffects.load() 调用：注册两个存档载体状态（重载模组时按名字复用）。 */
    public static void loadMarks() {
        mark = registerMark("raen-afterimage");
        ownerMark = registerMark("raen-afterimage-owner");
    }

    private static StatusEffect registerMark(String name) {
        for (StatusEffect existing : Vars.content.statusEffects()) {
            if (existing != null && name.equals(existing.name)) return existing;
        }
        StatusEffect effect = new StatusEffect(name);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        // permanent：不会被引擎自动摘掉；time 仍然每帧递减，正好当「剩余寿命」用。
        effect.permanent = true;
        effect.color = Color.valueOf("cdd6e6");
        Vars.content.statusEffects().add(effect);
        return effect;
    }

    /** 由主类在 init() 里挂上：残影的寿命 / 回血 / 读档重建都要走全局钩子（残影身上没有本效果）。 */
    public static void registerHooks() {
        if (ticked) return;
        ticked = true;
        Events.run(EventType.Trigger.update, AfterimageEffect::tick);

        // 换图 / 读档：单位全部重建，先丢掉进程内的旧账本。
        // 注意本事件在读图阶段就触发（entities 还没读，Groups.unit 是空的），
        // 所以真正的「按标记状态重建绑定」推迟到下一帧的 tick 里做。
        Events.on(EventType.WorldLoadEvent.class, event -> {
            clearAll();
            rebindPending = true;
        });
        Events.on(EventType.ResetEvent.class, event -> {
            clearAll();
            rebindPending = false;
        });
    }

    /** 读档后重建：靠残影身上的标记状态找回本体与剩余寿命。 */
    private static void rebindFromStatuses() {
        if (mark == null) return;
        // 先收集、遍历完再删：边遍历 Groups.unit 边 remove 会打乱遍历下标。
        Seq<Unit> expired = new Seq<>();
        for (Unit unit : Groups.unit) {
            if (unit == null || unit.dead() || !unit.isValid()) continue;
            if (cloneSet.contains(unit)) continue;

            float life = unit.getDuration(mark);
            if (life <= 0f) {
                // 有标记条目但寿命已归零（存档正好卡在消散那一帧）：直接收掉，别留成孤儿。
                if (unit.hasEffect(mark) && unit.isAdded()) expired.add(unit);
                continue;
            }

            Unit owner = null;
            float raw = (ownerMark == null) ? 0f : unit.getDuration(ownerMark);
            if (raw > 0f) {
                Unit candidate = Groups.unit.getByID((int) (raw / ID_BIN));
                if (candidate != null && candidate != unit && !candidate.dead() && candidate.isAdded()) {
                    owner = candidate;
                }
            }

            Clone c = new Clone();
            c.unit = unit;
            c.owner = owner;
            c.life = life;
            c.refund = refundOf(owner);
            clones.add(c);
            cloneSet.add(unit);
        }

        for (Unit unit : expired) {
            unit.clearStatuses();
            unit.remove();
        }
    }

    /** 该本体身上挂的是哪一档：低级 50% / 中级 75% / 高级 100%，没有则 0（不回血）。 */
    private static float refundOf(Unit owner) {
        if (owner == null) return 0f;
        StatusEffect[] tiers = {raenStatusEffects.AfterimageC, raenStatusEffects.AfterimageB,
            raenStatusEffects.AfterimageA};
        for (StatusEffect tier : tiers) {
            if (tier instanceof AfterimageEffect effect && raen.Enemy.RaenStatusDriver.getDuration(owner, tier) > 0f) {
                return effect.refundRatio;
            }
        }
        return 0f;
    }

    /** 只收残影，不结算也不动携带者快照（换图 / 读档用：世界已经没了，没有回血对象）。 */
    private static void removeAllClones() {
        for (int i = clones.size - 1; i >= 0; i--) {
            removeClone(i, false);
        }
        clones.clear();
        cloneSet.clear();
    }

    /**
     * 该单位是否是残影。随机强化系统靠它把残影挡在抽取之外。
     * <p>除了进程内的账本，还看标记状态：读档时单位是在 {@code WorldLoadEvent} 之后才创建的，
     * 那一刻账本还是空的，只有状态能立刻认出来（否则残影会被随机强化抽到）。
     */
    public static boolean isClone(Unit unit) {
        if (unit == null) return false;
        if (cloneSet.contains(unit)) return true;
        return mark != null && unit.getDuration(mark) > 0f;
    }

    /** 清空所有残影与快照（换图 / 读档 / 新开时调用）。 */
    public static void clearAll() {
        removeAllClones();
        for (AfterimageEffect effect : instances) {
            effect.states.clear();
        }
        pruneTimer = 0f;
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            if (unit != null) states.remove(unit.id);
            return;
        }
        // 兜底：残影即使因为别的路径拿到了本效果，也绝不再产生残影。
        if (isClone(unit)) return;
        if (maxClones <= 0 || refundRatio < 0f) return;

        State st = states.get(unit.id);
        if (st == null || st.owner != unit) {
            // 第一次只做基线：否则第一次快照的「差值」会等于整条血。
            st = new State();
            st.owner = unit;
            st.lastSnapshot = unit.health;
            st.timer = 0f;
            states.put(unit.id, st);
            return;
        }

        st.timer += Time.delta;
        if (st.timer < snapshotInterval) return;
        st.timer %= snapshotInterval;

        float snapshot = unit.health;
        float loss = st.lastSnapshot - snapshot;
        st.lastSnapshot = snapshot;
        // 只认「衰减」：回血 / 持平都不生成残影。
        if (loss <= 0f) return;
        if (countOwned(unit) >= maxClones) return;

        spawnClone(unit, loss);
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        if (unit == null) return;
        states.remove(unit.id);
        // 效果被摘掉（例如改难度重掷）时，自己的残影一起消散，且不结算回血。
        for (int i = clones.size - 1; i >= 0; i--) {
            if (clones.get(i).owner == unit) removeClone(i, false);
        }
    }

    // ==================== 生成 ====================

    private void spawnClone(Unit owner, float health) {
        UnitType type = owner.type;
        if (type == null) return;

        Unit clone;
        try {
            clone = type.create(owner.team);
        } catch (Exception e) {
            return;
        }
        if (clone == null) return;

        float[] pos = pickSpawnPoint(owner);
        clone.maxHealth = Math.max(health, 1f);
        clone.health = clone.maxHealth;
        clone.shield = 0f;
        clone.set(pos[0], pos[1]);
        clone.rotation = owner.rotation;
        // 显式给原版 AI：类型自带 controller 可能是 CommandAI（玩家阵营单位会原地待命），
        // 而残影要的是「可正常攻击」，和波次单位一致。
        clone.controller(type.flying ? new FlyingAI() : new GroundAI());
        clone.add();
        if (!clone.isAdded()) return;

        // 存档载体：时间 = 剩余寿命 / 本体 id（见 ID_BIN 注释）。id 过大就不写归属，
        // 读档后这个残影会退化成「无主残影」——按剩余寿命自然消散，不结算回血。
        // ⚠ 这里故意直接用 unit.apply / setDuration，不走 RaenStatusDriver：
        //   影子表只活在内存里，而标记必须落在原版 statuses 里才能被存档写出去（这是本效果唯一的持久化手段）。
        if (mark != null) clone.apply(mark, LIFE_SECONDS * 60f);
        if (ownerMark != null && owner.id >= 0 && owner.id < ID_MAX) {
            clone.apply(ownerMark, owner.id * ID_BIN + ID_BIAS);
        }

        Fx.spawn.at(clone.x, clone.y);

        Clone c = new Clone();
        c.unit = clone;
        c.owner = owner;
        c.life = LIFE_SECONDS * 60f;
        c.refund = refundRatio;
        clones.add(c);
        cloneSet.add(clone);
    }

    /** 在携带者周围选一个能站得住的落点；找不到就退回携带者脚下。 */
    private static float[] pickSpawnPoint(Unit owner) {
        float base = Math.max(owner.hitSize * 0.8f, 8f);
        for (int i = 0; i < SPAWN_ATTEMPTS; i++) {
            float ang = Mathf.random(360f);
            float dist = base * Mathf.random(0.6f, 1.4f);
            float x = owner.x + Mathf.cosDeg(ang) * dist;
            float y = owner.y + Mathf.sinDeg(ang) * dist;
            if (canStand(owner, x, y)) return new float[]{x, y};
        }
        return new float[]{owner.x, owner.y};
    }

    private static boolean canStand(Unit owner, float x, float y) {
        if (owner.type.flying) return true;
        if (Vars.world == null) return false;
        Tile tile = Vars.world.tileWorld(x, y);
        if (tile == null) return false;
        if (tile.solid() || tile.build != null) return false;
        if (owner.type.naval) return tile.floor().isLiquid;
        return !tile.floor().isLiquid || tile.floor().shallow;
    }

    private static int countOwned(Unit owner) {
        int count = 0;
        for (int i = 0; i < clones.size; i++) {
            Clone c = clones.get(i);
            if (c.owner != owner) continue;
            if (c.unit == null || c.unit.dead() || !c.unit.isAdded()) continue;
            count++;
        }
        return count;
    }

    // ==================== 寿命 / 回血 ====================

    private static void tick() {
        if (Vars.net.client()) return;
        if (Vars.state == null || !Vars.state.isPlaying()) return;

        // 读档后的第一帧：此时实体才全部读完，按标记状态重建绑定。
        if (rebindPending) {
            rebindPending = false;
            rebindFromStatuses();
        }

        if (clones.size > 0) {
            float delta = Time.delta;
            for (int i = clones.size - 1; i >= 0; i--) {
                Clone c = clones.get(i);
                Unit clone = c.unit;

                // 被打死（或已经不在场上）：不回复。
                if (clone == null || clone.dead() || !clone.isAdded() || clone.health <= 0f) {
                    removeClone(i, false);
                    continue;
                }
                // 本体没了：残影跟着消散，没有回血对象。
                // （owner == null 是读档时找不到本体的「无主残影」，让它自然到期。）
                if (c.owner != null && (c.owner.dead() || !c.owner.isAdded())) {
                    removeClone(i, false);
                    continue;
                }

                // 两个载体的 time 每帧都会被引擎扣 delta：id 小的单位几帧就把归属扣没了。
                // 每帧按内存里的权威值钉回去，存档那一刻写下去的就是精确值。
                if (c.owner != null && ownerMark != null && c.owner.id < ID_MAX) {
                    clone.setDuration(ownerMark, c.owner.id * ID_BIN + ID_BIAS);
                }

                c.life -= delta;
                if (c.life > 0f) {
                    if (mark != null) clone.setDuration(mark, c.life);
                    continue;
                }

                // 自然消失：按残影「剩余生命」的一部分回复本体。
                float heal = (c.owner == null) ? 0f : clone.health * c.refund;
                if (heal > 0f && c.owner.health < c.owner.maxHealth) {
                    c.owner.heal(heal);
                    Fx.heal.at(c.owner.x, c.owner.y);
                }
                removeClone(i, true);
            }
        }

        pruneTimer += Time.delta;
        if (pruneTimer >= PRUNE_INTERVAL) {
            pruneTimer = 0f;
            pruneStates();
        }
    }

    private static void removeClone(int index, boolean natural) {
        Clone c = clones.remove(index);
        Unit clone = c.unit;
        if (clone != null) {
            cloneSet.remove(clone);
            if (natural && clone.isAdded()) {
                Fx.spawnShockwave.at(clone.x, clone.y);
            }
            if (clone.isAdded()) {
                // 先把标记状态摘掉再移除：状态条目是池化的，留着会漏。
                clone.clearStatuses();
                // 静默移除：不触发 UnitDestroyEvent（回收 / 积焰 / 分裂都不该被残影骗）。
                clone.remove();
            }
        }
    }

    /** 清掉已经失效的携带者快照，避免 IntMap 无限增长（unit id 会回收）。 */
    private static void pruneStates() {
        IntSeq stale = new IntSeq();
        for (AfterimageEffect effect : instances) {
            stale.clear();
            for (IntMap.Entry<State> e : effect.states) {
                State st = e.value;
                if (st == null || st.owner == null || st.owner.dead() || !st.owner.isAdded()) {
                    stale.add(e.key);
                }
            }
            for (int i = 0; i < stale.size; i++) {
                effect.states.remove(stale.get(i));
            }
        }
    }
}
