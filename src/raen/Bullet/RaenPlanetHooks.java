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
 *   <li><b>显式写满 shownPlanets</b>（所有可落地星球）：非空 ⇒ 上面那段自动推断不会执行，
 *       也就不会因为成本里的物品把某颗星球踢掉。这是真正的保险；</li>
 *   <li><b>按星球换成本</b>：S / E 各一套，其它星球（小行星等）用 {@link #defaultCost}。
 *       {@code block.requirements} 只有一份，但放置扣费（{@code ConstructBlock}）与 UI 报价
 *       （{@code PlacementFragment}）都是「用时现读」，所以在 {@code WorldLoadEvent}
 *       （此时 {@code state.getPlanet()} 已是本局星球）整份替换即可。</li>
 * </ol>
 *
 * <h3>以后想重新挂科技树</h3>
 * 把 {@code new TechNode(parent, block, research)} 加回来即可，但<b>必须</b>保留第 2 条
 * （显式 {@code shownPlanets}），否则又会走进自动反推那条路；另外建节点前要先把成本换成
 * 那颗星球的 —— {@code TechNode} 构造器会按 {@code getDependencies()}（= 当前 requirements）
 * 给节点塞 {@code Research(item)} 目标，塞错了节点会永久锁死。
 */
public class RaenPlanetHooks {

    /** 没有专门定义的星球上的建造成本（两种星球都有的物品，避免任何一边凑不出来）。 */
    public static final ItemStack[] defaultCost = ItemStack.with(
            Items.graphite, 50,
            Items.silicon, 50
    );

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

        // ② 显式写满 shownPlanets：非空 ⇒ Block.postInit 的「按 requirements 反推星球」不会执行。
        //    这里覆盖所有可落地星球（塞普罗 / 埃里克尔 / 小行星）；没列进去的星球看不到方块。
        for (Planet planet : Vars.content.planets()) {
            if (planet.isLandable()) block.shownPlanets.add(planet);
        }

        // ③ 进世界前的默认成本；真正进游戏后由 WorldLoadEvent 换成当前星球的那一份
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
