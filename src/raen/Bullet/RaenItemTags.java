package raen.Bullet;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.content.Items;
import mindustry.gen.Bullet;
import mindustry.type.Item;

import java.util.Set;

/**
 * 物品标签注册表。
 * <p>
 * 负责三件事：
 * <ol>
 *   <li>把原版物品登记成 {@link RaenItemTag}（铜 / 铅 / 钛 / 钍 / 硅 / 玻璃 …），
 *       并给每条标签分配一个<b>位序号</b>（0~63），因此「子弹身上有哪些物品标签」
 *       可以压成一个 {@code long} 位掩码，判定一次遍历就够；</li>
 *   <li>提供 {@link #maskOf(Bullet)} 读取子弹上的物品标签、{@link #sums(long, float[])}
 *       把四属性分别求和；</li>
 *   <li>生成详情文案（方块详情页与「标签」数据库共用）。</li>
 * </ol>
 * 物品标签只会被 {@link RaenApplyBlock} 写到子弹上（每种物品独立计 50 发），
 * 命中结算全部在 {@link RaenItemHit} 里完成。
 */
public class RaenItemTags {

    /** 标签位宽上限（long 位掩码） */
    public static final int MAX = 64;

    /** 全部定义，下标 = 位序号 */
    private static final Seq<RaenItemTag> DEFS = new Seq<>();
    /** 物品 → 位序号 */
    private static final ObjectMap<Item, Integer> ITEM_INDEX = new ObjectMap<>();
    /** 标签名 → 位序号 */
    private static final ObjectMap<String, Integer> TAG_INDEX = new ObjectMap<>();

    private static boolean initialized = false;

    private RaenItemTags() {
    }

    /** 注册全部物品标签（可重复调用，只会执行一次） */
    public static void init() {
        if (initialized) return;
        initialized = true;

        // ==================== 20 种物品 ====================
        // 四属性不写死：def() 直接取原版 Item 的
        // explosiveness / flammability / radioactivity / charge，与原版数据永远一致。
        // 显示名与行为文案走本地化：raen.item.<key>.name / .effect
        def(Items.copper, "copper", "raenItCopper", "D98A5A")
                .damage(3f, 1f).instant();

        def(Items.lead, "lead", "raenItLead", "8E93A8").spawns();

        def(Items.titanium, "titanium", "raenItTitanium", "7A9BD6")
                .damage(5f, 1.05f).instant();

        def(Items.thorium, "thorium", "raenItThorium", "F573B8")
                .damage(7f, 1.10f).instant();

        def(Items.silicon, "silicon", "raenItSilicon", "6E7B8B")
                .homing(24f);

        def(Items.metaglass, "metaglass", "raenItGlass", "8FC3D6")
                .glass().spawns();

        def(Items.graphite, "graphite", "raenItGraphite", "9AA5B1");

        def(Items.plastanium, "plastanium", "raenItPlastanium", "C7D4A8").spawns();

        def(Items.phaseFabric, "phasefabric", "raenItPhase", "F0D8A8")
                .instant();

        def(Items.surgeAlloy, "surgealloy", "raenItSurge", "F3E979").spawns();

        def(Items.sporePod, "sporepod", "raenItSpore", "9A7BA8")
                .instant();

        def(Items.pyratite, "pyratite", "raenItPyratite", "FFAA5F");

        def(Items.blastCompound, "blastcompound", "raenItBlast", "FF795E");

        def(Items.coal, "coal", "raenItCoal", "4A4A4A");

        def(Items.sand, "sand", "raenItSand", "F7CFA0");

        def(Items.scrap, "scrap", "raenItScrap", "A8A8A8")
                .instant();

        def(Items.beryllium, "beryllium", "raenItBeryllium", "6FAE9A")
                .damage(8f, 1.045f).instant();

        def(Items.tungsten, "tungsten", "raenItTungsten", "7C8794")
                .damage(9f, 1.075f).instant();

        def(Items.oxide, "oxide", "raenItOxide", "F0E0A0");

        def(Items.carbide, "carbide", "raenItCarbide", "A8B0C0")
                .damage(10f, 1.125f).instant();
    }

    /**
     * 登记一条定义，并写入位序号。
     * <p>
     * 四属性<b>直接对齐原版物品数据</b>：{@code explosiveness} / {@code flammability} /
     * {@code radioactivity} / {@code charge}（放电性）。这里一次性抄好，
     * 之后想改单件物品的手感可以直接改 {@link RaenItemTag} 的字段（例如在 init 里再 .attr(...) 覆盖）。
     */
    private static RaenItemTag def(Item item, String key, String tag, String color) {
        RaenItemTag def = new RaenItemTag(item, key, tag, color);
        int index = DEFS.size;
        if (index >= MAX) throw new IllegalStateException("物品标签超过 " + MAX + " 种，位掩码装不下");

        // 对齐原版：爆炸性 / 燃烧性 / 放射性 / 放电性
        // （注：属性值是在这里写入的，所以不要指望「注册之后再 .attr(...)」能影响 sums()，
        //   后者读的就是定义里的这四个字段 —— 想覆盖就在下一行之后改 def.xxx）
        def.explosive = item.explosiveness;
        def.flammable = item.flammability;
        def.radioactive = item.radioactivity;
        def.conductive = item.charge;

        DEFS.add(def);
        ITEM_INDEX.put(item, index);
        TAG_INDEX.put(tag, index);
        return def;
    }

