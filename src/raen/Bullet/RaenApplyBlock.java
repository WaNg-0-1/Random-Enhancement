package raen.Bullet;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.ui.Image;
import arc.struct.IntSeq;
import arc.struct.ObjectFloatMap;
import arc.struct.ObjectIntMap;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import arc.scene.ui.layout.Table;
import arc.scene.style.TextureRegionDrawable;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.entities.TargetPriority;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.bullet.ContinuousBulletType;
import mindustry.entities.bullet.LaserBulletType;
import mindustry.entities.bullet.LightningBulletType;
import mindustry.entities.bullet.PointBulletType;
import mindustry.entities.bullet.PointLaserBulletType;
import mindustry.entities.bullet.RailBulletType;
import mindustry.entities.bullet.SapBulletType;
import mindustry.entities.bullet.ShrapnelBulletType;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.game.Team;
import mindustry.game.EventType.Trigger;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Category;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.ui.Styles;
import mindustry.world.Block;
import mindustry.world.draw.DrawBlock;
import mindustry.world.draw.DrawDefault;
import mindustry.world.meta.BlockGroup;
import mindustry.world.meta.BuildVisibility;
import mindustry.world.meta.Env;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;
import raen.Bullet.effect.RaenEffectDH;
import raen.Bullet.effect.RaenEffectJY;
import raen.Bullet.effect.RaenEffectPZ;
import raen.Bullet.effect.RaenEffectSS;
import raen.Bullet.effect.RaenEffectZD;
import raen.Bullet.effect.RaenEffectZJ;
import raen.Bullet.effect.RaenEffectZR;

/**
 * 施加方块：把「物品标签」挂到经过范围的己方子弹上。
 * <p>
 * <b>消耗模型（160.3.5 重做）</b>
 * <ul>
 *   <li>不再是「按时间扣物品」。现在是 <b>1 个物品 = {@link #shotsPerItem} 发子弹</b>，
 *       每挂一次标签就消耗 1 发；</li>
 *   <li><b>每种物品独立计算</b>：箱子里的铜用完了，不影响铅还在给子弹加层；
 *       某一种打空时才会真正从仓库里扣掉 1 个该物品，再开新的一份；</li>
 *   <li>子弹挂在它身上的物品标签种类越多，命中时的四属性（爆炸 / 燃烧 / 放射 / 放电）就越强，
 *       具体结算见 {@link RaenItemHit}；</li>
 *   <li>同一发子弹对同一种物品只吃一次（标签已存在就跳过），因此「每发 1 次」是精确的。</li>
 * </ul>
 * <b>能量</b> = 装载的物品<b>种类数</b> × {@link #energyPerItem}（只看种类，不看数量），
 * 它决定作用范围、耗电、主标签解锁，以及<b>每发子弹能带几种物品标签</b>：
 * <ul>
 *   <li>范围 = {@link #baseRangeTiles} + {@link #rangePerEnergyTiles} × 能量；</li>
 *   <li>主标签按 {@link #mainLadder} 逐档解锁 —— <b>0 能量（刚放下、没装东西）就自带追击</b>，
 *       子弹经过时挂 1 层追击标签，命中照常触发追击效果；之后 2 / 4 / 6 / 8 能量依次是
 *       破绽 / 积焰 / 酸蚀 / 注入，已解锁的全部挂 1 层；</li>
 *   <li>物品标签数量按 {@link #itemTagCaps} 限流：1 能量每发 1 种、4 能量 2 种、7 能量 3 种；</li>
 *   <li>能量 {@link #powerStartEnergy} 起耗电；</li>
 *   <li>能量 {@link #weakenStartEnergy} 起，<b>范围与挂标签范围完全相同</b>
 *       （都取 {@link RaenApplyBlockBuild#rangePx()}，随能量实时加减）：
 *       对进入范围的敌方子弹每秒削 {@link #weakenPerSecond} 的伤害，下限 {@link #weakenFloor}；</li>
 *   <li>只接受有物品标签定义的物品（见 {@link RaenItemTags}），其它物品一格都收不了。</li>
 * </ul>
 */
public class RaenApplyBlock extends Block {

    /** 初始作用半径（格）。 */
    public float baseRangeTiles = 6f;
    /** 每 1 点能量追加的半径（格）。 */
    public float rangePerEnergyTiles = 2f;

    /** 1 个物品被消耗后补充的施加次数。 */
    public int shotsPerItem = 50;

    /**
     * 每种物品的次数上限 = 5 个物品的量（5 × 50 = 250）。
     * 另外还能以「物品」的形态存 {@link #itemCapacity} 个（15 个），所以每种物品总共能存 20 个。
     */
    public int maxChargesPerItem = 250;

    /** 每种物品（种类）提供的能量。 */
    public int energyPerItem = 1;

    /**
     * <b>每种</b>液体的容量上限（单位）—— 直接写引擎的 {@link Block#liquidCapacity}，
     * 在构造器里赋值（默认 {@link #LIQUID_CAPACITY}）。
     * <p>
     * ⚠ <b>不要在本类里声明同名字段</b>：{@code Block} 自己就有 {@code public float liquidCapacity = 10f}，
     * 子类再声明一个同名的会把父类字段<b>遮蔽</b>（hide，不是 override）——
     * 引擎/UI 全是按 {@code Block} 类型读的，读到的永远是父类那份 10，
     * 于是「液体容量 2500」只在模组自己的代码里成立，游戏里照样显示/限制为 10。
     * <p>
     * ⚠ 引擎的语义是<b>每种液体各存这么多</b>，不是所有液体加起来：
     * {@code LiquidModule} 内部是 {@code float[] liquids}，按 {@code liquid.id} 各自独立计数
     * （见 {@code BuildingComp.transferLiquid}：{@code next.block.liquidCapacity - next.liquids.get(liquid)}）。
     * <p>
     * 液体<b>不提供能量</b> —— 装载液体不会提升范围、主标签解锁与物品标签上限。
     */
    public static final float LIQUID_CAPACITY = 2500f;

    /**
     * 每多少单位液体可以挂一次液体标签（5 = 每 5 单位液体挂一次）。
     * <p>
     * 单颗子弹<b>最多携带一个</b>液体标签：已经带了的子弹不会再被挂上别的液体。
     */
    public float liquidUnitsPerTag = 5f;

    /**
     * 每种液体的<b>次数上限</b>（默认 250，与物品的 {@link #maxChargesPerItem} 同构）。
     * <p>
     * 液体也走「次数池 + 仓库存量」两层结构（和物品一样）：
     * <ul>
     *   <li><b>液体仓</b>：每种最多 {@link #liquidCapacity} 单位（默认 2500），
     *       每 {@link #liquidUnitsPerTag} 单位（默认 5）折算成 1 次；</li>
     *   <li><b>次数池</b>：每种最多 {@link #maxChargesPerLiquid} 次。</li>
     * </ul>
     * 所以每种液体最多能挂 <b>250（次数池）+ 2500 ÷ 5（仓库存量）= 750 次</b> 液体标签
     * —— 与物品的 250 + 15 × 50 = 1000 次同一套算法。
     */
    public int maxChargesPerLiquid = 250;

    /** 从多少能量开始耗电。 */
    public int powerStartEnergy = 4;
    /** 超过起始能量后每 1 点能量每秒耗电。 */
    public float powerPerEnergy = 30f;

    /**
     * 敌方子弹削弱：能量达到这个值才启用。
     * <p>
     * <b>作用范围就是 {@link #rangePx()} 算出来的那个圆</b>（与己方挂标签完全同一个），
     * 所以能量一变、范围立刻跟着变，不存在第二个独立半径。
     */
    public int weakenStartEnergy = 5;
    /** 敌方子弹每秒被削掉的伤害比例（0.03 = 按初始伤害每秒 -3%）。 */
    public float weakenPerSecond = 0.03f;
    /** 削弱下限：伤害不会低于初始伤害的这个比例（0.5 = 50%）。 */
    public float weakenFloor = 0.5f;

    /**
     * 「白送的塑钢」标记（氧化物 10% 附带）。只是记账用：
     * {@link RaenApplyBlockBuild#attachItems} 算名额占用时会把它从 bitCount 里扣掉，
     * 所以这颗塑钢不会挤占 {@link #itemTagCaps} 的正式名额。命中结算完全走塑钢本身。
     */
    public static final String FREE_PLAST = "raenFreePlastanium";

    /**
     * 「这发子弹的物品标签已经被某个领域抽过」的标记。
     * <p>
     * 多个领域重叠时，子弹只在<b>第一个</b>领域那里抽一次物品标签，之后所有领域直接跳过：
     * 既不重复 roll、也不重复扣次数，白送的塑钢也不会被后来的领域「买」成正式的。
     * 主标签不受影响（它不耗次数、`attach` 幂等），仍然可以被更高级的领域补上。
     */
    public static final String APPLIED = "raenFieldApplied";

    /** 氧化物：获得标签时白送一个「不占名额」塑钢标签的概率 */
    public float oxideFreePlastChance = 0.10f;

    /** 敌方子弹削弱的载体标签：层数 = 初始伤害 × {@link #WEAKEN_SCALE} */
    public static final String WEAKEN = "raenWeaken";
    /** 伤害 → 标签层数的放大倍数（0.01 的精度） */
    public static final float WEAKEN_SCALE = 100f;


    /**
     * 主效果阶梯：能量达到 {@link MainTag#energy} 就解锁对应主标签，0 = 刚放下就有。
     * <p>
     * 默认：0 →追击，2 →破绽，4 →积焰，6 →酸蚀，8 →注入。
     * 中毒 / 转命 / 连锁不在这里，它们由物品标签提供（放射性 / 布 / 合金）。
     * 已解锁的主标签<b>全部挂 1 层</b>（命中时各施加 1 层）。
     */
    public Seq<MainTag> mainLadder = Seq.with(
            new MainTag(0, RaenEffectZJ.INSTANCE),
            new MainTag(2, RaenEffectPZ.INSTANCE),
            new MainTag(4, RaenEffectJY.INSTANCE),
            new MainTag(6, RaenEffectSS.INSTANCE),
            new MainTag(8, RaenEffectZR.INSTANCE));

    /**
     * 物品标签数量上限阶梯：能量达到 {@link ItemTagCap#energy} 后，
     * 每发子弹最多能带几种物品标签（默认 1 能量→1 种、4 能量→2 种、7 能量→3 种）。
     * <p>
     * 超出上限的部分不再挂；多发子弹之间会随机挑，所以混装时所有物品都能轮到。
     */
    public Seq<ItemTagCap> itemTagCaps = Seq.with(
            new ItemTagCap(1, 1),
            new ItemTagCap(4, 2),
            new ItemTagCap(7, 3));

    /**
     * 物品标签数量上限的<b>最小~最大</b>值（供 UI 文案用）。
     * <p>
     * 数据库对话框的「物品标签」说明要写「每发最多带 N~M 种」，
     * 直接读 {@link #itemTagCaps} 推导，改参数时文案自动跟着变。
     * <p>
     * ⚠ 做成<b>静态</b>方法：UI 侧不能在拿不到方块实例时 {@code new RaenApplyBlock(...)} ——
     * Block 的构造器会自动把方块登记进 {@code Vars.content.blocks()}，会凭空多出一个 content 条目。
     */
    public static int[] itemTagLimitRange(Seq<ItemTagCap> caps){
        int min = Integer.MAX_VALUE, max = 0;
        if (caps != null){
            for (int i = 0; i < caps.size; i++){
                ItemTagCap cap = caps.get(i);
                if (cap == null) continue;
                min = Math.min(min, cap.count);
                max = Math.max(max, cap.count);
            }
        }
        // 拿不到阶梯时退回默认的 1~3（与 itemTagCaps 的初始值一致）
        if (min == Integer.MAX_VALUE){ min = 1; max = 3; }
        return new int[]{min, max};
    }

    /** 解锁随机主标签所需能量；&lt; 0 表示关闭（默认关闭）。 */
    public int randomUnlockTypes = -1;

