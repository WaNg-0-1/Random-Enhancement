package raen.Bullet.effect;

import arc.graphics.Color;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Position;
import arc.struct.ObjectSet;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.entities.Units;
import mindustry.entities.bullet.LaserBulletType;
import mindustry.entities.bullet.MissileBulletType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Healthc;
import mindustry.gen.Hitboxc;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.world.blocks.ConstructBlock;

import static mindustry.Vars.indexer;

/**
 * 纯连锁闪电子弹。
 */
public class RaenChainLightningBullet extends MissileBulletType {

    // ───── 可调参数 ─────
    /** 最大连锁次数（含第一目标） */
    public int chainCount = 12;
    /** 每次跳跃的搜索半径 */
    public float chainRange = 280f;
    /** 伤害衰减系数 */
    public float decayFactor = 0.8f;
    /** 闪电颜色 */
    public Color lightningColor = Color.valueOf("87CEFA");
    /** 连锁对建筑造成的伤害倍率（建筑血厚，通常希望打薄一点） */
    public float buildingDamageMultiplier = 0.76f;
    /** 是否允许连锁到建筑 */
    public boolean chainBuildings = true;

    public RaenChainLightningBullet(float speed, float damage) {
        this.speed = speed;
        this.damage = damage;
        this.hitSize = 4f;
        this.lifetime = 120f;
        this.collides = true;
        // 打开格子碰撞才会打到建筑；命中建筑走的是 hitTile，不是 hitEntity
        this.collidesTiles = true;
        this.pierce = false;
        this.pierceBuilding = false;   // 撞到建筑就停下，否则子弹会直接穿过去
        this.hittable = true;          // ← 必须 true 才能击中单位！
        this.absorbable = false;
        this.status = StatusEffects.shocked;
        this.keepVelocity = false;
        // 不再生成子子弹，连锁只用闪电表现
        this.fragBullet = null;
        this.fragBullets = 0;
    }

    @Override
    public void hitEntity(Bullet b, Hitboxc entity, float health) {
        super.hitEntity(b, entity, health);

        if (entity instanceof Healthc && enemyOf(b.team, (Healthc) entity)) {
            chainFrom(b, (Healthc) entity);
        } else {
            fallbackChain(b);
        }
    }

    @Override
    public void hitTile(Bullet b, Building build, float x, float y, float initialHealth, boolean direct) {
        super.hitTile(b, build, x, y, initialHealth, direct);

        if (!direct || build.team == b.team || build.block instanceof ConstructBlock) {
            fallbackChain(b);
            return;
        }

        // 引擎对建筑不会自动结算子弹伤害（Building 不是 Hitboxc，也没人调用 hitEntity），
        // 所以这里按 hitEntity 的规则补一次：护甲倍率 / 穿透护甲 / 吸血
        damageBuilding(b, build);

        chainFrom(b, build);
    }

    /**
     * 兜底连锁。
     * <p>
     * 有些情况下命中回调拿不到合法的敌方目标（命中的是残骸、同队建筑、或是被判定成
     * 不可连锁的对象），但只要附近有敌人就应该照常起链。这里以子弹自身为起点再找一次。
     * <p>
     * 必须在 hitEntity / hitTile <b>之后</b>调用：引擎的 BulletComp.collision() 是先调
     * type.hit() 再调 hitEntity()，而且 Damage.collideLaser() 也会直接调 hit()，
     * 所以放在 hit() 里会重复起链。
     */
    private void fallbackChain(Bullet b) {
        if (b.data instanceof Boolean) return;   // 本发子弹已经起过链

        Healthc nearest = pickTarget(b.team, b, null);
        if (nearest != null) chainFrom(b, nearest);
    }

    /**
     * 复刻 BulletType.hitEntity 的伤害结算，作用于建筑。
     * 引擎对建筑不会自动结算子弹伤害（Building 不是 Hitboxc，也没人调用 hitEntity），
     * 所以这里按 hitEntity 的规则补一次：护甲倍率 / 穿透护甲 / 吸血。
     */
    private void damageBuilding(Bullet b, Building build) {
        float dealt = Math.max(Math.min(build.health, b.damage), 0f);
        float mult = armorMultiplier == 0f ? 1f : armorMultiplier;

        if (pierceArmor) {
            build.damagePierce(b.damage);
        } else if (mult != 1f) {
            build.damageArmorMult(b.damage, mult);
        } else {
            build.damage(b.damage);
        }

        if (lifesteal > 0f && b.owner instanceof Healthc) {
            ((Healthc) b.owner).heal(dealt * lifesteal);
        }
    }

    // ───────────────────────── 连锁核心 ─────────────────────────

