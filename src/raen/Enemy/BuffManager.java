package raen.Enemy;

import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;
public class BuffManager {
    private static final float SCAN_INTERVAL = 5f * 59;
    private static final float BASE_DURATION   = 60f * 60f * 60f * 24f * 365f;
    private static StatusEffect[][] tierPools = null;  // 索引 = 效果等级（低 / 中 / 高）
    private static StatusEffect[]   allPool   = null;  // 全部效果，用于清理与判定
    private static StatusEffect[][] exclusiveGroups = null;  // 互斥组：同组效果不会共存
    private static int   lastDifficulty = -1;
    private static float scanTimer      = 0f;
    private BuffManager() {}
    public static void init() {
        tierPools = raenStatusEffects.getTierPools();
        allPool   = raenStatusEffects.getAll();
        exclusiveGroups = raenStatusEffects.getExclusiveGroups();
        lastDifficulty = PVESettings.getDifficulty();

        Events.on(EventType.UnitCreateEvent.class, event -> {
            if (!shouldRun() || event.unit == null) return;
            applyRandomBuffs(event.unit);
        });
        Events.on(EventType.UnitSpawnEvent.class, event -> {
            if (!shouldRun() || event.unit == null) return;
            applyRandomBuffs(event.unit);
        });
        Events.run(EventType.Trigger.update, BuffManager::update);
    }
    private static void update() {
        if (!shouldRun()) return;

        int current = PVESettings.getDifficulty();
        if (current != lastDifficulty) {
            lastDifficulty = current;
            resetAllBuffs();
        }
        scanTimer += Time.delta;
        if (scanTimer >= SCAN_INTERVAL) {
            scanTimer = 0f;
            scanAndBuff();
        }
    }
    private static void scanAndBuff() {
        if (!PVESettings.isEnabled()) return;
        if (Vars.state == null || Vars.state.rules == null) return;
        Team waveTeam = Vars.state.rules.waveTeam;
        if (waveTeam == null) return;

        Groups.unit.each(unit -> {
            if (unit == null) return;
            if (unit.team != waveTeam) return;
            if (unit.controller() instanceof mindustry.gen.Player) return;
            if (!unit.isValid()) return;
            if (!hasAnyBuff(unit)) applyRandomBuffs(unit);
        });
    }
    private static void resetAllBuffs() {
        if (Vars.state == null || Vars.state.rules == null) return;
        Team waveTeam = Vars.state.rules.waveTeam;
        if (waveTeam == null) return;

        Groups.unit.each(unit -> {
            if (unit == null) return;
            if (unit.team != waveTeam) return;
            if (unit.controller() instanceof mindustry.gen.Player) return;
            if (!unit.isValid()) return;
            if (allPool != null) {
                for (StatusEffect effect : allPool) {
                    if (effect == null) continue;
                    if (unit.hasEffect(effect)) unit.unapply(effect);
                }
            }
            applyRandomBuffs(unit);
        });
    }
    private static void applyRandomBuffs(Unit unit) {
        if (!PVESettings.isEnabled()) return;
        if (unit == null || tierPools == null || tierPools.length == 0) return;
        if (!isEnemy(unit)) return;
        if (hasAnyBuff(unit)) return;

        int count     = PVESettings.getEffectCap(getUnitTier(unit));
        float diffMul = PVESettings.getDifficultyMultiplier();
        if (count <= 0) return;

        Seq<StatusEffect> chosen = new Seq<>();
        for (int i = 0; i < count; i++) {
            // 每个效果独立掷一次等级
            StatusEffect effect = pickEffect(PVESettings.rollTier(), chosen);
            if (effect == null) break;  // 所有等级的效果都已抽完
            chosen.add(effect);
            unit.apply(effect, BASE_DURATION * diffMul);
        }
    }
    /** 从指定等级抽取一个尚未使用的效果；该等级耗尽时回退到其它等级。 */
    private static StatusEffect pickEffect(int tier, Seq<StatusEffect> chosen) {
        StatusEffect effect = pickFromTier(tier, chosen);
        if (effect != null) return effect;

        for (int t = 0; t < tierPools.length; t++) {
            if (t == tier) continue;
            effect = pickFromTier(t, chosen);
            if (effect != null) return effect;
        }
        return null;
    }
    private static StatusEffect pickFromTier(int tier, Seq<StatusEffect> chosen) {
        if (tier < 0 || tier >= tierPools.length) return null;
        StatusEffect[] pool = tierPools[tier];
        if (pool == null || pool.length == 0) return null;

        int available = 0;
        for (StatusEffect effect : pool) {
            if (isPickable(effect, chosen)) available++;
        }
        if (available == 0) return null;

        int pick = (int) (Math.random() * available);
        for (StatusEffect effect : pool) {
            if (!isPickable(effect, chosen)) continue;
            if (pick-- == 0) return effect;
        }
        return null;
    }
    /** 未被选中、且不与已选效果同属一个互斥组。 */
    private static boolean isPickable(StatusEffect effect, Seq<StatusEffect> chosen) {
        if (effect == null || chosen.contains(effect, true)) return false;
        return !conflicts(effect, chosen);
    }
    /** 候选效果是否与已选效果同属一个互斥组，避免同族叠加。 */
    private static boolean conflicts(StatusEffect candidate, Seq<StatusEffect> chosen) {
        if (exclusiveGroups == null) return false;
        for (StatusEffect[] group : exclusiveGroups) {
            if (!inGroup(group, candidate)) continue;
            for (StatusEffect picked : chosen) {
                if (inGroup(group, picked)) return true;
            }
        }
        return false;
    }
    private static boolean inGroup(StatusEffect[] group, StatusEffect effect) {
        if (group == null) return false;
        for (StatusEffect member : group) {
            if (member == effect) return true;
        }
        return false;
    }
    private static boolean shouldRun() {
        if (Vars.net != null && Vars.net.active()) {
            return Vars.net.server();
        }
        return true;
    }
    private static boolean isEnemy(Unit unit) {
        if (unit == null || Vars.state == null || Vars.state.rules == null) return false;
        Team waveTeam = Vars.state.rules.waveTeam;
        if (waveTeam == null) return false;
        if (unit.team != waveTeam) return false;
        if (unit.controller() instanceof mindustry.gen.Player) return false;
        if (raen.Enemy.StatusEffectCore.Wingman.isDrone(unit)) return false;
        return unit.isValid();
    }
    private static boolean hasAnyBuff(Unit unit) {
        if (unit == null || allPool == null) return false;
        for (StatusEffect effect : allPool) {
            if (effect == null) continue;
            if (unit.hasEffect(effect)) return true;
        }
        return false;
    }

