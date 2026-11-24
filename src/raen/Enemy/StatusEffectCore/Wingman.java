package raen.Enemy.StatusEffectCore;

import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Unit;
import mindustry.graphics.Pal;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.type.unit.MissileUnitType;
import raen.Enemy.RaenStatusDriver;

public class Wingman extends StatusEffect {
    private static final ObjectMap<Unit, Seq<Unit>> droneMap = new ObjectMap<>();
    private static final ObjectMap<Unit, Integer> targetCountMap = new ObjectMap<>();
    private static final ObjectMap<Unit, Float> spawnTimerMap = new ObjectMap<>();
    /** 所有僚机效果实例：用来判断某个单位身上挂的最高是哪一档。 */
    private static final Seq<Wingman> instances = new Seq<>();
    /** 每补充一架僚机的间隔：10 秒（60 tick/秒）。改这里即可调整节奏。 */
    private static final float SPAWN_INTERVAL = 60f * 10f;
    public int maxDronesPerUnit;
    public Wingman(String name, int maxDrones) {
        super(name);
        this.damage = 0f;
        this.maxDronesPerUnit = maxDrones;
        this.transitionDamage = 0f;
        this.permanent = true;
        this.color = Pal.sap;
        instances.add(this);
    }
    public static void hardResetAll() {
        for (Seq<Unit> drones : droneMap.values()) {
            for (Unit d : drones) {
                if (d != null && d.isAdded()) d.kill();
                WingmanUnit.unregister(d);
            }
        }
        droneMap.clear();
        targetCountMap.clear();
        spawnTimerMap.clear();
    }
    public static void clearDronesFor(Unit parent) {
        Seq<Unit> drones = droneMap.get(parent);
        if (drones != null) {
            for (Unit d : drones) {
                if (d != null && d.isAdded()) d.kill();
                WingmanUnit.unregister(d);
            }
        }
        droneMap.remove(parent);
        targetCountMap.remove(parent);
        spawnTimerMap.remove(parent);
    }
    public static boolean isDrone(Unit unit) {
        return unit != null && unit.controller() instanceof WingmanAI;
    }
    /** 本档是否是该单位身上挂的最高档僚机效果。 */
    private boolean isTopTier(Unit unit) {
        for (Wingman other : instances) {
            if (other == this) continue;
            if (other.maxDronesPerUnit <= maxDronesPerUnit) continue;
            // 用 getDuration 判定「当前真的挂着」（直接查 statuses 列表，
            // 不受 gen UnitEntity / 组件 StatusComp 两条路径差异影响）。
            if (RaenStatusDriver.getDuration(unit, other) > 0f) return false;
        }
        return true;
    }
    @Override
    public void update(Unit unit, StatusEntry entry) {
        if (unit == null || unit.dead()) return;
        if (isDrone(unit)) return;
        // 三档共用同一份无人机表：万一同一单位同时挂了两档（例如互斥判定被绕过），
        // 两档会互相「补足 / 砍掉」僚机。这里让低档直接让位给高档，
        // 僚机数量始终由最高档说了算，不会出现两档打架。
        if (!isTopTier(unit)) return;
        if (WingmanCleanup.pendingCleanup) {
            WingmanCleanup.executeCleanup();
        }
        if (!Vars.state.isPlaying()) return;
        if (maxDronesPerUnit <= 0) {
            Seq<Unit> existing = droneMap.get(unit);
            if (existing != null && existing.size > 0) {
                clearDronesFor(unit);
            }
            return;
        }
        Seq<Unit> drones = droneMap.get(unit);
        if (drones == null) {
            drones = new Seq<>();
            droneMap.put(unit, drones);
        }
        targetCountMap.put(unit, maxDronesPerUnit);
        for (int i = drones.size - 1; i >= 0; i--) {
            Unit d = drones.get(i);
            if (d == null || d.dead() || !d.isAdded()) {
                WingmanUnit.unregister(d);
                drones.remove(i);
                continue;
            }
            if (d.controller() instanceof WingmanAI ai) {
                if (ai.parent == null || ai.parent.dead() || !ai.parent.isAdded()) {
                    d.kill();
                    WingmanUnit.unregister(d);
                    drones.remove(i);
                }
            }
        }
        while (drones.size > maxDronesPerUnit) {
            Unit d = drones.pop();
            if (d != null && d.isAdded()) d.kill();
            WingmanUnit.unregister(d);
        }
        // 按间隔逐架补充，而不是一口气补满：满员时计时归零，
        // 僚机被打爆后必须等满一个间隔才会补下一架，避免「打爆立即重生」
        // 导致伤害全部由僚机承担。
        Float cachedTimer = spawnTimerMap.get(unit);
        float spawnTimer = (cachedTimer == null) ? SPAWN_INTERVAL : cachedTimer;
        if (drones.size < maxDronesPerUnit) {
            spawnTimer += Time.delta;
            if (spawnTimer >= SPAWN_INTERVAL) {
                spawnTimer = 0f;
                Unit drone = spawnDrone(unit);
                if (drone != null) {
                    drones.add(drone);
                }
            }
        } else {
            spawnTimer = 0f;
        }
        spawnTimerMap.put(unit, spawnTimer);
    }
    @Override
    public void onRemoved(Unit unit) {
        clearDronesFor(unit);
    }
    private Unit spawnDrone(Unit parent) {
        if (WingmanUnit.wingmanA == null) return null;
        if (parent.type instanceof MissileUnitType) return null;
        UnitType droneType = WingmanUnit.wingmanA;
        Unit drone = droneType.create(parent.team);
        if (drone == null) return null;
        float angle = Mathf.random(360f);
        float dist = Mathf.random(20f, 40f);
        drone.set(
                parent.x + Mathf.cosDeg(angle) * dist,
                parent.y + Mathf.sinDeg(angle) * dist
        );
        drone.rotation = Mathf.random(360f);
        float inheritedHealth = Mathf.clamp(parent.type.health * 0.1f, 100f, 1500f);
        drone.maxHealth = inheritedHealth;
        drone.health    = inheritedHealth;
        WingmanAI ai = new WingmanAI(parent);
        ai.configureFromParent();
        drone.controller(ai);
        drone.add();
        WingmanUnit.activeDrones.add(drone);
        return drone;
    }
}