    /**
     * 「必带物品」选择功能的解锁门槛：每发子弹能带几种物品标签时开放选择（默认 2）。
     * <p>
     * 能带 2 种以上标签时，指定其中 1 种「必然附带」才有意义 —— 只带 1 种的话本来就必然是它。
     * <p>
     * ★ 这个功能<b>只针对物品</b>：液体一律随机挂载，不提供指定。
     */
    public int guaranteeUnlockTags = 2;
    /** 必带物品图标：相对方块边长的尺寸（3 格方块 0.7 → 约 17px） */
    public float guaranteeIconScale = 0.7f;
    /** 必带「已选但当前不生效」（断电 / 名额不足）时的图标透明度 */
    public float guaranteeIconInactiveAlpha = 0.35f;

    /**
     * 随机主标签池里排除掉的标签：中毒（{@code raensdPoison}）、转命（{@code raenZm}）、
     * 连锁（{@code raenlsChain}）都已经改为物品标签，不再作为主标签随机发放。
     */
    public Seq<String> randomExclude = Seq.with("raensdPoison", "raenZm", "raenlsChain");

    /** 一条主效果解锁规则 */
    public static class MainTag {
        /** 需要的能量（0 = 初始档，空载也有） */
        public final int energy;
        /** 主标签效果（随机档里会被逐帧改写） */
        public RaenEffect effect;
        /**
         * 挂载层数。默认 1 = 挂裸标签，命中时施加 1 层；
         * 想一次给多层就填 &gt;1，标签会写成 {@code 标签名*层数}。
         */
        public int stacks;

        public MainTag(int energy, RaenEffect effect) {
            this(energy, effect, 1);
        }

        public MainTag(int energy, RaenEffect effect, int stacks) {
            this.energy = energy;
            this.effect = effect;
            this.stacks = stacks;
        }
    }

    /** 物品标签数量上限规则 */
    public static class ItemTagCap {
        /** 达到多少能量生效 */
        public final int energy;
        /** 每发子弹最多带几种物品标签 */
        public final int count;

        public ItemTagCap(int energy, int count) {
            this.energy = energy;
            this.count = count;
        }
    }

    /** 领域颜色 */
    public Color fieldColor = Color.valueOf("6b9eff");
    /** 边框颜色 */
    public Color waveColor = fieldColor;
    /** 中心互反三角形：每多少能量多绘制一组（一组 = 正向 + 反向各一个空心等边三角形） */
    public int triangleEnergyStep = 4;
    /** 中心互反三角形最多组数（8 能量 = 2 组，再高不增加） */
    public int triangleMaxGroups = 3;
    /** 中心三角形基础半径 */
    public float triangleRadius = 6f;
    /** 每多一组三角形，半径变为上一组的倍数（1.75 = 每组是上一组的 1.75 倍） */
    public float triangleRadiusScale = 1.75f;
    /** 中心三角形旋转速度（度/帧），第 g 组速度 = 此值 × (1 + g × triangleSpeedStep) */
    public float triangleRotateSpeed = 0.5f;
    /** 每组三角形速度递增系数 */
    public float triangleSpeedStep = 0.35f;
    /** 中心三角形线宽 */
    public float triangleStroke = 1.05f;
    /** 三角形初始颜色（蓝） */
    public Color triangleColorStart = Color.valueOf("5fa8ff");
    /** 三角形满能量颜色（红） */
    public Color triangleColorEnd = Color.valueOf("ff5b5b");
    /** 颜色由蓝转红对应的能量上限 */
    public int triangleColorFullEnergy = 8;

    // ==================== 断电警告（装饰） ====================
    /** 断电警告闪电颜色 */
    public Color powerWarningColor = Color.valueOf("ff4030");
    /** 断电警告的闪烁间隔（秒）：每 3 秒闪一次 */
    public float powerWarningIntervalSeconds = 3f;
    /** 单次闪烁的持续时长（秒）：每轮亮这么久，最后 0.2 秒淡出 */
    public float powerWarningFlashSeconds = 1f;
    /**
     * 警告图标尺寸（格，1 = 8 像素）。默认 1 格：3×3 的方块上大约占 1/3 宽。
     * <p>
     * ⚠ 不要再用 {@code block.size * 8f * 系数} 那种写法：方块尺寸一改图标就跟着失控。
     */
    public float powerWarningSizeTiles = 1f;
    /** 断电警告淡出时长（秒）：闪烁末尾这么长时间里透明度降到 0 */
    private static final float FADE_SECONDS = 0.2f;

    /** 是否绘制中心互反三角形 */
    public boolean drawShape = true;

    /** 内圈光点轨道半径（格），以方块中心为圆心 */
    public float orbitInnerRadiusTiles = 3f;
    /** 外圈光点轨道相对范围圈的比例 */
    public float orbitOuterRatio = 0.95f;
    /** 光点组旋转角速度（度/帧），内外两点同角速度 */
    public float orbitRotateSpeed = 0.35f;
    /** 每组光点投掷间隔（秒） */
    public float orbitThrowInterval = 5f;
    /** 投掷时在两光点间画闪电的概率 */
    public float orbitLightningChance = 0.25f;
    /** 闪电触发的随机延迟范围（秒），避免所有组同时触发：delay ∈ [min, max) */
    public float orbitLightningDelayMin = 1f;
    public float orbitLightningDelayMax = 2f;
    /** 光点半径 */
    public float orbitDotRadius = 1.68f;
    /** 范围圈旋转角速度（度/帧），低速 */
    public float rangeRotateSpeed = 0.08f;

    /**
     * 方块绘制器：本体 + 液体 + 能层装饰（见 {@link RaenBlocks#load()} 的 DrawMulti 装配）。
     * 覆写 {@link #load()} / {@link #loadIcon()} 让本体贴图按模组 atlas key 加载。
     */
    public DrawBlock drawer = new DrawDefault();

    /** 本体贴图在 atlas 里的 key（模组贴图自动带 raen- 前缀） */
    private static final String BODY_REGION = "raen-applyField";

    @Override
    public void load() {
        super.load();
        region = Core.atlas.find(BODY_REGION, region);
        drawer.load(this);
    }

    @Override
    public void loadIcon() {
        super.loadIcon();
        region = Core.atlas.find(BODY_REGION, region);
        fullIcon = uiIcon = region;
    }

    /**
     * 给所有「施加领域」画范围圈。
     * <p>
     * 画在方块自己的 {@code draw()} 里会被渲染剔除（本体离屏时整块不画，圈就没了），
     * 所以统一在全局 {@code Trigger.draw} 里遍历（注册见 {@link RaenBlocks#load()}）。
     * <p>
     * 范围是<b>圆形</b>：命中判定走 {@code dst2}，所以这里也必须画圆，不能用方框。
     */
    public static void drawAllRanges() {
        if (Vars.state == null || !Vars.state.isGame() || Vars.world == null) return;

        for (Building build : Groups.build) {
            if (build instanceof RaenApplyBlockBuild && build.isValid()) {
                ((RaenApplyBlockBuild) build).drawRange();
            }
        }
    }

    /**
     * 是否是「瞬间命中」类子弹 —— 子弹本体永远停在炮口，命中判定由特效 / 范围伤害完成。
     * <p>
     * 这类子弹的 {@code bullet.x/y} 就是<b>炮口</b>，不是真正被打中的位置，所以按标签种类分别处理：
     * <ul>
     *   <li><b>主标签照挂</b> —— 9 个效果全部按 {@code ctxTarget} 结算，
     *       {@code effect/} 包里不出现任何 {@code ctxX/ctxY}，所以与炮口坐标无关；</li>
     *   <li><b>物品标签 / 四属性 / 液体不挂</b> —— 它们都是「在命中处<b>生成</b>东西」
     *       （铅的 0 速子弹、塑钢 / 玻璃碎片、合金闪电、液态生成），命中处 = 炮口 ⇒ 全堆在炮口；</li>
     *   <li><b>持续型连主标签也不挂</b> —— 频率问题（12 次/秒），见 {@link #isContinuousType}。</li>
     * </ul>
     * 实际后果：这些炮塔只有在<b>开火位置</b>落进范围内才会被挂上标签（普通子弹是「飞过就挂」），
     * 这正是它们与常规子弹唯一的区别。
     * <p>
     * 覆盖的炮塔（v160 实测的子弹类，见各条注释）：
     * <ul>
     *   <li>{@link LaserBulletType} —— 蓝瑟、<b>魔灵</b>的二段激光（外壳是 FlakBulletType 的 fragBullet）；</li>
     *   <li>{@link ContinuousBulletType}（连续激光 / 连续火焰）—— 融毁、升华；</li>
     *   <li>{@link PointLaserBulletType} —— 光辉（速度 0、5 tick 结算一次）；</li>
     *   <li>{@link ShrapnelBulletType} —— 雷光；</li>
     *   <li>{@link RailBulletType} —— 厄兆；</li>
     *   <li>{@link PointBulletType}、{@link LightningBulletType}、{@link SapBulletType} —— 其它瞬时射线类；</li>
     *   <li>兜底：<b>速度为 0 且不继承炮口速度</b>的子弹（任何模组加的「原地」子弹都归这一类）。</li>
     * </ul>
     * ⚠ 只影响「挂标签」：敌方子弹的削弹（{@code weakenEnemyBullet}）照旧 —— 它改的是 {@code damage}，
     * 与命中点无关，对连续激光同样有效。
     */
    public static boolean isInstantHitType(BulletType type) {
        if (type == null) return false;
        if (type instanceof ContinuousBulletType) return true;
        if (type instanceof LaserBulletType) return true;
        if (type instanceof PointLaserBulletType) return true;
        if (type instanceof ShrapnelBulletType) return true;
        if (type instanceof RailBulletType) return true;
        if (type instanceof PointBulletType) return true;
        if (type instanceof LightningBulletType) return true;
        if (type instanceof SapBulletType) return true;
        // 兜底（故意写得严格，避免误伤「速度 0 但靠 keepVelocity 继承炮口速度」的自定义子弹）：
        // 自身速度为 0 + 不继承炮口速度 ⇒ 这发子弹永远停在它出生的地方
        return type.speed <= 0f && !type.keepVelocity;
    }

    /**
     * 是否<b>持续型</b>瞬间命中子弹（每 {@code damageInterval} 结算一次伤害，默认 5 tick = 12 次/秒）。
     * <p>
     * 与单发型的区别只在<b>触发频率</b>：融毁 / 升华是 {@link ContinuousBulletType}，
     * 光辉的 {@link PointLaserBulletType} 也是 5 tick 结算一次（{@code speed = 0}、{@code lifetime = 20}，
     * 一发子弹本身就要结算 4 次）。所以它们连主标签都不能挂 ——
     * 追击 / 积焰 / 酸蚀 / 破绽 全是「命中叠层」，会被放大 4~12 倍。
     */
    public static boolean isContinuousType(BulletType type) {
        return type instanceof ContinuousBulletType || type instanceof PointLaserBulletType;
    }

    public RaenApplyBlock(String name) {
        super(name);

        buildType = RaenApplyBlockBuild::new;
        hasPower = true;
        hasItems = true;
        itemCapacity = 15;
        // 液体：可以输入多种液体（不限制种类），但液体不提供能量
        // ★ 必须写父类（Block）的 liquidCapacity —— 引擎按 Block 类型读，别在本类里声明同名字段（会遮蔽成 10）
        hasLiquids = true;
        liquidCapacity = LIQUID_CAPACITY;
        // 点击方块弹出「剩余 + 物品列表」面板（buildConfiguration）
        configurable = true;
        update = true;
        destructible = true;
        hasConsumers = true;
        envEnabled = Env.any;
        canOverdrive = false;
        group = BlockGroup.none;
        priority = TargetPriority.base;
        buildVisibility = BuildVisibility.shown;
        health = 12000000;
        size = 1;

        consumePowerDynamic(this::powerNeed);
        requirements(Category.effect, ItemStack.with());

        RaenItemTags.init();
        RaenLiquidTags.init();
        RaenEffectBase.init();
    }

    /**
     * <b>接受所有液体</b>（不限种类）。
     * <p>
     * 原版 {@code Block.consumesLiquid} 读的是 {@code liquidFilter}（默认全 false），
     * 所以这里直接覆写为「全收」——原版 11 种液体（含镓）以及其它模组加的液体都能倒进来。
     * <p>
     * 液体的<b>额外效果</b>只对 {@link RaenLiquidTags} 里登记过的那些有；
     * 没登记的液体照样能被装载、挂到子弹上、命中时在命中处生成（基础效果），
     * 只是没有额外效果 —— 见 {@link RaenLiquidHit}。
     */
    @Override
    public boolean consumesLiquid(Liquid liquid) {
        return true;
    }

