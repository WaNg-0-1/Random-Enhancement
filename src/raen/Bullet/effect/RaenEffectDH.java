package raen.Bullet.effect;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Lines;
import arc.math.Angles;
import arc.math.Mathf;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.Fx;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Healthc;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.ui.Fonts;
import raen.Bullet.RaenEffectBase;
import raen.Bullet.RaenEffectText;

import static raen.Bullet.RaenEffectText.num;

/**
 * 效果 —— 电荷 / 磁爆（DianHe）
 * <p>
 * 移植自测试模组的 {@code FzEffectDH}，只改了包名、类名与子弹标签名；
 * 连锁闪电子弹改为本包内的 {@link RaenChainLightningBullet}。
 * <p>
 * 子弹随机附加正/负电荷，命中目标时施加；异极碰撞会引爆并生成磁爆场。
 * 单位与建筑都可以带电、都会被磁爆场影响。
 */
public class RaenEffectDH extends RaenEffectBase {

    public static final String ID_POS = "raenccPos";
    public static final String ID_NEG = "raenccNeg";
    public static final RaenEffectDH INSTANCE;

    static {
        INSTANCE = new RaenEffectDH();
        register(INSTANCE);
    }

    // ==================== 可调参数 ====================
    /** 引爆阈值 */
    public int chargeThreshold = 20;
    /** 磁爆场持续时间（tick，360 = 6 秒） */
    public float fieldLifetime = 370f;
    /** 磁爆场呼吸动画速度（弧度/帧，0.05 ≈ 2 秒一次） */
    public float fieldPulseSpeed = 0.05f;
    /** 磁爆场呼吸动画幅度（占半径比例） */
    public float fieldPulseAmount = 0.035f;
    /** 磁爆场基础半径 */
    public float fieldBaseRadius = 40f;
    /** 磁爆场施加电荷间隔（tick，60 = 1 秒） */
    public float fieldApplyInterval = 55f;
    /** 磁场引爆时的基础伤害 */
    public float fieldTriggerDamage = 10f;
    /** 每层引爆伤害倍率 */
    public float detonationMultiplier = 0.03f;
    /** 大引爆范围伤害倍率 */
    public float bigAoeMultiplier = 0.2f;
    /** 小引爆范围伤害倍率 */
    public float smallAoeMultiplier = 0.0125f;
    /** 小引爆范围 */
    public float smallAoeRange = 24f;
    /** 大引爆范围 */
    public float bigAoeRange = 52.8f;
    /** 磁爆圈异极湮灭时，引爆范围内目标身上每层电荷的伤害 */
    public float annihilationStackDamage = 1.5f;
    /** 磁爆圈异极湮灭的百分比伤害上限 */
    public float annihilationDamageCap = 360f;
    /** 层数 / 磁场倒计时文字缩放（1 = 默认大小；越小越不挡视线） */
    public float textScale = 0.25f;

    // ==================== 闲置衰减（防止目标一直挂着状态不结算） ====================
    /** 层数多久没有变化（既没有被同极叠加、也没有被异极作用）后开始衰减（tick，600 = 10 秒） */
    public float idleDecayDelay = 600f;
    /** 衰减间隔（tick，60 = 每秒掉 1 层） */
    public float decayInterval = 60f;
    /**
     * 自然消散时每层造成的<b>固定</b>伤害（0.00125 = 0.125% / 层）。
     * 这里【不乘目标血量】：自然消散只是象征性的轻微伤害，真正的代价是掉层数；
     * 大伤害来自异极碰撞引爆。
     */
    public float decayDamagePerStack = 0.00125f;

    public Color posColor = Color.valueOf("4DA6FF");
    public Color negColor = Color.valueOf("FF6B4A");
    public Color posTextColor = Color.valueOf("87CEEB");
    public Color negTextColor = Color.valueOf("FFA07A");

    /** 极性：+1 正，-1 负（层数存在父类的 layers 中） */
    private final ObjectMap<Healthc, Integer> polarity = new ObjectMap<>();
    private final Seq<MagneticField> fields = new Seq<>();
    private float fieldTickTimer = 0f;

    /** 目标 → { 层数没变化的时间, 衰减计时 } */
    private final ObjectMap<Healthc, float[]> idle = new ObjectMap<>();
    /** 目标 → 上一帧的层数（用来检测「层数发生变化」） */
    private final ObjectMap<Healthc, Integer> lastStacks = new ObjectMap<>();
    /** 复用：闲置检测要遍历的目标 */
    private final Seq<Healthc> tmpTargets = new Seq<>();

