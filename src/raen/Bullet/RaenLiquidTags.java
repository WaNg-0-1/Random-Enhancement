package raen.Bullet;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.content.Liquids;
import mindustry.content.StatusEffects;
import mindustry.gen.Bullet;
import mindustry.type.Liquid;
import mindustry.Vars;

import java.util.Set;

/**
 * 液体标签注册表。
 * <p>
 * 负责三件事：
 * <ol>
 *   <li>把 v160 的 10 种原版液体登记成 {@link RaenLiquidTag}（水 / 矿渣 / 油 / 冷却液 /
 *       瘤液 / 芳油 / 臭氧 / 氢 / 氮 / 氰），各自带一个<b>额外效果</b>；</li>
 *   <li>提供「子弹带了哪种液体」的读写（<b>按液体名承载</b>，不是位掩码）；</li>
 *   <li>生成详情文案（方块详情页与「标签」数据库共用）。</li>
 * </ol>
 * <p>
 * <b>为什么用液体名而不是位掩码</b>：位掩码（{@code long}）最多 64 种，而需求是
 * <b>所有液体都能用</b> —— 原版 11 种（含镓）+ 各模组自己加的液体，种类数不固定。
 * 改成「标记名 = {@code raenLiq} + 液体名」后，任意液体都能挂到子弹上；
 * 液体名由引擎保证全局唯一（{@code ContentLoader} 会拒绝重名），
 * 且<b>不依赖 mod 的加载顺序</b>（内容 id 会变，名字不会）。
 * <p>
 * 液体标签只会被 {@link RaenApplyBlock} 写到子弹上（每 10 单位液体挂一次），
 * 命中结算全部在 {@link RaenLiquidHit} 里完成。
 * <p>
 * ⚠ 液体<b>不提供能量</b>：装载液体不会提升方块的能量、范围、主标签解锁与物品标签上限。
 * ⚠ 液体<b>不提供四属性</b>（爆炸 / 燃烧 / 放射 / 放电）—— 那是物品的机制。
 */
public class RaenLiquidTags {

    /**
     * 液体标签前缀：完整标记 = {@code raenLiq} + 液体名（如 {@code raenLiqwater}）。
     * <p>
     * 用小写原样的液体名，是为了让「标记 ↔ 液体」双向可推，不必再维护一张对照表。
     */
    public static final String TAG_PREFIX = "raenLiq";

    /** 有「额外效果」的液体定义（目前是原版 10 种），下标即顺序 */
    private static final Seq<RaenLiquidTag> DEFS = new Seq<>();
    /** 液体 → 定义（<b>只有</b>有额外效果的液体在里面） */
    private static final ObjectMap<Liquid, RaenLiquidTag> BY_LIQUID = new ObjectMap<>();

    private static boolean initialized = false;

    private RaenLiquidTags() {
    }

    /** 注册全部液体定义（可重复调用，只会执行一次） */
    public static void init() {
        if (initialized) return;
        // 液体内容还没加载（base content 未就绪）时先不登记，等下次调用再试 ——
        // 否则会拿一堆 null 液体去登记，后面全错。
        if (Liquids.water == null) return;
        initialized = true;

        // ==================== 10 种原版液体 ====================
        // 基础效果对**所有**液体都有：子弹命中时在命中处生成该液体。
        // 下面只声明各自的「额外效果」。

        // 水：额外附带 3 秒潮湿
        def(Liquids.water, "water", "596AB8")
                .soak(StatusEffects.wet, 3f);

        // 矿渣：额外附带 3 秒融化
        def(Liquids.slag, "slag", "FFA166")
                .soak(StatusEffects.melting, 3f);

        // 油：额外附带 3 秒油浸
        def(Liquids.oil, "oil", "313131")
                .soak(StatusEffects.tarred, 3f);

        // 冷却液：额外附带 3 秒冻结
        def(Liquids.cryofluid, "cryofluid", "6ECDEC")
                .soak(StatusEffects.freezing, 3f);

        // 瘤液：额外附带 5 秒弱化（原版液体本身没有 status）
        def(Liquids.neoplasm, "neoplasm", "C33E2B")
                .soak(StatusEffects.sapped, 5f);

        // 芳油：命中叠层，每层 5% 概率施加 2 秒腐蚀
        def(Liquids.arkycite, "arkycite", "84A94B")
                .corrode(2f, 0.05f);

        // 臭氧：命中附带 3% 子弹伤害、4 格范围伤害
        def(Liquids.ozone, "ozone", "FC81DD")
                .area(4f, 0.03f);

        // 氢：命中点燃 5 格范围内目标（参考硫，不额外积焰）
        def(Liquids.hydrogen, "hydrogen", "9EABF7")
                .ignite(5f);

        // 氮：命中施加 2 秒冻结和 1 秒静止
        def(Liquids.nitrogen, "nitrogen", "EFE3FF")
                .freeze(2f, 1f);

        // 氰：参考玻璃，命中处生成碎片（伤害 5%，完全穿甲）
        def(Liquids.cyanogen, "cyanogen", "89E8B6")
                .shards(0.05f);
    }

