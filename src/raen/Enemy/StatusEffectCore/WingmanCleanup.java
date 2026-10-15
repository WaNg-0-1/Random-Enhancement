package raen.Enemy.StatusEffectCore;

import arc.Events;
import arc.util.Time;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
public class WingmanCleanup {
    public static boolean pendingCleanup = false;
    /** 兜底扫描间隔：60 秒。 */
    private static final float PRUNE_INTERVAL = 60f * 60f;
    private static float pruneTimer = 0f;
    public static void init() {
        Events.on(EventType.WorldLoadEvent.class, e -> {
            pendingCleanup = true;
        });
        Events.run(EventType.Trigger.update, WingmanCleanup::update);
    }
    private static void update() {
        pruneTimer += Time.delta;
        if (pruneTimer >= PRUNE_INTERVAL) {
            pruneTimer = 0f;
            WingmanUnit.pruneDrones();
        }
    }
    public static void executeCleanup() {
        Groups.unit.each(u -> {
            if (u == null || u.dead() || !u.isAdded()) return;
            if (u.controller() instanceof WingmanAI) {
                u.kill();
            } else if (u.type == WingmanUnit.wingmanA) {
                u.kill();
            }
        });
        for (int i = WingmanUnit.activeDrones.size - 1; i >= 0; i--) {
            Unit u = WingmanUnit.activeDrones.get(i);
            if (u != null && !u.dead() && u.isAdded()) u.kill();
        }
        WingmanUnit.activeDrones.clear();
        Wingman.hardResetAll();
        pendingCleanup = false;
    }
}