package raen.Bullet.effect;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Font;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Healthc;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.ui.Fonts;
import raen.Bullet.RaenEffectBase;
import raen.Bullet.RaenEffectText;

import static raen.Bullet.RaenEffectText.num;

/**
 * 效果 —— 积焰（JiYan）
 * <p>
 * 移植自测试模组的 {@code FzEffectJY}，只改了包名、类名与子弹标签名。
 * 子弹命中目标时叠加 1 层积焰（无上限），每 0.75 秒结算一次持续伤害；
 * 目标死亡时范围引爆并连锁。单位与建筑都生效。
 */
public class RaenEffectJY extends RaenEffectBase {

    public static final String ID = "raenjyFlame";
    public static final RaenEffectJY INSTANCE;

    static {
        INSTANCE = new RaenEffectJY();
        register(INSTANCE);
    }

    // ==================== 可调参数 ====================
    /** 持续伤害触发间隔（tick，45 = 0.75 秒） */
    public float dotInterval = 45f;
    /** 基础真实伤害 */
    public float baseDotDamage = 1f;
    /**
     * 每层额外真实伤害（平伤：0.01 = 每层 +0.01 点）。
     * 不要改成「按最大生命百分比」：测试环境下会出现【当前生命远大于最大生命】的单位，
     * 那时按 maxHealth 算出来的真伤会小到等于没伤害。
     */
    public float dotTruePerStack = 0.01f;
    /** 每层对「目标当前生命」的比例系数 */
    public float dotHpStackRatio = 1f / 5000f;
    /** 上面那项再乘目标当前生命的多少（0.2 = 20%） */
    public float dotHpScale = 0.20f;
    /** 有护盾时额外附带「护盾值 × 该系数」的伤害 */
    public float shieldDamageRatio = 0.005f;
    /** 层数分摊范围下限（16 格）：实际范围 = 该值 + 层数 × {@link #spreadRangePerStack}，上限 {@link #spreadRangeMax} */
    public float spreadRangeBase = 128f;
    /** 每多 1 层追加的分摊范围（格）：10% 格 = 每 10 层 +1 格 */
    public float spreadRangePerStack = 0.1f * 8f;
    /** 层数分摊范围上限（24 格） */
    public float spreadRangeMax = 192f;
    /** 死亡时「伤害」范围（8 格） */
    public float deathAoeRange = 64f;
    /** 死亡时「连锁引爆」的搜索范围（16 格）：只引爆这个范围内的同阵营积焰目标 */
    public float deathChainRange = 128f;
    /** 死亡引爆：层数 × 该值 */
    public float deathFlatPerStack = 0.05f;
    /** 死亡引爆：层数 × 该值 × 目标最大生命 × deathHpRatio */
    public float deathHpPerStack = 0.001f;
    /** 死亡引爆的生命比例系数 */
    public float deathHpRatio = 0.10f;
    /** 额外效果（引燃 / 点燃地面 / 范围伤害）的层数门槛 */
    public float igniteThreshold = 20f;
    /** 额外效果范围（3.6 格） */
    public float igniteAoeRange = 3.6f * 8f;
    /** 额外效果：层数 × 该值 */
    public float igniteAoeMultiplier = 0.08f;
    /** 同一目标两次额外效果的最小间隔（tick，15 秒） */
    public float igniteCooldown = 15f * 60f;
    /** 灼烧状态时长：层数 × 该系数 */
    public float igniteStatusMultiplier = 45f;
    /** 层数文字缩放（1 = 默认大小；越小越不挡视线） */
    public float textScale = 0.25f;
    /** 主题色 */
    public Color color = Color.valueOf("FF6B2B");

    private float timer = 0f;
    private final RaenEffectText text;

