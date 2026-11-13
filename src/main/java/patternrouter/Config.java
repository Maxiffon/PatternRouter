package patternrouter;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/** config/patternrouter.cfg */
public final class Config {

    private static final String SIDE_KEY = "panelSide";
    private static final String SIDE_CATEGORY = "client";
    private static final String SIDE_COMMENT = "Pattern terminal side panel position: right (NEI items hide under it) or left. Can also be switched with the arrow in the panel title.";

    /** Panel side: right of the terminal (NEI makes room) or left. */
    public static String panelSide = "right";

    private static Configuration config;

    private Config() {}

    public static void load(File file) {
        config = new Configuration(file);
        panelSide = config.getString(SIDE_KEY, SIDE_CATEGORY, "right", SIDE_COMMENT, new String[] { "right", "left" });
        if (config.hasChanged()) config.save();
    }

    /** Called from the panel's side button; the choice is kept across restarts. */
    public static void setPanelSide(String side) {
        panelSide = "left".equalsIgnoreCase(side) ? "left" : "right";
        if (config == null) return;
        config.get(SIDE_CATEGORY, SIDE_KEY, "right", SIDE_COMMENT)
            .set(panelSide);
        config.save();
    }
}
