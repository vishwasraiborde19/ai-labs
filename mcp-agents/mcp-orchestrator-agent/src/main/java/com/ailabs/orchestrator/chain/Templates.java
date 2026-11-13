package com.ailabs.orchestrator.chain;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fills {@code {{input}}}, {@code {{prev}}} and {@code {{stepN}}} in step tasks and tool arguments. */
public final class Templates {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*(input|prev|step(\\d+))\\s*}}");

    private Templates() {}

    /**
     * @param outputs outputs of the steps run so far (index 0 = step 1)
     * @param json    escape values for use inside a JSON string literal
     */
    public static String render(String template, String input, List<String> outputs, boolean json) {
        if (template == null) return null;
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v;
            if ("input".equals(m.group(1))) {
                v = input;
            } else if ("prev".equals(m.group(1))) {
                v = outputs.isEmpty() ? input : outputs.get(outputs.size() - 1);
            } else {
                int n = Integer.parseInt(m.group(2));
                if (n < 1 || n > outputs.size()) {
                    throw new IllegalArgumentException("{{step" + n + "}} is not available yet (only " + outputs.size() + " steps have run)");
                }
                v = outputs.get(n - 1);
            }
            v = v == null ? "" : v;
            m.appendReplacement(sb, Matcher.quoteReplacement(json ? jsonEscape(v) : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }
}
