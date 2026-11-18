package raen.Bullet;

import arc.struct.Seq;
import raen.Bullet.effect.RaenEffectDH;
import raen.Bullet.effect.RaenEffectJY;
import raen.Bullet.effect.RaenEffectLS;
import raen.Bullet.effect.RaenEffectPZ;
import raen.Bullet.effect.RaenEffectSS;
import raen.Bullet.effect.RaenEffectZD;
import raen.Bullet.effect.RaenEffectZJ;
import raen.Bullet.effect.RaenEffectZM;
import raen.Bullet.effect.RaenEffectZR;

/**
 * 效果池：<b>只有显式写进 {@link #init()} 的效果，才会被光环 / 标印 / 随机主标签抽到。</b>
 * <p>
 * 为什么不用 {@link RaenEffectBase#all()}：
 * 注册表里除了「能挂到子弹上、命中就生效」的标签，还躺着一些只服务于特定玩法的内部效果
 * （例如 {@link RaenItemHit}：必须先由领域方块写入物品标签才有意义，单独挂上去等于空标签）。
 * 直接拿注册表当抽奖池，这类效果就会被随机抽到，白白稀释概率；
 * 以后再加新的内部效果也一样会漏进来。
 * <p>
 * 所以这里改成<b>白名单</b>：新效果默认进不了池子，要参与随机就必须在这一行显式登记 ——
 * 加效果时忘记登记，最多是抽不到（容易发现），而不是悄悄污染已有光环的手感（难以发现）。
 * <p>
 * 注意：这里只影响「随机抽取」。{@link RaenEffectStatus}（光环状态）与 {@link RaenApplyBlock}
 * 的主效果阶梯都是用 {@code add(...)} 显式指定的，不经过这个池子。
 */
public class RaenEffectPool {

    private static final Seq<RaenEffectBase> POOL = new Seq<>();
    private static boolean initialized = false;

    private RaenEffectPool() {
    }

    /** 建立白名单（可重复调用，只会执行一次） */
    public static void init() {
        if (initialized) return;
        initialized = true;

        // ★★★ 只有写在这里的效果才会被随机抽到。
        // 需要「必须由物品/领域触发」的效果（如 RaenItemHit）不要写进来。
        put(RaenEffectDH.INSTANCE);   // 电荷
        put(RaenEffectJY.INSTANCE);   // 积焰
        put(RaenEffectLS.INSTANCE);   // 连锁
        put(RaenEffectPZ.INSTANCE);   // 破绽
        put(RaenEffectSS.INSTANCE);   // 酸蚀
        put(RaenEffectZD.INSTANCE);   // 中毒
        put(RaenEffectZJ.INSTANCE);   // 追击
        put(RaenEffectZM.INSTANCE);   // 转命
        put(RaenEffectZR.INSTANCE);   // 注入
    }

    /** 追加一个效果到池子（运行时登记用，例如其它子模块想参与随机） */
    public static void add(RaenEffectBase effect) {
        init();
        put(effect);
    }

    /** 池子里的全部效果。只读，调用方不要修改返回的 Seq。 */
    public static Seq<RaenEffectBase> all() {
        init();
        return POOL;
    }

    /** 该效果是否在池子里（= 能否被随机抽到） */
    public static boolean contains(RaenEffectBase effect) {
        init();
        return effect != null && POOL.contains(effect, true);
    }

    private static void put(RaenEffectBase effect) {
        if (effect != null && !POOL.contains(effect, true)) POOL.add(effect);
    }
}
