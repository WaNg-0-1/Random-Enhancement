package raen.Enemy;
import arc.Core;
import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType;
public class PVESettings {
    public static final String KEY_DIFFICULTY = "raen-pve-difficulty";
    public static final int OFF    = 0;
    public static final int EASY   = 1;
    public static final int HARD   = 2;
    public static final int DEFAULT = EASY;

    // ---- 效果等级：低级 / 中级 / 高级 ----
    public static final int TIER_LOW   = 0;
    public static final int TIER_MID   = 1;
    public static final int TIER_HIGH  = 2;
    public static final int TIER_COUNT = 3;

    /** 各难度抽取效果等级的概率，索引 = difficulty - EASY，列依次为 低 / 中 / 高。 */
    private static final float[][] TIER_CHANCE = {
            {0.66f, 0.33f, 0.01f},   // 简单：66% 低级 / 33% 中级 / 1% 高级
            {0.07f, 0.66f, 0.27f},   // 困难：7%  低级 / 66% 中级 / 27% 高级
    };

    /** 困难难度在「单位等级」基础上额外增加的效果数量，索引 = 单位等级 - 1。 */
    private static final int[] HARD_EXTRA = {0, 1, 1, 2, 3};

    private PVESettings() {}
    public static void init() {
        Events.on(EventType.ClientLoadEvent.class, event -> registerSlider());
    }
    public static int getDifficulty() {
        if (Core.settings == null) return DEFAULT;
        int value = Core.settings.getInt(KEY_DIFFICULTY, DEFAULT);
        // 旧版本的 2(普通) / 3(困难) 一并归入困难
        if (value < OFF)  return OFF;
        if (value > HARD) return HARD;
        return value;
    }
    public static boolean isEnabled() {
        return getDifficulty() != OFF;
    }
    public static float getDifficultyMultiplier() {
        switch (getDifficulty()) {
            case EASY: return 1f;   // 简单
            case HARD: return 2f;   // 困难
            default:   return 1f;   // 关闭（不会被使用）
        }
    }
    /** 效果数量上限：简单不额外增加（= 单位等级），困难按单位等级额外增加。 */
    public static int getEffectCap(int unitTier) {
        int tier = Math.max(1, Math.min(HARD_EXTRA.length, unitTier));
        if (getDifficulty() == HARD) return tier + HARD_EXTRA[tier - 1];
        return tier;
    }
    /** 按当前难度掷一次效果等级，返回 TIER_LOW / TIER_MID / TIER_HIGH。 */
    public static int rollTier() {
        int index = getDifficulty() - EASY;
        if (index < 0 || index >= TIER_CHANCE.length) index = DEFAULT - EASY;
        float[] chance = TIER_CHANCE[index];
        float roll = (float) Math.random();
        if (roll < chance[TIER_LOW]) return TIER_LOW;
        if (roll < chance[TIER_LOW] + chance[TIER_MID]) return TIER_MID;
        return TIER_HIGH;
    }
    public static String label(int value) {
        switch (value) {
            case OFF:  return "关闭";
            case EASY: return "简单 (×1.0)";
            case HARD: return "困难 (×2.0)";
            default:   return "未知";
        }
    }
    private static void registerSlider() {
        if (Vars.ui == null || Vars.ui.settings == null) return;
        try {
            Vars.ui.settings.addCategory("PVE 随机强化", table -> {
                table.sliderPref(
                        KEY_DIFFICULTY,
                        DEFAULT,
                        OFF,
                        HARD,
                        PVESettings::label
                );
            });
        } catch (Exception e) {
            arc.util.Log.err("raen-pve: 难度滑块注册失败", e);
        }
    }
}
