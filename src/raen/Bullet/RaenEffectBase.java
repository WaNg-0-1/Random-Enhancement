package raen.Bullet;

import arc.Events;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Font.FontData;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.World;
import mindustry.entities.Fires;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.game.EventType.BuildDamageEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.UnitDamageEvent;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Healthc;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.type.StatusEffect;
import mindustry.ui.Fonts;
import mindustry.world.Tile;

/**
 * 效果父类
 * <p>
 *
 * <p>
 * 负责：
 * <ul>
 *   <li>收集并提供「子弹数据」与「命中的目标数据」（{@link #ctxBullet} / {@link #ctxTarget} 等上下文）；</li>
 *   <li>统一管理各效果在单位 / 建筑上的层数；</li>
 *   <li>统一注册事件分发（子弹命中单位、子弹命中建筑、目标死亡、世界重置、全局更新、全局绘制）；</li>
 *   <li>提供范围伤害、真实伤害、状态施加等公共工具方法。</li>
 * </ul>
 * 子类继承本类并实现 {@link #onHit()} 等回调即可。
 * <p>
 * <b>暂停修复：</b> Mindustry 的 {@code Trigger.update} 在暂停时仍会触发，而 {@code Time.delta}
 * 暂停时不会刷新（会保留上一帧的值），所以所有倒计时都会继续走。这里在全局更新里
 * 判断 {@code Vars.state.isPaused()}，暂停时直接跳过，倒计时随之暂停。
 */
public abstract class RaenEffectBase implements RaenEffect {

    // ==================== 注册表 / 事件分发 ====================
    private static final Seq<RaenEffectBase> REGISTRY = new Seq<>();
    private static boolean initialized = false;

    private static final ObjectSet<Building> tmpBuildings = new ObjectSet<>();

    // ==================== 本次触发的上下文数据 ====================
    /** 触发本次效果的子弹（直接施加时为 null） */
    protected Bullet ctxBullet;
    /** 本次命中的目标（单位或建筑） */
    protected Healthc ctxTarget;
    /** 施加者队伍 */
    protected Team ctxTeam;
    /** 子弹伤害 */
    protected float ctxDamage;
    /** 命中点 / 来源坐标 */
    protected float ctxX, ctxY;
    /** 本次施加的层数 */
    protected int ctxStacks = 1;

    /**
     * 直接施加（{@code .to(...)} / {@code applyTo(...)}，没有子弹）且调用方没有指定 sourceDamage 时，
     * 用来代替「子弹伤害」的兜底值。
     * <p>
     * 依赖子弹伤害的效果（例如酸蚀的 30% 额外伤害、电荷的异极引爆）必须靠它才不会静默变成 0 伤害；
     * 各效果可以按自己的设计覆盖（例如电荷直接用 {@code fieldTriggerDamage}）。
     * 想「直接施加只叠层、完全不产生伤害」就把它设成 0。
     */
    public float fallbackDamage = 10f;

    /** 通用层数存储（单位与建筑共用） */
    protected final ObjectMap<Healthc, Integer> layers = new ObjectMap<>();

    /** 复用的文本颜色，避免每次绘制都新建对象 */
    protected final Color tmpColor = new Color();

    // ==================== 初始化 ====================

    /** 注册事件分发（可重复调用，只会执行一次） */
    public static void init() {
        if (initialized) return;
        initialized = true;

        // 子弹命中单位
        Events.on(UnitDamageEvent.class, e -> dispatchHit(e.bullet, e.unit));
        // 子弹命中建筑（Mindustry 会为每次子弹对建筑的伤害触发该事件）
        Events.on(BuildDamageEvent.class, e -> dispatchHit(e.source, e.build));
        // 目标死亡
        Events.on(UnitDestroyEvent.class, e -> dispatchDeath(e.unit));
        Events.on(BlockDestroyEvent.class, e -> {
            if (e.tile != null) dispatchDeath(e.tile.build);
        });
        // 世界重置
        Events.on(WorldLoadEvent.class, e -> resetAll());
        // 全局更新 / 绘制
        Events.run(Trigger.update, RaenEffectBase::updateAll);
        // 子弹类型绑定：世界更新结束后再扫一遍，保证本帧刚生成的新子弹也立刻带上效果
        Events.run(Trigger.afterGameUpdate, RaenEffectBase::markBoundBullets);
        Events.run(Trigger.draw, RaenEffectBase::drawAll);
    }

