package raen.Enemy.StatusEffectCore;

import arc.Events;
import arc.math.Mathf;
import arc.struct.IntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 团队血量 / 护盾共享。
 */
public class TeamHealthEffect extends StatusEffect {

    /** 当前在队里的成员。用 Seq 是为了只用本工程已验证过的 arc API。 */
    private static final Seq<Unit> team = new Seq<>();
    /** 按阵营分组的成员表，key = Team.id。 */
    private static final IntMap<Seq<Unit>> teamGroups = new IntMap<>();
    /** 成员 → 它当前挂着的档位实例，用来读取该档位的入队门槛。 */
    private static final ObjectMap<Unit, TeamHealthEffect> sources = new ObjectMap<>();

    /** 入队门槛 = gateRatio × maxHealth + gateFlat。 */
    public float gateRatio;
    public float gateFlat;

    public TeamHealthEffect(String name, float gateRatio, float gateFlat) {
        super(name);
        this.gateRatio = gateRatio;
        this.gateFlat = gateFlat;
    }

    /**
     * 由主类在 init() 里调用，挂上全局 tick。
     * 注意不能叫 init()：StatusEffect 继承自 Content，而 Content.init() 是实例方法，
     * Java 不允许用 static 方法隐藏它。
     */
    public static void registerTick() {
        Events.run(EventType.Trigger.update, TeamHealthEffect::sync);
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        register(unit, this);
    }

    @Override
    public void onRemoved(Unit unit) {
        super.onRemoved(unit);
        unregister(unit);
    }

    private static void register(Unit unit, TeamHealthEffect source) {
        if (unit == null) return;
        // 血量低于自己那一档的门槛就不进队；已经在队里的按退队处理。
        if (unit.health < thresholdOf(unit, source)) {
            unregister(unit);
            return;
        }
        if (sources.get(unit) == null) {
            team.add(unit);
        }
        sources.put(unit, source);
    }

    private static void unregister(Unit unit) {
        if (unit == null) return;
        for (int i = team.size - 1; i >= 0; i--) {
            if (team.get(i) == unit) team.remove(i);
        }
        sources.remove(unit);
    }

    private static Seq<Unit> groupOf(Team team) {
        int id = team == null ? Team.derelict.id : team.id;
        Seq<Unit> members = teamGroups.get(id);
        if (members == null) {
            members = new Seq<>();
            teamGroups.put(id, members);
        }
        return members;
    }

    private static float thresholdOf(Unit unit, TeamHealthEffect source) {
        if (source == null) return 1f;
        return source.gateRatio * unit.maxHealth + source.gateFlat;
    }

    /** 由主类注册到全局 tick，每帧调用一次。 */
    public static void sync() {
        if (Vars.state == null || !Vars.state.isPlaying()) return;

        // 1) 结算前先判定血量：阵亡 / 失效 / 低于自己那一档门槛的，直接退队。
        //    受到致命伤害的单位在 Unit.damage() 里就已经死了，这里不会再给它回血。
        for (int i = team.size - 1; i >= 0; i--) {
            Unit u = team.get(i);
            boolean drop = u == null || u.dead() || !u.isValid()
                    || u.health < thresholdOf(u, sources.get(u));
            if (drop) {
                team.remove(i);
                if (u != null) sources.remove(u);
            }
        }

        // 2) 每帧按单位当前阵营重新分组（单位有可能换队），各队独立结算。
        for (Seq<Unit> members : teamGroups.values()) {
            members.clear();
        }
        for (int i = 0; i < team.size; i++) {
            Unit u = team.get(i);
            groupOf(u.team).add(u);
        }
        for (Seq<Unit> members : teamGroups.values()) {
            syncGroup(members);
        }
    }

    /** 对同一阵营的成员拉平血量百分比、平分护盾。 */
    private static void syncGroup(Seq<Unit> members) {
        int n = members.size;
        if (n < 2) return;

        // 生命：全队拉平到同一血量百分比。
        float totalHealth = 0f;
        float totalMax    = 0f;
        for (int i = 0; i < n; i++) {
            Unit u = members.get(i);
            totalHealth += u.health;
            totalMax += u.maxHealth;
        }
        float percent = (totalMax > 0f) ? totalHealth / totalMax : 0f;

        // 护盾：全部加起来，分给所有在队成员。
        float shieldPool = 0f;
        for (int i = 0; i < n; i++) {
            shieldPool += members.get(i).shield;
        }
        float shieldShare = shieldPool / n;

        // 写回。
        for (int i = 0; i < n; i++) {
            Unit u = members.get(i);
            if (u.dead() || !u.isValid()) continue;
            u.health = Mathf.clamp(percent * u.maxHealth, 0f, u.maxHealth);
            u.shield = shieldShare;
        }
    }
}
