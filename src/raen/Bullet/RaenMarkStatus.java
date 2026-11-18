package raen.Bullet;

import arc.math.Mathf;
import arc.struct.IntMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 标印：给范围内的己方子弹附加随机标签。
 */
public class RaenMarkStatus extends StatusEffect {

    /** 作用半径（世界单位，8 = 1 格）。 */
    public float range = 64f;
    /** 每颗子弹最多附加几个标签。 */
    public int tagCount = 1;
    /** 每个标签的层数。 */
    public int stacks = 1;
    /** true = 单位获得该效果时抽取并固定，之后只施加抽到的；false = 实时随机。 */
    public boolean fixed = false;
    /** 是否绘制范围圈。 */
    public boolean drawRange = true;

    /** 固定模式下，每个单位抽到的效果（单位 id → 效果）。 */
    private final IntMap<Seq<RaenEffectBase>> picked = new IntMap<>();

    public RaenMarkStatus(String name) {
        super(name);
        this.damage = 0f;
        this.transitionDamage = 0f;
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            if (unit != null) picked.remove(unit.id);
            return;
        }

        float range = this.range;
        if (range <= 0f) return;

        // 抽奖池是白名单（RaenEffectPool）：只有显式登记的效果才会被标印抽到，
        // 领域专用效果（如 RaenItemHit）不会混进来稀释概率。
        Seq<RaenEffectBase> pool = RaenEffectPool.all();
        if (pool == null || pool.size == 0) return;

        Seq<RaenEffectBase> locked = fixed ? pickedFor(unit, pool) : null;
        final String mark = "raen-mark-" + unit.id;
        final float r2 = range * range;

        Groups.bullet.intersect(unit.x - range, unit.y - range, range * 2f, range * 2f, bullet -> {
            if (bullet == null) return;
            if (bullet.team != unit.team) return;
            if (bullet.dst2(unit.x, unit.y) > r2) return;
            if (RaenBulletMarks.hasBulletMark(bullet, mark)) return;

            RaenBulletMarks.addBulletMark(bullet, mark);

            if (locked != null) {
                for (int i = 0; i < locked.size; i++) {
                    locked.get(i).attach(bullet, stacks);
                }
                return;
            }

            for (int i = 0; i < tagCount; i++) {
                pool.get(Mathf.random(pool.size - 1)).attach(bullet, stacks);
            }
        });
    }

    /** 固定模式：第一次用到时抽 tagCount 个，之后一直用这一批。 */
    private Seq<RaenEffectBase> pickedFor(Unit unit, Seq<RaenEffectBase> pool) {
        Seq<RaenEffectBase> list = picked.get(unit.id);
        if (list != null) return list;

        list = new Seq<>();
        Seq<RaenEffectBase> left = pool.copy();
        int want = Math.min(tagCount, left.size);

        while (list.size < want && left.size > 0) {
            int idx = Mathf.random(left.size - 1);
            list.add(left.get(idx));
            left.remove(idx);
        }

        picked.put(unit.id, list);
        return list;
    }

    @Override
    public void draw(Unit unit) {
        if (!drawRange || unit == null || unit.dead()) return;
        RaenEffectBase.drawAuraRange(unit.x, unit.y, range);
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        if (unit != null) picked.remove(unit.id);
    }
}
