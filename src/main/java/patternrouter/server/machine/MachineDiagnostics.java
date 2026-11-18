package patternrouter.server.machine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

import appeng.api.util.IInterfaceViewable;
import appeng.helpers.IInterfaceHost;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.RecipeMapWorkable;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMap;

/** Debug log text: what a provider points at. */
public final class MachineDiagnostics {

    private MachineDiagnostics() {}

    public static String describe(IInterfaceViewable view) {
        StringBuilder b = new StringBuilder();
        describe(b, view.getTileEntity(), true);
        if (view instanceof IInterfaceHost) {
            for (TileEntity n : MachineLocator.deliveryTargets(view)) {
                b.append(" | side ");
                describe(b, n, true);
            }
        }
        return b.toString();
    }

    private static void describe(StringBuilder b, TileEntity te, boolean followHatchOwner) {
        if (!(te instanceof IGregTechTileEntity gtTile)) {
            b.append(
                te == null ? "null"
                    : te.getClass()
                        .getSimpleName());
            return;
        }
        IMetaTileEntity mte = gtTile.getMetaTileEntity();
        b.append(
            mte == null ? "noMTE"
                : mte.getClass()
                    .getSimpleName());
        if (mte instanceof RecipeMapWorkable workable) {
            b.append(" maps=")
                .append(names(workable.getAvailableRecipeMaps()));
        }
        if (mte instanceof MTEMultiBlockBase multi) {
            b.append(" separation=")
                .append(multi.isInputSeparationEnabled());
        }
        if (mte instanceof MTEHatch && followHatchOwner) {
            World w = te.getWorldObj();
            ChunkCoordinates owner = HatchOwners.ownerOf(mte, w);
            b.append(" owner=")
                .append(owner == null ? "null" : owner.posX + "," + owner.posY + "," + owner.posZ);
            if (owner != null && Worlds.isLoaded(w, owner)) {
                b.append(" -> ");
                describe(b, Worlds.loadedTile(w, owner), false);
            }
        }
    }

    public static String names(Collection<RecipeMap<?>> maps) {
        List<String> names = new ArrayList<>();
        if (maps != null) for (RecipeMap<?> m : maps) names.add(m == null ? "null" : m.unlocalizedName);
        return names.toString();
    }
}
