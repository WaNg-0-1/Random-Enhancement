package raen.Enemy.StatusEffectCore;

import arc.Events;
import arc.struct.IntMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.entities.units.StatusEntry;
import mindustry.game.EventType;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 共享回复：携带者记住范围内的己方单位（每 5 秒刷新），组内的伤害与治疗会在成员间扩散。
 */
public class SharedRecoveryEffect extends StatusEffect {

    /** 记忆刷新间隔（tick）。 */
    private static final float REFRESH_INTERVAL = 300f;
    /** 护盾高于自身最大生命这个比例时，本效果的回复无效。 */
    private static final float SHIELD_BLOCK_RATIO = 0.5f;

    /** 记忆范围（世界单位）。 */
    public float range = 104f;
    /** 最多记住的己方单位数量（不含携带者）。 */
    public int maxMembers = 3;
    /** 受伤时其他成员回复的比例。 */
    public float damageRatio = 0.02f;
    /** 被治疗时其他成员回复的比例。 */
    public float healRatio = 0.05f;

    /** 每个携带者的组，下标 0 恒为携带者自己。 */
    private final IntMap<Seq<Unit>> groups = new IntMap<>();
    private final IntMap<Float> refreshTimers = new IntMap<>();

    // ===== 全局快照：每个单位每帧只结算一次，供所有携带者共享 =====
    private static final IntMap<Float> prevHealth = new IntMap<>();
    private static final IntMap<Float> prevShield = new IntMap<>();
    private static final IntMap<Float> damagePool = new IntMap<>();
    private static final IntMap<Float> healPool = new IntMap<>();
    private static final IntMap<Integer> lastFrame = new IntMap<>();
    private static int frameId;

    public SharedRecoveryEffect(String name, float range, int maxMembers, float damageRatio, float healRatio) {
        super(name);
        this.range = range;
        this.maxMembers = maxMembers;
        this.damageRatio = damageRatio;
        this.healRatio = healRatio;
        this.damage = 0f;
        this.transitionDamage = 0f;
    }

    /** 由主类在 init() 里挂上，只负责推进帧号。 */
    public static void registerTick() {
        Events.run(EventType.Trigger.update, () -> frameId++);
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            forget(unit);
            return;
        }

        // 每 5 秒重扫一次范围内的己方单位
        float timer = refreshTimers.get(unit.id, 0f) - Time.delta;
        if (timer <= 0f) {
            timer = REFRESH_INTERVAL;
            refresh(unit);
        }
        refreshTimers.put(unit.id, timer);

        Seq<Unit> group = groups.get(unit.id);
        if (group == null || group.size < 2) return;

        // 先把本帧所有成员的伤害 / 治疗量结算出来（全局每单位只算一次）
        for (int i = 0; i < group.size; i++) {
            Unit member = group.get(i);
            if (alive(member)) snapshot(member);
        }

