package raen.Bullet.effect;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Font;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.gen.Healthc;
import mindustry.graphics.Layer;
import mindustry.ui.Fonts;
import raen.Bullet.RaenEffectBase;
import raen.Bullet.RaenEffectText;

import static raen.Bullet.RaenEffectText.num;

/**
 * 效果 —— 破绽（PoZhan）
 */
public class RaenEffectPZ extends RaenEffectBase {

    /** 子弹标签名：唯一，定下后不要改 */
    public static final String ID = "raenPz";
    public static final RaenEffectPZ INSTANCE;

    static {
        INSTANCE = new RaenEffectPZ();
        register(INSTANCE);          // ★ 必须，注册进全局分发
    }

    // ==================== 可调参数 ====================
    /** 层数持续时间（tick，600 = 10 秒），到期后开始逐层衰减 */
    public float duration = 10f * 60f;
    /** 到期后每多少 tick 掉 1 层（12 = 0.2 秒） */
    public float decayInterval = 12f;
    /** 层数上限 */
    public int maxStacks = 60;
    /** 命中触发破绽伤害的概率 */
    public float triggerChance = 0.30f;
    /** 触发破绽后叠层的概率 */
    public float applyChance = 0.30f;
    /** 每层伤害系数（子弹伤害 × 层数 × 该系数） */
    public float damageRatio = 0.0075f;
    /** 直接施加（无子弹）时的等效子弹伤害（构造时同步给父类的 fallbackDamage） */
    public float directHitDamage = 5f;
    /** 层数文字缩放（和另外几个效果保持一致，避免挡视线） */
    public float textScale = 0.25f;
    /** 跳字缩放（比层数略大一点，方便看清打出了多少） */
    public float jumpTextScale = 0.35f;
    /** 跳字存活时间（秒） */
    public float jumpTextLife = 1f;
    /** 主题色 */
    public Color color = Color.valueOf("FF5500");

    private final RaenEffectText text;
    /**
     * 各目标的剩余持续时间。
     * ★ 用 float[] 而不是 Float：ObjectMap 的 for-each 迭代出来的 Entry.value 只是当前槽位值的**拷贝**，
     * 写回 entry.value 不会影响 map（倒计时会永远不减、层数永远不过期），必须改数组里的元素。
     */
    private final ObjectMap<Healthc, float[]> durations = new ObjectMap<>();
    /** 伤害跳字 */
    private final Seq<JumpText> jumps = new Seq<>();
    /** 复用：本帧到期的目标，避免每帧 new */
    private final Seq<Healthc> expiry = new Seq<>();

