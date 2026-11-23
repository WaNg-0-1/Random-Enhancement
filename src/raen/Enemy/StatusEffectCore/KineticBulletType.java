package raen.Enemy.StatusEffectCore;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.math.Mathf;
import arc.util.Tmp;
import mindustry.content.Fx;
import mindustry.entities.bullet.MissileBulletType;
import mindustry.gen.Bullet;
import mindustry.graphics.Drawf;

import static mindustry.Vars.tilesize;

/**
 * 动能子弹 —— 飞得越久、速度越快、伤害越高。
 *
 * <p>移植自 fz 模组（{@code fz.en.newl.KineticBulletType}），用于「动能反击」。
 * 绘制全走几何图元，所以可以运行时 new：不会像普通 BulletType 那样因
 * 贴图区域为 null 而在绘制时 NPE。
 *
 * <h3>原理（原版 drag）</h3>
 * 原版 {@code BulletComp.update()} 每帧做两件事：
 * <pre>
 *   x += vel.x * Time.delta;
 *   vel.scl(Math.max(1f - type.drag * Time.delta, 0));
 *   // 之后如果 type.accel != 0：vel.setLength(vel.len() + accel * Time.delta)
 * </pre>
 * 当 {@code drag < 0} 时，缩放系数恒大于 1，速度按帧指数放大，子弹越飞越快。
 * 但它是<b>按当前速度成比例</b>放大的：初速只有 1 时，{@code drag = -0.006}
 * 每帧才涨 0.006，肉眼几乎看不出变化 —— 这就是"初始速度过小时 drag 似乎不生效"的原因。
 *
 * <p>本类的处理：
 * <ol>
 *   <li>保留 {@code drag < 0}，速度越到生命末期涨得越猛（指数特性）；</li>
 *   <li>叠加原版 {@code accel}：它是<b>恒定加量</b>{@code vel.len() + accel*delta}，
 *       与当前速度无关，保证低初速也能立刻起步，不会卡在原地；</li>
 *   <li>{@link #init(Bullet)} 里再给一个初速下限 {@link #minSpeed}，
 *       防止 {@code velocityScl} / 池复用导致速度接近 0 时 setLength 失效。</li>
 * </ol>
 *
 * <h3>伤害</h3>
 * 伤害直接绑定<b>实时速度</b>：{@code 倍率 = 1 + (上限-1) * 归一化(当前速度 - 初速)}，
 * 速度达到 {@link #speedForMaxDamage} 时封顶为 {@link #maxDamageMultiplier}，永不超过。
 * 引擎在 {@code hitEntity} / 建筑碰撞 / 吸血里读的都是 {@code b.damage}，
 * 所以每帧按倍率重写 {@code b.damage} 即可让所有伤害通道一起随动能增长；
 * 溅射伤害在 {@link #hit(Bullet, float, float, boolean)} 里单独同步缩放。
 *
 * <h3>预设</h3>
 * {@code new KineticBulletType()} = 基础伤害 20、最高 2.5 倍（封顶 50）、初速 1、射程 45 格。
 * 由构造时二分求解得到：{@code lifetime = 120 tick (2s)}、{@code drag = -0.006}、
 * {@code accel ≈ 0.0204}，末速 ≈ 5.61，累计位移恰好 360 世界单位 = 45 格。
 * 生命末期速度最快、倍率最接近 2.5；提前命中则按比例只拿到较低伤害。
 *
 * <h3>远程预设（180 格）</h3>
 * {@link #longRange()} = 初速 4、{@code lifetime = 240 tick (4s)}、{@code drag = -0.002}，
 * 反解出 {@code accel ≈ 0.006381}，末速 ≈ 8.41，累计位移 1440 世界单位 = 180.00 格。
 * 射程变 4 倍后不能沿用 45 格的参数：负 drag 是指数放大，长时间下会直接把速度推到失控，
 * 所以初速抬高、drag 调小、让 {@code accel} 承担主要加速，速度全程 4 → 8.41（2.1 倍）稳步上升。
 *
 * <pre>
 * weapon.bullet = new KineticBulletType();                    // 45 格预设
 * weapon.bullet = KineticBulletType.longRange();              // 180 格预设
 * weapon.bullet = new KineticBulletType(30f, 3f, 2f, 60f);   // 自定义 伤害/倍率/初速/格数
 * // 改过 lifetime / drag / speed / rangeTiles 之后调用 bullet.recalculate() 重新标定
 * </pre>
 * 绘制完全走几何图元（不依赖贴图），因此运行时 {@code new} 出来的实例即使没走
 * 内容加载流程、贴图区域为 null，也不会在绘制时空指针。
 */
