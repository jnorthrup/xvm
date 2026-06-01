package org.xvm.runtime;

import java.util.concurrent.atomic.AtomicLong;

import borg.trikeshed.lib.ChunkedMutableSeries;
import borg.trikeshed.lib.MutableSeries;
import borg.trikeshed.lib.Reducer;
import borg.trikeshed.lib.ReduxMutableSeries;
import kotlin.jvm.functions.Function1;

import org.xvm.asm.constants.TypedefResolutionPublisher.TypedefCallsite;

/**
 * Redux-backed transcript verifier for typedef parameter responses.
 *
 * A branch vote is recorded against a mapped pointcut and callsite. The
 * transcript is the teacher/verifier: params==0 allows the staircase
 * scaffold, params!=0 blocks it.
 */
public final class TypedefStaircaseTranscriptOracle {
    public enum Branch {
        A,
        B
    }

    public enum Vote {
        ALLOW,
        BLOCK
    }

    public record TranscriptRow(
            long seq,
            Branch branch,
            int opcode,
            VmPointcutDispatch.Kind pointcutKind,
            int siteOrd,
            String siteName,
            String alias,
            int paramCount,
            Vote vote,
            XvmPrimitiveTranslationTable.VtableLayout layout,
            byte mixinCompat,
            String reason) {
    }

    public record TranscriptState(
            int total,
            int allowA,
            int blockA,
            int allowB,
            int blockB,
            int maxParamCount) {
        public boolean branchAllowed(Branch branch) {
            return switch (branch) {
                case A -> blockA == 0;
                case B -> blockB == 0;
            };
        }
    }

    private static final TranscriptRow CAPTURE = new TranscriptRow(
            -1,
            Branch.A,
            -1,
            VmPointcutDispatch.Kind.GAP,
            -1,
            "capture",
            "capture",
            -1,
            Vote.ALLOW,
            XvmPrimitiveTranslationTable.VtableLayout.VIRTUAL,
            (byte) 0,
            "capture");

    private final Object lock = new Object();
    private final AtomicLong seq = new AtomicLong();
    private final ReduxMutableSeries<TranscriptRow, TranscriptState> transcript;

    public TypedefStaircaseTranscriptOracle() {
        var delegate = new ChunkedMutableSeries<TranscriptRow>(64);
        transcript = new ReduxMutableSeries<>(delegate, new TranscriptReducer(), new TranscriptReducer().getZero(), CAPTURE);
    }

    public TranscriptRow record(Branch branch, int opcode, TypedefCallsite site,
                                String alias, int paramCount,
                                XvmPrimitiveTranslationTable.XvmPrimitive primitive) {
        var kind = VmPointcutDispatch.kindOf(opcode);
        var options = XvmPrimitiveTranslationTable.vtableOptions(primitive);
        var vote = paramCount == 0 ? Vote.ALLOW : Vote.BLOCK;
        var reason = vote == Vote.ALLOW
                ? "params=0 allows typedef staircase scaffolding"
                : "params!=0 blocks typedef staircase scaffolding";
        var row = new TranscriptRow(
                seq.incrementAndGet(),
                branch,
                opcode,
                kind,
                site.siteIndex(),
                site.name(),
                alias,
                paramCount,
                vote,
                options.layout(),
                options.mixinCompat(),
                reason);
        synchronized (lock) {
            transcript.dispatch(row);
        }
        return row;
    }

    public TranscriptState state() {
        synchronized (lock) {
            return transcript.reify();
        }
    }

    public boolean branchAllowed(Branch branch) {
        return state().branchAllowed(branch);
    }

    public TranscriptRow[] snapshot() {
        synchronized (lock) {
            var journal = transcript.getEventJournal();
            var size = journal.getA();
            @SuppressWarnings("unchecked")
            var reader = (Function1<Integer, TranscriptRow>) journal.getB();
            var rows = new TranscriptRow[size];
            for (var i = 0; i < size; i++) {
                rows[i] = reader.invoke(i);
            }
            return rows;
        }
    }

    private static final class TranscriptReducer implements Reducer<TranscriptRow, TranscriptState> {
        @Override
        public TranscriptState getZero() {
            return new TranscriptState(0, 0, 0, 0, 0, 0);
        }

        @Override
        public TranscriptState combine(TranscriptState state, TranscriptRow row) {
            var allowA = state.allowA();
            var blockA = state.blockA();
            var allowB = state.allowB();
            var blockB = state.blockB();
            if (row.branch() == Branch.A) {
                if (row.vote() == Vote.ALLOW) {
                    allowA++;
                } else {
                    blockA++;
                }
            } else {
                if (row.vote() == Vote.ALLOW) {
                    allowB++;
                } else {
                    blockB++;
                }
            }
            return new TranscriptState(
                    state.total() + 1,
                    allowA,
                    blockA,
                    allowB,
                    blockB,
                    Math.max(state.maxParamCount(), row.paramCount()));
        }
    }
}
