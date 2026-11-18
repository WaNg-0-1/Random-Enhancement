package raen.Bullet;

import arc.Core;
import mindustry.type.Liquid;
import mindustry.type.StatusEffect;

/**
 * 液体标签定义。
 * <p>
 * 一种液体对应一条标签，标签会写到子弹上（见 {@link RaenBulletMarks}），
 * 命中时由 {@link RaenLiquidHit} 统一结算：
 * <ul>
 *   <li><b>所有液体</b>：子弹命中时在命中处生成该液体（与原版液体子弹一致）；</li>
 *   <li>{@link Kind#soak} —— 对被命中的目标施加一个状态（潮湿 / 融化 / 油浸 / 冻结 / 弱化）；</li>
 *   <li>{@link Kind#corrode} —— 目标身上叠层，每层提供概率触发一次腐蚀；</li>
 *   <li>{@link Kind#areaDamage} —— 命中处造成范围伤害；</li>
 *   <li>{@link Kind#ignite} —— 点燃范围内的敌方目标（参考物品「硫」）；</li>
 *   <li>{@link Kind#freeze} —— 目标同时被冻结与静止；</li>
 *   <li>{@link Kind#shards} —— 命中处生成一枚碎片（参考物品「玻璃」）。</li>
 * </ul>
 * 文案全部走本地化：{@code raen.liquid.<key>.name} / {@code raen.liquid.<key>.effect}，
 * key 存在字段 {@link #key} 里（例如 water、neoplasm）。
 */
public class RaenLiquidTag {

    /** 液体的额外效果类型 */
    public enum Kind {
        /** 只生成液体，没有额外效果 */
        none,
        /** 对目标施加一个状态 */
        soak,
        /** 目标叠层，每层概率触发腐蚀 */
        corrode,
        /** 命中处范围伤害 */
        areaDamage,
        /** 点燃范围内的敌方目标 */
        ignite,
        /** 目标：冻结 + 静止 */
        freeze,
        /** 命中处生成碎片 */
        shards
    }

    /** 对应的原版液体 */
    public final Liquid liquid;
    /** 本地化 key 的液体段（water / neoplasm …），决定 raen.liquid.{key}.xxx */
    public final String key;
    /** 详情页配色（十六进制，不带 #） */
    public final String color;

    /**
     * 子弹标签名：{@code raenLiq} + 液体名。
     * <p>
     * ⚠ <b>不要写死</b> —— 液体名由 {@link #liquid} 决定，
     * 这样任意液体（包括 mod 加的）都能自动得到唯一标记。
     */
    public String tag() {
        return RaenLiquidTags.tagOf(liquid);
    }

    // ==================== 额外效果 ====================
    /** 额外效果类型 */
    public Kind kind = Kind.none;
    /** {@link Kind#soak} 施加的状态（null = 用液体自带的 effect） */
    public StatusEffect soakEffect;
    /** 状态持续时间（秒） */
    public float seconds = 3f;
    /** 范围类效果的半径（格） */
    public float radiusTiles = 4f;
    /** 范围伤害占子弹伤害的比例（0.03 = 3%） */
    public float damageRatio;
    /** 碎片伤害占子弹伤害的比例 */
    public float shardRatio;
    /** {@link Kind#corrode} 每层提供的触发概率（0.05 = 每层 5%） */
    public float chancePerStack = 0.05f;
    /** {@link Kind#corrode} 的层数上限 */
    public int maxStacks = 100;
    /** {@link Kind#freeze} 里「静止」的持续时间（秒） */
    public float unmovingSeconds = 1f;

    public RaenLiquidTag(Liquid liquid, String key, String color) {
        this.liquid = liquid;
        this.key = key;
        this.color = color;
    }

    // ==================== 文案（全部走本地化） ====================

    /** 本地化显示名 */
    public String name() {
        return Core.bundle.get("raen.liquid." + key + ".name");
    }

    /** 本地化行为文案 */
    public String effectText() {
        return Core.bundle.get("raen.liquid." + key + ".effect");
    }

    // ==================== 配置 ====================

    /** 设置额外效果类型 */
    public RaenLiquidTag kind(Kind kind) {
        this.kind = kind;
        return this;
    }

    /** 设置状态效果与持续时间（秒） */
    public RaenLiquidTag soak(StatusEffect effect, float seconds) {
        this.kind = Kind.soak;
        this.soakEffect = effect;
        this.seconds = seconds;
        return this;
    }

    /** 叠层 + 每层概率触发腐蚀 */
    public RaenLiquidTag corrode(float seconds, float chancePerStack) {
        this.kind = Kind.corrode;
        this.seconds = seconds;
        this.chancePerStack = chancePerStack;
        return this;
    }

    /** 命中处范围伤害：半径（格）+ 伤害占子弹伤害的比例 */
    public RaenLiquidTag area(float radiusTiles, float damageRatio) {
        this.kind = Kind.areaDamage;
        this.radiusTiles = radiusTiles;
        this.damageRatio = damageRatio;
        return this;
    }

    /** 点燃范围内的敌方目标：半径（格） */
    public RaenLiquidTag ignite(float radiusTiles) {
        this.kind = Kind.ignite;
        this.radiusTiles = radiusTiles;
        return this;
    }

    /** 冻结 + 静止 */
    public RaenLiquidTag freeze(float freezeSeconds, float unmovingSeconds) {
        this.kind = Kind.freeze;
        this.seconds = freezeSeconds;
        this.unmovingSeconds = unmovingSeconds;
        return this;
    }

    /** 命中处生成碎片：伤害占子弹伤害的比例 */
    public RaenLiquidTag shards(float shardRatio) {
        this.kind = Kind.shards;
        this.shardRatio = shardRatio;
        return this;
    }

    /** 一行详情，例如：{@code [#596AB8]水[]：命中处生成水　[lightgray]目标潮湿 3 秒[]} */
    public String describe() {
        return Core.bundle.format("raen.liquid.describe", color, name(), effectText());
    }
}
