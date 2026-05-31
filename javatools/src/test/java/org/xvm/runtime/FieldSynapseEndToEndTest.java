package org.xvm.runtime;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.xvm.asm.ErrorList;
import org.xvm.tool.Compiler;
import org.xvm.tool.Launcher;
import org.xvm.tool.LauncherOptions.CompilerOptions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD: FieldSynapse slab handoff.
 *
 * Ring(2048) → fire/timeout → immutable slab → subscriber → CRMS fold.
 */
public class FieldSynapseEndToEndTest {

    private static File xdkLibDir;
    private static File xdkJavaToolsDir;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void findXdk() {
        File base = new File(System.getProperty("user.dir"));
        File xdkBuild = new File(base, "xdk/build/install/xdk");
        if (!xdkBuild.exists()) {
            xdkBuild = new File(base.getParentFile(), "xdk/build/install/xdk");
        }
        xdkLibDir = new File(xdkBuild, "lib");
        xdkJavaToolsDir = new File(xdkBuild, "javatools");
        assertTrue(xdkLibDir.isDirectory(),
                "XDK lib not found: " + xdkLibDir + " — run ./gradlew installDist");
    }

    // ── 1. Slab fire on capacity ──────────────────────────────────────

    @Test
    void slabFire_onCapacity() {
        FieldSynapse.reset();
        FieldSynapse.active = true;

        ConcurrentLinkedQueue<String> slabReasons = new ConcurrentLinkedQueue<>();
        AtomicInteger totalSlabEvents = new AtomicInteger();
        AtomicInteger slabCount = new AtomicInteger();

        FieldSynapse.setSubscriber((slab, count, epoch, nanoStart, nanoEnd) -> {
            slabCount.incrementAndGet();
            totalSlabEvents.addAndGet(count);
            slabReasons.add("epoch=" + epoch + " count=" + count +
                    " nano=" + (nanoEnd - nanoStart) + "ns");
        });

        try {
            // publish exactly 2048 — should trigger fire flush
            for (int i = 0; i < 2048; i++) {
                FieldSynapse.publishStatic(0xA5, "SlabFire.get", i, false);
            }

            // ring should be empty after fire
            assertEquals(0, FieldSynapse.size(), "ring should be empty after fire flush");
            assertEquals(1, slabCount.get(), "exactly one slab");
            assertEquals(2048, totalSlabEvents.get(), "all events in slab");

            System.out.println("\n=== slab fire ===");
            System.out.println("slabs           : " + slabCount.get());
            System.out.println("total events    : " + totalSlabEvents.get());
            slabReasons.forEach(r -> System.out.println("  " + r));
        } finally {
            FieldSynapse.active = false;
        }
    }

    // ── 2. Timeout flush (speculative underrun) ───────────────────────

    @Test
    void slabTimeout_speculativeUnderrun() {
        FieldSynapse.reset();
        FieldSynapse.active = true;

        ConcurrentLinkedQueue<int[]> slabs = new ConcurrentLinkedQueue<>(); // [count, epoch]

        FieldSynapse.setSubscriber((slab, count, epoch, nanoStart, nanoEnd) -> {
            slabs.add(new int[]{count, (int) epoch});
        });

        try {
            // publish 500 — not enough for fire
            for (int i = 0; i < 500; i++) {
                FieldSynapse.publishStatic(0xA7, "Underrun.prop", i, false);
            }
            assertEquals(500, FieldSynapse.size(), "ring has 500, no fire");

            // timeout flush — drain what's available
            FieldSynapse.timeoutFlush();
            assertEquals(0, FieldSynapse.size(), "ring empty after timeout");
            assertEquals(1, slabs.size(), "one slab from timeout");

            int[] first = slabs.peek();
            assertEquals(500, first[0], "slab has 500 events");

            // publish 3 more, timeout again
            FieldSynapse.publishStatic(0xA5, "Tiny.a", 0, false);
            FieldSynapse.publishStatic(0xA5, "Tiny.a", 1, false);
            FieldSynapse.publishStatic(0xA5, "Tiny.a", 2, false);
            FieldSynapse.timeoutFlush();

            assertEquals(2, slabs.size(), "second slab from timeout");
            System.out.println("\n=== speculative underrun ===");
            System.out.println("slabs           : " + slabs.size());
            System.out.println("slab 0          : " + slabs.poll()[0] + " events");
            System.out.println("slab 1          : " + slabs.poll()[0] + " events");
        } finally {
            FieldSynapse.active = false;
        }
    }

