package org.raku.nqp.sixmodel.reprs

import java.nio.file.DirectoryStream
import java.nio.file.Path

import org.raku.nqp.sixmodel.SixModelObject

class IOHandleInstance : SixModelObject() {
    /* Object that can perform I/O operations; will be checked for its
     * capabilities by interface by ops and then invoked. */
    @JvmField var handle: Any? = null

    /* This wraps directories that were opened for lazy file listings */
    @JvmField var dirstrm: DirectoryStream<Path>? = null

    /* This is the iterator from the dirstrm */
    @JvmField var diri: Iterator<Path>? = null
}
