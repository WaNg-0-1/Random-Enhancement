package raen.Enemy;

import arc.graphics.Color;
import arc.struct.Seq;
import mindustry.content.Fx;
import mindustry.entities.bullet.ArtilleryBulletType;
import mindustry.entities.effect.MultiEffect;
import mindustry.graphics.Pal;
import mindustry.type.StatusEffect;
import mindustry.Vars;
import raen.Enemy.StatusEffectCore.*;

public class raenStatusEffects {

    // ---- 已有的自定义效果 ----
    public static StatusEffect BulletSpawnArtillery;
    public static StatusEffect DamageReductionA, DamageReductionB, DamageReductionC;
    public static StatusEffect GenerateWingmanA, GenerateWingmanB, GenerateWingmanC;
    public static StatusEffect TeamHealthA, TeamHealthB, TeamHealthC;
    public static StatusEffect ShieldToHealthA, ShieldToHealthB, ShieldToHealthC;
    public static StatusEffect HealthToShieldA, HealthToShieldB, HealthToShieldC;
    public static StatusEffect ReflectA, ReflectB, ReflectC;
    public static StatusEffect LinkBurstA, LinkBurstB, LinkBurstC;
    public static StatusEffect LinkTransferA, LinkTransferB, LinkTransferC;
    public static StatusEffect SplitA, SplitB, SplitC;
    public static StatusEffect ShareA, ShareB, ShareC;
    public static StatusEffect Ignite;        // 单级效果，放在低级池
    public static StatusEffect BulletOrbit;   // 单级效果，放在高级池

    // ---- 浴血系列：开火后激活，按已损失生命比例强化 ----
    public static StatusEffect BloodAtkA, BloodAtkB, BloodAtkC;     // 伤害 +0→200/300/400%
    public static StatusEffect BloodRateA, BloodRateB, BloodRateC;  // 攻速 +0→150/210/280%
    public static StatusEffect BloodMoveA, BloodMoveB, BloodMoveC;  // 移速 +0→160/230/320%

    // ---- 高风险高收益组合（同一族只取一档） ----
    public static StatusEffect GlassCannonA, GlassCannonB, GlassCannonC;  // 玻璃大炮
    public static StatusEffect NimbleA, NimbleB, NimbleC;                 // 轻装上阵
    public static StatusEffect HeavyArmorA, HeavyArmorB, HeavyArmorC;     // 重装上阵
    public static StatusEffect ScratcherA, ScratcherB, ScratcherC;        // 刮痧者

    // ---- 势大力沉：慢攻速换巨量伤害，与刮痧者互斥 ----
    public static StatusEffect MightyA, MightyB, MightyC;                  // 攻速 60/35/10%、伤害 200/375/1500%

    // ---- 幽影：定期随机传送到附近的空地并回血 ----
    public static StatusEffect ShadowA, ShadowB, ShadowC;                 // 15格/15秒、23格/12秒、30格/9秒

    // ---- 扭曲：周期性抢走范围内敌方子弹并折返 ----
    public static StatusEffect DistortA, DistortB, DistortC;              // 碰撞体积+1/2/3格，生效0.5/0.9/1.3秒，冷却15/12/9秒

    // ---- 回收：范围内有单位死亡时抢它的武器（不分敌我），限时使用 ----
    public static StatusEffect SalvageA, SalvageB, SalvageC;              // 10格/1槽/存5/3秒、15格/2槽/存7/4秒、20格/3槽/存9/5秒

    // ---- 标印：给范围内的己方子弹附加随机标签 ----
    public static StatusEffect MarkRandomA, MarkRandomB, MarkRandomC;     // 碰撞体积×1.5/2/2.5，1/2/3 标签，实时随机
    public static StatusEffect MarkFixedA, MarkFixedB, MarkFixedC;        // 碰撞体积×1.5/2/2.5，1/2/3 标签，获得时抽取并固定

    // ---- 进攻：持续攻击累积倍率，攻击 / 攻速两条路线 ----
    public static StatusEffect OffenseAtkA, OffenseAtkB, OffenseAtkC;     // 攻击 200%/400%/600%，移速血量 25%
    public static StatusEffect OffenseRateA, OffenseRateB, OffenseRateC;  // 攻速 200%/400%/600%，移速血量 25%

    // ---- 行者系：持续移动 30 秒蓄满，停步归零（9 个互相排斥）----
    public static StatusEffect WalkerSprintA, WalkerSprintB, WalkerSprintC;     // 疾步：移速 +0→200/400/600%，攻击/攻速 100%→50%
    public static StatusEffect WalkerKineticA, WalkerKineticB, WalkerKineticC; // 动能攻击：攻击/移速 +0→100/200/300%，血量 45%、攻速 66%
    public static StatusEffect WalkerPursuerA, WalkerPursuerB, WalkerPursuerC; // 追击者：攻速/移速 +0→100/200/300%，血量 45%、攻击 66%

    // ---- 吞噬：每 20/15/10 秒进入 1 秒吞噬窗口，吃弹回血回盾（冷却越长越低级）----
    public static StatusEffect DevourA, DevourB, DevourC;  // 低级 20 秒/25%，中级 15 秒/50%，高级 10 秒/75%

    // ---- 动能反击：每 1 秒快照血+盾，衰减时原地发射动能导弹（40 格射程 / 60 格追踪）----
    public static StatusEffect KineticCounterA, KineticCounterB, KineticCounterC; // 1~3 / 1~5 / 1~7 枚，倍率 1.5→3 / 4.5 / 6

    // ---- 残影：低/中每 2 秒、高级每 1.5 秒快照血量，有衰减就按差值生成一个分身，自然消散时回血 ----
    public static StatusEffect AfterimageA, AfterimageB, AfterimageC;     // 1/2/3 个分身，回血 50%/75%/100%

    // ---- 12 个纯数值效果 ----
    public static StatusEffect SpeedA,  SpeedB,  SpeedC;   // 速度 +12.5% / +25% / +50%
    public static StatusEffect HealthA, HealthB, HealthC;  // 生命 +12.5% / +25% / +50%
    public static StatusEffect DamageA, DamageB, DamageC;  // 伤害 +12.5% / +25% / +50%
    public static StatusEffect ReloadA, ReloadB, ReloadC;  // 攻速 +12.5% / +25% / +50%

    // ---- 各等级倍率常量 ----
    private static final float MUL_A = 1.125f;  // +12.5%
    private static final float MUL_B = 1.25f;   // +25%
    private static final float MUL_C = 1.50f;   // +50%

