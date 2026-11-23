package raen.Bullet;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.entities.bullet.BulletType;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;

/**
 * 「临时盾」：由「布」的标签在命中处生成的一个静止六边形护罩。
 * <p>
 * 与 {@code fz.en.ShieldBullet} 的区别（用户要求的「改进」）：
 * <ul>
 *   <li><b>不随时间缩小</b>：半径恒定，只有<b>血量归零</b>或<b>存在时间归零</b>才消失；</li>
 *   <li><b>有血量</b>：每吸收一发敌方子弹就扣掉那发子弹的伤害，扣光就碎；</li>
 *   <li>「仅受伤害时掉血」：不吸收子弹时血量不会自己衰减。</li>
 * </ul>
 * 运行时数据（当前血量 / 最大血量 / 半径）存在 {@code bullet.data} 里，
 * 生命周期交给 {@code bullet.lifetime}（引擎到点自动回收）。
 */
public class RaenShieldBullet extends BulletType {

    /** 同时在场的护罩上限（防止刷屏 + 控制每帧的子弹扫描开销） */
    public static int maxActive = 16;

    /** 单例类型（运行时懒加载） */
    public static RaenShieldBullet type;

    /** 场上还活着的护罩（只用于上限判断与清理） */
    private static final Seq<Bullet> active = new Seq<>();

    /** 护罩颜色：默认取「布」的物品颜色 */
    public Color shieldColor = Items.phaseFabric.color;
    /** 六边形边数（原版力场也是多边形，6 = 六边形） */
    public int sides = 6;
    /** 六边形旋转速度（度/帧） */
    public float rotateSpeed = 0.4f;

    /** 每个护罩的运行时状态，存在 {@code bullet.data} 里 */
    public static class ShieldState {
        public float hp;
        public float maxHp;
        public float radius;
    }

    /** 建好单例类型（可重复调用）；不能叫 {@code load} —— 那是 {@link BulletType} 的实例方法 */
    public static void ensureType() {
        if (type != null) return;

        type = new RaenShieldBullet();
        type.load();
    }

    public RaenShieldBullet() {
        speed = 0f;
        damage = 0f;
        lifetime = 60f;

        collides = false;
        collidesAir = false;
        collidesGround = false;
        collidesTiles = false;
        hittable = false;
        absorbable = false;
        keepVelocity = false;
        reflectable = false;

        hitEffect = Fx.none;
        despawnEffect = Fx.none;
        shootEffect = Fx.none;
        smokeEffect = Fx.none;
    }

    /**
     * 生成一个临时盾。
     *
     * @param radius        生效半径（世界单位，8 = 1 格）
     * @param lifetimeTicks 存在时长（tick）
     * @param hp            可承受的总伤害
     * @return 生成的子弹；达到上限或创建失败时返回 null
     */
    public static Bullet spawn(Team team, float x, float y, float radius, float lifetimeTicks, float hp) {
        ensureType();

        // 清掉已经消失的护罩，再判断上限
        for (int i = active.size - 1; i >= 0; i--) {
            Bullet b = active.get(i);
            if (b == null || !b.isAdded()) active.remove(i);
        }
        if (active.size >= maxActive) return null;

        Bullet b = type.create(null, team, x, y, 0f);
        if (b == null) return null;

        b.lifetime = Math.max(6f, lifetimeTicks);

        ShieldState st = new ShieldState();
        st.hp = Math.max(1f, hp);
        st.maxHp = st.hp;
        st.radius = Math.max(4f, radius);
        b.data = st;

        // ⚠ 不能用 RaenBulletMarks 打 NO_TAG：标签就存在 bullet.data 里，会覆盖上面的 ShieldState。
        //    领域那边改成按类型跳过（RaenApplyBlock 里判 bullet.type == RaenShieldBullet.type）。

        active.add(b);
        Fx.absorb.at(x, y, type.shieldColor);
        return b;
    }

    @Override
    public void update(Bullet b) {
        if (!(b.data instanceof ShieldState st)) return;

        final float r = st.radius;
        final float r2 = r * r;

        Groups.bullet.intersect(b.x - r, b.y - r, r * 2f, r * 2f, other -> {
            if (other == null || other == b) return;
            if (other.team == b.team) return;
            // 与护盾同规则：只挡「能被吸收」的子弹（激光 / 射线类本来就不该被一个罩子吃掉）
            if (!other.type.absorbable) return;
            if (other.dst2(b.x, b.y) > r2) return;

            // 只有这里会掉血：吸收的伤害 = 那发子弹的伤害
            st.hp -= Math.max(0f, other.damage);
            other.absorb();
        });

        if (st.hp <= 0f) {
            Fx.absorb.at(b.x, b.y, shieldColor);
            b.remove();
        }
    }

    /**
     * 绘制：照原版「护盾力场」的画法 —— 半透明填充 + 呼吸脉动 + 低速旋转的六边形描边。
     * <p>
     * 原版 {@code ForceProjector.ForceBuild.drawShield()} 就是
     * {@code Draw.color(color) → Draw.alpha(低值) → Fill.poly(...)} 再来一圈 {@code Lines.poly}，
     * 这里照抄结构，只是颜色取「布」的物品色、透明度随剩余血量与呼吸变化。
     */
    @Override
    public void draw(Bullet b) {
        if (!(b.data instanceof ShieldState st)) return;

        float ratio = st.maxHp <= 0f ? 0f : Mathf.clamp(st.hp / st.maxHp);
        float pulse = 0.5f + 0.5f * Mathf.sin(Time.time, 40f, 1f);   // 0~1 呼吸
        float rot = Time.time * rotateSpeed;

        // 外层：半透明填充（呼吸 + 血量越少越淡）
        Draw.color(shieldColor);
        Draw.alpha(0.06f + 0.06f * pulse + 0.05f * ratio);
        Fill.poly(b.x, b.y, sides, st.radius, rot);

        // 内层：反向慢转的一圈，做出力场的层次感
        Draw.alpha(0.05f + 0.05f * pulse);
        Fill.poly(b.x, b.y, sides, st.radius * 0.86f, -rot * 0.7f);

        // 描边：血量越低越细越淡
        Draw.color(shieldColor, 0.65f + 0.25f * ratio);
        Lines.stroke(1.6f + 1.2f * ratio);
        Lines.poly(b.x, b.y, sides, st.radius, rot);

        Draw.reset();
    }
}
