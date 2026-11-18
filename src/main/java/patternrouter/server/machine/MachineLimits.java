package patternrouter.server.machine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import gregtech.api.enums.GTValues;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.RecipeMapWorkable;
import gregtech.api.logic.ProcessingLogic;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import patternrouter.PatternRouter;

/**
 * Can the machine run the recipe at all: enough power (energy and laser hatches, amperage, the machine's EU discount)
 * and whatever else the machine checks itself before it starts a recipe (coil heat with its per-voltage bonus, glass
 * or casing tier, ...). The machine is asked through its own GT processing logic, so every machine applies its own
 * rules; nothing here guesses them.
 */
public final class MachineLimits {

    private static final Method SET_POWER = method(
        MTEMultiBlockBase.class,
        "setProcessingLogicPower",
        ProcessingLogic.class);
    private static final Method CREATE_LOGIC = method(MTEMultiBlockBase.class, "createProcessingLogic");
    private static final Field LOGIC = field(MTEMultiBlockBase.class, "processingLogic");
    private static final Method VALIDATE = method(ProcessingLogic.class, "validateRecipe", GTRecipe.class);
    private static final Field VOLTAGE = field(ProcessingLogic.class, "availableVoltage");
    private static final Field AMPERAGE = field(ProcessingLogic.class, "availableAmperage");
    private static final Field EU_MODIFIER = field(ProcessingLogic.class, "euModifier");
    private static final Field EU_MOD_SUPPLIER = field(ProcessingLogic.class, "euModSupplier");

    private MachineLimits() {}

    /**
     * Why a machine among these that runs the map can't run the recipe; null if all can (or none could be asked).
     * Every one must: a P2P interface hands the inputs to whichever of its outputs is free.
     */
    public static Reason whyNot(List<IMetaTileEntity> machines, RecipeMap<?> map, GTRecipe recipe) {
        if (recipe == null) return null;
        for (IMetaTileEntity mte : machines) {
            if (map != null && mte instanceof RecipeMapWorkable w
                && !w.getAvailableRecipeMaps()
                    .contains(map))
                continue;
            Reason r = check(mte, recipe.mEUt, recipe);
            if (r != null) return r;
        }
        return null;
    }

    /** Power only (the assembly line: its recipes come from data sticks, not from processing logic). */
    public static Reason whyNotPower(List<IMetaTileEntity> machines, Class<?> machineClass, long eut) {
        for (IMetaTileEntity mte : machines) {
            if (!machineClass.isInstance(mte)) continue;
            Reason r = check(mte, eut, null);
            if (r != null) return r;
        }
        return null;
    }

    private static Reason check(IMetaTileEntity mte, long eut, GTRecipe recipe) {
        try {
            if (mte instanceof MTEBasicMachine basic) {
                // A single block runs recipes up to its own voltage.
                long have = GTValues.V[Math.min(basic.mTier, GTValues.V.length - 1)];
                if (eut > have) return Reason.power(eut, have, 1);
                return recipe == null ? null : SingleBlockConditions.check(basic, recipe);
            }
            if (!(mte instanceof MTEMultiBlockBase multi)) return null;
            ProcessingLogic logic = logicOf(multi);
            long voltage, amps;
            if (logic != null) {
                if (SET_POWER != null) SET_POWER.invoke(multi, logic);
                voltage = VOLTAGE == null ? 0 : VOLTAGE.getLong(logic);
                amps = AMPERAGE == null ? 0 : AMPERAGE.getLong(logic);
            } else {
                // Machines with their own recipe check (the assembly line): average hatch voltage times amps.
                voltage = multi.getAverageInputVoltage();
                amps = multi.getMaxInputAmps();
            }
            if (voltage > 0 && amps > 0) {
                long need = (long) Math.ceil(eut * (logic == null ? 1 : euModifier(logic)));
                long have = amps > Long.MAX_VALUE / voltage ? Long.MAX_VALUE : voltage * amps;
                if (need > have) return Reason.power(need, voltage, amps);
            }
            if (logic == null || recipe == null || VALIDATE == null) return null;
            CheckRecipeResult result = (CheckRecipeResult) VALIDATE.invoke(logic, recipe);
            if (result == null || result.wasSuccessful()) return null;
            if ("insufficient_heat".equals(result.getID())) {
                int heat = heatOf(multi);
                if (heat >= 0) return Reason.heat(recipe.mSpecialValue, heat);
            }
            return Reason.machine(result);
        } catch (Throwable t) {
            // A machine that can't be asked outside its own tick: say nothing rather than something wrong.
            PatternRouter.LOG.debug("Machine limit check failed for {}: {}", mte.getClass(), t.toString());
            return null;
        }
    }

