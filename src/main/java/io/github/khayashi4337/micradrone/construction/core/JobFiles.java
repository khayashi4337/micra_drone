package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.compile.PlacementManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The construction's files under the world data root (04 F-2/D-18): manifests named by content hash, one directory per
 * job (journal, ledger, outcome, the job itself, a program file only when the program differs from the kind's standard
 * shape, and the pending part of the write-ahead log while it awaits the owner's answer), one claims file, and one
 * placed-registry file per claim. Every write goes through {@link FileSystemPort#writeAtomic} so a torn file is never
 * produced here; every read that cannot be trusted ends in {@link JobLoad.Broken} instead of a guess.
 */
public final class JobFiles {
    /** One manifest per content hash: identical build lists share the file. */
    public static final String MANIFESTS_DIR = "manifests/";
    public static final String CLAIMS_DIR = "claims/";
    /** All claims in one small file (D-23): the claim map survives a lost registry. */
    public static final String CLAIMS_FILE = "claims.bin";
    public static final String PLACED_FILE = "placed.bin";
    /** A claim's supply switches (Task 27a); removed when the claim is released, like the registry file. */
    public static final String SUPPLY_FILE = "supply.bin";
    /** The write-ahead log's segments live here (FileWalSink, Task 25). */
    public static final String WAL_DIR = "wal/";
    public static final String MANIFEST_SUFFIX = ".bin";
    public static final String OUTCOME_FILE = "outcome.bin";
    /** Program files exist only for MODIFY/ROLLBACK and non-standard programs; there is no repair.bin. */
    public static final String PROGRAM_FILE = "program.bin";
    /** The job itself: admitted at once, updated at state changes and checkpoints (replaces SavedData). */
    public static final String JOB_FILE = "job.bin";
    /** The part of the write-ahead log that awaits the owner's answer; gone once it is decided. */
    public static final String PENDING_LOG_FILE = "pending_log.bin";

    private final FileSystemPort fs;

    public JobFiles(FileSystemPort fs) {
        this.fs = Objects.requireNonNull(fs, "fs");
    }

    /**
     * Writes every file of the job (the manifest only if its content-hash file is new). The pending part of the
     * write-ahead log is appended while an answer is awaited and deleted once answered; a standard program deletes an
     * existing program file.
     */
    public void saveJob(JobRecord r, LedgerBook ledgers) throws IOException {
        String dir = ConstructionJob.JOBS_DIR + r.job().jobId() + "/";
        String manifestPath = MANIFESTS_DIR + r.manifest().hash() + MANIFEST_SUFFIX;
        if (fs.read(manifestPath).isEmpty()) {
            fs.writeAtomic(manifestPath, envelope(SaveTypes.MANIFEST,
                    ManifestCodec.toTree(new ManifestCodec.Stored(r.manifest(), r.nodeTypes()))));
        }
        fs.writeAtomic(dir + ConstructionJob.JOURNAL_FILE,
                envelope(SaveTypes.JOURNAL, JournalCodec.toTree(r.journal())));
        fs.writeAtomic(dir + ConstructionJob.LEDGER_FILE,
                envelope(SaveTypes.LEDGER, LedgerCodec.toTree(ledgers.of(r.job().jobId()))));
        fs.writeAtomic(dir + OUTCOME_FILE, envelope(SaveTypes.OUTCOME, OutcomeCodec.toTree(r.outcome())));
        fs.writeAtomic(dir + JOB_FILE, envelope(SaveTypes.JOB, JobCodec.toTree(r.job())));
        JobProgram standard = standardProgram(r.job().kind(), r.manifest());
        if (standard != null && standard.equals(r.program())) {
            fs.delete(dir + PROGRAM_FILE);
        } else {
            fs.writeAtomic(dir + PROGRAM_FILE, envelope(SaveTypes.PROGRAM, ProgramCodec.toTree(r.program())));
        }
        if (r.pendingLog.isEmpty()) {
            fs.delete(dir + PENDING_LOG_FILE);
        } else {
            fs.writeAtomic(dir + PENDING_LOG_FILE, walFrames(r.pendingLog));
        }
    }

    /**
     * Reads one job's files back. Files that are missing, torn, sealed for a different type, or otherwise unreadable are
     * collected as reasons ("journal: missing", "ledger: ...") and produce a {@link JobLoad.Broken}; a missing or released
     * claim adds "claim: missing". Nothing is guessed.
     */
    public JobLoad loadJob(ConstructionJob saved, ClaimBook claims) throws IOException {
        String dir = ConstructionJob.JOBS_DIR + saved.jobId() + "/";
        List<String> reasons = new ArrayList<>();
        ManifestCodec.Stored stored = manifestOf(saved.manifestHash(), reasons);
        PlacementManifest manifest = stored == null ? null : stored.manifest();
        Map<String, String> nodeTypes = stored == null ? Map.of() : stored.nodeTypes();
        JobProgram program = programOf(saved, manifest, dir, reasons);
        Journal journal = null;
        Object journalTree = treeAt(SaveTypes.JOURNAL, dir + ConstructionJob.JOURNAL_FILE, "journal", reasons);
        if (journalTree != null) {
            try {
                int size = program == null ? saved.total() : program.size();
                journal = JournalCodec.fromTree(journalTree, JobService.journalCapacity(size));
            } catch (RuntimeException e) {
                reasons.add("journal: " + e.getMessage());
            }
        }
        MaterialLedger ledger = null;
        Object ledgerTree = treeAt(SaveTypes.LEDGER, dir + ConstructionJob.LEDGER_FILE, "ledger", reasons);
        if (ledgerTree != null) {
            try {
                ledger = LedgerCodec.fromTree(ledgerTree);
            } catch (RuntimeException e) {
                reasons.add("ledger: " + e.getMessage());
            }
        }
        JobOutcome outcome = null;
        Object outcomeTree = treeAt(SaveTypes.OUTCOME, dir + OUTCOME_FILE, "outcome", reasons);
        if (outcomeTree != null) {
            try {
                outcome = OutcomeCodec.fromTree(outcomeTree);
            } catch (RuntimeException e) {
                reasons.add("outcome: " + e.getMessage());
            }
        }
        List<WalEntry> pending = pendingLog(dir, reasons);
        SiteClaim claim = claims.find(saved.claimId()).filter(c -> !c.released()).orElse(null);
        if (claim == null) {
            reasons.add("claim: missing");
        }
        if (!reasons.isEmpty() || manifest == null || program == null || journal == null || ledger == null
                || outcome == null) {
            if (reasons.isEmpty()) {
                reasons.add("files: unreadable");
            }
            return new JobLoad.Broken(manifest, nodeTypes, reasons);
        }
        JobRecord r = new JobRecord(saved, manifest, nodeTypes, program, journal, outcome, claim.operatingBox());
        r.pendingLog = pending;
        return new JobLoad.Loaded(r, ledger);
    }

    public void saveClaims(ClaimBook claims) throws IOException {
        fs.writeAtomic(CLAIMS_FILE, envelope(SaveTypes.CLAIMS, ClaimCodec.toTree(claims)));
    }

    public Optional<ClaimBook> loadClaims(int maxPerOwner) throws IOException {
        Optional<byte[]> bytes = fs.read(CLAIMS_FILE);
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ClaimCodec.fromTree(migrated(SaveTypes.CLAIMS, bytes.get()), maxPerOwner));
    }

    public void saveRegistry(PlacedRegistry registry) throws IOException {
        fs.writeAtomic(CLAIMS_DIR + registry.claimId() + "/" + PLACED_FILE,
                envelope(SaveTypes.REGISTRY, RegistryCodec.toTree(registry)));
    }

    public Optional<PlacedRegistry> loadRegistry(String claimId) throws IOException {
        Optional<byte[]> bytes = fs.read(CLAIMS_DIR + claimId + "/" + PLACED_FILE);
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(RegistryCodec.fromTree(migrated(SaveTypes.REGISTRY, bytes.get())));
    }

    /** Writes the claim's supply switches (Task 27a). */
    public void saveSupply(String claimId, SupplySettings settings) throws IOException {
        fs.writeAtomic(CLAIMS_DIR + claimId + "/" + SUPPLY_FILE,
                envelope(SaveTypes.SUPPLY, SupplyCodec.toTree(settings)));
    }

    /** The claim's saved supply switches, or empty when it has none. */
    public Optional<SupplySettings> loadSupply(String claimId) throws IOException {
        Optional<byte[]> bytes = fs.read(CLAIMS_DIR + claimId + "/" + SUPPLY_FILE);
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(SupplyCodec.fromTree(migrated(SaveTypes.SUPPLY, bytes.get())));
    }

    /** Removes the claim's supply file; a missing file is not an error (a released claim keeps nothing). */
    public void deleteSupply(String claimId) throws IOException {
        fs.delete(CLAIMS_DIR + claimId + "/" + SUPPLY_FILE);
    }

    /** Every file under the root (jobs, manifests, claims, wal, claims.bin): the orphan sweep's input. */
    public List<String> allFiles() throws IOException {
        TreeSet<String> out = new TreeSet<>();
        out.addAll(fs.list(ConstructionJob.JOBS_DIR));
        out.addAll(fs.list(MANIFESTS_DIR));
        out.addAll(fs.list(CLAIMS_DIR));
        out.addAll(fs.list(WAL_DIR));
        if (fs.read(CLAIMS_FILE).isPresent()) {
            out.add(CLAIMS_FILE);
        }
        return List.copyOf(out);
    }

    /** The program every job of this kind has unless a file says otherwise: builds place the manifest, repairs start empty. */
    private static JobProgram standardProgram(JobKind kind, PlacementManifest manifest) {
        if (manifest == null) {
            return null;
        }
        return switch (kind) {
            case BUILD -> JobProgram.build(manifest);
            case REPAIR -> new JobProgram(List.of(), List.of());
            case MODIFY, ROLLBACK -> null;
        };
    }

    private ManifestCodec.Stored manifestOf(String hash, List<String> reasons) throws IOException {
        Object tree = treeAt(SaveTypes.MANIFEST, MANIFESTS_DIR + hash + MANIFEST_SUFFIX, "manifest", reasons);
        if (tree == null) {
            return null;
        }
        try {
            return ManifestCodec.fromTree(tree);
        } catch (RuntimeException e) {
            reasons.add("manifest: " + e.getMessage());
            return null;
        }
    }

    /**
     * The program file decides nothing when it is absent: BUILD and REPAIR rebuild their standard program (a non-standard
     * one was written, so a present file is always read). MODIFY and ROLLBACK have no standard; their file is required.
     */
    private JobProgram programOf(ConstructionJob saved, PlacementManifest manifest, String dir, List<String> reasons)
            throws IOException {
        Optional<byte[]> bytes = fs.read(dir + PROGRAM_FILE);
        if (bytes.isEmpty()) {
            JobProgram standard = standardProgram(saved.kind(), manifest);
            if (standard == null) {
                reasons.add("program: missing");
            }
            return standard;
        }
        if (manifest == null) {
            return null;
        }
        try {
            return ProgramCodec.fromTree(migrated(SaveTypes.PROGRAM, bytes.get()), manifest);
        } catch (UnreadableFileException | RuntimeException e) {
            reasons.add("program: " + e.getMessage());
            return null;
        }
    }

    private List<WalEntry> pendingLog(String dir, List<String> reasons) throws IOException {
        Optional<byte[]> bytes = fs.read(dir + PENDING_LOG_FILE);
        if (bytes.isEmpty()) {
            return List.of();
        }
        WalCodec.Frames frames = WalCodec.readFrames(bytes.get());
        if (frames.validLength() != bytes.get().length) {
            reasons.add("pending_log: torn at " + frames.validLength());
            return List.of();
        }
        return frames.entries();
    }

    /** The envelope's migrated payload tree, or null with the reason recorded (missing, torn, unreadable, wrong type). */
    private Object treeAt(String type, String path, String kind, List<String> reasons) throws IOException {
        Optional<byte[]> bytes = fs.read(path);
        if (bytes.isEmpty()) {
            reasons.add(kind + ": missing");
            return null;
        }
        try {
            return migrated(type, bytes.get());
        } catch (UnreadableFileException | RuntimeException e) {
            reasons.add(kind + ": " + e.getMessage());
            return null;
        }
    }

    private static Object migrated(String type, byte[] bytes) throws UnreadableFileException {
        PersistenceEnvelope e = PersistenceEnvelope.fromBytes(bytes);
        if (!type.equals(e.type())) {
            throw new UnreadableFileException("a " + e.type() + " file where a " + type + " file belongs");
        }
        return SaveTypes.migrations().payloadOf(e);
    }

    private static byte[] envelope(String type, Object tree) {
        return new PersistenceEnvelope(type, SaveTypes.currentVersions().get(type), tree).toBytes();
    }

    /** The pending part of the log as one sealed frame per entry (dropped frames count as torn). */
    private static byte[] walFrames(List<WalEntry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (WalEntry e : entries) {
            byte[] frame = WalCodec.frame(e);
            out.write(frame, 0, frame.length);
        }
        return out.toByteArray();
    }
}