    /**
     * 从首个命中目标开始连锁，最多 chainCount 跳。
     * <p>
     * 单位和建筑统一按 Healthc + Position 处理，所以同一条链可以在两者之间来回跳。
     * 首个目标的伤害已由 hitEntity / hitTile 结算，这里从它开始往外跳，不重复扣血。
     * <p>
     * 每一跳用一条激光（LaserBulletType）笔直连到下一个目标，
     * 不使用 Lightning.create —— 后者的闪电路径带随机散布，方向不精确。
     * 激光只作表现，不生成后续子弹、也不参与碰撞。
     * <p>
     * 注意：子弹只有能和格子碰撞（collidesTiles = true）时撞到建筑才会走到 hitTile。
     * 使用处若用匿名初始化块把它改回 false，子弹会直接穿过建筑、连锁也不触发。
     */
    private void chainFrom(Bullet b, Healthc first) {
        // 重入保护：同一发子弹只起一次链
        b.data = Boolean.TRUE;

        ObjectSet<Healthc> visited = new ObjectSet<>();
        visited.add(first);

        // current 是当前所在目标（兼作下一跳的搜索起点）
        Healthc current = first;
        float currentDamage = damage;

        for (int i = 0; i < chainCount; i++) {
            if (!current.isValid()) break;

            float cx = current.getX(), cy = current.getY();
            Fx.chainLightning.at(cx, cy, 0, lightningColor, this);

            if (i >= chainCount - 1) break;   // 已经是最后一跳，收工

            Healthc next = pickTarget(b.team, current, visited);
            if (next == null) break;

            float nx = next.getX(), ny = next.getY();
            float ang = Angles.angle(cx, cy, nx, ny);
            final float len = Mathf.dst(cx, cy, nx, ny);

            // 用激光子弹来画这条链：Lightning.create 的闪电路径带随机散布，
            // 达不到“从当前目标笔直指向下一个目标”的效果。
            // 只用它做表现，不生成后续子弹、也不走碰撞。
            LaserBulletType bolt = new LaserBulletType(currentDamage * 0.13f) {{
                length = len;
                collides = true;
                collidesTiles = true;
                pierce = true;
                pierceBuilding = true;
                hittable = true;
            }};
            // ★ 运行时 new 出来的子弹类型必须补 load()，否则贴图区域为 null → 绘制时 NPE
            bolt.load();
            bolt.create(b, b.team, cx, cy, ang);
            Fx.chainLightning.at(cx, cy, 0, lightningColor, next);

            // 跳跃伤害在这里结算；首个目标不参与（已被引擎打过）
            next.damage(currentDamage * damageScale(next));

            currentDamage *= decayFactor;
            visited.add(next);
            current = next;
        }
    }

    /** 建筑按倍率减伤，单位按 1 倍 */
    private float damageScale(Healthc target) {
        return target instanceof Building ? buildingDamageMultiplier : 1f;
    }

    /**
     * 搜索下一个连锁目标。
     * <p>
     * 优先级（由高到低）：
     * <ol>
     *   <li>单位 &gt; 建筑</li>
     *   <li>最大生命值大的 &gt; 小的（同一类内部比较）</li>
     *   <li>距离近的 &gt; 远的（前两项都相同时的兜底）</li>
     * </ol>
     * <p>
     * 单位和建筑都收进同一个候选池再统一比较，所以同一条链可以在两者之间来回跳。
     */
    private Healthc pickTarget(Team team, Position from, ObjectSet<Healthc> visited) {
        float x = from.getX(), y = from.getY();

        // 需要写进 lambda，用单元素数组当可变引用
        Healthc[] best = new Healthc[1];
        float[] bestDst = new float[]{0f};

        Units.nearbyEnemies(team, x, y, chainRange, u -> {
            if (isChainTarget(team, u, visited)) {
                pick(best, bestDst, u, x, y);
            }
        });

        if (chainBuildings) {
            indexer.allBuildings(x, y, chainRange, b -> {
                if (b != null && isChainTarget(team, b, visited)) {
                    pick(best, bestDst, b, x, y);
                }
            });
        }

        return best[0];
    }

    /** 按“单位优先 → 最大生命优先 → 距离近优先”决定是否替换当前最优目标 */
    private static void pick(Healthc[] best, float[] bestDst, Healthc candidate, float x, float y) {
        float dst = Mathf.dst(x, y, candidate.getX(), candidate.getY());

        if (best[0] == null || better(candidate, dst, best[0], bestDst[0])) {
            best[0] = candidate;
            bestDst[0] = dst;
        }
    }

    private static boolean better(Healthc a, float da, Healthc b, float db) {
        boolean ua = a instanceof Unit, ub = b instanceof Unit;

        // ① 单位整体优先于建筑（而不是比距离）
        if (ua != ub) return ua;

        // ② 同类之间比最大生命，大的优先
        float ha = a.health(), hb = b.health();
        if (ha != hb) return ha > hb;

        // ③ 兜底：更近的优先
        return da < db;
    }

    /** 是否为可连锁的敌方目标。visited 允许为 null（兜底起链时用） */
    private static boolean isChainTarget(Team team, Healthc h, ObjectSet<Healthc> visited) {
        // visited 为 null 时不能调 contains()，ObjectSet.contains 不接受 null
        if (visited != null && visited.contains(h)) return false;

        if (h instanceof Unit && ((Unit) h).inFogTo(team)) return false;
        if (h instanceof Building && !((Building) h).isDiscovered(team)) return false;

        return h.isValid() && enemyOf(team, h);
    }

    /** @return 是否为敌对方目标；建筑也会通过判定 */
    private static boolean enemyOf(Team team, Healthc h) {
        if (!h.isValid()) return false;
        if (h instanceof Building && ((Building) h).block instanceof ConstructBlock) return false;

        Team other = h instanceof Teamc ? ((Teamc) h).team() : null;
        return other != null && other != team && other != Team.derelict;
    }
}
