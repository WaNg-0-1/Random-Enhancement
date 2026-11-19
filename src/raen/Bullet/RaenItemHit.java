package raen.Bullet;

import arc.Core;
import arc.graphics.Color;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.content.StatusEffects;
import mindustry.entities.Fires;
import mindustry.entities.Lightning;
import mindustry.entities.Units;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Healthc;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;
import mindustry.world.Tile;
import mindustry.Vars;
import raen.Bullet.effect.RaenEffectJY;
import raen.Bullet.effect.RaenEffectLS;
import raen.Bullet.effect.RaenEffectZJ;
import raen.Bullet.effect.RaenEffectZM;

/**
 * 物品标签命中处理器。
 * <p>
 * {@link RaenApplyBlock} 把物品标签写到子弹上时，同时写下 {@link #ID} 这个「总开关」标签，
 * 于是子弹命中时本处理器会被 {@link RaenEffectBase} 的事件分发唤醒，按子弹身上的物品标签结算：
 * <p>
 * <b>四属性</b>（爆炸 B / 燃烧 C / 放射 F / 放电 D）<b>独立于主标签与物品标签</b>，分两种结算方式：
 * <ul>
 *   <li><b>爆炸 B</b>（命中时）：B 概率在命中处造成半径 6B 格、伤害 = B + B×子弹伤害 的范围伤害；</li>
 *   <li><b>点燃 C</b>（命中时）：C 概率点燃命中处地面，并对半径 4C 内的敌方单位施加 5 秒燃烧；</li>
 *   <li><b>放射性 F</b>（挂标签时）：方块按 F 的概率给经过的子弹挂「中毒」标签，命中照常叠中毒层；</li>
 *   <li><b>放电性 D</b>（挂标签时）：方块按 1.25×D 的概率给经过的子弹挂「电荷」标签，
 *       电荷按这发子弹的<b>全伤害</b>结算。</li>
 * </ul>
 * 所以只要方块里的物品能提供某一种属性，哪怕这发子弹因为名额限制一个物品标签都没挂上，
 * 也照样会判定该属性。概率超过 100% 时按 100% 处理。
 * <p>
 * <b>物品行为</b>：铜/钛/钍/铍/钨/碳化物的加伤在「获得标签时」一次性结算（见
 * {@link RaenApplyBlock}），其余物品在这里按命中结算；硅的追踪由 {@link #onTick(float)} 每帧转向。
 * <p>
 * 铅 / 煤 / 孢子夹 / 废料的层数记在<b>目标</b>身上（同一目标被连续命中才会叠起来），
 * 目标死亡或世界重置时自动清空。
 */
public class RaenItemHit extends RaenEffectBase {

    /** 子弹标签名：唯一，定下后不要改 */
    public static final String ID = "raenItem";
    public static final RaenItemHit INSTANCE;

    /**
     * 四属性载体标签：方块把「装载物品的四属性合计」写在这两个标签上（层数 = 属性值 × 100）。
     * <p>
     * 只有<b>爆炸（B）</b>与<b>点燃（C）</b>需要带到命中时结算；
     * 放电（D）与放射（F）改在<b>挂标签时</b>直接判定并给子弹挂「电荷 / 中毒」标签
     * （见 {@code RaenApplyBlock#rollAttributeTags}），所以不用写成属性标签。
     */
    public static final String ATTR_B = "raenAttrB";   // 爆炸性
    public static final String ATTR_C = "raenAttrC";   // 燃烧性
    /** 属性值 → 标签层数的放大倍数（0.15 → 15） */
    public static final float ATTR_SCALE = 100f;

    /** 追踪载体标签：层数 = 搜索半径（格）。硅 = 24，玻璃碎片 = 12 */
    public static final String HOMING = "raenHoming";

    /** 铅的延迟子弹标记：层数 = 触发范围伤害的半径（格），命中时结算一次 */
    public static final String LEAD_SHARD = "raenLeadShard";

    static {
        INSTANCE = new RaenItemHit();
        register(INSTANCE);          // ★ 必须，注册进全局分发
    }

    // ==================== 四属性调参 ====================
    /** 爆炸：每 1 点爆炸性提供的半径（格） */
    public float explodeRadiusPerPoint = 6f;
    /** 爆炸：每 1 点爆炸性提供的固定伤害 */
    public float explodeFlatPerPoint = 1f;
    /** 爆炸：每 1 点爆炸性换算成子弹伤害的百分比（0.01 = 1%，即 B=2.2 → 2.2% 子弹伤害） */
    public float explodePercentPerPoint = 0.01f;
    /** 点燃：每 1 点燃烧性提供的半径（格） */
    public float igniteRadiusPerPoint = 4f;
    /** 点燃：燃烧持续时间（秒） */
    public float igniteSeconds = 5f;
    /** 放电性 → 给经过的子弹挂「电荷」标签的概率倍率（方块挂标签时用） */
    public float chargeTagChance = 1.25f;
    /** 放射性 → 给经过的子弹挂「中毒」标签的概率倍率（方块挂标签时用） */
    public float poisonTagChance = 1f;