    // ==================== 子弹类型绑定（「发射自带效果」） ====================

    /** 子弹类型 → 自带的效果；由 {@link RaenEffect#bind} 写入 */
    private static final ObjectMap<BulletType, BoundType> TYPE_EFFECTS = new ObjectMap<>();

    /** 某个子弹类型的绑定项 */
    private static class BoundType {
        final RaenEffect.Combo combo = RaenEffect.combo();
        /** 只给这个队伍的子弹附加；null = 不限队伍 */
        Team team;

        BoundType(Team team) {
            this.team = team;
        }
    }

    /**
     * 让某种子弹类型「发射自带效果」：该类型之后生成的每一发子弹都会被自动附加这些效果。
     * <p>
     * 可以直接写在子弹类型的匿名初始化块里（{@code this} 就是那个 BulletType 实例）：
     * <pre>
     * new BasicBulletType(4f, 20){{
     *     speed = 2f;
     *     RaenEffect.bind(this, RaenEffectJY.INSTANCE, 1);              // 不限队伍
     *     RaenEffect.bind(this, myTeam, RaenEffectZD.INSTANCE, 2);      // 只给自己队伍
     * }};
     * </pre>
     * 挂载时机：{@code Trigger.update} 与 {@code Trigger.afterGameUpdate} 各扫一次
     * （后者保证本帧新生成的子弹也能立刻带上），暂停时不扫。
     * <p>
     * ⚠ 不限队伍时，敌方用同一种子弹打你，那些子弹也会带上效果并施加到你身上；
     * 只给自己用就传 ownerTeam（推荐）。
     */
    public static void bindType(BulletType type, RaenEffect effect, int stacks) {
        bindType(type, null, effect, stacks);
    }

    /** 只给 ownerTeam 的子弹绑定效果（其它队伍用同一种子弹不会带上） */
    public static void bindType(BulletType type, Team ownerTeam, RaenEffect effect, int stacks) {
        if (type == null || effect == null || stacks <= 0) return;
        init();

        BoundType bound = TYPE_EFFECTS.get(type);
        if (bound == null) {
            bound = new BoundType(ownerTeam);
            TYPE_EFFECTS.put(type, bound);
        } else if (ownerTeam != null) {
            bound.team = ownerTeam;
        }
        bound.combo.add(effect, stacks);
    }

    /** 合并一整个效果组合到某个子弹类型上（不限队伍） */
    public static void bindType(BulletType type, RaenEffect.Combo combo) {
        bindType(type, null, combo);
    }

    /** 合并一整个效果组合到某个子弹类型上，只给 ownerTeam 的子弹 */
    public static void bindType(BulletType type, Team ownerTeam, RaenEffect.Combo combo) {
        if (type == null || combo == null || combo.isEmpty()) return;
        init();

        BoundType bound = TYPE_EFFECTS.get(type);
        if (bound == null) {
            bound = new BoundType(ownerTeam);
            TYPE_EFFECTS.put(type, bound);
        } else if (ownerTeam != null) {
            bound.team = ownerTeam;
        }
        for (int i = 0; i < combo.entries.size; i++) {
            RaenEffect.Combo.Entry entry = combo.entries.get(i);
            bound.combo.add(entry.effect, entry.stacks);
        }
    }

    /** 取消某个子弹类型的自带效果 */
    public static void unbindType(BulletType type) {
        if (type != null) TYPE_EFFECTS.remove(type);
    }

    /** 给所有「已绑定效果」的子弹补上标签（子弹是池化复用的，标签只加不删，attach 自带幂等） */
    private static void markBoundBullets() {
        if (TYPE_EFFECTS.size == 0) return;
        if (Vars.state == null || !Vars.state.isGame() || Vars.state.isPaused()) return;
        if (Vars.world == null) return;

        for (Bullet bullet : Groups.bullet) {
            if (bullet == null) continue;

            BoundType bound = TYPE_EFFECTS.get(bullet.type);
            if (bound == null || bound.combo.isEmpty()) continue;
            // 指定了队伍就只给该队伍的子弹附加，避免敌方用同一种子弹反过来给你挂效果
            if (bound.team != null && bullet.team != bound.team) continue;

            for (int i = 0; i < bound.combo.entries.size; i++) {
                RaenEffect.Combo.Entry entry = bound.combo.entries.get(i);
                entry.effect.attach(bullet, entry.stacks);
            }
        }
    }

