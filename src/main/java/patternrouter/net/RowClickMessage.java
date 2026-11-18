package patternrouter.net;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Client to server: a click on row #index of the list with this token. */
abstract class RowClickMessage implements IMessage {

    public int token;
    public int index;

    RowClickMessage() {}

    RowClickMessage(int token, int index) {
        this.token = token;
        this.index = index;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        token = buf.readInt();
        index = buf.readUnsignedByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(token);
        buf.writeByte(index);
    }
}
