package patternrouter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Side panel in the AE2 pattern terminal: put the freshly encoded pattern straight into the ME interface or CRIB
 * whose GregTech machine runs this recipe (with the right mold/circuit).
 *
 * Needed on both sides. acceptableRemoteVersions = "*": if only one side has it, players can still
 * connect; the panel just finds nothing.
 */
@Mod(
    modid = PatternRouter.MODID,
    name = PatternRouter.NAME,
    version = Tags.VERSION,
    dependencies = "required-after:appliedenergistics2;required-after:gregtech;after:NotEnoughItems",
    acceptableRemoteVersions = "*")
public class PatternRouter {

    public static final String MODID = "patternrouter";
    public static final String NAME = "Pattern Router";
    public static final Logger LOG = LogManager.getLogger(NAME);

    @SidedProxy(clientSide = "patternrouter.ClientProxy", serverSide = "patternrouter.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Config.load(event.getSuggestedConfigurationFile());
        Net.init();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
        LOG.info("{} {} loaded", NAME, Tags.VERSION);
    }
}
