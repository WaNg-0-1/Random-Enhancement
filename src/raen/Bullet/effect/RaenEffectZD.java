package raen.Bullet.effect;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.ObjectFloatMap;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.gen.Groups;
import mindustry.gen.Healthc;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.type.StatusEffect;
import mindustry.type.unit.MissileUnitType;
import mindustry.ui.Fonts;
import raen.Bullet.RaenEffectBase;
import raen.Bullet.RaenEffectText;

import static raen.Bullet.RaenEffectText.num;

/**
 * 效果 —— 中毒 / 毒圈（ZhongDu）
 * <p>
 * 移植自测试模组的 {@code FzEffectZD}，只改了包名、类名与子弹标签名。
 * 子弹命中目标叠加中毒层数，满血每 1 秒结算一次、随血量降低线性提速到濒死每秒 10 次，
 * 并按层数分档减速（10 档 + 不减速档）；
 * 目标死亡时残留毒圈，毒圈按「水池」机制扣血并合并。单位与建筑都生效。
 * <p>
 * 结算前会先做「处决」判定：每层提供「0.05% 已损失生命 + 2.5 点」的斩杀伤害，
 * 两部分相加后封顶在「已损失生命的 11.11%」；血量低于斩杀线时不再直接击杀，
 * 而是每次结算有 0.33% 概率斩杀，失败后下次 +0.33%（约 303 次必中），回到斩杀线以上则重置。
 * 斩杀<b>不消耗层数</b>，让死亡后续效果（毒圈、其它效果 onDeath）拿到完整层数。
 * <p>
 * 减速用 {@link #DUSU_TIERS} 一组**固定**的状态实例，绝不修改共享实例的字段
 * （否则多单位同时中毒时会互相覆盖倍率）。
 */
public class RaenEffectZD extends RaenEffectBase {

    public static final String ID = "raensdPoison";
    public static final RaenEffectZD INSTANCE;

    /**
     * 减速档位数量（不含「不减速」档，所以一共 SLOW_TIERS + 1 个状态实例）。
     * <p>
     * ★ 这里必须用「一档一个独立 StatusEffect」而不是「改一个共享实例的字段再 apply」：
     * StatusEffect 是全局唯一对象，引擎是**按实例**读 speedMultiplier 的，
     * 多单位同时中毒时后结算的单位会把值改掉，导致所有单位都吃最后一个倍率（还会随处理顺序每帧抖）。
     */
    public static final int SLOW_TIERS = 10;
    /** 减速下限：无论多少层，速度倍率最低只到 0.1（移动速度剩 10%），不会减到 0 / 停住 */
    public static final float MIN_SLOW_MULT = 0.1f;
    /** 层数达到该值时减速到下限（映射到最后一档） */
    public static final float STACKS_FOR_FROZEN = 1000f;

    /**
     * 减速状态阶梯：第 i 档 speedMultiplier = 1 - (1 - {@link #MIN_SLOW_MULT}) × i / SLOW_TIERS。
     * 第 0 档 = 1（不减速），第 {@link #SLOW_TIERS} 档 = {@link #MIN_SLOW_MULT}（剩 10% 速度，不再往下）。
     * 所有实例**创建后就不再修改**，所以多单位互不干扰。
     */
    public static final StatusEffect[] DUSU_TIERS = new StatusEffect[SLOW_TIERS + 1];

    static {
        for (int i = 0; i <= SLOW_TIERS; i++) {
            final float mult = 1f - (1f - MIN_SLOW_MULT) * i / (float) SLOW_TIERS;
            final String name = "dusu" + i;
            DUSU_TIERS[i] = new StatusEffect(name) {{
                speedMultiplier = mult;
                effectChance = 1f;
                permanent = false;
                // 动态创建的 StatusEffect 不进 Vars.content，拿不到 uiIcon。
                // 其它模组（如 MindustryX）画单位血条时会读它，null 会直接 NPE。
                uiIcon = fullIcon = mindustry.content.StatusEffects.burning.uiIcon;
            }};
        }
    }

    static {
        INSTANCE = new RaenEffectZD();
        register(INSTANCE);
    }

