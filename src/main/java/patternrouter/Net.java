package patternrouter;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import patternrouter.server.Candidates;

/** Сетевые сообщения: запрос списка, список, загрузка, результат. */
public final class Net {

    public static SimpleNetworkWrapper CH;

    private Net() {}

    public static void init() {
        CH = NetworkRegistry.INSTANCE.newSimpleChannel("patrouter");
        CH.registerMessage(Request.Handler.class, Request.class, 0, Side.SERVER);
        CH.registerMessage(Upload.Handler.class, Upload.class, 1, Side.SERVER);
        CH.registerMessage(CandidateList.Handler.class, CandidateList.class, 2, Side.CLIENT);
        CH.registerMessage(Result.Handler.class, Result.class, 3, Side.CLIENT);
        CH.registerMessage(ExtractOld.Handler.class, ExtractOld.class, 4, Side.SERVER);
    }

    /** Вынуть старый шаблон на тот же результат (конфликт номер index) в инвентарь игрока. */
    public static class ExtractOld implements IMessage {

        public int token;
        public int index;

        public ExtractOld() {}

        public ExtractOld(int token, int index) {
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

        public static class Handler implements IMessageHandler<ExtractOld, IMessage> {

            @Override
            public IMessage onMessage(ExtractOld msg, MessageContext ctx) {
                EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                CommonProxy.runOnServer(() -> Candidates.handleExtract(player, msg.token, msg.index));
                return null;
            }
        }
    }

    // ---------- клиент → сервер ----------

    /** Найти машины для шаблона в выходном слоте открытого терминала шаблонов. */
    public static class Request implements IMessage {

        public String category = "";
        public List<ItemStack> nonConsumables = new ArrayList<>();

        public Request() {}

        public Request(String category, List<ItemStack> nonConsumables) {
            this.category = category == null ? "" : category;
            this.nonConsumables = nonConsumables;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            category = ByteBufUtils.readUTF8String(buf);
            int n = Math.min(buf.readUnsignedByte(), 16);
            for (int i = 0; i < n; i++) {
                ItemStack s = ByteBufUtils.readItemStack(buf);
                if (s != null) nonConsumables.add(s);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            ByteBufUtils.writeUTF8String(buf, category);
            int n = Math.min(nonConsumables.size(), 16);
            buf.writeByte(n);
            for (int i = 0; i < n; i++) ByteBufUtils.writeItemStack(buf, nonConsumables.get(i));
        }

        public static class Handler implements IMessageHandler<Request, IMessage> {

            @Override
            public IMessage onMessage(Request msg, MessageContext ctx) {
                EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                CommonProxy.runOnServer(() -> Candidates.handleRequest(player, msg.category, msg.nonConsumables));
                return null;
            }
        }
    }

    /** Положить шаблон в кандидата с номером index из списка token. */
    public static class Upload implements IMessage {

        public int token;
        public int index;

        public Upload() {}

        public Upload(int token, int index) {
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

        public static class Handler implements IMessageHandler<Upload, IMessage> {

            @Override
            public IMessage onMessage(Upload msg, MessageContext ctx) {
                EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                CommonProxy.runOnServer(() -> Candidates.handleUpload(player, msg.token, msg.index));
                return null;
            }
        }
    }

    // ---------- сервер → клиент ----------

    /** Одна строка списка. */
    public static class Entry {

        public static final int RECIPE_MATCH = 1; // машина делает рецепты этой категории
        public static final int MOLD_MATCH = 2; // нужные формы/схемы лежат в машине
        public static final int MOLD_MISMATCH = 4; // машина та, но формы не те
        public static final int HAS_SAME = 8; // уже есть шаблон на тот же результат

        public ItemStack icon;
        public String name = "";
        public String suffix = "";
        public int free;
        public int flags;
    }

    public static class CandidateList implements IMessage {

        public int token;
        /** 0 — всё хорошо; иначе код причины пустого списка (см. Candidates.NOTE_*). */
        public int note;
        public List<Entry> entries = new ArrayList<>();
        /** Уже существующие шаблоны на тот же результат (где лежат). */
        public List<Entry> conflicts = new ArrayList<>();

        @Override
        public void fromBytes(ByteBuf buf) {
            token = buf.readInt();
            note = buf.readUnsignedByte();
            readEntries(buf, entries);
            readEntries(buf, conflicts);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(token);
            buf.writeByte(note);
            writeEntries(buf, entries);
            writeEntries(buf, conflicts);
        }

        private static void readEntries(ByteBuf buf, List<Entry> list) {
            int n = buf.readUnsignedByte();
            for (int i = 0; i < n; i++) {
                Entry e = new Entry();
                e.icon = ByteBufUtils.readItemStack(buf);
                e.name = ByteBufUtils.readUTF8String(buf);
                e.suffix = ByteBufUtils.readUTF8String(buf);
                e.free = buf.readUnsignedShort();
                e.flags = buf.readUnsignedByte();
                list.add(e);
            }
        }

        private static void writeEntries(ByteBuf buf, List<Entry> list) {
            int n = Math.min(list.size(), 255);
            buf.writeByte(n);
            for (int i = 0; i < n; i++) {
                Entry e = list.get(i);
                ByteBufUtils.writeItemStack(buf, e.icon);
                ByteBufUtils.writeUTF8String(buf, e.name);
                ByteBufUtils.writeUTF8String(buf, e.suffix);
                buf.writeShort(Math.min(e.free, 65535));
                buf.writeByte(e.flags);
            }
        }

        public static class Handler implements IMessageHandler<CandidateList, IMessage> {

            @Override
            public IMessage onMessage(CandidateList msg, MessageContext ctx) {
                PatternRouter.proxy.onCandidates(msg);
                return null;
            }
        }
    }

    public static class Result implements IMessage {

        public static final int OK = 0;
        public static final int NO_PATTERN = 1;
        public static final int GONE = 2;
        public static final int FULL = 3;
        public static final int DENIED = 4;
        public static final int EXTRACTED = 5;
        public static final int INV_FULL = 6;

        public int code;
        public String name = "";

        public Result() {}

        public Result(int code, String name) {
            this.code = code;
            this.name = name == null ? "" : name;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            code = buf.readUnsignedByte();
            name = ByteBufUtils.readUTF8String(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(code);
            ByteBufUtils.writeUTF8String(buf, name);
        }

        public static class Handler implements IMessageHandler<Result, IMessage> {

            @Override
            public IMessage onMessage(Result msg, MessageContext ctx) {
                PatternRouter.proxy.onResult(msg);
                return null;
            }
        }
    }
}