    /** 注册一个效果（效果类的静态块里调用） */
    public static void register(RaenEffectBase effect) {
        init();
        if (effect != null && !REGISTRY.contains(effect)) {
            REGISTRY.add(effect);
        }
    }

    /** 所有已注册的效果。只读，调用方不要修改返回的 Seq。 */
    public static Seq<RaenEffectBase> all() {
        init();
        return REGISTRY;
    }

    // ==================== 分发 ====================

    private static void dispatchHit(Bullet bullet, Healthc target) {
        if (bullet == null || target == null || target.dead()) return;
        Team targetTeam = teamOf(target);
        if (bullet.team != null && bullet.team == targetTeam) return;

        for (int i = 0; i < REGISTRY.size; i++) {
            RaenEffectBase effect = REGISTRY.get(i);
            int stacks = effect.bulletStacks(bullet);
            if (stacks <= 0) continue;
            effect.trigger(bullet, target, bullet.team, bullet.damage, bullet.x, bullet.y, stacks);
        }
    }

    /**
     * 供「主动击杀」路径手动补一次死亡分发。
     * <p>
     * 正常情况下 {@code Healthc.kill()} 会同步触发 UnitDestroyEvent / BlockDestroyEvent，
     * 但个别引擎路径（例如某些单位不派发 destroy）不会，此时调用方可以补一次，
     * 保证死亡类效果（毒圈、注入引爆等）仍能拿到击杀瞬间的层数。
     * <p>
     * <b>幂等：</b>{@link #dispatchDeath} 会给所有效果 clearLayer，重复调用时
     * 各效果 {@code has(target)} 均为 false，不会再触发第二次 onDeath。
     */
    public static void notifyDeath(Healthc target) {
        if (target == null) return;
        dispatchDeath(target);
    }

    private static void dispatchDeath(Healthc target) {
        if (target == null) return;
        for (int i = 0; i < REGISTRY.size; i++) {
            RaenEffectBase effect = REGISTRY.get(i);
            if (!effect.has(target)) {
                effect.clearLayer(target);
                continue;
            }
            effect.ctxBullet = null;
            effect.ctxTarget = target;
            effect.ctxTeam = teamOf(target);
            effect.ctxDamage = 0f;
            effect.ctxX = target.x();
            effect.ctxY = target.y();
            effect.ctxStacks = effect.layer(target);
            effect.onDeath(target);
            effect.clearLayer(target);
        }
    }

    private static void updateAll() {
        if (Vars.state == null || !Vars.state.isGame() || Vars.state.isPaused()) return; // ★ 暂停时不再计时
        if (Vars.world == null) return;

        float delta = Time.delta;
        if (delta <= 0f) return;

        for (int i = 0; i < REGISTRY.size; i++) {
            REGISTRY.get(i).onTick(delta);
        }

        // 给「绑定了自带效果」的子弹补标签
        markBoundBullets();
    }

    private static void drawAll() {
        for (int i = 0; i < REGISTRY.size; i++) {
            REGISTRY.get(i).onDraw();
        }
    }

    private static void resetAll() {
        for (int i = 0; i < REGISTRY.size; i++) {
            RaenEffectBase effect = REGISTRY.get(i);
            effect.layers.clear();
            effect.onReset();
        }
    }

    // ==================== 对外接口实现 ====================

    /**
     * 由事件分发调用：把上下文装填好后触发子类的 {@link #onHit()}。
     */
    public final int trigger(Bullet bullet, Healthc target, Team team, float damage, float x, float y, int stacks) {
        this.ctxBullet = bullet;
        this.ctxTarget = target;
        this.ctxTeam = team;
        this.ctxDamage = damage;
        this.ctxX = x;
        this.ctxY = y;
        this.ctxStacks = Math.max(1, stacks);
        onHit();
        return ctxStacks;
    }