    // ── 3. Multi-slab with live VM ────────────────────────────────────

    @Test
    void slabHandoff_liveVM() throws Exception {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        FieldSynapse.reset();
        FieldSynapse.active = true;

        List<FieldSynapse[]> allSlabs = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger totalCaptured = new AtomicInteger();

        FieldSynapse.setSubscriber((slab, count, epoch, nanoStart, nanoEnd) -> {
            allSlabs.add(Arrays.copyOf(slab, count));
            totalCaptured.addAndGet(count);
        });

        try {
            compileAndRun("""
                module SlabTest {
                    void run() {
                        @Inject Console c;
                        Int x = 42;
                        Int y = x + 1;
                        c.print(y.toString());
                    }
                }
                """, "SlabTest");

            // flush remaining events (timeout-style)
            FieldSynapse.flush("test-end");

            System.out.println("\n=== slab handoff (live VM) ===");
            System.out.println("total slabs     : " + allSlabs.size());
            System.out.println("total captured  : " + totalCaptured.get());
            System.out.println("ring remaining  : " + FieldSynapse.size());
            System.out.println("pool size       : " + FieldSynapse.POOL.size());

            assertTrue(totalCaptured.get() > 0, "should capture field events via slabs");

            // Verify BEFORE/AFTER pairing in first slab
            if (!allSlabs.isEmpty()) {
                FieldSynapse[] firstSlab = allSlabs.get(0);
                int before = 0, after = 0;
                for (FieldSynapse evt : firstSlab) {
                    if (evt.phase == 0) before++; else after++;
                }
                System.out.println("  first slab    : " + firstSlab.length + " events");
                System.out.println("    BEFORE      : " + before);
                System.out.println("    AFTER       : " + after);

                // reify first 3
                for (int i = 0; i < Math.min(3, firstSlab.length); i++) {
                    System.out.println("    [" + i + "] " + firstSlab[i].reify());
                }
            }

            // slab summary
            for (int i = 0; i < allSlabs.size(); i++) {
                FieldSynapse[] slab = allSlabs.get(i);
                System.out.println("  slab[" + i + "]        : " + slab.length + " events");
            }
        } finally {
            VmPointcutPublisher.active = false;
            FieldSynapse.active = false;
        }
    }

    // ── 4. CRMS fold: group by callsiteHash, pair BEFORE/AFTER ───────

    @Test
    void crmsFold_groupByCallsite() {
        FieldSynapse.reset();
        FieldSynapse.active = true;

        List<FieldSynapse[]> slabs = new ArrayList<>();
        FieldSynapse.setSubscriber((slab, count, epoch, nanoStart, nanoEnd) -> {
            slabs.add(Arrays.copyOf(slab, count));
        });

        try {
            // publish paired events at 3 different sites
            for (int i = 0; i < 100; i++) {
                FieldSynapse.publishStatic(0xA5, "Site.a", i, false); // BEFORE GET
                FieldSynapse.publishStatic(0xA5, "Site.a", i, true);  // AFTER GET
                FieldSynapse.publishStatic(0xA7, "Site.b", i, false);
                FieldSynapse.publishStatic(0xA7, "Site.b", i, true);
                FieldSynapse.publishStatic(0xA6, "Site.c", i, false); // BEFORE SET
                FieldSynapse.publishStatic(0xA6, "Site.c", i, true);  // AFTER SET
            }
            FieldSynapse.flush("test");

            System.out.println("\n=== CRMS fold ===");

            // group by callsiteHash
            Map<Integer, List<FieldSynapse>> groups = new LinkedHashMap<>();
            for (FieldSynapse[] slab : slabs) {
                for (FieldSynapse evt : slab) {
                    groups.computeIfAbsent(evt.callsiteHash, k -> new ArrayList<>()).add(evt);
                }
            }

            System.out.println("callsite groups : " + groups.size());
            assertTrue(groups.size() >= 3, "should have 3+ distinct callsite groups");

            for (var entry : groups.entrySet()) {
                List<FieldSynapse> cell = entry.getValue();
                int before = (int) cell.stream().filter(e -> e.phase == 0).count();
                int after = (int) cell.stream().filter(e -> e.phase == 1).count();
                String site = cell.get(0).methodName();
                System.out.println("  hash=0x" + Integer.toHexString(entry.getKey()) +
                        " site=" + site +
                        " BEFORE=" + before + " AFTER=" + after +
                        " resolved=" + (before == after));
                assertEquals(before, after, "BEFORE/AFTER should pair for " + site);
            }
        } finally {
            FieldSynapse.active = false;
        }
    }

