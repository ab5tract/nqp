package org.raku.nqp.truffle;

import org.raku.nqp.runtime.CodeRef;
import org.raku.nqp.runtime.StaticCodeInfo;
import org.raku.nqp.sixmodel.STable;
import org.raku.nqp.sixmodel.SixModelObject;

/**
 * Raw field reads for the Kotlin fast paths. A Kotlin {@code lateinit}
 * property reads through a getter (or an inlined check) that throws when
 * unset, and under partial evaluation that failure path -- exception
 * construction, stack-trace sanitizing -- is inlined at every read site
 * (seen: ten copies in the compiled {@code infix:<+>} root, one per
 * {@code .st}). Java reads the backing field directly, so the PE-visible
 * Kotlin code reads such fields through here. This file is Java for that
 * one reason.
 */
final class NqpRaw {
    private NqpRaw() {}

    /** {@code o.st} without the lateinit check; null only for an unset STable. */
    static STable st(SixModelObject o) {
        return o.st;
    }

    /** {@code cr.staticInfo} without the lateinit check. */
    static StaticCodeInfo staticInfo(CodeRef cr) {
        return cr.staticInfo;
    }
}
