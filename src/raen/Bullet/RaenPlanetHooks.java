package raen.Bullet;

import arc.Events;
import arc.struct.ObjectFloatMap;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;
import mindustry.game.EventType;
import mindustry.type.ItemStack;
import mindustry.type.Planet;
import mindustry.world.Block;

/**
 * 「施加领域」的双星球接入（S 星 = 塞普罗 / E 星 = 埃里克尔）：<b>建造成本</b> + <b>科技树</b>。
 * <p>
 * <b>为什么不用做两个方块</b>
 * <ul>
 *   <li><b>科技树原生支持挂多棵树</b>：{@code TechNode} 的构造器末尾是
 *       {@code content.techNode = this; content.techNodes.add(this)}，
 *       而研究对话框（{@code ResearchDialog.switchTree} / {@code rebuildItems}）是按
 *       「根节点身份」（{@code planet.techTree == lastNode}）来判定当前在哪棵树上，
 *       并<b>不</b>要求一个 content 只有一个节点。所以同一个方块可以同时挂在两棵树的不同位置，
 *       <b>研究成本各写各的</b>（每个 {@code TechNode} 自带一份 {@code requirements}）。</li>
 *   <li><b>建造成本没有原生分叉</b>：{@code Block.requirements} 只有一份数组，引擎里
 *       没有任何「按星球」的入口（原版也是靠「两套方块」解决，比如各种墙 / 熔炉）。</li>
 * </ul>
 *
 * <b>所以成本这里走「用时现读」</b>：放置扣费（{@code ConstructBlock.update} /
 * {@code BuildBlockCallPacket}）、UI 报价（{@code PlacementFragment} 的
 * {@code core.items.has(block.requirements, ...)} 与物品清单）、方块信息面板，
 * 全部是「用到时再读 {@code block.requirements} 字段」，没有任何缓存。因此只要在
 * {@code WorldLoadEvent}（此时 {@code state.getPlanet()} 已经是本局所在星球）把数组换成
 * 对应星球的那一份，显示与扣费就同时正确了。一个存档只会同时存在一个星球，不存在歧义。
 *
 * <b>三个必须守住的顺序 / 边界</b>
 * <ol>
 *   <li><b>建节点之前先换成本</b>：{@code TechNode} 构造器会调 {@code content.getDependencies()}
 *       给节点塞 {@code Research(item)} 目标，而方块的依赖就是 {@code requirements} 里的物品。
 *       如果先建 E 星节点、方块身上还挂着 S 星成本，E 星节点就会要求「研究 铅 / 钛」——
 *       这俩在 E 星永远不满足，节点直接永久锁死。所以下面按「设成本 → 建节点」成对执行。</li>
 *   <li><b>研究成本必须非空</b>：{@code Control.checkAutoUnlocks()} 会把
 *       {@code requirements.length == 0} 且父节点已解锁的节点<b>自动解锁（不分星球）</b>；
 *       而解锁状态是 per-content 的（存在 settings 的 {@code <name>-unlocked}），
 *       一旦被自动解锁，另一颗星球的节点也会变成「已研究」。</li>
 *   <li><b>解锁本身是跨星球的</b>：研究进度存在 {@code Core.settings}（不是存档），
 *       所以「在 S 星研究完 → E 星也显示已研究」是原版所有内容共有的行为，
 *       单方块方案无法避免。真要严格隔离，只能做两个方块。</li>
 * </ol>
 */
public class RaenPlanetHooks {

    /** S 星（塞普罗）建造成本。 */
    public static final ItemStack[] serpuloCost = ItemStack.with(
            Items.copper, 50,
            Items.lead, 50
    );

    /** E 星（埃里克尔）建造成本。 */
    public static final ItemStack[] erekirCost = ItemStack.with(
            Items.graphite, 30,
            Items.silicon, 25,
            Items.beryllium, 36
    );

    /** S 星研究成本 = 建造成本 × 5。 */
    public static final ItemStack[] serpuloResearch = ItemStack.with(
            Items.copper, 250,
            Items.lead, 250
    );

    /**
     * E 星研究成本 = 建造成本 × 5。
     * ⚠ 这里显式给值，不走 E 星整棵树的 ×0.9 / oxide ×0.5 乘数（见 {@link #attach}），
     * 否则 180 会被静默乘成 162，「翻 5 倍」就对不上了。
     */
    public static final ItemStack[] erekirResearch = ItemStack.with(
            Items.graphite, 150,
            Items.silicon, 125,
            Items.beryllium, 180
    );

    /**
     * 入口：挂科技树 + 注册「进世界时换成本」。在 {@link RaenBlocks#load()} 里调用。
     * <p>
     * 放在 {@code loadContent()} 阶段（原版两棵树在 {@code createBaseContent()} 里就已经建好），
     * 这样 {@code Planet.init()} 随后的 {@code techTree.addPlanet(planet)} 会把两颗星球
     * 补进本内容的 {@code shownPlanets} / {@code databaseTabs}，核心数据库里两个标签页都能查到。
     */
    public static void install() {
        // 幂等：load() 被重复调用时不要再挂一遍（重复挂会让科技树上出现两个同名节点）
        if (!RaenBlocks.applyField.techNodes.isEmpty()) return;

        // ① S 星：接在双管炮（duo）之后
        attach(serpuloCost, Blocks.duo, serpuloResearch);
        // ② E 星：接在撕裂（breach，E 星最初的那门炮台）之后
        attach(erekirCost, Blocks.breach, erekirResearch);

        // 世界外的默认报价（主菜单 / 编辑器 / 还没进任何星球）按 S 星算
        applyCost();

        Events.on(EventType.WorldLoadEvent.class, e -> applyCost());
    }

    /**
     * 换成本 + 挂节点。
     * <p>
     * ⚠ 顺序是硬要求：{@link TechNode} 构造器会按 {@code Block.getDependencies()}（= 当前
     * {@code requirements} 里的物品）给节点塞 {@code Research(item)} 目标。所以必须先把成本
     * 换成这颗星球的那一份，再建节点；反了 E 星节点会要求「研究 铅 / 钛」→ 永远不满足 → 永久锁死。
     */
    private static void attach(ItemStack[] cost, Block parentContent, ItemStack[] research) {
        Block block = RaenBlocks.applyField;
        block.requirements = cost;

        TechNode parent = TechTree.all.find(n -> n.content == parentContent);
        if (parent == null) {
            Log.warn("[raen] 科技树父节点缺失，跳过：@", parentContent.name);
            return;
        }

        TechNode node = new TechNode(parent, block, ItemStack.empty);

        // 斩断从父节点继承的 researchCostMultipliers：E 星整棵树挂着一张 ×0.9（oxide ×0.5）
        // 的表，而那张表是**共享实例**，不能就地改（会连带改掉整棵树）。
        // 这里换成一张新表 + 重设成本，让上面那些研究成本就是字面值。
        node.researchCostMultipliers = new ObjectFloatMap<>();
        node.setupRequirements(research);
    }

    /** 按当前星球重绑建造成本；没有星球（自定义局 / 主菜单）时按 S 星算。 */
    private static void applyCost() {
        Planet planet = Vars.state == null ? null : Vars.state.getPlanet();
        RaenBlocks.applyField.requirements = planet != null && planet == Planets.erekir ? erekirCost : serpuloCost;
    }
}
