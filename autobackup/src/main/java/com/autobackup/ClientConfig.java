package com.autobackup;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    public enum Corner {
        BOTTOM_RIGHT,
        BOTTOM_LEFT,
        TOP_RIGHT,
        TOP_LEFT
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_ICON;
    public static final ModConfigSpec.EnumValue<Corner> CORNER;
    public static final ModConfigSpec.DoubleValue SCALE;
    public static final ModConfigSpec.IntValue MARGIN;
    public static final ModConfigSpec.DoubleValue MIN_DISPLAY_SECONDS;
    public static final ModConfigSpec.BooleanValue BOB;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SHOW_ICON = builder
                .comment("Show the chest icon while the world is saving or backing up.")
                .define("showIcon", true);
        CORNER = builder
                .comment("Screen corner for the icon.")
                .defineEnum("corner", Corner.BOTTOM_RIGHT);
        SCALE = builder
                .comment("Icon size multiplier.")
                .defineInRange("scale", 1.0D, 0.5D, 4.0D);
        MARGIN = builder
                .comment("Distance from the screen edge in pixels.")
                .defineInRange("margin", 6, 0, 100);
        MIN_DISPLAY_SECONDS = builder
                .comment("Keep the icon visible for at least this long, even if the save finishes instantly.")
                .defineInRange("minDisplaySeconds", 2.0D, 0.5D, 30.0D);
        BOB = builder
                .comment("Animate the arrow dropping onto the chest.")
                .define("animateArrow", true);
        SPEC = builder.build();
    }

    private ClientConfig() {
    }
}
