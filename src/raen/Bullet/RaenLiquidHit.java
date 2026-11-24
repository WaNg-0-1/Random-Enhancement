package raen.Bullet;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.entities.Fires;
import mindustry.entities.Puddles;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.entities.bullet.BulletType;
import mindustry.gen.Groups;
import mindustry.gen.Healthc;
import mindustry.gen.Unit;
import mindustry.type.Liquid;
import mindustry.type.StatusEffect;
import mindustry.world.Tile;
import mindustry.Vars;
import raen.Bullet.RaenLiquidTag.Kind;

/**
 * 液体标签命中处理器。
 * <p>
 * {@link RaenApplyBlock} 把液体标签写到子弹上时，同时写下 {@link #ID} 这个「总开关」标签，
 * 于是子弹命中时本处理器会被 {@link RaenEffectBase} 的事件分发唤醒，按子弹身上的液体标签结算：
 * <ul>
 *   <li><b>所有液体</b>：子弹命中时在命中处生成该液体（与原版液体子弹 {@code LiquidBulletType} 一致）；</li>
 *   <li>水 / 矿渣 / 油 / 冷却液：对被命中的目标施加对应状态 3 秒（潮湿 / 融化 / 油浸 / 冻结）；</li>
 *   <li>瘤液：对目标施加 5 秒弱化；</li>
 *   <li>芳油：目标身上叠层，每层 5% 概率施加 2 秒腐蚀；</li>
 *   <li>臭氧：命中处造成 3% 子弹伤害、4 格范围伤害；</li>
 *   <li>氢：点燃 5 格范围内的敌方目标（参考物品「硫」，但不额外积焰）；</li>
 *   <li>氮：命中叠层，每层 1% 概率触发；触发时按 (100+层数)% 施加冻结（2.5 秒基准）与静止（1 秒基准），然后清层；</li>
 *   <li>氰：命中处生成一枚碎片（伤害 = 子弹伤害 × 5%，完全穿甲）。</li>
 * </ul>
 * 芳油的层数记在<b>目标</b>身上（同一目标被连续命中才会叠起来），目标死亡或世界重置时自动清空。
 */
public class RaenLiquidHit extends RaenEffectBase {

    /** 子弹标签名：唯一，定下后不要改 */
    public static final String ID = "raenLiquid";
    public static final RaenLiquidHit INSTANCE;

    static {
        INSTANCE = new RaenLiquidHit();
        register(INSTANCE);          // ★ 必须，注册进全局分发
    }

    // ==================== 通用调参 ====================
    /** 命中处生成的液体量（与原版液体子弹的 puddleSize 同量级） */
    public float puddleSize = 6f;

    /** 芳油：每层提供多少概率触发腐蚀（默认 5%，可被定义覆盖） */
    public float corrodeChancePerStack = 0.05f;
    /** 芳油：腐蚀持续时间（秒） */
    public float corrodeSeconds = 2f;
    /** 芳油：叠层上限 */
    public int corrodeMaxStacks = 100;

    /** 氮：每层提供的触发概率（默认 1%） */
    public float freezeChancePerStack = 0.01f;
    /** 氮：叠层上限 */
    public int freezeMaxStacks = 100;
    /** 层数 → 时长倍率的换算：1 层 = 1%，即 (100+层数)% */
    private static final float FREEZE_STACK_PERCENT = 0.01f;

    /** 氰碎片：伤害占子弹伤害的比例 */
    public float cyanogenShardRatio = 0.05f;
    /** 氰碎片：速度占原弹速度的比例 */
    public float cyanogenShardSpeedScale = 0.6f;
    /** 氢：点燃的燃烧持续时间（秒），与物品「硫」一致 */
    public float burnSeconds = 5f;

    /**
     * 原版液体<b>自带</b>的状态效果持续时间（秒）。
     * <p>
     * 原版 {@code LiquidBulletType} 就是这么干的：{@code this.status = liquid.effect}，
     * 于是水→潮湿、矿渣→融化、油→油浸、冷却液→冻结……
     * <p>
     * 这里对<b>所有</b>液体都生效（包括 mod 加的、只要它设了 {@code effect}），
     * 不用登记也能享受原版自带效果。设为 0 则关闭这一段。
     */
    public float vanillaEffectSeconds = 2f;

    /** 氰碎片专用类型（完全穿甲）：按源子弹类型缓存 */
    private final ObjectMap<BulletType, BulletType> shardTypes = new ObjectMap<>();

    /** 芳油：目标 → 层数 */
    private final ObjectIntMap<Healthc> corrode = new ObjectIntMap<>();
    /** 氮：目标 → 层数 */
    private final ObjectIntMap<Healthc> freeze = new ObjectIntMap<>();

    private final RaenEffectText text;