        // 再按「来源」逐个分发
        for (int i = 0; i < group.size; i++) {
            Unit source = group.get(i);
            if (!alive(source)) continue;

            float damage = damagePool.get(source.id, 0f);
            if (damage > 0f) spread(group, source, damage * damageRatio);

            float heal = healPool.get(source.id, 0f);
            if (heal > 0f) spread(group, source, heal * healRatio);
        }
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        forget(unit);
    }

    // ==================== 记忆维护 ====================

    private Seq<Unit> groupOf(Unit carrier) {
        Seq<Unit> group = groups.get(carrier.id);
        if (group == null) {
            group = new Seq<>();
            groups.put(carrier.id, group);
        }
        return group;
    }

    private void refresh(Unit carrier) {
        Seq<Unit> group = groupOf(carrier);

        // 顺手把已经失效的成员从全局表里清掉
        for (int i = 0; i < group.size; i++) {
            Unit old = group.get(i);
            if (old != null && !alive(old)) purge(old);
        }

        // 范围内所有己方单位，按距离从近到远取前 maxMembers 个
        Seq<Unit> found = new Seq<>();
        Units.nearby(carrier.team, carrier.x, carrier.y, range, other -> {
            if (other != carrier && alive(other)) found.add(other);
        });
        found.sort((a, b) -> Float.compare(a.dst2(carrier.x, carrier.y), b.dst2(carrier.x, carrier.y)));

        group.clear();
        group.add(carrier);   // 下标 0 永远是携带者
        for (int i = 0; i < maxMembers && i < found.size; i++) {
            group.add(found.get(i));
        }
    }

    private void forget(Unit unit) {
        if (unit == null) return;
        groups.remove(unit.id);
        refreshTimers.remove(unit.id);
        purge(unit);
    }

    /** 清掉某个单位在所有全局表里的记录。 */
    private static void purge(Unit unit) {
        prevHealth.remove(unit.id);
        prevShield.remove(unit.id);
        damagePool.remove(unit.id);
        healPool.remove(unit.id);
        lastFrame.remove(unit.id);
    }

    // ==================== 结算 ====================

    /** 算出某个单位本帧的掉血/掉盾与外部治疗量；同一帧内只算一次。 */
    private static void snapshot(Unit unit) {
        if (lastFrame.get(unit.id, -1) == frameId) return;
        lastFrame.put(unit.id, frameId);

        float prevH = prevHealth.get(unit.id, unit.health);
        float prevS = prevShield.get(unit.id, unit.shield);

        float lostHealth = prevH - unit.health;   // > 0 表示掉血
        float lostShield = prevS - unit.shield;   // > 0 表示掉盾
        damagePool.put(unit.id, Math.max(lostHealth, 0f) + Math.max(lostShield, 0f));
        // 治疗只认血量增加，盾值增加不算
        healPool.put(unit.id, Math.max(-lostHealth, 0f));

        prevHealth.put(unit.id, unit.health);
        prevShield.put(unit.id, unit.shield);
    }

    /** 把 amount 平均发给组内除来源之外的成员（不设人数上限）。 */
    private static void spread(Seq<Unit> group, Unit source, float amount) {
        if (amount <= 0f) return;

        int recipients = 0;
        for (int i = 0; i < group.size; i++) {
            Unit target = group.get(i);
            if (target == source || !alive(target)) continue;
            // 自身护盾已经高于自身最大生命的 50%：本效果对它的回复无效
            if (target.shield >= target.maxHealth * SHIELD_BLOCK_RATIO) continue;
            recipients++;
        }
        if (recipients <= 0) return;

        float each = amount / recipients;
        for (int i = 0; i < group.size; i++) {
            Unit target = group.get(i);
            if (target == source || !alive(target)) continue;
            if (target.shield >= target.maxHealth * SHIELD_BLOCK_RATIO) continue;
            applyHeal(target, each);
        }
    }

    /** 优先回血，血满后补盾；同时把增量写回快照，避免被当成外部治疗。 */
    private static void applyHeal(Unit target, float amount) {
        float before = target.health;
        float healthAdded = 0f;
        float missing = target.maxHealth - before;
        if (missing > 0f) {
            healthAdded = Math.min(missing, amount);
            target.heal(healthAdded);
            amount -= healthAdded;
        }
        if (amount > 0f) {
            float room = Math.max(target.maxHealth - target.shield, 0f);
            target.shield += Math.min(room, amount);
        }

        if (healthAdded > 0f) {
            // 兜底必须用「治疗前」的值：before + healthAdded 正好等于治疗后的血量。
            // 若用 target.health（已被 heal 改过）会多算一次，下一帧会被误判成外部治疗。
            prevHealth.put(target.id, prevHealth.get(target.id, before) + healthAdded);
        }
        prevShield.put(target.id, target.shield);
    }

    private static boolean alive(Unit unit) {
        return unit != null && unit.isValid() && !unit.dead() && unit.health > 0f;
    }
}
