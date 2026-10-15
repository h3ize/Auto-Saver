package com.autobackup;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public enum NotifyMode {
        OFF,
        CHAT,
        ACTION_BAR
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue SAVE_INTERVAL_MINUTES;
    public static final ModConfigSpec.BooleanValue SKIP_WHEN_EMPTY;
    public static final ModConfigSpec.EnumValue<NotifyMode> NOTIFY_MODE;
    public static final ModConfigSpec.IntValue WARNING_SECONDS;
    public static final ModConfigSpec.BooleanValue BACKUP_ENABLED;
    public static final ModConfigSpec.IntValue BACKUP_INTERVAL_MINUTES;
    public static final ModConfigSpec.ConfigValue<String> BACKUP_DIRECTORY;
    public static final ModConfigSpec.IntValue RETENTION_COUNT;
    public static final ModConfigSpec.IntValue RETENTION_DAYS;
    public static final ModConfigSpec.LongValue MAX_TOTAL_SIZE_MB;
    public static final ModConfigSpec.BooleanValue BACKUP_ON_SHUTDOWN;
    public static final ModConfigSpec.IntValue COMPRESSION_LEVEL;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        ENABLED = builder
                .comment("Master switch for automatic saving and backups.")
                .define("enabled", true);

        builder.push("autosave");
        SAVE_INTERVAL_MINUTES = builder
                .comment("Minutes between automatic world saves. Minimum 1.")
                .defineInRange("intervalMinutes", 30, 1, 1440);
        SKIP_WHEN_EMPTY = builder
                .comment("Skip saves and backups while nobody is online and nothing could have changed.")
                .define("skipWhenEmpty", true);
        NOTIFY_MODE = builder
                .comment("Where to tell players about saves and backups: OFF, CHAT or ACTION_BAR.")
                .defineEnum("notifyMode", NotifyMode.CHAT);
        WARNING_SECONDS = builder
                .comment("Warn players this many seconds before a save or backup. 0 disables the warning.")
                .defineInRange("warningSeconds", 0, 0, 60);
        builder.pop();

        builder.push("backup");
        BACKUP_ENABLED = builder
                .comment("Create zipped backups of the world on a timer.")
                .define("enabled", true);
        BACKUP_INTERVAL_MINUTES = builder
                .comment("Minutes between automatic backups. Minimum 5.")
                .defineInRange("intervalMinutes", 30, 5, 10080);
        BACKUP_DIRECTORY = builder
                .comment("Folder to store backups in. Leave empty for a 'backups' folder next to the game or server folder.",
                        "Absolute paths such as D:/MinecraftBackups or /mnt/backup/minecraft are allowed.",
                        "Backups are grouped into one subfolder per world. A folder inside the world itself is refused.")
                .define("directory", "");
        RETENTION_COUNT = builder
                .comment("Keep only this many of the newest backups per world. 1 (default) deletes the previous backup",
                        "once a new one is made. 0 keeps everything.")
                .defineInRange("keepLast", 1, 0, 1000);
        RETENTION_DAYS = builder
                .comment("Delete backups older than this many days. 0 disables.")
                .defineInRange("maxAgeDays", 0, 0, 3650);
        MAX_TOTAL_SIZE_MB = builder
                .comment("Maximum combined size of one world's backups in megabytes. The oldest are deleted first. 0 disables.")
                .defineInRange("maxTotalSizeMb", 10240L, 0L, Long.MAX_VALUE);
        BACKUP_ON_SHUTDOWN = builder
                .comment("Create one more backup after the world has been fully saved and the server has stopped.")
                .define("backupOnShutdown", false);
        COMPRESSION_LEVEL = builder
                .comment("Zip compression level from 0 (store only) to 9 (smallest).")
                .defineInRange("compressionLevel", 5, 0, 9);
        builder.pop();

        SPEC = builder.build();
    }

    private Config() {
    }
}
