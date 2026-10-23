package raen.Enemy.StatusEffectCore;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.IntMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Healthc;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.type.StatusEffect;

/**
 * 连接类效果的公共基类：负责维持连接列表并绘制连线。
 */
public abstract class LinkEffect extends StatusEffect {

    /** 连接范围（世界单位）。 */
    public float range = 96f;
    /** 敌方连接上限。 */
    public int enemyCap = 1;
    /** 己方连接上限，0 表示不连接己方。 */
    public int allyCap = 0;
    /** 连线颜色。 */
    public Color linkColor = Color.valueOf("8fd0ff");

    protected final IntMap<Seq<Teamc>> enemyLinks = new IntMap<>();
    protected final IntMap<Seq<Unit>> allyLinks = new IntMap<>();

    /** 连接列表重建间隔（tick）。 */
    protected static final float REFRESH_INTERVAL = 10f;
    private final IntMap<Float> refreshTimers = new IntMap<>();

    protected LinkEffect(String name) {
        super(name);
    }

    protected Seq<Teamc> refreshEnemies(Unit unit) {
        Seq<Teamc> links = linksFor(enemyLinks, unit);
        if (enemyCap <= 0) return links;

        Seq<Teamc> found = new Seq<>();

        Units.nearbyEnemies(unit.team, unit.x, unit.y, range, other -> {
            if (other != unit && other.team != Team.derelict && alive(other)) found.add(other);
        });
        Units.nearbyBuildings(unit.x, unit.y, range, build -> {
            if (build != null && build.team() != unit.team && build.team() != Team.derelict && alive(build)) {
                found.add(build);
            }
        });

        found.sort((a, b) -> {
            int byClass = Integer.compare(linkPriority(a), linkPriority(b));
            if (byClass != 0) return byClass;
            int byHealth = Float.compare(healthOf(b), healthOf(a));
            if (byHealth != 0) return byHealth;
            return Float.compare(a.dst2(unit.x, unit.y), b.dst2(unit.x, unit.y));
        });

        links.clear();
        for (int i = 0; i < enemyCap && i < found.size; i++) {
            links.add(found.get(i));
        }
        return links;
    }

    protected Seq<Unit> refreshAllies(Unit unit) {
        Seq<Unit> links = linksFor(allyLinks, unit);
        if (allyCap <= 0) return links;

        Seq<Unit> found = new Seq<>();
        Units.nearby(unit.team, unit.x, unit.y, range, other -> {
            if (other != unit && alive(other)) found.add(other);
        });
        found.sort((a, b) -> Float.compare(a.dst2(unit.x, unit.y), b.dst2(unit.x, unit.y)));

        links.clear();
        for (int i = 0; i < allyCap && i < found.size; i++) {
            links.add(found.get(i));
        }
        return links;
    }

    protected static int linkPriority(Teamc target) {
        return target instanceof Unit ? 0 : 1;
    }

    protected static float healthOf(Teamc target) {
        return target instanceof Healthc h ? h.health() : 0f;
    }

    private static <T> Seq<T> linksFor(IntMap<Seq<T>> map, Unit unit) {
        Seq<T> links = map.get(unit.id);
        if (links == null) {
            links = new Seq<>();
            map.put(unit.id, links);
        }
        return links;
    }

    protected void clearLinks(Unit unit) {
        if (unit == null) return;
        enemyLinks.remove(unit.id);
        allyLinks.remove(unit.id);
        refreshTimers.remove(unit.id);
    }

    protected boolean shouldRefresh(Unit unit) {
        float timer = refreshTimers.get(unit.id, 0f) - Time.delta;
        if (timer <= 0f) {
            refreshTimers.put(unit.id, REFRESH_INTERVAL);
            return true;
        }
        refreshTimers.put(unit.id, timer);
        return false;
    }

    protected static boolean alive(Teamc target) {
        if (target instanceof Healthc h) return h.isValid() && h.health() > 0f;
        return false;
    }

    @Override
    public void draw(Unit unit) {
        drawLinks(unit, enemyLinks.get(unit.id), linkColor);
        drawLinks(unit, allyLinks.get(unit.id), linkColor);
    }

    protected void drawLinks(Unit unit, Seq<? extends Teamc> links, Color color) {
        if (unit == null || links == null || links.size == 0) return;
        if (!unit.isValid()) return;

        Draw.z(Layer.groundUnit - 1f);
        Draw.color(color);
        Lines.stroke(1.5f);

        float r1 = radiusOf(unit);
        Lines.circle(unit.x, unit.y, r1);

        for (int i = 0; i < links.size; i++) {
            Teamc target = links.get(i);
            if (!alive(target)) continue;

            float tx = target.getX(), ty = target.getY();
            float r2 = radiusOf(target);
            Lines.circle(tx, ty, r2);

            float dx = tx - unit.x, dy = ty - unit.y;
            float len = Mathf.dst(dx, dy);
            if (len <= r1 + r2) continue;
            float nx = dx / len, ny = dy / len;
            Lines.line(unit.x + nx * r1, unit.y + ny * r1, tx - nx * r2, ty - ny * r2);
        }

        Draw.color();
    }

    protected static float radiusOf(Teamc target) {
        if (target instanceof Unit u) return Math.max(u.hitSize / 2f, 1f);
        if (target instanceof Building b) return Math.max(b.hitSize() / 2f, 1f);
        return 4f;
    }
}
