package patternrouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.Side;

/** Mixins into GT and NEI. Late, because both are regular mods, not coremods. */
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
            // Controller coordinates stored in the hatch (needed for CRIB).
            mixins.add("gt.MTEHatchAccessor");
        }
        if (FMLLaunchHandler.side() == Side.CLIENT && loadedMods.contains("NotEnoughItems")) {
            // Remember the NEI recipe transferred into the pattern terminal.
            mixins.add("nei.RecipeHandlerRefMixin");
        }
        return mixins;
    }
}