    private final RaenEffectText text;

    public RaenEffectDH() {
        text = RaenEffectText.key("raen.tag.dh.name", "4DA6FF", this::describe);
    }

    @Override
    public String id() {
        return ID_POS;
    }

    @Override
    public String[] ids() {
        return new String[]{ID_POS, ID_NEG};
    }

    @Override
    public RaenEffectText text() {
        return text;
    }

    @Override
    public void clearLayer(Healthc target) {
        super.clearLayer(target);
        if (target != null) {
            polarity.remove(target);
            idle.remove(target);
            lastStacks.remove(target);
        }
    }

    // ==================== 子弹标签 ====================

    @Override
    public boolean attach(Bullet bullet, int stacks) {
        if (bullet == null || stacks <= 0) return false;
        if (bulletStacks(bullet) > 0) return false; // 已经有电荷了，不覆盖
        markBullet(bullet, Mathf.random() < 0.5f ? ID_POS : ID_NEG, stacks);
        return true;
    }

    // ==================== 命中 ====================

    @Override
    protected void onHit() {
        int incoming = 1;
        if (ctxBullet != null && readStacks(ctxBullet, ID_NEG) > 0) incoming = -1;

        // 直接施加（没有子弹）时按「磁场触发」结算：伤害基数用 fieldTriggerDamage，
        // 否则异极引爆会变成一个 0 伤害的空爆
        boolean fromField = ctxBullet == null;

        for (int i = 0; i < ctxStacks; i++) {
            applyCharge(ctxTarget, incoming, ctxDamage, ctxTeam, fromField);
        }
    }

    @Override
    public void onDeath(Healthc target) {
        super.onDeath(target);
        polarity.remove(target);
    }

    // ==================== 电荷逻辑 ====================

    /**
     * 通用电荷施加
     *
     * @param fromField true = 磁场触发（用 fieldTriggerDamage），false = 子弹触发（用 baseDamage）
     */
    private void applyCharge(Healthc target, int incoming, float baseDamage, Team sourceTeam, boolean fromField) {
        if (target == null || target.dead()) return;

        if (!polarity.containsKey(target)) {
            polarity.put(target, incoming);
            setLayer(target, 1);
            return;
        }

        int current = polarity.get(target, incoming);
        if (current == incoming) {
            setLayer(target, layer(target) + 1);
            return;
        }

        // ===== 异极碰撞 =====
        float effectiveDamage = fromField ? fieldTriggerDamage : baseDamage;
        int stacks = layer(target);

        if (stacks < chargeThreshold) {
            // --- 小磁爆 ---
            float aoeDamage = effectiveDamage * smallAoeMultiplier;
            areaDamage(sourceTeam, target.x(), target.y(), smallAoeRange, aoeDamage, null, true);
            Fx.explosion.at(target.x(), target.y());

            setLayer(target, stacks - 1);
            if (layer(target) <= 0) polarity.remove(target);
        } else {
            // --- 大引爆 ---
            int detonated = stacks;
            float trueDamageValue = detonated * detonationMultiplier * effectiveDamage;

            trueDamage(target, trueDamageValue);
            target.damage(0.001f);

            areaDamage(sourceTeam, target.x(), target.y(), bigAoeRange,
                    trueDamageValue * bigAoeMultiplier, null, true);

            fields.add(new MagneticField(target.x(), target.y(), incoming, sourceTeam, fieldBaseRadius));

            Fx.heal.at(target.x(), target.y(), incoming > 0 ? posColor : negColor);

            clearLayer(target);
        }
    }

    /** 磁爆场施加电荷（异极时触发引爆） */
    private void applyChargeFromField(Healthc target, MagneticField field) {
        if (target == null || target.dead()) return;

        if (!polarity.containsKey(target)) {
            polarity.put(target, field.polarity);
            setLayer(target, 1);
            return;
        }

        if (polarity.get(target, field.polarity) == field.polarity) {
            setLayer(target, layer(target) + 1);
            return;
        }

        applyCharge(target, field.polarity, fieldTriggerDamage, field.team, true);
    }

    // ==================== 闲置衰减 ====================