    // ==================== 物品行为调参 ====================
    /** 钍：穿甲比例（额外造成 敌方护甲值 × 该比例 的伤害，等效无视这么多护甲） */
    public float thoriumArmorPierce = 0.3f;

    /** 钛 / 钨：击退量 = 子弹伤害 × 该比例（直接作为冲量，即速度增量） */
    public float knockbackDamageRatio = 0.1f;

    /** 石墨：麻痹半径（格） */
    public float graphiteRadiusTiles = 5f;
    /** 石墨：麻痹时间（秒） */
    public float graphiteSeconds = 3f;
    /** 石墨：额外固定伤害 */
    public float graphiteFlatDamage = 3f;

    /** 塑钢：碎片数量下限 / 上限 */
    public int plastaniumMin = 1, plastaniumMax = 4;
    /** 塑钢：碎片伤害占子弹伤害的比例 */
    public float plastaniumDamageMin = 0.025f, plastaniumDamageMax = 0.075f;
    /** 塑钢：碎片生成圆环的半径范围（格，以命中点为圆心） */
    public float plastaniumRingMin = 3f, plastaniumRingMax = 7f;
    /** 塑钢：碎片从圆环上飞到「越过圆心」所需的时间（tick） */
    public float plastaniumTravelTicks = 40f;
    /**
     * 塑钢：每颗碎片的<b>随机生成延迟</b>上限（tick，0 = 不延迟）。
     * <p>
     * 一次命中产多颗碎片时，如果全部同帧生成，命中同一个目标就会同一帧结算好几次；
     * 加了 0~{@code 30} tick 的随机延迟后，碎片会错峰飞出、错峰命中，既降低瞬时峰值，
     * 视觉上也不会「一下子扎堆炸开」。
     */
    public float plastaniumDelayTicks = 30f;

    /** 布：有盾目标的额外伤害比例 */
    public float phaseShieldBonus = 0.25f;
    /** 布：附带敌方护盾值比例 */
    public float phaseShieldRatio = 0.01f;
    /** 布：附带伤害封顶（× 子弹伤害） */
    public float phaseShieldCap = 1.5f;

    /** 合金：闪电条数 */
    public int surgeMin = 1, surgeMax = 4;
    /** 合金：闪电长度的硬上限（格）—— 无论怎么配都不会超过 10 */
    public static final float SURGE_MAX_TILES = 10f;
    /** 合金：闪电长度（格），会被 {@link #SURGE_MAX_TILES} 夹住 */
    public float surgeLengthMin = 2f, surgeLengthMax = 10f;
    /** 合金：闪电伤害比例 */
    public float surgeDamageMin = 0.05f, surgeDamageMax = 0.10f;
    /** 合金：闪电颜色 */
    public Color surgeColor = Color.valueOf("C9E8FF");
    /** 布：命中特效颜色 */
    public Color phaseColor = Color.valueOf("F0D8A8");

    /** 爆混：范围伤害半径（格）与比例 */
    public float blastRadiusTiles = 5f;
    public float blastDamageRatio = 0.01f;
    /** 爆混：爆炸状态持续时间（秒） */
    public float blastSeconds = 3f;
    /** 氧化物：腐蚀半径（格）与时间（秒） */
    public float oxideRadiusTiles = 5f;
    public float oxideSeconds = 4f;
    /** 硫：燃烧时间（秒） */
    public float burnSeconds = 5f;
    /** 孢子夹：孢子减速时间（秒） */
    public float sporeSeconds = 5f;
    /** 孢子夹：叠层上限 */
    public int sporeMaxStacks = 100;
    /** 孢子夹：每层提供多少概率触发削弱（每层 1%） */
    public float sporeChancePerStack = 0.01f;
    /** 孢子夹：削弱的基础时长（秒） */
    public float sporeSapBaseSeconds = 5f;
    /** 孢子夹：削弱每层追加的时长（秒） */
    public float sporeSapPerStackSeconds = 0.1f;
    /** 沙子：泥泞时间（秒） */
    public float mudSeconds = 3f;
    /** 沙子：叠层上限 */
    public int sandMaxStacks = 100;
    /** 沙子：每层提供的「生成临时墙」概率（每层 1%） */
    public float sandChancePerStack = 0.01f;
    /** 沙子：临时墙生命 = 基础 + 层数 × 每层 */
    public float wallBaseHealth = 50f;
    public float wallHealthPerStack = 1f;
    /** 沙子：临时墙存在时间 = 基础 + 层数 × 每层（秒） */
    public float wallBaseSeconds = 5f;
    public float wallSecondsPerStack = 0.1f;

