package com.autobackup.server;

import com.autobackup.Config;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class AutoSaveCommand {
    private AutoSaveCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> config = Commands.literal("config")
                .executes(ctx -> listConfig(ctx.getSource()));
        config.then(option("enabled", Config.ENABLED, BoolArgumentType.bool(), Boolean.class, AutoBackupManager::rescheduleAll));
        config.then(option("saveInterval", Config.SAVE_INTERVAL_MINUTES, IntegerArgumentType.integer(1, 1440), Integer.class, AutoBackupManager::rescheduleSave));
        config.then(option("skipWhenEmpty", Config.SKIP_WHEN_EMPTY, BoolArgumentType.bool(), Boolean.class, null));
        config.then(notifyOption());
        config.then(option("warningSeconds", Config.WARNING_SECONDS, IntegerArgumentType.integer(0, 60), Integer.class, null));
        config.then(option("backupEnabled", Config.BACKUP_ENABLED, BoolArgumentType.bool(), Boolean.class, null));
        config.then(option("backupInterval", Config.BACKUP_INTERVAL_MINUTES, IntegerArgumentType.integer(5, 10080), Integer.class, AutoBackupManager::rescheduleAll));
        config.then(directoryOption());
        config.then(option("keepLast", Config.RETENTION_COUNT, IntegerArgumentType.integer(0, 1000), Integer.class, null));
        config.then(option("maxAgeDays", Config.RETENTION_DAYS, IntegerArgumentType.integer(0, 3650), Integer.class, null));
        config.then(option("maxTotalSizeMb", Config.MAX_TOTAL_SIZE_MB, LongArgumentType.longArg(0L), Long.class, null));
        config.then(option("backupOnShutdown", Config.BACKUP_ON_SHUTDOWN, BoolArgumentType.bool(), Boolean.class, null));
        config.then(option("compressionLevel", Config.COMPRESSION_LEVEL, IntegerArgumentType.integer(0, 9), Integer.class, null));

        event.getDispatcher().register(Commands.literal("autosave")
                .requires(AutoSaveCommand::canUse)
                .then(Commands.literal("backup").executes(ctx -> backup(ctx.getSource())))
                .then(Commands.literal("toggle").executes(ctx -> toggle(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("interval")
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                .executes(ctx -> interval(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
                .then(config));
    }

    /** Operators (level 2+), the console, and the host of a locally hosted world can use the command. */
    private static boolean canUse(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return player != null && source.getServer().isSingleplayerOwner(player.getGameProfile());
    }

    private static <T> LiteralArgumentBuilder<CommandSourceStack> option(String name, ModConfigSpec.ConfigValue<T> value,
                                                                         ArgumentType<T> type, Class<T> clazz, Runnable after) {
        return Commands.literal(name)
                .executes(ctx -> show(ctx.getSource(), name, String.valueOf(value.get())))
                .then(Commands.argument("value", type).executes(ctx -> {
                    T newValue = ctx.getArgument("value", clazz);
                    return apply(ctx.getSource(), name, value, newValue, after);
                }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> notifyOption() {
        List<String> names = Arrays.stream(Config.NotifyMode.values()).map(Enum::name).toList();
        return Commands.literal("notify")
                .executes(ctx -> show(ctx.getSource(), "notify", Config.NOTIFY_MODE.get().name()))
                .then(Commands.argument("value", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(names, builder))
                        .executes(ctx -> {
                            String raw = StringArgumentType.getString(ctx, "value").toUpperCase(java.util.Locale.ROOT);
                            try {
                                return apply(ctx.getSource(), "notify", Config.NOTIFY_MODE, Config.NotifyMode.valueOf(raw), null);
                            } catch (IllegalArgumentException e) {
                                ctx.getSource().sendFailure(Component.literal("notify must be one of: " + String.join(", ", names)));
                                return 0;
                            }
                        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> directoryOption() {
        return Commands.literal("backupDirectory")
                .executes(ctx -> {
                    String dir = Config.BACKUP_DIRECTORY.get();
                    return show(ctx.getSource(), "backupDirectory", dir.isEmpty() ? "(default: backups folder)" : dir);
                })
                .then(Commands.literal("reset")
                        .executes(ctx -> apply(ctx.getSource(), "backupDirectory", Config.BACKUP_DIRECTORY, "", null)))
                .then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(ctx -> apply(ctx.getSource(), "backupDirectory", Config.BACKUP_DIRECTORY,
                                StringArgumentType.getString(ctx, "value").trim(), null)));
    }

    private static <T> int apply(CommandSourceStack source, String name, ModConfigSpec.ConfigValue<T> value, T newValue, Runnable after) {
        value.set(newValue);
        value.save();
        if (after != null) {
            after.run();
        }
        source.sendSuccess(() -> Component.literal(name + " set to " + (newValue instanceof String s && s.isEmpty() ? "(default)" : newValue)), true);
        return 1;
    }

    private static int show(CommandSourceStack source, String name, String current) {
        source.sendSuccess(() -> Component.literal(name + " = " + current), false);
        return 1;
    }

    private static int listConfig(CommandSourceStack source) {
        String dir = Config.BACKUP_DIRECTORY.get();
        String text = "Auto Backup settings (use /autosave config <name> <value>)"
                + "\nenabled = " + Config.ENABLED.get()
                + "\nsaveInterval = " + Config.SAVE_INTERVAL_MINUTES.get() + " min"
                + "\nskipWhenEmpty = " + Config.SKIP_WHEN_EMPTY.get()
                + "\nnotify = " + Config.NOTIFY_MODE.get()
                + "\nwarningSeconds = " + Config.WARNING_SECONDS.get()
                + "\nbackupEnabled = " + Config.BACKUP_ENABLED.get()
                + "\nbackupInterval = " + Config.BACKUP_INTERVAL_MINUTES.get() + " min"
                + "\nbackupDirectory = " + (dir.isEmpty() ? "(default)" : dir)
                + "\nkeepLast = " + Config.RETENTION_COUNT.get()
                + "\nmaxAgeDays = " + Config.RETENTION_DAYS.get()
                + "\nmaxTotalSizeMb = " + Config.MAX_TOTAL_SIZE_MB.get()
                + "\nbackupOnShutdown = " + Config.BACKUP_ON_SHUTDOWN.get()
                + "\ncompressionLevel = " + Config.COMPRESSION_LEVEL.get();
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int backup(CommandSourceStack source) {
        AutoBackupManager.BackupStart result = AutoBackupManager.startBackup(source.getServer());
        switch (result) {
            case STARTED -> source.sendSuccess(() -> Component.literal("Backup started"), false);
            case BUSY -> source.sendFailure(Component.literal("A backup is already running"));
            case FAILED -> source.sendFailure(Component.literal("Backup could not start, check the server log"));
        }
        return result == AutoBackupManager.BackupStart.STARTED ? 1 : 0;
    }

    private static int toggle(CommandSourceStack source) {
        boolean enabled = !Config.ENABLED.get();
        Config.ENABLED.set(enabled);
        Config.ENABLED.save();
        AutoBackupManager.rescheduleSave();
        source.sendSuccess(() -> Component.literal("Auto-save and backups " + (enabled ? "enabled" : "disabled")), true);
        return 1;
    }

    private static int interval(CommandContext<CommandSourceStack> ctx, int minutes) {
        Config.SAVE_INTERVAL_MINUTES.set(minutes);
        Config.SAVE_INTERVAL_MINUTES.save();
        AutoBackupManager.rescheduleSave();
        ctx.getSource().sendSuccess(() -> Component.literal("Auto-save interval set to " + minutes + " minute(s)"), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        boolean enabled = Config.ENABLED.get();
        StringBuilder text = new StringBuilder();
        text.append("Auto-save and backups: ").append(enabled ? "enabled" : "disabled");
        if (enabled) {
            text.append("\nNext save: ").append(AutoBackupManager.formatDuration(AutoBackupManager.secondsUntilSave()))
                    .append(" (every ").append(Config.SAVE_INTERVAL_MINUTES.get()).append(" min)");
            if (Config.BACKUP_ENABLED.get()) {
                text.append("\nNext backup: ").append(AutoBackupManager.formatDuration(AutoBackupManager.secondsUntilBackup()))
                        .append(" (every ").append(Config.BACKUP_INTERVAL_MINUTES.get()).append(" min)");
            } else {
                text.append("\nBackups: disabled");
            }
        }
        text.append("\nLast backup: ").append(AutoBackupManager.lastBackupSummary());
        if (AutoBackupManager.isBackupRunning()) {
            text.append("\nA backup is running now");
        }
        try {
            Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path dir = BackupService.resolveBackupDirectory(worldDir, String.valueOf(worldDir.getFileName()));
            text.append("\nBackup folder: ").append(dir);
        } catch (IOException e) {
            text.append("\nBackup folder: unavailable");
        }
        String message = text.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
