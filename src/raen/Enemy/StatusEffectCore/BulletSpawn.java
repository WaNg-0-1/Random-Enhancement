package raen.Enemy.StatusEffectCore;

import arc.math.Mathf;
import arc.struct.IntMap;
import arc.util.Time;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Unit;
import mindustry.graphics.Pal;
import mindustry.type.StatusEffect;

public class BulletSpawn extends StatusEffect {

    public BulletType bulletType;
    public float spawnInterval = 299f;
    public int bulletCount = 3;
    public float spawnAngle = 0f;
    public boolean randomAngle = false;
    private final IntMap<Float> counters = new IntMap<>();

    public BulletSpawn(String name) {
        super(name);
        this.damage = 0f;
        this.transitionDamage = 0f;
        this.permanent = true;
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        if (unit == null || unit.dead() || bulletType == null) return;
        if (!unit.isValid()) {
            counters.remove(unit.id);
            return;
        }
        float counter = counters.get(unit.id, 0f) + Time.delta;
        if (counter >= spawnInterval) {
            counter = 0f;
            spawnBullets(unit);
        }
        counters.put(unit.id, counter);
    }

    @Override
    public void onRemoved(Unit unit) {
        if (unit != null) counters.remove(unit.id);
    }

    private void spawnBullets(Unit unit) {
        for (int i = 0; i < bulletCount; i++) {
            float angle;
            if (randomAngle) {
                angle = Mathf.random(360f);
            } else {
                float angleStep = (bulletCount > 1) ? 360f / bulletCount : 0f;
                angle = unit.rotation + spawnAngle + (angleStep * i);
            }
            bulletType.create(unit, unit.team, unit.x, unit.y, angle);
        }
    }
}