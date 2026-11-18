package patternrouter.net;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import patternrouter.PatternRouter;

/** Server to client: how an upload or extraction went. The codes are on the wire: never renumber. */
public class ActionResultMessage implements IMessage {

    public static final int OK = 0;
    public static final int NO_PATTERN = 1;
    /** The list is outdated (provider gone, other pattern, closed terminal): the client asks again. */
    public static final int GONE = 2;
    public static final int FULL = 3;
    public static final int DENIED = 4;
    public static final int EXTRACTED = 5;
    public static final int INVENTORY_FULL = 6;
    /** Uploaded after adding a Pattern Capacity Card. */
    public static final int OK_WITH_CARD = 7;
    /** Full, and no capacity card in inventory or network. */
    public static final int NO_CARD = 8;
    /** Full, and no more capacity cards fit. */
    public static final int MAX_CARDS = 9;

    public int code;
    /** The provider's raw name (may be empty). */
    public String providerName = "";

    public ActionResultMessage() {}

    public ActionResultMessage(int code, String providerName) {
        this.code = code;
        this.providerName = providerName == null ? "" : providerName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        code = buf.readUnsignedByte();
        providerName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(code);
        ByteBufUtils.writeUTF8String(buf, providerName);
    }

    public static class Handler implements IMessageHandler<ActionResultMessage, IMessage> {

        @Override
        public IMessage onMessage(ActionResultMessage msg, MessageContext ctx) {
            PatternRouter.proxy.onResult(msg);
            return null;
        }
    }
}