    @Override
    public boolean attach(Bullet bullet, int stacks) {
        if (bullet == null || stacks <= 0) return false;
        if (bulletStacks(bullet) > 0) return false;
        markBullet(bullet, id(), stacks);
        return true;
    }

    @Override
    public final int bulletStacks(Bullet bullet) {
        return readStacks(bullet, ids());
    }

    @Override
    public int applyTo(Healthc target, int stacks) {
        return applyTo(target, stacks, teamOf(target), 0f);
    }

    @Override
    public int applyTo(Healthc target, int stacks, Team sourceTeam, float sourceDamage) {
        if (target == null || stacks <= 0 || target.dead()) return 0;
        // 没有子弹：调用方传了伤害就用它，没传就退回本效果的 fallbackDamage（否则按子弹伤害缩放的伤害会静默变成 0）
        float damage = sourceDamage > 0f ? sourceDamage : fallbackDamage;
        return trigger(null, target, sourceTeam == null ? teamOf(target) : sourceTeam,
                damage, target.x(), target.y(), stacks);
    }

    @Override
    public int layer(Healthc target) {
        if (target == null) return 0;
        Integer value = layers.get(target, 0);
        return value == null ? 0 : value;
    }

    @Override
    public boolean has(Healthc target) {
        return layer(target) > 0;
    }

    @Override
    public void clearLayer(Healthc target) {
        if (target != null) layers.remove(target);
    }

    /** 设置层数（<=0 即移除） */
    public void setLayer(Healthc target, int value) {
        if (target == null) return;
        if (value <= 0) layers.remove(target);
        else layers.put(target, value);
    }

    /** 叠加层数 */
    public void addLayer(Healthc target, int amount) {
        if (target != null && amount > 0) setLayer(target, layer(target) + amount);
    }

    /** 命中时调用的核心逻辑，由子类实现 */
    protected abstract void onHit();

    /** 默认死亡清理 */
    @Override
    public void onDeath(Healthc target) {
        clearLayer(target);
    }

    // ==================== 子弹标签读写（统一接入 RaenBulletMarks） ====================

    /**
     * 给子弹写入效果标签。层数为 1 时只写 id，保证与旧标签完全兼容。
     * 底层走 {@link RaenBulletMarks}，因此：
     * <ul>
     *   <li>与分裂 / 折射 / 反射等方块共用同一套标签，且会被 {@code copyBulletMarks} 复制；</li>
     *   <li>不会覆盖别的方块写在 {@code bullet.data} 上的去重标记（避免无限套娃）。</li>
     * </ul>
     */
    public static void markBullet(Bullet bullet, String id, int stacks) {
        RaenBulletMarks.addBulletMark(bullet, id, stacks);
    }

    /** 读取子弹上某个 / 某些标签的层数，0 表示没有 */
    public static int readStacks(Bullet bullet, String... ids) {
        if (bullet == null || ids == null) return 0;
        for (int i = 0; i < ids.length; i++) {
            int stacks = RaenBulletMarks.getBulletMark(bullet, ids[i]);
            if (stacks > 0) return stacks;
        }
        return 0;
    }

    /** 检查子弹是否携带指定标签（与 {@link RaenBulletMarks#hasBulletMark} 一致） */
    public static boolean hasBulletMark(Bullet bullet, String... ids) {
        return readStacks(bullet, ids) > 0;
    }

    // ==================== 公共工具 ====================

    /** 目标队伍（单位与建筑都是 Teamc） */
    public static Team teamOf(Healthc target) {
        if (target instanceof Teamc) return ((Teamc) target).team();
        return Team.derelict;
    }

    /** 目标是否有效 */
    public static boolean valid(Healthc target) {
        return target != null && !target.dead() && target.health() > 0f;
    }

    /** 目标体积（用于绘制层数文字时的偏移） */
    public static float targetSize(Healthc target) {
        if (target instanceof Unit) return ((Unit) target).hitSize;
        if (target instanceof Building) return ((Building) target).block.size * 8f;
        return 8f;
    }

    /** 真实伤害：无视护甲与护盾，直接扣血；扣完则击杀 */
    public static void trueDamage(Healthc target, float amount) {
        if (target == null || amount <= 0f || target.dead()) return;
        float left = target.health() - amount;
        if (left <= 0f) target.kill();
        else target.health(left);
    }

