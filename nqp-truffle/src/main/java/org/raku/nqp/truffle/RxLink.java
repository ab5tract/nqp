package org.raku.nqp.truffle;

import org.raku.nqp.truffle.RxNodes.Alt;
import org.raku.nqp.truffle.RxNodes.CaptureEnd;
import org.raku.nqp.truffle.RxNodes.Quant;
import org.raku.nqp.truffle.RxNodes.Rx;
import org.raku.nqp.truffle.RxNodes.Scan;
import org.raku.nqp.truffle.RxNodes.SubCapture;

/**
 * Turns a pattern tree into the linked graph the engine runs.
 *
 * <p>The front ends build a tree, because that is what a pattern looks like
 * on the page and what QAST::Regex hands over. The engine wants each node
 * to know what follows it, so that the continuation at every site is one
 * fixed target partial evaluation can fold. Linking is where the tree
 * becomes that graph, and it happens once, when the pattern is built.
 *
 * <p>Sequencing disappears in the process: a concatenation is not a node at
 * run time, it is the fact that one node's successor is the next.
 */
final class RxLink {

    private RxLink() { }

    /**
     * Links {@code node} so that {@code succ} follows it, and answers the
     * node to enter the result at -- which is not always the one passed in,
     * since a sequence enters at its first element.
     */
    static Rx link(Rx node, Rx succ) {
        if (node instanceof Seq seq) {
            Rx head = succ;
            for (int i = seq.parts.length - 1; i >= 0; i--) {
                head = link(seq.parts[i], head);
            }
            return head;
        }
        if (node instanceof Alt alt) {
            /* Each branch runs into what follows the alternation, so a
             * branch that matches but leaves the rest unmatchable fails
             * here and the next branch is tried. */
            Rx[] branches = alt.branches();
            for (int i = 0; i < branches.length; i++) {
                alt.setBranch(i, link(branches[i], succ));
            }
            alt.succ = succ;
            return alt;
        }
        if (node instanceof Quant quant) {
            quant.succ = succ;
            /* The body runs back into the quantifier rather than onward, so
             * one repetition can be followed by another. */
            quant.setBody(link(quant.body(), quant.again()));
            if (quant.separator() != null) {
                quant.setSeparator(link(quant.separator(), null));
            }
            return quant;
        }
        if (node instanceof SubCapture capture) {
            /* The body runs on into the end marker, which records the span
             * once everything after it has matched too. */
            CaptureEnd end = capture.end();
            capture.setBody(link(capture.body(), end));
            end.succ = succ;
            return capture;
        }
        if (node instanceof Scan scan) {
            scan.succ = succ;
            scan.setBody(link(scan.body(), null));
            return scan;
        }
        node.succ = succ;
        return node;
    }

    /**
     * A sequence, which exists only until linking: afterwards the parts are
     * joined by their successors and nothing represents the sequence itself.
     */
    static final class Seq extends Rx {
        final Rx[] parts;

        Seq(Rx... parts) { this.parts = parts; }

        @Override public int match(RxState state, int pos) {
            throw new IllegalStateException("a sequence must be linked away before matching");
        }
    }
}
