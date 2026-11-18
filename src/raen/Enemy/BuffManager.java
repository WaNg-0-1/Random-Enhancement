package raen.Enemy;

import arc.Events;
import arc.struct.IntMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;
public class BuffManager {
    private static final float SCAN_INTERVAL = 2f * 59;
    private static final float BASE_DURATION   = 60f * 60f * 60f * 24f * 365f;
    /** 互斥自检间隔：10 秒扫一遍全场，发现同族并存就自愈。 */
    private static final float AUDIT_INTERVAL = 60f * 10f;
    private static StatusEffect[][] tierPools = null;  // 索引 = 效果等级（低 / 中 / 高）
    private static StatusEffect[]   allPool   = null;  // 全部效果，用于清理与判定
    private static StatusEffect[][] exclusiveGroups = null;  // 互斥组：同组效果不会共存
    /** 已经抽过强化的单位：显式记账，保证「一个单位只强化一次」。 */
    private static final ObjectSet<Unit> rolledUnits = new ObjectSet<>();
    private static int   lastDifficulty = -1;
    private static boolean lastAllTeams = false;
    private static float scanTimer      = 0f;
    private static float auditTimer     = 0f;
    private BuffManager() {}
    public static void init() {
        tierPools = raenStatusEffects.getTierPools();
        allPool   = raenStatusEffects.getAll();
        exclusiveGroups = raenStatusEffects.getExclusiveGroups();
        lastDifficulty = PVESettings.getDifficulty();
        lastAllTeams   = PVESettings.isAllTeams();

        Events.on(EventType.UnitCreateEvent.class, event -> {
            if (!shouldRun() || event.unit == null) return;
            applyRandomBuffs(event.unit);
        });
        Events.on(EventType.UnitSpawnEvent.class, event -> {
            if (!shouldRun() || event.unit == null) return;
            applyRandomBuffs(event.unit);
        });
        Events.run(EventType.Trigger.update, BuffManager::update);
        // 换图 / 读档：单位全部重建，「已强化」的记账要一起清掉。
        Events.on(EventType.WorldLoadEvent.class, event -> rolledUnits.clear());
    }
    private static void update() {
        if (!shouldRun()) return;

        int current = PVESettings.getDifficulty();
        boolean allTeams = PVESettings.isAllTeams();
        if (current != lastDifficulty || allTeams != lastAllTeams) {
            lastDifficulty = current;
            lastAllTeams   = allTeams;
            resetAllBuffs();
        }
        scanTimer += Time.delta;
        if (scanTimer >= SCAN_INTERVAL) {
            scanTimer = 0f;
            scanAndBuff();
        }
        auditTimer += Time.delta;
        if (auditTimer >= AUDIT_INTERVAL) {
            auditTimer = 0f;
            auditExclusive();
        }
    }
    private static void scanAndBuff() {
        if (!PVESettings.isEnabled()) return;

        Groups.unit.each(unit -> {
            if (!isTarget(unit)) return;
            if (!hasAnyBuff(unit)) applyRandomBuffs(unit);
        });
    }
    private static void resetAllBuffs() {
        // 换难度要重新掷一遍：把「已强化」的记账清掉，单位才会被重新抽取。
        rolledUnits.clear();
        Groups.unit.each(unit -> {
            if (unit == null || !unit.isValid()) return;
            // 先无条件清掉所有强化：关闭「作用于所有单位」时，
            // 之前挂在友方单位身上的效果也必须一起消失。
            if (allPool != null) {
                for (StatusEffect effect : allPool) {
                    if (effect == null) continue;
                    if (unit.getDuration(effect) > 0f) unit.unapply(effect);
                }
            }
            if (isTarget(unit)) applyRandomBuffs(unit);
        });
    }
    private static void applyRandomBuffs(Unit unit) {
        if (!PVESettings.isEnabled()) return;
        if (unit == null || tierPools == null || tierPools.length == 0) return;
        if (!isTarget(unit)) return;
        // 「一个单位只强化一次」是互斥成立的前提：一旦同一单位被强化两次，
        // 两次各自独立抽取，就可能出现「高级僚机 + 低级僚机」同族并存。
        // 这里用显式记账兜底（不依赖引擎的 hasEffect 内部状态），双保险。
        if (rolledUnits.contains(unit)) return;
        if (hasAnyBuff(unit)) return;

        int count     = PVESettings.getEffectCap(getUnitTier(unit));
        float diffMul = PVESettings.getDifficultyMultiplier();
        if (count <= 0) return;

        Seq<StatusEffect> chosen = new Seq<>();
        // ★ 互斥判定必须以「单位当前身上的效果」为准，而不只是本轮抽到的那几个：
        //   chosen 里除了本轮已抽中的，再塞进单位身上已经挂着的强化，
        //   这样只要单位身上已经有某一档（例：高级僚机），
        //   后面任何一次施加强化都不会再补一个同族的低级档（反之亦然）。
        //   否则同一单位被多次施加强化时（例如换难度重掷、扫描补挂），
        //   会出现「高级僚机 + 低级僚机」同族并存 —— 两档共用同一份无人机表，
        //   会互相补足 / 砍掉僚机。
        collectExisting(unit, chosen);
        // 分裂产生的子单位不再吃分裂效果：它们的血量本来就低于门槛，
        // 一旦拿到就会在下一帧立刻再分裂，一代代增殖。
        // 预先塞进 chosen，三个档位就全部不可选了（chosen 同时也参与降级回退的排除）。
        if (raen.Enemy.StatusEffectCore.SplitEffect.isSplitChild(unit)) {
            chosen.add(raenStatusEffects.SplitA);
            chosen.add(raenStatusEffects.SplitB);
            chosen.add(raenStatusEffects.SplitC);
        }
        boolean applied = false;
        for (int i = 0; i < count; i++) {
            // 每个效果独立掷一次等级
            StatusEffect effect = pickEffect(PVESettings.rollTier(), chosen);
            if (effect == null) break;  // 所有等级的效果都已抽完
            chosen.add(effect);
            unit.apply(effect, BASE_DURATION * diffMul);
            applied = true;
        }
        if (applied) rolledUnits.add(unit);
        // 分裂子单位的僚机档位跟母体保持一致（子单位和母体长得一模一样，
        // 不能一个 3 架一个 1 架，看着就像同一个单位同时带了两档）。
        applyInheritedWingman(unit);
    }
    /** 分裂子单位 → 母体的僚机档位（1/2/3），没有则 0。 */
    private static final IntMap<Integer> inheritedWingman = new IntMap<>();
    /** 记录分裂子单位应当继承的僚机档位。 */
    public static void setInheritedWingman(Unit child, int tier) {
        if (child == null || tier <= 0) return;
        inheritedWingman.put(child.id, tier);
    }
    /** 把子单位抽到的僚机档位换成母体那一档。 */
    private static void applyInheritedWingman(Unit unit) {
        Integer tier = inheritedWingman.get(unit.id);
        if (tier == null) return;
        inheritedWingman.remove(unit.id);

        StatusEffect keep = tier == 3 ? raenStatusEffects.GenerateWingmanC
            : tier == 2 ? raenStatusEffects.GenerateWingmanB
            : raenStatusEffects.GenerateWingmanA;
        // 先把本次抽到的僚机摘掉，避免两档并存（unapply 会顺带清掉该单位的僚机表）。
        for (StatusEffect effect : new StatusEffect[]{raenStatusEffects.GenerateWingmanA,
                raenStatusEffects.GenerateWingmanB, raenStatusEffects.GenerateWingmanC}){
            if (effect == keep) continue;
            if (unit.getDuration(effect) > 0f) unit.unapply(effect);
        }
        if (unit.getDuration(keep) <= 0f) {
            unit.apply(keep, BASE_DURATION * PVESettings.getDifficultyMultiplier());
        }
    }
    /** 把单位身上已经挂着的强化收进 chosen：既算「已占用」，也参与互斥判定。 */
    private static void collectExisting(Unit unit, Seq<StatusEffect> out) {
        if (allPool == null) return;
        for (StatusEffect effect : allPool) {
            if (effect == null) continue;
            // 同上：用 getDuration 读真实状态（直接查 statuses 列表，两条实体路径行为一致）。
            if (unit.getDuration(effect) > 0f) out.add(effect);
        }
    }
    /**
     * 互斥自检：同一个单位同时挂着同族（互斥组）的两个档位属于异常状态。
     * 这里统一「保留最高档、摘掉其余」，并打一条日志方便定位是谁带进来的。
     * <p>
     * 正常流程下（抽取时已经把单位身上的效果算进 chosen）不会触发，
     * 这里是兜底：万一有别的路径绕过了抽取逻辑，也不会让两档长期打架。
     */
    private static void auditExclusive() {
        if (exclusiveGroups == null) return;
        // 顺手清掉已经死掉的单位，避免记账集合无限增长。
        Seq<Unit> dead = new Seq<>();
        for(Unit u : rolledUnits){
            if (u == null || u.dead() || !u.isAdded()) dead.add(u);
        }
        for(Unit u : dead) rolledUnits.remove(u);

        Groups.unit.each(unit -> {
            if (unit == null || !unit.isValid()) return;
            for (StatusEffect[] group : exclusiveGroups) {
                if (group == null || group.length < 2) continue;
                StatusEffect keep = null;
                int hits = 0;
                for (StatusEffect member : group) {
                    // 用 getDuration 判定「当前真的挂着」（直接查 statuses 列表）。
                    if (member == null || unit.getDuration(member) <= 0f) continue;
                    hits++;
                    if (keep == null || tierOf(member) > tierOf(keep)) keep = member;
                }
                if (hits <= 1) continue;

                for (StatusEffect member : group) {
                    if (member == null || member == keep) continue;
                    if (unit.getDuration(member) <= 0f) continue;
                    unit.unapply(member);
                }
                Log.warn("[raen] 互斥组冲突：同一单位同时带有同族多个档位，已保留最高档 " + keep.name
                    + "（单位 id " + unit.id + "）");
            }
        });
    }
    /** 效果档位：名字结尾 A = 低级 / B = 中级 / C = 高级，其余（单级效果）记 0。 */
    private static int tierOf(StatusEffect effect) {
        if (effect == null || effect.name == null || effect.name.isEmpty()) return 0;
        switch(effect.name.charAt(effect.name.length() - 1)){
            case 'A': return 1;
            case 'B': return 2;
            case 'C': return 3;
            default: return 0;
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
    /** 标印系列的出现概率（其余效果为 1，即正常概率）。 */
    private static final float MARK_CHANCE = 0.15f;
    /** 复用：本轮筛选后的候选。 */
    private static final Seq<StatusEffect> candidates = new Seq<>();

    private static StatusEffect pickFromTier(int tier, Seq<StatusEffect> chosen) {
        if (tier < 0 || tier >= tierPools.length) return null;
        StatusEffect[] pool = tierPools[tier];
        if (pool == null || pool.length == 0) return null;

        // 先筛出可选项；标印系列额外过一道概率，降低出现率
        candidates.clear();
        for (StatusEffect effect : pool) {
            if (!isPickable(effect, chosen)) continue;
            if (effect instanceof raen.Bullet.RaenMarkStatus && Math.random() >= MARK_CHANCE) continue;
            candidates.add(effect);
        }
        if (candidates.size == 0) return null;

        return candidates.get((int) (Math.random() * candidates.size));
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
    /**
     * 该单位是否应当获得随机强化。
     * 默认只认敌方波次单位；开启「作用于所有单位」后不再按队伍筛选。
     */
    private static boolean isTarget(Unit unit) {
        if (unit == null || Vars.state == null || Vars.state.rules == null) return false;
        if (!unit.isValid()) return false;
        if (raen.Enemy.StatusEffectCore.Wingman.isDrone(unit)) return false;
        if (PVESettings.isAllTeams()) return true;
        Team waveTeam = Vars.state.rules.waveTeam;
        if (waveTeam == null) return false;
        if (unit.team != waveTeam) return false;
        if (unit.controller() instanceof mindustry.gen.Player) return false;
        return true;
    }
    private static boolean hasAnyBuff(Unit unit) {
        if (unit == null || allPool == null) return false;
        // 用 getDuration(effect) > 0 判「当前是否挂着」：它直接读 statuses 列表，
        // 不依赖 applied 位图，也不受两条实体路径（gen UnitEntity / 组件 StatusComp）差异影响。
        for (StatusEffect effect : allPool) {
            if (effect == null) continue;
            if (unit.getDuration(effect) > 0f) return true;
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