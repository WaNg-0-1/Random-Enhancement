package raen.Bullet;

import arc.math.Angles;
import arc.struct.ObjectSet;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.type.Item;
import raen.Bullet.effect.RaenEffectZJ;

import java.util.HashSet;
import java.util.Set;

/**
 * 碎片子弹工具。
 * <p>
 * 铅 / 塑钢 / 玻璃 都要「生成一枚继承原子弹的子弹」，做法与测试模组的
 * {@code fz.Ty.FZfzly}（复制领域场）一致：<b>直接拿原子弹的类型来 create</b>，
 * 所以碎片的外观、尺寸、颜色、拖尾、飞行手感都跟原弹完全一样，不用另外造类型。
 * <pre>
 * Bullet shard = source.type.create(source, team, x, y, angle, damage, 1f);
 * shard.vel.set(Angles.trnsx(angle, speed), Angles.trnsy(angle, speed));
 * shard.time = 0f;
 * </pre>
 * 三类碎片的标签策略各不相同（<b>别为了「统一」把它们合并</b>，各有明确理由）：
 * <ul>
 *   <li><b>铅</b>（{@link #spawn(Bullet, Team, float, float, float, float, float, boolean)}）：
 *       整份继承，它是「延后触发的一发同标签子弹」，继承才是设计意图；</li>
 *   <li><b>塑钢</b>（{@link #spawnChase(Bullet, Team, float, float, float, float, float)}）：
 *       <b>只带追击</b>。一次 3-5 颗碎片就是 3-5 次独立命中，若继承合金 / 布之类的标签会高频刷
 *       闪电、电荷、连锁与转命，强度失控（v160.3.96 实测后收紧）；</li>
 *   <li><b>玻璃</b>（{@link #snapshotWithoutGenerators(Bullet)}）：摘掉会继续「额外生成」的标签
 *       （玻璃 / 铅 / 塑钢 / 合金），其余加伤、燃烧、中毒等照常继承。</li>
 * </ul>
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
     * 生成一枚碎片，继承原子弹的类型与<b>除「生成类」以外的全部</b>标签（铅的「延迟触发子弹」）。
     * <p>
     * ⚠ 不再用 {@code copyBulletMarks} 整份抄：碎片一旦继承 铅 / 塑钢 / 玻璃 / 合金，
     * 就又能自己产碎片与闪电（虽然 {@link #maxDepth} 拦得住「碎片生碎片」，但闪电之类拦不住），
     * 所以这里改成「快照 → 剥掉 {@link RaenItemTags#generates} 的标签 → 再写」。
     */
    public static Bullet spawn(Bullet source, Team team, float x, float y,
                               float damage, float speed, float angle, boolean copyTags) {
        Bullet bullet = spawn(source == null ? null : source.type, source, team, x, y, damage, speed, angle, false);
        if (bullet == null) return null;

        if (copyTags && source != null) {
            for (String mark : snapshotWithoutGenerators(source)) {
                RaenBulletMarks.addBulletMark(bullet, mark);
            }
            int depth = RaenBulletMarks.getBulletMark(source, SHARD);
            RaenBulletMarks.addBulletMark(bullet, SHARD, depth + 1);
        }
        RaenBulletMarks.addBulletMark(bullet, NO_TAG, 1);
        return bullet;
    }

    /**
     * 生成一枚<b>只带追击标签</b>的碎片（塑钢专用）。
     * <p>
     * 为什么不整份继承标签：塑钢一次产 3-5 个碎片，碎片伤害虽只有原弹的 3%-10%，
     * 但<b>每颗碎片都是一次独立的命中结算</b>。只要原弹带合金（闪电 / 电荷 / 连锁）、布（转命）
     * 这类「命中即叠层 / 即触发」的标签，一次塑钢命中就会凭空多出 3-5 次触发，
     * 叠层速度远超设计预期（实测「塑钢 + 合金」会高频刷闪电、电荷与连锁，过于强势）。
     * <p>
     * 所以塑钢碎片只保留 <b>{@link RaenEffectZJ#ID 追击}</b>：命中时照常叠追击层，
     * 其余标签（伤害加成、四属性、其它物品行为）一概不继承。
     *
     * @param source 源子弹（碎片外观与速度继承它；层数只抄追击与代数）
     */
    public static Bullet spawnChase(Bullet source, Team team, float x, float y,
                                    float damage, float speed, float angle) {
        return spawnChase(source == null ? null : source.type, team, x, y, damage, speed, angle,
                source == null ? 0 : RaenBulletMarks.getBulletMark(source, RaenEffectZJ.ID),
                source == null ? 0 : RaenBulletMarks.getBulletMark(source, SHARD));
    }

    /**
     * 同上，但来源用<b>事先抄好的值</b>（{@code sourceType} / 追击层数 / 代数）。
     * <p>
     * 塑钢碎片是随机延迟生成的，那时源子弹很可能已经被池子回收复用，
     * 不能再从它身上读 {@code type} 或标签层数，所以延迟路径走这个重载。
     */
    public static Bullet spawnChase(BulletType sourceType, Team team, float x, float y,
                                    float damage, float speed, float angle, int chaseStacks, int depth) {
        Bullet bullet = spawn(sourceType, null, team, x, y, damage, speed, angle, false);
        if (bullet == null) return null;

        // 只抄「追击」的层数；碎片代数继续累加，防止任何生成类标签回流
        if (chaseStacks > 0) RaenBulletMarks.addBulletMark(bullet, RaenEffectZJ.ID, chaseStacks);
        RaenBulletMarks.addBulletMark(bullet, SHARD, depth + 1);
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
        BulletType shardType = shardTypeOf(sourceType);

        // 与 FZfzly 相同：直接拿源类型 create，速度缩放传 1（下面再精确写 vel）
        Bullet bullet = shardType.create(source, team == null ? Team.derelict : team, x, y, angle, damage, 1f);
        if (bullet == null) return null;

        bullet.damage = damage;
        bullet.lifetime = Math.max(bullet.lifetime, minLifetime);
        bullet.vel.set(Angles.trnsx(angle, speed), Angles.trnsy(angle, speed));
        bullet.time = 0f;
        return bullet;
    }

    /**
     * 碎片用哪个类型 create。
     * <p>
     * ⚠ 关键：源类型如果自己会「生东西」（{@code fragBullet} / {@code intervalBullet} / {@code lightning}），
     * <b>绝不能直接拿它 create</b> —— 碎片是「同类型的新子弹」，会把这些类型级行为一起带走：
     * 实测「劫难 afflict」的主子弹 {@code bulletInterval = 3} + {@code intervalBullets = 2}（≈40 发/秒）
     * 加 {@code fragBullets = 20}，一颗碎片就能分裂出一片子弹海，配合塑钢再次生碎片直接内存溢出。
     * <p>
     * 这种情况退回干净的兜底类型（{@link #type}）：外观变成普通小弹丸，但不会再分裂 / 打闪电。
     * 普通子弹（没有这些字段）照旧沿用源类型，外观手感不变。
     */
    public static BulletType shardTypeOf(BulletType sourceType) {
        load();
        if (sourceType == null) return type;
        return generates(sourceType) ? type : sourceType;
    }

    /**
     * 这个子弹类型自己会不会「生东西」：分裂弹（{@code fragBullet}）、周期分裂弹（{@code intervalBullet}）、
     * 自带闪电（{@code lightning > 0}）。碎片的类型选择与「分裂弹不给生成类标签」都用这一条判定。
     */
    public static boolean generates(BulletType type) {
        return type != null
                && (type.fragBullet != null || type.intervalBullet != null || type.lightning > 0);
    }

    /** 被别的子弹当作「分裂弹 / 周期分裂弹」的类型（内容表扫出来的一次性缓存） */
    private static ObjectSet<BulletType> fragSpawned;

    /**
     * 这发子弹是不是「由别的子弹分裂出来的」（某个类型的 {@code fragBullet} / {@code intervalBullet}）。
     * <p>
     * 这类子弹数量极大（劫难 afflict ≈ 40 发/秒），再给它们挂「生成类」标签就会滚雪球；
     * {@link RaenApplyBlock} 用这条判定跳过 铅 / 塑钢 / 玻璃 / 合金。
     * <p>
     * ⚠ 只能按<b>类型</b>判：引擎没有给子弹留「我是谁的碎片」这条信息。所以同一个类型如果
     * 既被炮塔直射、又被别的子弹当分裂弹用，也会被一并当成分裂弹（保守方向：宁可少挂）。
     */
    public static boolean isFragSpawned(BulletType type) {
        if (type == null) return false;
        if (fragSpawned == null) {
            fragSpawned = new ObjectSet<>();
            if (Vars.content != null) {
                for (BulletType t : Vars.content.bullets()) {
                    if (t == null) continue;
                    if (t.fragBullet != null) fragSpawned.add(t.fragBullet);
                    if (t.intervalBullet != null) fragSpawned.add(t.intervalBullet);
                }
            }
        }
        return fragSpawned.contains(type);
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
