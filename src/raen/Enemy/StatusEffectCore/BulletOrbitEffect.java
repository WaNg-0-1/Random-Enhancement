package raen.Enemy.StatusEffectCore;

import arc.math.Mathf;
import arc.struct.IntMap;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.entities.bullet.LaserBulletType;
import mindustry.entities.units.StatusEntry;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 弹幕环绕：周期性抢走范围内的敌方子弹环绕自身，结束时甩向锁定目标。
 */
public class BulletOrbitEffect extends StatusEffect {

    /** 抓取半径 = hitSize × 这个系数。 */
    public float captureMul = 2.5f;
    /** 环绕半径 = hitSize × 这个系数。 */
    public float orbitMul = 2f;
    /** 触发间隔（秒）。 */
    public float cycleSeconds = 10f;
    /** 状态持续（秒）。 */
    public float activeSeconds = 2.5f;
    /** 被抓子弹的伤害倍率。 */
    public float damageScale = 0.5f;
    /** 每 tick 从 bullet.time 里扣掉的比例（4/5 → 净老化 1/5）。 */
    public float ageCut = 4f / 5f;
    /** 环绕角速度（弧度 / tick）。 */
    public float orbitOmega = 0.1f;
    /** 径向修正系数。 */
    public float radialK = 0.25f;
    /** 甩出时的锁定半径（格）。 */
    public float releaseRangeTiles = 30f;
    /** 甩出时的飞行速度（单位 / tick）。 */
    public float releaseSpeed = 5f;

    private final IntMap<Float> timers = new IntMap<>();
    private final IntMap<Boolean> wasActive = new IntMap<>();
    /** 子弹 id → 抢到它的单位 id。只有在这张表里的子弹才会被环绕。 */
    private final IntMap<Integer> captured = new IntMap<>();

    public BulletOrbitEffect(String name) {
        super(name);
        this.damage = 0f;
        this.transitionDamage = 0f;
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            if (unit != null) {
                timers.remove(unit.id);
                wasActive.remove(unit.id);
            }
            return;
        }

        float timer = timers.get(unit.id, 0f) + Time.delta / 60f;
        if (timer >= cycleSeconds) timer %= cycleSeconds;
        timers.put(unit.id, timer);

        boolean active = timer < activeSeconds;

        if (!active && wasActive.get(unit.id, false)) {
            release(unit);
        }
        wasActive.put(unit.id, active);

        if (!active) return;

        float capture = unit.hitSize * captureMul;
        float orbit = unit.hitSize * orbitMul;
        if (capture <= 0f || orbit <= 0f) return;

        Groups.bullet.intersect(unit.x - capture, unit.y - capture, capture * 2f, capture * 2f, bullet -> {
            if (bullet == null || bullet.type == null) return;
            if (bullet.type instanceof LaserBulletType) return;

            if (captured.get(bullet.id, -1) != unit.id) {
                if (bullet.team == unit.team) return;
                captured.put(bullet.id, unit.id);
                bullet.team = unit.team;
                bullet.owner = unit;
                bullet.damage *= damageScale;
            }

            bullet.time = Math.max(bullet.time - Time.delta * ageCut, 0f);

            float dx = bullet.x - unit.x;
            float dy = bullet.y - unit.y;
            float dist = Mathf.dst(dx, dy);
            if (dist < 0.001f) return;

            float nx = dx / dist, ny = dy / dist;
            float tangSpeed = orbitOmega * dist;
            float radialSpeed = (orbit - dist) * radialK;
            float maxRadial = Math.max(dist * 0.5f, 4f);
            radialSpeed = Mathf.clamp(radialSpeed, -maxRadial, maxRadial);

            bullet.vel.set(-ny * tangSpeed + nx * radialSpeed, nx * tangSpeed + ny * radialSpeed);
        });
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        if (unit != null && wasActive.get(unit.id, false)) {
            release(unit);
        }
        if (unit != null) {
            timers.remove(unit.id);
            wasActive.remove(unit.id);
        }
    }

    private void release(Unit unit) {
        if (unit == null || !unit.isValid()) return;

        float range = releaseRangeTiles * Vars.tilesize;
        float capture = unit.hitSize * captureMul;

        Teamc target = Units.closestTarget(unit.team, unit.x, unit.y, range,
                e -> e != null && e.team != Team.derelict && !e.dead(),
                t -> t != null && t.team() != Team.derelict);

        final float aimX, aimY;
        if (target != null) {
            aimX = target.x();
            aimY = target.y();
        } else {
            aimX = unit.x + Mathf.cosDeg(unit.rotation) * range;
            aimY = unit.y + Mathf.sinDeg(unit.rotation) * range;
        }

        Groups.bullet.intersect(unit.x - capture, unit.y - capture, capture * 2f, capture * 2f, bullet -> {
            if (bullet == null || captured.get(bullet.id, -1) != unit.id) return;
            float ang = Mathf.angle(aimX - bullet.x, aimY - bullet.y);
            bullet.vel.trns(ang, releaseSpeed);
            captured.remove(bullet.id);
        });
    }
}
