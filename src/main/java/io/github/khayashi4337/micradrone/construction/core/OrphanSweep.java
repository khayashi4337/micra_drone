package io.github.khayashi4337.micradrone.construction.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which files a job directory still owes the world once the jobs are loaded (04 F-2/D-23): a job's files live until its
 * claim is released, so a sweep looks at jobs(), claims() and what is on disk, and hands the caller the file names to
 * delete (never ledger contents — those decide nothing here).
 */
public final class OrphanSweep {
    private OrphanSweep() {
    }

    /** Ended jobs (cancelled, failed, rolled back, verified, partial) whose claims are released, or whose claims are gone. */
    public static Set<String> forgettableJobs(Collection<ConstructionJob> jobs, ClaimBook claims) {
        Set<String> out = new TreeSet<>();
        for (ConstructionJob j : jobs) {
            if (j.state().terminal() && claimGone(claims, j.claimId())) {
                out.add(j.jobId());
            }
        }
        return out;
    }

    /**
     * Of the given files, the ones nothing keeps alive: job directories of jobs that are not kept, manifest files no kept
     * job hashes to, and claim registries of released or missing claims (claims.bin itself stays).
     */
    public static List<String> orphanFiles(List<String> files, Collection<ConstructionJob> kept, ClaimBook claims) {
        Set<String> keptIds = new HashSet<>();
        Set<String> keptHashes = new HashSet<>();
        for (ConstructionJob j : kept) {
            keptIds.add(j.jobId());
            keptHashes.add(j.manifestHash());
        }
        List<String> out = new ArrayList<>();
        for (String f : files) {
            if (f.startsWith(ConstructionJob.JOBS_DIR)) {
                String rest = f.substring(ConstructionJob.JOBS_DIR.length());
                int slash = rest.indexOf('/');
                String id = slash >= 0 ? rest.substring(0, slash) : rest;
                if (!keptIds.contains(id)) {
                    out.add(f);
                }
            } else if (f.startsWith(JobFiles.MANIFESTS_DIR)) {
                String name = f.substring(JobFiles.MANIFESTS_DIR.length());
                String hash = name.endsWith(JobFiles.MANIFEST_SUFFIX)
                        ? name.substring(0, name.length() - JobFiles.MANIFEST_SUFFIX.length()) : name;
                if (!keptHashes.contains(hash)) {
                    out.add(f);
                }
            } else if (f.startsWith(JobFiles.CLAIMS_DIR)) {
                String rest = f.substring(JobFiles.CLAIMS_DIR.length());
                int slash = rest.indexOf('/');
                String id = slash >= 0 ? rest.substring(0, slash) : rest;
                if (claimGone(claims, id)) {
                    out.add(f);
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    private static boolean claimGone(ClaimBook claims, String claimId) {
        return claims.find(claimId).map(SiteClaim::released).orElse(true);
    }
}
