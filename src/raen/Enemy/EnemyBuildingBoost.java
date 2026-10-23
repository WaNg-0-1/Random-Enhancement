package raen.Enemy;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Rules.TeamRule;
import mindustry.game.Team;

/**
 * 敌方建筑血量 / 伤害倍率。
 */
public class EnemyBuildingBoost {

    private static final String TAG_HEALTH = "raen-base-block-health";
    private static final String TAG_DAMAGE = "raen-base-block-damage";

    /** 已套用过的难度，用于检测变化。 */
    private static int lastDifficulty = Integer.MIN_VALUE;

    private EnemyBuildingBoost() {}

    public static void init() {
        // 换图 / 读档后重新套用：rules 会被替换，基线也要按新地图重新取。
        Events.on(EventType.WorldLoadEvent.class, event -> {
            apply();
            lastDifficulty = PVESettings.getDifficulty();
        });
        Events.run(EventType.Trigger.update, EnemyBuildingBoost::update);
    }

    private static void update() {
        if (Vars.state == null || !Vars.state.isPlaying()) return;
        if (Vars.net != null && Vars.net.active() && !Vars.net.server()) return;

        int current = PVESettings.getDifficulty();
        if (current != lastDifficulty) {
            lastDifficulty = current;
            apply();
        }
    }

    /** 当前难度对应的倍率；关闭时为 1（即地图原始值）。 */
    public static float multiplier() {
        switch (PVESettings.getDifficulty()) {
            case PVESettings.EASY: return 1.25f;
            case PVESettings.HARD: return 1.75f;
            default:               return 1f;
        }
    }

    private static void apply() {
        if (Vars.state == null || Vars.state.rules == null || Vars.state.map == null) return;

        Team waveTeam = Vars.state.rules.waveTeam;
        if (waveTeam == null) return;

        TeamRule rule = Vars.state.rules.teams.get(waveTeam);
        if (rule == null) return;

        float baseHealth = baseline(TAG_HEALTH, rule.blockHealthMultiplier);
        float baseDamage = baseline(TAG_DAMAGE, rule.blockDamageMultiplier);

        float mul = multiplier();
        rule.blockHealthMultiplier = baseHealth * mul;
        rule.blockDamageMultiplier = baseDamage * mul;
    }

    /** 第一次见到这张地图时记下它的原始值，之后一直拿它当基线。 */
    private static float baseline(String tag, float current) {
        String key = tag + "-" + Vars.state.map.name();
        String stored = Vars.state.rules.tags.get(key);

        if (stored == null || stored.isEmpty()) {
            Vars.state.rules.tags.put(key, String.valueOf(current));
            return current;
        }
        try {
            return Float.parseFloat(stored);
        } catch (NumberFormatException e) {
            // 存档里是脏数据就重记一次，别让它把倍率算成 NaN
            Vars.state.rules.tags.put(key, String.valueOf(current));
            return current;
        }
    }
}
