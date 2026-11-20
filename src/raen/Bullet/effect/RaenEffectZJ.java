package raen.Bullet.effect;

import arc.Core;
import arc.graphics.Color;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.content.Fx;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.gen.Healthc;
import raen.Bullet.RaenEffectBase;
import raen.Bullet.RaenEffectText;

import static raen.Bullet.RaenEffectText.num;

/**
 * 效果 —— 追击（ZhuiJi）
 */
public class RaenEffectZJ extends RaenEffectBase {

    public static final String ID = "raenzjChase";
    public static final RaenEffectZJ INSTANCE;

    static {
        INSTANCE = new RaenEffectZJ();
        register(INSTANCE);
    }

    /** 结算间隔（tick，60 = 1 秒） */
    public float interval = 60f;
    /** 伤害半径下限（格）：实际半径 = 该值 + 层数 × {@link #splashRadiusPerStack}，上限 {@link #splashRadiusMax} */
    public float splashRadiusMin = 2.6f;
    /** 每多 1 层追加的半径（格）：1/8 格 = 每 8 层 +1 格 */
    public float splashRadiusPerStack = 1f / 8f;
    /** 伤害半径上限（格）：层数再高也不会超过它 */
    public float splashRadiusMax = 4.8f;
    /** 追击子弹每帧飞行像素 */
    public float bulletSpeed = 25f;
    /** 层数阈值：低于它每次只消耗 1 层、伤害 1；达到后把超过阈值的层数一次性消耗 */
    public int threshold = 10;
    /** 达到阈值后每多 1 层追加的伤害 */
    public float extraDamagePerStack = 0.1f;
    /** 主题色 */
    public Color color = Color.valueOf("88DDFF");

    private final RaenEffectText text;
    /** 各目标的施加方队伍（用于范围伤害的敌我判定） */
    private final ObjectMap<Healthc, Team> teamMap = new ObjectMap<>();
    /** 复用：本次要结算的目标 */
    private final Seq<Healthc> snapshot = new Seq<>();
    /** 追击子弹类型：只建一次、只 load 一次 */
    private BasicBulletType homingType;
    private float timer = 0f;