    /**
     * 层数在 idleDecayDelay（默认 10 秒）内没有变化（既没有被同极叠加、也没有被异极作用）
     * 就开始衰减：每秒 -1 层，并按【当前层数】对目标造成伤害（走 {@code damage()}，受护甲影响）。
     * 目的：防止目标一直挂着电荷不结算。
     * <p>
     * 自己的衰减同样会改变层数，所以衰减后要同步 {@link #lastStacks}，
     * 否则下一帧会被误判成「层数变化」而把计时重置掉，衰减就永远走不动了。
     */
    private void tickIdleDecay(float delta) {
        if (polarity.size == 0) return;

        tmpTargets.clear();
        for (ObjectMap.Entry<Healthc, Integer> entry : polarity) {
            tmpTargets.add(entry.key);
        }

        for (int i = 0; i < tmpTargets.size; i++) {
            Healthc target = tmpTargets.get(i);
            if (target == null) continue;

            int stacks = layer(target);
            if (target.dead() || stacks <= 0) {
                clearLayer(target);
                continue;
            }

            Integer lastObj = lastStacks.get(target);
            int last = lastObj == null ? stacks : lastObj;

            if (stacks != last) {
                // 层数变了（同极叠加 / 异极引爆 / 磁场施加）→ 重新计时
                lastStacks.put(target, stacks);
                float[] reset = idleTime(target);
                reset[0] = 0f;
                reset[1] = 0f;
                continue;
            }

            float[] state = idleTime(target);
            state[0] += delta;
            if (state[0] < idleDecayDelay) continue;

            state[1] += delta;
            if (state[1] < decayInterval) continue;
            state[1] -= decayInterval;

            // 先按「当前层数」造成伤害（固定点数、不乘血量），再掉 1 层
            float damage = stacks * decayDamagePerStack;
            if (damage > 0f) target.damage(damage);

            if (target.dead()) {
                clearLayer(target);
                continue;
            }

            int next = stacks - 1;
            if (next <= 0) {
                clearLayer(target);              // 清层 + 清极性 + 清计时
            } else {
                setLayer(target, next);
                lastStacks.put(target, next);    // ★ 自己的衰减不算「外部变化」
            }
        }
    }

    /** 取（或创建）目标的闲置状态数组：{ 层数没变化的时间, 衰减计时 } */
    private float[] idleTime(Healthc target) {
        float[] state = idle.get(target);
        if (state == null) {
            state = new float[]{0f, 0f};
            idle.put(target, state);
        }
        return state;
    }

    // ==================== 全局更新 ====================

    @Override
    public void onTick(float delta) {
        // --- 闲置衰减：层数 10 秒没变化 → 每秒 -1 层并造成伤害 ---
        tickIdleDecay(delta);

        // --- 更新磁爆场 ---
        for (int i = 0; i < fields.size; i++) {
            MagneticField field = fields.get(i);
            field.age += delta;
            field.remainingLifetime -= delta;
            if (field.dying) {
                field.deathTimer -= delta;
                if (field.deathTimer <= 0f) field.removed = true;
            }
            if (field.remainingLifetime <= 0f && !field.dying) {
                field.markDying();
            }
        }
        fields.remove(field -> field.removed);

        // --- 每秒：磁爆场施加电荷 ---
        fieldTickTimer += delta;
        if (fieldTickTimer < fieldApplyInterval) return;
        fieldTickTimer = 0f;

        for (int i = 0; i < fields.size; i++) {
            MagneticField field = fields.get(i);
            if (field.removed || field.dying) continue;
            applyFieldCharge(field);
        }

        // --- 同极合并 + 异极湮灭 ---
        processFieldInteractions();
    }

    /** 磁爆场给范围内所有单位 / 建筑施加同种电荷 */
    private void applyFieldCharge(MagneticField field) {
        final float r2 = field.radius * field.radius;

        Groups.unit.intersect(field.x - field.radius, field.y - field.radius,
                field.radius * 2f, field.radius * 2f, unit -> {
                    if (unit.dead()) return;
                    if (unit.dst2(field.x, field.y) > r2) return;
                    applyChargeFromField(unit, field);
                });

        eachBuilding(field.x, field.y, field.radius, build -> applyChargeFromField(build, field));
    }

    // ==================== 磁爆场交互 ====================