    // ==================== 可调参数 ====================
    /** 每层毒提供的/ 伤害 */
    public float damagePerStack = 0.1f;
    /** 每层伤害里走 damage 的比例（0.75 → 0.075/层） */
    public float normalPartRatio = 0.75f;
    /** 每层伤害里走真实伤害的比例（0.25 → 0.025/层） */
    public float truePartRatio = 0.25f;
    /** 满血时的中毒结算间隔（tick，60 = 1 秒） */
    public float unitInterval = 60f;
    /** 濒死时的中毒结算间隔（tick，6 = 每秒 10 次），按血量线性插值 */
    public float minUnitInterval = 6f;
    /** 毒圈结算间隔（tick，60 = 1 秒） */
    public float fieldInterval = 60f;
    /** 死亡时残留毒圈的半径 */
    public float fieldRadius = 48f;
    /** 减速状态持续时间 */
    public float slowDuration = 120f;
    /** 处决·百分比部分：每层提供的「已损失生命比例」斩杀伤害（0.0005 = 0.05%/层） */
    public float executePercentPerStack = 0.0005f;
    /** 处决·固定部分：每层额外提供的固定斩杀伤害（点数，不与百分比部分分开封顶） */
    public float executeFlatPerStack = 2.5f;
    /**
     * 处决·总伤害上限（占「已损失生命」比例，0.2 = 20%）。
     * 百分比与固定部分<b>相加后</b>一起受它约束。
     * <p>
     * 上限随已损失生命增长，斩杀线被自然压在 {@code hp/H < r/(1+r)} 以下
     * （0.1111 → 约 10% 血）；满血时上限为 0，不可能触发。
     * 设为 &lt;= 0 表示不封顶。
     */
    public float executeDamageCapRatio = 0.1111f;
    /** 斩杀基础概率：血量低于斩杀线时每次结算 0.33%，失败后每次 +0.33%（封顶 100%） */
    public float executeProcChance = 0.0033f;
    /** 层数 / 毒圈水量文字缩放（1 = 默认大小；越小越不挡视线） */
    public float textScale = 0.25f;
    /** 毒圈呼吸动画速度（弧度/帧，0.05 ≈ 2 秒一次；调大就会开始"抖"） */
    public float fieldPulseSpeed = 0.05f;
    /** 毒圈呼吸动画幅度（占半径比例，越小越安静） */
    public float fieldPulseAmount = 0.04f;

    public Color fieldColor = Color.valueOf("6bff6b");

    private final Seq<PoisonField> poisonFields = new Seq<>();
    /** 每个中毒目标自己的结算计时（按当前血量比例算间隔） */
    private final ObjectFloatMap<Healthc> poisonTimers = new ObjectFloatMap<>();
    /** 每个目标「低于斩杀线但没斩掉」的累计失败次数：概率 = (次数+1) × 1% */
    private final ObjectIntMap<Healthc> executeMisses = new ObjectIntMap<>();
    private float fieldTimer = 0f;

    private final RaenEffectText text;

