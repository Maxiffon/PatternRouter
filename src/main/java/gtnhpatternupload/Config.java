package gtnhpatternupload;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/** config/gtnhpatternupload.cfg */
public final class Config {

    /** Где показывать панель: right — справа от терминала (NEI уступает место), left — слева. */
    public static String panelSide = "right";

    private Config() {}

    public static void load(File file) {
        Configuration c = new Configuration(file);
        panelSide = c.getString(
            "panelSide",
            "client",
            "right",
            "Pattern terminal side panel position: right (NEI items hide under it) or left.",
            new String[] { "right", "left" });
        if (c.hasChanged()) c.save();
    }
}
