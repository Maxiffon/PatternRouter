package patternrouter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * Боковая панель в терминале шаблонов AE2: закодированный шаблон можно сразу положить в ME-интерфейс или CRIB,
 * чья машина GregTech делает этот рецепт (и в которой лежит нужная форма).
 *
 * Нужен и клиенту, и серверу. acceptableRemoteVersions = "*": если мод есть только с одной стороны, игрок всё равно
 * подключается, просто панель ничего не найдёт.
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
