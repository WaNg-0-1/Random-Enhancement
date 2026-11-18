package raen.Bullet;

import arc.math.Angles;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.type.Item;

import java.util.HashSet;
import java.util.Set;

/**
 * 碎片子弹工具。
 * <p>
 * 铅 / 塑钢 / 玻璃 都要「生成一枚继承原子弹的子弹」，做法与测试模组的
 * {@code fz.Ty.FZfzly}（复制领域场）一致：<b>直接拿原子弹的类型来 create，再把标签整份复制过去</b>，
 * 所以碎片的外观、尺寸、颜色、拖尾、飞行手感都跟原弹完全一样，不用另外造类型。
 * <pre>
 * Bullet shard = source.type.create(source, team, x, y, angle, damage, 1f);
 * shard.vel.set(Angles.trnsx(angle, speed), Angles.trnsy(angle, speed));
 * shard.time = 0f;
 * RaenBulletMarks.copyBulletMarks(source, shard);
 * </pre>
 * 玻璃碎片额外<b>摘掉会继续「额外生成」的标签</b>（玻璃 / 铅 / 塑钢 / 合金）——
 * 即「去除碎片、闪电等额外生成的属性」，其余加伤、燃烧、中毒等属性照常继承。
 * <p>
 * 两层防套娃：
 * <ol>
 *   <li>所有碎片都带 {@link #NO_TAG}，{@link RaenApplyBlock} 看到它就不再挂新标签；</li>
 *   <li>碎片带 {@link #SHARD} 层数（= 第几代），代数达到 {@link #maxDepth} 后不再产碎片 —— 默认 1 代。</li>
 * </ol>
 */
public class RaenShards {

    /** 不可再挂标签的标记（玻璃碎片也需要，避免又被领域挂一遍） */
    public static final String NO_TAG = "raenItemNoTag";
    /** 碎片代数标记：标签名*代数 */
    public static final String SHARD = "raenItemShard";
    /** 允许的最大代数（1 = 原子弹可以产碎片，碎片本身不再产） */
    public static int maxDepth = 1;
    /** 碎片最短存活时间（tick）：慢速 / 静止碎片不会还没碰到目标就过期 */
    public static float minLifetime = 60f;

    /** 兜底碎片类型：只有拿不到源类型时才会用到 */
    public static BasicBulletType type;

    private RaenShards() {
    }

    /** 建好兜底碎片类型（可重复调用）；第一次 spawn 时也会自动调用 */
    public static void load() {
        if (type != null) return;

        type = new BasicBulletType(1f, 0f, "shell") {{
            speed = 1f;
            lifetime = 90f;
            width = 5f;
            height = 6f;
            hitSize = 3f;

            collides = true;
            collidesTiles = false;
            collidesAir = true;
            collidesGround = true;
            hittable = true;
            // 保持默认行为：可以被护盾吸收（否则碎片会绕过护盾，和布/转命的护盾体系冲突）
            absorbable = true;
            pierce = false;
            pierceBuilding = false;
            keepVelocity = false;
            drag = 0f;
            knockback = 0f;

            hitEffect = Fx.hitBulletSmall;
            despawnEffect = Fx.none;
            shootEffect = Fx.none;
            smokeEffect = Fx.none;
        }};
        // 运行时 new 出来的子弹类型必须补 load()，否则贴图区域为 null → 绘制 NPE
        type.load();
    }

    // ==================== 生成 ====================

    /**
     * 生成一枚碎片，继承原子弹的类型与<b>全部</b>标签（铅 / 塑钢的「触发效果的子弹」）。
     */
    public static Bullet spawn(Bullet source, Team team, float x, float y,
                               float damage, float speed, float angle, boolean copyTags) {
        Bullet bullet = spawn(source == null ? null : source.type, source, team, x, y, damage, speed, angle, copyTags);
        if (bullet == null) return null;

        if (copyTags && source != null) {
            RaenBulletMarks.copyBulletMarks(source, bullet);
            int depth = RaenBulletMarks.getBulletMark(source, SHARD);
            RaenBulletMarks.addBulletMark(bullet, SHARD, depth + 1);
        }
        RaenBulletMarks.addBulletMark(bullet, NO_TAG, 1);
        return bullet;
    }

