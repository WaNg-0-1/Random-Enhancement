package raen.Bullet.effect;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.game.Team;
import mindustry.gen.Healthc;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import raen.Bullet.RaenEffectBase;
import raen.Bullet.RaenEffectText;

import static mindustry.Vars.tilesize;
import static raen.Bullet.RaenEffectText.num;

/**
 * 效果 —— 注入（ZR）
 * <p>
 * 移植自测试模组的 {@code FzEffectZR}，只改了包名、类名与子弹标签名。
 * <p>
 * 命中叠加层数（无硬上限），满 {@link #maxStacks} 层（默认 40）进入「注入」状态 6 秒：
 *   · 进入时快照目标有效血量（生命 + 护盾）
 *   · 期间继续命中，超出阈值的部分每层向 A 追加固定点数（可调）
 *   · 6 秒结束：计算  A = max(0, 进入有效血量 - 结束有效血量) + 溢出层数折算，
 *     其中【血量差部分】封顶在目标最大生命 × 30%（防止高频百分比伤害把它刷到爆表），
 *     溢出层数折算（每层 0.75 点）不吃上限
 *     在目标处按已损失血量比例引爆（半径 10~25 格、伤害 A × 5%~50%），清空层数
 *   · 注入期间目标死亡：立即以 25 格半径造成 A 伤害
 *   · 视觉：注入期间在目标身上画一个碰撞体积大小的圆，像秒针一样扫过逐渐填满（6 秒填满一整圈）
 * 层数未满阈值时目标死亡无效果。作用于单位与建筑。
 */
public class RaenEffectZR extends RaenEffectBase {

    /** 子弹标签名：唯一，定下后不要改 */
    public static final String ID = "raenZr";
    public static final RaenEffectZR INSTANCE;

    static {
        INSTANCE = new RaenEffectZR();
        register(INSTANCE);          // ★ 必须，注册进全局分发
    }

    // ==================== 可调参数 ====================
    /** 进入注入状态的层数阈值；超过后仍可继续叠层（从 50 降到 40，用来补偿下面新增的伤害池封顶） */
    public int maxStacks = 40;
    /**
     * ★ A 中【血量差部分】的上限 = 目标最大生命值 × 本比例（0.20 = 20%）。
     * <p>
     * 防止高频【百分比伤害】（中毒持续伤害 / 转命扣血 / 积焰的按生命值伤害…）
     * 在 5 秒注入期内把 A 刷到爆表，从而让爆炸伤害失控。
     * <b>溢出层数折算不吃这个上限</b>，所以继续叠层始终有意义。
     * 设为 0 或负数表示不封顶。
     */
    public float injectDamageCapRatio = 0.20f;
    /** 注入持续时间（tick，60 = 1 秒） */
    public float injectDuration = 5f * 60f;
    /** 超出阈值层数后，每层直接折算成多少点伤害加到 A 中（不进上限） */
    public float overflowDamagePerStack = 0.75f;
    /** 爆炸最小半径（格；已损失血量比例 = 0 时） */
    public float minRadius = 8f;
    /** 爆炸最大半径（格；已损失血量比例 = 100% 时） */
    public float maxRadius = 24f;
    /** 死亡引爆的固定半径（格） */
    public float deathRadius = 16f;
    /** 未满阈值就死亡时的引爆半径（格） */
    public float deathPartialRadius = 12f;
    /** 未满阈值就死亡时：层数 × 该值 */
    public float deathPartialMultiplier = 0.10f;
    /** 伤害倍率下限（A 的 5%） */
    public float minDamageRatio = 0.05f;
    /** 伤害倍率上限（A 的 50%） */
    public float maxDamageRatio = 0.50f;
    /** 爆炸视觉持续时间（tick） */
    public float boomDuration = 30f;
    /** 层数文字垂直偏移 */
    public float textOffset = 8f;
    /** 主题色（紫色） */
    public Color color = Color.valueOf("AA55FF");

