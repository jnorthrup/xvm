package org.xvm.runtime;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
 * TDD: Real timer-driven slab flush.
 *
 * ScheduledExecutorService ticks at intervalMs.
 * On each tick: if ring has events (0 < count < SLAB_SIZE) → flush.
 * Fire (count == SLAB_SIZE) flushes immediately from publish() hot path.
 * Timer handles the speculative underrun path.
 */
public class FieldSynapseTimerFlushTest {

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
        assertTrue(xdkLibDir.isDirectory());
    }

    // ── 1. Timer flushes on tick ──────────────────────────────────────

    @Test
    void timerFlush_onTick() throws Exception {
        FieldSynapse.reset();
        FieldSynapse.active = true;

        CountDownLatch slabLatch = new CountDownLatch(1);
        ConcurrentLinkedQueue<int[]> slabs = new ConcurrentLinkedQueue<>(); // [count]

        FieldSynapse.setSubscriber((slab, count, epoch, nanoStart, nanoEnd) -> {
            slabs.add(new int[]{count});
            slabLatch.countDown();
        });

        // start 50ms timer
        FieldSynapse.startTimer(50);

        try {
            // publish 100 events — not enough for fire
            for (int i = 0; i < 100; i++) {
                FieldSynapse.publishStatic(0xA5, "TimerFlush.get", i, false);
            }
            assertEquals(100, FieldSynapse.size(), "ring has 100, no fire");

            // wait for timer tick to flush
            boolean received = slabLatch.await(2, TimeUnit.SECONDS);
            assertTrue(received, "timer should have flushed within 2s");

            // ring should be empty after timer flush
            Thread.sleep(100); // let the flush complete
            assertEquals(0, FieldSynapse.size(), "ring should be empty after timer flush");

            assertEquals(1, slabs.size(), "exactly one slab from timer");
            assertEquals(100, slabs.peek()[0], "slab has 100 events");

            System.out.println("\n=== timer flush on tick ===");
            System.out.println("slabs           : " + slabs.size());
            System.out.println("slab[0]         : " + slabs.peek()[0] + " events");
            System.out.println("ring after      : " + FieldSynapse.size());
        } finally {
            FieldSynapse.stopTimer();
            FieldSynapse.active = false;
        }
    }

    // ── 2. Timer does not double-flush empty ring ─────────────────────

    @Test
    void timerFlush_noDoubleFlush() throws Exception {
        FieldSynapse.reset();
        FieldSynapse.active = true;

        AtomicInteger slabCount = new AtomicInteger();
        ConcurrentLinkedQueue<int[]> slabs = new ConcurrentLinkedQueue<>();

        FieldSynapse.setSubscriber((slab, count, epoch, nanoStart, nanoEnd) -> {
            slabCount.incrementAndGet();
            slabs.add(new int[]{count, (int) epoch});
        });

        FieldSynapse.startTimer(50);

        try {
            // publish 10 events
            for (int i = 0; i < 10; i++) {
                FieldSynapse.publishStatic(0xA7, "NoDouble.prop", i, false);
            }

            // wait for timer to flush
            Thread.sleep(200);

            // now ring is empty — wait a few more ticks
            Thread.sleep(300);

            // publish nothing more — no new slabs should appear
            int countBefore = slabCount.get();
            Thread.sleep(300);
            assertEquals(countBefore, slabCount.get(),
                    "timer should not flush empty ring");

            System.out.println("\n=== no double flush ===");
            System.out.println("slabs           : " + slabCount.get());
            System.out.println("slab[0]         : " + slabs.peek()[0] + " events");
        } finally {
            FieldSynapse.stopTimer();
            FieldSynapse.active = false;
        }
    }

    // ── 3. Fire + timer interleaving ──────────────────────────────────

    @Test
    void fireAndTimer_interleave() throws Exception {
        FieldSynapse.reset();
        FieldSynapse.active = true;

        CountDownLatch twoSlabs = new CountDownLatch(2);
        ConcurrentLinkedQueue<String> slabDescs = new ConcurrentLinkedQueue<>();

        FieldSynapse.setSubscriber((slab, count, epoch, nanoStart, nanoEnd) -> {
            slabDescs.add("epoch=" + epoch + " count=" + count);
            twoSlabs.countDown();
        });

        FieldSynapse.startTimer(50);

        try {
            // batch 1: 2048 events → immediate fire
            for (int i = 0; i < 2048; i++) {
                FieldSynapse.publishStatic(0xA5, "Fire.get", i, false);
            }
            assertEquals(0, FieldSynapse.size(), "ring empty after fire");

            // batch 2: 200 events — wait for timer
            for (int i = 0; i < 200; i++) {
                FieldSynapse.publishStatic(0xA7, "Timer.prop", i, false);
            }

            boolean received = twoSlabs.await(2, TimeUnit.SECONDS);
            assertTrue(received, "should get 2 slabs (fire + timer)");

            Thread.sleep(100);

            System.out.println("\n=== fire + timer interleave ===");
            System.out.println("slabs           : " + slabDescs.size());
            slabDescs.forEach(d -> System.out.println("  " + d));

            assertEquals(2, slabDescs.size());
            // first slab is fire (2048), second is timer (200)
            assertTrue(slabDescs.stream().anyMatch(d -> d.contains("count=2048")));
            assertTrue(slabDescs.stream().anyMatch(d -> d.contains("count=200")));
        } finally {
            FieldSynapse.stopTimer();
            FieldSynapse.active = false;
        }
    }

    // ── 4. Multiple timer ticks, multiple slabs ───────────────────────

    @Test
    void multipleTicks_multipleSlabs() throws Exception {
        FieldSynapse.reset();
        FieldSynapse.active = true;

        CountDownLatch threeSlabs = new CountDownLatch(3);
        ConcurrentLinkedQueue<int[]> slabs = new ConcurrentLinkedQueue<>();

        FieldSynapse.setSubscriber((slab, count, epoch, nanoStart, nanoEnd) -> {
            slabs.add(new int[]{count, (int) epoch});
            threeSlabs.countDown();
        });

        FieldSynapse.startTimer(80);

        try {
            // publish 50, wait for tick, publish 50, wait for tick, publish 50
            for (int batch = 0; batch < 3; batch++) {
                for (int i = 0; i < 50; i++) {
                    FieldSynapse.publishStatic(0xA6, "Multi.set", batch * 50 + i, false);
                }
                Thread.sleep(150); // wait for timer tick
            }

            boolean received = threeSlabs.await(3, TimeUnit.SECONDS);
            assertTrue(received, "should get 3 slabs from 3 timer ticks");

            System.out.println("\n=== multiple ticks ===");
            System.out.println("slabs           : " + slabs.size());
            int i = 0;
            for (int[] s : slabs) {
                System.out.println("  slab[" + i++ + "]        : " + s[0] + " events epoch=" + s[1]);
            }

            assertEquals(3, slabs.size());
            // each slab should be ~50 events
            for (int[] s : slabs) {
                assertTrue(s[0] > 0, "each slab should have events");
            }
        } finally {
            FieldSynapse.stopTimer();
            FieldSynapse.active = false;
        }
    }

    // ── 5. Live VM with timer ─────────────────────────────────────────

    @Test
    void liveVM_withTimer() throws Exception {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        FieldSynapse.reset();
        FieldSynapse.active = true;

        ConcurrentLinkedQueue<int[]> slabs = new ConcurrentLinkedQueue<>();
        AtomicInteger totalCaptured = new AtomicInteger();

        FieldSynapse.setSubscriber((slab, count, epoch, nanoStart, nanoEnd) -> {
            slabs.add(new int[]{count, (int) epoch});
            totalCaptured.addAndGet(count);
        });

        FieldSynapse.startTimer(50);

        try {
            compileAndRun("""
                module TimerVMTest {
                    void run() {
                        @Inject Console c;
                        Int x = 42;
                        Int y = x + 1;
                        c.print(y.toString());
                    }
                }
                """, "TimerVMTest");

            // wait for timer to flush remaining events
            Thread.sleep(500);

            System.out.println("\n=== live VM with timer ===");
            System.out.println("total slabs     : " + slabs.size());
            System.out.println("total captured  : " + totalCaptured.get());
            System.out.println("ring remaining  : " + FieldSynapse.size());
            System.out.println("pool size       : " + FieldSynapse.POOL.size());

            assertTrue(totalCaptured.get() > 0, "timer should flush VM events");

            int i = 0;
            for (int[] s : slabs) {
                System.out.println("  slab[" + i++ + "]        : " + s[0] + " events epoch=" + s[1]);
            }
        } finally {
            FieldSynapse.stopTimer();
            VmPointcutPublisher.active = false;
            FieldSynapse.active = false;
        }
    }

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
                // VM may throw on exit
            }
        }, "vm-runner");
        runner.setDaemon(true);
        runner.start();
        runner.join(5_000);
    }
}
