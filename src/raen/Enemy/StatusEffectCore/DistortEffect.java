package raen.Enemy.StatusEffectCore;

import arc.math.Mathf;
import arc.struct.IntMap;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.entities.bullet.LaserBulletType;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 扭曲：周期性把范围内的敌方子弹抢过来，改队、削伤害并折返。
 */
public class DistortEffect extends StatusEffect {

    /** 作用半径（格）：实际半径 = 自身碰撞体积(hitSize) + rangeTiles × 8。 */
    public float rangeTiles = 1f;
    /** 每次生效时长（秒）。 */
    public float activeSeconds = 1f;
    /** 生效结束后的冷却（秒）。周期 = activeSeconds + cooldownSeconds。 */
    public float cooldownSeconds = 14f;
    /** 抢过来时伤害乘以这个系数。 */
    public float damageScale = 0.25f;
    /** 抢过来时 bullet.time = lifetime × 这个比例（0 = 满寿命）。 */
    public float timeScale = 0.5f;
    /** 偏转角度 = 原方向 + 180° ± spreadDegrees（360 就等价于全向随机）。 */
    public float spreadDegrees = 0f;
    /** 抢弹时在子弹位置播放的特效。 */
    public mindustry.entities.Effect stealEffect = Fx.hitBulletSmall;

    private final IntMap<Float> timers = new IntMap<>();

    public DistortEffect(String name, float rangeTiles, float activeSeconds, float cooldownSeconds) {
        super(name);
        this.rangeTiles = rangeTiles;
        this.activeSeconds = activeSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.damage = 0f;
        this.transitionDamage = 0f;
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            if (unit != null) timers.remove(unit.id);
            return;
        }

        float period = activeSeconds + cooldownSeconds;
        if (period <= 0f) return;

        float timer = timers.get(unit.id, 0f) + Time.delta / 60f;
        if (timer >= period) timer %= period;
        timers.put(unit.id, timer);

        if (timer >= activeSeconds) return;

        // 半径 = 自身碰撞体积 + 额外格数（1/2/3 格），高级不再覆盖过大的场地
        float range = unit.hitSize + rangeTiles * Vars.tilesize;
        if (range <= 0f) return;

        final float r2 = range * range;

        Groups.bullet.intersect(unit.x - range, unit.y - range, range * 2f, range * 2f, b -> {
            if (b == null || b.type == null) return;
            if (b.team == unit.team) return;
            if (b.type instanceof LaserBulletType) return;
            if (b.dst2(unit.x, unit.y) > r2) return;

            float ang = b.rotation();

            b.team = unit.team;
            b.damage *= damageScale;
            b.time = timeScale * b.lifetime;

            b.vel.setAngle(ang + 180f + Mathf.range(spreadDegrees));

            stealEffect.at(b.x, b.y);
        });
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        if (unit != null) timers.remove(unit.id);
    }
}