    public RaenEffectJY() {
        text = RaenEffectText.key("raen.tag.jy.name", "FF6B2B", this::describe);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RaenEffectText text() {
        return text;
    }

    // ==================== 命中 ====================

    @Override
    protected void onHit() {
        addLayer(ctxTarget, ctxStacks);
    }

    // ==================== 持续结算 ====================

    @Override
    public void onTick(float delta) {
        if (layers.size == 0) {
            timer = 0f;
            return;
        }

        timer += delta;
        if (timer < dotInterval) return;
        timer = 0f;

        // 快照，防止遍历过程中修改
        Seq<Healthc> snapshot = new Seq<>();
        for (ObjectMap.Entry<Healthc, Integer> entry : layers) {
            snapshot.add(entry.key);
        }

        for (int i = 0; i < snapshot.size; i++) {
            Healthc unit = snapshot.get(i);
            int stacks = layer(unit);
            if (unit == null || unit.dead() || stacks <= 0) {
                clearLayer(unit);
                continue;
            }

            // ① 真实伤害：1 + 层数 × 1%
            float truePart = baseDotDamage + stacks * dotTruePerStack;

            // ② 普通伤害：1 + 层数 × (1/5000) × 目标当前生命 × 20%
            float normalPart = baseDotDamage + stacks * dotHpStackRatio * (unit.health() * dotHpScale);

            unit.damage(normalPart);
            if (unit instanceof Unit) {
                Unit u = (Unit) unit;
                // 对护盾翻倍（再打一次），并附带护盾值 × 0.5%
                if (u.shield > 0f) u.damage(normalPart + u.shield * shieldDamageRatio);
            }
            trueDamage(unit, truePart);

            // 结算中目标可能已死亡（死亡事件已经把层数清掉了）
            if (unit.dead()) {
                clearLayer(unit);
                continue;
            }

            // ③ 层数达到门槛：引燃目标 + 点燃地面 + 范围伤害（同一目标 15 秒一次）
            if (stacks >= igniteThreshold && extraReady(unit)) {
                applyStatus(unit, StatusEffects.burning, stacks * igniteStatusMultiplier);
                spawnFire(unit);
                areaDamage(teamOf(unit), unit.x(), unit.y(), igniteAoeRange,
                        stacks * igniteAoeMultiplier, unit, true);
            }

            // ③ 层数 -1
            stacks--;

            // ④ 层数 ≥2 时把一半分给范围内一个没有积焰的目标（单位或建筑，建筑之间 / 建筑与单位之间都能互相分摊）
            if (stacks >= 2) {
                int give = stacks / 2;
                Healthc target = findSpreadTarget(unit, spreadRange(stacks));
                if (target != null) {
                    addLayer(target, give);
                    stacks -= give;
                }
            }

            setLayer(unit, stacks);
        }
    }

    /**
     * 当前层数下的分摊范围（世界单位）：16 格 + 层数 × 0.1 格，上限 24 格。
     * <p>
     * 用<b>分摊前</b>的层数算 —— 层数越高、能传染得越远，但封顶 24 格。
     */
    private float spreadRange(int stacks) {
        return Math.min(spreadRangeMax, spreadRangeBase + stacks * spreadRangePerStack);
    }

    /**
     * 查找范围内最近的、没有积焰的同队伍目标 —— <b>单位与建筑都算</b>，两者可以互相分摊。
     * <p>
     * 先扫单位、再扫建筑，取距离更近的那个（同距离时单位优先，保持和以前一致）。
     */
    private Healthc findSpreadTarget(Healthc from, float range) {
        final float fx = from.x(), fy = from.y();
        final float r2 = range * range;
        final Team team = teamOf(from);
        final Healthc[] result = new Healthc[1];
        final float[] bestD = {r2};          // 当前最近距离（平方）

        // ① 单位
        Groups.unit.intersect(fx - range, fy - range, range * 2f, range * 2f, other -> {
            if (other == from || other.dead()) return;
            if (other.team != team) return;
            float d = other.dst2(fx, fy);
            if (d > bestD[0]) return;
            if (has(other)) return;
            bestD[0] = d;
            result[0] = other;
        });

        // ② 建筑（同一套判定，跟单位一起比距离）
        eachBuilding(fx, fy, range, build -> {
            if (build == from) return;
            if (build.team != team) return;
            float d = build.dst2(fx, fy);
            if (d > bestD[0]) return;
            if (has(build)) return;
            bestD[0] = d;
            result[0] = build;
        });

        if (result[0] != null) {
            Fx.chainLightning.at(fx, fy, 0f, color, result[0]);
        }
        return result[0];
    }

    // ==================== 额外效果冷却 ====================

    /** 目标 → { 上次触发额外效果的时间 } */
    private final ObjectMap<Healthc, float[]> lastExtra = new ObjectMap<>();

    /** 该目标的额外效果是否可用（每 15 秒最多一次；首次立即可用） */
    private boolean extraReady(Healthc target) {
        if (igniteCooldown <= 0f) return true;
        float[] state = lastExtra.get(target);
        if (state == null) {
            state = new float[]{-igniteCooldown};
            lastExtra.put(target, state);
        }
        if (Time.time - state[0] < igniteCooldown) return false;
        state[0] = Time.time;
        return true;
    }

    // ==================== 死亡引爆 + 连锁 ====================

    @Override
    public void onDeath(Healthc target) {
        lastExtra.remove(target);
        int stacks = layer(target);
        if (stacks <= 0) return;

        final float tx = target.x(), ty = target.y();
        final Team victimTeam = teamOf(target);

        float deathDamage = stacks * deathFlatPerStack
                + stacks * deathHpPerStack * target.maxHealth() * deathHpRatio;

        // ① 死亡目标自身的范围引爆：★ 只伤害【死者自己所属阵营】，不会波及施加方
        areaDamageSameTeam(victimTeam, tx, ty, deathAoeRange, deathDamage, target, true);
        Fx.fire.at(tx, ty, color);
        spawnFire(target);
        applyStatus(target, StatusEffects.melting, stacks);
        applyStatus(target, StatusEffects.burning, stacks);

        // ② 收集范围内其它积焰目标（快照）：用「引爆范围」，比伤害范围大
        Seq<Healthc> nearby = new Seq<>();
        float r2 = deathChainRange * deathChainRange;

        for (Unit other : Groups.unit) {
            if (other == target || other.health() <= 0f) continue;
            if (other.team != victimTeam) continue;
            if (other.dst2(tx, ty) > r2) continue;
            if (has(other)) nearby.add(other);
        }
        for (Building other : Groups.build) {
            if (other == target || other.dead()) continue;
            if (other.team != victimTeam) continue;
            if (other.dst2(tx, ty) > r2) continue;
            if (has(other)) nearby.add(other);
        }

        // ③ 连锁引爆
        for (int i = 0; i < nearby.size; i++) {
            Healthc other = nearby.get(i);
            int otherStacks = layer(other);
            if (otherStacks <= 0) continue;

            clearLayer(other);
            float damage = otherStacks * deathFlatPerStack
                    + otherStacks * deathHpPerStack * other.maxHealth() * deathHpRatio;

            areaDamageSameTeam(victimTeam, other.x(), other.y(), deathAoeRange, damage, other, true);
            spawnFire(other);
            applyStatus(other, StatusEffects.melting, otherStacks);
            applyStatus(other, StatusEffects.burning, otherStacks);
            Fx.fire.at(other.x(), other.y(), color);
            Fx.chainLightning.at(tx, ty, 0f, color, other);
        }
    }

    // ==================== 绘制 ====================

    @Override
    public void onDraw() {
        if (layers.size == 0) return;

        for (ObjectMap.Entry<Healthc, Integer> entry : layers) {
            Healthc unit = entry.key;
            int stacks = entry.value == null ? 0 : entry.value;
            if (unit == null || unit.dead() || stacks <= 0) continue;

            float t = Mathf.clamp(stacks / 100f);
            tmpColor.set(color).lerp(Color.white, t * 0.5f);
            tmpColor.a = 0.9f;

            // 槽位 1：与破绽 / 电荷 / 中毒的层数上下错开，避免大数字横向压在一起
            drawStacks(unit, 1, String.valueOf(stacks), textScale, tmpColor);
        }

        Draw.reset();
    }

    @Override
    public void onReset() {
        timer = 0f;
        lastExtra.clear();
    }

    // ==================== 文本 ====================

    public String describe() {
        return Core.bundle.format("raen.tag.jy.title", text.color) + "\n" +
                Core.bundle.get("raen.tag.jy.l1") + "\n" +
                Core.bundle.format("raen.tag.jy.l2", num(dotInterval / 60f)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.jy.l3", (int) (dotTruePerStack * 100)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.jy.l4", (int) (dotHpScale * 100)) + "\n" +
                "    " + Core.bundle.format("raen.tag.jy.l5", num(shieldDamageRatio * 100)) + "\n" +
                "  · " + Core.bundle.get("raen.tag.jy.l6") + "\n" +
                "  · " + Core.bundle.format("raen.tag.jy.l7", (int) (spreadRangeBase / 8),
                (int) (spreadRangeMax / 8)) + "\n" +
                "    " + Core.bundle.get("raen.tag.jy.l8") + "\n" +
                "  · " + Core.bundle.format("raen.tag.jy.l9", (int) igniteThreshold) + "\n" +
                "    " + Core.bundle.format("raen.tag.jy.l10", (int) (igniteAoeRange / 8),
                (int) (igniteAoeMultiplier * 100), num(igniteCooldown / 60f)) + "\n" +
                Core.bundle.get("raen.tag.jy.l11") + "\n" +
                "  · " + Core.bundle.format("raen.tag.jy.l12", (int) (deathAoeRange / 8),
                (int) (deathFlatPerStack * 100), num(deathHpPerStack), (int) (deathHpRatio * 100)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.jy.l13", (int) (deathChainRange / 8)) + "\n" +
                "    " + Core.bundle.get("raen.tag.jy.l14");
    }
}
