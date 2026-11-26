package com.enterprise.sfgexporter.scheduler;

import com.enterprise.sfgexporter.config.AppProperties;
import com.enterprise.sfgexporter.config.SecurityValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * Manages post-upload archiving of CSV files from {@code staging/} into {@code backup/yyyy-MM-dd/}
 * and runs a scheduled daily retention sweep removing files and empty date folders older than
 * {@code app.storage.retention-days} (default: 15 days).
 *
 * <p>Security &amp; filesystem guarantees:</p>
 * <ul>
 *     <li>Every source and destination path is validated with {@link SecurityValidator#ensureChildPath}
 *         to prevent path traversal outside {@code stagingDir} or {@code backupDir}.</li>
 *     <li>Symbolic links inside {@code backupDir} are skipped so cleanup can never follow links
 *         outside the backup root.</li>
 *     <li>Newly created date folders are restricted to POSIX {@code 0700} where supported.</li>
 *     <li>Uses atomic rename when staging and backup share a mount, falling back cleanly across mounts.</li>
 * </ul>
 */
@Service
public class BackupRetentionService {

    private static final Logger log = LoggerFactory.getLogger(BackupRetentionService.class);
    private static final DateTimeFormatter DATE_FOLDER_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE; // yyyy-MM-dd
    private static final Set<PosixFilePermission> RESTRICTED_DIR_PERMS = PosixFilePermissions.fromString("rwx------");

    private static final String PART_SUFFIX = ".part";

    private final Path stagingDir;
    private final Path backupDir;
    private final int retentionDays;
    private final int stagingPartMaxAgeHours;
    private final ZoneId cleanupZone;
    private final Clock clock;

    @Autowired
    public BackupRetentionService(AppProperties properties) {
        this(
                Path.of(properties.getStorage().getStagingDir()),
                Path.of(properties.getStorage().getBackupDir()),
                properties.getStorage().getRetentionDays(),
                properties.getStorage().getStagingPartMaxAgeHours(),
                ZoneId.of(properties.getStorage().getCleanupTimeZone()),
                Clock.systemUTC()
        );
    }

    public BackupRetentionService(
            Path stagingDir,
            Path backupDir,
            int retentionDays,
            ZoneId cleanupZone,
            Clock clock
    ) {
        this(stagingDir, backupDir, retentionDays, 24, cleanupZone, clock);
    }

    public BackupRetentionService(
            Path stagingDir,
            Path backupDir,
            int retentionDays,
            int stagingPartMaxAgeHours,
            ZoneId cleanupZone,
            Clock clock
    ) {
        Objects.requireNonNull(stagingDir, "stagingDir must not be null");
        Objects.requireNonNull(backupDir, "backupDir must not be null");
        Objects.requireNonNull(cleanupZone, "cleanupZone must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be >= 1");
        }
        if (stagingPartMaxAgeHours < 1) {
            throw new IllegalArgumentException("stagingPartMaxAgeHours must be >= 1");
        }
        this.stagingDir = stagingDir.toAbsolutePath().normalize();
        this.backupDir = backupDir.toAbsolutePath().normalize();
        this.retentionDays = retentionDays;
        this.stagingPartMaxAgeHours = stagingPartMaxAgeHours;
        this.cleanupZone = cleanupZone;
        this.clock = clock;
    }

    /**
     * Moves a successfully uploaded CSV file from {@code staging/} into {@code backup/yyyy-MM-dd/}.
     */
    public Path archiveToBackup(Path stagedFile) throws IOException {
        return archiveToBackup(stagedFile, LocalDate.now(clock.withZone(cleanupZone)));
    }

    public Path archiveToBackup(Path stagedFile, LocalDate archiveDate) throws IOException {
        Path verifiedSource = SecurityValidator.ensureChildPath(stagingDir, stagedFile);
        if (Files.isSymbolicLink(verifiedSource)
                || !Files.isRegularFile(verifiedSource, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to archive non-regular or symlink file: " + verifiedSource.getFileName());
        }

        Path sourceFileName = Objects.requireNonNull(
                verifiedSource.getFileName(), "Staged file path must include a file name");

        if (Files.isSymbolicLink(backupDir)) {
            throw new SecurityException("Refusing to archive into symbolic link backup root: " + backupDir.getFileName());
        }
        String dateFolderName = DATE_FOLDER_FORMAT.format(archiveDate);
        Path dateFolder = SecurityValidator.ensureChildPath(backupDir, backupDir.resolve(dateFolderName));
        createDirectorySafely(dateFolder);

        Path targetPath = SecurityValidator.ensureChildPath(
                dateFolder, dateFolder.resolve(sourceFileName.toString()));
        if (Files.isSymbolicLink(targetPath)) {
            throw new SecurityException("Refusing to overwrite symbolic link in backup directory: " + targetPath.getFileName());
        }

        try {
            Files.move(verifiedSource, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(verifiedSource, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Archived '{}' to backup folder '{}'", targetPath.getFileName(), dateFolderName);
        return targetPath;
    }

    /**
     * Daily retention cleanup trigger scheduled via {@code app.storage.cleanup-cron}.
     */
    @Scheduled(
            cron = "${app.storage.cleanup-cron:0 0 2 * * *}",
            zone = "${app.storage.cleanup-time-zone:UTC}"
    )
    public void runScheduledCleanup() {
        cleanupExpiredBackups();
    }

    /**
     * Removes backup files and empty date folders older than {@code retentionDays},
     * plus expired failed CSVs and crash-orphaned {@code .part} files in {@code stagingDir}.
     */
    public CleanupSummary cleanupExpiredBackups() {
        LocalDate today = LocalDate.now(clock.withZone(cleanupZone));
        LocalDate cutoffDate = today.minusDays(retentionDays);
        Instant cutoffInstant = clock.instant().minus(retentionDays, ChronoUnit.DAYS);
        Instant orphanPartCutoff = clock.instant().minus(stagingPartMaxAgeHours, ChronoUnit.HOURS);

        CleanupCounters counters = new CleanupCounters();
        cleanupBackupDirectory(today, cutoffDate, cutoffInstant, counters);
        cleanupExpiredStagingFiles(cutoffInstant, orphanPartCutoff, counters);

        log.info("Backup retention cleanup completed (retention={}d, cutoff={}): deleted {} file(s) and {} empty folder(s)",
                retentionDays, cutoffDate, counters.deletedFiles, counters.deletedFolders);
        return new CleanupSummary(counters.deletedFiles, counters.deletedFolders, cutoffDate);
    }

    private void cleanupBackupDirectory(
            LocalDate today,
            LocalDate cutoffDate,
            Instant cutoffInstant,
            CleanupCounters counters
    ) {
        if (!Files.isDirectory(backupDir, LinkOption.NOFOLLOW_LINKS)) {
            log.debug("Backup directory '{}' does not exist yet; skipping backup cleanup", backupDir);
            return;
        }
        try (DirectoryStream<Path> topLevelEntries = Files.newDirectoryStream(backupDir)) {
            for (Path entry : topLevelEntries) {
                try {
                    processBackupEntry(entry, today, cutoffDate, cutoffInstant, counters);
                } catch (NoSuchFileException ignored) {
                    // Entry disappeared concurrently during sweep
                } catch (IOException | SecurityException ex) {
                    log.warn("Skipping backup entry '{}' during cleanup: {}",
                            entry.getFileName(), ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.error("Error during backup retention cleanup: {}", ex.getMessage(), ex);
        }
    }

    private void cleanupExpiredStagingFiles(
            Instant cutoffInstant,
            Instant orphanPartCutoff,
            CleanupCounters counters
    ) {
        if (!Files.isDirectory(stagingDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(stagingDir)) {
            for (Path entry : entries) {
                try {
                    cleanupStagingEntry(entry, cutoffInstant, orphanPartCutoff, counters);
                } catch (NoSuchFileException ignored) {
                    // File was already promoted or archived concurrently
                } catch (IOException | SecurityException ex) {
                    log.warn("Skipping staging entry '{}' during cleanup: {}",
                            entry.getFileName(), ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.error("Error during staging directory cleanup: {}", ex.getMessage(), ex);
        }
    }

    private void cleanupStagingEntry(
            Path entry,
            Instant cutoffInstant,
            Instant orphanPartCutoff,
            CleanupCounters counters
    ) throws IOException {
        if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path safeEntry = SecurityValidator.ensureChildPath(stagingDir, entry);
        Path fileNamePath = safeEntry.getFileName();
        String fileName = fileNamePath != null ? fileNamePath.toString() : "";
        Instant threshold = fileName.endsWith(PART_SUFFIX) ? orphanPartCutoff : cutoffInstant;
        Instant lastModified = Files.getLastModifiedTime(safeEntry, LinkOption.NOFOLLOW_LINKS).toInstant();
        if (lastModified.isBefore(threshold) && Files.deleteIfExists(safeEntry)) {
            counters.deletedFiles++;
        }
    }

    private void processBackupEntry(
            Path entry,
            LocalDate today,
            LocalDate cutoffDate,
            Instant cutoffInstant,
            CleanupCounters counters
    ) throws IOException {
        if (Files.isSymbolicLink(entry)) {
            log.warn("Skipping symlink inside backup directory: {}", entry.getFileName());
            return;
        }
        Path safeEntry = SecurityValidator.ensureChildPath(backupDir, entry);

        if (Files.isDirectory(safeEntry, LinkOption.NOFOLLOW_LINKS)) {
            cleanupDateFolder(safeEntry, today, cutoffDate, counters);
        } else if (Files.isRegularFile(safeEntry, LinkOption.NOFOLLOW_LINKS)) {
            cleanupLooseFile(safeEntry, cutoffInstant, counters);
        }
    }

    private void cleanupDateFolder(
            Path dateFolder,
            LocalDate today,
            LocalDate cutoffDate,
            CleanupCounters counters
    ) throws IOException {
        LocalDate folderDate = parseDateFolder(dateFolder);
        if (folderDate == null) {
            return;
        }

        if (folderDate.isBefore(cutoffDate)) {
            counters.deletedFiles += deleteFilesInsideFolder(dateFolder);
            if (deleteFolderIfEmpty(dateFolder)) {
                counters.deletedFolders++;
            }
            return;
        }

        // Also prune any stale empty date folders from previous days
        if (folderDate.isBefore(today) && deleteFolderIfEmpty(dateFolder)) {
            counters.deletedFolders++;
        }
    }

    private void cleanupLooseFile(Path file, Instant cutoffInstant, CleanupCounters counters) throws IOException {
        Instant lastModified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant();
        if (lastModified.isBefore(cutoffInstant) && Files.deleteIfExists(file)) {
            counters.deletedFiles++;
        }
    }

    private int deleteFilesInsideFolder(Path dateFolder) throws IOException {
        int count = 0;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dateFolder)) {
            for (Path child : children) {
                try {
                    if (Files.isSymbolicLink(child)) {
                        log.warn("Skipping symlink inside date folder '{}': {}", dateFolder.getFileName(), child.getFileName());
                        continue;
                    }
                    Path safeChild = SecurityValidator.ensureChildPath(dateFolder, child);
                    if (Files.isRegularFile(safeChild, LinkOption.NOFOLLOW_LINKS) && Files.deleteIfExists(safeChild)) {
                        count++;
                    }
                } catch (NoSuchFileException ignored) {
                    // File already removed concurrently
                } catch (IOException | SecurityException ex) {
                    log.warn("Skipping expired backup file '{}' inside '{}': {}",
                            child.getFileName(), dateFolder.getFileName(), ex.getMessage());
                }
            }
        }
        return count;
    }

    private boolean deleteFolderIfEmpty(Path folder) throws IOException {
        if (isDirectoryEmpty(folder)) {
            try {
                return Files.deleteIfExists(folder);
            } catch (DirectoryNotEmptyException | NoSuchFileException ex) {
                return false;
            }
        }
        return false;
    }

    private boolean isDirectoryEmpty(Path folder) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            return !entries.iterator().hasNext();
        }
    }

    private LocalDate parseDateFolder(Path folder) {
        Path folderName = folder.getFileName();
        if (folderName == null) {
            return null;
        }
        try {
            return LocalDate.parse(folderName.toString(), DATE_FOLDER_FORMAT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private void createDirectorySafely(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir)) {
            throw new SecurityException("Refusing to archive into symbolic link directory: " + dir.getFileName());
        }
        boolean existed = Files.exists(dir, LinkOption.NOFOLLOW_LINKS);
        Files.createDirectories(dir);
        if (!existed) {
            try {
                Files.setPosixFilePermissions(dir, RESTRICTED_DIR_PERMS);
            } catch (UnsupportedOperationException | IOException ignored) {
                // Non-POSIX filesystem or restricted chmod environment
            }
        }
    }

    private static final class CleanupCounters {
        int deletedFiles;
        int deletedFolders;
    }

    public record CleanupSummary(int deletedFiles, int deletedFolders, LocalDate cutoffDate) {
    }
}
