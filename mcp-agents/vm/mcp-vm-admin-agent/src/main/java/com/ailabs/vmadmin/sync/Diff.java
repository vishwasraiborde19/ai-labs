package com.ailabs.vmadmin.sync;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-based unified diff (LCS), good for config files. Inputs beyond {@link #MAX_CELLS} line pairs
 * get a summary instead of a full diff.
 */
public final class Diff {

    static final long MAX_CELLS = 4_000_000L;
    private static final int CONTEXT = 3;

    private Diff() {}

    private record Op(char kind, String line, int a, int b) {}

    public static String unified(String nameA, String nameB, String a, String b) {
        List<String> x = lines(a);
        List<String> y = lines(b);
        if ((long) x.size() * y.size() > MAX_CELLS) {
            return "--- " + nameA + "\n+++ " + nameB + "\n(files differ: " + x.size() + " vs " + y.size()
                    + " lines, too large to diff)\n";
        }
        List<Op> ops = script(x, y);
        StringBuilder out = new StringBuilder("--- ").append(nameA).append('\n').append("+++ ").append(nameB).append('\n');
        int i = 0;
        boolean any = false;
        while (i < ops.size()) {
            if (ops.get(i).kind() == ' ') {
                i++;
                continue;
            }
            any = true;
            int start = Math.max(0, i - CONTEXT);
            int end = i;
            int lastChange = i;
            while (end < ops.size()) {
                if (ops.get(end).kind() != ' ') {
                    lastChange = end;
                } else if (end - lastChange > 2 * CONTEXT) {
                    break;
                }
                end++;
            }
            end = Math.min(ops.size(), lastChange + CONTEXT + 1);
            int aStart = -1, bStart = -1, aLen = 0, bLen = 0;
            for (int k = start; k < end; k++) {
                Op o = ops.get(k);
                if (o.kind() != '+') {
                    aLen++;
                    if (aStart < 0) {
                        aStart = o.a();
                    }
                }
                if (o.kind() != '-') {
                    bLen++;
                    if (bStart < 0) {
                        bStart = o.b();
                    }
                }
            }
            out.append("@@ -").append(aLen == 0 ? firstA(ops, start) : aStart + 1).append(',').append(aLen)
                    .append(" +").append(bLen == 0 ? firstB(ops, start) : bStart + 1).append(',').append(bLen).append(" @@\n");
            for (int k = start; k < end; k++) {
                out.append(ops.get(k).kind()).append(ops.get(k).line()).append('\n');
            }
            i = end;
        }
        if (!any) {
            out.append("(no differences)\n");
        }
        return out.toString();
    }

    private static int firstA(List<Op> ops, int at) {
        return at < ops.size() ? ops.get(at).a() : 0;
    }

    private static int firstB(List<Op> ops, int at) {
        return at < ops.size() ? ops.get(at).b() : 0;
    }

    static List<String> lines(String s) {
        List<String> l = new ArrayList<>(List.of(s.split("\n", -1)));
        if (!l.isEmpty() && l.get(l.size() - 1).isEmpty()) {
            l.remove(l.size() - 1);  // trailing newline
        }
        return l;
    }

    private static List<Op> script(List<String> x, List<String> y) {
        int n = x.size(), m = y.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = x.get(i).equals(y.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Op> ops = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (x.get(i).equals(y.get(j))) {
                ops.add(new Op(' ', x.get(i), i++, j++));
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new Op('-', x.get(i), i++, j));
            } else {
                ops.add(new Op('+', y.get(j), i, j++));
            }
        }
        while (i < n) {
            ops.add(new Op('-', x.get(i), i++, j));
        }
        while (j < m) {
            ops.add(new Op('+', y.get(j), i, j++));
        }
        return ops;
    }
}