    private void processFieldInteractions() {
        Seq<MagneticField> live = new Seq<>();
        for (int i = 0; i < fields.size; i++) {
            MagneticField field = fields.get(i);
            if (!field.removed && !field.dying) live.add(field);
        }
        if (live.size < 2) return;

        // --- 第一步：异极湮灭 ---
        for (int i = 0; i < live.size; i++) {
            MagneticField a = live.get(i);
            if (a.removed || a.dying) continue;
            for (int j = i + 1; j < live.size; j++) {
                MagneticField b = live.get(j);
                if (b.removed || b.dying) continue;
                if (a.polarity == b.polarity) continue;

                float dist = Mathf.dst(a.x, a.y, b.x, b.y);
                if (dist >= a.radius + b.radius) continue;

                float midX = (a.x + b.x) / 2f;
                float midY = (a.y + b.y) / 2f;
                float explosionRadius = Math.max(a.radius, b.radius) * 2f;
                float timeSum = a.remainingSeconds() + b.remainingSeconds();

                annihilationDamage(a.team, midX, midY, explosionRadius, timeSum);

                Fx.airBubble.at(midX, midY);
                Fx.heal.at(midX, midY, Color.white);

                a.markDying();
                b.markDying();
            }
        }

        // --- 第二步：同极合并 ---
        live.clear();
        for (int i = 0; i < fields.size; i++) {
            MagneticField field = fields.get(i);
            if (!field.removed && !field.dying) live.add(field);
        }
        if (live.size < 2) return;

        int n = live.size;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            MagneticField a = live.get(i);
            for (int j = i + 1; j < n; j++) {
                MagneticField b = live.get(j);
                if (a.polarity != b.polarity) continue;
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
            int polarityValue = live.get(indices.first()).polarity;
            Team team = live.get(indices.first()).team;

            for (int k = 0; k < indices.size; k++) {
                MagneticField field = live.get(indices.get(k));
                sumX += field.x;
                sumY += field.y;
                if (field.radius > maxRadius) maxRadius = field.radius;
                field.markDying();
            }

            MagneticField merged = new MagneticField(sumX / indices.size, sumY / indices.size,
                    polarityValue, team, maxRadius + 8f);
            merged.remainingLifetime = fieldLifetime;
            fields.add(merged);
        }
    }

    /** 异极湮灭伤害：引爆范围内目标身上的电荷层数，并按目标当前生命的百分比造成真实伤害（有上限） */
    private void annihilationDamage(Team safeTeam, float x, float y, float radius, float timeSum) {
        final float r2 = radius * radius;

        Groups.unit.intersect(x - radius, y - radius, radius * 2f, radius * 2f, unit -> {
            if (unit.dead() || unit.team == safeTeam) return;
            if (unit.dst2(x, y) > r2) return;
            detonateStacks(unit);
            applyAnnihilationDamage(unit, timeSum);
        });

        eachBuilding(x, y, radius, build -> {
            if (build.team == safeTeam) return;
            detonateStacks(build);
            applyAnnihilationDamage(build, timeSum);
        });
    }

    /** 引爆目标身上所有电荷层数：每层造成固定伤害，然后清空 */
    private void detonateStacks(Healthc target) {
        int stacks = layer(target);
        if (stacks <= 0) return;
        float damage = stacks * annihilationStackDamage;
        if (damage > 0f) target.damage(damage);
        clearLayer(target);
    }

