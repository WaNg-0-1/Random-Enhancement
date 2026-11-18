package raen.Bullet;

import mindustry.gen.Bullet;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 子弹标签工具（全 mod 共用的通用接口）
 * <p>
 *
 * <p>
 * 约定：
 * <ul>
 *   <li>所有标签统一存放在 {@code bullet.data} 的 {@code Set<String>} 里；</li>
 *   <li>支持「标签名」与「标签名*层数」两种写法：层数 ≤1 时只写标签名，
 *       因此读取时两种写法都能识别；</li>
 *   <li>{@link #copyBulletMarks} 会把这些标签一起复制给新子弹，
 *       所以分裂 / 折射类方块生成的新子弹天然继承全部标签，
 *       不会因为多个领域重叠而反复处理同一颗子弹（防套娃卡死）；</li>
 *   <li><b>绝不覆盖别人的 {@code bullet.data}</b>：如果有方块把 {@code bullet.data}
 *       当作自己的去重标记（例如用 {@code bullet.data == this} 判断），
 *       直接覆盖会让它每帧都重复处理同一颗子弹并造成无限循环。
 *       遇到这种情况本类会放弃写入标签（宁可不标记，也不破坏别人的去重标记）。
 *       这类方块请改用 {@link #addBulletMark(Bullet, String)} / {@link #hasBulletMark(Bullet, String)}。</li>
 * </ul>
 */
public class RaenBulletMarks {

    /** 标签与层数之间的分隔符：标签名*层数 */
    public static final String STACK_SEPARATOR = "*";

    /** 给子弹追加标签（若已存在同名标签则不重复添加、也不降级层数） */
    public static void addBulletMark(Bullet bullet, String mark) {
        if (bullet == null || mark == null || mark.isEmpty()) return;

        Set<String> tags = writableMarks(bullet);
        if (tags == null) return;
        if (findIn(tags, mark) <= 0) {
            tags.add(mark);
        }
    }

    /**
     * 给子弹追加「带层数」的标签：层数 ≤1 时只写标签名。
     * 会覆盖同名标签原有的层数。
     */
    public static void addBulletMark(Bullet bullet, String mark, int stacks) {
        if (bullet == null || mark == null || mark.isEmpty() || stacks <= 0) return;

        Set<String> tags = writableMarks(bullet);
        if (tags == null) return;

        removeBulletMark(tags, mark);
        tags.add(stacks <= 1 ? mark : mark + STACK_SEPARATOR + stacks);
    }

    /** 读取子弹上指定标签的层数，0 表示没有该标签 */
    public static int getBulletMark(Bullet bullet, String mark) {
        return bullet == null ? 0 : findIn(bullet.data, mark);
    }

    /** 检查子弹是否携带指定标签（「标签名」与「标签名*层数」都算携带） */
    public static boolean hasBulletMark(Bullet bullet, String mark) {
        return getBulletMark(bullet, mark) > 0;
    }

    /** 移除指定标签（含「标签名*层数」形式） */
    public static void removeBulletMark(Bullet bullet, String mark) {
        if (bullet == null || mark == null || mark.isEmpty()) return;

        Object data = bullet.data;
        if (data instanceof Set) {
            @SuppressWarnings("unchecked")
            Set<String> tags = (Set<String>) data;
            removeBulletMark(tags, mark);
            return;
        }
        if (data instanceof String && match((String) data, mark) > 0) {
            bullet.data = null;
        }
    }

    /** 将源子弹的所有标签深拷贝到目标子弹 */
    @SuppressWarnings("unchecked")
    public static void copyBulletMarks(Bullet from, Bullet to) {
        if (from == null || to == null || from == to) return;

        Set<String> source = null;
        if (from.data instanceof Set) {
            source = (Set<String>) from.data;
        } else if (from.data instanceof String && !((String) from.data).isEmpty()) {
            source = new HashSet<>();
            source.add((String) from.data);
        }

        if (source == null || source.isEmpty()) {
            // 源子弹没有标签：清掉目标上的残留（子弹是池化复用的，可能带着上一颗的标签）
            if (to.data instanceof Set || to.data instanceof String) {
                to.data = null;
            }
            return;
        }

        // 目标 data 被别的方块占用时不动它，避免破坏别人的去重标记
        if (to.data == null || to.data instanceof Set || to.data instanceof String) {
            to.data = new HashSet<>(source);
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 取得可写的标签集合。
     *
     * @return null 表示 {@code bullet.data} 已被其它方块占用，放弃写入
     */
    @SuppressWarnings("unchecked")
    private static Set<String> writableMarks(Bullet bullet) {
        Object data = bullet.data;
        if (data instanceof Set) {
            return (Set<String>) data;
        }
        if (data == null || data instanceof String) {
            Set<String> tags = new HashSet<>();
            if (data instanceof String && !((String) data).isEmpty()) {
                tags.add((String) data);
            }
            bullet.data = tags;
            return tags;
        }
        // bullet.data 是别的方块的去重标记，不能覆盖
        return null;
    }

    /** 从任意 data（Set / String）中查出标签层数 */
    private static int findIn(Object data, String mark) {
        if (data == null || mark == null || mark.isEmpty()) return 0;

        if (data instanceof Iterable) {
            for (Object raw : (Iterable<?>) data) {
                if (!(raw instanceof String)) continue;
                int value = match((String) raw, mark);
                if (value > 0) return value;
            }
            return 0;
        }
        if (data instanceof String) {
            return match((String) data, mark);
        }
        return 0;
    }

    /** 比较单个标签字符串：完全相等 → 1，mark*层数 → 层数，否则 0 */
    private static int match(String raw, String mark) {
        if (raw == null || raw.isEmpty() || mark == null || mark.isEmpty()) return 0;
        if (raw.equals(mark)) return 1;

        if (raw.length() > mark.length() + STACK_SEPARATOR.length()
                && raw.startsWith(mark)
                && raw.charAt(mark.length()) == STACK_SEPARATOR.charAt(0)) {
            try {
                int value = Integer.parseInt(raw.substring(mark.length() + STACK_SEPARATOR.length()));
                if (value > 0) return value;
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static void removeBulletMark(Set<String> tags, String mark) {
        if (tags == null || tags.isEmpty()) return;

        Iterator<String> iterator = tags.iterator();
        while (iterator.hasNext()) {
            if (match(iterator.next(), mark) > 0) {
                iterator.remove();
            }
        }
    }
}