    public RaenLiquidHit() {
        text = RaenEffectText.key("raen.tag.liquid.name", "7FB8D8", this::describe);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RaenEffectText text() {
        return text;
    }

    // ==================== 命中 ====================

    @Override
    protected void onHit() {
        if (ctxBullet == null) return;

        // 子弹身上带的液体（任意液体都能挂 —— 标记名 = raenLiq + 液体名）
        Liquid liquid = RaenLiquidTags.liquidOf(ctxBullet);
        if (liquid == null) return;

        // ① 所有液体：命中处生成该液体（同原版液体子弹 LiquidBulletType）
        deposit(liquid);

        if (ctxTarget == null || !valid(ctxTarget)) return;

        // ② 原版液体自带的状态效果（Liquid.effect：水→潮湿、矿渣→融化、油→油浸…）
        //    与原版 LiquidBulletType 的 status = liquid.effect 一致。
        //    只在「没有单独定义」时应用，避免和下面的额外效果重复。
        RaenLiquidTag def = RaenLiquidTags.def(liquid);
        if (def == null) applyVanillaEffect(liquid);

        // ③ 额外效果（只有登记过的液体才有）
        if (def != null) applyExtra(def);
    }

    /**
     * 原版液体自带的状态效果。
     * <p>
     * {@code liquid.effect} 是原版给每种液体配的状态（水=潮湿 / 矿渣=融化 / 油=油浸 / 冷却液=冻结…），
     * 原版液体子弹就是靠它生效的。镓、氮气等没配 {@code effect} 的液体就没有。
     */
    private void applyVanillaEffect(Liquid liquid) {
        if (vanillaEffectSeconds <= 0f) return;

        StatusEffect effect = liquid.effect;
        if (effect == null || effect == StatusEffects.none) return;

        applyStatus(ctxTarget, effect, vanillaEffectSeconds * 60f);
    }

    /** 在命中处生成液体（原版 LiquidBulletType 的行为） */
    private void deposit(Liquid liquid) {
        if (liquid == null || Vars.world == null) return;
        Tile tile = Vars.world.tileWorld(ctxX, ctxY);
        if (tile == null) return;

        Puddles.deposit(tile, liquid, puddleSize);
        Fx.hitLiquid.at(ctxX, ctxY, liquid.color);
    }

    /** 按液体种类结算额外效果 */
    private void applyExtra(RaenLiquidTag def) {
        switch (def.kind){
            case soak -> soak(def);
            case corrode -> corrodeHit(def);
            case areaDamage -> areaDamageHit(def);
            case ignite -> igniteHit(def);
            case freeze -> freezeHit(def);
            case shards -> shardsHit(def);
            default -> {
            }
        }
    }

    /** 浸泡：对目标施加一个状态（水 / 矿渣 / 油 / 冷却液 / 瘤液） */
    private void soak(RaenLiquidTag def) {
        StatusEffect effect = def.soakEffect != null ? def.soakEffect : def.liquid.effect;
        applyStatus(ctxTarget, effect, def.seconds * 60f);
    }

    /** 芳油：目标叠层，每层概率触发一次腐蚀 */
    private void corrodeHit(RaenLiquidTag def) {
        int stacks = Math.min(def.maxStacks, corrode.get(ctxTarget, 0) + 1);
        corrode.put(ctxTarget, stacks);

        float chance = def.chancePerStack > 0f ? def.chancePerStack : corrodeChancePerStack;
        if (Mathf.random() >= stacks * chance) return;
        corrode.remove(ctxTarget, 0);

        applyStatus(ctxTarget, StatusEffects.corroded, def.seconds * 60f);
    }

    /** 臭氧：命中处范围伤害 */
    private void areaDamageHit(RaenLiquidTag def) {
        float damage = ctxDamage * def.damageRatio;
        if (damage <= 0f) return;

        areaDamage(ctxTeam, ctxX, ctxY, def.radiusTiles * 8f, damage, null, true);
        Fx.blastExplosion.at(ctxX, ctxY);
    }

    /** 氢：点燃范围内的敌方目标（参考物品「硫」，但不额外积焰） */
    private void igniteHit(RaenLiquidTag def) {
        float radius = def.radiusTiles * 8f;
        final float x = ctxX, y = ctxY;
        final float r2 = radius * radius;

        Groups.unit.intersect(x - radius, y - radius, radius * 2f, radius * 2f, unit -> {
            if (unit == null || unit.dead() || unit.team == ctxTeam) return;
            if (unit.dst2(x, y) > r2) return;
            unit.apply(StatusEffects.burning, burnSeconds * 60f);
            spawnFire(unit);
        });

        Tile tile = Vars.world == null ? null : Vars.world.tileWorld(x, y);
        if (tile != null) Fires.create(tile);
        Fx.fire.at(x, y);
    }

    /**
     * 氮：命中先叠层，再按「层数%」掷一次触发；触发后清层。
     * 时长 = (100 + 层数)% × 基准秒数（每层 1% → 100 层时翻倍）。
     */
    private void freezeHit(RaenLiquidTag def) {
        int maxStacks = def.freezeMaxStacks > 0 ? def.freezeMaxStacks : freezeMaxStacks;
        int stacks = Math.min(maxStacks, freeze.get(ctxTarget, 0) + 1);
        freeze.put(ctxTarget, stacks);

        float chance = def.freezeChancePerStack > 0f ? def.freezeChancePerStack : freezeChancePerStack;
        if (Mathf.random() >= stacks * chance) return;
        freeze.remove(ctxTarget, 0);

        float scale = 1f + stacks * FREEZE_STACK_PERCENT;
        applyStatus(ctxTarget, StatusEffects.unmoving, def.unmovingSeconds * 60f * scale);
        applyStatus(ctxTarget, StatusEffects.freezing, def.seconds * 60f * scale);
    }

    /** 氰：命中处生成一枚完全穿甲的碎片 */
    private void shardsHit(RaenLiquidTag def) {
        if (RaenShards.depthExceeded(ctxBullet)) return;

        float ratio = def.shardRatio > 0f ? def.shardRatio : cyanogenShardRatio;
        float damage = ctxDamage * ratio;
        if (damage <= 0f) return;

        BulletType source = ctxBullet.type;
        BulletType shardType = piercingType(source);
        if (shardType == null) return;

        float speed = ctxBullet.vel.len() * cyanogenShardSpeedScale;
        float angle = speed > 0.01f ? ctxBullet.vel.angle() : Mathf.random(360f);

        mindustry.gen.Bullet shard = RaenShards.spawn(shardType, ctxTeam, ctxX, ctxY,
                damage, speed, angle, null);
        if (shard == null) return;
        // 代数标记：碎片本身不再产碎片
        RaenBulletMarks.addBulletMark(shard, RaenShards.SHARD, 1);
    }

    /**
     * 氰碎片专用类型：<b>完全穿甲</b>（{@code pierceArmor = true}，无视目标护甲）。
     * <p>
     * 穿甲是 {@link BulletType} 上的开关，命中那一刻才会读，
     * 所以不能直接复用原弹类型 —— 这里按源类型缓存一份「同贴图 + 穿甲」的副本。
     * <p>
     * 贴图：源类型是 {@link BasicBulletType} 时沿用它的贴图名，否则退回通用的 shell。
     */
    private BulletType piercingType(BulletType source) {
        if (source == null) return null;

        BulletType cached = shardTypes.get(source);
        if (cached != null) return cached;

        String sprite = source instanceof BasicBulletType basic && basic.sprite != null ? basic.sprite : "shell";

        // 注意这里是 {{ }}：Mindustry 的惯用写法（实例初始化块套实例初始化块），
        // 单层 { } 里写字段赋值是语法错误。
        BulletType type = new BasicBulletType(3.5f, 0f, sprite){{
            pierceArmor = true;          // ★ 完全穿甲
            lifetime = 60f;
            drag = 0.01f;
            collides = true;
            collidesTiles = false;
            collidesAir = true;
            collidesGround = true;
            width = 5f;
            height = 7f;
            hitSize = 3f;
            knockback = 0f;
            frontColor = Color.valueOf("89E8B6");
            backColor = Color.valueOf("4FA97E");
            hitEffect = Fx.hitLiquid;
            despawnEffect = Fx.none;
            shootEffect = Fx.none;
            smokeEffect = Fx.none;
        }};
        // 运行时 new 的子弹类型必须补 load()，否则贴图区域为 null → 绘制 NPE
        if (Core.atlas != null) type.load();

        shardTypes.put(source, type);
        return type;
    }

    // ==================== 层数清理 ====================

    @Override
    public void clearLayer(Healthc target) {
        super.clearLayer(target);
        if (target != null) {
            corrode.remove(target, 0);
            freeze.remove(target, 0);
        }
    }

    @Override
    public void onReset() {
        corrode.clear();
        freeze.clear();
        shardTypes.clear();
    }

    // ==================== 详情文案 ====================

    public String describe() {
        return Core.bundle.format("raen.taghit.title", text.color) + "\n" +
                Core.bundle.get("raen.tag.liquid.desc") + "\n\n" +
                RaenLiquidTags.describeAll();
    }

    /**
     * 该液体有没有「额外效果」定义。
     * <p>
     * ⚠ <b>没有定义 ≠ 不能用</b>：方块接受<b>所有</b>液体，
     * 没定义的液体照样能被装载、挂到子弹上、命中时在命中处生成 —— 只是没有额外效果。
     */
    public static boolean supports(Liquid liquid) {
        return RaenLiquidTags.hasExtra(liquid);
    }
}
