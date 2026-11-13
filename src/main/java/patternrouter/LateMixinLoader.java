package patternrouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.Side;

/** Миксины в GT и NEI. Они поздние, потому что это обычные моды, а не coremod. */
@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.patternrouter.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> mixins = new ArrayList<>();
        if (loadedMods.contains("gregtech")) {
            // Координаты контроллера, записанные в люке (нужны для CRIB).
            mixins.add("gt.MTEHatchAccessor");
        }
        if (FMLLaunchHandler.side() == Side.CLIENT && loadedMods.contains("NotEnoughItems")) {
            // Запоминаем рецепт NEI, который переносят в терминал шаблонов.
            mixins.add("nei.RecipeHandlerRefMixin");
        }
        return mixins;
    }
}