public class KineticBulletType extends MissileBulletType{

    // ───────────────────────── 可调参数 ─────────────────────────

    /** 最高伤害倍率，倍率恒为 [1, maxDamageMultiplier] */
    public float maxDamageMultiplier = 2.5f;
    /** 射程（格），内部换算为世界单位 = rangeTiles * tilesize(8) */
    public float rangeTiles = 45f;
    /** 达到最高倍率所需的速度，由 {@link #recalculate()} 按末速自动标定 */
    public float speedForMaxDamage = 5f;
    /** 自动标定时取末速的多少作为满倍率速度，留余量保证末期一定摸到上限 */
    public float fullPowerSpeedFraction = 0.95f;
    /** 伤害增长曲线指数：1 = 线性跟速度走，>1 = 更偏生命末期发力 */
    public float damageRampPow = 1f;
    /** 初速下限，修复低初速时负 drag 增长过慢的问题 */
    public float minSpeed = 1f;
    /** 溅射伤害是否一起按动能倍率放大 */
    public boolean scaleSplashDamage = true;

    // ───────────────────────── 表现参数 ─────────────────────────
    /** 低速冷色 → 高速热色 */
    public Color coldColor = Color.valueOf("9ad9ff");
    public Color hotColor = Color.valueOf("ffb454");
    /** 尾迹长度 = tailLength + tailGrow * 充能度 */
    public float tailLength = 7f, tailGrow = 22f;
    /** 锥形尾焰根部宽度（满充能时），尾端会收尖，避免画成一根粗矩形。 */
    public float flameBaseWidth = 3.5f;
    /** 尾焰尾端宽度。 */
    public float flameTipWidth = 0.35f;
    /** 内层白热焰长度占整段尾焰的比例。 */
    public float innerFlameFraction = 0.55f;

    // ───────────────────────── 构造 ─────────────────────────

    /** 预设：20 伤害 / 2.5 倍 / 初速 1 / 45 格 */
    public KineticBulletType(){
        this(20f, 2.5f, 1f, 45f);
    }

    /**
     * 180 格远程预设：20 伤害 / 2.5 倍 / 初速 4 / lifetime 240 tick / drag -0.002，
     * accel 由 {@link #recalculate()} 反解为 ≈0.006381，末速 ≈8.41，射程精确 180 格。
     * <p>
     * 注意：当前构造器里 {@code homingPower = 1f}，强追踪会让弹道拐弯，
     * 180 格是"路径长度"而非"直线覆盖半径"。需要严格直线射程时用
     * {@link #longRange(boolean)} 传 false，或把返回值的 homingPower 置 0。
     */
    public static KineticBulletType longRange(){
        return longRange(true);
    }

    /** @param homing 是否保留追踪；false = 关闭 homingPower，走严格 180 格直线弹道 */
    public static KineticBulletType longRange(boolean homing){
        KineticBulletType b = new KineticBulletType(20f, 2.5f, 4f, 180f, 240f, -0.002f);
        if(!homing){
            b.homingPower = 0f;
            b.homingRange = 0f;
        }
        return b;
    }

    public KineticBulletType(float damage, float maxMultiplier, float speed, float rangeTiles){
        this(damage, maxMultiplier, speed, rangeTiles, 120f, -0.006f);
    }

    /**
     * @param damage        基础（出生时）伤害
     * @param maxMultiplier 最高伤害倍率
     * @param speed         初速（世界单位/tick）
     * @param rangeTiles    期望射程（格），构造时会反解出正好飞完这段距离的 accel
     * @param lifetime      生命时长（tick）
     * @param drag          必须为负；负得越多越"后期爆发"
     */
    public KineticBulletType(float damage, float maxMultiplier, float speed, float rangeTiles,
                             float lifetime, float drag){
        super(speed, damage);

        this.damage = damage;
        this.maxDamageMultiplier = Math.max(1f, maxMultiplier);
        this.rangeTiles = Math.max(1f, rangeTiles);
        this.lifetime = Math.max(2f, lifetime);
        // 负 drag 是本类型的立身之本；传了 >=0 也强行拉回负值
        this.drag = Math.min(drag, -0.0001f);

        // 追踪只改方向、不改速度长度，所以不影响动能倍率；但弹道会变弯，
        // 标定出的射程是"路径长度"而非直线覆盖。要严格直线射程请把 homingPower 置 0。
        homingPower = 1f;
        homingRange = 80f;
        weaveMag = 0f;
        weaveScale = 0f;
        rotateSpeed = 0f;
        keepVelocity = false;
        scaleKeepVelocity = false;
        pierce = false;
        pierceBuilding = false;
        collides = true;
        collidesTiles = true;
        collidesAir = true;
        collidesGround = true;
        hittable = true;

        // ── 表现：原版导弹外观参数保留，但绘制由本类接管 ──
        sprite = "missile";
        hitSize = 3f;
        trailLength = 16;
        // 初速越大拖尾越长，视锥剔除尺寸要跟着放大（原版 init() 用的也是这个算法）
        drawSize = 8f;
        trailWidth = 1.8f;
        trailChance = 0f;
        trailInterval = 0f;
        trailEffect = Fx.none;
        smokeEffect = Fx.none;
        shootEffect = Fx.shootSmall;
        hitEffect = Fx.hitBulletSmall;
        despawnEffect = Fx.hitBulletSmall;
        trailColor = coldColor;
        hitColor = hotColor;
        lightColor = hotColor;
        lightRadius = 22f;
        lightOpacity = 0.6f;

        recalculate();
    }

