package raen.Bullet;

import arc.struct.Seq;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.gen.Healthc;

/**
 * 效果接口类

 * <pre>
 * // 1) 附加到子弹：子弹命中时自动施加到命中的目标（单位或建筑）
 * RaenEffect.combo(RaenEffectJY.INSTANCE, 1).add(RaenEffectZD.INSTANCE, 2).toBullet(bullet);
 *
 * // 2) 直接施加到单位
 * RaenEffect.combo(RaenEffectJY.INSTANCE, 5).to(unit);
 *
 * // 3) 直接施加到建筑
 * RaenEffect.combo(RaenEffectZD.INSTANCE, 3).to(building);
 * </pre>
 * 其中 {@code (附加:[效果A，1层；效果B，2层])} 对应的就是
 * {@code RaenEffect.combo(效果A, 1).add(效果B, 2)}。
 * <p>
 * 具体的生命周期逻辑由父类 {@link RaenEffectBase} 收集并转发给各个效果实现。
 */
public interface RaenEffect {

    /** 效果唯一标识（同时也是子弹标签名） */
    String id();

    /** 该效果可能写入子弹的所有标签名（例如电荷有正/负两种） */
    default String[] ids() {
        return new String[]{id()};
    }

    /** 文本描述 */
    RaenEffectText text();

    /**
     * 给子弹附加标签。
     *
     * @return 是否附加成功（false 表示该子弹已经有这个效果了）
     */
    boolean attach(Bullet bullet, int stacks);

    /** 读取子弹上携带的该效果层数，0 表示没有 */
    int bulletStacks(Bullet bullet);

    /**
     * 直接施加到目标（单位或建筑），返回本次施加的层数。
     * 没有子弹，所以「子弹伤害」用本效果的 {@code fallbackDamage} 兜底；
     * 队伍默认取目标自己的队伍（需要 AOE 时请用下面那个重载显式传施加方队伍）。
     */
    int applyTo(Healthc target, int stacks);

    /** 直接施加到目标，并指定施加者队伍与伤害基数（sourceDamage &lt;= 0 时用 fallbackDamage 兜底） */
    int applyTo(Healthc target, int stacks, Team sourceTeam, float sourceDamage);

    /** 当前层数 */
    int layer(Healthc target);

    /** 是否有层数 */
    boolean has(Healthc target);

    /** 清除层数（目标死亡、世界重置时调用） */
    void clearLayer(Healthc target);

    default void onTick(float delta) {
    }

    default void onDeath(Healthc target) {
    }

    default void onDraw() {
    }

    default void onReset() {
    }

    static Combo combo() {
        return new Combo();
    }

    static Combo combo(RaenEffect effect, int stacks) {
        return new Combo().add(effect, stacks);
    }

    // ==================== 子弹构造时直接调用：让子弹「发射自带效果」 ====================

    /**
     * 子弹构造时直接调用，给这颗子弹自带一种效果（命中时施加到命中的目标）。
     * <pre>
     * Bullet b = shootType.create(unit, team, x, y, angle);
     * RaenEffect.fire(b, RaenEffectJY.INSTANCE, 1);      // 积焰 ×1
     * </pre>
     * 也可以写在自己的 {@code BulletType} 子类里（Mindustry 的 {@code init(Bullet)} 钩子），
     * 这样该类型的每一发子弹都会自带效果，不用改每个开火点：
     * <pre>
     * new BasicBulletType(...){{
     *     &#64;Override public void init(Bullet b){ RaenEffect.fire(b, RaenEffectJY.INSTANCE, 1); }
     * }};
     * </pre>
     *
     * @return 是否成功附加（子弹上已经有该效果时返回 false）
     */
    static boolean fire(Bullet bullet, RaenEffect effect, int stacks) {
        return effect != null && effect.attach(bullet, stacks);
    }

    /**
     * 子弹构造时直接调用，一次性自带任意多种效果（每种 1 层）。
     * <pre>
     * RaenEffect.fire(b, RaenEffectJY.INSTANCE, RaenEffectZD.INSTANCE);
     * </pre>
     *
     * @return 成功附加的效果种类数
     */
    static int fire(Bullet bullet, RaenEffect... effects) {
        if (bullet == null || effects == null) return 0;
        int count = 0;
        for (int i = 0; i < effects.length; i++) {
            if (effects[i] != null && effects[i].attach(bullet, 1)) count++;
        }
        return count;
    }

