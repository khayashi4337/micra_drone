package io.github.khayashi4337.micradrone.construction.core;

import io.github.khayashi4337.micradrone.build.model.Box;
import io.github.khayashi4337.micradrone.build.model.IntPos;
import io.github.khayashi4337.micradrone.build.model.Issue;
import io.github.khayashi4337.micradrone.build.model.IssueCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The server's claims (04 F-4): a new site may not overlap another live claim's operating box in the same dimension,
 * and an owner holds at most a limited number. Reservation happens on the main thread, one approval after another, so
 * two overlapping approvals in one tick can never both win.
 */
public final class ClaimBook {
    public static final int DEFAULT_MAX_CLAIMS_PER_OWNER = 8;
    static final String SUBJECT_SITE = "site";
    static final String DATA_CLAIM = "claim";
    static final String DATA_OWNER = "owner";
    static final String DATA_LIMIT = "limit";

    private final int maxPerOwner;
    private final Map<String, SiteClaim> claims = new LinkedHashMap<>();

    public ClaimBook(int maxPerOwner) {
        this.maxPerOwner = maxPerOwner;
    }

    public List<Issue> check(UUID owner, String dimension, Box operatingBox, String exceptClaimId) {
        List<Issue> out = new ArrayList<>();
        int mine = 0;
        for (SiteClaim c : claims.values()) {
            if (c.released() || c.claimId().equals(exceptClaimId)) {
                continue;
            }
            if (c.ownerUuid().equals(owner)) {
                mine++;
            }
            if (c.dimension().equals(dimension) && overlaps(c.operatingBox(), operatingBox)) {
                out.add(Issue.of(IssueCode.E_CLAIM_OVERLAP, c.claimId(), List.of(SUBJECT_SITE),
                        "ここは、ほかの区画(" + c.claimId() + ")と重なっています",
                        Map.of(DATA_CLAIM, c.claimId(), DATA_OWNER, c.ownerUuid().toString()), List.of()));
            }
        }
        if (exceptClaimId == null && mine >= maxPerOwner) {
            out.add(Issue.of(IssueCode.E_CLAIM_LIMIT, "", List.of(SUBJECT_SITE),
                    "区画は1人" + maxPerOwner + "個までです。使わない建物を片付けてください",
                    Map.of(DATA_LIMIT, String.valueOf(maxPerOwner)), List.of()));
        }
        return out;
    }

    /**
     * A job that works on an existing claim (MODIFY, REPAIR, ROLLBACK) needs that claim to be live, in the job's
     * dimension, and held by the job's owner; otherwise it could touch somebody else's site or a released one.
     */
    public List<Issue> checkOwned(String claimId, UUID owner, String dimension) {
        SiteClaim c = claimId == null ? null : claims.get(claimId);
        String why = c == null ? "no such claim" : c.released() ? "released" : !c.dimension().equals(dimension)
                ? "another dimension" : !c.ownerUuid().equals(owner) ? "another owner" : null;
        if (why == null) {
            return List.of();
        }
        return List.of(Issue.of(IssueCode.E_CLAIM_INVALID, String.valueOf(claimId), List.of(SUBJECT_SITE),
                "この区画(" + claimId + ")では作業できません: " + why, Map.of(DATA_CLAIM, String.valueOf(claimId)), List.of()));
    }

    /** A MODIFY that reaches further grows its claim to cover both the old and the new site, in one step. */
    public SiteClaim grow(String claimId, Box worldBox, Box operatingBox) {
        SiteClaim c = claims.get(claimId);
        if (c == null || c.released()) {
            throw new IllegalArgumentException("no live claim " + claimId);
        }
        SiteClaim grown = new SiteClaim(c.schemaVersion(), c.claimId(), c.ownerUuid(), c.dimension(), union(c.worldBox(), worldBox),
                union(c.operatingBox(), operatingBox), false, c.createdTick());
        claims.put(claimId, grown);
        return grown;
    }

    public SiteClaim reserve(String claimId, UUID owner, String dimension, Box worldBox, Box operatingBox, long tick) {
        if (claims.containsKey(claimId)) {
            throw new IllegalStateException("claim " + claimId + " exists already");
        }
        SiteClaim c = new SiteClaim(SiteClaim.SCHEMA_VERSION, claimId, owner, dimension, worldBox, operatingBox, false, tick);
        claims.put(claimId, c);
        return c;
    }

    public void release(String claimId) {
        SiteClaim c = claims.get(claimId);
        if (c == null) {
            throw new IllegalArgumentException("no claim " + claimId);
        }
        claims.put(claimId, c.release());
    }

    public void restore(SiteClaim c) {
        claims.put(c.claimId(), c);
    }

    public Optional<SiteClaim> find(String claimId) {
        return Optional.ofNullable(claims.get(claimId));
    }

    public List<SiteClaim> active() {
        return claims.values().stream().filter(c -> !c.released()).toList();
    }

    public List<SiteClaim> all() {
        return List.copyOf(claims.values());
    }

    public Optional<SiteClaim> claimAt(String dimension, IntPos pos) {
        return active().stream().filter(c -> c.dimension().equals(dimension)
                && c.operatingBox().contains(pos.x(), pos.y(), pos.z())).findFirst();
    }

    public static boolean overlaps(Box a, Box b) {
        return a.minA() <= b.maxA() && b.minA() <= a.maxA() && a.minB() <= b.maxB() && b.minB() <= a.maxB()
                && a.minC() <= b.maxC() && b.minC() <= a.maxC();
    }

    public static Box union(Box a, Box b) {
        return new Box(Math.min(a.minA(), b.minA()), Math.min(a.minB(), b.minB()), Math.min(a.minC(), b.minC()),
                Math.max(a.maxA(), b.maxA()), Math.max(a.maxB(), b.maxB()), Math.max(a.maxC(), b.maxC()));
    }
}
