package patternrouter.net;

import net.minecraft.entity.player.EntityPlayerMP;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import patternrouter.ServerQueue;
import patternrouter.server.PatternPlacement;

/** Client to server: move the old pattern with the same output (conflict #index) into the player's inventory. */
public class ExtractConflictMessage extends RowClickMessage {

    public ExtractConflictMessage() {}

    public ExtractConflictMessage(int token, int index) {
        super(token, index);
    }

    public static class Handler implements IMessageHandler<ExtractConflictMessage, IMessage> {

        @Override
        public IMessage onMessage(ExtractConflictMessage msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ServerQueue.submitAction(
                player.getUniqueID(),
                "extract",
                () -> PatternPlacement.extract(player, msg.token, msg.index));
            return null;
        }
    }
}
