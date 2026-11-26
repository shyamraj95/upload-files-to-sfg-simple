# Oracle to IBM Sterling File Gateway (SFG) SFTP Exporter

A clean, lightweight, production-hardened **Java 21 + Spring Boot 4.1.3** worker application that extracts data from an Oracle database via PL/SQL (`SYS_REFCURSOR`) or parameterized SQL files, streams rows directly to timestamped CSV files with flat memory usage, uploads them atomically (`.tmp` → `.csv`) to **IBM Sterling File Gateway (SFG)** over hardened SFTP, and retains local backups for 15 days with automated daily cleanup.

---

## Table of Contents

1. [End-to-End Pipeline Flow](#end-to-end-pipeline-flow)
2. [Architectural Philosophy](#architectural-philosophy)
3. [Project Structure & Component Reference](#project-structure--component-reference)
4. [Prerequisites & Dependency Stack](#prerequisites--dependency-stack)
5. [Configuration Reference (`application.yml`)](#configuration-reference-applicationyml)
6. [Required Server Environment Variables](#required-server-environment-variables)
7. [Oracle Database Setup](#oracle-database-setup)
8. [Linux Server & IBM SFG Security Preparation](#linux-server--ibm-sfg-security-preparation)
9. [Adding a New Export Pipeline (Zero Code Changes)](#adding-a-new-export-pipeline-zero-code-changes)
10. [Running Multiple Queries on Different Schedules to Separate CSVs & SFG Paths](#running-multiple-queries-on-different-schedules-to-separate-csvs--sfg-paths)
11. [Building, Testing & Packaging](#building-testing--packaging)
12. [Deployment (`systemd` & Containers/Kubernetes)](#deployment-systemd--containerskubernetes)
13. [Observability, Failure Recovery & Horizontal Scaling](#observability-failure-recovery--horizontal-scaling)
14. [Troubleshooting Guide](#troubleshooting-guide)

---

## End-to-End Pipeline Flow

```text
  [Spring ThreadPoolTaskScheduler]
   (Dynamic CronTrigger per app.jobs[] entry)
                 │
                 ▼
  [ExportJobOrchestrator]  ──► Per-job ReentrantLock (prevents overlapping runs)
                 │             SLF4J MDC tagging (jobName, runId)
                 ▼
  [OracleDataExtractor]    ──► PL/SQL SYS_REFCURSOR (CallableStatement)
                 │             OR Parameterized .sql file (NamedParameterJdbcTemplate)
                 │             (Forward-only cursor, configurable fetchSize + queryTimeout)
                 ▼
  [StreamingCsvWriter]     ──► Row-by-row Apache Commons CSV (64 KB buffer, O(1) heap)
                 │             Writes staging/<PREFIX>_yyyyMMdd_HHmmss.csv.part
                 │             Atomically promotes .part -> .csv (chmod 0600)
                 ▼
  [AtomicSftpUploader]     ──► Hardened Apache MINA SshClient (strict known_hosts + modern crypto)
                 │             Streams to <remoteDir>/<filename>.tmp (64 KB buffered stream)
                 │             Atomically renames .tmp -> .csv (SFG never sees partial files)
                 │             Retries transient errors (jittered exponential backoff)
                 │             Fails fast on host-key mismatch / auth failure
                 ▼
  [BackupRetentionService] ──► Moves staging/<file>.csv -> backup/yyyy-MM-dd/<file>.csv
                               Daily @Scheduled sweep purges files & empty folders > 15 days
```

---

## Architectural Philosophy

- **Zero Over-Engineering:** Uses only essential Spring dependencies (`spring-boot-starter`, `spring-boot-starter-jdbc`, `spring-boot-starter-actuator`, `ojdbc11`, `commons-csv`, `spring-integration-sftp`). No Spring Batch, Quartz, JPA/Hibernate, or embedded web server (`spring.main.web-application-type: none`).
- **Flat O(1) Memory Usage:** Oracle `ResultSet` rows are never collected into a `List`. Both `STORED_PROCEDURE` and `SQL_QUERY` paths stream directly from the JDBC cursor into a 64 KB buffered CSV writer while reusing a single row array buffer and freeing Oracle temporary `CLOB`s per row.
- **Atomic Delivery to IBM SFG:** Local CSVs write to `.part` → `.csv`, and SFTP uploads stream to `<filename>.tmp` before issuing an atomic SFTP `rename` to `<filename>.csv`. Any mid-stream write or rename error triggers best-effort remote `.tmp` cleanup.
- **Defense-in-Depth Security:**
  - 100% environment-driven secrets (zero credentials in YAML or code; `SftpTargetConfig.toString()` redacts secrets).
  - Strict allowlist regex `^[a-zA-Z0-9_]+$` on job names, file prefixes, schemas, packages, procedures, cursor names, and parameter names.
  - Strict path-traversal validation (`ensureChildPath`, symlink skipping, remote path validation).
  - Strict OpenSSH `known_hosts` host-key verification enabled by default (`RejectAllServerKeyVerifier` fallback).
  - Modern SSH cipher, KEX, and MAC allowlists only (legacy CBC, 3DES, RC4, SHA-1, MD5 disabled).
  - Database row contents are never written to application logs.
- **Declarative Multi-Job Extensibility:** Adding a new Oracle-to-SFG job requires only adding a YAML block under `app.jobs[]` (and an optional `.sql` file)—no Java code changes or rebuilds.

---

## Project Structure & Component Reference

```text
oracle-sfg-exporter/
├── pom.xml                                                 # Maven build (Java 21, Spring Boot 4.1.3)
├── .env.example                                            # Environment variable template
├── .gitignore                                              # Excludes target/, .env, SSH keys, data/
├── README.md                                               # Project & operations guide
└── src/
    ├── main/
    │   ├── java/com/enterprise/sfgexporter/
    │   │   ├── SfgExporterApplication.java                 # Entry point (@SpringBootApplication, @EnableScheduling)
    │   │   ├── config/
    │   │   │   ├── AppProperties.java                      # Root @ConfigurationProperties(prefix = "app")
    │   │   │   ├── SchedulerConfiguration.java             # ThreadPoolTaskScheduler with non-blocking graceful shutdown
    │   │   │   └── SecurityValidator.java                  # Fail-fast startup validator & allowlist/path-traversal guards
    │   │   ├── model/
    │   │   │   ├── ExportJobConfig.java                    # Single pipeline YAML configuration model
    │   │   │   ├── SftpTargetConfig.java                   # Named IBM SFG target config (redacted toString)
    │   │   │   ├── CsvOptions.java                         # Delimiter, header, quote, charset, empty-file options
    │   │   │   ├── RetryConfig.java                        # Capped exponential backoff retry configuration
    │   │   │   ├── SourceType.java                         # STORED_PROCEDURE | SQL_QUERY enum
    │   │   │   └── JobExecutionResult.java                 # Immutable execution summary record (no row data)
    │   │   ├── db/
    │   │   │   └── OracleDataExtractor.java                # Streaming PL/SQL SYS_REFCURSOR & SQL file extractor
    │   │   ├── csv/
    │   │   │   └── StreamingCsvWriter.java                 # Streaming Apache Commons CSV writer (.part -> .csv)
    │   │   ├── sftp/
    │   │   │   ├── SshClientFactory.java                   # Hardened Apache MINA SshClient builder
    │   │   │   ├── SftpSessionFactoryRegistry.java         # Per-target DefaultSftpSessionFactory lifecycle manager
    │   │   │   └── AtomicSftpUploader.java                 # Atomic .tmp -> .csv uploader with exponential backoff retry
    │   │   └── scheduler/
    │   │       ├── BackupRetentionService.java             # staging/ -> backup/yyyy-MM-dd/ archiver & 15-day cleanup
    │   │       ├── DynamicJobScheduler.java                # Registers enabled app.jobs[] cron triggers on TaskScheduler
    │   │       ├── ExportJobOrchestrator.java              # End-to-end execution, per-job locking, MDC correlation
    │   │       └── ExportJobsHealthIndicator.java          # Spring Boot 4 Actuator HealthIndicator ("exportJobs")
    │   └── resources/
    │       ├── application.yml                             # Annotated multi-job & multi-target configuration
    │       └── sql/
    │           ├── hourly_payment_settlement.sql           # Sample parameterized SQL query
    │           └── sample_plsql_procedure.sql              # Reference Oracle PL/SQL SYS_REFCURSOR package DDL
    └── test/
        ├── java/com/enterprise/sfgexporter/
        │   ├── SfgExporterApplicationContextTest.java      # Full @SpringBootTest wiring & Actuator health test
        │   ├── config/SecurityValidatorTest.java           # SQLi, path traversal, cron, cipher & SQL resource checks
        │   ├── db/StreamingCsvWriterAndExtractorTest.java  # H2 streaming SQL-to-CSV, quoting, BigDecimal, PL/SQL builder
        │   ├── sftp/AtomicSftpUploaderIntegrationTest.java # Embedded Apache MINA SFTP server (key/pass/known_hosts/retry)
        │   ├── backup/BackupRetentionServiceTest.java      # Archiving, 15-day boundary retention & empty folder cleanup
        │   └── scheduler/DynamicJobSchedulerAndOrchestratorTest.java # Dynamic cron registration & end-to-end orchestration
        └── resources/sql/test_export.sql                   # Test parameterized SQL query
```

---

## Prerequisites & Dependency Stack

- **JDK:** Java 21 LTS (or newer)
- **Build Tool:** Apache Maven 3.9+
- **Core Stack (managed via `spring-boot-starter-parent:4.1.3`):**
  - `org.springframework.boot:spring-boot-starter`
  - `org.springframework.boot:spring-boot-starter-jdbc` (HikariCP, JDBC4 native `Connection.isValid()`)
  - `org.springframework.boot:spring-boot-starter-actuator` (health contributor, no web starter)
  - `com.oracle.database.jdbc:ojdbc11` (`runtime` scope, BOM-managed)
  - `org.apache.commons:commons-csv:1.14.1`
  - `org.springframework.integration:spring-integration-sftp` (Apache MINA SSHD, BOM-managed)
  - `com.fasterxml.jackson.core:jackson-annotations` (`provided` scope only — satisfies compile-time `@JsonInclude` metadata on `spring-boot-health` without packaging Jackson at runtime)
  - `org.springframework.boot:spring-boot-configuration-processor` (configured under `maven-compiler-plugin` `<annotationProcessorPaths>` to generate IDE metadata for `app.*`)

---

## Configuration Reference (`application.yml`)

All application settings live under `app.*` in [application.yml](src/main/resources/application.yml):

### `app.storage`
| Property | Default | Description |
| --- | --- | --- |
| `staging-dir` | `./data/staging` | Directory where CSVs are streamed before SFTP upload |
| `backup-dir` | `./data/backup` | Archive root containing `yyyy-MM-dd/` folders (must be distinct from `staging-dir`) |
| `sql-dir` | `""` | Optional allowlisted directory confining external `file:` SQL scripts (must be a non-symlink directory) |
| `retention-days` | `15` | Days to retain backup files/folders before deletion (`>= 1`) |
| `staging-part-max-age-hours` | `24` | Age in hours after which crash-orphaned `.part` staging files are purged (`>= 1`) |
| `cleanup-cron` | `0 0 2 * * *` | Spring 6-field cron expression for daily retention sweep |
| `cleanup-time-zone` | `UTC` | IANA time zone for cleanup schedule & archive date folders |

### `app.scheduler`
| Property | Default | Description |
| --- | --- | --- |
| `pool-size` | `4` | Max concurrent scheduled jobs on this instance (`>= 1`) |
| `thread-name-prefix` | `sfg-job-` | Worker thread naming prefix in logs |
| `await-termination-seconds` | `60` | Graceful shutdown wait limit for active jobs |

### `app.sftp-targets.<name>`
| Property | Default | Description |
| --- | --- | --- |
| `host` / `port` / `username` | — / `22` / — | IBM SFG SFTP connection target |
| `private-key-path` | — | Preferred auth: path to server-mounted SSH private key |
| `private-key-passphrase` | — | Optional passphrase for encrypted private key |
| `password` | — | Password auth fallback when `private-key-path` is unset |
| `known-hosts-path` | — | Required when `strict-host-key-checking: true` |
| `strict-host-key-checking` | `true` | Rejects unknown or rotated server host keys by default (`false` requires `app.allow-insecure-sftp-dev-mode: true`) |
| `temp-suffix` | `.tmp` | Suffix used during upload before atomic rename to `.csv` |
| `remote-base-dir` | `""` | Base mailbox path prepended to every job's `remote-dir` |
| `connect/auth/channel-open-timeout-ms` | `15000` | Deterministic SSH/SFTP timeouts (`>= 1000` ms) |
| `max-concurrent-uploads` | `2` | Per-target upload concurrency bulkhead (`>= 1`) |
| `allowed-ciphers` / `allowed-key-exchanges` / `allowed-macs` | Modern defaults | Strict allowlists validated against approved strong cryptographic algorithms |
| `retry.max-attempts` / `initial-backoff-ms` / `multiplier` / `max-backoff-ms` | `3` / `2000` / `2.0` / `30000` | Jittered exponential backoff settings for transient SFTP failures (`max-attempts <= 20`, `max-backoff-ms <= 300000`) |

### `app.jobs[]`
| Property | Default | Description |
| --- | --- | --- |
| `name` | *(required)* | Unique job identifier (`^[a-zA-Z0-9_]+$`) |
| `enabled` | `true` | Set `false` to skip scheduler registration without removing config |
| `cron` / `time-zone` | *(required)* / `UTC` | Spring 6-field cron (`sec min hour dom month dow`) & IANA zone |
| `source-type` | `STORED_PROCEDURE` | `STORED_PROCEDURE` or `SQL_QUERY` |
| `schema-name` / `package-name` / `procedure-name` / `cursor-param-name` | — / — / — / `P_RESULT_CURSOR` | Oracle PL/SQL target (each validated against `^[a-zA-Z0-9_]+$`) |
| `sql-file` | — | `classpath:sql/...` or `file:/...` `.sql` resource (for `SQL_QUERY`) |
| `parameters` | `{}` | Map of IN parameters (`param => ?` for PL/SQL or `:param` for SQL) |
| `fetch-size` / `query-timeout-seconds` | `1000` / `300` | Oracle JDBC cursor chunk size and statement timeout |
| `file-prefix` / `timestamp-pattern` | *(required)* / `yyyyMMdd_HHmmss` | Produces `<file-prefix>_<timestamp>.csv` |
| `csv.*` | `,`, header `true`, `"`, `\n`, `UTF-8`, empty `true`, `neutralize-formulas: true`, `max-clob-chars: 1048576` | Delimiter, header, quote char, record separator, null string, charset, `write-empty-file`, CSV formula injection neutralization, max LOB char limit |
| `sftp-target` / `remote-dir` | *(required)* / `""` | Key in `app.sftp-targets` and target SFG mailbox subdirectory |

---

## Required Server Environment Variables

All credentials and sensitive paths must be provided via server environment variables (see [.env.example](.env.example)):

| Variable | Required | Description |
| --- | --- | --- |
| `ORACLE_DB_URL` | Yes | Oracle JDBC URL (e.g., `jdbc:oracle:thin:@//oracle-db.internal:1521/ORCLPDB1`) |
| `ORACLE_DB_USER` | Yes | Least-privilege Oracle user (`EXECUTE` on packages / `SELECT` on views only) |
| `ORACLE_DB_PASSWORD` | Yes | Oracle database password |
| `APP_STAGING_DIR` | No | Local staging directory (default: `/var/app/sfg-exporter/staging`) |
| `APP_BACKUP_DIR` | No | Local backup directory (default: `/var/app/sfg-exporter/backup`) |
| `APP_SQL_DIR` | No | Optional base directory confining external `file:` SQL scripts (default: unset) |
| `APP_BACKUP_RETENTION_DAYS` | No | Backup retention window in days (default: `15`) |
| `SFG_PRIMARY_HOST` | Yes | Primary IBM SFG SFTP hostname or IP |
| `SFG_PRIMARY_PORT` | No | Primary IBM SFG SFTP port (default: `22`) |
| `SFG_PRIMARY_USER` | Yes | Primary IBM SFG SFTP login username |
| `SFG_SSH_KEY_PATH` | Key auth | Absolute path to SSH private key (`chmod 600`, preferred) |
| `SFG_SSH_KEY_PASSPHRASE` | Optional | Passphrase if the SSH private key is encrypted |
| `SFG_KNOWN_HOSTS_PATH` | Yes* | Absolute path to pinned OpenSSH `known_hosts` (*required when strict host-key checking is enabled) |
| `SFG_SECONDARY_*` / `SFG_SFTP_PASSWORD` | Optional | Secondary SFG target example (password auth fallback) |

---

## Oracle Database Setup

1. Create a dedicated read-only service user in Oracle (never use an application owner or DBA account):
   ```sql
   CREATE USER sfg_export_ro IDENTIFIED BY "<STRONG_PASSWORD>";
   GRANT CREATE SESSION TO sfg_export_ro;
   GRANT EXECUTE ON fin_reporting.pkg_sfg_exports TO sfg_export_ro;
   -- Only grant SELECT on specific tables/views needed by SQL_QUERY jobs:
   GRANT SELECT ON fin_reporting.payment_settlements TO sfg_export_ro;
   ```
2. Reference PL/SQL package DDL returning a `SYS_REFCURSOR` is provided in [src/main/resources/sql/sample_plsql_procedure.sql](src/main/resources/sql/sample_plsql_procedure.sql).
3. `OracleDataExtractor` invokes PL/SQL using named argument notation (`{call SCHEMA.PKG.PROC(P_ARG => ?, P_RESULT_CURSOR => ?)}`), so YAML parameter declaration order does not need to match the PL/SQL signature order and defaulted optional PL/SQL parameters work naturally.

---

## Linux Server & IBM SFG Security Preparation

### 1. Create a Dedicated Non-Root Service Account & Directories

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin sfgexporter

# Working directories (staging + 15-day backup archive)
sudo mkdir -p /var/app/sfg-exporter/staging /var/app/sfg-exporter/backup
sudo chown -R sfgexporter:sfgexporter /var/app/sfg-exporter
sudo chmod 700 /var/app/sfg-exporter /var/app/sfg-exporter/staging /var/app/sfg-exporter/backup

# External config & SSH key directories
sudo mkdir -p /etc/sfg-exporter/keys /etc/sfg-exporter/sql
sudo chown -R root:sfgexporter /etc/sfg-exporter
sudo chmod 750 /etc/sfg-exporter /etc/sfg-exporter/keys /etc/sfg-exporter/sql
```

### 2. Generate & Pin IBM SFG `known_hosts`

Strict host-key verification is enabled by default (`strict-host-key-checking: true`). Obtain and verify the IBM SFG server's host key out-of-band with your SFG administrator before deploying:

```bash
ssh-keyscan -p 22 -t ed25519,ecdsa,rsa sfg-prod.internal.corp | sudo tee /etc/sfg-exporter/keys/known_hosts
# Verify SHA-256 fingerprints against the official values provided by your SFG team:
ssh-keygen -lf /etc/sfg-exporter/keys/known_hosts

# Lock down key and known_hosts permissions
sudo chown sfgexporter:sfgexporter /etc/sfg-exporter/keys/sfg_ed25519 /etc/sfg-exporter/keys/known_hosts
sudo chmod 600 /etc/sfg-exporter/keys/sfg_ed25519
sudo chmod 640 /etc/sfg-exporter/keys/known_hosts
```

### 3. Enforced SSH Cryptography

`SshClientFactory` restricts Apache MINA SSHD to modern algorithms only:
- **Ciphers:** `aes256-gcm@openssh.com`, `aes128-gcm@openssh.com`, `chacha20-poly1305@openssh.com`, `aes256-ctr`, `aes192-ctr`, `aes128-ctr`
- **Key Exchange:** `curve25519-sha256`, `ecdh-sha2-nistp521/384/256`, `diffie-hellman-group18/16-sha512`, `diffie-hellman-group14-sha256`, `diffie-hellman-group-exchange-sha256`
- **MACs:** `hmac-sha2-512-etm@openssh.com`, `hmac-sha2-256-etm@openssh.com`, `hmac-sha2-512`, `hmac-sha2-256`

Weak algorithms (`cbc`, `3des`, `rc4`, `sha1`, `md5`, `diffie-hellman-group1`) are excluded.

---

## Adding a New Export Pipeline (Zero Code Changes)

### Option 1: PL/SQL Stored Procedure (`STORED_PROCEDURE`)

Append a new block under `app.jobs[]` in your externalized YAML config (`/etc/sfg-exporter/application.yml`):

```yaml
app:
  jobs:
    - name: daily_ap_invoices
      enabled: true
      cron: "0 30 5 * * *"
      time-zone: "UTC"
      source-type: STORED_PROCEDURE
      schema-name: "FIN_REPORTING"
      package-name: "PKG_SFG_EXPORTS"
      procedure-name: "SP_EXPORT_AP_INVOICES"
      cursor-param-name: "P_RESULT_CURSOR"
      parameters:
        "[P_COMPANY_CODE]": "US01"
      fetch-size: 2000
      file-prefix: "AP_INVOICE"
      sftp-target: sfg-primary
      remote-dir: "/finance/ap"
```

### Option 2: External Parameterized SQL File (`SQL_QUERY`)

1. Save your query to `/etc/sfg-exporter/sql/daily_vendor_balances.sql` (using `:param` placeholders; no trailing semicolon required).
2. Append the job block to `app.jobs[]`:

```yaml
app:
  jobs:
    - name: daily_vendor_balances
      enabled: true
      cron: "0 45 5 * * *"
      time-zone: "America/New_York"
      source-type: SQL_QUERY
      sql-file: "file:/etc/sfg-exporter/sql/daily_vendor_balances.sql"
      parameters:
        status: "ACTIVE"
      file-prefix: "VENDOR_BAL"
      csv:
        delimiter: "|"
        include-header: true
        write-empty-file: false
      sftp-target: sfg-primary
      remote-dir: "/finance/vendors"
```

All names, SQL identifiers, and prefixes are validated at startup against `^\w+$` (ASCII `[a-zA-Z0-9_]`); invalid entries or missing `.sql` files fail fast immediately on boot.

---

## Running Multiple Queries on Different Schedules to Separate CSVs & SFG Paths

Running two (or more) independent queries on different schedules—each producing its own timestamped CSV and uploading to a separate IBM SFG directory or server—requires **zero Java code changes**.

### Step 1: Create Separate SQL Files

Place each query in its own `.sql` file under `src/main/resources/sql/` (packaged in the JAR) or on the host under `/etc/sfg-exporter/sql/` (externalized, editable without rebuilding):

- **Query 1** (`/etc/sfg-exporter/sql/payment_settlements.sql` — runs every 15 minutes):
  ```sql
  SELECT payment_id, batch_reference, currency_code, amount, updated_at_utc
  FROM fin_reporting.payment_settlements
  WHERE settlement_status = :settlement_status
    AND updated_at_utc >= (SYSTIMESTAMP AT TIME ZONE 'UTC') - NUMTODSINTERVAL(:lookback_minutes, 'MINUTE')
  ORDER BY updated_at_utc ASC, payment_id ASC
  ```
- **Query 2** (`/etc/sfg-exporter/sql/daily_reconciliation.sql` — runs once daily at 06:30 UTC):
  ```sql
  SELECT account_number, currency_code, opening_balance, closing_balance, business_date
  FROM fin_reporting.daily_reconciliation
  WHERE business_date = TRUNC(SYSDATE) - :lookback_days
  ORDER BY account_number ASC
  ```

### Step 2: Configure Two Independent Entries Under `app.jobs[]`

Add both jobs to `application.yml` (or `/etc/sfg-exporter/application.yml`), giving each its own `cron`, `time-zone`, `sql-file`, `file-prefix`, `sftp-target`, and `remote-dir`:

```yaml
app:
  sftp-targets:
    "[sfg-primary]":
      host: ${SFG_PRIMARY_HOST}
      port: ${SFG_PRIMARY_PORT:22}
      username: ${SFG_PRIMARY_USER}
      private-key-path: ${SFG_SSH_KEY_PATH}
      private-key-passphrase: ${SFG_SSH_KEY_PASSPHRASE:}
      known-hosts-path: ${SFG_KNOWN_HOSTS_PATH}
      strict-host-key-checking: true
      temp-suffix: ".tmp"
      remote-base-dir: "/inbound"

  jobs:
    # Query 1: Runs every 15 minutes -> generates PMT_SETTLEMENT_*.csv -> uploads to /inbound/payments/settled/
    - name: payment_settlements_15m
      enabled: true
      cron: "0 */15 * * * *"
      time-zone: "UTC"
      source-type: SQL_QUERY
      sql-file: "file:/etc/sfg-exporter/sql/payment_settlements.sql"
      parameters:
        "[settlement_status]": "SETTLED"
        "[lookback_minutes]": 15
      fetch-size: 1000
      query-timeout-seconds: 180
      file-prefix: "PMT_SETTLEMENT"
      timestamp-pattern: "yyyyMMdd_HHmmss"
      csv:
        delimiter: ","
        include-header: true
        write-empty-file: false
      sftp-target: sfg-primary
      remote-dir: "/payments/settled"

    # Query 2: Runs daily at 06:30 America/New_York -> generates DAILY_RECON_*.csv -> uploads to /inbound/finance/recon/
    - name: daily_reconciliation_export
      enabled: true
      cron: "0 30 6 * * *"
      time-zone: "America/New_York"
      source-type: SQL_QUERY
      sql-file: "file:/etc/sfg-exporter/sql/daily_reconciliation.sql"
      parameters:
        "[lookback_days]": 1
      fetch-size: 2000
      query-timeout-seconds: 600
      file-prefix: "DAILY_RECON"
      timestamp-pattern: "yyyyMMdd_HHmmss"
      csv:
        delimiter: "|"
        include-header: true
        write-empty-file: true
      sftp-target: sfg-primary              # Or point to a second target (e.g. `sfg-partner-b`) for another SFG server
      remote-dir: "/finance/recon"
```

#### Common Spring 6-Field Cron Schedules (`sec min hour day-of-month month day-of-week`)

| Cadence | Cron Expression |
| --- | --- |
| Every 15 minutes | `0 */15 * * * *` |
| Every hour at `:10` | `0 10 * * * *` |
| Every 4 hours | `0 0 */4 * * *` |
| Daily at 06:30 | `0 30 6 * * *` |
| Weekdays (Mon–Fri) at 18:00 | `0 0 18 * * MON-FRI` |

### Step 3: Tune Thread & Connection Pool Sizing (If Schedules Can Overlap)

- Each job runs independently on `ThreadPoolTaskScheduler` (`app.scheduler.pool-size`, default `4`) using its own HikariCP Oracle connection (`spring.datasource.hikari.maximum-pool-size`, default `5`).
- If you configure more than 4 jobs whose schedules can fire at the same time, increase both `app.scheduler.pool-size` and `spring.datasource.hikari.maximum-pool-size` accordingly.
- Each job has its own per-job `ReentrantLock` in `ExportJobOrchestrator`, so a slow run of Query 1 skips overlapping ticks of Query 1 without ever blocking Query 2.

### Step 4: Verify Startup & Output Paths

1. Restart the application and verify `DynamicJobScheduler` logs both jobs at startup:
   ```text
   Registered scheduled export job 'payment_settlements_15m' with cron='0 */15 * * * *' (zone='UTC', target='sfg-primary')
   Registered scheduled export job 'daily_reconciliation_export' with cron='0 30 6 * * *' (zone='America/New_York', target='sfg-primary')
   Successfully registered 2 active export job(s)
   ```
2. Verify separate outputs & archives:
   - **SFG Path 1:** `/inbound/payments/settled/PMT_SETTLEMENT_<yyyyMMdd_HHmmss>.csv` (uploaded as `.tmp`, atomically renamed to `.csv`)
   - **SFG Path 2:** `/inbound/finance/recon/DAILY_RECON_<yyyyMMdd_HHmmss>.csv` (uploaded as `.tmp`, atomically renamed to `.csv`)
   - **Local 15-day backup:** Both CSVs are archived under `${APP_BACKUP_DIR}/yyyy-MM-dd/` with distinct prefixes and cleaned up automatically after `retention-days`.

---

## Building, Testing & Packaging

```bash
# Clean compile (with -Xlint:all,-processing,-classfile) and run all 54 tests
mvn clean test

# Build executable Spring Boot fat JAR (target/oracle-sfg-exporter-1.0.0.jar)
mvn clean package
```

### What the Test Suite Covers (54 Tests)
- `SfgExporterApplicationContextTest` — Boots the full Spring Boot 4.1.3 context with H2 and verifies bean wiring, HikariCP Oracle socket timeouts, startup validation, scheduler registration, clean shutdown, and Actuator health (`UP`).
- `SecurityValidatorTest` — Rejects SQL injection in procedure names, path traversal/UNC paths/symlinks in file prefixes/remote dirs/`sql-dir`/SQL paths, overlapping staging & backup dirs, missing `known_hosts`, disabled strict host-key checking without dev opt-in, missing auth, missing SQL files, and weak/disallowed SSH algorithms.
- `StreamingCsvWriterAndExtractorTest` — Streams real H2 JDBC `ResultSet` rows to timestamped CSV, verifies quoting/pipes/`BigDecimal`/nulls, CSV formula injection neutralization (cells, headers, leading whitespace/newlines), bounded `CLOB`/`SQLXML` & `BLOB` resource freeing, `write-empty-file: false` skipping, oversized SQL rejection, statement cleanup on bind errors, and safe PL/SQL call generation.
- `AtomicSftpUploaderIntegrationTest` — Spins up a real embedded Apache MINA SFTP server to verify SSH private-key auth, password auth, strict `0600` key/`known_hosts` permission checks, atomic `.tmp` → `.csv` rename, strict `known_hosts` rejection, exponential backoff retry with bulkhead release during sleep, partial `.tmp` cleanup on mid-stream failure, and cyclic exception cause / control-char sanitization.
- `BackupRetentionServiceTest` — Verifies atomic archiving into `backup/yyyy-MM-dd/`, symlink rejection (staging files, backup root, date folders, and expired folder entries), orphan `.part` staging cleanup, deletion of files/folders older than 15 days, retention of the exact 15-day boundary, and pruning of stale empty folders.
- `DynamicJobSchedulerAndOrchestratorTest` — Verifies enabled-only dynamic cron registration, end-to-end orchestration, and safe failure reporting without duplicate SFTP re-upload if local archiving fails after upload.

*(Note: `maven-surefire-plugin` passes `-Xshare:off -javaagent:...byte-buddy-agent...` solely during `mvn test` to prevent Java 21 JEP 451 dynamic agent warnings from Mockito; it has zero effect on the production JAR.)*

---

## Deployment (`systemd` & Containers/Kubernetes)

### Option A: Linux `systemd` Service

1. Copy `target/oracle-sfg-exporter-1.0.0.jar` to `/opt/sfg-exporter/`.
2. Populate `/etc/sfg-exporter/exporter.env` from [.env.example](.env.example) and lock permissions:
   ```bash
   sudo chown sfgexporter:sfgexporter /etc/sfg-exporter/exporter.env
   sudo chmod 600 /etc/sfg-exporter/exporter.env
   ```
3. Create `/etc/systemd/system/sfg-exporter.service`:
   ```ini
   [Unit]
   Description=Oracle to IBM SFG SFTP Exporter
   After=network-online.target
   Wants=network-online.target

   [Service]
   Type=simple
   User=sfgexporter
   Group=sfgexporter
   EnvironmentFile=/etc/sfg-exporter/exporter.env
   ExecStart=/usr/bin/java -Xms256m -Xmx512m -XX:+UseG1GC -jar /opt/sfg-exporter/oracle-sfg-exporter-1.0.0.jar --spring.config.additional-location=optional:file:/etc/sfg-exporter/application.yml
   Restart=on-failure
   RestartSec=10
   SuccessExitStatus=143

   # OS-level sandboxing
   NoNewPrivileges=true
   PrivateTmp=true
   ProtectSystem=strict
   ReadWritePaths=/var/app/sfg-exporter

   [Install]
   WantedBy=multi-user.target
   ```
4. Enable and start:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now sfg-exporter
   sudo journalctl -u sfg-exporter -f
   ```

### Option B: Docker / Kubernetes Notes

- Run as a non-root UID (`runAsNonRoot: true`, `readOnlyRootFilesystem: true`).
- Mount an `emptyDir` or PersistentVolumeClaim at `/var/app/sfg-exporter` (so 15-day backups persist across pod restarts).
- Mount SSH private key (`defaultMode: 0400`) and `known_hosts` (`defaultMode: 0440`) as read-only Kubernetes `Secret` volumes, and inject Oracle/passphrase credentials via `envFrom: secretRef`.
- Mount `app.jobs[]` via a `ConfigMap` referenced with `--spring.config.additional-location=file:/config/application.yml`.

---

## Observability, Failure Recovery & Horizontal Scaling

- **Structured MDC Logging:** Every log line during a job run includes `[job=<jobName> run=<runId>]` for instant log aggregation in Splunk/ELK/Datadog. JDBC and SSHD loggers are set to `WARN` so SQL parameters and row values are never emitted.
- **Actuator Health (`exportJobs`):** `ExportJobsHealthIndicator` reports `configuredJobs`, `enabledJobs`, and `lastRuns` (status, row count, filename, timestamp, duration). If any job's latest run fails, health flips to `DOWN` until its next successful run. *(Add `spring-boot-starter-webmvc` only if you want `/actuator/health` exposed over HTTP instead of JMX.)*
- **Failure Recovery:**
  - If Oracle extraction fails, partial `.part` files are deleted automatically.
  - If SFTP upload fails permanently after all retries, the complete staged `.csv` is **retained in `staging/`** and its filename is logged so operators can inspect or manually deliver it without re-running heavy Oracle extracts.
- **Horizontal Scaling:**
  - Single-node overlap protection is built in via per-job `ReentrantLock`.
  - **Recommended multi-node pattern (Zero-Coordination Sharding):** Partition `app.jobs[]` across separate worker deployments (e.g., `finance-exporter` vs. `payments-exporter`) using externalized YAML files.
  - **Active-Active Shared Cluster:** If deploying identical replicas with the same `app.jobs[]`, wrap `ExportJobOrchestrator.executeJob(...)` with a lightweight DB-backed distributed lock (such as ShedLock backed by an Oracle lock table).

---

## Troubleshooting Guide

| Symptom | Likely Cause | Resolution |
| --- | --- | --- |
| Startup fails with `Invalid ... must strictly match ^[a-zA-Z0-9_]+$` | Whitespace, hyphen, dot, or special character in a job name, SQL identifier, or file prefix | Use alphanumeric + underscore characters only (`A-Z`, `a-z`, `0-9`, `_`) |
| `SSH2_DISCONNECT_HOST_KEY_NOT_VERIFIABLE` / `Server key did not validate` | Target SFG host key is missing from `known_hosts` or was rotated | Run `ssh-keyscan`, verify fingerprint with SFG admin, and update `SFG_KNOWN_HOSTS_PATH` |
| `No matching cipher/kex/mac found` during SFTP handshake | IBM SFG server offers a narrower subset of modern algorithms | Check SFG's supported modern algorithms and adjust `allowed-ciphers`, `allowed-key-exchanges`, or `allowed-macs` (avoid weak SHA-1/CBC) |
| `ORA-06550` / wrong number or types of arguments | YAML `parameters` key or `cursor-param-name` doesn't match Oracle PL/SQL parameter names | Verify exact parameter names in Oracle `ALL_ARGUMENTS` view |
| `SQL resource not found` at startup | Typo in `sql-file` or file not readable by `sfgexporter` | Verify `classpath:sql/...` or `file:/...` path and `chmod 640` permissions |
