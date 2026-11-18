package patternrouter.net;

import java.util.ArrayList;
import java.util.List;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import patternrouter.PatternRouter;
import patternrouter.common.ListNote;

/** Server to client: the providers that fit the pattern, and existing patterns with the same output. */
public class CandidateListMessage implements IMessage {

    private static final int MAX_ROWS = 255;

    /** Identifies this list in upload and extract clicks. */
    public int token;
    /** {@link ListNote} by ordinal. */
    public int note;
    public List<ProviderEntry> entries = new ArrayList<>();
    /** Existing patterns with the same output (where they are). */
    public List<ProviderEntry> conflicts = new ArrayList<>();

    @Override
    public void fromBytes(ByteBuf buf) {
        token = buf.readInt();
        note = buf.readUnsignedByte();
        readEntries(buf, entries);
        readEntries(buf, conflicts);
        // Added in 1.4 after everything older clients read; an older server sends nothing here.
        if (buf.isReadable()) {
            int n = buf.readUnsignedByte();
            for (int i = 0; i < n && buf.isReadable(); i++) {
                boolean takesCard = buf.readBoolean();
                if (i < entries.size()) entries.get(i).takesCard = takesCard;
            }
        }
        // Added after takesCard: why a row doesn't fit.
        if (buf.isReadable()) {
            for (ProviderEntry e : entries) e.reasonsSent = true;
            int n = buf.readUnsignedByte();
            for (int i = 0; i < n && buf.isReadable(); i++) {
                String reason = ByteBufUtils.readUTF8String(buf);
                if (i < entries.size()) entries.get(i).reason = reason;
            }
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(token);
        buf.writeByte(note);
        writeEntries(buf, entries);
        writeEntries(buf, conflicts);
        int n = Math.min(entries.size(), MAX_ROWS);
        buf.writeByte(n);
        for (int i = 0; i < n; i++) buf.writeBoolean(entries.get(i).takesCard);
        buf.writeByte(n);
        for (int i = 0; i < n; i++) {
            String r = entries.get(i).reason;
            ByteBufUtils.writeUTF8String(buf, r == null ? "" : r);
        }
    }

    private static void readEntries(ByteBuf buf, List<ProviderEntry> list) {
        int n = buf.readUnsignedByte();
        for (int i = 0; i < n; i++) list.add(ProviderEntry.read(buf));
    }

    private static void writeEntries(ByteBuf buf, List<ProviderEntry> list) {
        int n = Math.min(list.size(), MAX_ROWS);
        buf.writeByte(n);
        for (int i = 0; i < n; i++) list.get(i)
            .write(buf);
    }

    public static class Handler implements IMessageHandler<CandidateListMessage, IMessage> {

        @Override
        public IMessage onMessage(CandidateListMessage msg, MessageContext ctx) {
            PatternRouter.proxy.onCandidates(msg);
            return null;
        }
    }
}