    /**
     * 每秒耗电：能量到 {@link #powerStartEnergy} 才开始，每多 1 点 +{@link #powerPerEnergy}。
     * <p>
     * ⚠ <b>单位坑（v160.4.02 修过）</b>：{@code consumePowerDynamic} 收的是「每 tick」的引擎单位，
     * 而 {@link #powerPerEnergy} 是按<b>每秒</b>写的 —— 必须 /60 再返回。
     * 之前直接返回 30，引擎按每 tick 30 算 ⇒ 4 种物品时需求 30×60 = <b>1800/秒</b>（设计值 30/秒）。
     * 依据：{@code ConsumePower.display()} 里就是 {@code usage * 60f} 配 {@code powerSecond} 单位。
     */
    private float powerNeed(Building build) {
        int energy = ((RaenApplyBlockBuild) build).energy();
        if (energy < powerStartEnergy) return 0f;
        return (energy - powerStartEnergy + 1) * powerPerEnergy / 60f;
    }

    /** 当前能量下每发子弹最多带几种物品标签（0 = 一种都挂不上）。 */
    private int itemTagLimit(int energy) {
        int best = 0;
        for (int i = 0; i < itemTagCaps.size; i++) {
            ItemTagCap cap = itemTagCaps.get(i);
            if (cap == null) continue;
            if (energy >= cap.energy && cap.count > best) best = cap.count;
        }
        return best;
    }

    @Override
    public void setStats() {
        stats.add(Stat.range, baseRangeTiles, StatUnit.blocks);

        int perItem = Math.max(1, shotsPerItem);
        int itemsPerCharge = maxChargesPerItem / perItem;
        RaenItemHit hit = RaenItemHit.INSTANCE;

        StringBuilder builder = new StringBuilder();
        builder.append(Core.bundle.format("raen.block.stats.onlyItems", RaenItemTags.localizedNameList())).append('\n');
        builder.append(Core.bundle.format("raen.block.stats.consume", shotsPerItem)).append('\n');
        builder.append(Core.bundle.format("raen.block.stats.capacity",
                maxChargesPerItem, itemsPerCharge, itemCapacity, itemsPerCharge + itemCapacity, shotsPerItem)).append('\n');
        builder.append(Core.bundle.format("raen.block.stats.energy", energyPerItem)).append('\n');
        builder.append(Core.bundle.format("raen.block.stats.range", fmt(baseRangeTiles), fmt(rangePerEnergyTiles))).append('\n');
        builder.append(Core.bundle.format("raen.block.stats.power", powerStartEnergy, (int) powerPerEnergy,
                Math.max(0, powerStartEnergy - 1))).append('\n');
        builder.append(Core.bundle.get("raen.block.stats.mainsHeader")).append('\n');

        for (int i = 0; i < mainLadder.size; i++) {
            MainTag tag = mainLadder.get(i);
            if (tag == null || tag.effect == null) continue;
            String at = tag.energy <= 0
                    ? Core.bundle.get("raen.block.stats.initial")
                    : Core.bundle.format("raen.block.stats.energyValue", tag.energy);
            builder.append("  · ")
                    .append(Core.bundle.format("raen.block.stats.mainsEntry", at, tag.effect.text().title()))
                    .append('\n');
        }
        if (randomUnlockTypes >= 0) {
            builder.append("  · ")
                    .append(Core.bundle.format("raen.block.stats.random", randomUnlockTypes))
                    .append('\n');
        }

        builder.append(Core.bundle.get("raen.block.stats.capsHeader")).append('\n');
        for (int i = 0; i < itemTagCaps.size; i++) {
            ItemTagCap cap = itemTagCaps.get(i);
            if (cap == null) continue;
            builder.append("  · ")
                    .append(Core.bundle.format("raen.block.stats.capsEntry", cap.energy, cap.count))
                    .append('\n');
        }
        // 多领域重叠：物品标签只由第一个领域抽一次（APPLIED 标记）
        builder.append("  · ").append(Core.bundle.get("raen.block.stats.applied")).append('\n');

        builder.append('\n').append(Core.bundle.get("raen.block.stats.weakenHeader")).append('\n');
        builder.append("  · ").append(Core.bundle.format("raen.block.stats.weaken",
                        weakenStartEnergy, fmt(weakenPerSecond * 100f), fmt(weakenFloor * 100f)))
                .append('\n');
        // 瞬间命中类子弹不挂标签（命中点 = 炮口），规则见 isInstantHitType
        builder.append("  · ").append(Core.bundle.get("raen.block.stats.instant")).append('\n');

        builder.append('\n').append(Core.bundle.get("raen.block.stats.attrHeader")).append('\n');
        builder.append("  · ").append(Core.bundle.format("raen.attr.explode",
                fmt(hit.explodeRadiusPerPoint), fmt(hit.explodeFlatPerPoint),
                fmt(hit.explodePercentPerPoint * 100f))).append('\n');
        builder.append("  · ").append(Core.bundle.format("raen.attr.ignite",
                fmt(hit.igniteRadiusPerPoint), fmt(hit.igniteSeconds))).append('\n');
        builder.append("  · ").append(Core.bundle.get("raen.attr.radioactive")).append('\n');
        builder.append("  · ").append(Core.bundle.format("raen.attr.charge", fmt(hit.chargeTagChance))).append('\n');
        builder.append(Core.bundle.get("raen.block.stats.itemsHeader")).append('\n');
        builder.append(RaenItemTags.describeAll()).append("[]");
        builder.append('\n');
        // 必带物品：解锁门槛跟着 itemTagCaps 走，所以写「能带 N 种标签时」
        builder.append(Core.bundle.get("raen.block.stats.guaranteedHeader")).append('\n');
        builder.append(Core.bundle.format("raen.block.stats.guaranteedEntry", guaranteeUnlockTags)).append('\n');
        builder.append(Core.bundle.get("raen.block.stats.liquidsHeader")).append('\n');
        // 参数：次数池上限、液体仓上限、仓能折算的次数、总次数、每次消耗的单位
        int unitsPerTag = Math.max(1, (int)liquidUnitsPerTag);
        int stockShots = (int)(liquidCapacity / liquidUnitsPerTag);
        builder.append(Core.bundle.format("raen.block.stats.liquidsEntry",
                maxChargesPerLiquid, (int)liquidCapacity, stockShots,
                maxChargesPerLiquid + stockShots, unitsPerTag)).append('\n');
        builder.append(RaenLiquidTags.describeAll()).append("[]");

        stats.add(Stat.abilities, builder.toString());
        super.setStats();
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid) {
        // 实际生效范围是圆形（子弹判定走 dst2），所以预览也画圆
        float range = baseRangeTiles * 8f;
        float wx = x * 8 + offset;
        float wy = y * 8 + offset;
        Draw.color(valid ? Pal.accent : Pal.surge, 0.15f);
        Fill.circle(wx, wy, range);
        Draw.color();
        Drawf.dashCircle(wx, wy, range, valid ? Pal.accent : Pal.surge);
    }

    /** 建筑本体 */
    public class RaenApplyBlockBuild extends Building {

        /** 当前装载的物品（有存量，或膛内还有剩余发数） */
        private final Seq<Item> stocked = new Seq<>();
        /** 每种物品「当前这份」还剩多少发（打空即扣 1 个物品再开一份） */
        private final ObjectIntMap<Item> charges = new ObjectIntMap<>();
        /** 玻璃：待生成的延迟碎片 */
        private final Seq<PendingShard> pending = new Seq<>();
        /** 复用容器，避免每帧 new */
        private final Seq<MainTag> active = new Seq<>();
        private final Seq<RaenEffectBase> pool = new Seq<>();
        /** 随机档的临时条目（effect 逐帧改写） */
        private final MainTag randomEntry = new MainTag(0, null);
        /** 复用：本轮可挂标签的候选物品 */
        private final Seq<Item> candidates = new Seq<>();
        /** 复用：当前有液体的种类（不含单位数） */
        private final Seq<Liquid> liquidKinds = new Seq<>();
        /** 复用：当前各液体的单位数 */
        private final ObjectFloatMap<Liquid> liquidAmounts = new ObjectFloatMap<>();
        /** 每种液体的「次数池」：还能挂几次液体标签（与物品的 charges 同构） */
        private final ObjectIntMap<Liquid> liquidCharges = new ObjectIntMap<>();
        /** UI 里选中的「必带」物品（<b>只能选 1 种</b>）：它必然挂到子弹上，剩下的名额随机 */
        private Item guaranteedItem = null;
        /** 面板顶部的「已选必带」图标行，选中变化时重建 */
        private Table guaranteedIcons;
        /** 每发子弹对本方块只判定一次属性挂标签用的去重标记 */
        private String rollMark;        /** 方块内物品的四属性合计（爆炸 / 燃烧 / 放射 / 放电），独立于子弹标签 */
        private final float[] attr = new float[4];
        /** 方块内物品的物品标签位掩码 */
        private long stockedMask = 0L;
        /** 中心三角形渐变色复用，避免每帧 new Color */
        private static final Color TMP_TRI_COLOR = new Color();
        /** 光点投掷：每组每周期只触发一次。存 cycle+1，0 表示该组还没触发过 */
        private final long[] lastThrowCycles = new long[32];

        /** 当前能量（= 装载的物品<b>种类</b>数 × {@link #energyPerItem}）。 */
        private int energy = 0;

        /** 当前能量：决定范围 / 耗电 / 主标签解锁 / 物品标签上限。 */
        public int energy() {
            return energy;
        }

        /**
         * 本帧<b>实际生效</b>的能量 —— 断电降级在这里。
         * <p>
         * 能量 ≥ {@link #powerStartEnergy} 时才需要通电；供电不足时不是整块停摆，而是
         * <b>降级为「{@code powerStartEnergy - 1} 能量」那档</b>继续工作（那一档本来就不耗电）：
         * 主标签按 3 能量解锁（追击 / 破绽）、物品标签上限按 3 能量算、范围也缩回 3 能量的范围、
         * 削弹（需要 5 能量 + 电）自然关闭。
         * <p>
         * ⚠ 纯客户端直接返回额定能量：{@code potentialEfficiency} 来自电力网，MP 客户端拿不到，
         * 否则有电时也会把范围圈画小。实际逻辑以服务端为准。
         */
        public int effectiveEnergy() {
            if (energy < powerStartEnergy) return energy;
            if (Vars.net.client()) return energy;
            return potentialEfficiency < 0.99f ? Math.max(0, powerStartEnergy - 1) : energy;
        }

        /** 当前装载的物品种类（供 {@link DrawFieldDeco} 循环取色）。 */
        public Seq<Item> stockedItems() {
            return stocked;
        }

        /**
         * 「必带物品」选择是否已解锁。
         * <p>
         * 门槛 = 每发子弹能带的物品标签数 ≥ {@link #guaranteeUnlockTags}（默认 2）。
         * <p>
         * 只带 1 种标签时本来就必然是它，指定没有意义；能带 2 种以上才需要「其中 1 种必然附带」。
         * 注意这里看的是<b>标签上限</b>（{@link #itemTagLimit(int)}），不是能量本身 ——
         * 能量阶梯（{@link #itemTagCaps}）和 {@link #energyPerItem} 变了，解锁时机自动跟着变。
         */
        private boolean guaranteeUnlocked() {
            // 用降级后的能量：断电时只有 1 个物品标签名额，「必带」自然就不该解锁
            return itemTagLimit(effectiveEnergy()) >= guaranteeUnlockTags;
        }

        /** 当前作用半径（像素）。断电时按降级后的能量算，圈会跟着变小。 */
        public float rangePx() {
            return (baseRangeTiles + rangePerEnergyTiles * effectiveEnergy()) * 8f;
        }

        // ==================== 装载刷新 ====================

