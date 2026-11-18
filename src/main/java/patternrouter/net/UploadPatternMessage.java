package patternrouter.net;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import patternrouter.ServerQueue;
import patternrouter.server.PatternPlacement;

/** Client to server: put the terminal's pattern into candidate #index of list #token. */
public class UploadPatternMessage extends RowClickMessage {

    public UploadPatternMessage() {}

    public UploadPatternMessage(int token, int index) {
        super(token, index);
    }

    public static class Handler implements IMessageHandler<UploadPatternMessage, IMessage> {

        @Override
        public IMessage onMessage(UploadPatternMessage msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ServerQueue.submitAction(
                player.getUniqueID(),
                "upload",
                () -> PatternPlacement.upload(player, msg.token, msg.index));
            return null;
        }
    }
}
