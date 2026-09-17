package com.ethlo.r7.r7f;

import com.ethlo.r7.api.GatewayHeaders;
import com.ethlo.r7.api.MutableGatewayHeaders;
import com.ethlo.r7.r7f.fbs.DeltaOp;
import com.ethlo.r7.r7f.fbs.DeltaOpKind;
import com.ethlo.r7.r7f.fbs.HeaderDelta;
import com.ethlo.r7.util.MutableFastGatewayHeaders;

/**
 * Turns one header set into the difference from another, and back again.
 * <p>
 * Both directions live here on purpose: the only property that matters is that they compose —
 * reconstructing an encoded difference must reproduce the target exactly, names, values, order
 * and repeats included — and a property that spans two pieces of code is best kept where both
 * can be read at once.
 * <p>
 * <strong>Correctness does not depend on the diff being good.</strong> A {@code COPY} is only
 * ever emitted for a base entry that equals the target entry, so whatever the matching heuristic
 * does, applying the ops in order rebuilds the target. A poor match costs bytes — in the limit
 * it emits everything, which is the size of the full set it replaced — and cannot cost
 * correctness. That is what makes a simple sequential heuristic the right choice here rather
 * than a minimal-diff algorithm nobody wants to debug at 3am.
 */
final class HeaderDeltaCodec
{
    /**
     * Thrown when a delta cannot be turned back into headers. This is a statement about the
     * record, not about the reader: something the writer referred to is not there, so the
     * header set is unknown rather than partially known.
     */
    static final class UnreconstructableDeltaException extends Exception
    {
        UnreconstructableDeltaException(final String message)
        {
            super(message);
        }
    }

    private HeaderDeltaCodec()
    {
    }

    /**
     * Sequential match with one step of lookahead.
     * <p>
     * It recognises the three things a gateway actually does to a header set — leave an entry
     * alone, replace a value, append or insert an entry — and gives up gracefully on anything
     * else by emitting it. The shapes it handles are the shapes that occur: Host rewritten in
     * place, forwarding headers appended, a filter adding or replacing one entry.
     */
    static void diff(final String[] baseNames,
                     final String[] baseValues,
                     final int baseCount,
                     final GatewayHeaders target,
                     final Ops out)
    {
        out.reset();
        target.forEach(out, (ops, name, value) ->
        {
            final int cursor = ops.baseCursor;

            if (cursor < baseCount && matches(baseNames[cursor], baseValues[cursor], name, value))
            {
                ops.copy(cursor);
                ops.baseCursor = cursor + 1;
                return;
            }

            // The base entry here is gone from the target, but the next one survives: skip it
            // rather than emitting everything from this point on.
            if (cursor + 1 < baseCount && matches(baseNames[cursor + 1], baseValues[cursor + 1], name, value))
            {
                ops.copy(cursor + 1);
                ops.baseCursor = cursor + 2;
                return;
            }

            ops.emit(name, value);

            // Same name, different value is a replacement, so the base entry it replaced is
            // consumed. Advancing past it keeps everything after it aligned.
            if (cursor < baseCount && baseNames[cursor].equals(name))
            {
                ops.baseCursor = cursor + 1;
            }
        });
    }

    private static boolean matches(final String baseName, final String baseValue, final String name, final String value)
    {
        return baseName.equals(name) && baseValue.equals(value);
    }

    /**
     * Rebuilds the header set a delta describes.
     *
     * @throws UnreconstructableDeltaException when the base is absent, or an op refers outside
     *                                         it — in both cases the target cannot be known, and
     *                                         saying so is the only honest outcome
     */
    static GatewayHeaders reconstruct(final GatewayHeaders base, final HeaderDelta delta)
            throws UnreconstructableDeltaException
    {
        if (base == null)
        {
            throw new UnreconstructableDeltaException(
                    "the entry this delta is expressed against was not read, so the headers it describes are unknown");
        }

        final Ops scratch = new Ops();
        final int baseCount = materialise(base, scratch);

        final MutableGatewayHeaders result = new MutableFastGatewayHeaders();
        final DeltaOp op = new DeltaOp();
        final int opCount = delta.opsLength();

        for (int i = 0; i < opCount; i++)
        {
            delta.ops(op, i);
            if (op.kind() == DeltaOpKind.COPY)
            {
                final long start = op.baseIndex();
                final long count = op.count();
                if (start + count > baseCount)
                {
                    throw new UnreconstructableDeltaException(
                            "op " + i + " copies base entries " + start + ".." + (start + count - 1)
                                    + " but the base holds " + baseCount);
                }
                for (int b = (int) start; b < start + count; b++)
                {
                    result.add(scratch.name[b], scratch.value[b]);
                }
            }
            else
            {
                result.add(JournalDecoder.asLatin1(op.nameAsByteBuffer()),
                        JournalDecoder.asLatin1(op.valueAsByteBuffer()));
            }
        }
        return result;
    }

    /**
     * Copies a header set into indexable arrays. A delta addresses the base by position, and
     * {@link GatewayHeaders} only offers traversal.
     */
    static int materialise(final GatewayHeaders headers, final Ops into)
    {
        into.baseLength = 0;
        headers.forEach(into, (target, name, value) -> target.appendBase(name, value));
        return into.baseLength;
    }

    /**
     * Working space for one diff or reconstruction: the base as arrays, and the ops produced.
     * Held by the caller so it can be reused rather than allocated per exchange.
     */
    static final class Ops
    {
        static final int COPY = 0;
        static final int EMIT = 1;

        int[] kind = new int[32];
        int[] baseIndex = new int[32];
        int[] count = new int[32];
        String[] emittedName = new String[32];
        String[] emittedValue = new String[32];
        int size;

        String[] name = new String[32];
        String[] value = new String[32];
        int baseLength;

        int baseCursor;

        void reset()
        {
            size = 0;
            baseCursor = 0;
        }

        void appendBase(final String headerName, final String headerValue)
        {
            if (baseLength == name.length)
            {
                name = java.util.Arrays.copyOf(name, name.length * 2);
                value = java.util.Arrays.copyOf(value, value.length * 2);
            }
            name[baseLength] = headerName;
            value[baseLength] = headerValue;
            baseLength++;
        }

        /**
         * Records a copy of one base entry, extending the previous op when it is the copy of
         * the entry immediately before — consecutive survivors are the common case, and one
         * run is far smaller than one op each.
         */
        void copy(final int index)
        {
            if (size > 0 && kind[size - 1] == COPY && baseIndex[size - 1] + count[size - 1] == index)
            {
                count[size - 1]++;
                return;
            }
            ensure();
            kind[size] = COPY;
            baseIndex[size] = index;
            count[size] = 1;
            size++;
        }

        void emit(final String headerName, final String headerValue)
        {
            ensure();
            kind[size] = EMIT;
            emittedName[size] = headerName;
            emittedValue[size] = headerValue;
            size++;
        }

        private void ensure()
        {
            if (size < kind.length)
            {
                return;
            }
            final int capacity = kind.length * 2;
            kind = java.util.Arrays.copyOf(kind, capacity);
            baseIndex = java.util.Arrays.copyOf(baseIndex, capacity);
            count = java.util.Arrays.copyOf(count, capacity);
            emittedName = java.util.Arrays.copyOf(emittedName, capacity);
            emittedValue = java.util.Arrays.copyOf(emittedValue, capacity);
        }
    }
}
