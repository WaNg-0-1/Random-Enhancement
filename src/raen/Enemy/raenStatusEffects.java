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

    public static void load() {
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
                    lifetime = 50f;
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
                fragBullets = 15;
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
     * 低级：数值 +12.5% / 1 架僚机 / 减伤 25% / 共血（血量需 >75%）/ 盾转血 36→20 / 血转盾 10%
     * 中级：数值 +25%   / 2 架僚机 / 炮击 / 减伤 50% / 共血（血量需 >50%）/ 盾转血 50→40 / 血转盾 20%
     * 高级：数值 +50%   / 3 架僚机 / 减伤 75% / 共血（血量需 >1）  / 盾转血 60→60 / 血转盾 30%
     */
    public static StatusEffect[][] getTierPools() {
        StatusEffect[][] pools = new StatusEffect[PVESettings.TIER_COUNT][];
        pools[PVESettings.TIER_LOW] = buildPool(
                SpeedA, HealthA, DamageA, ReloadA,
                GenerateWingmanA, DamageReductionA, TeamHealthA,
                ShieldToHealthA, HealthToShieldA);
        pools[PVESettings.TIER_MID] = buildPool(
                SpeedB, HealthB, DamageB, ReloadB,
                GenerateWingmanB, BulletSpawnArtillery, DamageReductionB, TeamHealthB,
                ShieldToHealthB, HealthToShieldB);
        pools[PVESettings.TIER_HIGH] = buildPool(
                SpeedC, HealthC, DamageC, ReloadC,
                GenerateWingmanC, DamageReductionC, TeamHealthC,
                ShieldToHealthC, HealthToShieldC);
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
     * 共血三档共用同一份 team 名册，若共存会各自按自己的门槛把对方踢出/拉进队，结果互相打架。
     */
    public static StatusEffect[][] getExclusiveGroups() {
        return new StatusEffect[][] {
                {GenerateWingmanA, GenerateWingmanB, GenerateWingmanC},
                {TeamHealthA, TeamHealthB, TeamHealthC},
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
