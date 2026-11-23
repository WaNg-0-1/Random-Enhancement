package raen;
import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType;
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
import raen.Bullet.RaenBlocks;
import raen.Bullet.RaenDatabaseDialog;
import raen.Bullet.RaenEffect;
import raen.Bullet.RaenEffectBase;
import raen.Bullet.RaenEffectPool;
import raen.Bullet.RaenItemHit;
import raen.Bullet.RaenLiquidHit;
import raen.Bullet.effect.RaenEffectDH;
import raen.Bullet.effect.RaenEffectJY;
import raen.Bullet.effect.RaenEffectLS;
import raen.Bullet.effect.RaenEffectPZ;
import raen.Bullet.effect.RaenEffectSS;
import raen.Bullet.effect.RaenEffectZD;
import raen.Bullet.effect.RaenEffectZM;
import raen.Bullet.effect.RaenEffectZJ;
import raen.Bullet.effect.RaenEffectZR;
public class raenMod extends Mod {
    @Override
    public void loadContent() {
        raenStatusEffects.load();
        WingmanUnit.load();
        RaenBlocks.load();
    }
    @Override
    public void init() {
        // 子弹标签 / 效果系统：注册事件分发（可重复调用，只会执行一次）。
        RaenEffectBase.init();
        // 强制加载所有标签效果：register() 只写在各自的静态块里，不引用就一个都不会注册，
        // 标印和领域方块会拿不到任何标签（表现为光环照画、子弹却没效果）。
        RaenEffect[] tags = {
                RaenEffectDH.INSTANCE, RaenEffectJY.INSTANCE, RaenEffectPZ.INSTANCE,
                RaenEffectSS.INSTANCE, RaenEffectZD.INSTANCE, RaenEffectZM.INSTANCE,
                RaenEffectZR.INSTANCE, RaenEffectLS.INSTANCE, RaenEffectZJ.INSTANCE,
                RaenItemHit.INSTANCE,
                // 液体标签的命中处理器：同样要在这里拉起来，否则静态块不执行 → 不会被注册 → 命中不结算
                RaenLiquidHit.INSTANCE};
        for (RaenEffect effect : tags) {
            if (effect == null) throw new IllegalStateException("RaenEffect 静态注册失败");
        }
        // 建立「随机抽取白名单」：只有写进 RaenEffectPool.init() 的效果才会被光环 / 标印抽到。
        // 放在这里是为了启动期就把所有标签效果拉起来，缺登记能在日志里立刻看到。
        RaenEffectPool.init();
        PVESettings.init();
        BuffManager.init();
        WingmanCleanup.init();
        TeamHealthEffect.registerTick();
        SharedRecoveryEffect.registerTick();
        SalvageEffect.registerEvents();
        // 残影：寿命推进 / 消散回血 / 存档前清场（残影身上没有本效果，只能走全局钩子）
        raen.Enemy.StatusEffectCore.AfterimageEffect.registerHooks();
        EnemyBuildingBoost.init();

        // 世界加载：清掉按单位 id 记录的运行时状态（id 会重用，不清会串到新单位上）
        Events.on(EventType.WorldLoadEvent.class, e -> {
            raen.Enemy.StatusEffectCore.OffenseEffect.clearAll();
            raen.Enemy.StatusEffectCore.BloodbathEffect.clearAll();
        });

        // 核心数据库：换成带「标签」分类的版本（等客户端 UI 就绪后再换）
        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (Vars.ui != null && Vars.ui.database != null) {
                Vars.ui.database = new RaenDatabaseDialog();
            }
        });
    }
}
/*

git tag v160.4.03
git push origin v160.4.03

 */