    // ---- 类别配色 ----
    private static final Color COLOR_SPEED  = Color.valueOf("8fd0ff");  // 浅蓝
    private static final Color COLOR_HEALTH = Color.valueOf("6ee06e");  // 翠绿
    private static final Color COLOR_DAMAGE = Color.valueOf("ff7070");  // 红
    private static final Color COLOR_RELOAD = Color.valueOf("ffc46b");  // 橙黄
    private static final Color COLOR_REDUCE_A = Color.valueOf("cbb2ff");  // 减伤 低级
    private static final Color COLOR_REDUCE_B = Color.valueOf("a884ff");  // 减伤 中级
    private static final Color COLOR_REDUCE_C = Color.valueOf("8155ff");  // 减伤 高级
    private static final Color COLOR_TEAM_A = Color.valueOf("a9f5d0");  // 共血 低级
    private static final Color COLOR_TEAM_B = Color.valueOf("6fe6b8");  // 共血 中级
    private static final Color COLOR_TEAM_C = Color.valueOf("35c98f");  // 共血 高级
    private static final Color COLOR_S2H_A = Color.valueOf("cfeeff");  // 盾转血 低级
    private static final Color COLOR_S2H_B = Color.valueOf("9adcff");  // 盾转血 中级
    private static final Color COLOR_S2H_C = Color.valueOf("5fc6ff");  // 盾转血 高级
    private static final Color COLOR_H2S_A = Color.valueOf("ffd0e8");  // 血转盾 低级
    private static final Color COLOR_H2S_B = Color.valueOf("ff9fd0");  // 血转盾 中级
    private static final Color COLOR_H2S_C = Color.valueOf("ff6cb8");  // 血转盾 高级
    private static final Color COLOR_REFLECT_A = Color.valueOf("c4fbff");  // 反伤 低级
    private static final Color COLOR_REFLECT_B = Color.valueOf("88f4ff");  // 反伤 中级
    private static final Color COLOR_REFLECT_C = Color.valueOf("4ce9ff");  // 反伤 高级
    private static final Color COLOR_LINKB_A = Color.valueOf("ffe066");  // 短时爆发 低级
    private static final Color COLOR_LINKB_B = Color.valueOf("ffc61a");  // 短时爆发 中级
    private static final Color COLOR_LINKB_C = Color.valueOf("ffa600");  // 短时爆发 高级
    private static final Color COLOR_LINKT_A = Color.valueOf("a6ffd9");  // 血量转移 低级
    private static final Color COLOR_LINKT_B = Color.valueOf("4dffb3");  // 血量转移 中级
    private static final Color COLOR_LINKT_C = Color.valueOf("00e68a");  // 血量转移 高级
    private static final Color COLOR_SPLIT_A = Color.valueOf("ffd27f");  // 分裂 低级
    private static final Color COLOR_SPLIT_B = Color.valueOf("ffab4d");  // 分裂 中级
    private static final Color COLOR_SPLIT_C = Color.valueOf("ff8426");  // 分裂 高级
    private static final Color COLOR_SHARE_A = Color.valueOf("ffb3e6");  // 共享回复 低级
    private static final Color COLOR_SHARE_B = Color.valueOf("ff66c4");  // 共享回复 中级
    private static final Color COLOR_SHARE_C = Color.valueOf("ff1a9e");  // 共享回复 高级
    private static final Color COLOR_IGNITE = Color.valueOf("ff7a3d");   // 点燃（单级）
    private static final Color COLOR_ORBIT  = Color.valueOf("9d7bff");   // 弹幕环绕（单级）
    private static final Color COLOR_BLOOD_ATK  = Color.valueOf("ff3355");  // 浴血·攻击
    private static final Color COLOR_BLOOD_RATE = Color.valueOf("ff8a33");  // 浴血·攻速
    private static final Color COLOR_BLOOD_MOVE = Color.valueOf("ff33aa");  // 浴血·移速
    private static final Color COLOR_GLASS   = Color.valueOf("ff4d6d");   // 玻璃大炮
    private static final Color COLOR_NIMBLE  = Color.valueOf("4dffb8");   // 轻装上阵
    private static final Color COLOR_HEAVY   = Color.valueOf("8a9bff");   // 重装上阵
    private static final Color COLOR_SCRATCH = Color.valueOf("c9b458");   // 刮痧者
    private static final Color COLOR_SHADOW  = Color.valueOf("6c5ce7");   // 幽影
    private static final Color COLOR_MIGHTY  = Color.valueOf("c1121f");   // 势大力沉
    private static final Color COLOR_DISTORT = Color.valueOf("c6ff4d");   // 扭曲
    private static final Color COLOR_SALVAGE = Color.valueOf("7dd3b0");   // 回收
    private static final Color COLOR_MARK_RANDOM = Color.valueOf("d4ff66");  // 标印·随机
    private static final Color COLOR_MARK_FIXED  = Color.valueOf("a8e600");  // 标印·固定
    private static final Color COLOR_OFFENSE_ATK  = Color.valueOf("e64a19"); // 进攻·攻击
    private static final Color COLOR_OFFENSE_RATE = Color.valueOf("ffb300"); // 进攻·攻速
    private static final Color COLOR_AFTERIMAGE_A = Color.valueOf("cdd6e6"); // 残影 低级（幽灵灰）
    private static final Color COLOR_AFTERIMAGE_B = Color.valueOf("a2adc2"); // 残影 中级
    private static final Color COLOR_AFTERIMAGE_C = Color.valueOf("78849c"); // 残影 高级
    private static final Color COLOR_WALK_SPRINT_A = Color.valueOf("8de8d0");  // 疾步 低级
    private static final Color COLOR_WALK_SPRINT_B = Color.valueOf("4dd6b8");  // 疾步 中级
    private static final Color COLOR_WALK_SPRINT_C = Color.valueOf("00b894");  // 疾步 高级
    private static final Color COLOR_WALK_KINETIC_A = Color.valueOf("ffb08a"); // 动能攻击 低级
    private static final Color COLOR_WALK_KINETIC_B = Color.valueOf("ff8a5c"); // 动能攻击 中级
    private static final Color COLOR_WALK_KINETIC_C = Color.valueOf("ff5a2b"); // 动能攻击 高级
    private static final Color COLOR_WALK_PURSUER_A = Color.valueOf("c9a6ff"); // 追击者 低级
    private static final Color COLOR_WALK_PURSUER_B = Color.valueOf("a06bff"); // 追击者 中级
    private static final Color COLOR_WALK_PURSUER_C = Color.valueOf("7a33ff"); // 追击者 高级
    private static final Color COLOR_DEVOUR_A = Color.valueOf("b8ff9c"); // 吞噬 低级
    private static final Color COLOR_DEVOUR_B = Color.valueOf("7ee65c"); // 吞噬 中级
    private static final Color COLOR_DEVOUR_C = Color.valueOf("4db32b"); // 吞噬 高级
    private static final Color COLOR_KC_A = Color.valueOf("ffe0a3"); // 动能反击 低级
    private static final Color COLOR_KC_B = Color.valueOf("ffb454"); // 动能反击 中级
    private static final Color COLOR_KC_C = Color.valueOf("ff7a18"); // 动能反击 高级

