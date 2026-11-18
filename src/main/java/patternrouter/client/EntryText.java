package patternrouter.client;

import java.util.List;

import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
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
        localizeReason(e);
    }

    /** Why the row doesn't fit: the server's reason, or one known from the flags. */
    private static void localizeReason(ProviderEntry e) {
        e.reasonText = "";
        e.reasonFlag = null;
        String r = e.reason == null ? "" : e.reason;
        try {
            if (r.startsWith("pr:")) {
                IChatComponent c = IChatComponent.Serializer.func_150699_a(r.substring(3));
                if (c != null) e.reasonText = c.getFormattedText();
                if (c instanceof ChatComponentTranslation t) e.reasonFlag = SHORT.get(t.getKey());
            } else if (r.startsWith("gt:")) {
                int bar = r.indexOf('|');
                String id = r.substring(3, bar < 0 ? r.length() : bar);
                gregtech.api.recipe.check.CheckRecipeResult sample = gregtech.api.recipe.check.CheckRecipeResultRegistry
                    .getSampleFromRegistry(id);
                gregtech.api.recipe.check.CheckRecipeResult result = sample.newInstance();
                if (bar >= 0) result.readFromNBT((NBTTagCompound) JsonToNBT.func_150315_a(r.substring(bar + 1)));
                e.reasonText = result.getDisplayString();
                e.reasonFlag = id.contains("heat") ? "patternrouter.flag.heat"
                    : id.contains("power") || id.contains("voltage") ? "patternrouter.flag.power"
                        : "patternrouter.flag.cantrun";
            }
        } catch (Throwable ignored) {
            // GT result unknown to this client's GT: fall back to the flags
        }
        if (e.reasonText.isEmpty() && e.has(ProviderEntry.SETUP_CONFLICTS)) {
            e.reasonText = I18n.format(
                e.has(ProviderEntry.NO_FLUIDS) ? "patternrouter.why.nofluid"
                    : e.has(ProviderEntry.OTHER_MODE) ? "patternrouter.why.othermode.any"
                        // Anything else only a server that sends reasons can tell: an older one also marks missing
                        // research this way.
                        : e.reasonsSent ? "patternrouter.why.othermold" : "patternrouter.why.oldserver");
        }
    }

    /** Server reason key: the short flag shown in the row. */
    private static final java.util.Map<String, String> SHORT = new java.util.HashMap<>();
    static {
        SHORT.put("patternrouter.why.power", "patternrouter.flag.power");
        SHORT.put("patternrouter.why.heat", "patternrouter.flag.heat");
        SHORT.put("patternrouter.why.research", "patternrouter.flag.research");
        SHORT.put("patternrouter.why.othermode", "patternrouter.flag.othermode");
        SHORT.put("patternrouter.why.cleanroom", "patternrouter.flag.cleanroom");
        SHORT.put("patternrouter.why.cleanroom.broken", "patternrouter.flag.cleanroom");
        SHORT.put("patternrouter.why.cleanroom.dirty", "patternrouter.flag.cleanroom");
        SHORT.put("patternrouter.why.lowgravity", "patternrouter.flag.lowgravity");
        SHORT.put("patternrouter.why.compression", "patternrouter.flag.compression");
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
        return flagsLine(e, tooltip, 0);
    }

    /**
     * The list row's flags line in this width: words dropped first from "remembered", then from every flag, so the free
     * slot count always shows.
     */
    static String flagsLine(ProviderEntry e, net.minecraft.client.gui.FontRenderer fr, int width) {
        for (int compact = 0; compact < 2; compact++) {
            String s = flagsLine(e, false, compact);
            if (fr.getStringWidth(s) <= width) return s;
        }
        return flagsLine(e, false, 2);
    }

    /** compact: 0 full words, 1 "remembered" as a bare star, 2 symbols only. */
    private static String flagsLine(ProviderEntry e, boolean tooltip, int compact) {
        String good = color(tooltip ? EnumChatFormatting.GREEN : EnumChatFormatting.DARK_GREEN);
        String bad = color(tooltip ? EnumChatFormatting.RED : EnumChatFormatting.DARK_RED);
        String warn = color(tooltip ? EnumChatFormatting.YELLOW : EnumChatFormatting.GOLD);
        String dim = color(tooltip ? EnumChatFormatting.GRAY : EnumChatFormatting.DARK_GRAY);
        boolean words = compact < 2;
        StringBuilder sb = new StringBuilder();
        if (e.has(ProviderEntry.REMEMBERED)) flag(sb, warn, "★", compact == 0 ? "patternrouter.flag.remembered" : null);
        if (e.has(ProviderEntry.SETUP_FITS)) flag(sb, good, "✔", words ? "patternrouter.flag.mold" : null);
        else if (e.has(ProviderEntry.SETUP_CONFLICTS)) flag(sb, bad, "✘", words ? conflictKey(e) : null);
        else if (e.has(ProviderEntry.BY_NAME)) flag(sb, warn, "≈", words ? "patternrouter.flag.byname" : null);
        else if (e.has(ProviderEntry.RECIPE_MATCH)) flag(sb, good, "✔", words ? "patternrouter.flag.machine" : null);
        if (e.has(ProviderEntry.HAS_SAME_OUTPUT)) flag(sb, warn, "≡", words ? "patternrouter.flag.same" : null);
        if (e.freeSlots == 0) sb.append(bad)
            .append(I18n.format("patternrouter.flag.full"));
        else sb.append(dim)
            .append(I18n.format(tooltip ? "patternrouter.flag.free.long" : "patternrouter.flag.free", e.freeSlots));
        return sb.toString();
    }

    private static String conflictKey(ProviderEntry e) {
        if (e.reasonFlag != null) return e.reasonFlag;
        if (e.has(ProviderEntry.NO_FLUIDS)) return "patternrouter.flag.nofluid";
        if (e.has(ProviderEntry.OTHER_MODE)) return "patternrouter.flag.othermode";
        return "patternrouter.flag.othermold";
    }

    /** A flag: symbol and words, or the bare symbol when key is null. "Same output" has no symbol of its own. */
    private static void flag(StringBuilder sb, String color, String symbol, String key) {
        sb.append(color);
        if (key == null) {
            sb.append(symbol)
                .append(' ');
            return;
        }
        if (!symbol.equals("≡")) sb.append(symbol)
            .append(' ');
        sb.append(I18n.format(key))
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
