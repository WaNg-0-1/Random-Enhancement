package raen.Enemy.StatusEffectCore;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Healthc;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;

/**
 * 血量转移：同时连接敌方（单位 + 建筑）与己方单位，
 * 敌方受到的「掉血 + 掉盾」按比例转化为对己方的治疗。
 */
public class LinkTransferEffect extends LinkEffect {

    /** 快照清理间隔（tick）。 */
    private static final float PRUNE_INTERVAL = 300f;

    /** 敌方损失转化为治疗的比例。 */
    public float transferRatio = 0.75f;

    private final ObjectMap<Teamc, Float> enemyHealth = new ObjectMap<>();
    private final ObjectMap<Teamc, Float> enemyShield = new ObjectMap<>();

    /** 上次清理快照的时间（Time.time）。 */
    private float lastPrune = -1f;

    public LinkTransferEffect(String name, float transferRatio) {
        super(name);
        this.transferRatio = transferRatio;
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (!alive(unit)) {
            clearLinks(unit);
            return;
        }

        // 定期清掉已经失效的目标，避免死亡对象的引用长期滞留
        if (lastPrune < 0f || Time.time - lastPrune > PRUNE_INTERVAL) {
            lastPrune = Time.time;
            prune(enemyHealth);
            prune(enemyShield);
        }

        if (shouldRefresh(unit)) {
            refreshEnemies(unit);
            refreshAllies(unit);
            snapshotEnemies(enemyLinks.get(unit.id));
        }

        Seq<Teamc> enemies = enemyLinks.get(unit.id);
        Seq<Unit> allies = allyLinks.get(unit.id);
        if (enemies == null || allies == null || enemies.size == 0 || allies.size == 0) return;

        // 统计所有连接敌方本帧的掉血 + 掉盾（建筑没有护盾，只算掉血）
        float loss = 0f;
        for (int i = 0; i < enemies.size; i++) {
            Teamc enemy = enemies.get(i);
            if (!alive(enemy)) continue;
            if (enemy instanceof Healthc h) {
                loss += drop(enemyHealth, enemy, h.health());
            }
            if (enemy instanceof Unit u) {
                loss += drop(enemyShield, enemy, u.shield);
            }
        }
        if (loss <= 0f) return;

        float each = loss * transferRatio / allies.size;
        for (int i = 0; i < allies.size; i++) {
            Unit ally = allies.get(i);
            if (alive(ally)) heal(ally, each);
        }
    }

    /** 重建连接后把敌方血量 / 盾值快照刷新到当前值。 */
    private void snapshotEnemies(Seq<Teamc> enemies) {
        if (enemies == null) return;
        for (int i = 0; i < enemies.size; i++) {
            Teamc enemy = enemies.get(i);
            if (!alive(enemy)) continue;
            if (enemy instanceof Healthc h) enemyHealth.put(enemy, h.health());
            if (enemy instanceof Unit u) enemyShield.put(enemy, u.shield);
        }
    }

    /** 移除已失效的目标（先收集再删，避免遍历时改动集合）。 */
    private static void prune(ObjectMap<Teamc, Float> map) {
        Seq<Teamc> stale = null;
        for (Teamc key : map.keys()) {
            if (!alive(key)) {
                if (stale == null) stale = new Seq<>();
                stale.add(key);
            }
        }
        if (stale == null) return;
        for (int i = 0; i < stale.size; i++) {
            map.remove(stale.get(i));
        }
    }

    /** 优先回血；血满则回盾，盾值上限为最大生命值。 */
    private static void heal(Unit ally, float amount) {
        float missing = ally.maxHealth - ally.health;
        if (missing > 0f) {
            float toHealth = Math.min(missing, amount);
            ally.heal(toHealth);
            amount -= toHealth;
        }
        if (amount <= 0f) return;

        float room = ally.maxHealth - ally.shield;
        if (room > 0f) {
            ally.shield += Math.min(room, amount);
        }
    }

    /** 记录本帧下降量并更新快照。 */
    private static float drop(ObjectMap<Teamc, Float> map, Teamc key, float current) {
        Float cached = map.get(key);
        float prev = (cached == null) ? current : cached;
        map.put(key, current);
        return Math.max(prev - current, 0f);
    }

    @Override
    public void onRemoved(Unit unit) {
        clearLinks(unit);
    }
}
