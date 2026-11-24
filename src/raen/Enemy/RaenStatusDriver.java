package raen.Enemy;

import arc.Events;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 影子状态驱动：给「把本模组全部状态都列入免疫」的单位也能正常吃到强化。
 *
 * <p>原版 {@code StatusComp.applyStatus()} 在入口就 {@code if(isImmune(fx)) return;}，
 * 所以 {@code unit.apply()} 对这类单位完全无效。这里不碰原版状态列表，而是自己按
 * {@code unit.id} 保存 {@link StatusEntry}，在 {@link EventType.Trigger#afterGameUpdate}
 * （单位状态更新之后，倍率刚被重置过）逐帧推进：扣时间 → 调 {@link StatusEffect#update} →
 * 到期调 {@link StatusEffect#onRemoved}。
 *
 * <p>只有 {@link Unit#isImmune(StatusEffect)} 为 true 的效果走影子表；非免疫单位照常走原版，
 * 所以存档 / 同步 / 原版状态交互都保持原样。
 */
public final class RaenStatusDriver {

    private static final IntMap<Seq<StatusEntry>> shadow = new IntMap<>();
    private static boolean initialized = false;

    private RaenStatusDriver() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        Events.run(EventType.Trigger.afterGameUpdate, RaenStatusDriver::update);
        Events.on(EventType.WorldLoadEvent.class, e -> clearAll());
        Events.on(EventType.ResetEvent.class, e -> clearAll());
    }

    /** 统一施加入口：被免疫的效果进影子表，其余照常走原版。 */
    public static void apply(Unit unit, StatusEffect effect, float duration) {
        if (unit == null || effect == null || !unit.isValid()) return;
        if (duration <= 0f) {
            unapply(unit, effect);
            return;
        }

        Seq<StatusEntry> list = shadow.get(unit.id);
        StatusEntry existing = find(list, effect);

        if (!unit.isImmune(effect)) {
            // 原本不免疫：清掉可能残留的影子条目，避免同一效果跑两遍。
            if (existing != null) {
                list.remove(existing, true);
                effect.onRemoved(unit);
                if (list.isEmpty()) shadow.remove(unit.id);
            }
            unit.apply(effect, duration);
            return;
        }

        if (existing != null) {
            existing.time = Math.max(existing.time, duration);
            effect.applied(unit, existing.time, true);
            markApplied(unit, effect);
            return;
        }
        if (list == null) {
            list = new Seq<>();
            shadow.put(unit.id, list);
        }
        StatusEntry entry = new StatusEntry().set(effect, duration);
        list.add(entry);
        // 与原版 applyStatus 新建条目一致：extend = false。
        effect.applied(unit, duration, false);
        markApplied(unit, effect);
    }

    /** 统一读取时长：影子表优先，否则读原版。 */
    public static float getDuration(Unit unit, StatusEffect effect) {
        if (unit == null || effect == null) return 0f;
        StatusEntry entry = find(shadow.get(unit.id), effect);
        return entry != null ? entry.time : unit.getDuration(effect);
    }

    /** 统一移除：影子条目与原版条目都清掉。 */
    public static void unapply(Unit unit, StatusEffect effect) {
        if (unit == null || effect == null) return;
        Seq<StatusEntry> list = shadow.get(unit.id);
        if (list != null) {
            StatusEntry entry = find(list, effect);
            if (entry != null) {
                list.remove(entry, true);
                effect.onRemoved(unit);
                if (list.isEmpty()) shadow.remove(unit.id);
            }
        }
        unit.unapply(effect);
    }

    /** 单位消失时清掉它的影子条目（并结算 onRemoved）。 */
    public static void clear(Unit unit) {
        if (unit == null) return;
        Seq<StatusEntry> list = shadow.remove(unit.id);
        if (list == null) return;
        for (StatusEntry entry : list) {
            if (entry != null && entry.effect != null) entry.effect.onRemoved(unit);
        }
    }

    /** 换图 / 重置：影子表按单位 id 存，实体全变了，直接丢掉。 */
    public static void clearAll() {
        shadow.clear();
    }

    private static StatusEntry find(Seq<StatusEntry> list, StatusEffect effect) {
        if (list == null) return null;
        for (int i = 0; i < list.size; i++) {
            StatusEntry entry = list.get(i);
            if (entry != null && entry.effect == effect) return entry;
        }
        return null;
    }

    private static void markApplied(Unit unit, StatusEffect effect) {
        unit.statusBits().set(effect.id);
    }

    private static void update() {
        if (Vars.net != null && Vars.net.active() && Vars.net.client()) return;
        if (shadow.isEmpty()) return;

        // 用 key 快照遍历：清理死单位时会改 map。
        IntSeq ids = shadow.keys().toSeq();
        for (int k = 0; k < ids.size; k++) {
            int id = ids.get(k);
            Seq<StatusEntry> list = shadow.get(id);
            if (list == null) continue;

            Unit unit = Groups.unit.getByID(id);
            if (unit == null) {
                // 实体已经没了，没有 Unit 可传，效果自己的 IntMap 在 clearAll() 里统一清。
                shadow.remove(id);
                continue;
            }
            // dead 的单位 isValid() 也是 false，所以必须先判 dead 再判 valid：
            // 死亡是最常见的「状态该结束了」的时机，这里必须补 onRemoved
            // （僚机 / 残影 / 回收队列 / 幽影计时器…都靠它释放 per-unit 状态，id 复用时才不会串）。
            if (unit.dead() || !unit.isValid()) {
                clear(unit);
                continue;
            }

            for (int i = list.size - 1; i >= 0; i--) {
                StatusEntry entry = list.get(i);
                if (entry == null || entry.effect == null) {
                    list.remove(i);
                    continue;
                }
                entry.time = Math.max(0f, entry.time - Time.delta);
                if (entry.time <= 0f && !entry.effect.permanent) {
                    entry.effect.onRemoved(unit);
                    list.remove(i);
                    continue;
                }
                markApplied(unit, entry.effect);
                // 原版 StatusComp.update() 在单位更新时把倍率重置成 1 并只按 statuses 里的条目乘；
                // 影子条目不在那张表里，所以必须在这里补乘，否则「纯数值类」（速度 / 生命 / 伤害 /
                // 攻速 / 玻璃大炮 / 轻装 / 重装 / 刮痧 / 势大力沉…）对被免疫单位完全无效。
                applyMultipliers(unit, entry);
                entry.effect.update(unit, entry);
            }
            if (list.isEmpty()) shadow.remove(id);
        }
    }

    /** 把一条影子状态的倍率乘进单位，镜像原版 {@code StatusComp.update()} 的乘算分支。 */
    private static void applyMultipliers(Unit unit, StatusEntry entry) {
        StatusEffect effect = entry.effect;
        if (effect.dynamic) {
            // 动态条目：倍率挂在 entry 上（unit.apply(effect) 建出来的条目默认全是 1）。
            unit.speedMultiplier *= entry.speedMultiplier;
            unit.healthMultiplier *= entry.healthMultiplier;
            unit.damageMultiplier *= entry.damageMultiplier;
            unit.reloadMultiplier *= entry.reloadMultiplier;
            unit.buildSpeedMultiplier *= entry.buildSpeedMultiplier;
            unit.dragMultiplier *= entry.dragMultiplier;
            if (entry.armorOverride >= 0f) unit.armorOverride = entry.armorOverride;
        } else {
            unit.speedMultiplier *= effect.speedMultiplier;
            unit.healthMultiplier *= effect.healthMultiplier;
            unit.damageMultiplier *= effect.damageMultiplier;
            unit.reloadMultiplier *= effect.reloadMultiplier;
            unit.buildSpeedMultiplier *= effect.buildSpeedMultiplier;
            unit.dragMultiplier *= effect.dragMultiplier;
        }
        unit.disarmed |= effect.disarm;
    }
}
