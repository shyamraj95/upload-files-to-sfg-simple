package com.enterprise.sfgexporter.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.enterprise.sfgexporter.scheduler.BackupRetentionService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupRetentionServiceTest {

    @TempDir
    Path tempDir;

    /**
     * Verifies that {@link BackupRetentionService#archiveToBackup(Path)} moves a staged CSV into
     * {@code backup/yyyy-MM-dd/}, and {@link BackupRetentionService#cleanupExpiredBackups()} deletes
     * date folders strictly older than {@code retentionDays} (16 days old), retains boundary folders
     * (15 days old), and prunes stale empty date folders from earlier days.
     */
    @Test
    void archivesStagedCsvIntoDateFolderAndPurgesBackupsOlderThan15Days() throws Exception {
        Path stagingDir = Files.createDirectories(tempDir.resolve("staging"));
        Path backupDir = Files.createDirectories(tempDir.resolve("backup"));

        // Fixed "today" = 2026-11-22; 15-day cutoff = 2026-11-07
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneId.of("UTC"));
        BackupRetentionService service = new BackupRetentionService(
                stagingDir, backupDir, 15, ZoneId.of("UTC"), fixedClock);

        // 1. Archive a staged file into backup/2026-11-22/
        Path stagedCsv = stagingDir.resolve("GL_JOURNAL_20261122_020000.csv");
        Files.writeString(stagedCsv, "COL1\nVAL1\n");

        Path archived = service.archiveToBackup(stagedCsv);

        assertThat(Files.exists(stagedCsv)).isFalse();
        assertThat(archived)
                .isEqualTo(backupDir.resolve("2026-11-22/GL_JOURNAL_20261122_020000.csv"))
                .exists();

        // 2. Seed historical folders:
        //    - 2026-11-06 (16 days old -> MUST be deleted along with its folder)
        //    - 2026-11-07 (exactly 15 days old -> MUST be retained)
        //    - 2026-11-10 (empty stale folder -> MUST be pruned)
        Path expiredFolder = Files.createDirectories(backupDir.resolve("2026-11-06"));
        Files.writeString(expiredFolder.resolve("OLD_1.csv"), "old");
        Files.writeString(expiredFolder.resolve("OLD_2.csv"), "old");

        Path boundaryFolder = Files.createDirectories(backupDir.resolve("2026-11-07"));
        Path boundaryFile = Files.writeString(boundaryFolder.resolve("KEEP_15D.csv"), "keep");

        Path staleEmptyFolder = Files.createDirectories(backupDir.resolve("2026-11-10"));

        // 3. Run retention cleanup
        BackupRetentionService.CleanupSummary summary = service.cleanupExpiredBackups();

        assertThat(summary.cutoffDate()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(summary.deletedFiles()).isEqualTo(2);
        assertThat(summary.deletedFolders()).isEqualTo(2); // expiredFolder + staleEmptyFolder

        assertThat(Files.exists(expiredFolder)).isFalse();
        assertThat(Files.exists(staleEmptyFolder)).isFalse();
        assertThat(Files.exists(boundaryFile)).isTrue();
        assertThat(Files.exists(archived)).isTrue();
    }

    /**
     * Verifies that attempting to archive a symbolic link inside {@code staging/} is rejected
     * with an {@link IOException} so arbitrary external files cannot be moved into {@code backup/}.
     */
    @Test
    void refusesToArchiveSymlinkInStagingDir() throws Exception {
        Path stagingDir = Files.createDirectories(tempDir.resolve("staging"));
        Path backupDir = Files.createDirectories(tempDir.resolve("backup"));
        Path outsideTarget = Files.writeString(tempDir.resolve("outside.csv"), "secret");
        Path symlink = Files.createSymbolicLink(stagingDir.resolve("evil.csv"), outsideTarget);

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneId.of("UTC"));
        BackupRetentionService service = new BackupRetentionService(
                stagingDir, backupDir, 15, ZoneId.of("UTC"), fixedClock);

        assertThatThrownBy(() -> service.archiveToBackup(symlink))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symlink");
        assertThat(Files.exists(outsideTarget)).isTrue();
    }

    /**
     * Verifies that retention cleanup skips symbolic links inside expired date folders
     * without following them or deleting the external target file.
     */
    @Test
    void skipsSymlinkInsideExpiredBackupFolderWithoutDeletingTarget() throws Exception {
        Path stagingDir = Files.createDirectories(tempDir.resolve("staging"));
        Path backupDir = Files.createDirectories(tempDir.resolve("backup"));
        Path outsideTarget = Files.writeString(tempDir.resolve("keep_outside.txt"), "do-not-delete");

        Path expiredFolder = Files.createDirectories(backupDir.resolve("2026-11-01"));
        Files.createSymbolicLink(expiredFolder.resolve("link.csv"), outsideTarget);

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneId.of("UTC"));
        BackupRetentionService service = new BackupRetentionService(
                stagingDir, backupDir, 15, ZoneId.of("UTC"), fixedClock);

        service.cleanupExpiredBackups();

        assertThat(Files.exists(outsideTarget)).isTrue();
        assertThat(Files.readString(outsideTarget)).isEqualTo("do-not-delete");
    }

    /**
     * Verifies staging directory housekeeping: failed CSVs older than {@code retentionDays} and
     * crash-orphaned {@code .part} files older than {@code stagingPartMaxAgeHours} are deleted,
     * while recent in-progress {@code .part} files are preserved.
     */
    @Test
    void cleansUpExpiredStagingCsvAndOrphanPartWhileKeepingActivePart() throws Exception {
        Path stagingDir = Files.createDirectories(tempDir.resolve("staging"));
        Path backupDir = Files.createDirectories(tempDir.resolve("backup"));
        Instant now = Instant.parse("2026-11-22T02:00:00Z");

        Path expiredFailedCsv = Files.writeString(stagingDir.resolve("OLD_FAILED.csv"), "old");
        Files.setLastModifiedTime(expiredFailedCsv, FileTime.from(now.minus(16, ChronoUnit.DAYS)));

        Path orphanPart = Files.writeString(stagingDir.resolve("CRASH.csv.part"), "partial");
        Files.setLastModifiedTime(orphanPart, FileTime.from(now.minus(25, ChronoUnit.HOURS)));

        Path activePart = Files.writeString(stagingDir.resolve("ACTIVE.csv.part"), "writing");
        Files.setLastModifiedTime(activePart, FileTime.from(now.minus(1, ChronoUnit.HOURS)));

        Clock fixedClock = Clock.fixed(now, ZoneId.of("UTC"));
        BackupRetentionService service = new BackupRetentionService(
                stagingDir, backupDir, 15, 24, ZoneId.of("UTC"), fixedClock);

        BackupRetentionService.CleanupSummary summary = service.cleanupExpiredBackups();

        assertThat(summary.deletedFiles()).isEqualTo(2);
        assertThat(Files.exists(expiredFailedCsv)).isFalse();
        assertThat(Files.exists(orphanPart)).isFalse();
        assertThat(Files.exists(activePart)).isTrue();
    }

    /**
     * Verifies that archiving refuses to write into a date folder ({@code backup/yyyy-MM-dd})
     * if that date folder is a symbolic link pointing outside {@code backup/}.
     */
    @Test
    void refusesSymlinkDateFolderDuringArchive() throws Exception {
        Path stagingDir = Files.createDirectories(tempDir.resolve("staging"));
        Path backupDir = Files.createDirectories(tempDir.resolve("backup"));
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        Files.createSymbolicLink(backupDir.resolve("2026-11-22"), outsideDir);

        Path stagedCsv = Files.writeString(stagingDir.resolve("GL_20261122.csv"), "A\n1\n");
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneId.of("UTC"));
        BackupRetentionService service = new BackupRetentionService(
                stagingDir, backupDir, 15, ZoneId.of("UTC"), fixedClock);

        assertThatThrownBy(() -> service.archiveToBackup(stagedCsv))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("symbolic link directory");
    }

    /**
     * Verifies fault-isolated retention sweeps: encountering a symlink in one expired folder
     * does not abort cleanup of subsequent valid expired date folders.
     */
    @Test
    void continuesCleanupWhenOneFolderContainsSymlinkAndSubsequentFolderIsExpired() throws Exception {
        Path stagingDir = Files.createDirectories(tempDir.resolve("staging"));
        Path backupDir = Files.createDirectories(tempDir.resolve("backup"));
        Path outsideTarget = Files.writeString(tempDir.resolve("keep.txt"), "keep");

        Path symlinkFolder = Files.createDirectories(backupDir.resolve("2026-11-01"));
        Files.createSymbolicLink(symlinkFolder.resolve("link.csv"), outsideTarget);

        Path cleanExpiredFolder = Files.createDirectories(backupDir.resolve("2026-11-02"));
        Files.writeString(cleanExpiredFolder.resolve("OLD.csv"), "old");

        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneId.of("UTC"));
        BackupRetentionService service = new BackupRetentionService(
                stagingDir, backupDir, 15, ZoneId.of("UTC"), fixedClock);

        BackupRetentionService.CleanupSummary summary = service.cleanupExpiredBackups();

        assertThat(Files.exists(outsideTarget)).isTrue();
        assertThat(Files.exists(cleanExpiredFolder)).isFalse();
        assertThat(summary.deletedFiles()).isEqualTo(1);
        assertThat(summary.deletedFolders()).isEqualTo(1);
    }

    /**
     * Verifies that archiving fails fast with {@link SecurityException} if the configured
     * {@code backup/} root directory itself was replaced with a symbolic link.
     */
    @Test
    void refusesToArchiveWhenBackupRootIsSymlink() throws Exception {
        Path stagingDir = Files.createDirectories(tempDir.resolve("staging"));
        Path realBackupDir = Files.createDirectories(tempDir.resolve("real_backup"));
        Path symlinkBackupRoot = Files.createSymbolicLink(tempDir.resolve("backup_link"), realBackupDir);

        Path stagedCsv = Files.writeString(stagingDir.resolve("GL_ROOT_SYM.csv"), "A\n1\n");
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-22T02:00:00Z"), ZoneId.of("UTC"));
        BackupRetentionService service = new BackupRetentionService(
                stagingDir, symlinkBackupRoot, 15, ZoneId.of("UTC"), fixedClock);

        assertThatThrownBy(() -> service.archiveToBackup(stagedCsv))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("symbolic link backup root");
    }

    /**
     * Verifies that loose files placed directly in {@code backup/} are purged when older than
     * {@code retentionDays}, non-{@code yyyy-MM-dd} subdirectories are safely ignored, and
     * cleanup succeeds gracefully as a no-op when {@code staging/} and {@code backup/} do not exist yet.
     */
    @Test
    void cleansLooseExpiredBackupFilesIgnoresNonDateFoldersAndHandlesMissingDirs() throws Exception {
        Instant now = Instant.parse("2026-11-22T02:00:00Z");
        Clock fixedClock = Clock.fixed(now, ZoneId.of("UTC"));

        // 1. Non-existent staging/backup dirs -> safe no-op
        BackupRetentionService missingDirsService = new BackupRetentionService(
                tempDir.resolve("no_staging"), tempDir.resolve("no_backup"), 15, ZoneId.of("UTC"), fixedClock);
        BackupRetentionService.CleanupSummary emptySummary = missingDirsService.cleanupExpiredBackups();
        assertThat(emptySummary.deletedFiles()).isZero();
        assertThat(emptySummary.deletedFolders()).isZero();

        // 2. Loose expired file + loose recent file + non-date folder in backup/
        Path stagingDir = Files.createDirectories(tempDir.resolve("staging2"));
        Path backupDir = Files.createDirectories(tempDir.resolve("backup2"));

        Path expiredLoose = Files.writeString(backupDir.resolve("LOOSE_OLD.csv"), "old");
        Files.setLastModifiedTime(expiredLoose, FileTime.from(now.minus(20, ChronoUnit.DAYS)));

        Path recentLoose = Files.writeString(backupDir.resolve("LOOSE_RECENT.csv"), "recent");
        Files.setLastModifiedTime(recentLoose, FileTime.from(now.minus(2, ChronoUnit.DAYS)));

        Path nonDateDir = Files.createDirectories(backupDir.resolve("manual_notes"));
        Path noteFile = Files.writeString(nonDateDir.resolve("readme.txt"), "keep");

        BackupRetentionService service = new BackupRetentionService(
                stagingDir, backupDir, 15, ZoneId.of("UTC"), fixedClock);
        BackupRetentionService.CleanupSummary summary = service.cleanupExpiredBackups();

        assertThat(summary.deletedFiles()).isEqualTo(1);
        assertThat(summary.deletedFolders()).isZero();
        assertThat(Files.exists(expiredLoose)).isFalse();
        assertThat(Files.exists(recentLoose)).isTrue();
        assertThat(Files.exists(noteFile)).isTrue();
    }
}
