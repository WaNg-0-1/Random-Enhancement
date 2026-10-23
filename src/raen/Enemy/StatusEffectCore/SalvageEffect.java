package raen.Enemy.StatusEffectCore;

import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.entities.units.WeaponMount;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;
import mindustry.type.Weapon;
import mindustry.type.weapons.RepairBeamWeapon;

/**
 * 回收：范围内有单位死亡时，把它的武器收入队列，按顺序限时使用。
 */
public class SalvageEffect extends StatusEffect {

    /** 生效半径（格）。 */
    public float rangeTiles = 10f;
    /** 同时「使用中」的名额。 */
    public int activeSlots = 1;
    /** 队列里最多存几个单位的数据（含使用中的）。 */
    public int maxQueue = 5;
    /** 每个单位武器的使用时长（秒）。 */
    public float durationSeconds = 3f;

    private static final ObjectMap<Unit, SalvageEffect> sources = new ObjectMap<>();
    private static final ObjectMap<Unit, State> states = new ObjectMap<>();

    public SalvageEffect(String name) {
        super(name);
        this.damage = 0f;
        this.transitionDamage = 0f;
    }

    public static void registerEvents() {
        Events.on(EventType.UnitDestroyEvent.class, event -> {
            Unit dead = event.unit;
            if (dead == null) return;

            sources.remove(dead);
            states.remove(dead);

            if (dead.type == null || dead.type.weapons.size == 0) return;

            float maxRange = 20f * Vars.tilesize;

            Groups.unit.intersect(dead.x - maxRange, dead.y - maxRange, maxRange * 2f, maxRange * 2f, other -> {
                if (other == null || other == dead || other.dead() || !other.isValid()) return;

                SalvageEffect source = sources.get(other);
                if (source == null) return;

                float r = source.rangeTiles * Vars.tilesize;
                if (dead.dst2(other) > r * r) return;

                grant(other, dead, source);
            });
        });
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) {
            if (unit != null) {
                sources.remove(unit);
                states.remove(unit);
            }
            return;
        }

        sources.put(unit, this);

        State st = states.get(unit);
        if (st == null || st.queue.isEmpty()) return;

        int slots = Math.max(activeSlots, 1);
        float duration = Math.max(durationSeconds, 0.1f) * 60f;
        boolean changed = false;

        for (int i = st.queue.size - 1; i >= 0; i--) {
            Entry e = st.queue.get(i);
            if (e.expireAt > 0f && Time.time >= e.expireAt) {
                st.queue.remove(i);
                changed = true;
            }
        }

        int active = 0;
        for (int i = 0; i < st.queue.size; i++) {
            if (st.queue.get(i).expireAt > 0f) active++;
        }
        for (int i = 0; i < st.queue.size && active < slots; i++) {
            Entry e = st.queue.get(i);
            if (e.expireAt <= 0f) {
                e.expireAt = Time.time + duration;
                active++;
                changed = true;
            }
        }

        if (changed) rebuild(unit, st);

        if (st.queue.isEmpty() && unit.mounts.length != st.base.length) {
            unit.mounts = st.base;
        }
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        if (unit == null) return;

        sources.remove(unit);
        State st = states.remove(unit);
        if (st != null && unit.isValid() && !unit.dead()) {
            unit.mounts = st.base;
        }
    }

    private static void grant(Unit carrier, Unit dead, SalvageEffect source) {
        Seq<Weapon> usable = new Seq<>();
        for (int i = 0; i < dead.type.weapons.size; i++) {
            Weapon weapon = dead.type.weapons.get(i);
            if (!isUsable(weapon)) continue;

            Weapon copy = weapon.copy();
            copy.mirror = false;
            copy.alternate = false;
            copy.otherSide = -1;
            usable.add(copy);
        }
        if (usable.isEmpty()) return;

        State st = states.get(carrier);
        if (st == null) {
            st = new State();
            st.base = carrier.mounts;
            states.put(carrier, st);
        }

        Entry e = new Entry();
        e.mounts = new WeaponMount[usable.size];
        for (int i = 0; i < usable.size; i++) {
            Weapon w = usable.get(i);
            e.mounts[i] = w.mountType.get(w);
        }
        e.expireAt = 0f;
        st.queue.add(e);

        int capacity = Math.max(source.maxQueue, Math.max(source.activeSlots, 1));
        while (st.queue.size > capacity && st.queue.size > 0) {
            st.queue.remove(0);
        }
    }

    private static boolean isUsable(Weapon weapon) {
        if (weapon == null || weapon.bullet == null) return false;
        if (weapon instanceof RepairBeamWeapon) return false;
        return !weapon.bullet.killShooter;
    }

    private static void rebuild(Unit carrier, State st) {
        int extra = 0;
        for (int i = 0; i < st.queue.size; i++) {
            Entry e = st.queue.get(i);
            if (e.expireAt > 0f) extra += e.mounts.length;
        }

        WeaponMount[] merged = new WeaponMount[st.base.length + extra];
        System.arraycopy(st.base, 0, merged, 0, st.base.length);

        int idx = st.base.length;
        for (int i = 0; i < st.queue.size; i++) {
            Entry e = st.queue.get(i);
            if (e.expireAt <= 0f) continue;
            System.arraycopy(e.mounts, 0, merged, idx, e.mounts.length);
            idx += e.mounts.length;
        }

        carrier.mounts = merged;
    }

    private static class Entry {
        WeaponMount[] mounts;
        float expireAt;
    }

    private static class State {
        WeaponMount[] base;
        final Seq<Entry> queue = new Seq<>();
    }
}