    /** 铅：范围伤害半径 = 基础 + 每层追加（格） */
    public float leadRadiusBaseTiles = 3f;
    public float leadRadiusPerStack = 0.1f;
    /** 铅：单层触发概率（每层 1%） */
    public float leadChancePerStack = 0.01f;
    /** 铅：层数上限（概率封顶用） */
    public int leadMaxStacks = 100;
    /** 煤：单层触发概率（每层 5%） */
    public float coalChancePerStack = 0.05f;
    /** 煤：层数上限 */
    public int coalMaxStacks = 100;
    /** 废料：层数上限 */
    public int scrapMaxStacks = 5;
    /** 废料：每层结算间隔（秒）与该次静止时间（秒） */
    public float scrapIntervalSeconds = 5f;
    public float scrapFreezeSeconds = 1f;

    /** 硅：追踪转向速度（度/帧） */
    public float homingTurnDegrees = 6f;

    // ==================== 运行时状态 ====================
    /** 铅：目标 → 层数 */
    private final ObjectIntMap<Healthc> lead = new ObjectIntMap<>();
    /** 煤：目标 → 层数 */
    private final ObjectIntMap<Healthc> coal = new ObjectIntMap<>();
    /** 孢子夹：目标 → 层数 */
    private final ObjectIntMap<Healthc> spore = new ObjectIntMap<>();
    /** 沙子：目标 → 层数 */
    private final ObjectIntMap<Healthc> sand = new ObjectIntMap<>();
    /** 废料：目标 → {剩余层数, 计时} */
    private final ObjectMap<Healthc, float[]> scrap = new ObjectMap<>();
    /** 硅：需要追踪的子弹 */
    private final Seq<Bullet> homing = new Seq<>();
    /** 复用：本帧到期的目标 */
    private final Seq<Healthc> expired = new Seq<>();
    /** 复用：四属性求和结果 */
    private final float[] attr = new float[4];

    private final RaenEffectText text;

    public RaenItemHit() {
        text = RaenEffectText.key("raen.tag.item.name", "D8C48A", this::describe);
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
        if (ctxBullet == null || ctxTarget == null || !valid(ctxTarget)) return;

        // 铅的 0 速延迟子弹：命中时先把它自带的范围伤害结算掉
        // （放在最前面：它可能没带任何物品标签，只有这一条标记）
        leadShardBlast();

        long mask = RaenItemTags.maskOf(ctxBullet);
        readAttributes(ctxBullet);

        boolean anyAttr = attr[0] > 0f || attr[1] > 0f || attr[2] > 0f || attr[3] > 0f;
        if (mask == 0L && !anyAttr) return;

        float damage = ctxDamage;

        // ---- ① 四属性：只有爆炸 / 点燃在命中时结算 ----
        // 概率来自方块里物品的合计（写在属性标签上），与子弹带了哪些物品标签无关
        if (attr[0] > 0f && Mathf.chance(Math.min(1f, attr[0]))) explode();
        if (attr[1] > 0f && Mathf.chance(Math.min(1f, attr[1]))) ignite();

        // ---- ② 物品行为 ----
        if (RaenItemTags.has(mask, Items.lead)) leadHit(damage);
        if (RaenItemTags.has(mask, Items.graphite)) graphiteHit();
        if (RaenItemTags.has(mask, Items.plastanium)) plastaniumHit(damage);
        if (RaenItemTags.has(mask, Items.phaseFabric)) phaseFabricHit(damage);
        if (RaenItemTags.has(mask, Items.titanium) || RaenItemTags.has(mask, Items.tungsten)) knockbackHit(damage);
        if (RaenItemTags.has(mask, Items.surgeAlloy)) surgeHit(damage);
        if (RaenItemTags.has(mask, Items.sporePod)) sporeHit();
        if (RaenItemTags.has(mask, Items.pyratite)) pyratiteHit();
        if (RaenItemTags.has(mask, Items.blastCompound)) blastHit(damage);
        if (RaenItemTags.has(mask, Items.coal)) coalHit();
        if (RaenItemTags.has(mask, Items.sand)) sandHit();
        if (RaenItemTags.has(mask, Items.scrap)) scrapHit();
        if (RaenItemTags.has(mask, Items.thorium)) armorPierceHit();
        if (RaenItemTags.has(mask, Items.oxide)) oxideHit();
        if (RaenItemTags.has(mask, Items.carbide)) carbideHit();
    }

