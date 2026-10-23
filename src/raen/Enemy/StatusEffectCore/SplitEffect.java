package raen.Enemy.StatusEffectCore;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.IntMap;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

/**
 * 分裂：血量低于阈值时，生成两个继承部分血量的同类单位，随后自身死亡。
 */
public class SplitEffect extends StatusEffect {

    /** 由分裂产生的子单位，按 unit id 记录。int → boolean，体积极小，不需要定期清理。 */
    private static final IntMap<Boolean> BORN_FROM_SPLIT = new IntMap<>();

    /** 触发分裂的血量百分比。 */
    public float splitRatio;
    /** 子单位继承的血量百分比。 */
    public float childHealthRatio;

    public SplitEffect(String name, float splitRatio, float childHealthRatio, Color color) {
        super(name);
        this.splitRatio = splitRatio;
        this.childHealthRatio = childHealthRatio;
        this.damage = 0f;
        this.transitionDamage = 0f;
        this.color = color;
    }

    /** 该单位是否由分裂产生。为 true 时不应再获得分裂效果。 */
    public static boolean isSplitChild(Unit unit) {
        return unit != null && BORN_FROM_SPLIT.get(unit.id, Boolean.FALSE);
    }

    @Override
    public void loadIcon() {
        super.loadIcon();
        // 暂未提供专属贴图时沿用现有占位图，避免状态栏出现缺失纹理；
        // 以后补上 SplitA/B/C.png 后会自动使用新贴图。
        if ((uiIcon == null || !uiIcon.found()) && Core.atlas != null) {
            uiIcon = fullIcon = Core.atlas.find("raen-SpeedA");
        }
    }

    @Override
    public void update(Unit unit, StatusEntry entry) {
        super.update(unit, entry);
        if (Vars.net.client()) return;
        if (unit == null || unit.dead() || !unit.isValid()) return;
        if (splitRatio <= 0f || childHealthRatio <= 0f) return;
        if (unit.maxHealth <= 0f || unit.health <= 0f) return;
        // 兜底：子单位即使因为别的原因拿到了本效果，也绝不再分裂。
        if (isSplitChild(unit)) return;
        // 严格「低于」阈值才分裂；阈值以上继续正常战斗。
        if (unit.health >= unit.maxHealth * splitRatio) return;

        split(unit);
    }

    private void split(Unit unit) {
        float distance = Math.max(unit.hitSize * 1.5f, 12f);
        int spawned = 0;

        for (int i = 0; i < 2; i++) {
            Unit child = unit.type.create(unit.team);
            if (child == null) continue;

            // 一左一右分开落点，避免两个子单位重叠。
            float angle = unit.rotation + (i == 0 ? 90f : -90f);
            child.set(
                    unit.x + Mathf.cosDeg(angle) * distance,
                    unit.y + Mathf.sinDeg(angle) * distance
            );
            child.rotation = unit.rotation;
            child.health = Mathf.clamp(unit.maxHealth * childHealthRatio, 1f, child.maxHealth);
            child.shield = 0f;

            child.add();
            // 打上「分裂产物」标记，之后抽效果时分裂整族都会被排除。
            BORN_FROM_SPLIT.put(child.id, true);
            spawned++;
        }

        // 只有至少成功生成一个子单位时才移除母体，避免单位凭空消失。
        if (spawned > 0) {
            unit.kill();
        }
    }
}
