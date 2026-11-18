package patternrouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.Side;

/** Mixin into NEI (late, NEI is a regular mod). Optional: if it fails, the panel still works without NEI context. */
@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.patternrouter.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        List<String> mixins = new ArrayList<>();
        if (FMLLaunchHandler.side() == Side.CLIENT && loadedMods.contains("NotEnoughItems")) {
            // Remember the NEI recipe transferred into the pattern terminal.
            mixins.add("nei.RecipeHandlerRefMixin");
        }
        if (FMLLaunchHandler.side() == Side.CLIENT && loadedMods.contains("appliedenergistics2")) {
            // Mouse wheel over the side panel scrolls its list instead of the terminal grid.
            mixins.add("ae2.AEBaseGuiWheelMixin");
        }
        return mixins;
    }
}
