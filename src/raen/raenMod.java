package raen;
import mindustry.mod.Mod;
import raen.Enemy.BuffManager;
import raen.Enemy.EnemyBuildingBoost;
import raen.Enemy.PVESettings;
import raen.Enemy.StatusEffectCore.SalvageEffect;
import raen.Enemy.StatusEffectCore.SharedRecoveryEffect;
import raen.Enemy.StatusEffectCore.TeamHealthEffect;
import raen.Enemy.StatusEffectCore.WingmanCleanup;
import raen.Enemy.StatusEffectCore.WingmanUnit;
import raen.Enemy.raenStatusEffects;
public class raenMod extends Mod {
    @Override
    public void loadContent() {
        raenStatusEffects.load();
        WingmanUnit.load();
    }
    @Override
    public void init() {
        PVESettings.init();
        BuffManager.init();
        WingmanCleanup.init();
        TeamHealthEffect.registerTick();
        SharedRecoveryEffect.registerTick();
        SalvageEffect.registerEvents();
        EnemyBuildingBoost.init();
    }
}