        /** 重新统计装载：有剩余次数、或库存里还排着队的物品都算「装载中」。 */
        private void refresh() {
            stocked.clear();
            stockedMask = 0L;

            Seq<RaenItemTag> defs = RaenItemTags.all();
            for (int i = 0; i < defs.size; i++) {
                Item item = defs.get(i).item;
                int left = charges.get(item, 0);

                // 仓库空闲量大于「一次补充的量」才补：250 的仓库打掉 51 发（剩 199）时补一次
                if (items.get(item) > 0 && maxChargesPerItem - left > shotsPerItem) {
                    items.remove(item, 1);
                    left += shotsPerItem;
                    charges.put(item, left);
                }

                if (left > 0 || items.get(item) > 0) {
                    stocked.add(item);
                    stockedMask |= 1L << i;     // 定义下标 == 位序号
                }
            }
            energy = stocked.size * energyPerItem;
            // 四属性 = 方块内所有物品的四属性合计（与子弹最终带了哪些标签无关）
            RaenItemTags.sums(stockedMask, attr);
        }

        /** 本方块是否提供了至少一种四属性 */
        private boolean hasAttribute() {
            return attr[0] > 0f || attr[1] > 0f || attr[2] > 0f || attr[3] > 0f;
        }

        /**
         * 消耗 1 发次数（物品本身在「放进方块」那一刻就已经被消耗掉了，这里只扣次数）。
         *
         * @return false = 这种物品没有剩余次数，本次跳过
         */
        private boolean takeCharge(Item item) {
            int left = charges.get(item, 0);
            if (left <= 0) return false;
            charges.put(item, left - 1);
            return true;
        }

        /** 某种物品当前还剩多少发（剩余次数 + 库存里排队待转换的物品）。 */
        private int shotsLeft(Item item) {
            return Math.max(0, charges.get(item, 0)) + Math.max(0, items.get(item)) * shotsPerItem;
        }

        // ==================== 每帧 ====================

        @Override
        public void updateTile() {
            tickPending();
            refresh();
            refreshLiquids();

            int energy = effectiveEnergy();
            // 0 能量也要生效：方块放下就自带追击。
            // 能量 ≥ powerStartEnergy 但断电时不 return —— effectiveEnergy() 已把这一档降级成
            // 「powerStartEnergy - 1」，继续跑那部分不耗电的功能（v160.4.02 起）。

            // 下面这个 range 同时是「己方挂标签」和「削弱敌方子弹」的范围：
            // 每次 updateTile 都按当前能量重算，能量加减立即生效，不再有第二个半径。
            float range = rangePx();
            if (range <= 0f) return;

            collectActive(energy);
            final int limit = itemTagLimit(energy);

            final float r2 = range * range;
            Groups.bullet.intersect(x - range, y - range, range * 2f, range * 2f, bullet -> {
                if (bullet == null) return;
                if (bullet.dst2(x, y) > r2) return;

                // 敌方子弹：能量够就持续削伤害（每秒 -3%，最低到初始的 50%）
                // 用的就是上面那个 range / r2 —— 与挂标签范围同一份
                if (bullet.team != team) {
                    if (energy >= weakenStartEnergy) weakenEnemyBullet(bullet);
                    return;
                }
                // 碎片子弹不允许再挂任何标签（否则玻璃 / 塑钢会互相套娃）
                if (RaenBulletMarks.hasBulletMark(bullet, RaenShards.NO_TAG)) return;
                // 临时盾（布）：它不参与命中，挂标签纯浪费，而且它的 data 里存着护罩状态
                if (bullet.type == RaenShieldBullet.type) return;

                // 持续型瞬间命中子弹（融毁 / 升华 / 光辉）：每 5 tick 结算一次 = 12 次/秒，
                // 连主标签都不能挂（追击 / 积焰 / 酸蚀 / 破绽 会被放大 4~12 倍），见 isContinuousType
                if (isContinuousType(bullet.type)) return;

                // 已解锁的主标签全部挂 1 层（命中时各施加 1 层）
                attachMains(bullet);

                // 单发型瞬间命中子弹（蓝瑟 / 雷光 / 厄兆 / 魔灵二段激光）：主标签 + 纯加伤/纯状态类物品标签，
                // 四属性与液体跳过（它们都要在命中处「生成」，而命中处就是炮口）
                if (isInstantHitType(bullet.type)) {
                    attachItems(bullet, limit, true);
                    return;
                }

                // 四属性独立于标签：直接把本方块物品的合计写到子弹上（爆炸 / 点燃命中时用）
                if (hasAttribute()) {
                    RaenItemHit.writeAttributes(bullet, attr);
                    ensureMarker(bullet);
                    rollAttributeTags(bullet);
                }

                attachItems(bullet, limit, false);
                attachLiquid(bullet);
            });
        }

        /** 已解锁的主标签全部挂 1 层（{@code attach} 自带幂等，多帧重复调用不会叠层） */
        private void attachMains(Bullet bullet) {
            for (int i = 0; i < active.size; i++) {
                MainTag tag = active.get(i);
                if (tag.effect != null) tag.effect.attach(bullet, Math.max(1, tag.stacks));
            }
        }

        /** 保证命中时会被 {@link RaenItemHit} 接管（属性可能在没有物品标签时也要生效） */
        private void ensureMarker(Bullet bullet) {
            if (RaenBulletMarks.hasBulletMark(bullet, RaenItemHit.ID)) return;
            RaenEffectBase.markBullet(bullet, RaenItemHit.ID, 1);
        }

        /**
         * 四属性里「挂标签」的两种，在这里一次性判定：
         * <ul>
         *   <li>放电性 D → 1.25D 概率给子弹挂 1 层「电荷」标签（电荷按这发子弹的全伤害结算）；</li>
         *   <li>放射性 F → <b>每满 100% 必定挂 1 层毒，余数按概率再补 1 层</b>：
         *       60% → 60% 概率 1 层；100% → 1 层；120% → 1 层 + 20% 再 1 层；
         *       200% → 2 层；250% → 2 层 + 50% 再 1 层。</li>
         * </ul>
         * 每发子弹对本方块<b>只判定一次</b>（用位置标记 {@code raenRoll<pos>} 去重），
         * 否则子弹在领域里多待几帧就会把概率刷成必然触发。
         */
        private void rollAttributeTags(Bullet bullet) {
            // 倍率统一放在 RaenItemHit 上，详情文案与判定共用一份数据
            float chargeChance = attr[3] * RaenItemHit.INSTANCE.chargeTagChance;
            float poison = attr[2] * RaenItemHit.INSTANCE.poisonTagChance;
            if (chargeChance <= 0f && poison <= 0f) return;

            if (rollMark == null) {
                rollMark = "raenRoll" + (tile == null ? 0 : tile.pos());
            }
            if (RaenBulletMarks.hasBulletMark(bullet, rollMark)) return;
            RaenBulletMarks.addBulletMark(bullet, rollMark, 1);

            // 放电：单层概率挂
            if (chargeChance > 0f && Mathf.chance(Math.min(1f, chargeChance))) {
                RaenEffectDH.INSTANCE.attach(bullet, 1);
            }

            // 放射：满 100% 定量给层，余数按概率补 1 层
            if (poison > 0f) {
                int stacks = (int) poison;
                float rest = poison - stacks;
                if (rest > 0f && Mathf.chance(rest)) stacks++;
                if (stacks > 0) {
                    // 直接写层数（attach 是幂等的，只会挂 1 层，没法表达 2 层以上）
                    int current = RaenBulletMarks.getBulletMark(bullet, RaenEffectZD.ID);
                    if (stacks > current) RaenEffectBase.markBullet(bullet, RaenEffectZD.ID, stacks);
                }
            }
        }

        /**
         * 对进入范围的敌方子弹持续削弱：每秒削掉「初始伤害 × {@link #weakenPerSecond}」，
         * 但不会低于初始伤害的 {@link #weakenFloor}。
         * <p>
         * 初始伤害记在 {@link #WEAKEN} 标签的层数里（只写一次），
         * 所以子弹被多个领域削弱时，下限始终按它出厂伤害的 50% 算，而不是一路往下叠。
         */
        private void weakenEnemyBullet(Bullet bullet) {
            if (weakenPerSecond <= 0f) return;

            int encoded = RaenBulletMarks.getBulletMark(bullet, WEAKEN);
            float base;
            if (encoded > 0) {
                base = encoded / WEAKEN_SCALE;
            } else {
                base = bullet.damage;
                if (base <= 0f) return;                 // 没伤害的子弹不用管
                RaenBulletMarks.addBulletMark(bullet, WEAKEN, Math.round(base * WEAKEN_SCALE));
                // bullet.data 被别的方块占用时标签写不进去 —— 那就别削，
                // 否则「初始伤害」会一路跟着当前伤害掉，下限形同虚设
                if (RaenBulletMarks.getBulletMark(bullet, WEAKEN) <= 0) return;
            }

            float floor = base * Mathf.clamp(weakenFloor, 0f, 1f);
            if (bullet.damage <= floor) return;

            bullet.damage = Math.max(floor, bullet.damage - base * weakenPerSecond * Time.delta / 60f);
        }

        /**
         * 给子弹挂物品标签，受「物品标签数量上限」约束。
         * <p>
         * 上限按能量阶梯来（1 能量 1 种 / 4 能量 2 种 / 7 能量 3 种）。
         * <p>
         * <b>「必带」物品优先</b>：{@link #guaranteedItem} 选中的那 1 种先挂，
         * 剩下的名额（1 或 2 个）再从其余已装载物品里随机挑（这样混装时每种物品都能轮到，
         * 不会被定义顺序靠前的铜、铅一直霸占名额）。
         */
        /**
         * 挂物品标签。
         *
         * @param instant 这发子弹是「瞬间命中」类（本体停在炮口，命中处 = 炮口）⇒
         *                只允许带 {@link RaenItemTag#instantSafe 纯加伤 / 纯状态} 的物品，
         *                会生成东西（铅 / 塑钢 / 玻璃 / 合金）、会在命中处做范围效果
         *                （石墨 / 硫 / 爆混 / 煤 / 氧化物）、会改弹道（硅）的一律跳过
         */
        private void attachItems(Bullet bullet, int limit, boolean instant) {
            if (limit <= 0) return;

            // 这发子弹已经被某个领域抽过物品标签 ⇒ 后续领域直接跳过（见 APPLIED）
            if (RaenBulletMarks.hasBulletMark(bullet, APPLIED)) return;

            // 分裂弹（fragBullet / intervalBullet 产出的子弹）数量极大：劫难一发主弹 ≈ 40 发/秒，
            // 再给它挂「生成类」标签就会滚雪球（每发都产碎片 → 每片又是一次命中）。
            boolean frag = RaenShards.isFragSpawned(bullet.type);

            final int plastIndex = RaenItemTags.indexOf(Items.plastanium);
            long mask = RaenItemTags.maskOf(bullet);
            // 「氧化物白送的塑钢」不占名额：算占用时把它扣掉（否则它会顶掉一个正式名额）。
            // ★ 它只是「先垫上效果」——不阻止正式抽取再抽到塑钢（见下面两处的 convert）
            boolean freePlast = plastIndex >= 0
                    && (mask & (1L << plastIndex)) != 0L
                    && RaenBulletMarks.hasBulletMark(bullet, FREE_PLAST);
            int used = Long.bitCount(mask) - (freePlast ? 1 : 0);
            int room = limit - Math.max(0, used);
            if (room <= 0) return;

            /** 本次是否真的挂上了物品标签（挂了才打 APPLIED，空领域不会把子弹「锁死」） */
            boolean attached = false;

            // ① 必带物品：优先挂上（要在已装载且余额够时才生效）
            Item guaranteed = guaranteedItem;
            if (guaranteed != null && room > 0
                    && guaranteeUnlocked()                                  // 断电降级 / 名额不足时不允许「必带」
                    && (!instant || RaenItemTags.instantSafe(guaranteed))
                    && (!frag || !RaenItemTags.generates(guaranteed))) {
                int index = RaenItemTags.indexOf(guaranteed);
                boolean convert = freePlast && index == plastIndex;      // 必带恰好是那颗白送的塑钢
                if (index >= 0                              // 有物品标签定义
                    && ((mask & (1L << index)) == 0L || convert)   // 还没有这种标签（白送塑钢例外）
                    && stocked.contains(guaranteed)         // 已装载
                    && takeCharge(guaranteed)){             // 还有余额
                    if (convert) {
                        // 白送的塑钢被正式选中 ⇒ 转成占名额的正式标签（标签本身不变，只改记账）
                        RaenBulletMarks.removeBulletMark(bullet, FREE_PLAST);
                        freePlast = false;
                    } else {
                        applyItem(bullet, guaranteed);
                    }
                    mask = RaenItemTags.maskOf(bullet);     // applyItem 可能白送塑钢，重新读
                    freePlast = RaenBulletMarks.hasBulletMark(bullet, FREE_PLAST);
                    attached = true;
                    room--;
                }
            }

            // ② 其余名额随机
            candidates.clear();
            for (int i = 0; i < stocked.size; i++) {
                Item item = stocked.get(i);
                int index = RaenItemTags.indexOf(item);
                if (index < 0) continue;
                // 已经有这种标签就跳过 —— 例外：白送的塑钢仍参与抽取（抽中后转为占名额）
                if ((mask & (1L << index)) != 0L && !(freePlast && index == plastIndex)) continue;
                if (instant && !RaenItemTags.instantSafe(item)) continue;  // 瞬中弹只带纯加伤/纯状态类
                if (frag && RaenItemTags.generates(item)) continue;        // 分裂弹不带生成类标签
                candidates.add(item);
            }
            int count = Math.min(room, candidates.size);
            for (int i = 0; i < count; i++) {
                // 从剩余候选里随机取一个，取过的挪到前面去（洗牌式不重复抽取）
                int pick = i + Mathf.random(candidates.size - 1 - i);
                Item item = candidates.get(pick);
                candidates.set(pick, candidates.get(i));
                candidates.set(i, item);

                int index = RaenItemTags.indexOf(item);
                if (index < 0) continue;
                boolean convert = freePlast && index == plastIndex;
                // 上一发可能刚被氧化物「白送」了塑钢 ⇒ 这个候选已经有了，别白扣次数（白送的除外）
                if ((mask & (1L << index)) != 0L && !convert) continue;

                if (!takeCharge(item)) continue;
                if (convert) {
                    // 抽中了那颗白送的塑钢：占用名额、扣一次充能，但标签不变、效果仍只算一次
                    RaenBulletMarks.removeBulletMark(bullet, FREE_PLAST);
                    freePlast = false;
                } else {
                    applyItem(bullet, item);
                }
                mask = RaenItemTags.maskOf(bullet);   // applyItem 可能白送塑钢，重新读
                freePlast = RaenBulletMarks.hasBulletMark(bullet, FREE_PLAST);
                attached = true;
            }

            // ③ 打标记：这发子弹的物品标签已由本领域抽过 ⇒ 后续领域不再插手
            if (attached) RaenBulletMarks.addBulletMark(bullet, APPLIED, 1);
        }