    private static final ObjectMap<String, Integer> NAME_TIER = new ObjectMap<>();

    static {
        NAME_TIER.put("dagger",   1);  // 尖刀
        NAME_TIER.put("mace",     2);  // 战锤
        NAME_TIER.put("fortress", 3);  // 堡垒
        NAME_TIER.put("scepter",  4);  // 权杖
        NAME_TIER.put("reign",    5);  // 王座
        NAME_TIER.put("nova",     1);  // 新星
        NAME_TIER.put("pulsar",   2);  // 恒星
        NAME_TIER.put("quasar",   3);  // 耀星
        NAME_TIER.put("vela",     4);  // 灾星
        NAME_TIER.put("corvus",   5);  // 死星
        NAME_TIER.put("crawler",  1);  // 爬虫
        NAME_TIER.put("atrax",    2);  // 毒蛛
        NAME_TIER.put("spiroct",  3);  // 血蛭
        NAME_TIER.put("arkyid",   4);  // 毒蛊
        NAME_TIER.put("toxopid",  5);  // 天蝎
        NAME_TIER.put("flare",    1);  // 星辉
        NAME_TIER.put("horizon",  2);  // 天垠
        NAME_TIER.put("zenith",   3);  // 苍穹
        NAME_TIER.put("antumbra", 4);  // 月影
        NAME_TIER.put("eclipse",  5);  // 日蚀
        NAME_TIER.put("mono",     1);  // 独影
        NAME_TIER.put("poly",     2);  // 幻型
        NAME_TIER.put("mega",     3);  // 巨像
        NAME_TIER.put("quad",     4);  // 雷霆（空辅 T4）
        NAME_TIER.put("oct",      5);  // 要塞（空辅 T5）
        NAME_TIER.put("risso",    1);  // 梭鱼
        NAME_TIER.put("minke",    2);  // 飞鲨
        NAME_TIER.put("bryde",    3);  // 戟鲸
        NAME_TIER.put("sei",      4);  // 蛟龙
        NAME_TIER.put("omura",    5);  // 海神
        NAME_TIER.put("retusa",   1);  // 潜螺
        NAME_TIER.put("oxynoe",   2);  // 电鳗
        NAME_TIER.put("cyerce",   3);  // 江豚
        NAME_TIER.put("aegires",  4);  // 玄武
        NAME_TIER.put("navanax",  5);  // 龙王
        NAME_TIER.put("stell",    1);  // 围护
        NAME_TIER.put("locus",    2);  // 循迹
        NAME_TIER.put("precept",  3);  // 准绳
        NAME_TIER.put("vanquish", 4);  // 征服
        NAME_TIER.put("conquer",  5);  // 领主
        NAME_TIER.put("merui",    1);  // 天守
        NAME_TIER.put("cleroi",   2);  // 天赐
        NAME_TIER.put("anthicus", 3);  // 天灾
        NAME_TIER.put("tecta",    4);  // 天理
        NAME_TIER.put("collaris", 5);  // 天帝
        NAME_TIER.put("elude",    1);  // 挣脱
        NAME_TIER.put("avert",    2);  // 遮蔽
        NAME_TIER.put("obviate",  3);  // 消散
        NAME_TIER.put("quell",    4);  // 遏止
        NAME_TIER.put("disrupt",  5);  // 悲怆
    }
    private static int getUnitTier(Unit unit) {
        if (unit == null || unit.type == null) return 1;
        Integer byName = NAME_TIER.get(unit.type.name);
        if (byName != null) return byName;
        float hp = unit.maxHealth;
        if (hp < 500f)  return 1;
        if (hp < 1500f)  return 2;
        if (hp < 4500f) return 3;
        if (hp < 13500f) return 4;
        return 5;
    }
}