    /**
     * 登记一条定义，并写入索引。
     * <p>
     * 液体不提供任何四属性（爆炸 / 燃烧 / 放射 / 放电）—— 那是物品的机制。
     */
    private static RaenLiquidTag def(Liquid liquid, String key, String color) {
        RaenLiquidTag def = new RaenLiquidTag(liquid, key, color);
        DEFS.add(def);
        BY_LIQUID.put(liquid, def);
        return def;
    }

    // ==================== 查询 ====================

    /** 全部定义（只读，调用方不要修改）。<b>只含</b>有额外效果的液体。 */
    public static Seq<RaenLiquidTag> all() {
        init();
        return DEFS;
    }

    /** 液体的定义，null = 没有额外效果（但照样能挂载、照样命中生成液体） */
    public static RaenLiquidTag def(Liquid liquid) {
        if (liquid == null) return null;
        init();
        return BY_LIQUID.get(liquid);
    }

    /** 该液体有没有「额外效果」定义 */
    public static boolean hasExtra(Liquid liquid) {
        return def(liquid) != null;
    }

    // ==================== 子弹标记（按液体名承载） ====================

    /** 液体的子弹标记名：{@code raenLiq} + 液体名 */
    public static String tagOf(Liquid liquid) {
        return liquid == null ? null : TAG_PREFIX + liquid.name;
    }

    /**
     * 从标记名反查液体。
     *
     * @return null = 不是液体标记 / 对应液体已不存在
     */
    public static Liquid liquidOfTag(String tag) {
        if (tag == null || !tag.startsWith(TAG_PREFIX)) return null;

        String name = tag.substring(TAG_PREFIX.length());
        // 容忍「标签名*层数」的写法（液体标记本身不带层数，但别的方块可能改写）
        int cut = name.indexOf(RaenBulletMarks.STACK_SEPARATOR);
        if (cut > 0) name = name.substring(0, cut);
        if (name.isEmpty() || Vars.content == null) return null;

        return Vars.content.liquid(name);
    }

    /**
     * 读取子弹身上携带的液体。
     *
     * @return null = 子弹没有液体标签
     */
    public static Liquid liquidOf(Bullet bullet) {
        if (bullet == null) return null;

        Object data = bullet.data;
        if (data instanceof Set){
            for (Object raw : (Set<?>)data){
                if (!(raw instanceof String)) continue;
                Liquid liquid = liquidOfTag((String)raw);
                if (liquid != null) return liquid;
            }
            return null;
        }
        if (data instanceof String){
            return liquidOfTag((String)data);
        }
        return null;
    }

    /** 子弹是否已经携带了液体标签（单颗子弹最多一个液体标签） */
    public static boolean hasLiquid(Bullet bullet) {
        return liquidOf(bullet) != null;
    }

    // ==================== 文案 ====================

    /** 全部液体的本地化名字，用「/」连成一串（方块详情页的「只能放入…」用） */
    public static String localizedNameList() {
        init();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < DEFS.size; i++) {
            if (i > 0) builder.append('/');
            builder.append(DEFS.get(i).name());
        }
        return builder.toString();
    }

    /** 全部液体标签的清单文本（方块详情页用） */
    public static String describeAll() {
        init();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < DEFS.size; i++) {
            if (i > 0) builder.append('\n');
            builder.append(DEFS.get(i).describe());
        }
        return builder.toString();
    }
}