        /** 真正挂标签：总开关 + 物品标签 + 一次性伤害修正 + 特殊行为。 */
        private void applyItem(Bullet bullet, Item item) {
            RaenItemTag def = RaenItemTags.def(item);
            if (def == null) return;

            // 总开关：命中时由 RaenItemHit 统一结算
            RaenEffectBase.markBullet(bullet, RaenItemHit.ID, 1);
            RaenBulletMarks.addBulletMark(bullet, def.tag, 1);

            if (def.damageAdd != 0f) bullet.damage += def.damageAdd;
            if (def.damageMultiplier != 1f) bullet.damage *= def.damageMultiplier;

            if (def.homing) RaenItemHit.INSTANCE.track(bullet, def.homingRangeTiles);
            if (def.glassShard) scheduleGlass(bullet);

            // 氧化物：获得标签时还有概率白送一个「不占名额」的塑钢标签（完全继承）
            if (item == Items.oxide) grantFreePlastanium(bullet);
        }

        /**
         * 氧化物白送的塑钢标签：<b>不占 itemTagCaps 名额</b>，且与正常挂载<b>完全等价</b>。
         * <p>
         * 「完全继承」的落地方式：这里直接调 {@link #applyItem(Bullet, Item)} ——
         * 和正式抽到的塑钢走<b>同一条路径</b>（同一个标签名、同一份 {@link RaenItemTag} 定义、
         * 同样的「获得标签时」修正、命中时同一个 {@code plastaniumHit}）。
         * 所以以后改塑钢的碎片数量 / 伤害 / 只带追击 / 延迟，这颗白送的塑钢会<b>自动跟着变</b>。
         * <p>
         * 它只是「先垫上效果」，<b>不阻止正式抽取再抽到塑钢</b>：
         * <ul>
         *   <li>{@link #attachItems} 里这颗塑钢仍留在候选池里；</li>
         *   <li>正式抽中时走 {@code convert} 分支：扣一次充能、占一个名额、去掉 {@link #FREE_PLAST}，
         *       标签本身不变 ⇒ 效果始终只算一次；</li>
         *   <li>后续名额都没抽到塑钢，就保留白送的那颗（不占名额）。</li>
         * </ul>
         * 其它规则：概率 {@link #oxideFreePlastChance}（默认 10%）；已经有塑钢（正式或白送）就不再白送；
         * 分裂弹 / 瞬间命中类不挂（前者会回到「子弹海」，后者本来就不吃生成类标签）；白送不扣充能。
         */
        private void grantFreePlastanium(Bullet bullet) {
            if (!Mathf.chance(oxideFreePlastChance)) return;

            Item item = Items.plastanium;
            if (RaenItemTags.def(item) == null) return;

            // 已经有塑钢（正式或白送）⇒ 不重复挂，效果只生效一次
            if (RaenItemTags.has(RaenItemTags.maskOf(bullet), item)) return;

            if (RaenShards.isFragSpawned(bullet.type)) return;
            if (isInstantHitType(bullet.type)) return;

            // ★ 完全继承：走正常挂载的同一条路径（不是手写一份「简化版塑钢」）
            applyItem(bullet, item);
            // 只是记账：算名额占用时把这颗塑钢扣掉（不占名额）
            RaenBulletMarks.addBulletMark(bullet, FREE_PLAST, 1);
        }

        // ==================== 液体 ====================

        /**
         * 扫描液体装载情况，并把「仓里的液体」回填进<b>次数池</b>。
         * <p>
         * 液体走和物品完全一样的两层结构：
         * <ul>
         *   <li><b>次数池</b>（{@link #liquidCharges}）：每种最多 {@link #maxChargesPerLiquid} 次（默认 250）；</li>
         *   <li><b>液体仓</b>（引擎的 {@code liquids} 模块）：每种最多 {@link #liquidCapacity} 单位（默认 2500），
         *       每 {@link #liquidUnitsPerTag} 单位（默认 5）折算成 1 次。</li>
         * </ul>
         * 所以每种液体最多能挂 <b>250（次数池）+ 2500 ÷ 5（液体仓）= 750 次</b> 液体标签
         * —— 与物品的 <b>250（次数池）+ 15 × 50（物品仓）= 1000 次</b> 同一套算法。
         * <p>
         * ⚠ 回填是<b>必须</b>的：引擎的液体流转只按仓内实际存量算压力
         * （{@code BuildingComp.moveLiquidForward}：{@code next.block.liquidCapacity - next.liquids.get(liquid)}），
         * 它<b>不知道</b>次数池的存在 ⇒ 仓满时新液体进不来，只有把仓里的液体折算进次数池腾出空间才行。
         * <p>
         * 液体<b>不参与能量</b>（{@link #energy} 只算物品种类数）。
         */
        private void refreshLiquids() {
            liquidKinds.clear();
            liquidAmounts.clear();
            if (liquids == null) return;

            // ① 统计仓里现有的
            liquids.each((liquid, amount) -> {
                if (liquid == null || amount <= 0f) return;
                liquidKinds.add(liquid);
                liquidAmounts.put(liquid, amount);
            });

            // ② 仓 → 次数池 回填（与物品的 refresh() 同构）
            //    引擎的液体流转只按仓内实际存量算容量，它不知道次数池的存在 ⇒
            //    仓满时新液体进不来，只有把仓里的液体折算进次数池腾出空间才行。
            //    ⚠ 必须两遍扫描：LiquidModule.each 是按下标遍历数组，边遍历边 remove 会读到错位的数据。
            for (int i = 0; i < liquidKinds.size; i++){
                Liquid liquid = liquidKinds.get(i);
                int left = liquidCharges.get(liquid, 0);
                int room = maxChargesPerLiquid - left;
                if (room <= 0) continue;

                float amount = liquidAmountOf(liquid);
                if (amount < liquidUnitsPerTag) continue;

                // 与物品的 refresh() 同构：每次只补 1 次（物品那边是 1 个物品 = 50 次）
                liquidCharges.put(liquid, left + 1);
                liquidAmounts.put(liquid, amount - liquidUnitsPerTag);
                if (liquids != null) liquids.remove(liquid, liquidUnitsPerTag);
            }

            // ③ 次数池里还有货、但仓里已经空了的液体，也要算「已装载」
            //    （否则它虽然还能挂，却不会被 pickLiquid 挑中）
            for (Liquid liquid : liquidCharges.keys()){
                if (liquid == null) continue;
                if (liquidCharges.get(liquid, 0) <= 0) continue;
                if (!liquidKinds.contains(liquid)){
                    liquidKinds.add(liquid);
                    liquidAmounts.put(liquid, 0f);
                }
            }
        }

        /** 某种液体当前仓里有多少单位（不含已折算进次数池的） */
        private float liquidAmountOf(Liquid liquid) {
            return liquid == null ? 0f : liquidAmounts.get(liquid, 0f);
        }

        /** 某种液体当前还剩多少次可挂（次数池 + 仓里能折算的次数） */
        private int liquidShotsLeft(Liquid liquid) {
            if (liquid == null) return 0;
            int shots = liquidCharges.get(liquid, 0);
            if (liquidUnitsPerTag > 0f) shots += (int)(liquidAmountOf(liquid) / liquidUnitsPerTag);
            return shots;
        }

        /**
         * 倒进来的液体：<b>先折算成次数</b>，次数池满了才留在仓里。
         * <p>
         * 与物品的 {@code handleItem} 同构：物品进来时若次数池装得下就直接消耗掉换成次数，
         * 否则以「物品形态」存进库存；液体则是「单位形态」存进仓。
         * <p>
         * 这样两份资源是独立的 ⇒ 每种液体最多能挂
         * {@link #maxChargesPerLiquid}（次数池）+ {@link #liquidCapacity} ÷ {@link #liquidUnitsPerTag}（液体仓）次。
         */
        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            if (liquid == null || amount <= 0f) return;
            if (liquidUnitsPerTag <= 0f){
                super.handleLiquid(source, liquid, amount);
                return;
            }

            // ① 次数池还有空位 → 先折算（每 liquidUnitsPerTag 单位折 1 次）
            int left = liquidCharges.get(liquid, 0);
            int room = maxChargesPerLiquid - left;
            int convert = Math.min(room, (int)(amount / liquidUnitsPerTag));
            if (convert > 0){
                liquidCharges.put(liquid, left + convert);
                amount -= convert * liquidUnitsPerTag;
            }

            // ② 剩下的才进仓（引擎的仓容量由 liquidCapacity 限制）
            if (amount > 0f) super.handleLiquid(source, liquid, amount);
        }

        /**
         * 给子弹挂液体标签。
         * <p>
         * 规则：
         * <ul>
         *   <li><b>单颗子弹最多一个</b>液体标签 —— 已经带了液体的子弹直接跳过；</li>
         *   <li>每挂一次消耗 {@link #liquidUnitsPerTag} 单位液体（默认 5）；</li>
         *   <li><b>液体一律随机</b>：从装载的液体里随机挑一种挂上，UI 里不提供指定；</li>
         *   <li>液体标签不占物品标签的名额，两者互不影响。</li>
         * </ul>
         */
        private void attachLiquid(Bullet bullet) {
            if (bullet == null) return;
            if (liquidUnitsPerTag <= 0f) return;
            // 单颗子弹最多携带一个液体标签
            if (RaenLiquidTags.hasLiquid(bullet)) return;
            // 碎片子弹不允许再挂任何标签
            if (RaenBulletMarks.hasBulletMark(bullet, RaenShards.NO_TAG)) return;

            Liquid pick = pickLiquid();
            if (pick == null) return;

            // 消耗 1 次（优先扣次数池；次数池空了才从仓里现折算）
            if (!takeLiquidCharge(pick)) return;

            // 总开关 + 液体标签：命中时由 RaenLiquidHit 结算
            // 标记名 = raenLiq + 液体名 ⇒ 任意液体（含 mod 加的）都能挂
            RaenEffectBase.markBullet(bullet, RaenLiquidHit.ID, 1);
            RaenBulletMarks.addBulletMark(bullet, RaenLiquidTags.tagOf(pick), 1);
        }

