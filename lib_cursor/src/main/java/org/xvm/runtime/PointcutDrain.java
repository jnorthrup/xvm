package org.xvm.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Orchestrates the pointcut drain pipeline: lifecycle gate -> rollup -> file artifacts.
 *
 * Accepts a TypedefCascadeTable directly — no dependency on VmPointcutPublisher
 * or TypedefResolutionPublisher. Those wire in at VM runtime via their static inits.
 *
 * Lifecycle: RUNNING -> drain() -> DRAINING -> shutdown() -> SHUTDOWN
 */
public final class PointcutDrain {

    private final XvmLifecycle lifecycle;
    private final Path outputDir;
    private final TypedefCascadeTable table;
    private boolean drained;
    private boolean shutDown;

    public PointcutDrain(XvmLifecycle lifecycle, TypedefCascadeTable table, Path outputDir) {
        this.lifecycle = lifecycle;
        this.table = table;
        this.outputDir = outputDir;
    }

    /**
     * Drain: roll up the cascade table and write file artifacts.
     * Transitions RUNNING -> DRAINING. Idempotent after first call.
     */
    public void drain() {
        if (drained) return;
        if (!lifecycle.isRunning()) {
            throw new IllegalStateException("drain() requires RUNNING, got " + lifecycle.state());
        }
        writeArtifacts();
        lifecycle.drain();
        drained = true;
    }

    /**
     * Shut down the drain pipeline.
     * Transitions DRAINING -> SHUTDOWN.
     */
    public void shutdown() {
        if (!lifecycle.isDraining()) {
            throw new IllegalStateException("shutdown() requires DRAINING, got " + lifecycle.state());
        }
        shutDown = true;
        lifecycle.shutdown();
    }

    public boolean isDrained() { return drained; }
    public boolean isShutDown() { return shutDown; }

    // ── File artifact writers ───────────────────────────────────────────

    private void writeArtifacts() {
        try {
            Files.createDirectories(outputDir);
            var snap = CascadeRollup.cascadeRollup(table);
            writeCascadeCsv(snap);
            writeJointHistogramCsv(snap);
            writeTableDumpCsv();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write drain artifacts", e);
        }
    }

    private void writeCascadeCsv(CascadeRollup.TierSnapshot[] snap) throws IOException {
        var sb = new StringBuilder();
        sb.append("tier,total_events\n");
        for (var tier : snap) {
            sb.append(tier.tier).append(',').append(tier.totalEvents).append('\n');
        }
        Files.writeString(outputDir.resolve("cascade.csv"), sb.toString());
    }

    private void writeJointHistogramCsv(CascadeRollup.TierSnapshot[] snap) throws IOException {
        var sb = new StringBuilder();
        sb.append("kind,scope,count\n");
        var joint = snap[3].jointHistogram;
        int S = TypedefCascadeTable.SCOPE_COUNT;
        int K = TypedefCascadeTable.KIND_COUNT;
        if (joint != null) {
            for (int k = 0; k < K; k++) {
                for (int s = 0; s < S; s++) {
                    sb.append(k).append(',').append(s).append(',')
                      .append(joint[k * S + s]).append('\n');
                }
            }
        }
        Files.writeString(outputDir.resolve("joint_histogram.csv"), sb.toString());
    }

    private void writeTableDumpCsv() throws IOException {
        var sb = new StringBuilder();
        sb.append("row,site_ord,kind,scope,success,depth,pool_id\n");
        int n = table.rowCount();
        var siteOrd = table.siteOrdColumn();
        var kind = table.kindColumn();
        var scope = table.scopeColumn();
        var success = table.successColumn();
        var depth = table.depthColumn();
        var poolId = table.poolIdColumn();
        for (int i = 0; i < n; i++) {
            sb.append(i).append(',')
              .append(siteOrd[i] & 0xFF).append(',')
              .append(kind[i]).append(',')
              .append(scope[i]).append(',')
              .append(success[i]).append(',')
              .append(depth[i]).append(',')
              .append(poolId[i]).append('\n');
        }
        Files.writeString(outputDir.resolve("table_dump.csv"), sb.toString());
    }
}
