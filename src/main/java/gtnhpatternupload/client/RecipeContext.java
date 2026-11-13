package gtnhpatternupload.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;

/** Последний рецепт, перенесённый из NEI: категория (у GT — тип машины), несъедаемые входы, результат. */
public final class RecipeContext {

    /** Сколько помним рецепт: шаблон обычно кодируют сразу после переноса. */
    private static final long TTL_MS = 30L * 60 * 1000;

    public static String category = "";
    public static List<ItemStack> nonConsumables = Collections.emptyList();
    public static ItemStack result;
    public static long time;

    private RecipeContext() {}

    public static void capture(IRecipeHandler handler, int recipeIndex) {
        try {
            String id = handler.getOverlayIdentifier();
            List<ItemStack> nc = new ArrayList<>();
            List<PositionedStack> ingredients = handler.getIngredientStacks(recipeIndex);
            if (ingredients != null) {
                for (PositionedStack ps : ingredients) {
                    // В NEI у GregTech несъедаемые входы (формы, схемы, линзы) показаны с количеством 0.
                    if (ps != null && ps.item != null && ps.item.stackSize == 0) {
                        ItemStack c = ps.item.copy();
                        c.stackSize = 1;
                        nc.add(c);
                    }
                }
            }
            PositionedStack r = handler.getResultStack(recipeIndex);
            category = id == null ? "" : id;
            nonConsumables = nc;
            result = r == null || r.item == null ? null : r.item.copy();
            time = System.currentTimeMillis();
        } catch (Throwable t) {
            category = "";
            nonConsumables = Collections.emptyList();
            result = null;
        }
    }

    /** Рецепт свежий и его результат совпадает с выходом шаблона (иначе шаблон кодировали вручную). */
    public static boolean appliesTo(ItemStack patternOutput) {
        if (time == 0 || System.currentTimeMillis() - time > TTL_MS) return false;
        if (result == null || patternOutput == null) return true;
        return result.getItem() == patternOutput.getItem()
            && (result.getItemDamage() == patternOutput.getItemDamage() || result.getItemDamage() == 32767);
    }
}
