package raen.Bullet;

import arc.Core;
import mindustry.type.Item;

/**
 * 物品标签定义。
 * <p>
 * 一种物品对应一条标签，标签会写到子弹上（见 {@link RaenBulletMarks}），
 * 命中时由 {@link RaenItemHit} 统一结算：
 * <ul>
 *   <li>{@link #explosive} / {@link #flammable} / {@link #radioactive} / {@link #conductive}
 *       —— 爆炸性 / 燃烧性 / 放射性 / 放电性，取自原版物品数据；</li>
 *   <li>{@link #damageAdd} / {@link #damageMultiplier} —— 获得标签时一次性修正子弹伤害；</li>
 *   <li>{@link #homing} / {@link #glassShard} —— 特殊行为开关（追踪 / 延迟碎片）；</li>
 *   <li>其余的一次性/命中行为按 {@link #item} 在 {@link RaenItemHit} 里分支处理。</li>
 * </ul>
 * 文案全部走本地化：{@code raen.item.<key>.name} / {@code raen.item.<key>.effect}，
 * key 存在字段 {@link #key} 里（例如 copper、phasefabric）。
 */
public class RaenItemTag {

    /** 对应的原版物品 */
    public final Item item;
    /** 本地化 key 的物品段（copper / phasefabric …），决定 raen.item.{key}.xxx */
    public final String key;
    /** 子弹标签名：唯一，定下后不要改 */
    public final String tag;
    /** 详情页配色（十六进制，不带 #） */
    public final String color;

    // ==================== 四属性 ====================
    // 默认值由 RaenItemTags.def() 从原版物品数据里读取：
    // explosiveness / flammability / radioactivity / charge
    /** 爆炸性（原版 Item.explosiveness） */
    public float explosive;
    /** 燃烧性（原版 Item.flammability） */
    public float flammable;
    /** 放射性（原版 Item.radioactivity） */
    public float radioactive;
    /** 放电性（原版 Item.charge） */
    public float conductive;

    // ==================== 伤害修正（获得标签时结算一次） ====================
    /** 固定加伤 */
    public float damageAdd;
    /** 伤害倍率（1 = 不变） */
    public float damageMultiplier = 1f;

    // ==================== 特殊行为 ====================
    /** 是否为追踪弹（硅） */
    public boolean homing;
    /** 追踪搜索半径（格） */
    public float homingRangeTiles = 24f;
    /** 获得标签后是否延迟生成碎片子弹（玻璃） */
    public boolean glassShard;

    public RaenItemTag(Item item, String key, String tag, String color) {
        this.item = item;
        this.key = key;
        this.tag = tag;
        this.color = color;
    }

    // ==================== 文案（全部走本地化） ====================

    /** 本地化显示名 */
    public String name() {
        return Core.bundle.get("raen.item." + key + ".name");
    }

    /** 本地化行为文案 */
    public String effectText() {
        return Core.bundle.get("raen.item." + key + ".effect");
    }

    // ==================== 配置 ====================

    /** 设置四属性（一般由 RaenItemTags 从原版数据填好，这里留作运行时覆盖） */
    public RaenItemTag attr(float explosive, float flammable, float radioactive, float conductive) {
        this.explosive = explosive;
        this.flammable = flammable;
        this.radioactive = radioactive;
        this.conductive = conductive;
        return this;
    }

    /** 设置伤害修正：固定加伤 + 倍率 */
    public RaenItemTag damage(float add, float multiplier) {
        this.damageAdd = add;
        this.damageMultiplier = multiplier;
        return this;
    }

    /** 变为追踪弹 */
    public RaenItemTag homing(float rangeTiles) {
        this.homing = true;
        this.homingRangeTiles = rangeTiles;
        return this;
    }

    /** 变为「获得标签后延迟生成碎片」 */
    public RaenItemTag glass() {
        this.glassShard = true;
        return this;
    }

    /** 是否没有任何四属性（纯数值 / 纯行为物品） */
    public boolean noAttribute() {
        return explosive <= 0f && flammable <= 0f && radioactive <= 0f && conductive <= 0f;
    }

    /**
     * 一行详情，例如：
     * {@code [#D98A5A]铜[]：子弹伤害 +3　[lightgray](爆 0 / 燃 0 / 辐 0 / 电 0.15)[]}
     */
    public String describe() {
        String suffix = noAttribute() ? "" : Core.bundle.format("raen.item.attrSuffix",
                fmt(explosive), fmt(flammable), fmt(radioactive), fmt(conductive));
        return Core.bundle.format("raen.item.describe", color, name(), effectText(), suffix);
    }

    /** 去掉多余小数位：1.00 → 1，0.15 → 0.15 */
    private static String fmt(float value) {
        if (value == (int) value) return String.valueOf((int) value);
        String text = String.format("%.2f", value);
        while (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        if (text.endsWith(".")) text = text.substring(0, text.length() - 1);
        return text;
    }
}
