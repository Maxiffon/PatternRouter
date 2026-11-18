package patternrouter.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import patternrouter.ServerQueue;
import patternrouter.server.CandidateSearch;

/** Client to server: find machines for the pattern in the output slot of the open pattern terminal. */
public class RequestCandidatesMessage implements IMessage {

    private static final int MAX_NON_CONSUMABLES = 16;
    private static final int MAX_MACHINE_NAME_LENGTH = 100;

    /** NEI category (overlay id) of the last recipe transferred from NEI, or empty. */
    public String category = "";
    public List<ItemStack> nonConsumables = new ArrayList<>();
    /** NEI tab title in the player's language (e.g. "Fluid Solidifier"), for matching renamed interfaces. */
    public String machineName = "";
    /** False if the packet was malformed; the server ignores it. */
    private boolean valid = true;

    public RequestCandidatesMessage() {}

    public RequestCandidatesMessage(String category, List<ItemStack> nonConsumables, String machineName) {
        this.category = category == null ? "" : category;
        this.nonConsumables = nonConsumables;
        this.machineName = machineName == null ? "" : machineName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        category = ByteBufUtils.readUTF8String(buf);
        int n = buf.readUnsignedByte();
        if (n > MAX_NON_CONSUMABLES) {
            // Not written by this mod's client: the rest can't be read reliably, drop the request.
            valid = false;
            buf.skipBytes(buf.readableBytes());
            return;
        }
        for (int i = 0; i < n; i++) {
            ItemStack s = ByteBufUtils.readItemStack(buf);
            if (s != null) nonConsumables.add(s);
        }
        machineName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, category);
        int n = Math.min(nonConsumables.size(), MAX_NON_CONSUMABLES);
        buf.writeByte(n);
        for (int i = 0; i < n; i++) ByteBufUtils.writeItemStack(buf, nonConsumables.get(i));
        ByteBufUtils.writeUTF8String(
            buf,
            machineName.length() > MAX_MACHINE_NAME_LENGTH ? machineName.substring(0, MAX_MACHINE_NAME_LENGTH)
                : machineName);
    }

    public static class Handler implements IMessageHandler<RequestCandidatesMessage, IMessage> {

        @Override
        public IMessage onMessage(RequestCandidatesMessage msg, MessageContext ctx) {
            if (!msg.valid) return null;
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ServerQueue.submitLatestRequest(
                player.getUniqueID(),
                () -> CandidateSearch.handleRequest(player, msg.category, msg.nonConsumables, msg.machineName));
            return null;
        }
    }
}
