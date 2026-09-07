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

    /* The exact-typed MethodHandle calls of the bigint arithmetic site
     * (NqpTypeOps.bigintArith): Java's polymorphic-signature call is
     * unambiguous for a void setter, Kotlin's is not something the fast
     * path should depend on. Both handles are constants of the site, so
     * PE folds each call to the field access. */

    /** The BigInteger slot of a P6opaque Int, through a (SixModelObject)BigInteger getter. */
    static java.math.BigInteger getBig(java.lang.invoke.MethodHandle getter, SixModelObject o) {
        try {
            return (java.math.BigInteger) getter.invokeExact(o);
        } catch (Throwable t) {
            throw com.oracle.truffle.api.CompilerDirectives.shouldNotReachHere(t);
        }
    }

    /** Its store, through a (SixModelObject,BigInteger)void setter. */
    static void setBig(java.lang.invoke.MethodHandle setter, SixModelObject o, java.math.BigInteger v) {
        try {
            setter.invokeExact(o, v);
        } catch (Throwable t) {
            throw com.oracle.truffle.api.CompilerDirectives.shouldNotReachHere(t);
        }
    }
}
