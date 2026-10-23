package raen.Enemy.StatusEffectCore;

import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.IntMap;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.entities.Units;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Healthc;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 反伤：血量被削减时，把一部分伤害打回去。
 */
public class ReflectEffect extends StatusEffect {

    /** 反弹特效的播放间隔（tick），避免持续掉血时特效刷屏。 */
    private static final float FX_INTERVAL = 60f;

    /** 贴到范围边缘时的衰减系数：只生效一半。 */
    private static final float EDGE_FALLOFF = 0.5f;

    private static final Color REFLECT_COLOR = Color.valueOf("88ffff");

    /** 满血时的反弹比例。 */
    public float reflectRatioMin = 0.10f;
    /** 濒死时的反弹比例。 */
    public float reflectRatioMax = 0.45f;
    /** 反弹作用范围（世界单位）。 */
    public float reflectRange = 240f;

    private final IntMap<Float> lastHealth = new IntMap<>();
    private final IntMap<Float> fxCooldown = new IntMap<>();

    public ReflectEffect(String name, float reflectRatioMin, float reflectRatioMax, float reflectRange) {
        super(name);
        this.reflectRatioMin = reflectRatioMin;
        this.reflectRatioMax = reflectRatioMax;
        this.reflectRange = reflectRange;
    }

    @Override
    public void applied(Unit unit, float time, boolean extend) {
        super.applied(unit, time, extend);
        if (!extend && unit != null) {
            lastHealth.put(unit.id, unit.health);
        }
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            if (unit != null) {
                lastHealth.remove(unit.id);
                fxCooldown.remove(unit.id);
            }
            return;
        }

        float prev = lastHealth.get(unit.id, Float.NaN);
        if (!Float.isNaN(prev) && unit.health < prev && reflectRatioMax > 0f) {
            float damage = prev - unit.health;

            // 满血 reflectRatioMin → 濒死 reflectRatioMax
            float missing = 1f - Mathf.clamp(unit.health / unit.maxHealth, 0f, 1f);
            float ratio = reflectRatioMin + (reflectRatioMax - reflectRatioMin) * missing;

            Teamc target = Units.closestTarget(unit.team, unit.x, unit.y, reflectRange,
                    u -> u != unit && !u.dead(),
                    b -> !b.dead());

            if (target != null) {
                float dist = unit.dst(target.getX(), target.getY());
                float reflected = damage * ratio * rangeFalloff(dist);

                if (reflected > 0f && target instanceof Healthc health) {
                    health.damage(reflected);

                    float cooldown = fxCooldown.get(unit.id, 0f) - Time.delta;
                    if (cooldown <= 0f) {
                        cooldown = FX_INTERVAL;
                        // data 传 unit，特效会从该单位拉一道闪电到目标位置
                        Fx.chainLightning.at(target.getX(), target.getY(), 0f, REFLECT_COLOR, unit);
                        Fx.hitBulletSmall.at(target.getX(), target.getY());
                    }
                    fxCooldown.put(unit.id, cooldown);
                }
            }
        }

        lastHealth.put(unit.id, unit.health);
    }

    /** 范围一半以内 100%，从一半到边缘线性降到 EDGE_FALLOFF。 */
    private float rangeFalloff(float dist) {
        if (reflectRange <= 0f) return 1f;
        float half = reflectRange * 0.5f;
        float over = Mathf.clamp((dist - half) / half, 0f, 1f);
        return 1f - (1f - EDGE_FALLOFF) * over;
    }

    @Override
    public void onRemoved(Unit unit) {
        if (unit != null) {
            lastHealth.remove(unit.id);
            fxCooldown.remove(unit.id);
        }
    }
}