    // ───────────────────────── 标定 ─────────────────────────

    /**
     * 重新反解 accel、标定满倍率速度并刷新射程。
     * 手动改过 {@code lifetime / drag / speed / rangeTiles / fullPowerSpeedFraction} 后调用。
     * 想自定义 {@code accel} 或 {@code speedForMaxDamage}，请在本方法之后赋值。
     */
    public void recalculate(){
        accel = solveAcceleration();
        float[] flight = simulate(accel);
        speedForMaxDamage = Math.max(startSpeed() + 0.001f, flight[1] * fullPowerSpeedFraction);
        range = calculateRange();
    }

    /** 名义初速（跟 {@link #init(Bullet)} 里的下限保持一致） */
    private float startSpeed(){
        return Math.max(speed, minSpeed);
    }

    /**
     * 二分求出：在 lifetime 帧内恰好飞完 rangeTiles 格所需的恒定加速度。
     * 下界允许为负 —— 射程拉到 180 格后，某些 drag/lifetime 组合光靠负 drag
     * 就会飞超目标（例如初速 5 + 240 tick + drag -0.003 会飞 218 格），
     * 这时必须用负 accel 把射程压回来；旧实现直接返回 0 会让 range 悄悄超标。
     */
    private float solveAcceleration(){
        float target = Math.max(1f, rangeTiles * tilesize);

        float lo = -1f, hi = 1f;
        while(simulate(lo)[0] > target && lo > -1e6f) lo *= 2f;
        while(simulate(hi)[0] < target && hi < 1e6f) hi *= 2f;
        for(int i = 0; i < 64; i++){
            float mid = (lo + hi) * 0.5f;
            if(simulate(mid)[0] < target) lo = mid;
            else hi = mid;
        }
        return (lo + hi) * 0.5f;
    }

    /**
     * 按 60FPS（Time.delta = 1）逐帧复刻引擎弹道，返回 {总位移, 末速度}。
     * 顺序：首帧 justSpawned 不位移也不吃 drag → 位移 + drag → accel → time += 1 → 到寿命移除。
     */
    private float[] simulate(float accelValue){
        float v = startSpeed();
        float x = 0f, t = 0f;
        boolean first = true;

        while(t < lifetime - 0.0001f){
            if(!first){
                x += v;
                v *= Math.max(1f - drag, 0f);
            }
            // 引擎是 vel.setLength(vel.len() + accel*delta)：长度取绝对值、方向不变，
            // 所以负 accel 是减速而不是反向。二分的下界分支要照这个语义才算得准。
            v = Math.abs(v + accelValue);
            t = Math.min(t + 1f, lifetime);
            first = false;
        }
        return new float[]{x, v};
    }

    /** 原版 calculateRange() 忽略 accel，会把射程算少；这里用真实模拟位移 */
    @Override
    protected float calculateRange(){
        return Math.max(simulate(accel)[0], maxRange);
    }

    // ───────────────────────── 伤害倍率 ─────────────────────────

    /** 当前动能倍率，范围 [1, maxDamageMultiplier] */
    public float kineticMultiplier(Bullet b){
        float start = b.fdata > 0f ? b.fdata : startSpeed();
        float span = Math.max(speedForMaxDamage - start, 0.001f);
        float t = Mathf.clamp((b.vel.len() - start) / span, 0f, 1f);
        if(damageRampPow != 1f) t = (float)Math.pow(t, Math.max(0.01f, damageRampPow));
        return 1f + (maxDamageMultiplier - 1f) * t;
    }

    /** 充能度 0~1，供绘制 / 外部逻辑使用 */
    public float charge(Bullet b){
        return Mathf.clamp((kineticMultiplier(b) - 1f) / Math.max(maxDamageMultiplier - 1f, 0.001f), 0f, 1f);
    }