    /**
     * 让某种<b>子弹类型</b>「发射自带效果」：绑定之后，该类型生成的每一发子弹都会被自动附加效果。
     * <p>
     * 可以直接写在子弹类型的匿名初始化块里（{@code this} 就是那个 BulletType 实例），
     * 不需要展开成带 {@code init} 的匿名类。
     * <b>必须写在子弹类型自己的 {@code {{ }}} 里</b>：写在 lambda 中时 {@code this} 指向的是外层对象，
     * 会绑错类型。
     * <pre>
     * new BasicBulletType(4f, 20){{
     *     speed = 2f;
     *     lifetime = range / speed + 40;
     *     RaenEffect.bind(this, RaenEffectJY.INSTANCE, 1);                    // 积焰 ×1
     *     RaenEffect.bind(this, RaenEffectZD.INSTANCE, 2);                    // 中毒 ×2
     * }};
     * </pre>
     * 也可以一次绑一组：
     * <pre>
     * RaenEffect.bind(this, RaenEffect.combo(RaenEffectJY.INSTANCE, 1).add(RaenEffectDH.INSTANCE, 1));
     * </pre>
     * 等价但更「传统」的写法是在匿名类里重写 {@code init(Bullet)}（见 {@link #fire(Bullet, RaenEffect, int)}），
     * 两者效果一样；{@code bind} 的好处是能留在 {@code {{ }}} 块里。
     */
    static void bind(BulletType type, RaenEffect effect, int stacks) {
        RaenEffectBase.bindType(type, effect, stacks);
    }

    /** 一次给某个子弹类型绑定任意多个效果（每种 1 层，不限队伍） */
    static void bind(BulletType type, RaenEffect... effects) {
        if (effects == null) return;
        for (int i = 0; i < effects.length; i++) {
            RaenEffectBase.bindType(type, effects[i], 1);
        }
    }

    /** 一次给某个子弹类型绑定一整个效果组合（不限队伍） */
    static void bind(BulletType type, Combo combo) {
        RaenEffectBase.bindType(type, combo);
    }

    /**
     * 只给 ownerTeam 的子弹绑定效果（推荐）。
     * 不指定队伍时，敌方用同一种子弹打你，那些子弹也会带上效果并施加到你身上。
     */
    static void bind(BulletType type, Team ownerTeam, RaenEffect effect, int stacks) {
        RaenEffectBase.bindType(type, ownerTeam, effect, stacks);
    }

    /** 只给 ownerTeam 的子弹绑定任意多个效果（每种 1 层） */
    static void bind(BulletType type, Team ownerTeam, RaenEffect... effects) {
        if (effects == null) return;
        for (int i = 0; i < effects.length; i++) {
            RaenEffectBase.bindType(type, ownerTeam, effects[i], 1);
        }
    }

    /** 只给 ownerTeam 的子弹绑定一整个效果组合 */
    static void bind(BulletType type, Team ownerTeam, Combo combo) {
        RaenEffectBase.bindType(type, ownerTeam, combo);
    }

    /** 取消某个子弹类型的自带效果 */
    static void unbind(BulletType type) {
        RaenEffectBase.unbindType(type);
    }

    // ==================== 目标筛选 ====================

    /**
     * 光环（状态 / 能力）要检测的目标类型。
     * <p>
     * 不检测的类型会<b>整段跳过</b>对应的遍历（子弹的四叉树查询 / 单位查询 / 建筑格子扫描），
     * 所以只想要一部分时选对应的值就是最省性能的做法。
     */
    enum Targets {
        /** 全部：子弹 + 单位 + 建筑 */
        ALL(true, true, true),
        /** 只检测子弹 */
        BULLETS(true, false, false),
        /** 只检测单位 */
        UNITS(false, true, false),
        /** 只检测建筑 */
        BUILDINGS(false, false, true),
        /** 只检测单位与建筑（不检测子弹） */
        UNITS_BUILDINGS(false, true, true),
        /** 子弹 + 单位（不检测建筑） */
        BULLETS_UNITS(true, true, false),
        /** 子弹 + 建筑（不检测单位） */
        BULLETS_BUILDINGS(true, false, true);

