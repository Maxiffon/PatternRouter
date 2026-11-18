package patternrouter.net;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * The mod's network channel. v4: entries carry positions and setup items, the request the NEI tab name. Client and
 * server with different channel names just don't talk. The ids below are on the wire: never renumber.
 */
public final class PatternRouterNetwork {

    private static final String CHANNEL_NAME = "patrouter4";

    public static SimpleNetworkWrapper channel;

    private PatternRouterNetwork() {}

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL_NAME);
        channel.registerMessage(RequestCandidatesMessage.Handler.class, RequestCandidatesMessage.class, 0, Side.SERVER);
        channel.registerMessage(UploadPatternMessage.Handler.class, UploadPatternMessage.class, 1, Side.SERVER);
        channel.registerMessage(CandidateListMessage.Handler.class, CandidateListMessage.class, 2, Side.CLIENT);
        channel.registerMessage(ActionResultMessage.Handler.class, ActionResultMessage.class, 3, Side.CLIENT);
        channel.registerMessage(ExtractConflictMessage.Handler.class, ExtractConflictMessage.class, 4, Side.SERVER);
    }
}