        /**
         * 消耗 1 次液体挂载机会。
         * <p>
         * 优先扣次数池；次数池空了，就从仓里现扣 {@link #liquidUnitsPerTag} 单位折算 1 次
         * （仓里不够一个单位的零头留着下次继续攒）。
         */
        private boolean takeLiquidCharge(Liquid liquid) {
            int left = liquidCharges.get(liquid, 0);
            if (left > 0) {
                // 次数用完就把键删掉，别留一个 0 值的键在池子里
                // （ObjectIntMap.put 不会清理 0 值，留着会让「种类数」和实际可挂次数对不上）
                if (left - 1 <= 0) liquidCharges.remove(liquid);
                else liquidCharges.put(liquid, left - 1);
                return true;
            }

            if (liquids == null || liquidUnitsPerTag <= 0f) return false;
            float amount = liquids.get(liquid);
            if (amount < liquidUnitsPerTag) return false;

            liquids.remove(liquid, liquidUnitsPerTag);
            liquidAmounts.put(liquid, amount - liquidUnitsPerTag);
            return true;
        }

        /** 挑一种要挂的液体：<b>一律随机</b>（只从「还够挂一次」的液体里挑）。 */
        private Liquid pickLiquid() {
            int available = 0;
            for (int i = 0; i < liquidKinds.size; i++) {
                if (liquidShotsLeft(liquidKinds.get(i)) > 0) available++;
            }
            if (available <= 0) return null;

            int pick = Mathf.random(available - 1);
            for (int i = 0; i < liquidKinds.size; i++) {
                Liquid liquid = liquidKinds.get(i);
                if (liquidShotsLeft(liquid) <= 0) continue;
                if (pick-- == 0) return liquid;
            }
            return null;
        }

        /** 玻璃：0.5 秒后在「获得标签处」用<b>原子弹的类型</b>生成一枚 3% 伤害、不可挂标签的碎片。 */
        private void scheduleGlass(Bullet bullet) {
            PendingShard shard = new PendingShard();
            shard.team = bullet.team;
            shard.type = bullet.type;
            shard.x = bullet.x;
            shard.y = bullet.y;
            shard.damage = bullet.damage * glassDamageRatio;
            // 速度沿用原弹的 100%：半速会让碎片还没飞到目标就过期
            shard.speed = bullet.vel.len() * glassSpeedScale;
            shard.angle = shard.speed > 0.01f ? bullet.vel.angle() : Mathf.random(360f);
            shard.time = glassDelaySeconds * 60f;
            // 原弹 0.5 秒后可能已被回收复用，标签必须当场抄下来（并摘掉会继续额外生成的）
            shard.marks = RaenShards.snapshotWithoutGenerators(bullet);
            pending.add(shard);
        }

        /** 推进玻璃碎片的延迟生成 */
        private void tickPending() {
            if (pending.size == 0) return;

            for (int i = pending.size - 1; i >= 0; i--) {
                PendingShard shard = pending.get(i);
                shard.time -= Time.delta;
                if (shard.time > 0f) continue;

                pending.remove(i);
                Bullet glass = RaenShards.spawn(shard.type, shard.team, shard.x, shard.y,
                        shard.damage, shard.speed, shard.angle, shard.marks);
                // 火炮类子弹的目标是地板，碎片直飞会「越过目标」，所以给它一段追踪
                if (glass != null) RaenItemHit.INSTANCE.track(glass, glassHomingTiles);
            }
        }

        /**
         * 按阶梯收集本帧生效的主标签：
         * 0 能量→追击、2 能量→破绽、4 能量→积焰、6 能量→酸蚀、8 能量→注入（见 {@link #mainLadder}）。
         * 之后再按 {@link #randomUnlockTypes} 决定要不要再补一条随机主标签。
         */
        private void collectActive(int energy) {
            active.clear();

            for (int i = 0; i < mainLadder.size; i++) {
                MainTag tag = mainLadder.get(i);
                if (tag == null || tag.effect == null) continue;
                if (energy < tag.energy) continue;
                active.add(tag);
            }

            if (randomUnlockTypes < 0 || energy < randomUnlockTypes) return;

            pool.clear();
            // 白名单池：领域专用的内部效果（如 RaenItemHit）不在池里，天然不会被抽到
            Seq<RaenEffectBase> all = RaenEffectPool.all();
            for (int i = 0; i < all.size; i++) {
                RaenEffectBase effect = all.get(i);
                if (randomExclude.contains(effect.id(), false)) continue;   // 中毒 / 转命 / 连锁已改为物品标签
                if (isInLadder(effect)) continue;                           // 阶梯里已经有的不再随机
                pool.add(effect);
            }
            if (pool.size == 0) return;

            randomEntry.effect = pool.get(Mathf.random(pool.size - 1));
            randomEntry.stacks = 1;
            active.add(randomEntry);
        }

        /** 该效果是否已经在主效果阶梯里 */
        private boolean isInLadder(RaenEffect effect) {
            for (int i = 0; i < mainLadder.size; i++) {
                MainTag tag = mainLadder.get(i);
                if (tag != null && tag.effect == effect) return true;
            }
            return false;
        }

        // ==================== 物品进出 ====================

        /**
         * 放进来即消耗：物品不进库存，直接换成本物品的 {@link #shotsPerItem} 次施加机会。
         * <p>
         * 次数最多顶到 {@link #maxChargesPerItem}（= 5 个物品）；装不下的物品以「物品」形态
         * 存进库存（每种 {@link #itemCapacity} 个），等仓库空闲量大于一次补充的量再自动续。
         */
        /**
         * 接受<b>所有</b>液体（不限种类）。
         * <p>
         * 引擎默认的 {@code acceptLiquid} 只查「方块收不收这种液体」，容量是
         * {@code LiquidModule} 自己在装载时按 {@code liquid.id} 独立计数的 ——
         * 所以这里补一句容量判断，免得同一种液体被灌到超过上限。
         * <p>
         * 判定要看<b>两份资源</b>：液体仓（{@code liquidCapacity} 单位）+
         * 次数池（{@link #maxChargesPerLiquid} 次）。只判仓的话，
         * 次数池还有空位时反而会拒收新液体 —— 而次数池的空位正是靠新液体填的。
         */
        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            if (liquid == null) return false;

            boolean warehouseFull = liquids != null && liquids.get(liquid) >= liquidCapacity;
            boolean poolFull = liquidCharges.get(liquid, 0) >= maxChargesPerLiquid;
            if (warehouseFull && poolFull) return false;

