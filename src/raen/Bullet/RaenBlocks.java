package raen.Bullet;

import arc.Events;
import mindustry.game.EventType.Trigger;
import mindustry.world.Block;
import mindustry.world.draw.DrawBlock;
import mindustry.world.draw.DrawDefault;
import mindustry.world.draw.DrawLiquidRegion;
import mindustry.world.draw.DrawMulti;
import mindustry.world.draw.DrawRegion;

/**

 */
public class RaenBlocks {


    public static Block applyField;
    /** 沙子触发生成的临时墙（不可建造，只由代码放置） */
    public static RaenTempWall tempWall;

    public static void load() {
        // 物品标签表必须先就绪：方块构造时就会读 RaenItemTags。
        // 碎片子弹类型不在这里建：它要查贴图区域，等到第一次真正用到时再懒加载更稳。
        RaenItemTags.init();

        applyField = new RaenApplyBlock("applyField") {{
            size = 3;
            health = 360;

            // 本体 + 液体 + 能层装饰（图1~图8 按能量逐层叠加，镜像到四角并循环物品颜色）
            drawer = new DrawMulti(
                    new DrawDefault(),
                    // block.name 在 loadContent() 期间已被 ContentLoader 自动加过 "raen-" 前缀，
                    // 原版绘制器用的 block.name + suffix 正好命中 raen-applyField-liquid / -top。
                    // 不要在这里再手动拼 "raen-"，否则会变成 raen-raen-applyField-* → 贴图丢失。
                    new DrawLiquidRegion(),
                    new DrawRegion("-top"),
                    new DrawFieldDeco()
            );
        }};

        // 多星球接入：默认解锁 + 不挂科技树 + 建造成本按当前星球切换（S 铜铅 / E 石墨硅铍 / 其它石墨硅）。
        // 必须在 applyField 建好之后调用（它会写 alwaysUnlocked / shownPlanets / requirements）。
        // 为什么不能留空 shownPlanets，见 RaenPlanetHooks 的类注释。
        RaenPlanetHooks.install();

        // Block 的构造器会通过 MappableContent 自动登记进 Vars.content.blocks()，不用手动 add
        //
        // ⚠ 构造参数**不要**自己拼 "raen-" 前缀：
        //   ContentLoader.transformName 在 mod 上下文里会再自动加一次（currentMod.name + "-" + name），
        //   所以传 "raen-temp-wall" 会变成 "raen-raen-temp-wall"，
        //   引擎查的本地化 key 就成了 block.raen-raen-temp-wall.name（与 bundle 对不上，显示内部名）。
        //   传 "temp-wall" ⇒ 内容名 "raen-temp-wall" ⇒ key = block.raen-temp-wall.name ✅
        //   贴图 region 不受影响：贴图文件名 temp-wall.png 打包后本来就变成 raen-temp-wall。
        tempWall = new RaenTempWall("temp-wall");

        // 范围圈画在全局绘制里：这样方块本体离屏时圈也不会消失（画在 build.draw() 里会被剔除）
        Events.run(Trigger.draw, RaenApplyBlock::drawAllRanges);
    }
}