    /** The machine's own logic (its subclass overrides validateRecipe), a fresh one if it has none yet. */
    private static ProcessingLogic logicOf(MTEMultiBlockBase multi) throws Exception {
        ProcessingLogic logic = LOGIC == null ? null : (ProcessingLogic) LOGIC.get(multi);
        if (logic == null && CREATE_LOGIC != null) logic = (ProcessingLogic) CREATE_LOGIC.invoke(multi);
        return logic;
    }

    @SuppressWarnings("unchecked")
    private static double euModifier(ProcessingLogic logic) throws Exception {
        if (EU_MOD_SUPPLIER != null) {
            Supplier<Double> s = (Supplier<Double>) EU_MOD_SUPPLIER.get(logic);
            if (s != null && s.get() != null) return s.get();
        }
        double m = EU_MODIFIER == null ? 1 : EU_MODIFIER.getDouble(logic);
        return m > 0 ? m : 1;
    }

    /**
     * The heat the machine works with, as it computed it (coils plus its own bonuses, e.g. +100 K per voltage tier
     * above MV for the blast furnace); -1 if it keeps it under another name.
     */
    private static int heatOf(MTEMultiBlockBase multi) {
        Field f = HEAT_FIELDS.computeIfAbsent(multi.getClass(), MachineLimits::findHeatField);
        if (f == NO_FIELD) return -1;
        try {
            Object v = f.get(multi);
            return v instanceof Number num ? num.intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Heat field per machine class, found once ({@link #NO_FIELD} if it has none). */
    private static final java.util.Map<Class<?>, Field> HEAT_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Field NO_FIELD = field(MachineLimits.class, "noHeatField");
    /** Sentinel for {@link #NO_FIELD}: a field of our own, so the cache never depends on GT's names. */
    @SuppressWarnings("unused")
    private static int noHeatField;

    private static Field findHeatField(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName()
                    .toLowerCase();
                if (!n.contains("heatingcapacity") && !n.equals("mheat") && !n.equals("heat")) continue;
                Class<?> t = f.getType();
                if (t != int.class && t != long.class && t != short.class) continue; // a number, not a flag
                try {
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {}
            }
        }
        return NO_FIELD;
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
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

    /** Why it can't run, as text the client shows in the player's language. */
    public static final class Reason {

        /** "pr:" + chat JSON of our own message, or "gt:" + GT result id + "|" + its NBT (binary, Base64). */
        public final String wire;

        private Reason(String wire) {
            this.wire = wire;
        }

        static Reason power(long need, long voltage, long amps) {
            IChatComponent c = new ChatComponentTranslation(
                "patternrouter.why.power",
                num(need),
                tierName(need),
                num(voltage),
                tierName(voltage),
                num(amps));
            return ours(c);
        }

        private static String num(long n) {
            return String.format(java.util.Locale.ROOT, "%,d", n)
                .replace(',', ' ');
        }

        private static String tierName(long eut) {
            int t = Math.max(0, Math.min(GTUtility.getTier(eut), GTValues.VN.length - 1));
            return GTValues.VN[t];
        }

        static Reason heat(int need, int have) {
            return ours(new ChatComponentTranslation("patternrouter.why.heat", num(need), num(have)));
        }

        static Reason machine(CheckRecipeResult result) {
            return new Reason(
                "gt:" + result.getID() + "|" + encode(result.writeToNBT(new net.minecraft.nbt.NBTTagCompound())));
        }

        /** NBT as Base64 of its binary form: survives the trip exactly, unlike its text form. */
        private static String encode(net.minecraft.nbt.NBTTagCompound nbt) {
            try {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                net.minecraft.nbt.CompressedStreamTools.write(nbt, new java.io.DataOutputStream(bytes));
                return java.util.Base64.getEncoder()
                    .encodeToString(bytes.toByteArray());
            } catch (Throwable t) {
                return "";
            }
        }

        public static Reason ours(IChatComponent c) {
            return new Reason("pr:" + IChatComponent.Serializer.func_150696_a(c));
        }
    }
}
