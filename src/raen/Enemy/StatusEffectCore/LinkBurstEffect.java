package raen.Enemy.StatusEffectCore;

import arc.struct.IntMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Healthc;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;

/**
 * 短时爆发：周期性地临时连接敌方目标，连接期间把自身「掉血 + 掉盾」按倍数打过去。
 */
public class LinkBurstEffect extends LinkEffect {

    /** 一个完整周期（秒）。 */
    public float cycleSeconds = 18f;
    /** 每次连接的持续时长（秒）。 */
    public float activeSeconds = 3f;
    /** 连接期间的伤害倍率。 */
    public float multiplier = 0.75f;

    private final IntMap<Float> cycleTimers = new IntMap<>();
    private final IntMap<Float> lastHealth = new IntMap<>();
    private final IntMap<Float> lastShield = new IntMap<>();

    public LinkBurstEffect(String name, float cycleSeconds, float activeSeconds, float multiplier) {
        super(name);
        this.cycleSeconds = cycleSeconds;
        this.activeSeconds = activeSeconds;
        this.multiplier = multiplier;
    }

    @Override
    public void applied(Unit unit, float time, boolean extend) {
        super.applied(unit, time, extend);
        if (!extend && unit != null) {
            lastHealth.put(unit.id, unit.health);
            lastShield.put(unit.id, unit.shield);
        }
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (!alive(unit)) {
            clearLinks(unit);
            if (unit != null) {
                cycleTimers.remove(unit.id);
                lastHealth.remove(unit.id);
                lastShield.remove(unit.id);
            }
            return;
        }

        // 掉血 / 掉盾：每帧都更新快照，与是否处于连接期无关
        float loss = drop(lastHealth, unit.id, unit.health) + drop(lastShield, unit.id, unit.shield);

        float timer = cycleTimers.get(unit.id, 0f) + Time.delta / 60f;
        if (timer >= cycleSeconds) timer %= cycleSeconds;
        cycleTimers.put(unit.id, timer);

        // 不在连接期：断开并跳过
        if (timer >= activeSeconds) {
            clearLinks(unit);
            return;
        }

        if (shouldRefresh(unit)) refreshEnemies(unit);

        Seq<Teamc> links = enemyLinks.get(unit.id);
        if (links == null || links.size == 0 || loss <= 0f) return;

        float each = loss * multiplier / links.size;
        for (int i = 0; i < links.size; i++) {
            Teamc target = links.get(i);
            if (alive(target) && target instanceof Healthc h) h.damage(each);
        }
    }

    /** 记录本帧下降量并更新快照。 */
    private static float drop(IntMap<Float> map, int id, float current) {
        float prev = map.get(id, current);
        map.put(id, current);
        return Math.max(prev - current, 0f);
    }

    @Override
    public void onRemoved(Unit unit) {
        clearLinks(unit);
        if (unit != null) {
            cycleTimers.remove(unit.id);
            lastHealth.remove(unit.id);
            lastShield.remove(unit.id);
        }
    }
}
