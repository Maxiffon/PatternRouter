package patternrouter.client;

import java.util.List;

import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.StatCollector;

import patternrouter.common.Items;
import patternrouter.net.ProviderEntry;

/** Text of a list row: translated name, caption, flags. */
final class EntryText {

    private EntryText() {}

    /** Turns the raw name and JSON caption the server sent into text in the player's language. */
    static void localize(ProviderEntry e) {
        e.name = resolveName(e.name, e.icon);
        e.suffix = parseSuffix(e.suffix);
        if (e.suffix.isEmpty() && !e.setupItems.isEmpty()) e.suffix = setupCaption(e.setupItems);
    }

    static String displayName(ProviderEntry e) {
        return e.suffix == null || e.suffix.isEmpty() ? e.name : e.name + " " + e.suffix;
    }

    /**
     * Display name. The server sends the raw name: a player rename or the machine translation key (GT keys lack
     * ".name", e.g. gt.blockmachines.industrialextruder). If the key does not translate, use the icon item name.
     */
    static String resolveName(String raw, ItemStack icon) {
        String t = translate(raw);
        boolean looksLikeKey = t.equals(raw) && raw.indexOf(' ') < 0 && raw.indexOf('.') > 0;
        if (looksLikeKey && icon != null) {
            try {
                return icon.getDisplayName();
            } catch (Throwable ignored) {}
        }
        return t;
    }

    /** Flags line: dark colors in the list (light AE2 background), bright ones in tooltips (dark background). */
    static String flagsLine(ProviderEntry e, boolean tooltip) {
        String good = color(tooltip ? EnumChatFormatting.GREEN : EnumChatFormatting.DARK_GREEN);
        String bad = color(tooltip ? EnumChatFormatting.RED : EnumChatFormatting.DARK_RED);
        String warn = color(tooltip ? EnumChatFormatting.YELLOW : EnumChatFormatting.GOLD);
        String dim = color(tooltip ? EnumChatFormatting.GRAY : EnumChatFormatting.DARK_GRAY);
        StringBuilder sb = new StringBuilder();
        if (e.has(ProviderEntry.REMEMBERED)) flag(sb, warn, "★ ", "patternrouter.flag.remembered");
        if (e.has(ProviderEntry.SETUP_FITS)) flag(sb, good, "✔ ", "patternrouter.flag.mold");
        else if (e.has(ProviderEntry.SETUP_CONFLICTS)) flag(
            sb,
            bad,
            "✘ ",
            e.has(ProviderEntry.NO_FLUIDS) ? "patternrouter.flag.nofluid" : "patternrouter.flag.othermold");
        else if (e.has(ProviderEntry.BY_NAME)) flag(sb, warn, "≈ ", "patternrouter.flag.byname");
        else if (e.has(ProviderEntry.RECIPE_MATCH)) flag(sb, good, "✔ ", "patternrouter.flag.machine");
        if (e.has(ProviderEntry.HAS_SAME_OUTPUT)) flag(sb, warn, "", "patternrouter.flag.same");
        if (e.freeSlots == 0) sb.append(bad)
            .append(I18n.format("patternrouter.flag.full"));
        else sb.append(dim)
            .append(I18n.format(tooltip ? "patternrouter.flag.free.long" : "patternrouter.flag.free", e.freeSlots));
        return sb.toString();
    }

    private static void flag(StringBuilder sb, String color, String symbol, String key) {
        sb.append(color)
            .append(symbol)
            .append(I18n.format(key))
            .append("  ");
    }

    private static String color(EnumChatFormatting format) {
        return format.toString();
    }

    /**
     * "{[5], Mold (Ingot)}" from the items the machine is set up with. Names come from the item itself, as in
     * tooltips: GT translates its items through its own language manager, not plain translation keys.
     */
    private static String setupCaption(List<ItemStack> setupItems) {
        StringBuilder b = new StringBuilder("{");
        for (ItemStack st : setupItems) {
            if (b.length() > 1) b.append(", ");
            String n;
            try {
                n = Items.isProgrammedCircuit(st) ? "[" + st.getItemDamage() + "]" : st.getDisplayName();
            } catch (Throwable t) {
                n = "?";
            }
            b.append(n);
        }
        return b.append("}")
            .toString();
    }

    private static String translate(String raw) {
        if (raw == null) return "";
        if (StatCollector.canTranslate(raw)) return StatCollector.translateToLocal(raw);
        if (StatCollector.canTranslate(raw + ".name")) return StatCollector.translateToLocal(raw + ".name");
        return raw;
    }

    /** The CRIB caption (mold, circuit, number) arrives as JSON and is translated client-side. */
    private static String parseSuffix(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            IChatComponent c = IChatComponent.Serializer.func_150699_a(json);
            return c == null ? ""
                : c.getFormattedText()
                    .trim();
        } catch (Throwable t) {
            return json;
        }
    }
}