    /** 范围伤害：只伤害不属于 team 的单位与建筑（等价于原来的 nearbyEnemies 语义） */
    public static void areaDamage(Team team, float x, float y, float radius, float damage,
                                 Healthc exclude, boolean includeBuildings) {
        if (damage <= 0f || radius <= 0f || Vars.world == null) return;

        final float r2 = radius * radius;

        Groups.unit.intersect(x - radius, y - radius, radius * 2f, radius * 2f, unit -> {
            if (unit == exclude || unit.dead() || unit.team == team) return;
            if (unit.dst2(x, y) > r2) return;
            unit.damage(damage);
        });

        if (!includeBuildings) return;

        eachBuilding(x, y, radius, build -> {
            if (build == exclude || build.team == team) return;
            build.damage(damage);
        });
    }

    /** 范围伤害：只伤害【属于 team】的单位与建筑（自杀式爆炸 / 只打自己阵营时用） */
    public static void areaDamageSameTeam(Team team, float x, float y, float radius, float damage,
                                          Healthc exclude, boolean includeBuildings) {
        if (damage <= 0f || radius <= 0f || Vars.world == null || team == null) return;

        final float r2 = radius * radius;

        Groups.unit.intersect(x - radius, y - radius, radius * 2f, radius * 2f, unit -> {
            if (unit == exclude || unit.dead() || unit.team != team) return;
            if (unit.dst2(x, y) > r2) return;
            unit.damage(damage);
        });

        if (!includeBuildings) return;

        eachBuilding(x, y, radius, build -> {
            if (build == exclude || build.team != team) return;
            build.damage(damage);
        });
    }

    /**
     * 遍历范围内所有建筑（自动按中心点判定距离，并去重多格建筑）。
     * 建筑没有四叉树索引，所以这里按格子扫描，性能与半径相关而不是与建筑总数相关。
     */
    public static void eachBuilding(float x, float y, float radius, Cons<Building> cons) {
        if (Vars.world == null || cons == null || radius <= 0f) return;

        final float r2 = radius * radius;
        int tr = (int) Math.ceil(radius / 8f);
        int cx = World.toTile(x), cy = World.toTile(y);

        tmpBuildings.clear();
        for (int dx = -tr; dx <= tr; dx++) {
            for (int dy = -tr; dy <= tr; dy++) {
                if (dx * dx + dy * dy > tr * tr) continue;
                Tile tile = Vars.world.tile(cx + dx, cy + dy);
                if (tile == null) continue;
                Building build = tile.build;
                if (build == null || build.dead()) continue;
                if (build.dst2(x, y) > r2) continue;
                if (!tmpBuildings.add(build)) continue;
                cons.get(build);
            }
        }
    }

    // ==================== 范围施加（状态 / 能力 / 方块共用） ====================

    /** 目标是否符合筛选（相对施加方队伍） */
    public static boolean sideMatches(Team sourceTeam, Team targetTeam, RaenEffect.Side side) {
        if (side == null || side == RaenEffect.Side.ALL || sourceTeam == null) return true;
        boolean same = targetTeam == sourceTeam;
        return side == RaenEffect.Side.FRIENDLY ? same : !same;
    }

    /**
     * 给范围内的子弹附加效果标签（子弹没有冷却，进入范围就施加）。
     *
     * @param sourceTeam 施加方队伍，用于 {@code side} 筛选
     * @param side       子弹筛选：一般传 {@code RaenEffect.Side.FRIENDLY}（只给己方子弹）
     * @return 实际附加成功的子弹数
     */
    public static int markBulletsIn(Team sourceTeam, float x, float y, float range,
                                    RaenEffect.Combo combo, RaenEffect.Side side) {
        if (combo == null || combo.isEmpty() || range <= 0f) return 0;

        final float r2 = range * range;
        final int[] count = {0};

        Groups.bullet.intersect(x - range, y - range, range * 2f, range * 2f, bullet -> {
            if (bullet == null) return;
            if (bullet.dst2(x, y) > r2) return;
            if (!sideMatches(sourceTeam, bullet.team, side)) return;

            boolean any = false;
            for (int i = 0; i < combo.entries.size; i++) {
                RaenEffect.Combo.Entry entry = combo.entries.get(i);
                if (entry.effect.attach(bullet, entry.stacks)) any = true;
            }
            if (any) count[0]++;
        });

        return count[0];
    }

