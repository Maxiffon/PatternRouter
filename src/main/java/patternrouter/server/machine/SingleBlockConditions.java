package patternrouter.server.machine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.util.ChatComponentTranslation;

import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.util.GTRecipe;

/**
 * What a single-block GT machine checks before it starts a recipe besides voltage (GT's MTEBasicMachine.checkRecipe):
 * compression recipes only the multiblock compressor runs, low-gravity recipes need a space dimension, cleanroom
 * recipes need a formed, clean cleanroom around the machine. Looked up by name: GT versions differ in these fields.
 */
final class SingleBlockConditions {

    /** GT's recipe special values: low gravity, cleanroom, both. */
    private static final int LOW_GRAVITY = -100, CLEANROOM = -200, BOTH = -300;

    private static final Object COMPRESSION_TIER = staticField(
        "gregtech.api.util.GTRecipeConstants",
        "COMPRESSION_TIER");
    private static final Method METADATA = method(GTRecipe.class, "getMetadataOrDefault", 2);
    private static final Method LOW_GRAVITY_OK = method(MTEBasicMachine.class, "isValidForLowGravity", 2);
    private static final Field CLEANROOM_REF = field(
        gregtech.api.metatileentity.MetaTileEntity.class,
        "cleanroomReference");

    private SingleBlockConditions() {}

    static MachineLimits.Reason check(MTEBasicMachine machine, GTRecipe recipe) {
        if (compressionTier(recipe) > 0) return why("patternrouter.why.compression");
        int special = recipe.mSpecialValue;
        if ((special == LOW_GRAVITY || special == BOTH) && lowGravityProcessing() && !inLowGravity(machine, recipe)) {
            return why("patternrouter.why.lowgravity");
        }
        if (special == CLEANROOM || special == BOTH) {
            Object room = cleanroom(machine);
            if (room == UNKNOWN) return null;
            if (room == null) return why("patternrouter.why.cleanroom");
            gregtech.api.interfaces.ICleanroom c = (gregtech.api.interfaces.ICleanroom) room;
            if (!c.isValidCleanroom()) return why("patternrouter.why.cleanroom.broken");
            if (c.getCleanness() == 0) return why("patternrouter.why.cleanroom.dirty");
        }
        return null;
    }

    private static MachineLimits.Reason why(String key) {
        return MachineLimits.Reason.ours(new ChatComponentTranslation(key));
    }

    private static int compressionTier(GTRecipe recipe) {
        if (COMPRESSION_TIER == null || METADATA == null) return 0;
        try {
            Object v = METADATA.invoke(recipe, COMPRESSION_TIER, 0);
            return v instanceof Number n ? n.intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** GT's config: low-gravity recipes only in space (off in some packs). */
    private static boolean lowGravityProcessing() {
        try {
            Object proxy = Class.forName("gregtech.GTMod")
                .getField("proxy")
                .get(null);
            Field f = proxy.getClass()
                .getField("mLowGravProcessing");
            return f.getBoolean(proxy);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean inLowGravity(MTEBasicMachine machine, GTRecipe recipe) {
        if (LOW_GRAVITY_OK == null) return true;
        try {
            int dim = machine.getBaseMetaTileEntity()
                .getWorld().provider.dimensionId;
            return (Boolean) LOW_GRAVITY_OK.invoke(null, recipe, dim);
        } catch (Throwable t) {
            return true;
        }
    }

    private static final Object UNKNOWN = new Object();

    /** The cleanroom the machine is in, null if none, UNKNOWN if this GT keeps it elsewhere. */
    private static Object cleanroom(MTEBasicMachine machine) {
        try {
            if (CLEANROOM_REF != null) {
                Object ref = CLEANROOM_REF.get(machine);
                if (ref instanceof gregtech.api.interfaces.ICleanroomReceiver receiver) return receiver.getCleanroom();
            }
            Method get = machine.getClass()
                .getMethod("getCleanroom");
            return get.invoke(machine);
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }

    private static Object staticField(String className, String name) {
        try {
            return Class.forName(className)
                .getField(name)
                .get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, int params) {
        for (Method m : owner.getMethods()) {
            if (m.getName()
                .equals(name) && m.getParameterTypes().length == params) return m;
        }
        return null;
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }
}
