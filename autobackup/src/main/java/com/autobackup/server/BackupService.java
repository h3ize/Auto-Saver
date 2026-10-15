package com.autobackup.server;

import com.autobackup.Config;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BackupService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String STAMP_REGEX = "_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(_\\d+)?";

    private BackupService() {
    }

    public static Path resolveBackupDirectory(Path worldDir, String worldName) throws IOException {
        Path gameDir = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        Path defaultRoot = gameDir.resolve("backups");
        Path root = defaultRoot;
        String configured = Config.BACKUP_DIRECTORY.get().trim();

        if (!configured.isEmpty()) {
            try {
                Path candidate = Paths.get(configured);
                if (!candidate.isAbsolute()) {
                    candidate = gameDir.resolve(candidate);
                }
                root = candidate.toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                LOGGER.error("Backup directory '{}' is not a valid path, using the default instead", configured);
            }
        }

        Path target = prepare(root, worldDir, worldName);
        if (target == null && !root.equals(defaultRoot)) {
            LOGGER.error("Backup directory {} cannot be used (inside the world folder or not writable), falling back to {}", root, defaultRoot);
            target = prepare(defaultRoot, worldDir, worldName);
        }
        if (target == null) {
            throw new IOException("No usable backup directory available");
        }
        return target;
    }

    private static Path prepare(Path root, Path worldDir, String worldName) {
        Path target = root.resolve(sanitize(worldName)).normalize();
        if (target.startsWith(worldDir)) {
            return null;
        }
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            return null;
        }
        return Files.isWritable(target) ? target : null;
    }

    public static Path createBackup(Path worldDir, String worldName, Path targetDir) throws IOException {
        String base = sanitize(worldName) + "_" + LocalDateTime.now().format(STAMP);
        Path finalPath = targetDir.resolve(base + ".zip");
        int counter = 1;
        while (Files.exists(finalPath)) {
            finalPath = targetDir.resolve(base + "_" + counter + ".zip");
            counter++;
        }
        Path temp = targetDir.resolve(finalPath.getFileName() + ".tmp");
        try {
            writeZip(worldDir, worldName, temp);
            Files.move(temp, finalPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
        return finalPath;
    }

    private static void writeZip(Path worldDir, String worldName, Path zipFile) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile)))) {
            zip.setLevel(Config.COMPRESSION_LEVEL.get());
            Files.walkFileTree(worldDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || file.getFileName().toString().equals("session.lock")) {
                        return FileVisitResult.CONTINUE;
                    }
                    InputStream in = openQuietly(file);
                    if (in == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    String entryName = worldName + "/" + worldDir.relativize(file).toString().replace('\\', '/');
                    try (in) {
                        ZipEntry entry = new ZipEntry(entryName);
                        entry.setTime(attrs.lastModifiedTime().toMillis());
                        zip.putNextEntry(entry);
                        in.transferTo(zip);
                        zip.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LOGGER.warn("Skipping {} during backup: {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static InputStream openQuietly(Path file) {
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            LOGGER.warn("Skipping unreadable file {} during backup", file);
            return null;
        }
    }

    public static void prune(Path targetDir, String worldName) {
        String safeName = Pattern.quote(sanitize(worldName));
        Pattern zipPattern = Pattern.compile("^" + safeName + STAMP_REGEX + "\\.zip$");
        Pattern tempPattern = Pattern.compile("^" + safeName + STAMP_REGEX + "\\.zip\\.tmp$");

        List<Path> backups = new ArrayList<>();
        try (Stream<Path> stream = Files.list(targetDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (zipPattern.matcher(name).matches()) {
                    backups.add(path);
                } else if (tempPattern.matcher(name).matches()) {
                    deleteQuietly(path);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not scan {} for old backups: {}", targetDir, e.getMessage());
            return;
        }

        backups.sort(Comparator.comparingLong(BackupService::modified).reversed());

        int keep = Config.RETENTION_COUNT.get();
        long days = Config.RETENTION_DAYS.get();
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        List<Path> kept = new ArrayList<>(backups);

        for (int i = backups.size() - 1; i >= 1; i--) {
            Path file = backups.get(i);
            boolean tooMany = keep > 0 && i >= keep;
            boolean tooOld = days > 0 && modified(file) < cutoff;
            if (tooMany || tooOld) {
                deleteQuietly(file);
                kept.remove(file);
            }
        }

        long cap = Config.MAX_TOTAL_SIZE_MB.get() * 1024L * 1024L;
        if (cap > 0) {
            long total = 0L;
            for (Path file : kept) {
                total += size(file);
            }
            for (int i = kept.size() - 1; i >= 1 && total > cap; i--) {
                Path file = kept.remove(i);
                total -= size(file);
                deleteQuietly(file);
            }
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.warn("Could not delete {}: {}", path, e.getMessage());
        }
    }

    public static String sanitize(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned.isEmpty() ? "world" : cleaned;
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes / 1024.0D;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024.0D && unit < units.length - 1) {
            value /= 1024.0D;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