    /**
     * 给范围内的单位 / 建筑施加效果。<b>冷却由调用方控制</b>（例如每 7 秒调一次）。
     *
     * @param sourceTeam 施加方队伍（会写进效果的 ctxTeam，决定引爆 / AOE 打谁）
     * @param applyTeam  传给 {@code combo.to(...)} 的队伍，一般与 sourceTeam 相同；传 null 时用目标自己的队伍
     * @param side       目标筛选，默认建议 {@code RaenEffect.Side.ENEMY}
     * @return 实际施加的目标数
     */
    public static int applyToTargetsIn(Team sourceTeam, float x, float y, float range,
                                       RaenEffect.Combo combo, Team applyTeam,
                                       boolean affectUnits, boolean affectBuildings, RaenEffect.Side side) {
        if (combo == null || combo.isEmpty() || range <= 0f) return 0;

        final float r2 = range * range;
        final Team to = applyTeam == null ? sourceTeam : applyTeam;
        final int[] count = {0};

        if (affectUnits) {
            Groups.unit.intersect(x - range, y - range, range * 2f, range * 2f, unit -> {
                if (unit == null || unit.dead() || unit.dst2(x, y) > r2) return;
                if (!sideMatches(sourceTeam, unit.team, side)) return;
                combo.to(unit, to, 0f);   // 伤害传 0 → 各效果用自己的 fallbackDamage 兜底
                count[0]++;
            });
        }

        if (affectBuildings) {
            eachBuilding(x, y, range, build -> {
                if (!sideMatches(sourceTeam, build.team, side)) return;
                combo.to(build, to, 0f);
                count[0]++;
            });
        }

        return count[0];
    }

    /** 只对单位施加状态（建筑没有状态系统） */
    public static void applyStatus(Healthc target, StatusEffect effect, float duration) {
        if (target instanceof Unit && effect != null && duration > 0f) {
            ((Unit) target).apply(effect, duration);
        }
    }

    // ==================== 层数文字（统一绘制） ====================

    /** 层数文字的额外缩放（各效果的 textScale 会再乘上它）。0.5 = 比原来小一半。 */
    public static float stackTextScale = 0.5f;
    /** 第 0 个槽位相对「目标顶部」的偏移（正数向上）。8 = 原来层数文字的位置。 */
    public static float stackSlotBase = 8f;
    /**
     * 相邻槽位的垂直间距（正数 = 往下排）。
     * <p>
     * 字号半缩（{@link #stackTextScale} = 0.5）后，间距也按同比例从 10 收到 5，
     * 否则四行会显得稀稀拉拉。
     */
    public static float stackSlotSpacing = 5f;

    /**
     * 某个目标上第 {@code slot} 个层数文字的锚点 Y。
     * <p>
     * 每个效果占一个固定槽位、垂直错开 {@link #stackSlotSpacing} 像素 ——
     * 否则中毒层数涨到 4~5 位数时，会和积焰 / 电荷的数字横向叠在一起看不清。
     */
    public static float stackSlotY(Healthc target, int slot) {
        return target.y() + targetSize(target) / 2f + stackSlotBase - slot * stackSlotSpacing;
    }

    /**
     * 统一绘制「层数」：文字水平居中在目标上，按槽位垂直错开。
     *
     * @param slot  该效果固定占用的槽位（0 最靠上，往下递增；每个效果一个，别复用）
     * @param text  文字内容（例如 {@code "12"} / {@code "×6"} / {@code "+3"}）
     * @param scale 字号缩放（各效果用自己的 textScale）
     * @param color 文字颜色（含 alpha）
     */
    public static void drawStacks(Healthc target, int slot, String text, float scale, Color color) {
        if (target == null || text == null || text.isEmpty()) return;
        drawStacksAt(text, target.x(), stackSlotY(target, slot), scale, color);
    }

