package raen.Bullet;

import arc.Events;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.game.EventType;
import mindustry.type.ItemStack;
import mindustry.type.Planet;
import mindustry.world.Block;

/**
 * 「施加领域」的多星球接入：<b>默认解锁 + 不挂科技树 + 按星球换建造成本</b>。
 *
 * <h3>为什么不再走「科技树双挂载」</h3>
 * 科技树那边本来能挂两个节点（{@code content.techNodes} 是 Seq），但实测 E 星建筑栏里拿不到
 * 方块；根因不在科技树，而在 {@code Block.postInit()} 的这段自动推断：
 * <pre>
 * //usually, an empty set of planets is a configuration error. auto-assign based on requirements
 * if(requirements.length &gt; 0 &amp;&amp; shownPlanets.isEmpty()){
 *     for(Planet planet : content.planets()){
 *         if(planet.isLandable() &amp;&amp; !Structs.contains(requirements, s -&gt; !s.item.isOnPlanet(planet))){
 *             shownPlanets.add(planet);
 *         }
 *     }
 * }
 * </pre>
 * 建筑栏的判定是 {@code environmentBuildable() == isOnPlanet(state.getPlanet())}，只读
 * {@code shownPlanets}；而 {@code shownPlanets} 为空时，引擎会拿 {@code requirements} 反推
 * 「这个方块属于哪颗星球」。当时成本是 S 星的 铜 50 / 铅 50，铜铅在 E 星都不存在
 * ⇒ {@code shownPlanets = {serpulo}} ⇒ E 星建筑栏里没有它。科技树显示「已研究」只是解锁状态
 * （per-content、存 settings），跟这条过滤无关。
 *
 * <h3>所以这里做三件事</h3>
 * <ol>
 *   <li><b>默认解锁</b>（{@code alwaysUnlocked = true}）：不进科技树，落地就能造。
 *       {@code unlockedNowHost()} 恒真，建筑栏里「未研究」那条判定彻底不参与；</li>
 *   <li><b>shownPlanets 留空</b>：{@code isOnPlanet()} 对空集恒真 ⇒ 所有星球可见，
 *       包括各模组自己加的星球（那些星球没有原版物品，写死成塞普罗 / 埃里克尔会被排除）。
 *       前提是 {@code Block.postInit} 那一刻 {@code requirements} 为空 —— 默认免费正好满足；
 *       哪天默认要收费了，就必须改成显式 {@code shownPlanets}（否则又会被反推）；</li>
 *   <li><b>按星球换成本</b>：S / E 各一套，其它星球（含模组星球、小行星、自定义局）免费。
 *       {@code block.requirements} 只有一份，但放置扣费（{@code ConstructBlock}）与 UI 报价
 *       （{@code PlacementFragment}）都是「用时现读」，所以在 {@code WorldLoadEvent}
 *       （此时 {@code state.getPlanet()} 已是本局星球）整份替换即可。</li>
 * </ol>
 *
 * <h3>以后想重新挂科技树</h3>
 * 把 {@code new TechNode(parent, block, research)} 加回来即可，但要先把成本换成那颗星球的 ——
 * {@code TechNode} 构造器会按 {@code getDependencies()}（= 当前 requirements）给节点塞
 * {@code Research(item)} 目标，塞成另一颗星球的物品会让节点永久锁死。
 */
public class RaenPlanetHooks {

    /**
     * 没有专门定义的星球上的建造成本：<b>不收费</b>。
     * <p>
     * 各模组新增的星球往往根本没有原版物品（连铜铅硅都没有），任何"找两种都有"的组合都会翻车，
     * 所以这里直接空数组 —— 只有塞普罗 / 埃里克尔这两颗自己写了成本的星球才要材料。
     */
    public static final ItemStack[] defaultCost = ItemStack.empty;

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

    /** 入口：在 {@link RaenBlocks#load()} 里、方块创建之后调用一次。 */
    public static void install() {
        Block block = RaenBlocks.applyField;

        // ① 默认解锁：不进科技树，任何星球落地即可建造。
        //    （alwaysUnlocked 让 unlocked()/unlockedHost() 恒真，建筑栏不会因「未研究」隐藏它）
        block.alwaysUnlocked = true;

        // ② shownPlanets 故意【留空】：isOnPlanet() 对空集恒真 ⇒ 所有星球都能造，
        //    包括各模组自己加的星球（写死成塞普罗 / 埃里克尔会把它们排除掉）。
        //    代价：Block.postInit 会在「requirements 非空 && shownPlanets 为空」时按物品反推星球，
        //    所以第 ③ 条必须让 postInit 那一刻的 requirements 保持为空 —— 默认免费正好满足。
        //    数据库标签页单独给两个星球，纯粹是方便找（databaseTabs 与可见性无关）。
        block.databaseTabs.add(Planets.serpulo);
        block.databaseTabs.add(Planets.erekir);

        // ③ 进世界前的默认成本（空 = 免费），同时保证 postInit 不会反推星球
        block.requirements = defaultCost;

        Events.on(EventType.WorldLoadEvent.class, e -> applyCost());
    }

    /** 按当前星球重绑建造成本；没有星球（自定义局 / 主菜单 / 未知星球）时用默认成本。 */
    private static void applyCost() {
        Planet planet = Vars.state == null ? null : Vars.state.getPlanet();
        Block block = RaenBlocks.applyField;

        if (planet == Planets.serpulo) {
            block.requirements = serpuloCost;
        } else if (planet == Planets.erekir) {
            block.requirements = erekirCost;
        } else {
            block.requirements = defaultCost;
        }
    }
}
