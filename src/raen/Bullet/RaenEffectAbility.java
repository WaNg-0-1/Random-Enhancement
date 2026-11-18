package raen.Bullet;

import arc.util.Time;
import mindustry.entities.abilities.Ability;
import mindustry.gen.Unit;

/**
 * 效果能力（Ability 版）
 * <p>
 * <ul>
 *   <li><b>子弹</b>：进入范围的子弹立刻被附加效果（没有冷却，进范围即施加）；</li>
 *   <li><b>单位 / 建筑</b>：每 {@link #interval} 秒（默认 7 秒）给范围内符合 {@link #side} 的目标施加一次效果。</li>
 * </ul>
 * 区别：能力挂在单位类型上是<b>常驻</b>的，不需要反复施加状态。
 * <p>
 * 用法（把能力加进单位类型即可，例如在单位类型的定义里）：
 * <pre>
 * myUnit.abilities.add(new RaenEffectAbility(){{
 *     range = 160f;                           // 20 格（8 = 1 格）
 *     interval = 7f * 60f;                    // 7 秒一次
 *     side = RaenEffect.Side.ENEMY;           // 只对敌方单位/建筑
 *     bulletSide = RaenEffect.Side.FRIENDLY;  // 只给自己人的子弹附加
 *     targets = RaenEffect.Targets.ALL;       // 要检测的目标类型（不吃子弹就选 UNITS_BUILDINGS，省性能）
 *     drawRange = true;                       // 画范围圈（两个反向旋转的虚线圆）
 *     add(RaenEffectJY.INSTANCE, 1);
 *     add(RaenEffectZD.INSTANCE, 2);
 * }});
 * </pre>
 * 能力实例是「每个单位一份」（UnitType 会把 abilities 复制给每个单位），
 * 所以冷却计时用实例字段就够了；暂停时 {@code update} 不会被调用，计时跟着暂停。
 */
public class RaenEffectAbility extends Ability {

    /** 要施加的效果组合：(附加:[效果A，1层；效果B，2层]) */
    public RaenEffect.Combo effects = RaenEffect.combo();
    /** 作用半径（世界单位，8 = 1 格） */
    public float range = 160f;
    /** 对单位 / 建筑施加的冷却（tick，420 = 7 秒） */
    public float interval = 7f * 60f;

    /** 单位 / 建筑的目标筛选（相对拥有者队伍） */
    public RaenEffect.Side side = RaenEffect.Side.ENEMY;
    /** 子弹筛选（一般只给自己人的子弹） */
    public RaenEffect.Side bulletSide = RaenEffect.Side.FRIENDLY;
    /**
     * 要检测的目标类型。不检测的类型会<b>整段跳过</b>对应的遍历
     * （子弹四叉树查询 / 单位查询 / 建筑格子扫描），所以只想要一部分就选对应的值。
     * 例：只吃子弹 → {@code RaenEffect.Targets.BULLETS}；只打单位与建筑 → {@code UNITS_BUILDINGS}
     */
    public RaenEffect.Targets targets = RaenEffect.Targets.ALL;
    /** 是否绘制范围圈（两个反向旋转的虚线圆） */
    public boolean drawRange = true;

    /** 冷却计时（每单位一份） */
    private float timer = 0f;

    public RaenEffectAbility() {
        RaenEffectBase.init();
    }

    public RaenEffectAbility(RaenEffect effect, int stacks) {
        this();
        add(effect, stacks);
    }

    /** 追加要施加的效果与层数 */
    public RaenEffectAbility add(RaenEffect effect, int stacks) {
        effects.add(effect, stacks);
        return this;
    }

    /** 追加任意多个效果（每个 1 层） */
    public RaenEffectAbility add(RaenEffect... list) {
        effects.add(list);
        return this;
    }

    @Override
    public void update(Unit unit) {
        if (unit == null || unit.dead() || unit.team == null) return;
        if (effects.isEmpty()) return;

        // ① 子弹：进范围就施加（没有冷却）；不检测子弹时这段遍历完全不执行
        if (targets.bullets) {
            RaenEffectBase.markBulletsIn(unit.team, unit.x, unit.y, range, effects, bulletSide);
        }

        // ② 单位 / 建筑：每 interval 一次；两者都不检测时连冷却计时都不用走
        if (!targets.units && !targets.buildings) return;

        timer += Time.delta;
        if (timer < interval) return;
        timer = 0f;

        RaenEffectBase.applyToTargetsIn(unit.team, unit.x, unit.y, range,
                effects, unit.team, targets.units, targets.buildings, side);
    }

    /** 范围圈：内圈 = 实际生效范围，外圈 = 内圈 +10px 装饰，两个虚线圆反向旋转 */
    @Override
    public void draw(Unit unit) {
        if (!drawRange || unit == null || unit.dead()) return;
        RaenEffectBase.drawAuraRange(unit.x, unit.y, range);
    }

    /** 文本说明 */
    public String describe() {
        return "给范围内的" +
                (bulletSide == RaenEffect.Side.ENEMY ? "敌方" : bulletSide == RaenEffect.Side.FRIENDLY ? "己方" : "所有") +
                "子弹附加：" + effects.describe() + "\n" +
                "每 " + (interval / 60f) + " 秒给半径 " + (int) (range / 8) + " 格内的" +
                (targets.units ? "单位" : "") + (targets.units && targets.buildings ? "与" : "") +
                (targets.buildings ? "建筑" : "") + "施加同样的效果" +
                (side == RaenEffect.Side.ENEMY ? "（只对敌方）" : side == RaenEffect.Side.FRIENDLY ? "（只对己方）" : "（不分队伍）");
    }
}