    // ==================== 四属性载体（方块写、命中读） ====================

    /**
     * 把方块当前的「四属性合计」写到子弹上。
     * <p>
     * 只升不降：值没有变大就不动标签 —— 这样既不会每帧改写 {@code bullet.data}，
     * 也不会让两个领域互相把对方的属性抹掉。
     *
     * @param sums 长度 ≥4：{爆炸, 燃烧, 放射, 放电}
     */
    public static void writeAttributes(Bullet bullet, float[] sums) {
        if (bullet == null || sums == null || sums.length < 4) return;
        writeAttr(bullet, ATTR_B, sums[0]);
        writeAttr(bullet, ATTR_C, sums[1]);
    }

    private static void writeAttr(Bullet bullet, String tag, float value) {
        int encoded = Math.round(Math.max(0f, value) * ATTR_SCALE);
        if (encoded <= 0) return;
        if (RaenBulletMarks.getBulletMark(bullet, tag) >= encoded) return;
        RaenBulletMarks.addBulletMark(bullet, tag, encoded);
    }

    /** 从子弹上读出四属性合计，写进 {@link #attr}（只有 B/C 会被带到命中，F/D 在挂标签时就结算了） */
    private void readAttributes(Bullet bullet) {
        attr[0] = RaenBulletMarks.getBulletMark(bullet, ATTR_B) / ATTR_SCALE;
        attr[1] = RaenBulletMarks.getBulletMark(bullet, ATTR_C) / ATTR_SCALE;
        attr[2] = 0f;
        attr[3] = 0f;
    }

    // ==================== 四属性 ====================

    /**
     * 爆炸：命中处半径 6B 格的范围伤害。
     * <p>
     * 伤害 = B（固定）+ 子弹伤害 × (B%)，B 按「100% = 1」记，
     * 但换算成伤害时取百分之 B（B=2.2 → 2.2% 子弹伤害），两个系数分别由
     * {@link #explodeFlatPerPoint} / {@link #explodePercentPerPoint} 调。
     */
    private void explode() {
        float b = attr[0];
        float radius = b * explodeRadiusPerPoint * 8f;
        float damage = b * explodeFlatPerPoint + ctxDamage * b * explodePercentPerPoint;
        areaDamage(ctxTeam, ctxX, ctxY, radius, damage, null, true);   // 建筑也吃
        Fx.blastExplosion.at(ctxX, ctxY);
    }

    /** 点燃：命中处点火，半径 4C 内敌方单位燃烧 5 秒 */
    private void ignite() {
        float c = attr[1];
        float radius = c * igniteRadiusPerPoint * 8f;
        final float x = ctxX, y = ctxY;
        final float r2 = radius * radius;

        Groups.unit.intersect(x - radius, y - radius, radius * 2f, radius * 2f, unit -> {
            if (unit == null || unit.dead() || unit.team == ctxTeam) return;
            if (unit.dst2(x, y) > r2) return;
            unit.apply(StatusEffects.burning, igniteSeconds * 60f);
            spawnFire(unit);
        });

        Tile tile = tileAt(x, y);
        if (tile != null) Fires.create(tile);
        Fx.fire.at(x, y);
    }

    // ==================== 物品行为 ====================

    /**
     * 铅：命中叠层，按「层数%」概率在命中处留一发 <b>0 速子弹</b>
     * （伤害 = 子弹伤害 × 层数%，整份继承标签）——它停在原地不动，
     * 等有单位撞上来才结算，属于「延迟触发」而非即时伤。
     * 引爆时再补一发半径 3 + 0.1×层数 格的范围伤害，见 {@link #leadShardBlast()}。
     */
    private void leadHit(float damage) {
        int stacks = Math.min(leadMaxStacks, lead.get(ctxTarget, 0) + 1);
        lead.put(ctxTarget, stacks);

        if (Mathf.random() >= stacks * leadChancePerStack) return;
        lead.remove(ctxTarget, 0);

        // 碎片不再产碎片（代数上限由 RaenShards 统一控制）
        if (RaenShards.depthExceeded(ctxBullet)) return;

        // 0 速子弹：落在命中处不动，等有东西撞上来才结算（延迟触发）
        Bullet shard = RaenShards.spawn(ctxBullet, ctxTeam, ctxX, ctxY,
                damage * stacks / 100f, 0f, Mathf.random(360f), true);
        if (shard == null) return;

        // 把范围半径（格）记在子弹上，命中时由 leadShardBlast 结算
        RaenBulletMarks.addBulletMark(shard, LEAD_SHARD,
                Math.max(1, Math.round(leadRadiusBaseTiles + stacks * leadRadiusPerStack)));
    }