    @Override
    public void init(Bullet b){
        super.init(b);

        // 低初速兜底：负 drag 是比例放大，速度趋近 0 时增量也趋近 0。
        // 这里给一个下限，再由 accel 的恒定加量接手，保证一出生就在加速。
        if(b.vel.isZero(0.0001f)){
            b.vel.trns(b.rotation(), minSpeed);
        }else if(b.vel.len() < minSpeed){
            b.vel.setLength(minSpeed);
        }

        // 记录本发子弹的真实初速（可能受 velocityScl 影响）
        b.fdata = b.vel.len();
        b.damage = damage * b.damageMultiplier();   // 出生时倍率 = 1
    }

    @Override
    public void update(Bullet b){
        super.update(b);

        // 引擎在 hitEntity / BuildingComp.collision / lifesteal 里读的都是 b.damage，
        // 每帧按实时速度重写，所有伤害通道便自动跟着动能走。
        b.damage = damage * b.damageMultiplier() * kineticMultiplier(b);
    }

    @Override
    public void hit(Bullet b, float x, float y, boolean createFrags){
        if(!scaleSplashDamage || splashDamage <= 0f){
            super.hit(b, x, y, createFrags);
            return;
        }

        // 溅射走的是 splashDamage * b.damageMultiplier()，不吃 b.damage。
        // 类型是全局共享对象，所以只在结算期间临时放大，finally 中立刻还原。
        float original = splashDamage;
        splashDamage = original * kineticMultiplier(b);
        try{
            super.hit(b, x, y, createFrags);
        }finally{
            splashDamage = original;
        }
    }

    // ───────────────────────── 绘制 ─────────────────────────

    @Override
    public void drawTrail(Bullet b){
        if(trailLength > 0 && b.trail != null){
            float z = Draw.z();
            Draw.z(z - 0.0001f);
            b.trail.draw(Tmp.c1.set(coldColor).lerp(hotColor, charge(b)), trailWidth);
            Draw.z(z);
        }
    }

    /**
     * 不调用 BasicBulletType.draw()：运行时创建的子弹类型不一定走过内容加载，
     * 贴图区域可能为 null。这里纯几何绘制，顺带把"越飞越快"画出来。
     */
    @Override
    public void draw(Bullet b){
        drawTrail(b);
        drawParts(b);

        float charge = charge(b);
        float angle = b.rotation();
        float cos = Mathf.cosDeg(angle), sin = Mathf.sinDeg(angle);
        float len = tailLength + tailGrow * charge;
        Color c = Tmp.c1.set(coldColor).lerp(hotColor, charge);

        // 锥形尾焰：根部宽、尾端收尖，替代原先一根等宽粗线（高速时像橙色长方形）。
        // 垂直方向单位向量 = (-sin, cos)，四个顶点围成一条向后收窄的焰带。
        float px = -sin, py = cos;
        float baseW = 0.8f + (flameBaseWidth - 0.8f) * charge;
        Draw.color(c, 0.22f + 0.38f * charge);
        Fill.quad(
                b.x + px * baseW, b.y + py * baseW,
                b.x - px * baseW, b.y - py * baseW,
                b.x - cos * len - px * flameTipWidth, b.y - sin * len - py * flameTipWidth,
                b.x - cos * len + px * flameTipWidth, b.y - sin * len + py * flameTipWidth);

        // 内层白热焰：更窄更短，给尾焰一个亮芯，分层后不再是一块平板。
        float innerLen = len * innerFlameFraction;
        Draw.color(Color.white, 0.18f + 0.30f * charge);
        Fill.quad(
                b.x + px * baseW * 0.45f, b.y + py * baseW * 0.45f,
                b.x - px * baseW * 0.45f, b.y - py * baseW * 0.45f,
                b.x - cos * innerLen - px * 0.15f, b.y - sin * innerLen - py * 0.15f,
                b.x - cos * innerLen + px * 0.15f, b.y - sin * innerLen + py * 0.15f);

        // 弹芯 + 白热高光
        Draw.color(c);
        Fill.circle(b.x, b.y, 1.8f + 1.7f * charge);
        Draw.color(Color.white, 0.55f + 0.45f * charge);
        Fill.circle(b.x, b.y, 0.9f + 0.6f * charge);

        Draw.reset();
    }

    @Override
    public void drawLight(Bullet b){
        float charge = charge(b);
        Drawf.light(b, lightRadius * (1f + charge), hotColor, lightOpacity * (0.4f + 0.6f * charge));
    }
}