    /**
     * 生成一枚碎片，按给定的标签快照挂标签（玻璃用：快照里已经摘掉了「额外生成」类标签）。
     *
     * @param marks 标签快照，null / 空 = 不挂标签
     */
    public static Bullet spawn(BulletType sourceType, Team team, float x, float y,
                               float damage, float speed, float angle, Set<String> marks) {
        Bullet bullet = spawn(sourceType, null, team, x, y, damage, speed, angle, false);
        if (bullet == null) return null;

        if (marks != null) {
            for (String mark : marks) {
                RaenBulletMarks.addBulletMark(bullet, mark);
            }
        }
        RaenBulletMarks.addBulletMark(bullet, NO_TAG, 1);
        return bullet;
    }

    /** 兜底碎片（没有源类型时） */
    public static Bullet spawn(Team team, float x, float y, float damage, float speed, float angle) {
        return spawn((BulletType) null, null, team, x, y, damage, speed, angle, false);
    }

    /** 核心实现：按源类型 create + 写速度，不处理标签 */
    public static Bullet spawn(BulletType sourceType, Bullet source, Team team, float x, float y,
                               float damage, float speed, float angle, boolean copyTags) {
        BulletType shardType = sourceType;
        if (shardType == null) {
            load();
            shardType = type;
        }

        // 与 FZfzly 相同：直接拿源类型 create，速度缩放传 1（下面再精确写 vel）
        Bullet bullet = shardType.create(source, team == null ? Team.derelict : team, x, y, angle, damage, 1f);
        if (bullet == null) return null;

        bullet.damage = damage;
        bullet.lifetime = Math.max(bullet.lifetime, minLifetime);
        bullet.vel.set(Angles.trnsx(angle, speed), Angles.trnsy(angle, speed));
        bullet.time = 0f;
        return bullet;
    }

    // ==================== 标签快照 ====================

    /**
     * 复制子弹的标签快照，并摘掉会继续「额外生成」的标签（玻璃 / 铅 / 塑钢 / 合金）。
     * <p>
     * 玻璃碎片是 0.5 秒后才生成的，那时原弹很可能已经被回收复用，
     * 所以必须在这里把标签抄下来，而不是等生成时再去读原弹。
     */
    public static Set<String> snapshotWithoutGenerators(Bullet source) {
        Set<String> marks = new HashSet<>();
        if (source == null) return marks;

        Object data = source.data;
        if (data instanceof Set) {
            for (Object raw : (Set<?>) data) {
                if (raw instanceof String) marks.add((String) raw);
            }
        } else if (data instanceof String) {
            marks.add((String) data);
        }

        marks.removeIf(RaenShards::isGenerator);
        return marks;
    }

    /** 该标签是否会继续「额外生成」东西 */
    private static boolean isGenerator(String raw) {
        return sameTag(raw, Items.metaglass)
                || sameTag(raw, Items.lead)
                || sameTag(raw, Items.plastanium)
                || sameTag(raw, Items.surgeAlloy);
    }

    private static boolean sameTag(String raw, Item item) {
        RaenItemTag def = RaenItemTags.def(item);
        if (def == null || raw == null) return false;
        if (raw.equals(def.tag)) return true;

        // 兼容「标签*层数」写法
        return raw.startsWith(def.tag)
                && raw.length() > def.tag.length()
                && raw.charAt(def.tag.length()) == RaenBulletMarks.STACK_SEPARATOR.charAt(0);
    }

    /** 该子弹是否已经用完了碎片代数（不允许再产碎片） */
    public static boolean depthExceeded(Bullet source) {
        return source != null && RaenBulletMarks.getBulletMark(source, SHARD) >= maxDepth;
    }
}
