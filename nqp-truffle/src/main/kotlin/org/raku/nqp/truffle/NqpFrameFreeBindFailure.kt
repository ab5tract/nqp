package org.raku.nqp.truffle

import org.raku.nqp.runtime.ControlException

/**
 * A signature bind check failed in a frame-free block (jesp diamond 5).
 *
 * A framed callee's `assertparamcheck` finds the dispatch that invoked it
 * on its own CallFrame (`BindFailure.failed`). A frame-free callee has no
 * frame: `tc.frame` is the caller's, and reading the dispatch there resumed
 * the wrong dispatch or reported "Bind check failed" against the caller's
 * arguments -- the CORE.d compile failure that kept frame-free blocks off.
 * So a frame-free block throws this instead, and the direct road that
 * entered it (`NqpDispatch.enterResumableDirect` / the mapped and invoke
 * roads), which knows its own program, arguments and callee, resumes the
 * dispatch or reports the failure with the right ones.
 *
 * Carries nothing: the catcher has everything. One instance would do, but
 * a fresh one keeps a stack for the day something catches it by mistake.
 */
class NqpFrameFreeBindFailure : ControlException()