    /**
     * 铅的延迟子弹命中：以自身为中心打一发 3 + 10%层数 格的范围伤害。
     * <p>
     * 半径在生成时写在 {@link #LEAD_SHARD} 的层数里；结算后立刻摘掉标记，
     * 穿透类子弹（一次命中多个目标）也只炸一次。
     * 直击目标已经吃过子弹本身的伤害，所以范围伤害把它排除。
     */
    private void leadShardBlast() {
        int radiusTiles = RaenBulletMarks.getBulletMark(ctxBullet, LEAD_SHARD);
        if (radiusTiles <= 0) return;

        RaenBulletMarks.removeBulletMark(ctxBullet, LEAD_SHARD);
        areaDamage(ctxTeam, ctxX, ctxY, radiusTiles * 8f, ctxDamage, ctxTarget, true);
        Fx.blastExplosion.at(ctxX, ctxY);
    }

    /** 钛 / 钨：命中附带「子弹伤害 × 比例」的击退（把目标沿弹道方向推开） */
    private void knockbackHit(float damage) {
        if (!(ctxTarget instanceof Unit)) return;

        float force = damage * knockbackDamageRatio;
        if (force <= 0f) return;

        Unit unit = (Unit) ctxTarget;
        float angle;
        if (ctxBullet != null && ctxBullet.dst2(unit) > 1f) {
            angle = Angles.angle(ctxBullet.x, ctxBullet.y, unit.x, unit.y);   // 从子弹指向目标 = 推开
        } else if (ctxBullet != null) {
            angle = ctxBullet.rotation();
        } else {
            return;
        }

        unit.impulse(Angles.trnsx(angle, force), Angles.trnsy(angle, force));
    }

    /** 石墨：5 格麻痹 + 额外 3 点伤害 */
    private void graphiteHit() {
        ctxTarget.damage(graphiteFlatDamage);
        statusArea(StatusEffects.electrified, graphiteRadiusTiles * 8f, graphiteSeconds * 60f);
        Fx.hitLaserBlast.at(ctxX, ctxY, 0f, surgeColor);
    }

    /**
     * 塑钢：1-4 个碎片，在命中点为圆心、半径 3-7 格的圆环上等距生成，
     * 每个碎片朝圆心飞、并刚好越过圆心（速度按半径反算，寿命 = 飞行时间）。
     * <p>
     * ★ 碎片<b>只带追击标签</b>（见 {@link RaenShards#spawnChase}），不继承原子弹的其它标签：
     * 一次塑钢命中会产生多次额外命中，若碎片也带合金（闪电 / 电荷 / 连锁）、布（转命）
     * 之类的「命中即叠层」标签，叠层与触发频率会翻好几倍。
     * <p>
     * ★ 每颗碎片带 0~{@link #plastaniumDelayTicks} 的随机延迟（{@code Time.run}），错峰生成。
     * 延迟期间源子弹可能已被池子回收，所以类型 / 追击层数 / 代数都先抄成局部变量。
     */
    private void plastaniumHit(float damage) {
        if (RaenShards.depthExceeded(ctxBullet)) return;

        int count = Mathf.random(plastaniumMin, plastaniumMax);
        float startAngle = Mathf.random(360f);
        float travel = Math.max(6f, plastaniumTravelTicks);

        // ★ 延迟生成时源子弹可能已回收 ⇒ 现在就把要用的东西抄下来
        final BulletType srcType = ctxBullet == null ? null : ctxBullet.type;
        final int chaseStacks = ctxBullet == null ? 0 : RaenBulletMarks.getBulletMark(ctxBullet, RaenEffectZJ.ID);
        final int depth = ctxBullet == null ? 0 : RaenBulletMarks.getBulletMark(ctxBullet, RaenShards.SHARD);
        final Team team = ctxTeam;
        final float cx = ctxX, cy = ctxY;

        for (int i = 0; i < count; i++) {
            float angle = startAngle + i * 360f / count;
            float radiusTiles = Mathf.random(plastaniumRingMin, plastaniumRingMax);
            float dist = radiusTiles * 8f;

            float px = cx + Angles.trnsx(angle, dist);
            float py = cy + Angles.trnsy(angle, dist);

            // 半程越过圆心：速度 = 2×半径 / 飞行时间
            float speed = dist * 2f / travel;
            float dmg = damage * Mathf.random(plastaniumDamageMin, plastaniumDamageMax);
            float outAngle = angle + 180f;

            float delay = plastaniumDelayTicks > 0f ? Mathf.random(plastaniumDelayTicks) : 0f;
            if (delay <= 0.01f) {
                spawnPlastaniumShard(srcType, team, px, py, dmg, speed, outAngle, travel, chaseStacks, depth);
            } else {
                Time.run(delay, () -> spawnPlastaniumShard(
                        srcType, team, px, py, dmg, speed, outAngle, travel, chaseStacks, depth));
            }
        }
    }

