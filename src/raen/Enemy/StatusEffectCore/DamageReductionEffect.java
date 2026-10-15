package raen.Enemy.StatusEffectCore;

import arc.math.Mathf;
import arc.struct.IntMap;
import mindustry.Vars;
import mindustry.entities.units.StatusEntry;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;

public class DamageReductionEffect extends StatusEffect {
    public float maxReduction = 0.8f;
    private final IntMap<Float> lastHealth = new IntMap<>();
    public DamageReductionEffect(String name){
        super(name);
    }
    @Override
    public void applied(Unit unit, float time, boolean extend){
        super.applied(unit, time, extend);
        if(!extend && unit != null){
            lastHealth.put(unit.id, unit.health);
        }
    }
    @Override
    public void update(Unit unit, StatusEntry entry){
        super.update(unit, entry);
        if(Vars.net.client()) return;
        if(unit == null || unit.dead() || !unit.isValid()){
            if(unit != null) lastHealth.remove(unit.id);
            return;
        }
        float reduction = Mathf.clamp(maxReduction, 0f, 0.95f);
        float prev = lastHealth.get(unit.id, Float.NaN);
        if(!Float.isNaN(prev) && unit.health < prev){
            float damage = prev - unit.health;
            float lossRatio = 1f - Mathf.clamp(unit.health / unit.maxHealth, 0f, 1f);
            unit.heal(damage * lossRatio * reduction);
        }
        lastHealth.put(unit.id, unit.health);
    }
    @Override
    public void onRemoved(Unit unit){
        if(unit != null) lastHealth.remove(unit.id);
    }
}