package raen.Bullet.effect;

import arc.Core;
import arc.graphics.Color;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.gen.Healthc;
import mindustry.gen.Unit;
import raen.Bullet.RaenEffectBase;
import raen.Bullet.RaenEffectText;

import static raen.Bullet.RaenEffectText.num;

/**
 * 效果 —— 转命（ZM）
 * <p>
 * 移植自测试模组的 {@code FzEffectZM}，只改了包名、类名与子弹标签名。
 * 命中敌方单位后附加「转命」印记，并把持续时间刷新为 10 秒；
 * 之后**只按固定间隔结算**（默认每 1/3 秒一次）：按目标【当前生命值】的百分比扣除血量，
 * 并把扣除量的 95% 转化为【目标自身】的护盾。
 * <p>
 * ★ 曾经有过「每次命中额外独立结算一次」，但它会被高攻速炮台放大（配合注入直接伤害爆炸），
 * 已移除；现在改为**缩短结算间隔 + 降低单次比例**来补偿，使伤害与射速完全解耦。
 * 建筑没有护盾系统，因此不对建筑生效。
 */
public class RaenEffectZM extends RaenEffectBase {

    /** 子弹标签名：唯一，定下后不要改，否则旧存档/其它方块的标签会失效 */
    public static final String ID = "raenZm";
    public static final RaenEffectZM INSTANCE;

    static {
        INSTANCE = new RaenEffectZM();
        register(INSTANCE);          // ★ 必须，注册进全局分发
    }

    // ==================== 可调参数 ====================
    /** 结算间隔（tick）；18 = 每 18/60 秒一次 */
    public float interval = 18f;
    /** 印记持续时间（tick，720 = 12 秒）；每次命中都会刷新为这个值 */
    public float duration = 720f;
    /**
     * 每层每次结算扣除的【当前生命值】比例（0.0175% = 0.000175）。
     */
    public float drainPercent = 0.000175f;
    /** 层数上限 */
    public int maxStacks = 20;
    /** 扣除血量转化为护盾的比例 */
    public float shieldRatio = 0.99f;
    /** 主题色 */
    public Color color = Color.valueOf("FFFF88");

    private float timer = 0f;
    /** 每个目标剩余的印记时间（tick），用数组是为了原地修改、不产生装箱垃圾 */
    private final ObjectMap<Healthc, float[]> remaining = new ObjectMap<>();
    /** 倒计时到期的目标，复用避免每帧 new */
    private final Seq<Healthc> expired = new Seq<>();
    private final RaenEffectText text;

    public RaenEffectZM() {
        text = RaenEffectText.key("raen.tag.zm.name", "FFFF88", this::describe);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RaenEffectText text() {
        return text;
    }

    /** 清层时一并清掉倒计时，避免残留 */
    @Override
    public void clearLayer(Healthc target) {
        super.clearLayer(target);
        if (target != null) remaining.remove(target);
    }

    // ==================== 命中时 ====================

    @Override
    protected void onHit() {
        // 只作用于单位：建筑没有护盾系统
        if (!(ctxTarget instanceof Unit)) return;

        int cur = layer(ctxTarget);
        int add = Math.min(ctxStacks, maxStacks - cur);
        if (add > 0) addLayer(ctxTarget, add);

        // ★ 命中刷新持续时间（即使已经满层也刷新）
        float[] left = remaining.get(ctxTarget);
        if (left == null) remaining.put(ctxTarget, new float[]{duration});
        else left[0] = duration;

        // ★ 这里不再额外结算：伤害只走固定间隔的 onTick，与射速完全解耦
    }

    /**
     * 结算一次：扣除 当前生命 × drainPercent × 层数 的血量（真伤，无视护甲），
     * 并把扣除量的 shieldRatio 转化为【目标自身】的护盾。
     *
     * @return 是否真的扣到了血
     */
    private boolean drain(Healthc target, int stacks) {
        if (!(target instanceof Unit) || target.dead() || stacks <= 0) return false;

        float damage = target.health() * drainPercent * stacks;
        if (damage <= 0f) return false;

        trueDamage(target, damage);

        // 被这次结算打死了就不再给护盾
        if (target.dead()) return true;

        ((Unit) target).shield += damage * shieldRatio;
        return true;
    }

    // ==================== 持续结算 ====================

    @Override
    public void onTick(float delta) {
        if (layers.size == 0) {
            timer = 0f;
            remaining.clear();
            return;
        }

        // ① 印记倒计时：走完就清层（内部会同时移除 remaining）
        expired.clear();
        for (ObjectMap.Entry<Healthc, float[]> entry : remaining) {
            entry.value[0] -= delta;
            if (entry.value[0] <= 0f) expired.add(entry.key);
        }
        for (int i = 0; i < expired.size; i++) {
            clearLayer(expired.get(i));
        }

        // ② 每 interval 结算一次
        timer += delta;
        if (timer < interval) return;
        timer = 0f;

        // ★ 先快照：结算过程中可能有人死亡、有人被叠层
        Seq<Healthc> snapshot = new Seq<>();
        for (ObjectMap.Entry<Healthc, Integer> entry : layers) {
            snapshot.add(entry.key);
        }

        for (int i = 0; i < snapshot.size; i++) {
            Healthc target = snapshot.get(i);
            if (target == null) continue;

            int stacks = layer(target);
            if (target.dead() || stacks <= 0) {
                clearLayer(target);
                continue;
            }

            // 只作用于单位（建筑没有护盾，直接清层）
            if (!(target instanceof Unit)) {
                clearLayer(target);
                continue;
            }

            // 补一个倒计时（例如被别的代码直接 addLayer 进来的目标），避免印记永不过期
            if (!remaining.containsKey(target)) {
                remaining.put(target, new float[]{duration});
            }

            // 结算：扣 当前生命 × drainPercent × 层数 的血，并把 95% 转成目标自己的护盾
            drain(target, stacks);

            // 死亡事件已经把层数清掉了，这里只兜底清理
            if (target.dead()) {
                clearLayer(target);
            }
        }
    }

    // ==================== 世界重置 ====================

    @Override
    public void onReset() {
        timer = 0f;
        remaining.clear();
        expired.clear();
    }

    // ==================== 详情页文本 ====================

    public String describe() {
        return Core.bundle.format("raen.tag.zm.title", text.color) + "\n" +
                Core.bundle.format("raen.tag.zm.l1", maxStacks) + "\n" +
                Core.bundle.format("raen.tag.zm.l2", num(duration / 60f)) + "\n" +
                Core.bundle.format("raen.tag.zm.l3", num(interval / 60f)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.zm.l4", num(drainPercent * 100f)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.zm.l5", num(shieldRatio * 100f)) + "\n" +
                Core.bundle.get("raen.tag.zm.l6");
    }
}