    /** 生成一颗塑钢碎片（飞过圆心即消失，不会继续外飞） */
    private void spawnPlastaniumShard(BulletType type, Team team, float x, float y, float damage,
                                      float speed, float angle, float lifetime, int chaseStacks, int depth) {
        Bullet shard = RaenShards.spawnChase(type, team, x, y, damage, speed, angle, chaseStacks, depth);
        if (shard == null) return;
        shard.lifetime = lifetime;
        shard.time = 0f;
    }

    /** 布：对盾目标加伤、附带护盾值伤害、施加转命 */
    private void phaseFabricHit(float damage) {
        if (!(ctxTarget instanceof Unit)) return;

        Unit unit = (Unit) ctxTarget;
        if (unit.shield <= 0f) return;

        float bonus = damage * phaseShieldBonus;
        float shieldBonus = Math.min(unit.shield * phaseShieldRatio, damage * phaseShieldCap);
        unit.damage(bonus + shieldBonus);

        RaenEffectZM.INSTANCE.applyTo(unit, 1, ctxTeam, damage);
        Fx.hitLaser.at(ctxX, ctxY, 0f, phaseColor);
    }

    /** 合金：1-4 条闪电 + 1 层连锁 */
    private void surgeHit(float damage) {
        int count = Mathf.random(surgeMin, surgeMax);
        // 长度取整数格、两端都含（Mathf.random(int,int) 是闭区间）：2-10 就是 2~10 都能摇到
        int minLen = Math.max(1, (int)surgeLengthMin);
        int maxLen = Math.max(minLen, (int)Math.min(SURGE_MAX_TILES, surgeLengthMax));
        for (int i = 0; i < count; i++) {
            int length = Mathf.random(minLen, maxLen);
            float dmg = damage * Mathf.random(surgeDamageMin, surgeDamageMax);
            Lightning.create(ctxTeam, surgeColor, dmg, ctxX, ctxY, Mathf.random(360f), length);
        }
        RaenEffectLS.INSTANCE.applyTo(ctxTarget, 1, ctxTeam, damage);
    }

    /**
     * 孢子夹：命中叠 1 层并施加孢子减速；每层 1% 概率触发「削弱」，
     * 时长 = 基础 + 层数 × 每层追加，触发后清空层数。
     */
    private void sporeHit() {
        if (!(ctxTarget instanceof Unit)) return;

        applyStatus(ctxTarget, StatusEffects.sporeSlowed, sporeSeconds * 60f);

        int stacks = Math.min(sporeMaxStacks, spore.get(ctxTarget, 0) + 1);
        spore.put(ctxTarget, stacks);

        if (Mathf.random() >= stacks * sporeChancePerStack) return;

        spore.remove(ctxTarget, 0);
        applyStatus(ctxTarget, StatusEffects.sapped,
                (sporeSapBaseSeconds + stacks * sporeSapPerStackSeconds) * 60f);
    }

    /** 硫：5 秒燃烧 + 点燃命中处；目标身上有积焰时再补 1 层积焰 */
    private void pyratiteHit() {
        applyStatus(ctxTarget, StatusEffects.burning, burnSeconds * 60f);
        Tile tile = tileAt(ctxX, ctxY);
        if (tile != null) Fires.create(tile);
        Fx.fire.at(ctxX, ctxY);
        addFlameLayerIfAny();
    }

    /** 目标身上已经有积焰印记时，额外施加 1 层（硫 / 爆混的联动） */
    private void addFlameLayerIfAny() {
        if (RaenEffectJY.INSTANCE.has(ctxTarget)) {
            RaenEffectJY.INSTANCE.addLayer(ctxTarget, 1);
        }
    }

