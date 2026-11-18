package raen.Bullet.effect;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import mindustry.content.Fx;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.gen.Healthc;
import mindustry.gen.Unit;
import raen.Bullet.RaenEffectBase;
import raen.Bullet.RaenEffectText;

import static raen.Bullet.RaenEffectText.num;

/**
 * 效果 —— 酸蚀印记（SuanShi）
 */
public class RaenEffectSS extends RaenEffectBase {

    public static final String ID = "raenlyBuff";
    public static final RaenEffectSS INSTANCE;

    static {
        INSTANCE = new RaenEffectSS();
        register(INSTANCE);
    }

    /** 附加标签后子弹伤害的倍率（0.8 = 命中只造成 80%） */
    public float bulletDamageMultiplier = 0.8f;
    /** 额外伤害触发几率 */
    public float triggerChance = 0.15f;
    /** 额外伤害倍率（占子弹伤害，0.05 = 5%） */
    public float multiplierC = 0.05f;
    /** 引爆层数阈值 */
    public int stackThreshold = 36;
    /** 引爆范围（4 格） */
    public float detonateRange = 32f;
    /** 引爆伤害：层数 × 子弹伤害 × 该值 */
    public float detonateMultiplier = 0.20f;
    /** 层数超过该值后，命中时有概率把本次命中变成混伤 */
    public int weakenStacks = 18;
    /** 混伤里走真实伤害的比例（0.5 = 一半真实一半常规） */
    public float mixTrueRatio = 0.5f;
    /** 混伤上限：占子弹初始伤害的比例（0.5 = 50%） */
    public float mixCapRatio = 0.5f;

    /** 主题色 */
    public Color color = Color.valueOf("ff5555");

    private final RaenEffectText text;

    public RaenEffectSS() {
        text = RaenEffectText.key("raen.tag.ss.name", "ff5555", this::describe);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RaenEffectText text() {
        return text;
    }

    // ==================== 子弹标签 ====================

    @Override
    public boolean attach(Bullet bullet, int stacks) {
        if (bullet == null || stacks <= 0) return false;
        if (bulletStacks(bullet) > 0) return false;
        markBullet(bullet, ID, stacks);
        // 附带酸蚀的子弹伤害降低 20%
        bullet.damage *= bulletDamageMultiplier;
        return true;
    }

    // ==================== 命中 ====================

    @Override
    protected void onHit() {
        if (ctxTarget == null) return;
        for (int i = 0; i < ctxStacks; i++) {
            applyHit(ctxTarget, ctxDamage, ctxTeam);
        }
    }

    /** 核心结算逻辑（单位与建筑通用） */
    private void applyHit(Healthc target, float baseDamage, Team team) {
        if (target == null || target.dead()) return;

        int stacks = layer(target) + 1;

        // 与中毒联动
        if (RaenEffectZD.INSTANCE.has(target)) {
            RaenEffectZD.INSTANCE.addLayer(target, 1);
        }

        // 15% 概率：额外造成 5% 子弹伤害，并额外 +1 层
        if (Mathf.random() < triggerChance) {
            float extra = baseDamage * multiplierC;
            if (extra > 0f) target.damage(extra);
            stacks++;
        }

        // 层数 ≥ 18：按「层数%」概率附带一次混伤
        if (stacks >= weakenStacks && baseDamage > 0f && Mathf.chance(stacks / 100f)) {
            // 已损失生命 × (0.1 × 层数)%，上限为子弹初始伤害的 50%
            float lost = Math.max(target.maxHealth() - target.health(), 0f);
            float bonus = lost * (stacks * 0.1f) / 100f;

            float original = baseDamage / Math.max(bulletDamageMultiplier, 0.01f);
            bonus = Math.min(bonus, original * mixCapRatio);

            if (bonus > 0f) {
                // 一半走普通伤害（受护甲影响），一半走真实伤害（无视护甲与护盾）
                target.damage(bonus * (1f - mixTrueRatio));
                trueDamage(target, bonus * mixTrueRatio);
            }
            stacks++;
        }

        if (target.dead()) {
            clearLayer(target);
            return;
        }

        if (stacks >= stackThreshold) {
            detonate(target, baseDamage, team, stacks);
            return;
        }

        if (stackThreshold > stacks) stacks++;
        setLayer(target, stacks);
    }

    /** 叠满引爆：4 格范围伤害并清空层数 */
    private void detonate(Healthc target, float baseDamage, Team team, int stacks) {
        Fx.heal.at(target.x(), target.y(), Color.rgb(255, 50, 100));

        // 引爆补的是被扣掉的 20%，所以基数还原成「未弱化」的 100% 子弹伤害
        float original = baseDamage / Math.max(bulletDamageMultiplier, 0.01f);
        float damage = stacks * original * detonateMultiplier;
        if (damage > 0f) {
            areaDamage(team, target.x(), target.y(), detonateRange, damage, null, true);
        }

        clearLayer(target);
    }

    // ==================== 文本 ====================

    public String describe() {
        return Core.bundle.format("raen.tag.ss.title", text.color) + "\n" +
                "  · " + Core.bundle.format("raen.tag.ss.l1", (int) ((1f - bulletDamageMultiplier) * 100)) + "\n" +
                "  · " + Core.bundle.get("raen.tag.ss.l2") + "\n" +
                "  · " + Core.bundle.format("raen.tag.ss.l3", (int) (triggerChance * 100),
                (int) (multiplierC * 100)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.ss.l4", stackThreshold, (int) (detonateRange / 8),
                (int) (detonateMultiplier * 100)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.ss.l5", weakenStacks) + "\n" +
                "    " + Core.bundle.format("raen.tag.ss.l6", (int) (mixCapRatio * 100)) + "\n" +
                "    " + Core.bundle.format("raen.tag.ss.l7", (int) (mixTrueRatio * 100)) + "\n" +
                Core.bundle.get("raen.tag.ss.l8");
    }
}