            return super.acceptLiquid(source, liquid);
        }

        @Override
        public void handleItem(Building source, Item item) {
            if (item == null || RaenItemTags.indexOf(item) < 0) return;

            if (canConsumeNow(item)) {
                charges.put(item, charges.get(item, 0) + shotsPerItem);   // 直接被消耗
            } else {
                super.handleItem(source, item);                            // 计满了 → 以物品形态存着
            }
        }

        /** 现在投入 1 个物品能否立刻换成次数（不会被上限截断） */
        private boolean canConsumeNow(Item item) {
            return charges.get(item, 0) + shotsPerItem <= maxChargesPerItem;
        }

        /** 只收「有物品标签定义」的物品，其它物品（裂变物质、休眠晶体等）一律拒收。 */
        @Override
        public boolean acceptItem(Building source, Item item) {
            if (item == null || RaenItemTags.indexOf(item) < 0) return false;
            // 能立刻被消耗 → 不占容量
            if (canConsumeNow(item)) return true;
            // 否则要排队，受「每种物品 {@link #itemCapacity} 个」限制
            return items.get(item) < getMaximumAccepted(item);
        }

        @Override
        public int getMaximumAccepted(Item item) {
            // 返回 0 等于「这个物品一格都不收」，物流也不会往这里送
            return RaenItemTags.indexOf(item) < 0 ? 0 : itemCapacity;
        }

        /** 手动 / 单位批量投入：能立刻消耗时多收 1 个，其余按该物品的库存余量收。 */
        @Override
        public int acceptStack(Item item, int amount, Teamc source) {
            if (item == null || amount <= 0 || RaenItemTags.indexOf(item) < 0) return 0;

            int free = Math.max(0, getMaximumAccepted(item) - items.get(item));
            int convertible = canConsumeNow(item) ? 1 : 0;
            return Math.min(amount, free + convertible);
        }

        @Override
        public void handleStack(Item item, int amount, Teamc source) {
            for (int i = 0; i < amount; i++) {
                handleItem(null, item);
            }
        }

        // ==================== 面板 ====================

        /** 信息面板：能量 / 范围 / 主标签 / 四属性。剩余与每件余量在点击方块弹出的面板里（见 buildConfiguration）。 */
        @Override
        public void display(Table table) {
            super.display(table);
            refresh();
            refreshLiquids();

            // 四属性在 refresh() 里已经按方块物品合计算好（attr）

            // 本帧生效的主标签（0 能量也有追击）
            // ★ 用「生效能量」：断电降级时只能挂到 3 能量那一档，面板不能显示满了的列表
            final int eff = effectiveEnergy();
            collectActive(eff);
            StringBuilder tags = new StringBuilder();
            for (int i = 0; i < active.size; i++) {
                RaenEffect effect = active.get(i).effect;
                if (effect == null || effect.text() == null) continue;
                if (tags.length() > 0) tags.append(" · ");
                tags.append(effect.text().title());
            }

            table.row();
            table.add(Core.bundle.format("raen.block.panel.energy", energy, stocked.size, itemTagLimit(eff)))
                    .left().padTop(4f);
            // 断电降级单独一行：⚠ 不要在数字里拼「→」那种箭头，默认字体没有该字形，会被吞成「53」
            if (eff != energy) {
                table.row();
                table.add(Core.bundle.format("raen.block.panel.energyLow", eff))
                        .left().padTop(2f).growX().wrap();
            }

            table.row();
            table.add(Core.bundle.format("raen.block.panel.range", (int) (rangePx() / 8f)))
                    .left().padTop(2f);

            if (eff >= weakenStartEnergy) {
                table.row();
                table.add(Core.bundle.format("raen.block.panel.weaken",
                        fmt(weakenPerSecond * 100f), fmt(weakenFloor * 100f))).left().padTop(2f).growX().wrap();
            }

            table.row();
            table.add(Core.bundle.format("raen.block.panel.mains",
                    tags.length() == 0 ? Core.bundle.get("raen.block.panel.noMains") : tags.toString()))
                    .left().padTop(2f).growX().wrap();

            if (stocked.size > 0) {
                table.row();
                table.add(Core.bundle.format("raen.block.panel.attr",
                        fmt(attr[0]), fmt(attr[1]), fmt(attr[2]), fmt(attr[3]))).left().padTop(2f);
            }

            if (liquidKinds.size > 0) {
                table.row();
                table.add(Core.bundle.format("raen.block.panel.liquidInfo",
                        liquidKinds.size))
                        .left().padTop(2f).growX().wrap();
            }
        }

        // ==================== 绘制 ====================

        @Override
        public void draw() {
            // 本体 / 液体 / 能层装饰统一交给 DrawMulti（见 RaenBlocks.load()）。
            // 不调 super.draw()：Building.draw() 只会画 block.region，没有装饰层。
            drawer.draw(this);

            // 范围圈不在这里画：方块本体离屏时 draw() 不会调用，圈会跟着消失。
            // 统一由 drawAllRanges()（Trigger.draw）按圆形画，见 drawRange()。
            if (drawShape) drawTriangles();
            drawPowerWarning();
            drawGuaranteedIcon();
            Draw.reset();
        }

        /**
         * 中心互反三角形：初始一组（正 + 反），每 {@link RaenApplyBlock#triangleEnergyStep} 能量加一组，
         * 最多 {@link RaenApplyBlock#triangleMaxGroups} 组。颜色随能量从蓝渐变到红。
         */
        private void drawTriangles() {
            // 断电也要画：以前 alpha = potentialEfficiency，没电时直接 return ⇒ 整个形状消失。
            // 现在给个下限（0.4），断电时半透明但仍可见；有电则按供电比例淡入。
            float alpha = Math.max(0.4f, potentialEfficiency);

            // 组数仍按「额定能量」算：11 能量就是 3 组，不会因为断电少画
            int groups = Math.min(triangleMaxGroups, 1 + energy / triangleEnergyStep);
            float t = triangleColorFullEnergy <= 0 ? 0f
                    : Mathf.clamp((float) energy / triangleColorFullEnergy);
            Color color = TMP_TRI_COLOR.set(triangleColorStart).lerp(triangleColorEnd, t);

            Lines.stroke(triangleStroke);
            Draw.color(color, alpha * 0.75f);
            float grow = 0.75f + alpha * 0.25f;
            for (int g = 0; g < groups; g++) {
                // 每组半径 = 上一组 × 1.25；每组角速度不同（组号越大越快）
                float radius = triangleRadius * (float) Math.pow(triangleRadiusScale, g);
                float speed = triangleRotateSpeed * (1f + triangleSpeedStep * g);
                float rot = Time.time * speed * (g % 2 == 0 ? 1f : -1f);
                Lines.poly(x, y, 3, radius * grow, rot);                 // 正向旋转
                Lines.poly(x, y, 3, radius * grow, -rot);                // 反向旋转
            }
            Lines.stroke(1f);
            Draw.color();
        }

        /**
         * 断电警告（纯装饰）：能量进入「要通电」的档位（≥ {@link #powerStartEnergy}）但没接电时，
         * 在方块处每 {@link #powerWarningIntervalSeconds} 秒闪一次<b>原版警告图标</b>（{@link Icon#warning}）；
         * 通电后立刻消失。
         * <p>
         * 直接用 {@link Icon#warning} 的贴图绘制（不生成 {@code Effect} 实体、不占特效表）。
         * 贴图本身是白色描边（图标字体那套）⇒ 必须用 {@code Draw.color(powerWarningColor, alpha)} 染红，
         * 尺寸由 {@link #powerWarningSizeTiles}（格）直接换算像素，不受方块尺寸影响。
         */
        private void drawPowerWarning() {
            if (energy < powerStartEnergy) return;          // 0~3 能量本来就不耗电，不提示
            if (potentialEfficiency >= 0.99f) return;       // 有电 ⇒ 不画
            if (!enabled) return;                           // 玩家手动关掉的方块不刷警告

            // 每 interval 秒亮 flash 秒：先满亮度，最后 FADE_SECONDS 秒淡出
            float interval = Math.max(0.1f, powerWarningIntervalSeconds);
            float flash = Mathf.clamp(powerWarningFlashSeconds, 0.01f, interval);
            float phase = (Time.time / 60f) % interval;
            if (phase > flash) return;
            float alpha = Mathf.clamp((flash - phase) / FADE_SECONDS, 0f, 1f);

            // 尺寸按「格」给，直接换算成像素，不再乘 block.size
            float size = Mathf.clamp(powerWarningSizeTiles * 8f, 4f, 32f);
            float prevZ = Draw.z();

            Draw.z(Layer.effect + 1.5f);
            Draw.color(powerWarningColor, alpha);       // 图标本体是白色描边贴图 ⇒ 用警告色染色
            TextureRegion icon = Icon.warning.getRegion();
            Draw.rect(icon, x, y, size, size);          // rect(region) 以中心点绘制

            // 一点红光，暗处也能看见
            Drawf.light(x, y, size * 0.9f, powerWarningColor, alpha * 0.5f);

            Draw.color();
            Draw.z(prevZ);
        }

        /**
         * 必带物品：在方块正中心<b>常显</b>那件物品的图标。
         * <p>
         * 只画「选中的那一个」（{@link #guaranteedItem}）；没被选中的物品仍然走原来的能层装饰 / 光点组，不额外画。
         * <p>
         * ⚠ 断电（或物品种类多但标签名额不够）时「必带」本来就不生效 —— 图标<b>不隐藏</b>，
         * 改成 {@link #guaranteeIconInactiveAlpha} 的半透明，表示「已选但当前不起作用」。
         */
        private void drawGuaranteedIcon() {
            Item item = guaranteedItem;
            if (item == null) return;

            float size = Math.max(4f, block.size * 8f * guaranteeIconScale);
            // 「真正生效」= 名额够 + 方块里确实装了这件物品；否则半透明（已选但不起作用）
            float alpha = (guaranteeUnlocked() && stocked.contains(item)) ? 1f : guaranteeIconInactiveAlpha;
            float prevZ = Draw.z();

            Draw.z(Layer.blockOver);
            Draw.color(Color.white, alpha);
            Draw.rect(item.uiIcon, x, y, size, size);
            Draw.color();
            Draw.z(prevZ);
        }

        /**
         * 范围圈：<b>真正的生效范围是圆形</b>（命中判定走 `dst2`），所以画圆而不是方框。
         * <p>
         * 由 {@link #drawAllRanges()} 在全局 {@code Trigger.draw} 里调用 —— 画在方块自己的
         * {@code draw()} 里会随本体离屏被剔除，玩家挪开视野就看不到圈了。
         */
        public void drawRange() {
            float range = rangePx();
            if (range <= 0f) return;

            Draw.z(Layer.effect);

            // 范围圈：低速旋转的虚线圆
            drawRotatingRangeCircle(range);

            // 光点组：每种物品一组，组内两点分别在 3 格内轨道与 95% 范围外轨道上，同角速度旋转
            drawOrbits(range);

            Draw.reset();
        }

        /** 范围圈：常显虚线圈（原版画法，保证可见）+ 叠加低速旋转的短弧 */
        private void drawRotatingRangeCircle(float range) {
            // 常显圈：原版 dashCircle 自带灰底 + 配色两层，稳定可见
            Drawf.dashCircle(x, y, range, waveColor);
            // 叠加旋转虚线：用 lineAngleCenter 画短段，避免 Lines.arc 的 fraction 过小被剔除
            Lines.stroke(1.5f, waveColor);
            int dashes = 24;
            float rotation = Time.time * rangeRotateSpeed;
            for (int i = 0; i < dashes; i++) {
                float a = rotation + i * (360f / dashes);
                Lines.lineAngleCenter(
                        x + Mathf.cos(a * Mathf.degRad) * range,
                        y + Mathf.sin(a * Mathf.degRad) * range,
                        a, 6f);
            }
            Lines.stroke(1f);
            Draw.color();
        }

        /**
         * 光点组：方块内每种物品一组（组数 = 需求数），组内两光点同角速度旋转，
         * 组间平分 360°。每 {@link #orbitThrowInterval} 秒各组「投掷」一次：
         * 25% 概率在两光点间画一条闪电（Fx.lightning，纯特效无伤害）。
         */
        private void drawOrbits(float range) {
            int groups = stocked.size;
            if (groups <= 0) return;

            float innerR = orbitInnerRadiusTiles * 8f;
            float outerR = range * orbitOuterRatio;
            float baseRot = Time.time * orbitRotateSpeed;
            long cycle = (long) (Time.time / (orbitThrowInterval * 60f));

            for (int i = 0; i < groups; i++) {
                Item item = stocked.get(i);
                Color color = item != null && item.color != null ? item.color : Pal.accent;
                // 每组平分圆弧
                float angle = baseRot + i * (360f / groups);
                float a = angle * Mathf.degRad;
                float ix = x + Mathf.cos(a) * innerR, iy = y + Mathf.sin(a) * innerR;
                float ox = x + Mathf.cos(a) * outerR, oy = y + Mathf.sin(a) * outerR;

                Draw.color(color, 0.9f);
                Fill.circle(ix, iy, orbitDotRadius);
                Fill.circle(ox, oy, orbitDotRadius);

                // 投掷：每组每周期一次，各自延迟 1~2 秒，避免所有组同帧触发。
                long seed = tile.pos() + cycle * 7919L + i * 104729L;
                float delaySec = Mathf.randomSeed(seed, orbitLightningDelayMin, orbitLightningDelayMax);
                int delayTicks = (int) (delaySec * 60f);
                int tickInCycle = (int) (Time.time % (orbitThrowInterval * 60f));
                int slot = Math.min(i, lastThrowCycles.length - 1);
                boolean fired = lastThrowCycles[slot] == cycle + 1;
                if (!fired && tickInCycle >= delayTicks) {
                    lastThrowCycles[slot] = cycle + 1;
                    if (Mathf.randomSeed(seed + 31L) < orbitLightningChance) {
                        spawnOrbitLightning(ix, iy, ox, oy, color, seed);
                    }
                }
            }
            Draw.color();
        }

        /** 两光点之间画一条闪电特效（fx 只画一次，不造成伤害） */
        private void spawnOrbitLightning(float x1, float y1, float x2, float y2, Color color, long seed) {
            Seq<Vec2> points = new Seq<>();
            int segments = 5;
            for (int i = 0; i <= segments; i++) {
                float t = (float) i / segments;
                float px = Mathf.lerp(x1, x2, t);
                float py = Mathf.lerp(y1, y2, t);
                if (i > 0 && i < segments) {
                    px += Mathf.randomSeed(seed + i, -4f, 4f);
                    py += Mathf.randomSeed(seed + i + 100, -4f, 4f);
                }
                points.add(new Vec2(px, py));
            }
            Fx.lightning.at(x1, y1, 0f, color, points);
        }

        /**
         * 点击方块弹出的面板：<b>剩余发数写在物品列表上方</b>，下面是每种物品的图标与余量，
         * 再下面是「必带物品」选择区与液体区。
         * <p>
         * 这些信息原来挤在右侧信息面板里，20 种物品全装时那一条
         * 「余量：铜 1000 · 铅 1000 · …」会占满半屏宽，把范围和属性值都挤变形，
         * 所以改成独立面板（布局参考 {@code fz.pt.BlockA} 的物品网格）。
         */
        @Override
        public void buildConfiguration(Table table) {
            refresh();
            refreshLiquids();

            int sum = 0;
            for (int i = 0; i < stocked.size; i++) sum += shotsLeft(stocked.get(i));
            final int total = sum;      // lambda 里要用，必须是 effectively final

            Table panel = new Table();
            panel.table(Styles.black6, cont -> {
                cont.top().margin(6f);
                buildPanelContent(cont, total);
            });

            table.add(panel);
        }

        /** 配置面板的内容（勾选变化后会重建，所以单独抽成一个方法） */
        private void buildPanelContent(Table cont, int total) {
            // ① 剩余总量（在物品列表上方）
            cont.add(Core.bundle.format("raen.block.panel.total", total))
                    .left().padLeft(4f).padBottom(2f);
            cont.row();

            // ② 能量 / 物品上限（断电降级时按降级后的能量显示）
            final int eff = effectiveEnergy();
            cont.add(Core.bundle.format("raen.block.panel.energy", energy, stocked.size, itemTagLimit(eff)))
                    .left().padLeft(4f).padBottom(2f);
            cont.row();
            if (eff != energy) {
                cont.add(Core.bundle.format("raen.block.panel.energyLow", eff))
                        .left().padLeft(4f).padBottom(2f).growX().wrap();
                cont.row();
            }

            // ③ 物品网格：5 列，图标 + 该物品剩余次数
            if (stocked.size == 0) {
                cont.add(Core.bundle.get("raen.block.panel.noItems"))
                        .left().padLeft(4f).growX().wrap().padBottom(4f);
                cont.row();
            } else {
                Table grid = new Table();
                grid.defaults().size(44f).pad(2f);
                int col = 0;
                for (int i = 0; i < stocked.size; i++) {
                    Item item = stocked.get(i);
                    RaenItemTag def = RaenItemTags.def(item);
                    int left = shotsLeft(item);

                    Table cell = new Table();
                    cell.image(item.uiIcon).size(26f).padBottom(1f);
                    cell.row();
                    cell.add(String.valueOf(left)).fontScale(0.7f);

                    // 文案很长（铅 / 玻璃 / 塑钢），用固定宽度换行，避免气泡横穿屏幕
                    grid.add(cell).tooltip(t -> {
                        t.background(Styles.black6);
                        t.margin(6f).left();
                        t.add(def == null ? item.localizedName : def.name()).left().row();
                        if (def != null) {
                            t.add("[lightgray]" + def.effectText()).left().width(360f).wrap();
                        }
                    });

                    if (++col % 5 == 0) grid.row();
                }

                cont.add(grid).left().padBottom(4f);
                cont.row();
            }

            // ④ 必带物品：只有能附加 2 种以上物品标签时才有意义（4 能量解锁）
            buildGuaranteedSection(cont);
            cont.row();

            // ⑤ 液体：只读展示（液体一律随机挂载，不支持指定）
            buildLiquidSection(cont);
        }

        /**
         * 「必带物品」选择区（布局参考 {@code fz.pt.BlockA}）：
         * <ul>
         *   <li>顶部一行显示已选的物品图标 + 一个「清除」按钮；</li>
         *   <li>下面是物品图标网格，<b>只能选 1 种</b>：点别的替换掉当前选择，
         *       再点一次已选的那个则取消选择（用 {@link Styles#clearTogglei} 的按钮做开关，
         *       勾选态由 {@code Cell.update} 每帧同步）；</li>
         *   <li>没解锁（每发只能带 1 种物品标签）时整块不显示。</li>
         * </ul>
         */
        private void buildGuaranteedSection(Table cont) {
            if (!guaranteeUnlocked()) return;

            // 顶部：已选图标 + 清除按钮
            cont.table(top -> {
                top.left();

                guaranteedIcons = new Table();
                guaranteedIcons.left();
                top.add(guaranteedIcons).growX().left().padLeft(4f);
                top.button(Icon.cancel, Styles.clearTogglei, () -> {
                    guaranteedItem = null;
                    configure(config());
                    rebuildGuaranteedIcons();
                }).size(44f).right().tooltip(Core.bundle.get("raen.block.panel.guaranteedClear"));
            }).growX().padBottom(4f);
            cont.row();

            rebuildGuaranteedIcons();

            cont.add(Core.bundle.format("raen.block.panel.guaranteed", guaranteeUnlockTags))
                    .left().padLeft(4f).padBottom(2f).growX().wrap();
            cont.row();

            // 物品网格：单选开关（照 fz.pt.BlockA 的写法）
            Table grid = new Table();
            grid.defaults().size(44f).pad(2f);
            int col = 0;
            Seq<RaenItemTag> defs = RaenItemTags.all();
            for (int i = 0; i < defs.size; i++) {
                RaenItemTag def = defs.get(i);
                Item item = def.item;

                // 开关按钮：勾选状态跟随 guaranteedItem（只能有 1 个被勾上）
                grid.button(new TextureRegionDrawable(item.uiIcon), Styles.clearTogglei, 26f, () -> {
                    toggleGuaranteed(item);
                    rebuildGuaranteedIcons();
                }).update(b -> b.setChecked(guaranteedItem == item))
                        .size(44f)
                        .tooltip(t -> {
                            t.background(Styles.black6);
                            t.margin(6f).left();
                            t.add(def.name()).left().row();
                            t.add("[lightgray]" + def.effectText()).left().width(360f).wrap();
                        });

                if (++col % 7 == 0) grid.row();
            }

            cont.add(grid).left().padBottom(4f);
        }

        /** 重建「已选必带物品」的图标行 */
        private void rebuildGuaranteedIcons() {
            if (guaranteedIcons == null) return;

            guaranteedIcons.clearChildren();
            if (guaranteedItem == null) {
                guaranteedIcons.add(Core.bundle.get("raen.block.panel.guaranteedNone"))
                        .left().padLeft(4f);
            }else{
                guaranteedIcons.add(new Image(new TextureRegionDrawable(guaranteedItem.uiIcon)))
                        .size(26f).padRight(2f).tooltip(guaranteedItem.localizedName);
            }
            guaranteedIcons.invalidateHierarchy();
        }

        /** 液体区：只读展示装载的液体与剩余次数（液体一律随机挂载） */
        private void buildLiquidSection(Table cont) {
            if (liquidKinds.size == 0) return;

            cont.add(Core.bundle.get("raen.block.panel.liquids")).left().padLeft(4f).padBottom(2f);
            cont.row();

            Table grid = new Table();
            grid.defaults().size(44f).pad(2f);
            int col = 0;
            for (int i = 0; i < liquidKinds.size; i++) {
                Liquid liquid = liquidKinds.get(i);
                final int shots = liquidShotsLeft(liquid);

                Table cell = new Table();
                cell.image(liquid.uiIcon).size(26f).padBottom(1f);
                cell.row();
                cell.add(String.valueOf(shots)).fontScale(0.7f);

                grid.add(cell).tooltip(t -> {
                    t.background(Styles.black6);
                    t.margin(6f).left();
                    t.add(liquid.localizedName).left().row();
                    t.add("[lightgray]" + Core.bundle.format("raen.block.panel.liquidAmount",
                            shots, (int) liquidUnitsPerTag, (int) liquidCapacity)).left().width(360f).wrap();
                });

                if (++col % 7 == 0) grid.row();
            }

            cont.add(grid).left().padBottom(2f);
            cont.row();
            cont.add("[lightgray]" + Core.bundle.get("raen.block.panel.liquidRandom"))
                    .left().padLeft(4f).growX().wrap();
        }

        // ==================== 存档 ====================

        /**
         * 存档版本：1 = 增加「选中的液体」；2 = 改为「必带物品」列表；3 = 必带物品改单选；
         * 4 = 液体次数池（<b>有 bug，别用</b>）；5 = 液体次数池改成「结束符」写法
         */
        @Override
        public byte version() {
            return 5;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            // 必带物品：1 个物品 id（-1 = 没选）
            write.s(guaranteedItem == null ? -1 : guaranteedItem.id);
            // 液体次数池：逐条写（液体 id + 剩余次数），用 -1 结束符收尾。
            //
            // ⚠ 这里【不能】先写「种类数」再写条目：
            //   次数池里可能有「次数已耗尽（=0）」的种类，写的时候要跳过它们，
            //   于是「声明的种类数」会大于「真正写出的条目数」。
            //   读档那边按声明数去读，就会读到本区块之外去，把整个地图区的字节流读歪，
            //   地图区后半段全成垃圾 → 存档损坏 → 存档被删 → 重开地图变成全新空图。
            //   （原版 readChunk 不限制读取长度，多读的字节会直接吃掉下一个区块的数据。）
            //   用结束符就没有这个问题：写到哪儿读到哪儿，永远对齐。
            for (Liquid liquid : liquidCharges.keys()){
                if (liquid == null) continue;
                int shots = liquidCharges.get(liquid, 0);
                if (shots <= 0) continue;
                write.s(liquid.id);
                write.s(shots);
            }
            write.s(-1); // 结束符
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            if (revision >= 1 && revision < 2) {
                // 旧版本存的是「选中的液体 id」，直接丢弃（液体不再支持指定）
                read.s();
            }
            if (revision == 2) {
                // 旧版本存的是「数量 + 每个物品 id」的列表，只取第一个
                int count = read.s();
                guaranteedItem = null;
                for (int i = 0; i < count; i++) {
                    Item item = itemById(read.s());
                    if (item != null && guaranteedItem == null) guaranteedItem = item;
                }
            }
            if (revision >= 3) {
                short id = read.s();
                guaranteedItem = id < 0 ? null : itemById(id);
            }
            if (revision >= 4 && revision < 5) {
                // 旧格式（revision 4）：种类数 + 每个（液体 id + 剩余次数）
                // ⚠ 这个格式本身有 bug：写的时候会跳过「次数已耗尽」的种类，
                //    于是声明的种类数可能大于实际写出的条目数 → 读越界 → 地图区被读歪。
                //    这种旧存档一旦越界，后面的字节流已经错位，救不回来了；
                //    这里只做尽力而为的读取，别再往下读更多。
                liquidCharges.clear();
                int kinds = read.s();
                if (kinds < 0 || kinds > 256) kinds = 0;
                for (int i = 0; i < kinds; i++){
                    short liquidId = read.s();
                    int shots = read.s();
                    Liquid liquid = liquidById(liquidId);
                    if (liquid != null && shots > 0) liquidCharges.put(liquid, shots);
                }
            }
            if (revision >= 5) {
                // 新格式：逐条读到结束符 -1 为止（写多少读多少，永远不会越界）
                liquidCharges.clear();
                for (int i = 0; i < 256; i++){
                    short liquidId = read.s();
                    if (liquidId < 0) break; // 结束符
                    int shots = read.s();
                    Liquid liquid = liquidById(liquidId);
                    if (liquid != null && shots > 0) liquidCharges.put(liquid, shots);
                }
            }
        }

        /** 按 id 取回物品（存档用；id 越界就当没选） */
        private static Item itemById(short id) {
            if (id < 0 || Vars.content == null) return null;
            Seq<Item> all = Vars.content.items();
            return id < all.size ? all.get(id) : null;
        }

        /** 按 id 取回液体（存档用；id 越界就当没有） */
        private static Liquid liquidById(short id) {
            if (id < 0 || Vars.content == null) return null;
            Seq<Liquid> all = Vars.content.liquids();
            return id < all.size ? all.get(id) : null;
        }

        // ==================== 「必带物品」的联机同步 ====================

        /**
         * 联机同步用的配置值：<b>必带物品的 id</b>（0 或 1 个）。
         * <p>
         * 走原版 {@code config()} / {@code configured()} 机制：
         * 客户端改勾选后调 {@link #configure(Object)} → 广播到服务器 → 服务器再转发给其他客户端。
         * 用 {@link arc.struct.IntSeq} 而不是 {@code Integer}，因为它能被原版的
         * {@code TypeIO} 直接序列化（{@code Item} 也能存，但 IntSeq 更省事且不依赖内容顺序）。
         */
        @Override
        public Object config() {
            IntSeq ids = new IntSeq(1);
            if (guaranteedItem != null) ids.add(guaranteedItem.id);
            return ids;
        }

        /** 服务器（或其它客户端）把配置发过来时应用 */
        @Override
        public void configured(Unit builder, Object value) {
            super.configured(builder, value);

            if (value instanceof IntSeq ids) {
                applySelection(ids);
            }else if (value == null) {
                guaranteedItem = null;
            }
        }

        /**
         * 应用一份「必带物品」选择（来自联机配置）。
         * <p>
         * 无效的 id 会被丢掉；<b>只能选 1 种</b>，多余的忽略。
         */
        private void applySelection(IntSeq ids) {
            guaranteedItem = null;
            if (ids == null) return;

            for (int i = 0; i < ids.size; i++) {
                Item item = itemById((short)ids.get(i));
                if (item == null) continue;
                if (RaenItemTags.indexOf(item) < 0) continue;   // 没有物品标签的物品挂不了
                guaranteedItem = item;                          // 只能选 1 种，取第一个有效的
                break;
            }
        }

        /** 勾选 / 取消勾选一个「必带」物品，并同步给服务器 */
        private void toggleGuaranteed(Item item) {
            if (item == null || RaenItemTags.indexOf(item) < 0) return;

            // 只能选 1 种：点已选的那个 = 取消；点别的 = 替换掉当前的
            guaranteedItem = (guaranteedItem == item) ? null : item;

            // 通知服务器（联机时服务器才是权威方）
            configure(config());
        }
    }

    /** 玻璃的待生成碎片 */
    private static class PendingShard {
        Team team;
        /** 原子弹的类型：碎片按它的外观 / 飞行参数生成 */
        BulletType type;
        /** 生成时要挂的标签快照（已摘掉会继续额外生成的标签） */
        java.util.Set<String> marks;
        float x, y;
        float damage;
        float speed;
        float angle;
        /** 剩余时间（tick） */
        float time;
    }

    /** 玻璃碎片自带的追踪半径（格）：防止以地板为目标的炮弹类子弹带着碎片飞过头 */
    public float glassHomingTiles = 12f;
    /** 玻璃碎片伤害比例（× 获得标签时的子弹伤害） */
    public float glassDamageRatio = 0.03f;
    /**
     * 玻璃碎片速度比例（× 获得标签时的子弹速度）。
     * <p>
     * ★ 别调低于 1：碎片是按原弹生命周期飞的，半速很容易「还没飞到目标就过期」。
     */
    public float glassSpeedScale = 1f;
    /** 玻璃碎片延迟（秒） */
    public float glassDelaySeconds = 0.5f;

    /** 去掉多余小数位，用于详情文案 */
    private static String fmt(float value) {
        if (value == (int) value) return String.valueOf((int) value);
        return String.format("%.2f", value);
    }
}