    /** 爆混：爆炸 + 5 格 1% 范围伤害（建筑也吃）；目标有积焰时再补 1 层积焰 */
    private void blastHit(float damage) {
        applyStatus(ctxTarget, StatusEffects.blasted, blastSeconds * 60f);
        areaDamage(ctxTeam, ctxX, ctxY, blastRadiusTiles * 8f, damage * blastDamageRatio, null, true);
        Fx.blastExplosion.at(ctxX, ctxY);
        addFlameLayerIfAny();
    }

    /** 煤：叠层，按层数概率触发硫的效果 */
    private void coalHit() {
        int stacks = Math.min(coalMaxStacks, coal.get(ctxTarget, 0) + 1);
        coal.put(ctxTarget, stacks);

        if (Mathf.random() >= stacks * coalChancePerStack) return;
        coal.remove(ctxTarget, 0);
        pyratiteHit();
    }

    /** 废料：叠层，之后每 5 秒消耗 1 层施加 1 秒静止 */
    private void scrapHit() {
        if (!(ctxTarget instanceof Unit)) return;

        float[] state = scrap.get(ctxTarget);
        if (state == null) {
            state = new float[]{0f, 0f};
            scrap.put(ctxTarget, state);
        }
        state[0] = Math.min(scrapMaxStacks, state[0] + 1f);
    }

    /**
     * 沙子：泥泞 + 叠 1 层；每层 1% 概率在目标处立一堵临时墙
     * （生命 50 + 层数，存在 5 + 层数×0.1 秒，可被打掉），触发后清层。
     */
    private void sandHit() {
        applyStatus(ctxTarget, StatusEffects.muddy, mudSeconds * 60f);

        int stacks = Math.min(sandMaxStacks, sand.get(ctxTarget, 0) + 1);
        sand.put(ctxTarget, stacks);

        if (Mathf.random() >= stacks * sandChancePerStack) return;

        if (ctxTeam == null || RaenBlocks.tempWall == null) return;
        // 墙会避开单位和已有方块，最多偏移 RaenTempWall.maxOffsetTiles 格；
        // 到处都放不下时不清层，把这次机会留给下一次命中
        boolean placed = RaenBlocks.tempWall.place(ctxTeam, ctxTarget.x(), ctxTarget.y(),
                wallBaseHealth + stacks * wallHealthPerStack,
                wallBaseSeconds + stacks * wallSecondsPerStack);
        if (placed) sand.remove(ctxTarget, 0);
    }

    /** 氧化物：5 格腐蚀 */
    private void oxideHit() {
        statusArea(StatusEffects.corroded, oxideRadiusTiles * 8f, oxideSeconds * 60f);
    }

    /**
     * 钍：30% 穿甲 —— 引擎按护甲减免掉的伤害补回来一部分，
     * 等效「额外造成 敌方护甲值 × {@link #thoriumArmorPierce} 的伤害」。
     */
    private void armorPierceHit() {
        float armor = armorOf(ctxTarget);
        if (armor <= 0f) return;
        ctxTarget.damage(armor * thoriumArmorPierce);
    }

    /** 碳化物：额外造成一次等于敌方护甲的伤害 */
    private void carbideHit() {
        float armor = armorOf(ctxTarget);
        if (armor > 0f) ctxTarget.damage(armor);
    }

    // ==================== 追踪 ====================

    /**
     * 把这发子弹登记进追踪表。
     *
     * @param rangeTiles 追踪搜索半径（格）：硅用物品定义里的 24，玻璃碎片用 12
     */
    public void track(Bullet bullet, float rangeTiles) {
        if (bullet == null || rangeTiles <= 0f) return;

        int encoded = Math.max(1, Math.round(rangeTiles));
        if (RaenBulletMarks.getBulletMark(bullet, HOMING) < encoded) {
            RaenBulletMarks.addBulletMark(bullet, HOMING, encoded);
        }
        if (!homing.contains(bullet, true)) homing.add(bullet);
    }

    /** 每帧把追踪子弹的速度方向掰向最近的敌人 */
    private void tickHoming(float delta) {
        for (int i = homing.size - 1; i >= 0; i--) {
            Bullet bullet = homing.get(i);
            // 子弹被回收 / 标签被清 → 退出追踪表；标签层数就是搜索半径（格）
            int rangeTiles = (bullet == null || !bullet.isAdded()) ? 0 : RaenBulletMarks.getBulletMark(bullet, HOMING);
            if (rangeTiles <= 0) {
                homing.remove(i);
                continue;
            }

            Unit target = Units.closestEnemy(bullet.team, bullet.x, bullet.y,
                    rangeTiles * 8f, u -> u.isValid() && u.checkTarget(bullet.type.collidesAir, bullet.type.collidesGround));
            if (target == null) continue;

            float speed = bullet.vel.len();
            if (speed < 0.01f) continue;

            float want = Angles.angle(bullet.x, bullet.y, target.x, target.y);
            float next = Angles.moveToward(bullet.vel.angle(), want, homingTurnDegrees * delta);
            bullet.vel.set(Angles.trnsx(next, speed), Angles.trnsy(next, speed));
        }
    }

