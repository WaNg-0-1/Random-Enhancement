package raen.Bullet;

import arc.struct.ObjectMap;
import arc.util.Time;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 效果状态（StatusEffect 版）
 * <p>
 *
 * <ul>
 *   <li><b>子弹</b>：进入范围的子弹立刻被附加效果（没有冷却，进范围即施加）；</li>
 *   <li><b>单位 / 建筑</b>：每 {@link #interval} 秒（默认 7 秒）给范围内符合 {@link #side} 的目标施加一次效果。</li>
 * </ul>
 * 也就是「周期性给范围内的一切施加效果」，和 {@link RaenApplyBlock} 的领域是同一套效果。
 * <p>
 * 用法（在内容类里创建）：
 * <pre>
 * 光环 = new RaenEffectStatus("光环"){{
 *     range = 160f;                        // 20 格（8 = 1 格）
 *     interval = 7f * 60f;                 // 7 秒一次
 *     side = RaenEffect.Side.ENEMY;        // 只对敌方单位/建筑
 *     bulletSide = RaenEffect.Side.FRIENDLY; // 只给自己人的子弹附加
 *     targets = RaenEffect.Targets.ALL;    // 要检测的目标类型（不吃子弹就选 UNITS_BUILDINGS，省性能）
 *     drawRange = true;                    // 画范围圈（两个反向旋转的虚线圆）
 *     add(RaenEffectJY.INSTANCE, 1);
 *     add(RaenEffectZD.INSTANCE, 2);
 * }};
 * // 再对单位施加：unit.apply(光环, 600f);
 * </pre>
 * 注意：状态是<b>共享实例</b>（一种状态一个对象被所有单位使用），所以冷却计时按单位分开存；
 * 暂停时 {@code update} 不会被调用，计时自然跟着暂停。
 */
public class RaenEffectStatus extends StatusEffect {

    /** 要施加的效果组合：(附加:[效果A，1层；效果B，2层]) */
    public RaenEffect.Combo effects = RaenEffect.combo();
    /** 作用半径（世界单位，8 = 1 格） */
    public float range = 160f;
    /** 对单位 / 建筑施加的冷却（tick，420 = 7 秒） */
    public float interval = 7f * 60f;

    /** 单位 / 建筑的目标筛选（相对持有者队伍） */
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

    /** 持有者 → { 冷却计时 }；状态实例是共享的，所以必须按单位存 */
    private final ObjectMap<Unit, float[]> timers = new ObjectMap<>();

    public RaenEffectStatus(String name) {
        super(name);
        // 只负责施加效果，本身不造成伤害
        damage = 0f;
        transitionDamage = 0f;
        permanent = false;
        RaenEffectBase.init();
    }

    /** 追加要施加的效果与层数 */
    public RaenEffectStatus add(RaenEffect effect, int stacks) {
        effects.add(effect, stacks);
        return this;
    }

    /** 追加任意多个效果（每个 1 层） */
    public RaenEffectStatus add(RaenEffect... list) {
        effects.add(list);
        return this;
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        if (unit == null || unit.dead() || unit.team == null) return;
        if (effects.isEmpty()) return;

        // ① 子弹：进范围就施加（没有冷却）；不检测子弹时这段遍历完全不执行
        if (targets.bullets) {
            RaenEffectBase.markBulletsIn(unit.team, unit.x, unit.y, range, effects, bulletSide);
        }

        // ② 单位 / 建筑：每 interval 一次；两者都不检测时连冷却计时都不用走
        if (!targets.units && !targets.buildings) return;

        float[] timer = timers.get(unit);
        if (timer == null) {
            timer = new float[]{0f};
            timers.put(unit, timer);
        }

        timer[0] += Time.delta;
        if (timer[0] < interval) return;
        timer[0] = 0f;

        RaenEffectBase.applyToTargetsIn(unit.team, unit.x, unit.y, range,
                effects, unit.team, targets.units, targets.buildings, side);
    }

    /** 范围圈：内圈 = 实际生效范围，外圈 = 内圈 +10px 装饰，两个虚线圆反向旋转 */
    @Override
    public void draw(Unit unit) {
        drawAura(unit);
    }

    /**
     * 兼容不同版本的绘制回调：有的版本是 {@code draw(Unit)}，有的是 {@code draw(Unit, float)}。
     * 这里刻意不加 {@code @Override}，目标版本里没有这个重载时它就只是一个普通方法，编译照样通过。
     */
    public void draw(Unit unit, float time) {
        drawAura(unit);
    }

    private void drawAura(Unit unit) {
        if (!drawRange || unit == null || unit.dead()) return;
        RaenEffectBase.drawAuraRange(unit.x, unit.y, range);
    }

    @Override
    public void onRemoved(Unit unit) {
        if (unit != null) timers.remove(unit);
    }

    /**
     * 文本说明。想显示在状态详情里就自己接上，例如：
     * {@code description = 光环.describe();}
     */
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