        /** 是否检测子弹 */
        public final boolean bullets;
        /** 是否检测单位 */
        public final boolean units;
        /** 是否检测建筑 */
        public final boolean buildings;

        Targets(boolean bullets, boolean units, boolean buildings) {
            this.bullets = bullets;
            this.units = units;
            this.buildings = buildings;
        }
    }

    /** 范围施加时的目标筛选（相对"施加方队伍"） */
    enum Side {
        /** 不限队伍，范围内全部生效 */
        ALL,
        /** 只对施加方自己的队伍生效 */
        FRIENDLY,
        /** 只对非施加方队伍生效（敌人与中立） */
        ENEMY
    }

    /**
     * 效果组合：(附加:[效果A，1层；效果B，2层])
     */
    class Combo {

        public static class Entry {
            public final RaenEffect effect;
            public final int stacks;

            public Entry(RaenEffect effect, int stacks) {
                this.effect = effect;
                this.stacks = stacks;
            }
        }

        public final Seq<Entry> entries = new Seq<>();

        /** 追加一种效果与层数（可无限追加，数量任意） */
        public Combo add(RaenEffect effect, int stacks) {
            if (effect != null && stacks > 0) {
                entries.add(new Entry(effect, stacks));
            }
            return this;
        }

        /** 一次性追加任意多个效果，每个 1 层 */
        public Combo add(RaenEffect... effects) {
            return addAll(1, effects);
        }

        /** 一次性追加任意多个效果，每个都按 stacks 层 */
        public Combo addAll(int stacks, RaenEffect... effects) {
            if (effects != null) {
                for (int i = 0; i < effects.length; i++) {
                    add(effects[i], stacks);
                }
            }
            return this;
        }

        /** 元素个数（= 附加了几种效果） */
        public int size() {
            return entries.size;
        }

        /** 附加到子弹：命中时施加到命中的目标 */
        public Combo toBullet(Bullet bullet) {
            for (int i = 0; i < entries.size; i++) {
                Entry entry = entries.get(i);
                entry.effect.attach(bullet, entry.stacks);
            }
            return this;
        }

        /**
         * 直接施加到单位或建筑。
         * <p>
         * ⚠ 队伍默认取<b>目标自己的队伍</b>，而范围伤害是「打所有不属于 sourceTeam 的目标」，
         * 所以给敌人施加时这里会反过来打到自己人；需要引爆 / AOE 的效果请用
         * {@link #to(Healthc, Team, float)} 显式传施加方队伍。
         * <p>
         * 伤害基数不传 → 用效果自己的 {@code fallbackDamage} 兜底（不会变成 0 伤害）。
         */
        public Combo to(Healthc target) {
            return to(target, RaenEffectBase.teamOf(target), 0f);
        }

        /** 直接施加到单位或建筑，并指定施加者队伍（推荐） */
        public Combo to(Healthc target, Team sourceTeam) {
            return to(target, sourceTeam, 0f);
        }

        /**
         * 直接施加到单位或建筑，并指定施加者队伍与伤害基数。
         * <p>
         * 依赖「子弹伤害」的效果（酸蚀的 30% 额外伤害、电荷的异极引爆等）用的是
         * {@code sourceDamage}；传 0 或负数时会退回该效果的 {@code fallbackDamage}。
         */
        public Combo to(Healthc target, Team sourceTeam, float sourceDamage) {
            if (target == null) return this;
            for (int i = 0; i < entries.size; i++) {
                Entry entry = entries.get(i);
                entry.effect.applyTo(target, entry.stacks, sourceTeam, sourceDamage);
            }
            return this;
        }

        public boolean isEmpty() {
            return entries.size == 0;
        }

        /** 摘要文本，例如：积焰×1、中毒×2 */
        public String describe() {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < entries.size; i++) {
                if (i > 0) builder.append("、");
                Entry entry = entries.get(i);
                builder.append(entry.effect.text().summary(entry.stacks));
            }
            return builder.toString();
        }
    }
}
