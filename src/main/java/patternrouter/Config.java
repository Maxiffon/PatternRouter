package patternrouter;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/** config/patternrouter.cfg */
public final class Config {

    private static final String CATEGORY = "client";
    private static final String SIDE_COMMENT = "Pattern terminal side panel position: right (NEI items hide under it) or left. Can also be switched with the arrow in the panel title.";
    private static final String CUSTOM_COMMENT = "true after the panel was dragged by its title: panelX/panelY are then used instead of panelSide.";
    private static final String POS_COMMENT = "Panel position relative to the top-left corner of the terminal window (set by dragging the title).";

    /** Panel side: right of the terminal (NEI makes room) or left. */
    public static String panelSide = "right";
    /** The player dragged the panel: use panelX/panelY. */
    public static boolean panelCustom;
    /** Dragged position, relative to the terminal's guiLeft/guiTop. */
    public static int panelX, panelY;

    private static Configuration config;

    private Config() {}

    public static void load(File file) {
        config = new Configuration(file);
        panelSide = config.getString("panelSide", CATEGORY, "right", SIDE_COMMENT, new String[] { "right", "left" });
        panelCustom = config.getBoolean("panelCustom", CATEGORY, false, CUSTOM_COMMENT);
        panelX = config.getInt("panelX", CATEGORY, 0, -4096, 4096, POS_COMMENT);
        panelY = config.getInt("panelY", CATEGORY, 0, -4096, 4096, POS_COMMENT);
        if (config.hasChanged()) config.save();
    }

    /** Side arrow: back to automatic placement on that side. */
    public static void setPanelSide(String side) {
        panelSide = "left".equalsIgnoreCase(side) ? "left" : "right";
        panelCustom = false;
        if (config == null) return;
        config.get(CATEGORY, "panelSide", "right", SIDE_COMMENT)
            .set(panelSide);
        config.get(CATEGORY, "panelCustom", false, CUSTOM_COMMENT)
            .set(false);
        config.save();
    }

    /** Saves the position after the player dropped the panel. */
    public static void savePanelPos() {
        if (config == null) return;
        config.get(CATEGORY, "panelCustom", false, CUSTOM_COMMENT)
            .set(panelCustom);
        config.get(CATEGORY, "panelX", 0, POS_COMMENT)
            .set(panelX);
        config.get(CATEGORY, "panelY", 0, POS_COMMENT)
            .set(panelY);
        config.save();
    }
}