    /**
     * 在指定坐标绘制层数文字（跳字下方这类自定义位置用这个）。
     * <p>
     * 实际字号 = {@code scale × stackTextScale}，想整体放大/缩小层数只改 {@link #stackTextScale}。
     */
    public static void drawStacksAt(String text, float x, float y, float scale, Color color) {
        if (text == null || text.isEmpty()) return;

        Draw.z(Layer.overlayUI);
        Font font = Fonts.outline;
        font.setColor(color);
        drawStackText(font, text, x, y, scale * stackTextScale);
    }

    // ==================== 光环视觉（状态 / 能力共用） ====================

    /** 光环虚线圈颜色（默认 #88ffff） */
    public static Color auraColor = Color.valueOf("88ffff");
    /** 虚线线宽（像素，建议 1~2） */
    public static float auraLineWidth = 1.5f;
    /** 内圈不透明度（外圈自动取一半） */
    public static float auraAlpha = 0.75f;
    /** 每个圆圈的虚线段数（越大越密，绘制开销也越大） */
    public static int auraDashes = 40;
    /** 旋转速度（度/帧）：内圈顺时针，外圈反向 */
    public static float auraRotateSpeed = 0.5f;
    /** 外圈比内圈多出的半径（像素，纯装饰） */
    public static float auraOuterPadding = 10f;

    /**
     * 画光环：<b>内圈 = 实际生效范围</b>，外圈 = 内圈 + {@link #auraOuterPadding} 像素（装饰），
     * 两圈都是虚线且旋转方向相反，颜色 {@link #auraColor}、线宽 {@link #auraLineWidth}。
     */
    public static void drawAuraRange(float x, float y, float range) {
        if (range <= 0f || auraAlpha <= 0f || auraLineWidth <= 0f) return;

        Draw.z(Layer.effect);
        Draw.color(auraColor);
        Lines.stroke(auraLineWidth);

        float t = Time.time * auraRotateSpeed;
        drawDashedCircle(x, y, range, t, auraAlpha);                             // 内圈（生效范围）
        drawDashedCircle(x, y, range + auraOuterPadding, -t * 0.8f, auraAlpha * 0.5f); // 外圈（反向装饰）

        Draw.reset();
    }

    /** 画一个虚线圆：dashes 段等分圆周，每段留出空隙 */
    private static void drawDashedCircle(float x, float y, float radius, float rotation, float alpha) {
        if (radius <= 1f) return;

        int dashes = Math.max(8, auraDashes);
        float step = 360f / dashes;      // 相邻虚线的角间距
        float fill = step * 0.55f;       // 每段实线占的角宽，剩下的是空隙

        Draw.alpha(alpha);
        for (int i = 0; i < dashes; i++) {
            float a1 = rotation + i * step;
            float a2 = a1 + fill;
            Lines.line(
                    x + Angles.trnsx(a1, radius), y + Angles.trnsy(a1, radius),
                    x + Angles.trnsx(a2, radius), y + Angles.trnsy(a2, radius));
        }
    }

    /**
     * 绘制世界内的数字文字（层数 / 倒计时等）：临时把字体缩放到 scale，画完立刻还原。
     * <p>
     * {@code Fonts.outline} 是全局共用的字体，不还原会影响界面里其它文字。
     * 各效果用 {@code textScale} 字段控制大小（默认 0.5，比默认字号小一半，不挡视线）。
     */
    protected static void drawStackText(Font font, String text, float x, float y, float scale) {
        FontData data = font.getData();
        float oldX = data.scaleX, oldY = data.scaleY;
        data.setScale(scale);
        font.draw(text, x, y, Align.center);
        data.setScale(oldX, oldY);
    }

    /** 在目标脚下点火（单位与建筑通用） */
    public static void spawnFire(Healthc target) {
        if (target == null || Vars.world == null) return;
        Tile tile = null;
        if (target instanceof Unit) tile = ((Unit) target).tileOn();
        else if (target instanceof Building) tile = ((Building) target).tileOn();
        if (tile != null) Fires.create(tile);
    }

    /** 取目标所在的格子 */
    public static Tile tileOf(Healthc target) {
        if (target == null || Vars.world == null) return null;
        if (target instanceof Unit) return ((Unit) target).tileOn();
        if (target instanceof Building) return ((Building) target).tileOn();
        return null;
    }
}
