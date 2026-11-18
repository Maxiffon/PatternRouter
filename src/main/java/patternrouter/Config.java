package patternrouter;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/** config/patternrouter.cfg */
public final class Config {

    private static final String CATEGORY = "client";
    private static final String SIDE_COMMENT = "Pattern terminal side panel position: right (NEI items hide under it) or left. Can also be switched with the arrow in the panel title.";
    private static final String CUSTOM_COMMENT = "true after the panel was dragged by its title: panelX/panelY are then used instead of panelSide.";
    private static final String HIDE_FULL_COMMENT = "Hide interfaces without a free pattern slot. Can also be switched with the button in the panel title.";
    private static final String ROWS_COMMENT = "Interfaces shown in the panel list at once. Can also be changed by dragging the bottom-right corner of the panel.";
    private static final String POS_COMMENT = "Panel position relative to the top-left corner of the terminal window (set by dragging the title).";

    /** Panel side: right of the terminal (NEI makes room) or left. */
    public static String panelSide = "right";
    /** The player dragged the panel: use panelX/panelY. */
    public static boolean panelCustom;
    /** Dragged position, relative to the terminal's guiLeft/guiTop. */
    public static int panelX, panelY;
    /** Hide providers without a free pattern slot (toggle in the panel title). */
    public static boolean hideFull;
    /** Rows in the panel list (resize grip). */
    public static int panelRows = 5;
    /** Panel folded by its button in AE2's column. */
    public static boolean panelHidden;
    private static final String HIDDEN_COMMENT = "Panel folded away. Switched by the pattern button in the AE2 button column left of the terminal.";

    private static Configuration config;

    private Config() {}

    public static void load(File file) {
        config = new Configuration(file);
        panelSide = config.getString("panelSide", CATEGORY, "right", SIDE_COMMENT, new String[] { "right", "left" });
        panelCustom = config.getBoolean("panelCustom", CATEGORY, false, CUSTOM_COMMENT);
        panelX = config.getInt("panelX", CATEGORY, 0, -4096, 4096, POS_COMMENT);
        panelY = config.getInt("panelY", CATEGORY, 0, -4096, 4096, POS_COMMENT);
        hideFull = config.getBoolean("hideFull", CATEGORY, false, HIDE_FULL_COMMENT);
        panelHidden = config.getBoolean("panelHidden", CATEGORY, false, HIDDEN_COMMENT);
        panelRows = config.getInt("panelRows", CATEGORY, 5, 2, 20, ROWS_COMMENT);
        if (config.hasChanged()) config.save();
    }

    public static void setPanelHidden(boolean hidden) {
        panelHidden = hidden;
        if (config == null) return;
        config.get(CATEGORY, "panelHidden", false, HIDDEN_COMMENT)
            .set(hidden);
        config.save();
    }

    public static void setPanelRows(int rows) {
        panelRows = rows;
        if (config == null) return;
        config.get(CATEGORY, "panelRows", 5, ROWS_COMMENT)
            .set(rows);
        config.save();
    }

    public static void setHideFull(boolean hide) {
        hideFull = hide;
        if (config == null) return;
        config.get(CATEGORY, "hideFull", false, HIDE_FULL_COMMENT)
            .set(hide);
        config.save();
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