    // ==================== 持续结算 / 清理 ====================

    @Override
    public void onTick(float delta) {
        tickHoming(delta);
        tickScrap(delta);
    }

    /** 废料：每 5 秒消耗 1 层，施加 1 秒静止 */
    private void tickScrap(float delta) {
        if (scrap.size == 0) return;

        expired.clear();
        final float interval = scrapIntervalSeconds * 60f;

        for (ObjectMap.Entry<Healthc, float[]> entry : scrap) {
            Healthc target = entry.key;
            float[] state = entry.value;
            if (target == null || state == null) continue;

            if (target.dead() || state[0] <= 0f) {
                expired.add(target);
                continue;
            }

            state[1] += delta;
            while (state[1] >= interval && state[0] > 0f) {
                state[1] -= interval;
                state[0] -= 1f;
                applyStatus(target, StatusEffects.unmoving, scrapFreezeSeconds * 60f);
            }
            if (state[0] <= 0f) expired.add(target);
        }

        for (int i = 0; i < expired.size; i++) {
            if (expired.get(i) != null) scrap.remove(expired.get(i));
        }
    }

    /**
     * 目标死亡 / 世界重置时清掉它在叠层表里的记录。
     * <p>
     * ★ 这里必须重写 {@code clearLayer} 而不是 {@code onDeath}：事件分发在调用 {@code onDeath}
     * 之前会先用 {@code has(target)} 过滤，而本效果不往父类的 {@code layers} 里写层数
     * （层数分别记在 lead / coal / scrap 三张表里），过滤永远是 false —— 只有 {@code clearLayer}
     * 是无条件调用的。
     */
    @Override
    public void clearLayer(Healthc target) {
        super.clearLayer(target);
        if (target != null) {
            lead.remove(target, 0);
            coal.remove(target, 0);
            spore.remove(target, 0);
            sand.remove(target, 0);
            scrap.remove(target);
        }
    }

    @Override
    public void onReset() {
        lead.clear();
        coal.clear();
        spore.clear();
        sand.clear();
        scrap.clear();
        homing.clear();
        expired.clear();
    }

    // ==================== 工具 ====================

    /** 范围内给敌方单位施加状态 */
    private void statusArea(StatusEffect effect, float radius, float duration) {
        if (radius <= 0f) return;
        final float x = ctxX, y = ctxY;
        final float r2 = radius * radius;

        Groups.unit.intersect(x - radius, y - radius, radius * 2f, radius * 2f, unit -> {
            if (unit == null || unit.dead() || unit.team == ctxTeam) return;
            if (unit.dst2(x, y) > r2) return;
            unit.apply(effect, duration);
        });
    }

    /** 世界坐标取格子 */
    private static Tile tileAt(float x, float y) {
        return Vars.world == null ? null : Vars.world.tileWorld(x, y);
    }

    /** 目标护甲（单位取 armor，建筑取方块护甲） */
    private static float armorOf(Healthc target) {
        if (target instanceof Unit) return ((Unit) target).armor;
        if (target instanceof Building) return ((Building) target).block.armor;
        return 0f;
    }

    // ==================== 详情文案 ====================

    public String describe() {
        return Core.bundle.format("raen.taghit.title", text.color) + "\n" +
                Core.bundle.get("raen.taghit.intro") + "\n" +
                "  · " + Core.bundle.format("raen.attr.explode",
                        fmt(explodeRadiusPerPoint), fmt(explodeFlatPerPoint), fmt(explodePercentPerPoint * 100f)) + "\n" +
                "  · " + Core.bundle.format("raen.attr.ignite", fmt(igniteRadiusPerPoint), fmt(igniteSeconds)) + "\n" +
                "  · " + Core.bundle.get("raen.attr.radioactive") + "\n" +
                "  · " + Core.bundle.format("raen.attr.charge", fmt(chargeTagChance)) + "\n" +
                Core.bundle.get("raen.taghit.footer") + "\n" +
                Core.bundle.get("raen.taghit.itemsHeader") + "\n[]" +
                RaenItemTags.describeAll();
    }

    private static String fmt(float value) {
        if (value == (int) value) return String.valueOf((int) value);
        return String.format("%.2f", value);
    }
}