    public RaenEffectZD() {
        text = RaenEffectText.key("raen.tag.zd.name", "00ff00", this::describe);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RaenEffectText text() {
        return text;
    }

    // ==================== 命中 ====================

    @Override
    protected void onHit() {
        addLayer(ctxTarget, ctxStacks);
    }

    // ==================== 死亡 ====================

    @Override
    public void onDeath(Healthc target) {
        int stacks = layer(target);
        if (stacks > 0) {
            poisonFields.add(new PoisonField(target.x(), target.y(), stacks, fieldRadius));
        }
        super.onDeath(target);
    }

    // ==================== 全局更新 ====================

    @Override
    public void onTick(float delta) {
        // --- 毒圈 age / deathTimer ---
        for (int i = 0; i < poisonFields.size; i++) {
            PoisonField field = poisonFields.get(i);
            field.age += delta;
            if (field.dying) {
                field.deathTimer -= delta;
                if (field.deathTimer <= 0f) field.removed = true;
            }
        }
        poisonFields.remove(field -> field.removed);

        // --- 1. 中毒结算：每个目标按自身血量比例决定间隔（满血 1 秒 ↔ 濒死 0.1 秒） ---
        if (layers.size > 0) {
            Seq<Healthc> targets = new Seq<>();
            for (ObjectMap.Entry<Healthc, Integer> entry : layers) targets.add(entry.key);

            for (int i = 0; i < targets.size; i++) {
                Healthc target = targets.get(i);
                if (target == null || target.dead() || layer(target) <= 0) {
                    clearLayer(target);
                    continue;
                }
                float timer = poisonTimers.get(target, 0f) + delta;
                if (timer >= intervalOf(target)) {
                    poisonTimers.put(target, 0f);
                    tickPoisoned(target);
                } else {
                    poisonTimers.put(target, timer);
                }
            }
        }

        // --- 2. 毒圈结算与合并（每 1 秒） ---
        fieldTimer += delta;
        if (fieldTimer >= fieldInterval) {
            fieldTimer = 0f;
            tickFields();
        }
    }

    /** 结算间隔：满血 unitInterval，濒死 minUnitInterval，线性插值。 */
    private float intervalOf(Healthc target) {
        float hp = target.health(), max = target.maxHealth();
        float frac = max > 0f ? Mathf.clamp(hp / max, 0f, 1f) : 1f;
        return Mathf.lerp(minUnitInterval, unitInterval, frac);
    }

    /** 单个目标的结算：处决判定 → 伤害 → 减速 → 层数 -1。 */
    private void tickPoisoned(Healthc target) {
        int stacks = layer(target);
        if (target == null || target.dead() || stacks <= 0) {
            clearLayer(target);
            return;
        }

        // ★ 处决判定必须在「造成伤害 / 层数-1」之前：层数原封不动地留给死亡后续效果
        if (tryExecute(target, stacks)) return;

        float damage = stacks * damagePerStack;
        float scale = (target instanceof Unit && ((Unit) target).type instanceof MissileUnitType) ? 2f : 1f;

        target.damage(damage * normalPartRatio * scale);
        trueDamage(target, damage * truePartRatio * scale);

        if (target.dead()) {
            clearLayer(target);
            return;
        }

        if (target instanceof Unit) {
            // 按层数选档：0 层不减速、STACKS_FOR_FROZEN 层时降到下限 10%（不再减到 0）
            int tier = Mathf.clamp((int) (stacks / (STACKS_FOR_FROZEN / SLOW_TIERS) + 0.5f), 0, SLOW_TIERS);
            if (tier > 0) {
                // ★ 只 apply、不改实例：每个档位都是固定的独立状态，多单位同时中毒互不影响
                ((Unit) target).apply(DUSU_TIERS[tier], slowDuration);
            }
        }

        setLayer(target, stacks - 1);
    }

    /**
     * 处决：每层提供 {@code 0.05% × 已损失生命 + 2.5 点} 的斩杀伤害（封顶已损失生命 11.11%）。
     * 血量低于斩杀线时不再直接击杀：每次结算按「1% + 累计失败次数×1%」掷一次，
     * 失败则累计 +1；血量回到斩杀线以上时清零。斩杀不消耗层数。
     */
    private boolean tryExecute(Healthc target, int stacks) {
        if (stacks <= 0 || target == null || target.dead()) return false;
        if (executePercentPerStack <= 0f && executeFlatPerStack <= 0f) return false;  // 关闭处决

        float hp = target.health();
        float max = target.maxHealth();
        if (Float.isNaN(hp) || Float.isNaN(max) || hp <= 0f || max <= 0f) return false;

        float lost = max - hp;
        if (lost <= 0f) {                      // 满血 / 超血：重置累计概率
            executeMisses.remove(target, 0);
            return false;
        }

        float percentPart = Math.max(0f, stacks * executePercentPerStack) * lost;
        float flatPart = Math.max(0f, stacks * executeFlatPerStack);
        float executeDamage = percentPart + flatPart;

        // ★ 上限必须管「总和」：只封顶百分比部分会被无上限的固定部分绕过
        // ★ 上限基数用「已损失生命」：斩杀线被压在 hp/H < r/(1+r) 以下，满血时上限为 0
        if (executeDamageCapRatio > 0f) {
            executeDamage = Math.min(executeDamage, executeDamageCapRatio * lost);
        }
        if (executeDamage <= 0f || hp >= executeDamage) {
            executeMisses.remove(target, 0);   // 回到斩杀线以上：叠加概率重置
            return false;
        }

        int misses = executeMisses.get(target, 0);
        float chance = Mathf.clamp((misses + 1) * executeProcChance, 0f, 1f);
        if (Mathf.random() >= chance) {
            executeMisses.put(target, misses + 1);
            return false;
        }
        executeMisses.remove(target, 0);

        target.kill();

        // kill() 在客户端可能直接被忽略；没死就别乱发死亡分发，交回常规流程
        if (!target.dead()) return false;

        if (has(target)) RaenEffectBase.notifyDeath(target);
        return true;
    }

    private void tickFields() {
        if (poisonFields.size == 0) return;

        Seq<PoisonField> snapshot = new Seq<>();
        for (int i = 0; i < poisonFields.size; i++) {
            snapshot.add(poisonFields.get(i));
        }

        for (int i = 0; i < snapshot.size; i++) {
            PoisonField field = snapshot.get(i);
            if (field.removed || field.dying) continue;

            float pool = field.stacks * damagePerStack;
            if (pool <= 0f) {
                field.markDying();
                continue;
            }

            final float[] remain = {pool};
            final float r2 = field.radius * field.radius;
            final float fx = field.x, fy = field.y;

            Groups.unit.intersect(fx - field.radius, fy - field.radius,
                    field.radius * 2f, field.radius * 2f, unit -> {
                        if (remain[0] <= 0f || unit.dead()) return;
                        if (unit.dst2(fx, fy) > r2) return;

                        float deal = Math.min(remain[0], unit.health());
                        if (unit.type instanceof MissileUnitType) {
                            unit.damage(deal * 3f);
                            remain[0] += 12f;
                        } else {
                            unit.damage(deal);
                            remain[0] -= deal;
                        }
                    });

            eachBuilding(fx, fy, field.radius, build -> {
                if (remain[0] <= 0f) return;
                float deal = Math.min(remain[0], build.health());
                build.damage(deal);
                remain[0] -= deal;
            });

            int newStacks = (int) Math.ceil(remain[0] / damagePerStack);
            if (newStacks <= 0) field.markDying();
            else field.stacks = newStacks;
        }

        // --- 合并逻辑 ---
        Seq<PoisonField> live = new Seq<>();
        for (int i = 0; i < poisonFields.size; i++) {
            PoisonField field = poisonFields.get(i);
            if (!field.removed && !field.dying) live.add(field);
        }
        if (live.size < 2) return;

        int n = live.size;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            PoisonField a = live.get(i);
            for (int j = i + 1; j < n; j++) {
                PoisonField b = live.get(j);
                if (Mathf.dst(a.x, a.y, b.x, b.y) < a.radius + b.radius) {
                    union(parent, i, j);
                }
            }
        }

        ObjectMap<Integer, Seq<Integer>> groups = new ObjectMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            Seq<Integer> group = groups.get(root);
            if (group == null) {
                group = new Seq<>();
                groups.put(root, group);
            }
            group.add(i);
        }