    // ==================== 查询 ====================

    /** 全部定义（只读，调用方不要修改） */
    public static Seq<RaenItemTag> all() {
        init();
        return DEFS;
    }

    /** 物品对应的位序号，-1 = 没有定义 */
    public static int indexOf(Item item) {
        if (item == null) return -1;
        init();
        Integer index = ITEM_INDEX.get(item);
        return index == null ? -1 : index;
    }

    /** 物品对应的定义，null = 没有定义 */
    public static RaenItemTag def(Item item) {
        int index = indexOf(item);
        return index < 0 ? null : DEFS.get(index);
    }

    /**
     * 这种物品的标签能否挂到「瞬间命中」类子弹上（激光 / 射线 / 霰射光束）。
     * <p>
     * 这类子弹本体停在炮口、{@code bullet.x/y} 就是炮口，所以只放行「纯加伤 / 纯状态」的物品 ——
     * 只要会生成东西（碎片、闪电、墙）、会在命中处做范围效果、或会改弹道（追踪），一律 false。
     * 判定字段是 {@link RaenItemTag#instantSafe}（由 {@code init()} 里的 {@code .instant()} 标记）。
     */
    public static boolean instantSafe(Item item) {
        RaenItemTag def = def(item);
        return def != null && def.instantSafe;
    }

    /**
     * 这种物品的标签是否会「生成新东西」（铅 / 塑钢 / 玻璃 / 合金）。
     * <p>
     * 分裂弹（{@code fragBullet} / {@code intervalBullet}）不挂这类标签，碎片也不继承这类标签，
     * 否则会指数级滚雪球（见 {@link RaenItemTag#generates}）。
     */
    public static boolean generates(Item item) {
        RaenItemTag def = def(item);
        return def != null && def.generates;
    }

    /** 按标签名取定义（自动忽略 {@code *层数} 后缀），null = 不是物品标签 */
    public static RaenItemTag byTag(String tag) {
        if (tag == null || tag.isEmpty()) return null;
        init();

        Integer index = TAG_INDEX.get(tag);
        if (index == null) {
            // 带层数的写法：raenItCopper*3
            int cut = tag.indexOf(RaenBulletMarks.STACK_SEPARATOR);
            if (cut <= 0) return null;
            index = TAG_INDEX.get(tag.substring(0, cut));
        }
        return index == null ? null : DEFS.get(index);
    }

    /**
     * 读取子弹身上所有物品标签，压成位掩码。
     * <p>
     * 只遍历一遍 {@code bullet.data}，不会产生垃圾；没装标签的子弹直接返回 0。
     */
    public static long maskOf(Bullet bullet) {
        if (bullet == null) return 0L;
        init();

        Object data = bullet.data;
        if (data instanceof Set) {
            long mask = 0L;
            for (Object raw : (Set<?>) data) {
                if (!(raw instanceof String)) continue;
                long bit = bitOf((String) raw);
                if (bit != 0L) mask |= bit;
            }
            return mask;
        }
        if (data instanceof String) {
            return bitOf((String) data);
        }
        return 0L;
    }

    /** 单个标签名 → 位 */
    private static long bitOf(String raw) {
        Integer index = TAG_INDEX.get(raw);
        if (index == null) {
            int cut = raw.indexOf(RaenBulletMarks.STACK_SEPARATOR);
            if (cut <= 0) return 0L;
            index = TAG_INDEX.get(raw.substring(0, cut));
        }
        return index == null ? 0L : 1L << index;
    }

    /** 该位是否置位 */
    public static boolean has(long mask, Item item) {
        int index = indexOf(item);
        return index >= 0 && (mask & (1L << index)) != 0L;
    }

    /**
     * 把位掩码里的四属性分别相加，写进 {@code out}：
     * {@code out[0]=爆炸性 B、out[1]=燃烧性 C、out[2]=放射性 F、out[3]=放电性 D}。
     */
    public static void sums(long mask, float[] out) {
        out[0] = 0f;
        out[1] = 0f;
        out[2] = 0f;
        out[3] = 0f;

        int index = 0;
        long bits = mask;
        while (bits != 0L) {
            if ((bits & 1L) != 0L && index < DEFS.size) {
                // 直接读定义本身（不要预先抄成数组：属性是在 def() 之后的 .attr() 里才填的）
                RaenItemTag def = DEFS.get(index);
                out[0] += def.explosive;
                out[1] += def.flammable;
                out[2] += def.radioactive;
                out[3] += def.conductive;
            }
            bits >>>= 1;
            index++;
        }
    }

    /** 位掩码里有几种物品标签 */
    public static int count(long mask) {
        return Long.bitCount(mask);
    }

    // ==================== 文案 ====================

    /** 全部物品的本地化名字，用「/」连成一串（方块详情页的「只能放入…」用） */
    public static String localizedNameList() {
        init();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < DEFS.size; i++) {
            if (i > 0) builder.append('/');
            builder.append(DEFS.get(i).name());
        }
        return builder.toString();
    }

    /** 全部物品标签的清单文本（方块详情页用） */
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