    public static void load() {
        // 残影的两个「存档载体」状态（不进效果池，只用来在存档里记住残影的归属与剩余寿命）
        AfterimageEffect.loadMarks();
        // ============ 减伤：低级 25% / 中级 50% / 高级 75% ============
        DamageReductionA = makeReduction("DamageReductionA", 0.25f, COLOR_REDUCE_A);
        addToContent(DamageReductionA);
        DamageReductionB = makeReduction("DamageReductionB", 0.50f, COLOR_REDUCE_B);
        addToContent(DamageReductionB);
        DamageReductionC = makeReduction("DamageReductionC", 0.75f, COLOR_REDUCE_C);
        addToContent(DamageReductionC);
        // ============ Wingman：低级 1 架 / 中级 2 架 / 高级 3 架 ============
        GenerateWingmanA = new Wingman("GenerateWingmanA", 1);
        addToContent(GenerateWingmanA);
        GenerateWingmanB = new Wingman("GenerateWingmanB", 2);
        addToContent(GenerateWingmanB);
        GenerateWingmanC = new Wingman("GenerateWingmanC", 3);
        addToContent(GenerateWingmanC);
        // ============ 团队共血：低级 血量>75% 才入队 / 中级 >50% / 高级 >1 点 ============
        TeamHealthA = makeTeamHealth("TeamHealthA", 0.75f, 0f, COLOR_TEAM_A);
        addToContent(TeamHealthA);
        TeamHealthB = makeTeamHealth("TeamHealthB", 0.50f, 0f, COLOR_TEAM_B);
        addToContent(TeamHealthB);
        TeamHealthC = makeTeamHealth("TeamHealthC", 0f, 1f, COLOR_TEAM_C);
        addToContent(TeamHealthC);
        // ============ 盾转血：低级 36盾→20血 / 中级 50盾→40血 / 高级 60盾→60血（每秒）============
        ShieldToHealthA = makeShieldToHealth("ShieldToHealthA", 36f, 20f, COLOR_S2H_A);
        addToContent(ShieldToHealthA);
        ShieldToHealthB = makeShieldToHealth("ShieldToHealthB", 50f, 40f, COLOR_S2H_B);
        addToContent(ShieldToHealthB);
        ShieldToHealthC = makeShieldToHealth("ShieldToHealthC", 60f, 60f, COLOR_S2H_C);
        addToContent(ShieldToHealthC);
        // ============ 血转盾：掉血量的 10% / 20% / 30% 变成护盾 ============
        HealthToShieldA = makeHealthToShield("HealthToShieldA", 0.10f, COLOR_H2S_A);
        addToContent(HealthToShieldA);
        HealthToShieldB = makeHealthToShield("HealthToShieldB", 0.20f, COLOR_H2S_B);
        addToContent(HealthToShieldB);
        HealthToShieldC = makeHealthToShield("HealthToShieldC", 0.30f, COLOR_H2S_C);
        addToContent(HealthToShieldC);
        // ============ 反伤：满血 10/20/30% → 濒死 45/60/75%，范围 18/24/30 格（半程内满伤，边缘减半）============
        ReflectA = makeReflect("ReflectA", 0.10f, 0.45f, 18f * Vars.tilesize, COLOR_REFLECT_A);
        addToContent(ReflectA);
        ReflectB = makeReflect("ReflectB", 0.20f, 0.60f, 24f * Vars.tilesize, COLOR_REFLECT_B);
        addToContent(ReflectB);
        ReflectC = makeReflect("ReflectC", 0.30f, 0.75f, 30f * Vars.tilesize, COLOR_REFLECT_C);
        addToContent(ReflectC);
        // ============ 短时爆发：18/14/10 秒触发，连 3/4/5 秒，0.75/1.75/3 倍伤害，13/17/21 格，1/2/3 目标 ============
        LinkBurstA = makeLinkBurst("LinkBurstA", 18f, 3f, 0.75f, 13f * Vars.tilesize, 1, COLOR_LINKB_A);
        addToContent(LinkBurstA);
        LinkBurstB = makeLinkBurst("LinkBurstB", 14f, 4f, 1.75f, 17f * Vars.tilesize, 2, COLOR_LINKB_B);
        addToContent(LinkBurstB);
        LinkBurstC = makeLinkBurst("LinkBurstC", 10f, 5f, 3f, 21f * Vars.tilesize, 3, COLOR_LINKB_C);
        addToContent(LinkBurstC);
        // ============ 血量转移：敌方损失 75/100/125% 治疗己方，敌 1/2/3 己 1/2/2，13/17/21 格 ============
        LinkTransferA = makeLinkTransfer("LinkTransferA", 0.75f, 13f * Vars.tilesize, 1, 1, COLOR_LINKT_A);
        addToContent(LinkTransferA);
        LinkTransferB = makeLinkTransfer("LinkTransferB", 1.00f, 17f * Vars.tilesize, 2, 2, COLOR_LINKT_B);
        addToContent(LinkTransferB);
        LinkTransferC = makeLinkTransfer("LinkTransferC", 1.25f, 21f * Vars.tilesize, 3, 2, COLOR_LINKT_C);
        addToContent(LinkTransferC);
        // ============ 分裂：低于 30/40/50% 血量时分裂成两个继承 15/25/35% 最大生命的自身 ============
        SplitA = makeSplit("SplitA", 0.30f, 0.15f, COLOR_SPLIT_A);
        addToContent(SplitA);
        SplitB = makeSplit("SplitB", 0.40f, 0.25f, COLOR_SPLIT_B);
        addToContent(SplitB);
        SplitC = makeSplit("SplitC", 0.50f, 0.35f, COLOR_SPLIT_C);
        addToContent(SplitC);
        // ============ 共享回复：10/15/20 格，最多记住 3/5/7 人，伤害 2/4/6%、治疗 5/8/12% ============
        ShareA = makeShare("ShareA", 10f * Vars.tilesize, 3, 0.02f, 0.05f, COLOR_SHARE_A);
        addToContent(ShareA);
        ShareB = makeShare("ShareB", 15f * Vars.tilesize, 5, 0.04f, 0.08f, COLOR_SHARE_B);
        addToContent(ShareB);
        ShareC = makeShare("ShareC", 20f * Vars.tilesize, 7, 0.06f, 0.12f, COLOR_SHARE_C);
        addToContent(ShareC);
        // ============ 点燃（单级·低级池）：每 5 秒点燃 3 倍碰撞体积内的所有敌方目标 ============
        Ignite = makeIgnite("Ignite", 3f, 5f, COLOR_IGNITE);
        addToContent(Ignite);
        // ============ 弹幕环绕（单级·高级池）：每 10 秒控制 2.5 秒，以 2 倍碰撞体积环绕 ============
        BulletOrbit = makeOrbit("BulletOrbit", 2.5f, 2f, COLOR_ORBIT);
        addToContent(BulletOrbit);
        // ============ 浴血系列：开火后激活，每秒扣当前生命 1%，按已损失生命比例强化 ============
        BloodAtkA = makeBlood("BloodAtkA", BloodbathEffect.Kind.DAMAGE, 2.0f, COLOR_BLOOD_ATK);
        addToContent(BloodAtkA);
        BloodAtkB = makeBlood("BloodAtkB", BloodbathEffect.Kind.DAMAGE, 3.0f, COLOR_BLOOD_ATK);
        addToContent(BloodAtkB);
        BloodAtkC = makeBlood("BloodAtkC", BloodbathEffect.Kind.DAMAGE, 4.0f, COLOR_BLOOD_ATK);
        addToContent(BloodAtkC);
        BloodRateA = makeBlood("BloodRateA", BloodbathEffect.Kind.RELOAD, 1.5f, COLOR_BLOOD_RATE);
        addToContent(BloodRateA);
        BloodRateB = makeBlood("BloodRateB", BloodbathEffect.Kind.RELOAD, 2.1f, COLOR_BLOOD_RATE);
        addToContent(BloodRateB);
        BloodRateC = makeBlood("BloodRateC", BloodbathEffect.Kind.RELOAD, 2.8f, COLOR_BLOOD_RATE);
        addToContent(BloodRateC);
        BloodMoveA = makeBlood("BloodMoveA", BloodbathEffect.Kind.SPEED, 1.6f, COLOR_BLOOD_MOVE);
        addToContent(BloodMoveA);
        BloodMoveB = makeBlood("BloodMoveB", BloodbathEffect.Kind.SPEED, 2.3f, COLOR_BLOOD_MOVE);
        addToContent(BloodMoveB);
        BloodMoveC = makeBlood("BloodMoveC", BloodbathEffect.Kind.SPEED, 3.2f, COLOR_BLOOD_MOVE);
        addToContent(BloodMoveC);
        // ============ 玻璃大炮：生命 50/30/10%，攻击 200/600/1000% ============
        GlassCannonA = makeBuff("GlassCannonA", 1f, 0.50f, 2.0f, 1f, COLOR_GLASS);
        addToContent(GlassCannonA);
        GlassCannonB = makeBuff("GlassCannonB", 1f, 0.30f, 6.0f, 1f, COLOR_GLASS);
        addToContent(GlassCannonB);
        GlassCannonC = makeBuff("GlassCannonC", 1f, 0.10f, 10.0f, 1f, COLOR_GLASS);
        addToContent(GlassCannonC);
        // ============ 轻装上阵：生命 60/40/20%，速度 200/450/750%，攻速 125/150/175% ============
        NimbleA = makeBuff("NimbleA", 2.0f, 0.60f, 1f, 1.25f, COLOR_NIMBLE);
        addToContent(NimbleA);
        NimbleB = makeBuff("NimbleB", 4.5f, 0.40f, 1f, 1.50f, COLOR_NIMBLE);
        addToContent(NimbleB);
        NimbleC = makeBuff("NimbleC", 7.5f, 0.20f, 1f, 1.75f, COLOR_NIMBLE);
        addToContent(NimbleC);
        // ============ 重装上阵：速度 60/35/5%，生命 240/360/480%，伤害 150/200/250% ============
        HeavyArmorA = makeBuff("HeavyArmorA", 0.60f, 2.40f, 1.50f, 1f, COLOR_HEAVY);
        addToContent(HeavyArmorA);
        HeavyArmorB = makeBuff("HeavyArmorB", 0.35f, 3.60f, 2.00f, 1f, COLOR_HEAVY);
        addToContent(HeavyArmorB);
        HeavyArmorC = makeBuff("HeavyArmorC", 0.15f, 4.80f, 2.50f, 1f, COLOR_HEAVY);
        addToContent(HeavyArmorC);
        // ============ 刮痧者：攻速 200/300/400%，伤害 52/36/29% ============
        ScratcherA = makeBuff("ScratcherA", 1f, 1f, 0.52f, 2.0f, COLOR_SCRATCH);
        addToContent(ScratcherA);
        ScratcherB = makeBuff("ScratcherB", 1f, 1f, 0.36f, 3.0f, COLOR_SCRATCH);
        addToContent(ScratcherB);
        ScratcherC = makeBuff("ScratcherC", 1f, 1f, 0.29f, 4.0f, COLOR_SCRATCH);
        addToContent(ScratcherC);
        // ============ 幽影：15格/15秒、23格/12秒、30格/9秒，每次回复 10 + 已损失生命 1% ============
        ShadowA = makeShadow("ShadowA", 15f, 15f, COLOR_SHADOW);
        addToContent(ShadowA);
        ShadowB = makeShadow("ShadowB", 23f, 12f, COLOR_SHADOW);
        addToContent(ShadowB);
        ShadowC = makeShadow("ShadowC", 30f, 9f, COLOR_SHADOW);
        addToContent(ShadowC);
        // ============ 势大力沉：攻速 60/35/10%，伤害 200/375/1500% ============
        MightyA = makeBuff("MightyA", 1f, 1f, 2.00f, 0.60f, COLOR_MIGHTY);
        addToContent(MightyA);
        MightyB = makeBuff("MightyB", 1f, 1f, 3.75f, 0.35f, COLOR_MIGHTY);
        addToContent(MightyB);
        MightyC = makeBuff("MightyC", 1f, 1f, 15.0f, 0.10f, COLOR_MIGHTY);
        addToContent(MightyC);
        // ============ 扭曲：半径=碰撞体积+1/2/3格，生效0.5/0.9/1.3秒，冷却15/12/9秒 ============
        DistortA = makeDistort("DistortA", 1f, 0.5f, 15f, 0.25f, 0.75f, 180f, COLOR_DISTORT);
        addToContent(DistortA);
        DistortB = makeDistort("DistortB", 2f, 0.9f, 12f, 0.50f, 0.5f, 45f, COLOR_DISTORT);
        addToContent(DistortB);
        DistortC = makeDistort("DistortC", 3f, 1.3f, 9f, 0.75f, 0.26f, 0f, COLOR_DISTORT);
        addToContent(DistortC);
        // ============ 回收：10/15/20 格，使用槽位 1/2/3 个，队列容量 5/7/9，时长 3/4/5 秒 ============
        SalvageA = makeSalvage("SalvageA", 10f, 1, 5, 3f, COLOR_SALVAGE);
        addToContent(SalvageA);
        SalvageB = makeSalvage("SalvageB", 15f, 2, 7, 4f, COLOR_SALVAGE);
        addToContent(SalvageB);
        SalvageC = makeSalvage("SalvageC", 20f, 3, 9, 5f, COLOR_SALVAGE);
        addToContent(SalvageC);
        // ============ 标印：半径 = 携带者碰撞体积 × 1.5/2/2.5，每颗子弹 1/2/3 个标签 ============
        MarkRandomA = makeMark("MarkRandomA", 1.5f, 1, false, COLOR_MARK_RANDOM);
        addToContent(MarkRandomA);
        MarkRandomB = makeMark("MarkRandomB", 2f, 2, false, COLOR_MARK_RANDOM);
        addToContent(MarkRandomB);
        MarkRandomC = makeMark("MarkRandomC", 2.5f, 3, false, COLOR_MARK_RANDOM);
        addToContent(MarkRandomC);
        MarkFixedA = makeMark("MarkFixedA", 1.5f, 1, true, COLOR_MARK_FIXED);
        addToContent(MarkFixedA);
        MarkFixedB = makeMark("MarkFixedB", 2f, 2, true, COLOR_MARK_FIXED);
        addToContent(MarkFixedB);
        MarkFixedC = makeMark("MarkFixedC", 2.5f, 3, true, COLOR_MARK_FIXED);
        addToContent(MarkFixedC);
        // ============ 进攻：连续攻击 60 秒蓄满，攻击 / 攻速各三档 ============
        OffenseAtkA = makeOffense("OffenseAtkA", raen.Enemy.StatusEffectCore.OffenseEffect.Kind.DAMAGE, 2f, COLOR_OFFENSE_ATK);
        addToContent(OffenseAtkA);
        OffenseAtkB = makeOffense("OffenseAtkB", raen.Enemy.StatusEffectCore.OffenseEffect.Kind.DAMAGE, 4f, COLOR_OFFENSE_ATK);
        addToContent(OffenseAtkB);
        OffenseAtkC = makeOffense("OffenseAtkC", raen.Enemy.StatusEffectCore.OffenseEffect.Kind.DAMAGE, 6f, COLOR_OFFENSE_ATK);
        addToContent(OffenseAtkC);
        OffenseRateA = makeOffense("OffenseRateA", raen.Enemy.StatusEffectCore.OffenseEffect.Kind.RELOAD, 2f, COLOR_OFFENSE_RATE);
        addToContent(OffenseRateA);
        OffenseRateB = makeOffense("OffenseRateB", raen.Enemy.StatusEffectCore.OffenseEffect.Kind.RELOAD, 4f, COLOR_OFFENSE_RATE);
        addToContent(OffenseRateB);
        OffenseRateC = makeOffense("OffenseRateC", raen.Enemy.StatusEffectCore.OffenseEffect.Kind.RELOAD, 6f, COLOR_OFFENSE_RATE);
        addToContent(OffenseRateC);
        // ============ 行者系：持续移动 30 秒蓄满，停步归零；9 个互相排斥 ============
        // 疾步：移速 +0→200/400/600%，攻击/攻速倍率 100%→50%
        WalkerSprintA = makeWalker("WalkerSprintA", WalkerEffect.Kind.SPRINT, 2f, 0.50f, 0.50f, 1f, COLOR_WALK_SPRINT_A);
        addToContent(WalkerSprintA);
        WalkerSprintB = makeWalker("WalkerSprintB", WalkerEffect.Kind.SPRINT, 4f, 0.50f, 0.50f, 1f, COLOR_WALK_SPRINT_B);
        addToContent(WalkerSprintB);
        WalkerSprintC = makeWalker("WalkerSprintC", WalkerEffect.Kind.SPRINT, 6f, 0.50f, 0.50f, 1f, COLOR_WALK_SPRINT_C);
        addToContent(WalkerSprintC);
        // 动能攻击：攻击/移速 +0→100/200/300%，血量倍率 100%→45%，攻速倍率 100%→66%
        WalkerKineticA = makeWalker("WalkerKineticA", WalkerEffect.Kind.KINETIC, 1f, 1f, 0.66f, 0.45f, COLOR_WALK_KINETIC_A);
        addToContent(WalkerKineticA);
        WalkerKineticB = makeWalker("WalkerKineticB", WalkerEffect.Kind.KINETIC, 2f, 1f, 0.66f, 0.45f, COLOR_WALK_KINETIC_B);
        addToContent(WalkerKineticB);
        WalkerKineticC = makeWalker("WalkerKineticC", WalkerEffect.Kind.KINETIC, 3f, 1f, 0.66f, 0.45f, COLOR_WALK_KINETIC_C);
        addToContent(WalkerKineticC);
        // 追击者：攻速/移速 +0→100/200/300%，血量倍率 100%→45%，攻击倍率 100%→66%
        WalkerPursuerA = makeWalker("WalkerPursuerA", WalkerEffect.Kind.PURSUER, 1f, 0.66f, 1f, 0.45f, COLOR_WALK_PURSUER_A);
        addToContent(WalkerPursuerA);
        WalkerPursuerB = makeWalker("WalkerPursuerB", WalkerEffect.Kind.PURSUER, 2f, 0.66f, 1f, 0.45f, COLOR_WALK_PURSUER_B);
        addToContent(WalkerPursuerB);
        WalkerPursuerC = makeWalker("WalkerPursuerC", WalkerEffect.Kind.PURSUER, 3f, 0.66f, 1f, 0.45f, COLOR_WALK_PURSUER_C);
        addToContent(WalkerPursuerC);
        // ============ 吞噬：20/15/10 秒周期（冷却越长越低级），1 秒窗口，1.5 秒缴械+静止 ============
        DevourA = makeDevour("DevourA", 20f, 0.25f, COLOR_DEVOUR_A);
        addToContent(DevourA);
        DevourB = makeDevour("DevourB", 15f, 0.50f, COLOR_DEVOUR_B);
        addToContent(DevourB);
        DevourC = makeDevour("DevourC", 10f, 0.75f, COLOR_DEVOUR_C);
        addToContent(DevourC);
        // ============ 动能反击：每 1 秒快照，衰减即发射 1~3/5/7 枚动能导弹 ============
        KineticCounterA = makeKineticCounter("KineticCounterA", 3, 3f, COLOR_KC_A);
        addToContent(KineticCounterA);
        KineticCounterB = makeKineticCounter("KineticCounterB", 5, 4.5f, COLOR_KC_B);
        addToContent(KineticCounterB);
        KineticCounterC = makeKineticCounter("KineticCounterC", 7, 6f, COLOR_KC_C);
        addToContent(KineticCounterC);
        // ============ 残影：低/中 2 秒、高级 1.5 秒快照，分身血量=衰减差值，存续 4 秒，1/2/3 个，回血 50%/75%/100% ============
        AfterimageA = makeAfterimage("AfterimageA", 1, 0.50f, AfterimageEffect.SNAPSHOT_INTERVAL, COLOR_AFTERIMAGE_A);
        addToContent(AfterimageA);
        AfterimageB = makeAfterimage("AfterimageB", 2, 0.75f, AfterimageEffect.SNAPSHOT_INTERVAL, COLOR_AFTERIMAGE_B);
        addToContent(AfterimageB);
        AfterimageC = makeAfterimage("AfterimageC", 3, 1.00f, 60f * 1.5f, COLOR_AFTERIMAGE_C);
        addToContent(AfterimageC);

        // ============ BulletSpawn（中级）============
        BulletSpawnArtillery = new BulletSpawn("BulletSpawnArtillery") {{
            bulletType = new ArtilleryBulletType(0, 40, "shell") {{
                hitEffect = new MultiEffect(Fx.plasticExplosion, Fx.shockwave);
                knockback = 1f;
                lifetime = 50f;
                width = 13f;
                height = 15f;
                collidesTiles = false;
                splashDamageRadius = 40f * 0.75f;
                splashDamage = 90f;
                fragBullet = new ArtilleryBulletType(1.6f, 40, "shell") {{
                    hitEffect = new MultiEffect(Fx.plasticExplosion, Fx.shockwave);
                    knockback = 1f;
                    lifetime = 80f;
                    width = 6f;
                    height = 7f;
                    collidesTiles = false;
                    splashDamageRadius = 40f * 0.75f;
                    splashDamage = 90f;
                    frontColor = backColor = Pal.surge;
                    lifeScaleRandMax = 1.08f;
                    lifeScaleRandMin = 0.95f;
                    lightning = 3;
                }};
                fragBullets = 5;
                backColor = frontColor = Pal.surge;
                lifeScaleRandMax = 1.08f;
                lifeScaleRandMin = 0.95f;
            }};
            color = Pal.surge;
            bulletCount = 1;
            spawnInterval = 299f;
            randomAngle = true;
        }};
        addToContent(BulletSpawnArtillery);
        // ---- 速度 ----
        SpeedA  = makeBuff("SpeedA",  MUL_A, 1f, 1f, 1f, COLOR_SPEED);
        SpeedB  = makeBuff("SpeedB",  MUL_B, 1f, 1f, 1f, COLOR_SPEED);
        SpeedC  = makeBuff("SpeedC",  MUL_C, 1f, 1f, 1f, COLOR_SPEED);
        // ---- 生命 ----
        HealthA = makeBuff("HealthA", 1f, MUL_A, 1f, 1f, COLOR_HEALTH);
        HealthB = makeBuff("HealthB", 1f, MUL_B, 1f, 1f, COLOR_HEALTH);
        HealthC = makeBuff("HealthC", 1f, MUL_C, 1f, 1f, COLOR_HEALTH);
        // ---- 伤害 ----
        DamageA = makeBuff("DamageA", 1f, 1f, MUL_A, 1f, COLOR_DAMAGE);
        DamageB = makeBuff("DamageB", 1f, 1f, MUL_B, 1f, COLOR_DAMAGE);
        DamageC = makeBuff("DamageC", 1f, 1f, MUL_C, 1f, COLOR_DAMAGE);
        // ---- 攻速 ----
        ReloadA = makeBuff("ReloadA", 1f, 1f, 1f, MUL_A, COLOR_RELOAD);
        ReloadB = makeBuff("ReloadB", 1f, 1f, 1f, MUL_B, COLOR_RELOAD);
        ReloadC = makeBuff("ReloadC", 1f, 1f, 1f, MUL_C, COLOR_RELOAD);

        addToContent(SpeedA);  addToContent(SpeedB);  addToContent(SpeedC);
        addToContent(HealthA); addToContent(HealthB); addToContent(HealthC);
        addToContent(DamageA); addToContent(DamageB); addToContent(DamageC);
        addToContent(ReloadA); addToContent(ReloadB); addToContent(ReloadC);
    }
    private static StatusEffect makeReduction(String name, float maxReduction, Color color) {
        DamageReductionEffect effect = new DamageReductionEffect(name);
        effect.maxReduction = maxReduction;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeTeamHealth(String name, float gateRatio, float gateFlat, Color color) {
        TeamHealthEffect effect = new TeamHealthEffect(name, gateRatio, gateFlat);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeShieldToHealth(String name, float shieldPerSecond, float healthPerSecond, Color color) {
        ShieldToHealthEffect effect = new ShieldToHealthEffect(name, shieldPerSecond, healthPerSecond);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeHealthToShield(String name, float convertRatio, Color color) {
        HealthToShieldEffect effect = new HealthToShieldEffect(name, convertRatio);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeReflect(String name, float reflectRatioMin, float reflectRatioMax, float reflectRange, Color color) {
        ReflectEffect effect = new ReflectEffect(name, reflectRatioMin, reflectRatioMax, reflectRange);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeLinkBurst(String name, float cycle, float active, float multiplier,
                                              float range, int enemyCap, Color color) {
        LinkBurstEffect effect = new LinkBurstEffect(name, cycle, active, multiplier);
        effect.range = range;
        effect.enemyCap = enemyCap;
        effect.linkColor = color;
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeLinkTransfer(String name, float ratio, float range,
                                                 int enemyCap, int allyCap, Color color) {
        LinkTransferEffect effect = new LinkTransferEffect(name, ratio);
        effect.range = range;
        effect.enemyCap = enemyCap;
        effect.allyCap = allyCap;
        effect.linkColor = color;
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeSplit(String name, float splitRatio, float childHealthRatio, Color color) {
        SplitEffect effect = new SplitEffect(name, splitRatio, childHealthRatio, color);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeShare(String name, float range, int maxMembers, float damageRatio, float healRatio, Color color) {
        SharedRecoveryEffect effect = new SharedRecoveryEffect(name, range, maxMembers, damageRatio, healRatio);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeIgnite(String name, float radiusMul, float cycleSeconds, Color color) {
        IgniteEffect effect = new IgniteEffect(name);
        effect.radiusMul = radiusMul;
        effect.cycleSeconds = cycleSeconds;
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeOrbit(String name, float captureMul, float orbitMul, Color color) {
        BulletOrbitEffect effect = new BulletOrbitEffect(name);
        effect.captureMul = captureMul;
        effect.orbitMul = orbitMul;
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeBlood(String name, BloodbathEffect.Kind kind, float maxBonus, Color color) {
        BloodbathEffect effect = new BloodbathEffect(name, kind, maxBonus);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeBuff(String name,
                                         float speedMul,
                                         float healthMul,
                                         float damageMul,
                                         float reloadMul,
                                         Color color) {
        StatusEffect effect = new StatusEffect(name);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.speedMultiplier  = speedMul;
        effect.healthMultiplier = healthMul;
        effect.damageMultiplier = damageMul;
        effect.reloadMultiplier = reloadMul;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeShadow(String name, float rangeTiles, float intervalSeconds, Color color) {
        ShadowEffect effect = new ShadowEffect(name, rangeTiles, intervalSeconds);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeDistort(String name, float rangeTiles, float activeSeconds, float cooldownSeconds,
                                            float damageScale, float timeScale, float spreadDegrees, Color color) {
        DistortEffect effect = new DistortEffect(name, rangeTiles, activeSeconds, cooldownSeconds);
        effect.damageScale = damageScale;
        effect.timeScale = timeScale;
        effect.spreadDegrees = spreadDegrees;
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeOffense(String name, raen.Enemy.StatusEffectCore.OffenseEffect.Kind kind,
                                           float maxMultiplier, Color color) {
        raen.Enemy.StatusEffectCore.OffenseEffect effect =
                new raen.Enemy.StatusEffectCore.OffenseEffect(name, kind, maxMultiplier);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }

    private static StatusEffect makeWalker(String name, WalkerEffect.Kind kind, float maxBonus,
                                           float minDamageMultiplier, float minReloadMultiplier,
                                           float minHealthMultiplier, Color color) {
        WalkerEffect effect = new WalkerEffect(name, kind, maxBonus,
                minDamageMultiplier, minReloadMultiplier, minHealthMultiplier);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }

    private static StatusEffect makeDevour(String name, float cycleSeconds, float healRatio, Color color) {
        DevourEffect effect = new DevourEffect(name, cycleSeconds, healRatio);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }

    private static StatusEffect makeKineticCounter(String name, int maxMissiles, float maxMultiplier, Color color) {
        KineticCounterEffect effect = new KineticCounterEffect(name, maxMissiles, maxMultiplier);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }

    private static StatusEffect makeAfterimage(String name, int maxClones, float refundRatio,
                                               float snapshotInterval, Color color) {
        AfterimageEffect effect = new AfterimageEffect(name, maxClones, refundRatio);
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.snapshotInterval = snapshotInterval;
        effect.color = color;
        return effect;
    }

    private static StatusEffect makeMark(String name, float rangeHitSizeMul, int tagCount, boolean fixed, Color color) {
        raen.Bullet.RaenMarkStatus effect = new raen.Bullet.RaenMarkStatus(name);
        effect.rangeHitSizeMul = rangeHitSizeMul;
        effect.tagCount = tagCount;
        effect.fixed = fixed;
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }
    private static StatusEffect makeSalvage(String name, float rangeTiles, int activeSlots, int maxQueue, float durationSeconds, Color color) {
        SalvageEffect effect = new SalvageEffect(name);
        effect.rangeTiles = rangeTiles;
        effect.activeSlots = activeSlots;
        effect.maxQueue = maxQueue;
        effect.durationSeconds = durationSeconds;
        effect.damage = 0f;
        effect.transitionDamage = 0f;
        effect.color = color;
        return effect;
    }

    private static void addToContent(StatusEffect effect) {
        if (effect == null) return;
        // 模组方针：所有效果永久生效，不会自然到期。
        // 放在最前面，避免重名提前 return 时漏设（重载模组会重建实例）。
        // 注意：改难度时 BuffManager.resetAllBuffs() 是显式 unapply，不受 permanent 影响。
        effect.permanent = true;
        for (StatusEffect existing : Vars.content.statusEffects()) {
            if (existing != null && existing.name.equals(effect.name)) {
                return;
            }
        }
        Vars.content.statusEffects().add(effect);
    }

    /**
     * 按效果等级返回三个效果池，索引依次为 PVESettings.TIER_LOW / TIER_MID / TIER_HIGH。
     * 低级：数值 +12.5% / 1 架僚机 / 减伤 25% / 共血（血量需 >75%）/ 盾转血 36→20 / 血转盾 10% / 反伤 10→45%（18 格）
     *       短时爆发 18秒连3秒×0.75（13 格，1 目标）/ 血量转移 75%（13 格，敌1己1）/ 分裂 低于30%→2×15% / 共享回复 受伤2%、治疗5%（10 格，3 人）/ 点燃 每5秒（3×碰撞体积）
     *       浴血 攻击 +0→200%、攻速 +0→150%、移速 +0→160%（开火后激活，每秒扣当前生命1%）
     *       玻璃大炮 生命50%/伤200% | 轻装 生命60%/速200%/攻速125% | 重装 速60%/生命240%/伤150% | 刮痧 攻速200%/伤52% / 势大力沉 攻速60%/伤200% / 幽影 15格15秒 / 扭曲 碰撞+1格·0.5秒·冷却15秒 / 回收 10格/1槽/存5/3秒 / 标印 碰撞×1.5·1标签
     *       残影 每2秒快照、1 个、存续4秒、消散回血50%（低出现率）
     *       行者 疾步 移速+0→200%（攻击/攻速降至50%）/ 动能 攻击·移速+0→100%（血量45%、攻速66%）/ 追击 攻速·移速+0→100%（血量45%、攻击66%）
     *       吞噬 每20秒吃身边敌方子弹，回血 = 子弹伤害和×25%（上限 360 + 25% 已损失生命，溢出转盾）
     *       动能反击 每秒快照血+盾，衰减则发 1~3 枚导弹（伤害=衰减量，倍率 1.5→3，40 格射程/60 格追踪）
     * 中级：数值 +25%   / 2 架僚机 / 炮击 / 减伤 50% / 共血（血量需 >50%）/ 盾转血 50→40 / 血转盾 20% / 反伤 20→60%（24 格）
     *       短时爆发 14秒连4秒×1.75（17 格，2 目标）/ 血量转移 100%（17 格，敌2己2）/ 分裂 低于40%→2×25% / 共享回复 受伤4%、治疗8%（15 格，5 人）
     *       浴血 攻击 +0→300%、攻速 +0→210%、移速 +0→230%（开火后激活，每秒扣当前生命1%）
     *       玻璃大炮 生命30%/伤600% | 轻装 生命40%/速450%/攻速150% | 重装 速35%/生命360%/伤200% | 刮痧 攻速300%/伤36% / 势大力沉 攻速35%/伤375% / 幽影 23格12秒 / 扭曲 碰撞+2格·0.9秒·冷却12秒 / 回收 15格/2槽/存7/4秒 / 标印 碰撞×2·2标签
     *       残影 每2秒快照、2 个、存续4秒、消散回血75%（低出现率）
     *       行者 疾步 +0→400% / 动能 +0→200% / 追击 +0→200%（代价同低级）
     *       吞噬 每15秒 / 回血 50%（同低级）
     *       动能反击 1~5 枚 / 倍率 1.5→4.5
     * 高级：数值 +50%   / 3 架僚机 / 减伤 75% / 共血（血量需 >1）  / 盾转血 60→60 / 血转盾 30% / 反伤 30→75%（30 格）
     *       短时爆发 10秒连5秒×3（21 格，3 目标）/ 血量转移 125%（21 格，敌3己2）/ 分裂 低于50%→2×35% / 共享回复 受伤6%、治疗12%（20 格，7 人）/ 弹幕环绕 每10秒控2.5秒（2.5×抓、2×绕）
     *       浴血 攻击 +0→400%、攻速 +0→280%、移速 +0→320%（开火后激活，每秒扣当前生命1%）
     *       玻璃大炮 生命10%/伤1000% | 轻装 生命20%/速750%/攻速175% | 重装 速15%/生命480%/伤250% | 刮痧 攻速400%/伤29% / 势大力沉 攻速10%/伤1500% / 幽影 30格9秒 / 扭曲 碰撞+3格·1.3秒·冷却9秒 / 回收 20格/3槽/存9/5秒 / 标印 碰撞×2.5·3标签
     *       残影 每1.5秒快照、3 个、存续4秒、消散回血100%（低出现率）
     *       行者 疾步 +0→600% / 动能 +0→300% / 追击 +0→300%（代价同低级）
     *       吞噬 每10秒 / 回血 75%
     *       动能反击 1~7 枚 / 倍率 1.5→6
     */
    public static StatusEffect[][] getTierPools() {
        StatusEffect[][] pools = new StatusEffect[PVESettings.TIER_COUNT][];
        pools[PVESettings.TIER_LOW] = buildPool(
                SpeedA, HealthA, DamageA, ReloadA,
                GenerateWingmanA, DamageReductionA, TeamHealthA,
                ShieldToHealthA, HealthToShieldA, ReflectA,
                LinkBurstA, LinkTransferA, SplitA, ShareA, Ignite,
                BloodAtkA, BloodRateA, BloodMoveA,
                GlassCannonA, NimbleA, HeavyArmorA, ScratcherA, MightyA, ShadowA, DistortA, SalvageA,
                MarkRandomA, MarkFixedA, AfterimageA,
                OffenseAtkA, OffenseRateA,
                WalkerSprintA, WalkerKineticA, WalkerPursuerA, DevourA, KineticCounterA);
        pools[PVESettings.TIER_MID] = buildPool(
                SpeedB, HealthB, DamageB, ReloadB,
                GenerateWingmanB, BulletSpawnArtillery, DamageReductionB, TeamHealthB,
                ShieldToHealthB, HealthToShieldB, ReflectB,
                LinkBurstB, LinkTransferB, SplitB, ShareB,
                BloodAtkB, BloodRateB, BloodMoveB,
                GlassCannonB, NimbleB, HeavyArmorB, ScratcherB, MightyB, ShadowB, DistortB, SalvageB,
                MarkRandomB, MarkFixedB, AfterimageB,
                OffenseAtkB, OffenseRateB,
                WalkerSprintB, WalkerKineticB, WalkerPursuerB, DevourB, KineticCounterB);
        pools[PVESettings.TIER_HIGH] = buildPool(
                SpeedC, HealthC, DamageC, ReloadC,
                GenerateWingmanC, DamageReductionC, TeamHealthC,
                ShieldToHealthC, HealthToShieldC, ReflectC,
                LinkBurstC, LinkTransferC, SplitC, ShareC, BulletOrbit,
                BloodAtkC, BloodRateC, BloodMoveC,
                GlassCannonC, NimbleC, HeavyArmorC, ScratcherC, MightyC, ShadowC, DistortC, SalvageC,
                MarkRandomC, MarkFixedC, AfterimageC,
                OffenseAtkC, OffenseRateC,
                WalkerSprintC, WalkerKineticC, WalkerPursuerC, DevourC, KineticCounterC);
        return pools;
    }

    /** 全部效果（跨等级去重），用于清理与「是否已有强化」判定。 */
    public static StatusEffect[] getAll() {
        Seq<StatusEffect> all = new Seq<>();
        for (StatusEffect[] pool : getTierPools()) {
            for (StatusEffect effect : pool) {
                if (effect != null && !all.contains(effect, true)) all.add(effect);
            }
        }
        StatusEffect[] result = new StatusEffect[all.size];
        for (int i = 0; i < all.size; i++) result[i] = all.get(i);
        return result;
    }

    /**
     * 互斥组：同一组内的效果不会同时施加到同一个单位上。
     * 僚机三档共用同一份静态 droneMap，若 A/B/C 共存会互相把对方的无人机砍掉/补足，导致每帧刷怪；
     * 共血三档共用同一份 team 名册，若共存会各自按自己的门槛把对方踢出/拉进队，结果互相打架；
     * 分裂三档同理，共存时只有门槛最高的那一档会先生效，另一档纯属浪费；
     * 共享回复三档共存会各自建一套记忆表、各自按自己的比例回一次，治疗量互相喂养（左脚踩右脚）。
     * 浴血 / 玻璃大炮 / 轻装上阵 / 重装上阵 / 刮痧者都是「同一族只能取一档」：
     * 高等级完全覆盖低等级，共存只是白占效果槽，还会让倍率叠得不可控。
     * 玻璃 / 轻装 / 重装 三族还额外互相排斥：它们的血量倍率会互相抵消
     * （例如玻璃大炮C 的 10% 血 × 重装上阵C 的 480% = 48%，惩罚被打掉、只剩收益），
     * 所以一个单位只能走其中一条路线。
     */
    public static StatusEffect[][] getExclusiveGroups() {
        return new StatusEffect[][] {
                {GenerateWingmanA, GenerateWingmanB, GenerateWingmanC},
                {SplitA, SplitB, SplitC},
                // 共享回复与共血会互相喂养（回血 → 共血摊平 → 再回血），整族互斥
                {TeamHealthA, TeamHealthB, TeamHealthC, ShareA, ShareB, ShareC},
                {BloodAtkA, BloodAtkB, BloodAtkC},
                {BloodRateA, BloodRateB, BloodRateC},
                {BloodMoveA, BloodMoveB, BloodMoveC},
                // 三族体型互斥：同族互斥 + 跨族互斥，等于「只能选一条路线」
                {GlassCannonA, GlassCannonB, GlassCannonC,
                 NimbleA, NimbleB, NimbleC,
                 HeavyArmorA, HeavyArmorB, HeavyArmorC},
                // 刮痧者与势大力沉是镜像关系（高速低伤 / 低速高伤），整族互斥
                {ScratcherA, ScratcherB, ScratcherC, MightyA, MightyB, MightyC},
                {ShadowA, ShadowB, ShadowC},
                {DistortA, DistortB, DistortC},
                {SalvageA, SalvageB, SalvageC},
                {MarkRandomA, MarkRandomB, MarkRandomC, MarkFixedA, MarkFixedB, MarkFixedC},
                {AfterimageA, AfterimageB, AfterimageC},
                {OffenseAtkA, OffenseAtkB, OffenseAtkC, OffenseRateA, OffenseRateB, OffenseRateC},
                // 行者系 9 个互相排斥：三族共用「持续移动蓄力」机制，共存会互相叠加倍率与代价
                {WalkerSprintA, WalkerSprintB, WalkerSprintC,
                 WalkerKineticA, WalkerKineticB, WalkerKineticC,
                 WalkerPursuerA, WalkerPursuerB, WalkerPursuerC},
                {DevourA, DevourB, DevourC},
                {KineticCounterA, KineticCounterB, KineticCounterC},
        };
    }

    private static StatusEffect[] buildPool(StatusEffect... raw) {
        int count = 0;
        for (StatusEffect s : raw) if (s != null) count++;
        StatusEffect[] pool = new StatusEffect[count];
        int i = 0;
        for (StatusEffect s : raw) if (s != null) pool[i++] = s;
        return pool;
    }
}
