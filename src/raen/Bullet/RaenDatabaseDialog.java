package raen.Bullet;

import arc.Core;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.DatabaseDialog;

/**
 * 核心数据库的扩展：在原有分类之外追加一个「标签」入口。
 * <p>
 * 分三段列出，顺序固定：
 * <ol>
 *   <li><b>主标签</b>：能挂到子弹、命中就生效的效果（{@link RaenEffectBase} 注册表，
 *       排除 {@link RaenItemHit} —— 它只是物品标签的命中结算入口，玩家看到的是下面的「物品标签」）；</li>
 *   <li><b>物品标签</b>：20 种物品各一条，带配色、行为文案与四属性尾注（数据来自 {@link RaenItemTags}）；</li>
 *   <li><b>四属性标签</b>：爆炸 / 燃烧 / 放射 / 放电，文案与方块详情共用 {@code raen.attr.*}，
 *       数值直接读 {@link RaenItemHit} 的字段，改参数时这里自动跟着变。</li>
 * </ol>
 */
public class RaenDatabaseDialog extends DatabaseDialog {

    /** 四属性标题配色：爆 / 燃 / 辐 / 电 */
    private static final String COLOR_EXPLODE = "FF7A45";
    private static final String COLOR_FLAMMABLE = "FFAA5F";
    private static final String COLOR_RADIOACTIVE = "7BD86A";
    private static final String COLOR_CONDUCTIVE = "4DA6FF";

    public RaenDatabaseDialog() {
        cont.row();
        cont.button(Core.bundle.get("raen.database.tags"), Icon.info, this::showTags).padTop(8f).width(140f);
    }

    private void showTags() {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("raen.database.tags"));
        dialog.cont.pane(pane -> {
            pane.top().left();

            // ① 主标签
            section(pane, Core.bundle.get("raen.database.section.mains"), null);
            Seq<RaenEffectBase> all = RaenEffectBase.all();
            for (int i = 0; i < all.size; i++) {
                RaenEffectBase effect = all.get(i);
                // 物品 / 液体处理器不是玩家标签：它们只负责结算，
                // 真正展示的是下面 20 条物品标签与 10 条液体标签
                if (effect == null || effect == RaenItemHit.INSTANCE || effect == RaenLiquidHit.INSTANCE) continue;

                RaenEffectText text = effect.text();
                if (text == null) continue;
                entry(pane, text.title(), text.body());
            }

            // ② 物品标签
            //    说明里的「N~M 种」从方块的 itemTagCaps 实时推导，改参数时文案自动跟着变
            int[] tagRange = RaenApplyBlock.itemTagLimitRange(itemTagCaps());
            section(pane, Core.bundle.get("raen.database.section.items"),
                    Core.bundle.format("raen.database.section.items.hint",
                            tagRange[0], tagRange[1]));
            Seq<RaenItemTag> items = RaenItemTags.all();
            for (int i = 0; i < items.size; i++) {
                pane.add(items.get(i).describe()).left().growX().wrap().padTop(3f);
                pane.row();
            }

            // ③ 液体标签
            //    说明里的「每 N 单位挂一次」从方块的 liquidUnitsPerTag 实时读取
            section(pane, Core.bundle.get("raen.database.section.liquids"),
                    Core.bundle.format("raen.database.section.liquids.hint",
                            liquidUnitsPerTag()));
            Seq<RaenLiquidTag> liquids = RaenLiquidTags.all();
            for (int i = 0; i < liquids.size; i++) {
                pane.add(liquids.get(i).describe()).left().growX().wrap().padTop(3f);
                pane.row();
            }

            // ④ 四属性标签
            section(pane, Core.bundle.get("raen.database.section.attrs"),
                    Core.bundle.get("raen.taghit.intro"));
            RaenItemHit hit = RaenItemHit.INSTANCE;
            attrEntry(pane, "raen.tag.attr.explosive.name", COLOR_EXPLODE,
                    Core.bundle.format("raen.attr.explode",
                            fmt(hit.explodeRadiusPerPoint), fmt(hit.explodeFlatPerPoint),
                            fmt(hit.explodePercentPerPoint * 100f)));
            attrEntry(pane, "raen.tag.attr.flammable.name", COLOR_FLAMMABLE,
                    Core.bundle.format("raen.attr.ignite",
                            fmt(hit.igniteRadiusPerPoint), fmt(hit.igniteSeconds)));
            attrEntry(pane, "raen.tag.attr.radioactive.name", COLOR_RADIOACTIVE,
                    Core.bundle.get("raen.attr.radioactive"));
            attrEntry(pane, "raen.tag.attr.conductive.name", COLOR_CONDUCTIVE,
                    Core.bundle.format("raen.attr.charge", fmt(hit.chargeTagChance)));
        }).grow();

        dialog.addCloseButton();
        dialog.show();
    }

    /**
     * 取「施加领域」方块的物品标签阶梯（供文案参数化用）。
     * <p>
     * ⚠ 不能在拿不到实例时 {@code new RaenApplyBlock(...)} ——
     * Block 的构造器会自动把方块登记进 {@code Vars.content.blocks()}，会凭空多出一个 content 条目。
     * 所以这里返回 {@code null}，由调用方走静态默认值兜底。
     */
    private Seq<RaenApplyBlock.ItemTagCap> itemTagCaps(){
        if (RaenBlocks.applyField instanceof RaenApplyBlock block) return block.itemTagCaps;
        return null;
    }

    /** 取「施加领域」方块每次挂液体标签消耗的单位数；拿不到实例时用默认值 5。 */
    private int liquidUnitsPerTag(){
        if (RaenBlocks.applyField instanceof RaenApplyBlock block) return Math.max(1, (int)block.liquidUnitsPerTag);
        return 5;
    }

    /** 一段的标题（可选副标题） */
    private void section(Table pane, String title, String hint) {
        pane.add("[lightgray]" + title + "[]").left().padTop(12f).padBottom(2f).growX();
        pane.row();

        if (hint != null && !hint.isEmpty()) {
            pane.add("[lightgray]" + hint + "[]").left().growX().wrap().padBottom(2f);
            pane.row();
        }
    }

    /** 标题 + 正文（正文可空） */
    private void entry(Table pane, String title, String body) {
        pane.add(title).left().growX().wrap().padTop(6f);
        pane.row();

        if (body != null && !body.isEmpty()) {
            pane.add(body).left().growX().wrap().padBottom(6f);
            pane.row();
        }
    }

    /** 四属性标签的一条：标题走本地化 key，正文与方块详情共用 */
    private void attrEntry(Table pane, String nameKey, String color, String body) {
        entry(pane, "[#" + color + "]" + Core.bundle.get(nameKey) + "[]", body);
    }

    /** 与 RaenItemHit 的数值格式保持一致（整数不带小数） */
    private static String fmt(float value) {
        if (value == (int) value) return String.valueOf((int) value);
        return String.format("%.2f", value);
    }
}
