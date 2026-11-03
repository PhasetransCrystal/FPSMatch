package net.ptcrys.fpsmatch.editor.common.text.java2d;

import java.awt.Font;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The per-cluster attribute path that {@link Java2dTextBackend#layout} used before adjacent clusters
 * with the same face were merged into one attribute run. It is kept in the test sources only, as the
 * behavioural reference for the current implementation.
 *
 * <p>It repeats the documented rules instead of calling the backend: one JDK {@link BreakIterator}
 * character pass, the first covering host face per cluster (the first fallback face when none covers
 * the cluster), one {@code FONT} attribute per cluster, the same cluster boundaries in the same order
 * and the same first use order of the faces. The current implementation is equivalent exactly when it
 * produces lines that agree with this reference on metrics, caret positions, hits and pixels.
 * Nothing here measures time, so a comparison against it can never pass or fail because of machine
 * speed; the only expected difference is the number of attribute runs handed to the JDK.</p>
 */
final class LegacyClusterReference {
    private LegacyClusterReference() {
    }

    /** One cluster with the index of the host face the documented fallback rule selects for it. */
    private record Cluster(int start, int end, int faceIndex) {
    }

    /**
     * Builds the reference line through the same private {@link TextLine} constructor the backend
     * uses, so both lines are observed through the same public queries.
     *
     * @param text logical text to lay out, non-empty
     * @param faces registered faces in fallback order, aligned with {@code hosts}
     * @param hosts host side faces, parsed from the same font files with {@link TestFonts#inspect}
     * @param size physical size in pixels
     * @return the reference line, built with one font attribute per cluster
     */
    static TextLine line(String text, List<FontFaceId> faces, List<Font> hosts, float size) {
        List<Cluster> clusters = clusters(text, hosts);
        AttributedString attributed = new AttributedString(text);
        List<FontFaceId> used = new ArrayList<>();
        int[] boundaries = new int[clusters.size() + 1];
        for (int index = 0; index < clusters.size(); index++) {
            Cluster cluster = clusters.get(index);
            FontFaceId face = faces.get(cluster.faceIndex());
            if (!used.contains(face)) {
                used.add(face);
            }
            attributed.addAttribute(TextAttribute.FONT,
                    hosts.get(cluster.faceIndex()).deriveFont(size), cluster.start(), cluster.end());
            boundaries[index] = cluster.start();
        }
        boundaries[clusters.size()] = text.length();
        TextLayout layout = new TextLayout(attributed.getIterator(), TextLine.renderContext());
        return new TextLine(text, layout, used, boundaries);
    }

    /** Cluster boundaries of {@code text}, in the order the backend records them. */
    static int[] boundaries(String text) {
        List<Integer> collected = new ArrayList<>();
        BreakIterator iterator = characterIterator(text);
        for (int boundary = iterator.first(); boundary != BreakIterator.DONE;
                boundary = iterator.next()) {
            collected.add(boundary);
        }
        int[] boundaries = new int[collected.size()];
        for (int index = 0; index < boundaries.length; index++) {
            boundaries[index] = collected.get(index);
        }
        return boundaries;
    }

    /**
     * Number of attribute runs the merge must produce: adjacent clusters that chose the same face
     * count once. A value of {@code 1} means the whole line stays on one face; a value above two
     * means the faces alternate, so the merge has to close and reopen runs.
     */
    static int runCount(String text, List<Font> hosts) {
        int runs = 0;
        int previous = -1;
        for (Cluster cluster : clusters(text, hosts)) {
            if (runs == 0 || previous != cluster.faceIndex()) {
                runs++;
            }
            previous = cluster.faceIndex();
        }
        return runs;
    }

    private static List<Cluster> clusters(String text, List<Font> hosts) {
        List<Cluster> clusters = new ArrayList<>();
        BreakIterator iterator = characterIterator(text);
        for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE;
                start = end, end = iterator.next()) {
            clusters.add(new Cluster(start, end, firstCovering(text.substring(start, end), hosts)));
        }
        return clusters;
    }

    /** The documented fallback rule: first covering face, otherwise the first fallback face. */
    private static int firstCovering(String cluster, List<Font> hosts) {
        for (int index = 0; index < hosts.size(); index++) {
            if (hosts.get(index).canDisplayUpTo(cluster) < 0) {
                return index;
            }
        }
        return 0;
    }

    private static BreakIterator characterIterator(String text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        return iterator;
    }
}
