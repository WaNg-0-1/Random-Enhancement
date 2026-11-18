package raen.Bullet;

import arc.Core;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.meta.BuildVisibility;

/**
 * 临时墙：沙子触发时在目标处立起来的一堵「一次性」墙。
 * <p>
 * 特点：
 * <ul>
 *   <li>生命与存在时间<b>按次设置</b>（生命 = 50 + 层数，存在 = 5 + 层数×0.1 秒）；</li>
 *   <li>可被攻击：血量归零直接消失；时间到了也会自己消失；</li>
 *   <li>不可建造：{@link BuildVisibility#hidden}，菜单里不会出现，也没有配方；</li>
 *   <li>有专属贴图 {@code sprites/temp-wall.png}（沙砖墙），{@link #load()} 里按 atlas key
 *       {@code raen-temp-wall} 加载；找不到时回退到原版石墙，避免出现粉黑缺失贴图。</li>
 * </ul>
 * 放置入口只有 {@link #place(Team, float, float, float, float)}，由 {@link RaenItemHit} 在沙子触发时调用。
 */
public class RaenTempWall extends Block {

    /**
     * 存档数据版本（写进 {@link Building#version()}，读取时作为 {@code revision} 回传）。
     * <p>
     * 0 = 本类还没有自定义字段时的旧存档；1 = 带 maxHp / life / health。
     * 以后再加字段务必把它 +1，并在 {@link TempWallBuild#read} 里按 revision 逐级读，
     * 否则旧存档会「读到下一堵墙的数据」。
     */
    public static final byte SAVE_VERSION = 1;

    /**
     * 命中格被占 / 压着单位时，最多向外偏移几格找空位。
     * <p>
     * 0 = 只认命中格，放不下就不放；调大后墙会出现在附近最近的空地上。
     */
    public int maxOffsetTiles = 3;

    /** 找位置时复用，避免每次触发都 new */
    private final Seq<Tile> spots = new Seq<>();

    public RaenTempWall(String name) {
        super(name);

        size = 1;
        health = 60;
        update = true;
        solid = true;              // 是「墙」：挡路、挡子弹
        destructible = true;
        canOverdrive = false;
        // 被打爆后不要进重建队列：否则幻型(Poly)之类的支援单位会自动把它再盖回来
        rebuildable = false;
        // 即使被禁用也要走 updateTile：不然存在时间会冻住，墙永远不消失
        noUpdateDisabled = true;
        buildVisibility = BuildVisibility.hidden;   // 不可建造
        //requirements(Category.effect, ItemStack.with());

        buildType = TempWallBuild::new;
    }

    /** 本方块贴图在 atlas 中的 key（= mod 名前缀 + 贴图文件名 temp-wall）。 */
    private static final String REGION = "raen-temp-wall";

    @Override
    public void load() {
        super.load();
        // 专属沙墙贴图；模组未打包该贴图时回退到原版石墙，避免粉黑缺失贴图。
        region = Core.atlas.find(REGION, "stone-wall");
    }

    @Override
    public void loadIcon() {
        super.loadIcon();
        region = Core.atlas.find(REGION, "stone-wall");
        fullIcon = uiIcon = region;
    }

    /**
     * 在指定位置立一堵临时墙。
     * <p>
     * 命中格不合适（压着单位 / 已有方块 / 是液体）时会向内向外一圈圈找最近的空位，
     * 偏移上限由 {@link #maxOffsetTiles} 决定；实在没有空位就放弃（不生成）。
     *
     * @param hp      这一堵墙的最大生命
     * @param seconds 存在时间（秒）
     * @return 是否成功放下
     */
    public boolean place(Team team, float x, float y, float hp, float seconds) {
        if (team == null || Vars.world == null) return false;

        Tile tile = findSpot(Vars.world.tileWorld(x, y));
        if (tile == null) return false;

        tile.setBlock(this, team);

        Building build = tile.build;
        if (!(build instanceof TempWallBuild)) return false;

        TempWallBuild wall = (TempWallBuild) build;
        wall.maxHp = Math.max(1f, hp);
        wall.health = wall.maxHp;
        wall.life = Math.max(1f, seconds * 60f);
        return true;
    }

    // ==================== 选址 ====================

