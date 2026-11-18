package raen.Bullet;

import arc.Core;
import arc.func.Prov;

/**
 * 文本描述类
 * <p>

 * 负责书写效果的名称、主题色与详细描述。
 * 描述支持延迟生成（{@link Prov}），因此效果内部的可调参数被修改后，
 * 详情页里显示的数值依然是最新的。
 * <p>
 * <b>本地化</b>：名称与描述都不缓存，每次取用时才查 bundle
 * （{@link #name()} 走 {@code raen.tag.<x>.name}，描述由各效果的 {@code describe()} 拼 {@code raen.tag.<x>.lN}）,
 * 所以切换语言立刻生效。走 key 请用 {@link #key(String, String, Prov)}。
 * <p>
 * 由 {@link RaenApplyBlock} 在建筑详情页（setStats）中调用：
 * {@link #detail()} 输出该效果的完整说明，{@link #title()} 输出带颜色的名称。
 */
public class RaenEffectText {

    /** 直接给出的名称（未走本地化时使用；走 key 时为 null） */
    public final String name;
    /** 名称的本地化 key（例如 {@code raen.tag.jy.name}）；非 null 时 {@link #name()} 优先用它 */
    public final String nameKey;
    /** 主题色（十六进制，不带 #），用于详情页高亮，例如：FF6B2B */
    public final String color;

    private final Prov<String> body;

    public RaenEffectText(String name, String color, Prov<String> body) {
        this(name, null, color, body);
    }

    public RaenEffectText(String name, String color, String body) {
        this(name, null, color, () -> body);
    }

    private RaenEffectText(String name, String nameKey, String color, Prov<String> body) {
        this.name = name;
        this.nameKey = nameKey;
        this.color = color;
        this.body = body;
    }

    /**
     * 以本地化 key 构造（推荐）：名称在每次取用时才查 bundle，切换语言立刻生效。
     *
     * @param nameKey 形如 {@code raen.tag.jy.name}
     */
    public static RaenEffectText key(String nameKey, String color, Prov<String> body) {
        return new RaenEffectText(null, nameKey, color, body);
    }

    /** 效果名称（有本地化 key 时从 bundle 取） */
    public String name() {
        return nameKey == null ? name : Core.bundle.get(nameKey, nameKey);
    }

    /** 主题色 */
    public String color() {
        return color;
    }

    /** 纯文本详细描述 */
    public String body() {
        String text = body == null ? null : body.get();
        return text == null ? "" : text;
    }

    /** 带颜色标签的标题，例如：[#FF6B2B]积焰[] */
    public String title() {
        return "[#" + color + "]" + name() + "[]";
    }

    /** 详情页显示的完整段落 */
    public String detail() {
        return title() + "\n" + body();
    }

    /** 一行摘要，例如：[#FF6B2B]积焰[]×1 */
    public String summary(int stacks) {
        return title() + "×" + stacks;
    }

    /**
     * 详情文案里的数值格式化，供各效果拼 {@code Core.bundle.format(...)} 的参数使用：
     * 整数不带小数点；小数按量级取位（≥1 取 2 位、&lt;1 取 5 位）并去掉尾随零。
     * <pre>
     * 1500   → "1500"
     * 0.75   → "0.75"
     * 0.0175 → "0.0175"
     * 6.1667 → "6.17"
     * </pre>
     */
    public static String num(float value) {
        if (value == (long) value) return String.valueOf((long) value);

        String text = String.format(Math.abs(value) >= 1f ? "%.2f" : "%.5f", value);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') end--;
        if (end > 0 && text.charAt(end - 1) == '.') end--;
        return text.substring(0, end);
    }
}
