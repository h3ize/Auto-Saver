package com.autobackup.server;

import com.autobackup.Config;
import com.autobackup.net.SaveStatePayload;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AutoBackupManager {
    public enum BackupStart {
        STARTED,
        BUSY,
        FAILED
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean BACKUP_RUNNING = new AtomicBoolean();
    private static final List<ServerLevel> FROZEN = new ArrayList<>();

    private static ExecutorService executor;
    private static Future<?> currentTask;
    private static Path worldDirectory;
    private static int saveCountdown = -1;
    private static int backupCountdown = -1;
    private static boolean saveWarned;
    private static boolean backupWarned;
    private static boolean playersSinceBackup;
    private static volatile long lastBackupMillis;
    private static volatile long lastBackupBytes;
    private static volatile String lastBackupName = "";

    private AutoBackupManager() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "AutoBackup-Worker");
            thread.setDaemon(true);
            return thread;
        });
        worldDirectory = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        saveCountdown = saveIntervalTicks();
        backupCountdown = backupIntervalTicks();
        saveWarned = false;
        backupWarned = false;
        playersSinceBackup = false;
        lastBackupMillis = 0L;
        lastBackupBytes = 0L;
        lastBackupName = "";
        BACKUP_RUNNING.set(false);
        currentTask = null;
        synchronized (FROZEN) {
            FROZEN.clear();
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Future<?> task = currentTask;
        if (task != null) {
            try {
                task.get(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.warn("Backup did not finish before shutdown: {}", e.getMessage());
            }
        }
        restoreLevels();
        BACKUP_RUNNING.set(false);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (executor != null) {
            executor.shutdown();
        }
        Path worldDir = worldDirectory;
        boolean wanted = Config.ENABLED.get()
                && Config.BACKUP_ENABLED.get()
                && Config.BACKUP_ON_SHUTDOWN.get()
                && (!Config.SKIP_WHEN_EMPTY.get() || playersSinceBackup);
        if (wanted && worldDir != null && Files.isDirectory(worldDir)) {
            String worldName = String.valueOf(worldDir.getFileName());
            try {
                Path targetDir = BackupService.resolveBackupDirectory(worldDir, worldName);
                Path created = BackupService.createBackup(worldDir, worldName, targetDir);
                BackupService.prune(targetDir, worldName);
                LOGGER.info("Shutdown backup created: {}", created.getFileName());
            } catch (IOException e) {
                LOGGER.error("Shutdown backup failed", e);
            }
        }
        worldDirectory = null;
        executor = null;
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && BACKUP_RUNNING.get()) {
            sendIndicator(player, new SaveStatePayload(true));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Config.ENABLED.get()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getPlayerCount() > 0) {
            playersSinceBackup = true;
        }
        tickSave(server);
        tickBackup(server);
    }

    private static void tickSave(MinecraftServer server) {
        int interval = saveIntervalTicks();
        if (saveCountdown < 0 || saveCountdown > interval) {
            saveCountdown = interval;
        }
        saveCountdown--;

        int warnTicks = Config.WARNING_SECONDS.get() * 20;
        if (warnTicks > 0 && warnTicks < interval && !saveWarned && saveCountdown == warnTicks && shouldSave(server)) {
            saveWarned = true;
            announce(server, "Auto-save in " + Config.WARNING_SECONDS.get() + " seconds");
        }

        if (saveCountdown <= 0) {
            saveCountdown = interval;
            saveWarned = false;
            if (shouldSave(server)) {
                performSave(server);
            }
        }
    }

    private static void tickBackup(MinecraftServer server) {
        if (!Config.BACKUP_ENABLED.get()) {
            return;
        }
        int interval = backupIntervalTicks();
        if (backupCountdown < 0 || backupCountdown > interval) {
            backupCountdown = interval;
        }
        backupCountdown--;

        int warnTicks = Config.WARNING_SECONDS.get() * 20;
        if (warnTicks > 0 && warnTicks < interval && !backupWarned && backupCountdown == warnTicks && shouldBackup(server)) {
            backupWarned = true;
            announce(server, "Backup in " + Config.WARNING_SECONDS.get() + " seconds");
        }

        if (backupCountdown <= 0) {
            backupCountdown = interval;
            backupWarned = false;
            if (shouldBackup(server)) {
                startBackup(server);
            }
        }
    }

    private static boolean shouldSave(MinecraftServer server) {
        return !(Config.SKIP_WHEN_EMPTY.get() && server.getPlayerCount() == 0);
    }

    private static boolean shouldBackup(MinecraftServer server) {
        if (BACKUP_RUNNING.get()) {
            return false;
        }
        return !(Config.SKIP_WHEN_EMPTY.get() && server.getPlayerCount() == 0 && !playersSinceBackup);
    }

    public static long performSave(MinecraftServer server) {
        sendIndicator(server, true);
        announce(server, "Auto-saving...");
        long start = System.nanoTime();
        server.saveEverything(true, false, false);
        long millis = (System.nanoTime() - start) / 1_000_000L;
        sendIndicator(server, false);
        announce(server, "Save complete (" + millis + " ms)");
        return millis;
    }

    public static BackupStart startBackup(MinecraftServer server) {
        if (executor == null || !BACKUP_RUNNING.compareAndSet(false, true)) {
            return BackupStart.BUSY;
        }
        Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String worldName = String.valueOf(worldDir.getFileName());
        Path targetDir;
        try {
            targetDir = BackupService.resolveBackupDirectory(worldDir, worldName);
        } catch (IOException e) {
            LOGGER.error("Backup could not start", e);
            announce(server, "Backup failed: no usable backup folder");
            BACKUP_RUNNING.set(false);
            return BackupStart.FAILED;
        }

        sendIndicator(server, true);
        announce(server, "Backup starting...");
        server.saveEverything(true, true, true);
        synchronized (FROZEN) {
            for (ServerLevel level : server.getAllLevels()) {
                if (!level.noSave) {
                    level.noSave = true;
                    FROZEN.add(level);
                }
            }
        }
        playersSinceBackup = false;
        currentTask = executor.submit(() -> runBackup(server, worldDir, worldName, targetDir));
        return BackupStart.STARTED;
    }

    private static void runBackup(MinecraftServer server, Path worldDir, String worldName, Path targetDir) {
        long start = System.nanoTime();
        String name = "";
        long bytes = 0L;
        boolean failed = false;
        try {
            Path created = BackupService.createBackup(worldDir, worldName, targetDir);
            name = created.getFileName().toString();
            bytes = Files.size(created);
        } catch (Exception e) {
            failed = true;
            LOGGER.error("Backup failed", e);
        }
        try {
            BackupService.prune(targetDir, worldName);
        } catch (Exception e) {
            LOGGER.warn("Pruning old backups failed", e);
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        String finalName = name;
        long finalBytes = bytes;
        boolean finalFailed = failed;
        server.execute(() -> finishBackup(server, finalName, finalBytes, finalFailed, millis));
    }

    private static void finishBackup(MinecraftServer server, String name, long bytes, boolean failed, long millis) {
        restoreLevels();
        BACKUP_RUNNING.set(false);
        currentTask = null;
        sendIndicator(server, false);
        if (failed) {
            announce(server, "Backup failed, check the server log");
            return;
        }
        lastBackupMillis = System.currentTimeMillis();
        lastBackupBytes = bytes;
        lastBackupName = name;
        announce(server, "Backup complete: " + name + " (" + BackupService.formatSize(bytes) + ", " + millis + " ms)");
    }

    private static void restoreLevels() {
        synchronized (FROZEN) {
            for (ServerLevel level : FROZEN) {
                level.noSave = false;
            }
            FROZEN.clear();
        }
    }

    public static void rescheduleSave() {
        saveCountdown = saveIntervalTicks();
        saveWarned = false;
    }

    public static boolean isBackupRunning() {
        return BACKUP_RUNNING.get();
    }

    public static long secondsUntilSave() {
        return Math.max(0, saveCountdown) / 20L;
    }

    public static long secondsUntilBackup() {
        return Math.max(0, backupCountdown) / 20L;
    }

    public static String lastBackupSummary() {
        if (lastBackupName.isEmpty()) {
            return "none since the server started";
        }
        long ago = (System.currentTimeMillis() - lastBackupMillis) / 1000L;
        return lastBackupName + " (" + BackupService.formatSize(lastBackupBytes) + "), " + formatDuration(ago) + " ago";
    }

    public static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private static int saveIntervalTicks() {
        return Config.SAVE_INTERVAL_MINUTES.get() * 1200;
    }

    private static int backupIntervalTicks() {
        return Config.BACKUP_INTERVAL_MINUTES.get() * 1200;
    }

    private static void announce(MinecraftServer server, String text) {
        LOGGER.info(text);
        Config.NotifyMode mode = Config.NOTIFY_MODE.get();
        if (mode == Config.NotifyMode.OFF) {
            return;
        }
        boolean actionBar = mode == Config.NotifyMode.ACTION_BAR;
        Component plain = Component.literal(text).withStyle(ChatFormatting.GRAY);
        Component prefixed = Component.literal("[AutoBackup] ").withStyle(ChatFormatting.GOLD).append(plain);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(actionBar ? plain : prefixed, actionBar);
        }
    }

    private static void sendIndicator(MinecraftServer server, boolean active) {
        SaveStatePayload payload = new SaveStatePayload(active);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendIndicator(player, payload);
        }
    }

    private static void sendIndicator(ServerPlayer player, SaveStatePayload payload) {
        if (player.connection.hasChannel(SaveStatePayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