    public RaenEffectPZ() {
        // 直接施加时没有子弹，用 directHitDamage 当等效子弹伤害
        fallbackDamage = directHitDamage;
        text = RaenEffectText.key("raen.tag.pz.name", "FF5500", this::describe);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RaenEffectText text() {
        return text;
    }

    // ==================== 命中时 ====================
    @Override
    protected void onHit() {
        if (ctxTarget == null || !valid(ctxTarget)) return;

        // 有子弹时用子弹伤害；直接施加（.to / applyTo）时视为一次 directHitDamage 伤害的命中
        float base = ctxBullet == null ? directHitDamage : ctxDamage;

        // ① 按 triggerChance 概率触发破绽伤害（用触发前的当前层数）
        if (!Mathf.chance(triggerChance)) return;

        int stacks = layer(ctxTarget);
        if (stacks > 0) {
            float dmg = base * stacks * damageRatio;
            if (dmg > 0f) {
                ctxTarget.damage(dmg);
                addJump(ctxTarget, dmg);
            }
        }

        // ② 只有触发成功后才判定叠层：按 applyChance 概率叠加 ctxStacks 层（默认 1）并刷新时长
        if (Mathf.chance(applyChance)) {
            int add = Math.max(1, ctxStacks);
            if (stacks < maxStacks) {
                setLayer(ctxTarget, Math.min(maxStacks, stacks + add));
            }
            float[] left = durations.get(ctxTarget);
            if (left == null) {
                durations.put(ctxTarget, new float[]{duration, 0f});
            } else {
                left[0] = duration;          // 原地刷新，不新建
                left[1] = 0f;
            }
        }
    }

    // ==================== 持续结算 ====================
    @Override
    public void onTick(float delta) {
        // 层数持续时间到期后不再一次性清空，而是每 decayInterval 掉 1 层
        if (durations.size > 0) {
            expiry.clear();
            for (ObjectMap.Entry<Healthc, float[]> e : durations) {
                Healthc t = e.key;
                if (t == null) continue;
                if (t.dead() || !has(t)) {
                    expiry.add(t);
                    continue;
                }

                if (e.value[0] > 0f) {
                    e.value[0] -= delta;
                    continue;
                }

                e.value[1] += delta;
                if (e.value[1] < decayInterval) continue;
                e.value[1] -= decayInterval;

                int stacks = layer(t) - 1;
                if (stacks <= 0) {
                    expiry.add(t);
                } else {
                    setLayer(t, stacks);
                }
            }
            for (int i = 0; i < expiry.size; i++) {
                Healthc t = expiry.get(i);
                durations.remove(t);
                clearLayer(t);
            }
        }

        // 伤害跳字推进
        if (jumps.size > 0) {
            float step = delta / (60f * jumpTextLife);
            for (int i = jumps.size - 1; i >= 0; i--) {
                JumpText jt = jumps.get(i);
                jt.life += step;
                if (jt.life >= 1f) jumps.remove(i);
            }
        }
    }

    // ==================== 目标被摧毁 ====================
    @Override
    public void onDeath(Healthc target) {
        durations.remove(target);
        jumps.remove(jt -> jt.target == target);
        super.onDeath(target);       // ★ 父类负责清层
    }

    // ==================== 绘制 ====================
    @Override
    public void onDraw() {
        if (layers.size == 0 && jumps.size == 0) return;

        Draw.z(Layer.overlayUI);
        Font font = Fonts.outline;

        // 层数文字：×层数 —— 常显（不跟跳字跑），占槽位 0，正好在跳字下方
        for (ObjectMap.Entry<Healthc, Integer> entry : layers) {
            Healthc target = entry.key;
            int stacks = entry.value == null ? 0 : entry.value;
            if (target == null || target.dead() || stacks <= 0) continue;

            tmpColor.set(color).lerp(Color.white, Mathf.clamp(stacks / (float) maxStacks) * 0.4f);
            tmpColor.a = 0.95f;
            // x 跟跳字对齐（跳字在 +12 处），y 用槽位 0，跳字消失后也不会跑位
            drawStacksAt("×" + stacks, target.x() + 12f, stackSlotY(target, 0), textScale, tmpColor);
        }

        // 伤害跳字（目标右侧，向上飘并淡出）
        for (int i = 0; i < jumps.size; i++) {
            JumpText jt = jumps.get(i);
            float p = jt.life;
            float alpha = 1f - p * p;                 // 后段淡出更快
            float x = jt.x + 12f + p * 10f;
            float y = jt.y + p * 26f;

            tmpColor.set(color).lerp(Color.white, 0.35f);
            tmpColor.a = alpha;
            font.setColor(tmpColor);
            // 伤害保留两位小数（数值可能只有 0.25 这种量级，取整会变成 0）
            drawStackText(font, String.format("%.2f", jt.damage), x, y, jumpTextScale);
        }

        Draw.reset();
    }

    // ==================== 世界重置 ====================
    @Override
    public void onReset() {
        durations.clear();
        jumps.clear();
        expiry.clear();
    }

    // ==================== 工具 ====================
    private void addJump(Healthc target, float damage) {
        JumpText jt = new JumpText();
        jt.target = target;
        jt.x = target.x() + targetSize(target) / 2f;
        jt.y = target.y();
        jt.damage = damage;
        jt.life = 0f;
        jumps.add(jt);
    }

    // ==================== 详情页文本 ====================
    public String describe() {
        return Core.bundle.format("raen.tag.pz.title", text.color) + "\n" +
                "  · " + Core.bundle.format("raen.tag.pz.l1", (int) (triggerChance * 100f)) + "\n" +
                "    " + Core.bundle.format("raen.tag.pz.l2", num(damageRatio * 100f)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.pz.l3", (int) (applyChance * 100f)) + "\n" +
                "    " + Core.bundle.format("raen.tag.pz.l4", maxStacks) + "\n" +
                "  · " + Core.bundle.get("raen.tag.pz.l5") + "\n" +
                "  · " + Core.bundle.format("raen.tag.pz.l6", num(duration / 60f), num(decayInterval / 60f)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.pz.l7", num(directHitDamage));
    }

    private static class JumpText {
        /** 跳字跟随的目标（目标死亡时顺手清掉它的跳字） */
        Healthc target;
        float x, y;
        float damage;
        float life;
    }
}