    // ==================== 注入进度圈（钟表式扫过） ====================
    /** 是否绘制注入进度圈 */
    public boolean drawProgress = true;
    /** 进度圈半径相对「目标碰撞体积」的倍率：0.5 = 半径 = hitSize/2（正好贴住碰撞盒），想显眼点可调 0.75 */
    public float progressRadiusScale = 0.5f;
    /** 进度圈颜色（默认与主题色一致） */
    public Color progressColor = Color.valueOf("AA55FF");
    /** 扫过面积的填充不透明度 */
    public float progressFillAlpha = 0.40f;
    /** 外圈与秒针的不透明度 */
    public float progressLineAlpha = 0.9f;
    /** 进度圈线宽（建议 1~2） */
    public float progressLineWidth = 1.6f;

    private final RaenEffectText text;
    /** 目标 → 注入状态（仅注入期间存在） */
    private final ObjectMap<Healthc, Inject> injections = new ObjectMap<>();
    /** 待绘制的爆炸（坐标与半径均为像素） */
    private final Seq<Boom> booms = new Seq<>();
    /** 复用容器，避免每帧 new */
    private final Seq<Healthc> tmpKeys = new Seq<>();
    private final Seq<Healthc> tmpFinished = new Seq<>();
    /** 目标 → 最近一次施加者的队伍（未满阈值死亡时的爆炸要用） */
    private final ObjectMap<Healthc, Team> lastTeam = new ObjectMap<>();