    /** 以命中格为中心，按圈向外找第一个能放下的格子（同圈内随机取一个，避免总往同一个方向偏）。 */
    private Tile findSpot(Tile center) {
        if (center == null) return null;
        if (isFree(center)) return center;

        int max = Math.max(0, maxOffsetTiles);
        for (int r = 1; r <= max; r++) {
            spots.clear();
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    // 只看这一圈：内圈在上一轮已经确认全是死路
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    Tile tile = Vars.world.tile(center.x + dx, center.y + dy);
                    if (isFree(tile)) spots.add(tile);
                }
            }
            if (spots.size > 0) return spots.get(Mathf.random(spots.size - 1));
        }
        return null;
    }

    /** 这一格能不能立墙：空地块 + 非液体 + 不压单位。 */
    private boolean isFree(Tile tile) {
        if (tile == null || !tile.block().isAir()) return false;
        if (tile.floor() != null && (tile.floor().isLiquid || tile.floor().solid)) return false;
        if (tile.solid()) return false;
        return !blockedByUnit(tile);
    }

    /**
     * 这一格上是否压着地面单位（选址用）。
     * <p>
     * 原版在 {@code UnitComp.update()} 里有一条：<pre>if(tile != null && !canPassOn()) kill();</pre>
     * 也就是说「单位脚下的格子变成实体方块」会直接把单位判死（能 boost 的飞行单位除外）。
     * 所以这里两种都避开：
     * <ul>
     *   <li>单位正站在这一格 → 放下就是压死；</li>
     *   <li>碰撞箱压到这一格 → 即使不致命也会被挤出去，一样不要。</li>
     * </ul>
     */
    public static boolean blockedByUnit(Tile tile) {
        return checkUnits(tile, false);
    }

    /**
     * 是否有地面单位正**站在**这一格上、且会因此被压死（做兜底让位用）。
     * <p>
     * 和 {@link #blockedByUnit} 的区别：
     * <ul>
     *   <li>只看「正站在这一格」，压在边上不算（那种引擎会把它挤出去，不会死）；</li>
     *   <li>多足单位（{@code allowLegStep}）走的是 {@code legsSolid}，本来就能踩在方块上，不算；</li>
     *   <li>空中的单位（含爬行 / 正在 boost 的）同理不算。</li>
     * </ul>
     */
    public static boolean unitStandingOn(Tile tile) {
        return checkUnits(tile, true);
    }

    /**
     * @param fatalOnly true = 只找「会触发原版压死判定」的单位；false = 连带避开碰撞箱重叠的单位
     */
    private static boolean checkUnits(Tile tile, boolean fatalOnly) {
        if (tile == null || Groups.unit == null) return false;

        final boolean[] blocked = {false};
        final float cx = tile.worldx(), cy = tile.worldy();

        Groups.unit.intersect(cx - 8f, cy - 8f, 16f, 16f, unit -> {
            if (blocked[0] || unit == null || unit.dead()) return;
            if (unit.isFlying()) return;                 // 空军 / 爬行 / boost 中：不在墙里
            if (fatalOnly && unit.type.allowLegStep) return;   // 多足能踩在方块上，不会被压死

            // ① 正站在这格上：引擎会直接 kill
            if (unit.tileX() == tile.x && unit.tileY() == tile.y) {
                blocked[0] = true;
                return;
            }
            if (fatalOnly) return;

            // ② 碰撞箱压到这一格：会被挤出，同样避开（+1 像素容差）
            float half = unit.hitSize() * 0.5f + 1f;
            if (Math.abs(unit.x - cx) < 4f + half && Math.abs(unit.y - cy) < 4f + half) {
                blocked[0] = true;
            }
        });

        return blocked[0];
    }

    public static class TempWallBuild extends Building {
        /** 这一堵墙的最大生命（按次设置，所以不能直接用 block.health） */
        public float maxHp = 60f;
        /** 剩余存在时间（tick） */
        public float life = 300f;
        /** 落地后的前几帧做一次「是否压着单位」的兜底检查 */
        private float guard = 12f;

        @Override
        public float maxHealth() {
            return maxHp;
        }

        @Override
        public void updateTile() {
            // 兜底：读档 / 刷兵 / 传送等极端情况下单位可能正好站在墙里，
            // 原版会把单位判死，这里改成让墙自己让位（只查前几帧，避免长期开销）。
            // 只认「站在这一格」，压在边上但没站进来的那种交给引擎挤出去。
            if (guard > 0f) {
                guard -= Time.delta;
                if (unitStandingOn(tile)) {
                    kill();
                    return;
                }
            }

            if (health <= 0f) {
                kill();
                return;
            }

            if (life > 0f) life -= Time.delta;
            if (life <= 0f) kill();
        }

        /** 存档版本：写进数据块头，读取时作为 revision 回传，用来兼容旧档。 */
        @Override
        public byte version() {
            return SAVE_VERSION;
        }

        @Override
        public void write(Writes write) {
            super.write(write);
            write.f(maxHp);
            write.f(life);
            // health 必须自己存一份：readBase 会做 health = min(read.f(), block.health)，
            // 把超过 60 的血量钳掉，读回来就变成残血墙。
            write.f(health);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);

            if (revision >= 1) {
                maxHp = Math.max(1f, read.f());
                life = read.f();
                health = Mathf.clamp(read.f(), 0f, maxHp);
            } else {
                // 旧档没有这三个字段：拿被钳过的血量当上限，避免出现「血量 > 上限」或永久存在
                maxHp = Math.max(block.health, health);
                health = Math.min(health, maxHp);
                life = Math.min(life, 300f);
            }

            // 读档回来的墙也要做一次压单位检查
            guard = 12f;
        }
    }
}
