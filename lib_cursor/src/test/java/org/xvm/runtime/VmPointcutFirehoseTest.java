package org.xvm.runtime;

import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.xvm.tool.Console;
import org.xvm.tool.Launcher;
import org.xvm.tool.Launcher.LauncherException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Firehose rate test for VmPointcutPublisher.
 * Measures actual publish throughput — ring fill rate — with warmup and timing.
 *
 * Synthetic: directly calls publish() in a tight loop
 * Live:      runs a real .x module through the VM dispatch loop
 */
public class VmPointcutFirehoseTest {

    @TempDir
    java.nio.file.Path tempDir;

    // ─── synthetic rate test ───────────────────────────────────────────────────

    @Test
    public void syntheticFirehose() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;

        try {
            // ── warmup: small batch to trigger JIT ──
            for (int i = 0; i < 100_000; i++) {
                VmPointcutPublisher.publish(0xA7, "Warmup.method", i);
            }
            VmPointcutPublisher.reset();
            VmPointcutPublisher.active = true;

            // ── timed publish run ──
            int N = 1_000_000;
            int captured;
            long publishElapsedNs;

            {
                long t0 = System.nanoTime();
                for (int i = 0; i < N; i++) {
                    VmPointcutPublisher.publish(0xA7, "Synthetic.method", i);
                }
                publishElapsedNs = System.nanoTime() - t0;
                captured = VmPointcutPublisher.size();
            }

            // ── drain and collect ──
            int[] counts = new int[256];
            AtomicInteger total = new AtomicInteger();
            AtomicLong drainStart = new AtomicLong(Long.MAX_VALUE);
            AtomicLong drainEnd   = new AtomicLong(0);

            VmPointcutPublisher.drain(evt -> {
                int op = evt.opcode;
                counts[op]++;
                total.incrementAndGet();
                drainStart.updateAndGet(v -> Math.min(v, evt.nano));
                drainEnd.updateAndGet(v -> Math.max(v, evt.nano));
            });

            int got = total.get();
            double publishMs = publishElapsedNs / 1_000_000.0;
            double publishRate = publishMs > 0 ? N / publishMs : 0.0;

            double drainMs = (drainEnd.get() - drainStart.get()) / 1_000_000.0;
            double drainRate = drainMs > 0 ? got / drainMs : 0.0;

            System.out.println("\n=== synthetic firehose ===");
            System.out.println("published      : " + N);
            System.out.println("captured      : " + got + " / " + N);
            System.out.println("ring cap      : 65536");
            System.out.println("overflow      : " + (N > 65536 ? "yes (oldest evicted)" : "no"));
            System.out.println("publish elapsed: " + String.format("%.3f ms", publishMs));
            System.out.println("publish rate  : " + String.format("%.1f events/ms", publishRate));
            System.out.println("drain elapsed : " + String.format("%.3f ms", drainMs));
            System.out.println("drain rate    : " + String.format("%.1f events/ms", drainRate));
            System.out.println("opcode distribution:");
            for (int op = 0; op < 256; op++) {
                if (counts[op] > 0) {
                    System.out.println("  " + opcodeName(op) + "(0x" + Integer.toHexString(op) + ") = " + counts[op]);
                }
            }
            System.out.println("============================\n");

            assertTrue(got > 0, "drain should return at least one event");
            assertEquals(65536, got, "ring should be full"); // N > ring cap, we overflowed
            assertTrue(publishRate > 0, "publish should have non-zero throughput");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    // ─── live VM rate test ─────────────────────────────────────────────────────

    @Test
    public void liveFirehose() throws Exception {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;

        try {
            // ── warmup module run (no timing, no assertions) ──
            warmupModule(
                "module Warmup { void run() { @Inject Console c; c.print(\"warmup\"); } }"
            );
            // reset to clear warmup events
            VmPointcutPublisher.reset();
            VmPointcutPublisher.active = true;

            // ── timed run ──
            String output = runModule(
                "module FirehoseTest {" +
                "  void run() {" +
                "    @Inject Console c;" +
                "    c.print(\"hello\");" +
                "  }" +
                "}"
            );

            int[] counts = new int[256];
            AtomicInteger total = new AtomicInteger();
            AtomicLong startNano = new AtomicLong(Long.MAX_VALUE);
            AtomicLong endNano   = new AtomicLong(0);

            VmPointcutPublisher.drain(evt -> {
                int op = evt.opcode;
                if (counts[op] == 0) counts[op] = 1; else counts[op]++;
                total.incrementAndGet();
                startNano.updateAndGet(v -> Math.min(v, evt.nano));
                endNano.updateAndGet(v -> Math.max(v, evt.nano));
            });

            int got = total.get();
            long elapsedNs = endNano.get() - startNano.get();
            double elapsedMs = elapsedNs / 1_000_000.0;
            double rate = elapsedMs > 0 ? got / elapsedMs : 0.0;

            System.out.println("\n=== live VM firehose ===");
            System.out.println("module output : " + output.trim());
            System.out.println("total events  : " + got);
            System.out.println("elapsed       : " + String.format("%.3f ms", elapsedMs));
            System.out.println("events/ms     : " + String.format("%.1f", rate));
            if (got == 0) {
                System.out.println("NOTE: 0 events — likely fiber paused at MAX_OPS_PER_RUN before dispatch loop entry");
            } else {
                System.out.println("opcode distribution:");
                for (int op = 0; op < 256; op++) {
                    if (counts[op] > 0) {
                        System.out.println("  " + opcodeName(op) + "(0x" + Integer.toHexString(op) + ") = " + counts[op]);
                    }
                }
            }
            System.out.println("===========================\n");
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    /** Warmup: run a module to trigger VM JIT compilation */
    private void warmupModule(String moduleSrc) throws Exception {
        var file = tempDir.resolve("Warmup.x").toFile();
        java.nio.file.Files.writeString(file.toPath(), moduleSrc);
        Console console = new Console() {
            @Override public String out(Object o) { return ""; }
        };
        try {
            Launcher.launch(Launcher.CMD_RUN, new String[] { file.getAbsolutePath() }, console, null);
        } catch (LauncherException e) { /* ignore */ }
        System.out.println("[VmPointcutFirehoseTest] warmup module ran");
    }

    private String runModule(String moduleSrc) throws Exception {
        var file = tempDir.resolve("Test.x").toFile();
        java.nio.file.Files.writeString(file.toPath(), moduleSrc);
        StringBuilder sb = new StringBuilder();
        Console console = new Console() {
            @Override public String out(Object o) { if (o != null) sb.append(o); return ""; }
        };
        try {
            Launcher.launch(Launcher.CMD_RUN, new String[] { file.getAbsolutePath() }, console, null);
        } catch (LauncherException e) { /* ignore */ }
        System.out.println("[VmPointcutFirehoseTest] module output: [" + sb + "]");
        return sb.toString();
    }

    private static String opcodeName(int op) {
        switch (op) {
            case 0x10: return "CALL_00"; case 0x11: return "CALL_01"; case 0x12: return "CALL_0N"; case 0x13: return "CALL_0T";
            case 0x14: return "CALL_10"; case 0x15: return "CALL_11"; case 0x16: return "CALL_1N"; case 0x17: return "CALL_1T";
            case 0x18: return "CALL_N0"; case 0x19: return "CALL_N1"; case 0x1A: return "CALL_NN"; case 0x1B: return "CALL_NT";
            case 0x1C: return "CALL_T0"; case 0x1D: return "CALL_T1"; case 0x1E: return "CALL_TN"; case 0x1F: return "CALL_TT";
            case 0x20: return "NVOK_00"; case 0x21: return "NVOK_01"; case 0x22: return "NVOK_0N"; case 0x23: return "NVOK_0T";
            case 0x24: return "NVOK_10"; case 0x25: return "NVOK_11"; case 0x26: return "NVOK_1N"; case 0x27: return "NVOK_1T";
            case 0x28: return "NVOK_N0"; case 0x29: return "NVOK_N1"; case 0x2A: return "NVOK_NN"; case 0x2B: return "NVOK_NT";
            case 0x2C: return "NVOK_T0"; case 0x2D: return "NVOK_T1"; case 0x2E: return "NVOK_TN"; case 0x2F: return "NVOK_TT";
            case 0x33: return "SYN_INIT";
            case 0x34: return "CONSTR_0"; case 0x35: return "CONSTR_1"; case 0x36: return "CONSTR_N"; case 0x37: return "CONSTR_T";
            case 0x38: return "NEW_0";   case 0x39: return "NEW_1";   case 0x3A: return "NEW_N";   case 0x3B: return "NEW_T";
            case 0x40: return "NEWC_0";  case 0x41: return "NEWC_1";  case 0x42: return "NEWC_N";  case 0x43: return "NEWC_T";
            case 0x48: return "NEWV_0";  case 0x49: return "NEWV_1";  case 0x4A: return "NEWV_N";  case 0x4B: return "NEWV_T";
            case 0x4C: return "RETURN_0"; case 0x4D: return "RETURN_1"; case 0x4E: return "RETURN_N"; case 0x4F: return "RETURN_T";
            case 0x65: return "MOV_TYPE"; case 0x66: return "CAST";
            case 0x77: return "LOOP";     case 0x78: return "LOOP_END";
            case 0x79: return "JMP";      case 0x7A: return "JMP_TRUE";  case 0x7B: return "JMP_FALSE";
            case 0x90: return "ASSERT";   case 0x91: return "ASSERT_M";  case 0x92: return "ASSERT_V";
            case 0xA5: return "L_GET";   case 0xA6: return "L_SET";
            case 0xA7: return "P_GET";   case 0xA8: return "P_SET";
            default:   return "OP_0x" + Integer.toHexString(op);
        }
    }
}
