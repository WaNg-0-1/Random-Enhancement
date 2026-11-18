package raen.Bullet;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.draw.DrawBlock;

/**
 * 领域方块「能层装饰」绘制器：能量 N 层就把图1..图N 逐层叠加画在方块本体上。
 * <p>
 * <b>贴图</b>（均 96×96 = 3×3 方块大小，放在 {@code assets/sprites/}）：
 * <ul>
 *   <li>{@code raen-applyField-deco1}：中心小十字，1 层能量时绘制，直接盖在本体上；</li>
 *   <li>{@code raen-applyField-deco2..8}：图里只画了左上角，运行时按四角镜像出另外 3 份。</li>
 * </ul>
 * 图2 起不再盖在本体上：按所属象限向外偏移并放大（{@link #offsetStep} / {@link #scaleStep}），
 * 层数越高推得越远、放得越大；左上 (-x,+y)、右上 (+x,+y)、左下 (-x,-y)、右下 (+x,-y)。
 * <p>
 * <b>透明度</b>：第 1 层 50%，之后每层递减 {@link #alphaStep}（5%），封底 {@link #minAlpha}。<br>
 * <b>颜色</b>：每层按自己的周期（{@link #cycleSeconds} + {@link #cycleSecondsStep}×(层号-1) 秒）
 * 在方块当前装载的物品颜色里循环；没装物品时用 {@link #emptyColor}。
 */
public class DrawFieldDeco extends DrawBlock {

    /** 装饰层数上限（图1~图8） */
    public static final int MAX_LAYERS = 8;

    /** 贴图 atlas key 前缀（模组贴图自动加 "raen-"） */
    private static final String REGION_PREFIX = "raen-applyField-deco";

    /** 第 1 层透明度 */
    public float baseAlpha = 0.5f;
    /** 每多一层递减的透明度 */
    public float alphaStep = 0.05f;
    /** 透明度下限，避免高层完全看不见 */
    public float minAlpha = 0.12f;

    /** 第 1 层「换一种颜色」的周期（秒）。⚠ Time.time 是 tick（60/秒），这里必须 ÷60 */
    public float cycleSeconds = 1.0f;
    /** 每层额外增加的周期（秒），让各层循环速度不同 */
    public float cycleSecondsStep = 0.08f;

    /** 第 2 层起，每层向所属角额外偏移的世界单位（象限方向） */
    public float offsetStep = 2.4f;
    /** 第 2 层起，每层额外放大的比例 */
    public float scaleStep = 0.08f;

    /** 方块内没有物品时装饰的颜色（白色 = 原图颜色） */
    public Color emptyColor = Color.white;

    /** 图1~图8 原始贴图 */
    private final TextureRegion[] regions = new TextureRegion[MAX_LAYERS];
    /** 右上角镜像（水平翻转） */
    private final TextureRegion[] regionsFlipX = new TextureRegion[MAX_LAYERS];
    /** 左下角镜像（垂直翻转） */
    private final TextureRegion[] regionsFlipY = new TextureRegion[MAX_LAYERS];
    /** 右下角镜像（双轴翻转） */
    private final TextureRegion[] regionsFlipXY = new TextureRegion[MAX_LAYERS];

    @Override
    public void load(Block block) {
        for (int i = 0; i < MAX_LAYERS; i++) {
            TextureRegion base = Core.atlas.find(REGION_PREFIX + (i + 1));
            regions[i] = base;
            // TextureRegion.flip 会改 u/v，必须在新副本上翻，不能动原图
            TextureRegion fx = new TextureRegion(base);
            fx.flip(true, false);
            regionsFlipX[i] = fx;

            TextureRegion fy = new TextureRegion(base);
            fy.flip(false, true);
            regionsFlipY[i] = fy;

            TextureRegion fxy = new TextureRegion(base);
            fxy.flip(true, true);
            regionsFlipXY[i] = fxy;
        }
    }

    /** 装饰图标不参与方块图标（方块图标仍用本体贴图） */
    @Override
    public TextureRegion[] icons(Block block) {
        return new TextureRegion[0];
    }

    @Override
    public void draw(Building build) {
        if (!(build instanceof RaenApplyBlock.RaenApplyBlockBuild field)) return;

        int energy = field.energy();
        int layers = Math.min(energy, MAX_LAYERS);
        if (layers <= 0) return;

        Seq<Item> stocked = field.stockedItems();
        float size = build.block.size * 8f;

        // 一层一层叠加：1..layers
        for (int layer = 1; layer <= layers; layer++) {
            TextureRegion base = regions[layer - 1];
            if (base == null || !base.found()) continue;

            float alpha = alphaFor(layer);
            Color color = colorFor(stocked, layer);
            Draw.color(color, alpha);

            if (layer == 1) {
                // 图1 是中心小十字，直接覆盖在本体中央，不镜像
                Draw.rect(base, build.x, build.y, size, size);
            } else {
                // 图2+：图里只有左上角，按角镜像出四份，并向所属象限偏移 + 逐层放大
                float c = offsetStep * (layer - 1);
                float s = size * (1f + scaleStep * (layer - 1));
                // 左上：(-x,-y) 屏幕方向 = 世界 (-x,+y)
                drawCorner(base, build.x - c, build.y + c, s);
                // 右上：水平镜像，世界 (+x,+y)
                drawCorner(regionsFlipX[layer - 1], build.x + c, build.y + c, s);
                // 左下：垂直镜像，世界 (-x,-y)
                drawCorner(regionsFlipY[layer - 1], build.x - c, build.y - c, s);
                // 右下：双轴镜像，世界 (+x,-y)
                drawCorner(regionsFlipXY[layer - 1], build.x + c, build.y - c, s);
            }
        }
        Draw.color();
    }

    private void drawCorner(TextureRegion region, float x, float y, float size) {
        if (region != null && region.found()) Draw.rect(region, x, y, size, size);
    }

    /** 第 layer 层的透明度：50% 起，每层减 5%，封底 {@link #minAlpha} */
    public float alphaFor(int layer) {
        return Math.max(minAlpha, baseAlpha - alphaStep * Math.max(0, layer - 1));
    }

    /** 第 layer 层的颜色：按自己的周期在装载物品颜色里循环；整层四角同色 */
    private Color colorFor(Seq<Item> stocked, int layer) {
        if (stocked == null || stocked.isEmpty()) return emptyColor;
        // Time.time 单位是 tick（60 tick = 1 秒），除以 60 换成秒再除以周期
        float period = cycleSeconds + cycleSecondsStep * (layer - 1);
        int index = (int) Math.floor(Time.time / (60f * period)) % stocked.size;
        Item item = stocked.get(index);
        return item != null && item.color != null ? item.color : emptyColor;
    }
}