        for (ObjectMap.Entry<Integer, Seq<Integer>> entry : groups) {
            Seq<Integer> indices = entry.value;
            if (indices.size < 2) continue;

            float sumX = 0f, sumY = 0f, maxRadius = 0f;
            int totalStacks = 0;

            for (int k = 0; k < indices.size; k++) {
                PoisonField field = live.get(indices.get(k));
                sumX += field.x;
                sumY += field.y;
                totalStacks += field.stacks;
                if (field.radius > maxRadius) maxRadius = field.radius;
                field.markDying();
            }

            poisonFields.add(new PoisonField(sumX / indices.size, sumY / indices.size,
                    totalStacks, maxRadius + 8f));
        }
    }

    // ==================== 并查集 ====================

    private static int find(int[] parent, int i) {
        if (parent[i] != i) parent[i] = find(parent, parent[i]);
        return parent[i];
    }

    private static void union(int[] parent, int i, int j) {
        int ri = find(parent, i);
        int rj = find(parent, j);
        if (ri != rj) parent[ri] = rj;
    }

    // ==================== 毒圈数据 ====================

    private static class PoisonField {
        static final float FADE_IN_TIME = 25f;
        static final float DEATH_TIME = 20f;

        float x, y;
        int stacks;
        float radius;
        boolean removed;
        boolean dying;
        float age;
        float deathTimer;
        /** 呼吸动画的固定相位（弧度）：每个毒圈都不一样，且不会因为列表顺序变化而跳变 */
        float phase;

        PoisonField(float x, float y, int stacks, float radius) {
            this.x = x;
            this.y = y;
            this.stacks = stacks;
            this.radius = radius;
            this.phase = Mathf.random(0f, Mathf.PI2);
        }

        void markDying() {
            this.dying = true;
            this.deathTimer = DEATH_TIME;
        }

        float renderAlpha() {
            float baseAlpha = Mathf.clamp(0.25f + stacks * 0.008f, 0.2f, 0.75f);
            if (dying && DEATH_TIME > 0) {
                return baseAlpha * Mathf.clamp(deathTimer / DEATH_TIME);
            }
            if (age < FADE_IN_TIME && FADE_IN_TIME > 0) {
                return baseAlpha * Mathf.clamp(age / FADE_IN_TIME);
            }
            return baseAlpha;
        }

        float renderRadius() {
            if (dying && DEATH_TIME > 0) {
                return radius * (0.3f + 0.7f * Mathf.clamp(deathTimer / DEATH_TIME));
            }
            if (age < FADE_IN_TIME && FADE_IN_TIME > 0) {
                return radius * (0.5f + 0.5f * Mathf.clamp(age / FADE_IN_TIME));
            }
            return radius;
        }
    }

    // ==================== 绘制 ====================

    @Override
    public void onDraw() {
        // ---------- 毒圈 ----------
        if (poisonFields.size > 0) {
            Draw.z(Layer.effect);
            for (int i = 0; i < poisonFields.size; i++) {
                PoisonField field = poisonFields.get(i);
                if (field.removed) continue;

                float alpha = field.renderAlpha();
                if (alpha < 0.01f) continue;

                float r = field.renderRadius();
                // ★ 呼吸动画：用毒圈自己的固定相位 + 慢速。
                //   以前是 Mathf.sin(Time.time * 2.5f + i * 0.1f)：相位跟列表下标走（毒圈合并/消失时顺序一变就跳变），
                //   而且 2.5 弧度/帧 ≈ 24Hz，远快于人眼能看成"呼吸"的速度，所以看起来一直在抖。
                float pulse = 1f + Mathf.sin(Time.time * fieldPulseSpeed + field.phase) * fieldPulseAmount;

                Draw.color(fieldColor);
                Draw.alpha(alpha * 0.25f);
                Fill.circle(field.x, field.y, r * 1.15f * pulse);
                Draw.alpha(alpha * 0.5f);
                Fill.circle(field.x, field.y, r);
                Draw.alpha(alpha);
                Lines.stroke(1.8f);
                Lines.circle(field.x, field.y, r);
                Draw.alpha(alpha * 0.7f);
                Lines.stroke(0.8f);
                Lines.circle(field.x, field.y, r * 0.7f);
            }
            Draw.reset();

            // ---------- 毒圈水量文字 ----------
            Draw.z(Layer.overlayUI);
            Font font = Fonts.outline;
            for (int i = 0; i < poisonFields.size; i++) {
                PoisonField field = poisonFields.get(i);
                if (field.removed || field.dying) continue;

                float alpha = field.renderAlpha();
                if (alpha < 0.1f) continue;

                tmpColor.set(Color.yellow);
                tmpColor.a = alpha;
                font.setColor(tmpColor);
                drawStackText(font, String.format("%.0f", field.stacks * damagePerStack),
                        field.x, field.y + field.renderRadius() + 10f, textScale);
            }
            Draw.reset();
        }

        // ---------- 中毒层数文字 ----------
        if (layers.size > 0) {
            for (ObjectMap.Entry<Healthc, Integer> entry : layers) {
                Healthc target = entry.key;
                int stacks = entry.value == null ? 0 : entry.value;
                if (target == null || target.dead() || stacks <= 0) continue;

                tmpColor.set(Color.green).lerp(Color.red, Mathf.clamp(stacks / 500f));
                tmpColor.a = 0.9f;

                // 槽位 3：中毒层数经常是 4~5 位数，单独一行避免压到破绽 / 积焰 / 电荷
                drawStacks(target, 3, String.valueOf(stacks), textScale, tmpColor);
            }
            Draw.reset();
        }
    }

    @Override
    public void clearLayer(Healthc target) {
        super.clearLayer(target);
        if (target != null) {
            poisonTimers.remove(target, 0f);
            executeMisses.remove(target, 0);
        }
    }

    @Override
    public void onReset() {
        poisonFields.clear();
        poisonTimers.clear();
        executeMisses.clear();
        fieldTimer = 0f;
    }

    // ==================== 对外便捷接口 ====================

    /** 检查目标当前是否有中毒层数 */
    public boolean hasPoison(Healthc target) {
        return has(target);
    }

    /** 给目标增加中毒层数 */
    public void addPoisonStacks(Healthc target, int amount) {
        if (target == null || target.dead() || amount <= 0) return;
        addLayer(target, amount);
    }

    // ==================== 文本 ====================

    public String describe() {
        return Core.bundle.format("raen.tag.zd.title", text.color) + "\n" +
                Core.bundle.format("raen.tag.zd.l1",
                        num(damagePerStack * normalPartRatio), num(damagePerStack * truePartRatio)) + "\n" +
                Core.bundle.format("raen.tag.zd.l2", (int) (fieldRadius / 8)) + "\n" +
                Core.bundle.format("raen.tag.zd.l3", num(executePercentPerStack * 100f),
                        num(executeFlatPerStack)) + "\n" +
                "  " + Core.bundle.format("raen.tag.zd.l4", num(executeDamageCapRatio * 100f)) + "\n" +
                "  " + Core.bundle.format("raen.tag.zd.l5",
                String.format("%.1f", executeDamageCapRatio / (1f + executeDamageCapRatio) * 100f)) + "\n" +
                Core.bundle.format("raen.tag.zd.l6", num(damagePerStack)) + "\n" +
                Core.bundle.get("raen.tag.zd.l7") + "\n" +
                Core.bundle.get("raen.tag.zd.l8");
    }
}
