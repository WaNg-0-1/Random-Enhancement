package raen.Enemy.StatusEffectCore;

import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.entities.abilities.Ability;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.bullet.LaserBulletType;
import mindustry.gen.Bullet;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.Weapon;

public class WingmanAI extends mindustry.entities.units.AIController {

    private static final int timerShoot = 3;

    public Unit parent;
    public float attackRange = 120f;
    public float orbitRadius = 80f;
    public float orbitSpeed = 2f;
    public float bulletDamage = 10f;
    public float shootInterval = 90f;
    public float bulletRange = 120f;

    private BulletType bulletTypeStored;
    private boolean hasWeapon = true;

    private float orphanTimer = 0f;
    private static final float ORPHAN_TIMEOUT = 6f * 3f;
    private static final float MAX_PARENT_DIST = 1200f;

    private float phaseOffset;
    private final Vec2 vec = new Vec2();
    private boolean abilitiesInherited = false;

    public WingmanAI(Unit parent) {
        this.parent = parent;
        this.phaseOffset = Mathf.random(360f);
    }

    private static float calcBulletRange(BulletType b) {
        if (b instanceof LaserBulletType) {
            return ((LaserBulletType) b).length;
        }
        if (b.speed <= 0.01f) {
            if (b.range > 0) return b.range;
            return Math.max(b.lifetime * 4f, 80f);
        }
        return b.speed * b.lifetime;
    }
    private void inheritAbilities() {
        if (abilitiesInherited || unit == null || parent == null) return;
        if (parent.type.abilities == null || parent.type.abilities.size == 0) {
            abilitiesInherited = true;
            return;
        }
        Ability[] existing = unit.abilities;
        int existLen = (existing != null) ? existing.length : 0;
        int parentLen = parent.type.abilities.size;
        Ability[] merged = new Ability[existLen + parentLen];
        if (existing != null) {
            System.arraycopy(existing, 0, merged, 0, existLen);
        }
        for (int i = 0; i < parentLen; i++) {
            merged[existLen + i] = parent.type.abilities.get(i).copy();
        }
        unit.abilities = merged;
        abilitiesInherited = true;
    }
    public void configureFromParent() {
        if (parent == null) return;
        inheritAbilities();
        Weapon farthestWeapon = null;
        float farthestRange = 0f;
        if (parent.type.hasWeapons()) {
            for (Weapon w : parent.type.weapons) {
                if (w.bullet == null) continue;
                float range = calcBulletRange(w.bullet);
                if (range > farthestRange) {
                    farthestRange = range;
                    farthestWeapon = w;
                }
            }
        }
        if (farthestWeapon != null && farthestWeapon.bullet != null) {
            this.bulletTypeStored = farthestWeapon.bullet;
            this.bulletDamage = farthestWeapon.bullet.damage * 0.1f;
            this.bulletRange = Math.max(farthestRange, 30f);
            this.attackRange  = this.bulletRange * 1.5f;
            this.orbitRadius  = this.bulletRange;
            this.shootInterval =farthestWeapon.reload;
        } else {
            this.bulletTypeStored = null;
            this.bulletDamage = 5f;
            this.bulletRange = 120f;
            this.attackRange = 80f;
            this.shootInterval = 90f;
            this.orbitRadius = 80f;
            this.hasWeapon = false;
        }
    }

    @Override
    public void updateMovement() {
        if (unit == null || unit.dead()) return;
        if (!unit.isAdded() || !Vars.state.isPlaying()) {
            unit.kill();
            return;
        }
        if (!abilitiesInherited) inheritAbilities();

        if (parent == null || parent.dead() || !parent.isAdded()) {
            orphanTimer += Time.delta;
            if (orphanTimer > ORPHAN_TIMEOUT) {
                unit.kill();
            }
            return;
        }
        float maxDist = Math.max(orbitRadius * 2.5f, MAX_PARENT_DIST);
        if (unit.dst(parent) > maxDist) {
            orphanTimer += Time.delta * 3f;
            if (orphanTimer > ORPHAN_TIMEOUT * 0.5f) {
                unit.kill();
                return;
            }
            returnToParent();
            return;
        }
        orphanTimer = 0f;
        Teamc enemy = Units.closestTarget(
                unit.team,
                parent.x, parent.y,
                attackRange,
                u -> u != parent && u != unit,
                b -> b.team != unit.team
        );
        if (enemy != null) {
            attackTarget(enemy);
        } else {
            orbitParent();
        }
    }
    private void attackTarget(Teamc enemy) {
        float ex = enemy.getX(), ey = enemy.getY();
        float dist = unit.dst(ex, ey);
        float combatDist = Math.max(bulletRange * 0.85f, 20f);
        float tooClose   = Math.max(bulletRange * 0.4f,  10f);
        float tooFar     = Math.max(bulletRange, 30f);
        if (dist < tooClose) {
            float a = Angles.angle(ex, ey, unit.x, unit.y);
            unit.moveAt(vec.set(
                    ex + Mathf.cosDeg(a) * combatDist,
                    ey + Mathf.sinDeg(a) * combatDist
            ).sub(unit).limit(unit.type.speed * 1.2f));
        } else if (dist > tooFar) {
            unit.moveAt(vec.set(ex, ey).sub(unit).limit(unit.type.speed));
        } else {
            float angle = phaseOffset + orbitSpeed * 2f * Time.time;
            float tx = ex + Mathf.cosDeg(angle) * combatDist;
            float ty = ey + Mathf.sinDeg(angle) * combatDist;
            unit.moveAt(vec.set(tx, ty).sub(unit).limit(unit.type.speed * 0.7f));
        }
        unit.lookAt(ex, ey);
        if (hasWeapon && bulletTypeStored != null && dist <= tooFar) {
            if (timer.get(timerShoot, shootInterval)) {
                float angle = unit.angleTo(ex, ey);
                Bullet b = bulletTypeStored.create(unit, unit.team, unit.x, unit.y, angle, bulletDamage);
                if (b != null && b.vel.len() > 0.001f) {
                    b.vel.setLength(b.vel.len() * 0.75f);
                    if (b.vel.len() > 5f) b.vel.setLength(5);
                }
            }
        }
    }
    private void orbitParent() {
        if (parent == null) return;
        float dist = unit.dst(parent);
        float angle = phaseOffset + orbitSpeed * Time.time;
        float tx = parent.x + Mathf.cosDeg(angle) * orbitRadius * 2f;
        float ty = parent.y + Mathf.sinDeg(angle) * orbitRadius * 2f;
        if (dist > orbitRadius * 3f) {
            unit.moveAt(vec.set(tx, ty).sub(unit).limit(unit.type.speed * 2f));
        } else {
            unit.moveAt(vec.set(tx, ty).sub(unit).limit(unit.type.speed));
        }
        unit.lookAt(tx, ty);
        if (!unit.isShooting && unit.maxHealth > unit.health){
            unit.health += unit.maxHealth * 0.05f / 60f;
        }
    }
    private void returnToParent() {
        if (parent == null) return;
        unit.moveAt(vec.set(parent.x, parent.y).sub(unit).limit(unit.type.speed * 1.5f));
        unit.lookAt(parent.x, parent.y);
    }
}