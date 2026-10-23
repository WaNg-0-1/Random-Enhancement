package raen.Enemy.StatusEffectCore;

import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.IntMap;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.content.Liquids;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;

/**
 * 幽影：每隔一段时间随机传送到附近的空地，并回复少量生命。
 */
public class ShadowEffect extends StatusEffect {

    private static final float BASE_HEAL = 10f;
    private static final float LOST_RATIO = 0.01f;
    private static final float SHIELD_BLOCK = 0.5f;
    /** 随机撒点的尝试次数，找不到空地就放弃这一轮。 */
    private static final int ATTEMPTS = 40;

    /** 传送半径（格）。 */
    public float rangeTiles = 15f;
    /** 间隔（秒）。 */
    public float intervalSeconds = 15f;

    private final IntMap<Float> timers = new IntMap<>();

    public ShadowEffect(String name, float rangeTiles, float intervalSeconds) {
        super(name);
        this.rangeTiles = rangeTiles;
        this.intervalSeconds = intervalSeconds;
        this.damage = 0f;
        this.transitionDamage = 0f;
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            if (unit != null) timers.remove(unit.id);
            return;
        }

        float interval = intervalTicks();
        float timer = timers.get(unit.id, interval) + Time.delta;
        boolean trigger = timer >= interval;
        if (trigger) timer %= interval;
        timers.put(unit.id, timer);
        if (!trigger) return;

        teleport(unit);
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        if (unit != null) timers.remove(unit.id);
    }

    private float intervalTicks() {
        return Math.max(intervalSeconds * 60f, 1f);
    }

    private void teleport(Unit unit) {
        float range = rangeTiles * Vars.tilesize;

        if (range > 0f) {
            for (int i = 0; i < ATTEMPTS; i++) {
                float ang = Mathf.random(360f);
                float dist = Mathf.random(range);
                float tx = unit.x + Angles.trnsx(ang, dist);
                float ty = unit.y + Angles.trnsy(ang, dist);

                Tile tile = Vars.world.tileWorld(tx, ty);
                if (!canStand(unit, tile)) continue;

                Fx.shockwave.at(unit.x, unit.y);

                unit.set(tile.worldx(), tile.worldy());
                unit.vel.setZero();

                Fx.shockwave.at(unit.x, unit.y);
                if (tile.floor().isLiquid) {
                    Fx.bubble.at(unit.x, unit.y);
                    Fx.ripple.at(unit.x, unit.y);
                }

                healOnArrive(unit, 1f);
                return;
            }
        }

        healOnArrive(unit, 0.5f);
    }

    private static boolean canStand(Unit unit, Tile tile) {
        if (tile == null) return false;

        if (unit.type.flying) return !unitNear(tile, unit);

        if (tile.build != null || tile.solid()) return false;
        if (unitNear(tile, unit)) return false;

        Floor floor = tile.floor();

        if (unit.type.naval) {
            if (!floor.isLiquid) return false;
            return !isLethal(floor);
        }
        return !floor.isLiquid || floor.shallow;
    }

    private static boolean unitNear(Tile tile, Unit self) {
        final float cx = tile.worldx();
        final float cy = tile.worldy();
        final float r = Vars.tilesize * 0.5f;
        final float box = Vars.tilesize * 3f;
        final boolean[] found = {false};

        Groups.unit.intersect(cx - box, cy - box, box * 2f, box * 2f, other -> {
            if (found[0] || other == null || other == self) return;
            if (!other.isValid() || other.dead()) return;
            float rr = r + other.hitSize * 0.5f;
            if (other.dst2(cx, cy) <= rr * rr) found[0] = true;
        });
        return found[0];
    }

    private static boolean isLethal(Floor floor) {
        if (floor.damageTaken > 0f) return true;
        return floor.liquidDrop == Liquids.oil || floor.liquidDrop == Liquids.cryofluid;
    }

    private static void healOnArrive(Unit unit, float scale) {
        if (scale <= 0f) return;
        if (unit.shield > unit.maxHealth * SHIELD_BLOCK) return;

        float lost = Math.max(unit.maxHealth - unit.health, 0f);
        float amount = (BASE_HEAL + lost * LOST_RATIO) * scale;
        if (amount <= 0f) return;

        float missing = unit.maxHealth - unit.health;
        if (missing > 0f) {
            float toHealth = Math.min(missing, amount);
            unit.heal(toHealth);
            amount -= toHealth;
        }
        if (amount > 0f) {
            float room = Math.max(unit.maxHealth - unit.shield, 0f);
            unit.shield += Math.min(room, amount);
        }
    }
}
