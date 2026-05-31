package org.xvm.runtime;

import borg.trikeshed.lib.ChunkedMutableSeries;
import borg.trikeshed.lib.CollectorReducer;
import borg.trikeshed.lib.ReduxMutableSeries;
import borg.trikeshed.lib.SeriesKt;
import borg.trikeshed.lib.Reducer;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge: replaces ArrayList ctors at named pointcut sites with ReduxMutableSeries.
 *
 * toSeries().toReduxMutableSeries(delegate, params) — the pointcut replacement.
 *
 * Architecture:
 *   new ArrayList<>()  →  ReduxMutableSeries<T, Series<T>>
 *     eventJournal = ChunkedMutableSeries<T>(chunkSize)
 *     reducer      = CollectorReducer<T>
 *     capture      = initial element (from first add, or null)
 *
 *   The delegate is ChunkedMutableSeries (tree of fixed-size chunks, O(1) amortized append).
 *   The reducer is CollectorReducer (identity: every action collected into growing Series).
 *   The state is lazily reified: fold-on-read through the event journal.
 */
@SuppressWarnings("unchecked")
public final class ReduxListBridge {

    private static final int DEFAULT_CHUNK_SIZE = 4096;

    /**
     * Create a ReduxMutableSeries replacing new ArrayList().
     * For use as invokestatic target from rewritten bytecode.
     */
    public static <T> ReduxMutableSeries<T, ?> createDefault() {
        return createSized(DEFAULT_CHUNK_SIZE);
    }

    /**
     * Create a ReduxMutableSeries replacing new ArrayList(int).
     * chunkSize param maps to ArrayList initial capacity hint.
     */
    public static <T> ReduxMutableSeries<T, ?> createSized(int chunkSize) {
        borg.trikeshed.lib.MutableSeries<T> delegate = new ChunkedMutableSeries<>(chunkSize);
        borg.trikeshed.lib.Reducer<T, ?> reducer = new CollectorReducer<>();
        return new ReduxMutableSeries<>(
                delegate,
                (borg.trikeshed.lib.Reducer) reducer,
                reducer.getZero(),   // initialState = empty Series
                null                 // capture = null until first element
        );
    }

    /**
     * Wrap an existing ArrayList's contents into a ReduxMutableSeries.
     */
    @SuppressWarnings("unchecked")
    public static <T> ReduxMutableSeries<T, ?> wrap(ArrayList<T> list) {
        ReduxMutableSeries<T, ?> redux = (ReduxMutableSeries<T, ?>) createSized(DEFAULT_CHUNK_SIZE);
        for (T item : list) {
            redux.add(item);
        }
        return redux;
    }

    /**
     * Unary getter: toSeries().toList() — (ReduxMutableSeries) → List
     */
    public static <T> List<T> toSeriesToList(ReduxMutableSeries<T, ?> redux) {
        return SeriesKt.toList((borg.trikeshed.lib.Join) redux);
    }
}
