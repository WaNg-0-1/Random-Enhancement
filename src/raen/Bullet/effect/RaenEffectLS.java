package raen.Bullet.effect;

import arc.Core;
import arc.graphics.Color;
import arc.math.Angles;
import arc.math.Mathf;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.gen.Unit;
import raen.Bullet.RaenEffectBase;
import raen.Bullet.RaenEffectText;

import static raen.Bullet.RaenEffectText.num;

/**
 * 效果 —— 连锁（LianSuo）
 */
public class RaenEffectLS extends RaenEffectBase {

    public static final String ID = "raenlsChain";
    public static final RaenEffectLS INSTANCE;

    static {
        INSTANCE = new RaenEffectLS();
        register(INSTANCE);
    }

    /** 触发概率基数（%），概率 = 基数 + 层数 */
    public float baseTriggerPercent = 1f;
    /** 连锁目标数基数 */
    public float baseChainCount = 3f;
    /** 每层增加的连锁目标数 */
    public float chainCountPerStack = 0.10f;
    /** 伤害基数（%），伤害 = 子弹伤害 × (基数 + 层数)% */
    public float baseDamagePercent = 10f;
    /** 跳跃搜索半径基数（世界单位） */
    public float baseChainRange = 240f;
    /** 每次跳跃的伤害衰减 */
    public float decayFactor = 0.8f;
    /** 主题色 */
    public Color color = Color.valueOf("9BE7FF");

    private final RaenEffectText text;

    public RaenEffectLS() {
        text = RaenEffectText.key("raen.tag.ls.name", "9BE7FF", this::describe);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RaenEffectText text() {
        return text;
    }

    @Override
    protected void onHit() {
        if (ctxTarget == null) return;

        // 每次命中 +1 层
        addLayer(ctxTarget, Math.max(1, ctxStacks));
        int stacks = layer(ctxTarget);

        if (ctxBullet == null) return;
        if (Mathf.random() >= (baseTriggerPercent + stacks) / 100f) return;

        float damage = ctxBullet.damage * (baseDamagePercent + stacks) / 100f;
        if (damage <= 0f) return;

        Team team = ctxTeam != null ? ctxTeam : teamOf(ctxTarget);
        spawn(team, ctxTarget.x(), ctxTarget.y(), damage, stacks);

        // 触发后清空层数，否则下一次命中会立刻再次触发
        clearLayer(ctxTarget);
    }

    private void spawn(Team team, float x, float y, float damage, int stacks) {
        Unit nearest = Units.closestEnemy(team, x, y, baseChainRange, u -> u.isValid());
        float angle = nearest != null ? Angles.angle(x, y, nearest.x, nearest.y) : Mathf.random(360f);

        float range = baseChainRange + stacks;

        RaenChainLightningBullet chain = new RaenChainLightningBullet(range, damage);
        chain.chainCount = Math.max(1, (int) (baseChainCount + stacks * chainCountPerStack));
        chain.chainRange = range;
        chain.decayFactor = decayFactor;
        chain.lightningColor = color;
        // 运行时 new 出来的子弹类型必须补 load()，否则贴图区域为 null → 绘制 NPE
        chain.load();
        chain.create(null, team, x, y, angle);
    }

    public String describe() {
        return Core.bundle.format("raen.tag.ls.title", text.color) + "\n" +
                Core.bundle.get("raen.tag.ls.l1") + "\n" +
                Core.bundle.format("raen.tag.ls.l2", (int) baseTriggerPercent) + "\n" +
                "  · " + Core.bundle.format("raen.tag.ls.l3", (int) baseChainCount,
                (int) (chainCountPerStack * 100)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.ls.l4", (int) baseDamagePercent) + "\n" +
                "  · " + Core.bundle.format("raen.tag.ls.l5", (int) baseChainRange) + "\n" +
                "  · " + Core.bundle.format("raen.tag.ls.l6", (int) (decayFactor * 100)) + "\n" +
                Core.bundle.get("raen.tag.ls.l7");
    }
}
