package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Wire-up verification for VM and Field pointcut observation paths.
 * Reinstated real wireproto ByteBuffer decode assertions.
 */
public class PointcutObservationTest {

    @Test
    public void vmPublish_hotPath_producesWireprotoOnDrain() {
        VmPointcutPublisher.reset();
        FieldSynapse.reset();

        var sink = new RecordingSink();
        int sinkId = PointcutObservation.subscribe(sink);
        VmPointcutPublisher.active = true;

        try {
            VmPointcutPublisher.publish(0x10, "Test.run", 11);
            VmPointcutPublisher.publish(0x4C, "Test.run", 12);

            var drained = new ArrayList<VmPointcutPublisher.PointcutEvent>();
            VmPointcutPublisher.drain(drained::add);

            assertEquals(2, drained.size());
            assertEquals(1, sink.vmBatches.size(), "drain should have produced one VM batch");

            var batch = sink.vmBatches.getFirst();
            assertEquals(2, batch.count);
            assertArrayEquals(new int[]{0x10, 0x4C}, batch.opcodes());

        } finally {
            PointcutObservation.unsubscribe(sinkId);
            VmPointcutPublisher.reset();
            FieldSynapse.reset();
        }
    }

    @Test
    public void fieldPublishStatic_hotPath_producesWireprotoOnFlush() {
        VmPointcutPublisher.reset();
        FieldSynapse.reset();

        var sink = new RecordingSink();
        int sinkId = PointcutObservation.subscribe(sink);
        FieldSynapse.active = true;

        try {
            FieldSynapse.publishStatic(0xA5, "Field.read", 21, false);  // BEFORE
            FieldSynapse.publishStatic(0xA8, "Field.write", 22, true);   // AFTER

            FieldSynapse.flush("test");

            assertEquals(1, sink.fieldBatches.size(), "flush should have produced one FIELD batch");

            var batch = sink.fieldBatches.getFirst();
            assertEquals(2, batch.count);
            assertEquals(0L, batch.epoch, "first slab epoch");

            assertArrayEquals(new int[]{0xA5, 0xA8}, batch.opcodes());
            assertEquals(0, batch.events.get(0).phase, "first event should be BEFORE");
            assertEquals(1, batch.events.get(1).phase, "second event should be AFTER");

        } finally {
            PointcutObservation.unsubscribe(sinkId);
            VmPointcutPublisher.reset();
            FieldSynapse.reset();
        }
    }

    @Test
    public void unsubscribe_preventsOnBatchDelivery() {
        VmPointcutPublisher.reset();
        FieldSynapse.reset();

        var sink = new RecordingSink();
        int sinkId = PointcutObservation.subscribe(sink);
        VmPointcutPublisher.active = true;

        try {
            PointcutObservation.unsubscribe(sinkId);

            VmPointcutPublisher.publish(0x10, "Test.run", 11);
            VmPointcutPublisher.drain(evt -> {});

            assertEquals(0, sink.vmBatches.size());
            assertEquals(0, sink.fieldBatches.size());
        } finally {
            VmPointcutPublisher.reset();
            FieldSynapse.reset();
        }
    }

    private static final class RecordingSink implements PointcutObservation.Observable {
        private final List<VmBatch> vmBatches = new ArrayList<>();
        private final List<FieldBatch> fieldBatches = new ArrayList<>();

        @Override
        public void onBatch(PointcutObservation.Source source, ByteBuffer wireproto, int count, long epoch) {
            if (source == PointcutObservation.Source.VM) {
                vmBatches.add(VmBatch.decode(wireproto, count));
            } else {
                fieldBatches.add(FieldBatch.decode(wireproto, count, epoch));
            }
        }
    }

    private record VmBatch(List<VmPointcutPublisher.PointcutEvent> events, int count) {
        private static VmBatch decode(ByteBuffer wireproto, int count) {
            var buf = wireproto.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            var events = new ArrayList<VmPointcutPublisher.PointcutEvent>(count);
            while (buf.remaining() >= VmPointcutPublisher.RECORD_SIZE) {
                events.add(VmPointcutPublisher.fromWireproto(buf));
            }
            return new VmBatch(events, count);
        }

        private int[] opcodes() {
            var opcodes = new int[count];
            for (var i = 0; i < count; i++) {
                opcodes[i] = events.get(i).opcode;
            }
            return opcodes;
        }
    }

    private record FieldBatch(List<FieldSynapse> events, int count, long epoch) {
        private static FieldBatch decode(ByteBuffer wireproto, int count, long epoch) {
            var buf = wireproto.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            var events = new ArrayList<FieldSynapse>(count);
            while (buf.remaining() >= FieldSynapse.RECORD_SIZE) {
                events.add(FieldSynapse.fromWireproto(buf));
            }
            return new FieldBatch(events, count, epoch);
        }

        private int[] opcodes() {
            var opcodes = new int[count];
            for (var i = 0; i < count; i++) {
                opcodes[i] = events.get(i).opcode & 0xFF;
            }
            return opcodes;
        }
    }
}