    public RaenEffectZJ() {
        fallbackDamage = 5f;
        text = RaenEffectText.key("raen.tag.zj.name", "88DDFF", this::describe);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RaenEffectText text() {
        return text;
    }

    @Override
    public void clearLayer(Healthc target) {
        super.clearLayer(target);
        if (target != null) teamMap.remove(target);
    }

    // ==================== 命中时 ====================

    @Override
    protected void onHit() {
        if (ctxTarget == null || !valid(ctxTarget)) return;

        addLayer(ctxTarget, Math.max(1, ctxStacks));
        if (ctxTeam != null) teamMap.put(ctxTarget, ctxTeam);
    }

    // ==================== 每秒结算 ====================

    @Override
    public void onTick(float delta) {
        timer += delta;
        if (timer < interval) return;
        timer = 0f;

        if (layers.size == 0) return;

        snapshot.clear();
        for (ObjectMap.Entry<Healthc, Integer> entry : layers) {
            snapshot.add(entry.key);
        }

        for (int i = 0; i < snapshot.size; i++) {
            Healthc target = snapshot.get(i);
            if (target == null) continue;
            if (target.dead()) {
                clearLayer(target);
                continue;
            }

            int stacks = layer(target);
            if (stacks <= 0) {
                clearLayer(target);
                continue;
            }

            int consume;
            float finalDamage;
            // ★ 边界必须是「> threshold」才走强化分支：
            //   若写成 >=，当 stacks 恰好等于 threshold 时 consume = 0，
            //   层数不减却每秒照常发弹 → 停止施加层后无限追击（永久 1 点/秒 DoT）。
            if (stacks <= threshold) {
                consume = 1;                                  // 剩下 10 层逐层消耗，每层一次追击
                finalDamage = 1f;
            } else {
                consume = Math.max(1, stacks - threshold);    // 消耗「超过阈值」的全部层数，保底 1
                finalDamage = 1f + consume * extraDamagePerStack;
            }

            // ★ 必须先取队伍：下面的 clearLayer 会把它一起删掉
            Team team = teamMap.get(target);

            int newStacks = stacks - consume;
            if (newStacks <= 0) clearLayer(target);
            else setLayer(target, newStacks);

            if (team == null) {
                trueDamage(target, finalDamage);
            } else {
                // 伤害半径随层数增长：2.6 + 层数/8 格（上限 4.8 格）
                float radius = Math.min(splashRadiusMax, splashRadiusMin + stacks * splashRadiusPerStack) * 8f;
                spawnHomingBullet(target, team, finalDamage, radius);
            }
        }
    }

    /** 生成一枚从 (0,0) 精准飞向目标的追击子弹（{@code radius} = 命中时的伤害半径，世界单位） */
    private void spawnHomingBullet(Healthc target, Team team, float damage, float radius) {
        if (target == null || team == null || target.dead()) return;

        float spd = Math.max(1f, bulletSpeed);
        float tx = target.x(), ty = target.y();
        float dist = Mathf.dst(0f, 0f, tx, ty);
        float angle = Angles.angle(0f, 0f, tx, ty);

        BasicBulletType type = homingType();
        type.speed = spd;

        // create() 内部已经 add()，不要再调 b.add()
        Bullet b = type.create(null, team, 0f, 0f, angle);
        if (b != null) {
            b.lifetime = dist / spd + 20f;
            b.data = target;
            b.fdata = damage;
            // ★ 借用 damage 字段存「伤害半径」：这发子弹 collides = false，引擎从不读它的 damage
            b.damage = radius;
        }
    }

    /** 追击子弹类型：懒加载 + 只 load 一次 */
    private BasicBulletType homingType() {
        if (homingType == null) {
            final RaenEffectZJ self = this;
            homingType = new BasicBulletType(1f, 0f) {{
                speed = Math.max(1f, self.bulletSpeed);
                lifetime = 120f;
                damage = 0f;
                // 关闭原生碰撞：伤害由距离检测 + 范围伤害结算
                collides = false;
                collidesAir = false;
                collidesGround = false;
                keepVelocity = false;
                // 视觉：出发 + 拖尾；命中伤害由 update 手动结算，所以 hitEffect 保持 none
                shootEffect = Fx.shootSmall;
                trailEffect = Fx.hitBulletSmall;
                hitEffect = Fx.none;
                despawnEffect = Fx.none;
                smokeEffect = Fx.none;
                width = 4f;
                height = 8f;
                frontColor = self.color;
                backColor = self.color;
            }

                @Override
                public void update(Bullet b) {
                    super.update(b);
                    if (b == null) return;

                    Object d = b.data;
                    if (!(d instanceof Healthc)) {
                        b.remove();
                        return;
                    }

                    Healthc t = (Healthc) d;
                    if (t.dead()) {
                        b.remove();
                        return;
                    }

                    float x = t.x(), y = t.y();
                    if (Mathf.dst(b.x, b.y, x, y) <= Math.max(self.bulletSpeed, 6f)) {
                        Fx.hitLancer.at(x, y);
                        Fx.shockwave.at(x, y);
                        // 半径是发射时按层数算好的，存在 b.damage 里（见 spawnHomingBullet）
                        float radius = b.damage > 0f ? b.damage : self.splashRadiusMax * 8f;
                        areaDamage(b.team, x, y, radius, b.fdata, null, true);
                        b.remove();
                    }
                }
            };
            // 运行时 new 出来的子弹类型必须补 load()，否则贴图区域为 null → 绘制 NPE
            homingType.load();
        }
        return homingType;
    }

    // ==================== 目标被摧毁 ====================

    @Override
    public void onDeath(Healthc target) {
        if (target != null) teamMap.remove(target);
        super.onDeath(target);
    }

    // ==================== 世界重置 ====================

    @Override
    public void onReset() {
        timer = 0f;
        teamMap.clear();
        snapshot.clear();
    }

    // ==================== 详情页文本 ====================

    public String describe() {
        return Core.bundle.format("raen.tag.zj.title", text.color) + "\n" +
                Core.bundle.format("raen.tag.zj.l1", num(interval / 60f)) + "\n" +
                Core.bundle.format("raen.tag.zj.l2", num(splashRadiusMin), num(splashRadiusMax)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.zj.l3", threshold) + "\n" +
                "  · " + Core.bundle.format("raen.tag.zj.l4", threshold) + "\n" +
                "    " + Core.bundle.format("raen.tag.zj.l5", num(extraDamagePerStack)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.zj.l6", num(bulletSpeed)) + "\n" +
                Core.bundle.format("raen.tag.zj.l7", num(fallbackDamage));
    }
}