    // ── 5. InternPool round-trip ──────────────────────────────────────

    @Test
    void internPool_roundTrip() {
        FieldSynapse.reset();
        FieldSynapse.active = true;

        try {
            FieldSynapse.publishStatic(0xA5, "Alpha.get", 0, false);
            FieldSynapse.publishStatic(0xA7, "Beta.prop", 1, false);
            FieldSynapse.publishStatic(0xA6, "Gamma.set", 2, false);

            byte[] poolBytes = FieldSynapse.POOL.toBytes();
            ByteBuffer buf = ByteBuffer.wrap(poolBytes).order(ByteOrder.LITTLE_ENDIAN);
            int count = buf.getShort() & 0xFFFF;

            System.out.println("\n=== InternPool ===");
            System.out.println("entries: " + count + " bytes: " + poolBytes.length);

            for (int i = 0; i < count; i++) {
                int idx = buf.getShort() & 0xFFFF;
                int len = buf.getShort() & 0xFFFF;
                byte[] strBytes = new byte[len];
                buf.get(strBytes);
                String s = new String(strBytes, java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("  [" + idx + "] " + s);
            }

            assertTrue(count >= 3);
            assertEquals("Alpha.get", FieldSynapse.POOL.resolve(
                    FieldSynapse.peek(0).methodIdx));
        } finally {
            FieldSynapse.active = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private void compileAndRun(String source, String moduleName) throws Exception {
        Path srcFile = tempDir.resolve(moduleName + ".x");
        Files.writeString(srcFile, source);
        File outputDir = tempDir.resolve("out").toFile();
        outputDir.mkdirs();

        ErrorList errors = new ErrorList(20);
        CompilerOptions opts = new CompilerOptions.Builder()
                .addModulePath(xdkLibDir)
                .addModulePath(xdkJavaToolsDir)
                .setOutputLocation(outputDir)
                .addInputFile(srcFile.toFile())
                .build();

        Compiler compiler = new Compiler(opts, null, errors);
        int rc = compiler.run();
        if (rc != 0 || errors.hasSeriousErrors()) {
            fail("Compilation failed: " + errors);
        }

        File xtcFile = new File(outputDir, moduleName + ".xtc");
        assertTrue(xtcFile.exists());

        org.xvm.tool.Console console = new org.xvm.tool.Console() {
            @Override public String out(Object o) { return ""; }
        };

        String[] runArgs = {
            "-L", xdkLibDir.getAbsolutePath(),
            "-L", xdkJavaToolsDir.getAbsolutePath(),
            "-L", outputDir.getAbsolutePath(),
            xtcFile.getAbsolutePath()
        };

        Thread runner = new Thread(() -> {
            try {
                Launcher.launch(Launcher.CMD_RUN, runArgs, console, null);
            } catch (Exception e) {
                System.out.println("[run] " + e.getMessage());
            }
        }, "vm-runner");
        runner.setDaemon(true);
        runner.start();
        runner.join(5_000);
    }
}
