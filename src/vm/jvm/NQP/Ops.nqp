# NQP-specific ops for the JVM backend.
#
# What used to live here was a set of code-generation closures (preinc,
# predec, postinc, postdec, intify, numify, stringify, falsey, and the
# nqp-HLL unbox handlers) written against the bytecode emitter. The Truffle
# encoder has its own rows for every one of them, and the op registry
# (QAST::OperationsJVM) is data now, so there is nothing left to register.
# A backend-specific NQP op that needs a classlib method goes here as a
# map_classlib_hll_op('nqp', ...) call.