    /** 百分比真实伤害，受 annihilationDamageCap 限制 */
    private void applyAnnihilationDamage(Healthc target, float timeSum) {
        if (target == null || target.dead()) return;
        float damage = Math.min(target.health() * timeSum * 0.01f, annihilationDamageCap);
        if (damage <= 0f) return;
        trueDamage(target, damage);
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

    // ==================== 磁爆场数据 ====================

    private static class MagneticField {
        static final float FADE_IN_TIME = 15f;
        static final float DEATH_TIME = 20f;

        float x, y;
        int polarity;
        Team team;
        float remainingLifetime;
        float radius;
        boolean removed;
        boolean dying;
        float deathTimer;
        float age;
        /** 呼吸动画的固定相位（弧度） */
        float phase;

        MagneticField(float x, float y, int polarity, Team team, float radius) {
            this.x = x;
            this.y = y;
            this.polarity = polarity;
            this.team = team;
            this.radius = radius;
            this.remainingLifetime = INSTANCE.fieldLifetime;
            this.age = 0f;
            this.phase = Mathf.random(0f, Mathf.PI2);
        }

        void markDying() {
            this.dying = true;
            this.deathTimer = DEATH_TIME;
        }

        float remainingSeconds() {
            return remainingLifetime / 60f;
        }

        float renderAlpha() {
            float baseAlpha = Mathf.clamp(0.25f + radius * 0.004f, 0.2f, 0.7f);
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
        // ---------- 磁爆场 ----------
        if (fields.size > 0) {
            Draw.z(Layer.effect);
            for (int i = 0; i < fields.size; i++) {
                MagneticField field = fields.get(i);
                if (field.removed) continue;

                float alpha = field.renderAlpha();
                if (alpha < 0.01f) continue;

                float r = field.renderRadius();
                // ★ 呼吸动画用磁爆场自己的固定相位 + 慢速（原来跟着列表下标取相位、2.5 弧度/帧 ≈ 24Hz，看着就是抖）
                float pulse = 1f + Mathf.sin(Time.time * fieldPulseSpeed + field.phase) * fieldPulseAmount;
                float outerR = r * 1.15f * pulse;

                Color col = field.polarity > 0 ? posColor : negColor;

                Draw.color(col);
                Draw.alpha(alpha * 0.2f);
                Fill.circle(field.x, field.y, outerR);
                Draw.alpha(alpha * 0.45f);
                Fill.circle(field.x, field.y, r);
                Draw.alpha(alpha);
                Lines.stroke(1.8f);
                Lines.circle(field.x, field.y, r);
                Draw.alpha(alpha * 0.6f);
                Lines.stroke(0.8f);
                Lines.circle(field.x, field.y, r * 0.7f);
            }
            Draw.reset();

            // ---------- 磁爆场文字 ----------
            Draw.z(Layer.overlayUI);
            Font font = Fonts.outline;
            for (int i = 0; i < fields.size; i++) {
                MagneticField field = fields.get(i);
                if (field.removed || field.dying) continue;

                float alpha = field.renderAlpha();
                if (alpha < 0.1f) continue;

                String sign = field.polarity > 0 ? "+" : "-";
                String value = sign + " " + String.format("%.1f", field.remainingSeconds()) + "s";

                tmpColor.set(field.polarity > 0 ? posTextColor : negTextColor);
                tmpColor.a = alpha;
                font.setColor(tmpColor);
                drawStackText(font, value, field.x, field.y + field.renderRadius() + 8f, textScale);
            }
            Draw.reset();
        }

        // ---------- 电荷层数 ----------
        if (polarity.size > 0) {
            for (ObjectMap.Entry<Healthc, Integer> entry : polarity) {
                Healthc target = entry.key;
                int stacks = layer(target);
                if (target == null || target.dead() || stacks <= 0) continue;

                int value = entry.value == null ? 1 : entry.value;
                String label = (value > 0 ? "+" : "-") + stacks;

                tmpColor.set(value > 0 ? posColor : negColor);
                tmpColor.lerp(Color.white, Mathf.clamp(stacks / 20f) * 0.5f);
                tmpColor.a = 0.9f;

                // 槽位 2：排在积焰下面，避免和另外几个数字叠在一起
                drawStacks(target, 2, label, textScale, tmpColor);
            }
            Draw.reset();
        }
    }

    @Override
    public void onReset() {
        polarity.clear();
        fields.clear();
        idle.clear();
        lastStacks.clear();
        tmpTargets.clear();
        fieldTickTimer = 0f;
    }

    // ==================== 文本 ====================

    public String describe() {
        return Core.bundle.format("raen.tag.dh.title", text.color) + "\n" +
                Core.bundle.get("raen.tag.dh.l1") + "\n" +
                Core.bundle.get("raen.tag.dh.l2") + "\n" +
                "  · " + Core.bundle.format("raen.tag.dh.l3", chargeThreshold,
                num(smallAoeMultiplier * 100f), num(smallAoeRange / 8f)) + "\n" +
                "  · " + Core.bundle.format("raen.tag.dh.l4", chargeThreshold) + "\n" +
                "    " + Core.bundle.format("raen.tag.dh.l5", (int) (detonationMultiplier * 100),
                (int) (bigAoeMultiplier * 100), num(bigAoeRange / 8f)) + "\n" +
                "    " + Core.bundle.format("raen.tag.dh.l6", num(fieldLifetime / 60f)) + "\n" +
                Core.bundle.get("raen.tag.dh.l7") + "\n" +
                Core.bundle.format("raen.tag.dh.l8", num(annihilationStackDamage)) + "\n" +
                Core.bundle.format("raen.tag.dh.l9", (int) annihilationDamageCap) + "\n" +
                Core.bundle.format("raen.tag.dh.l10", num(idleDecayDelay / 60f)) + "\n" +
                Core.bundle.format("raen.tag.dh.l11", num(decayDamagePerStack)) + "\n" +
                Core.bundle.get("raen.tag.dh.l12");
    }
}
