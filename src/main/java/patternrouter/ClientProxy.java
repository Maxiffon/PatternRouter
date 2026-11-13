package patternrouter;

import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import patternrouter.client.ClientPanel;
import patternrouter.client.NeiCompat;

public class ClientProxy extends CommonProxy {

    private final ClientPanel panel = new ClientPanel();

    @Override
    public void init() {
        super.init();
        MinecraftForge.EVENT_BUS.register(panel);
        FMLCommonHandler.instance()
            .bus()
            .register(panel);
        if (Loader.isModLoaded("NotEnoughItems")) NeiCompat.register(panel);
    }

    @Override
    public void onCandidates(Net.CandidateList msg) {
        panel.post(() -> panel.onCandidates(msg));
    }

    @Override
    public void onResult(Net.Result msg) {
        panel.post(() -> panel.onResult(msg));
    }
}