    public RaenEffectZR() {
        text = RaenEffectText.key("raen.tag.zr.name", "AA55FF", this::describe);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RaenEffectText text() {
        return text;
    }

    /** 注入状态：进入时有效血量 + 溢出层数折算 + 施加者队伍 */
    private static class Inject {
        float startEff;           // 进入注入瞬间的有效血量（生命 + 护盾）
        float overflowAccum;      // 溢出层数折算累积
        float time;               // 已注入时间
        Team team;                // 施加者队伍
    }

    /** 爆炸视觉（像素） */
    private static class Boom {
        float x, y, radius, time;

        Boom(float x, float y, float radius) {
            this.x = x;
            this.y = y;
            this.radius = radius;
        }
    }

    /** 清层时一并丢掉注入状态，避免残留 */
    @Override
    public void clearLayer(Healthc target) {
        super.clearLayer(target);
        if (target != null) {
            injections.remove(target);
            lastTeam.remove(target);
        }
    }

    // ==================== 命中时 ====================
    @Override
    protected void onHit() {
        if (ctxTarget == null) return;
        int add = Math.max(0, ctxStacks);
        if (add == 0) return;

        int oldStacks = layer(ctxTarget);
        int newStacks = oldStacks + add;
        setLayer(ctxTarget, newStacks);
        if (ctxTeam != null) lastTeam.put(ctxTarget, ctxTeam);

        Inject inj = injections.get(ctxTarget);
        boolean justStarted = false;

        // 未注入且层数已达阈值 → 开启注入（快照进入时的有效血量）
        if (inj == null && newStacks >= maxStacks) {
            startInject(ctxTarget, ctxTeam);
            inj = injections.get(ctxTarget);
            justStarted = true;
        }

        if (inj != null) {
            // 仅把「本次命中新增的溢出层」折算进 A，避免重复计算
            int overflow;
            if (justStarted) {
                overflow = Math.max(0, newStacks - maxStacks);
            } else {
                overflow = Math.max(0, newStacks - maxStacks) - Math.max(0, oldStacks - maxStacks);
            }
            if (overflow > 0) inj.overflowAccum += overflow * overflowDamagePerStack;
        }
    }

    private void startInject(Healthc target, Team team) {
        Inject inj = new Inject();
        inj.startEff = effHp(target);
        inj.overflowAccum = 0f;
        inj.time = 0f;
        inj.team = team;
        injections.put(target, inj);
    }

    /** 有效血量 = 生命值 + 护盾（护盾只有单位有） */
    private float effHp(Healthc target) {
        float h = target.health();
        if (target instanceof Unit) h += ((Unit) target).shield;
        return h;
    }

    /**
     * A = min( max(0, 进入有效血量 - 结束有效血量), 目标最大生命 × injectDamageCapRatio )
     *     + 溢出层数折算
     * <p>
     * ★ <b>只有血量差那部分吃上限</b>：超阈值的层数折算（每层 overflowDamagePerStack）完全不吃上限，
     * 所以"继续叠层"永远有收益，同时又挡得住高频百分比伤害把血量差刷爆。
     * <p>
     * 封顶在这一处统一处理：不管是 6 秒正常结束、还是注入期间目标死亡，两条路径规则一致。
     */
    private float computeA(Healthc target, Inject inj) {
        float hpPart = inj.startEff - effHp(target);
        if (hpPart <= 0f) hpPart = 0f;   // 被治疗 / 被加盾超出快照值 → 忽略

        // 只封顶血量差部分
        if (injectDamageCapRatio > 0f) {
            float cap = target.maxHealth() * injectDamageCapRatio;
            if (cap > 0f) hpPart = Math.min(hpPart, cap);
        }

        return hpPart + inj.overflowAccum;   // ★ 溢出折算不进上限
    }

    // ==================== 持续结算 ====================
    @Override
    public void onTick(float delta) {
        // ---- 注入状态推进 ----
        if (injections.size > 0) {
            tmpKeys.clear();
            for (ObjectMap.Entry<Healthc, Inject> e : injections) {
                tmpKeys.add(e.key);
            }

            tmpFinished.clear();
            for (int i = 0; i < tmpKeys.size; i++) {
                Healthc target = tmpKeys.get(i);
                Inject inj = injections.get(target);
                if (target == null || inj == null) {
                    injections.remove(target);
                    continue;
                }
                if (target.dead()) continue;   // 死亡引爆交给 onDeath

                inj.time += delta;
                if (inj.time >= injectDuration) tmpFinished.add(target);
            }

            for (int i = 0; i < tmpFinished.size; i++) {
                Healthc target = tmpFinished.get(i);
                Inject inj = injections.get(target);
                if (target == null || inj == null) continue;
                detonate(target, inj, false);
                clearLayer(target);            // 内部会一并移除注入状态
            }
        }

        // ---- 爆炸视觉推进 ----
        for (int i = booms.size - 1; i >= 0; i--) {
            Boom b = booms.get(i);
            b.time += delta;
            if (b.time > boomDuration) booms.remove(i);
        }
    }

    /** 在目标处引爆。爆炸伤害来源为施加方队伍 */
    private void detonate(Healthc target, Inject inj, boolean isDeath) {
        float x = target.x();
        float y = target.y();
        float lost = 1f - Mathf.clamp(target.health() / Math.max(1f, target.maxHealth()));

        // ★ 半径参数是「格」，areaDamage / 绘制都用像素，这里统一换算
        float radius, dmgRatio;
        if (isDeath) {
            radius = deathRadius * tilesize;
            dmgRatio = maxDamageRatio;
        } else {
            radius = Mathf.lerp(minRadius, maxRadius, lost) * tilesize;
            dmgRatio = Mathf.lerp(minDamageRatio, maxDamageRatio, lost);
        }

        float A = computeA(target, inj);
        float damage = A * dmgRatio;
        if (damage > 0f && inj.team != null) {
            // 伤害来源为施加方队伍；只伤害不属于该队伍的单位与建筑，排除目标自己
            areaDamage(inj.team, x, y, radius, damage, target, true);
        }

        booms.add(new Boom(x, y, radius));
    }

    // ==================== 死亡时 ====================
    @Override
    public void onDeath(Healthc target) {
        Inject inj = injections.get(target);
        if (inj != null) {
            detonate(target, inj, true);   // 注入期间死亡 → 16 格 A 伤害
            injections.remove(target);
        } else {
            // 没到阈值就死亡：造成一次 12 格范围、层数 × 10% 的伤害
            int stacks = layer(target);
            Team team = lastTeam.get(target);
            if (stacks > 0 && team != null) {
                float damage = stacks * deathPartialMultiplier;
                if (damage > 0f) {
                    areaDamage(team, target.x(), target.y(),
                            deathPartialRadius * tilesize, damage, target, true);
                }
            }
        }
        super.onDeath(target);             // ★ 父类负责清层
    }

    // ==================== 绘制爆炸 ====================
    @Override
    public void onDraw() {
        Draw.z(Layer.effect);

        // ---------- 注入进度圈：目标身上一个碰撞体积大小的圆，钟表式扫过逐渐填满 ----------
        if (drawProgress && injections.size > 0) {
            for (ObjectMap.Entry<Healthc, Inject> entry : injections) {
                Healthc target = entry.key;
                Inject inj = entry.value;
                if (target == null || inj == null || target.dead()) continue;

                // 填充进度 = 已注入时间 / 总时长（6 秒正好填满一圈）
                float p = Mathf.clamp(inj.time / Math.max(1f, injectDuration));
                float r = targetSize(target) * progressRadiusScale;
                if (r <= 1f) continue;

                float cx = target.x(), cy = target.y();
                // arc 的角度约定：0° 为正右、度数增加沿屏幕顺时针，所以 270° 是正上方（12 点）
                float start = 270f;
                float hand = start + 360f * p;      // 秒针位置

                Draw.color(progressColor);

                // ① 秒针扫过的面积（从 12 点顺时针铺满）
                Draw.alpha(progressFillAlpha);
                Fill.arc(cx, cy, r, p, start);

                // ② 秒针 + 外圈
                Draw.alpha(progressLineAlpha);
                Lines.stroke(progressLineWidth);
                Lines.line(cx, cy, cx + Angles.trnsx(hand, r), cy + Angles.trnsy(hand, r));
                Lines.circle(cx, cy, r);
            }
            Draw.reset();
        }

        // ---------- 爆炸 ----------
        if (booms.size == 0) return;

        for (int i = 0; i < booms.size; i++) {
            Boom b = booms.get(i);
            float p = Mathf.clamp(b.time / boomDuration);

            // 开头 15% 快速淡入（避免突然出现），之后平滑淡出
            float fadeIn = Mathf.clamp(p / 0.15f);
            float fade = (1f - p) * fadeIn;
            // 半径先快后慢（easeOutQuad），看起来像冲击波而不是匀速膨胀
            float ease = 1f - (1f - p) * (1f - p);
            float r = b.radius * (0.25f + 0.75f * ease);

            // ★ 必须用 Draw.color / Draw.alpha：Fill 与 Lines 走的是 Draw 的当前颜色，
            //   直接改 tmpColor 不会生效（之前就会画出默认的白色不透明光斑）
            Draw.color(color);

            // 内层光晕
            Draw.alpha(fade * 0.28f);
            Fill.circle(b.x, b.y, r * 0.82f);

            // 冲击波外圈
            Draw.alpha(fade * 0.85f);
            Lines.stroke(0.8f + 3.4f * (1f - p));
            Lines.circle(b.x, b.y, r);

            // 内侧细圈，增加层次
            Draw.alpha(fade * 0.45f);
            Lines.stroke(0.5f + 1.2f * (1f - p));
            Lines.circle(b.x, b.y, r * 0.6f);
        }
        Draw.reset();
    }

    // ==================== 世界重置 ====================
    @Override
    public void onReset() {
        injections.clear();
        booms.clear();
        tmpKeys.clear();
        tmpFinished.clear();
    }

    // ==================== 详情页文本 ====================
    public String describe() {
        return Core.bundle.format("raen.tag.zr.title", text.color) + "\n" +
                Core.bundle.format("raen.tag.zr.l1", maxStacks, num(injectDuration / 60f)) + "\n" +
                "  · " + Core.bundle.get("raen.tag.zr.l2") + "\n" +
                "  · " + Core.bundle.format("raen.tag.zr.l3", maxStacks, num(overflowDamagePerStack)) + "\n" +
                "  · " + Core.bundle.get("raen.tag.zr.l4") + "\n" +
                "    " + Core.bundle.format("raen.tag.zr.l5", num(injectDamageCapRatio * 100f)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.zr.l6", num(deathRadius)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.zr.l7", num(injectDuration / 60f)) + "\n" +
                "    " + Core.bundle.format("raen.tag.zr.l8", num(minRadius), num(maxRadius)) + "\n" +
                "    " + Core.bundle.format("raen.tag.zr.l9", num(minDamageRatio * 100f), num(maxDamageRatio * 100f)) + "\n" +
                Core.bundle.format("raen.tag.zr.l10", maxStacks, num(deathPartialRadius),
                        (int) (deathPartialMultiplier * 100)) + "\n" +
                Core.bundle.get("raen.tag.zr.l11");
    }
}
