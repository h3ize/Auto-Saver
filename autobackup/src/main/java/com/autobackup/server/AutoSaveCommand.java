package com.autobackup.server;

import com.autobackup.Config;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Path;

public final class AutoSaveCommand {
    private AutoSaveCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("autosave")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("now").executes(ctx -> now(ctx.getSource())))
                .then(Commands.literal("backup").executes(ctx -> backup(ctx.getSource())))
                .then(Commands.literal("toggle").executes(ctx -> toggle(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("interval")
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                .executes(ctx -> interval(ctx, IntegerArgumentType.getInteger(ctx, "minutes"))))));
    }

    private static int now(CommandSourceStack source) {
        long millis = AutoBackupManager.performSave(source.getServer());
        source.sendSuccess(() -> Component.literal("World saved in " + millis + " ms"), false);
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
