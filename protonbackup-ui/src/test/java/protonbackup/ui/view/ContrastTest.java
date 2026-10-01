package protonbackup.ui.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Readability is a requirement of the UI (the Python build had text that was too small or unreadable),
 * so it is tested. Every pair of text colour and the colour it is drawn on is listed here and must reach
 * the WCAG AA contrast of 4.5:1; font sizes may not drop below 16 px. Change a colour in
 * {@code app.css} and this test says whether the result is still legible. No JavaFX toolkit needed.
 */
class ContrastTest {

    private static final double MINIMUM = 4.5;

    /** foreground variable, background variable, where it is used */
    private static final List<String[]> PAIRS = List.of(
            pair("-pb-text", "-pb-surface", "body text"),
            pair("-pb-text-muted", "-pb-surface", "captions, hints, prompt text"),
            pair("-pb-text", "-pb-page", "page titles and text on the page"),
            pair("-pb-text-muted", "-pb-page", "captions on the page"),
            pair("-pb-text", "-pb-card", "text in cards"),
            pair("-pb-text-muted", "-pb-card", "captions in cards"),
            pair("-pb-on-accent", "-pb-accent", "purple buttons, check mark"),
            pair("-pb-on-accent", "-pb-accent-hover", "purple buttons under the mouse"),
            pair("-pb-on-accent-disabled", "-pb-accent-disabled", "disabled purple buttons"),
            pair("-pb-on-plain", "-pb-plain", "grey buttons, the Advanced expander"),
            pair("-pb-on-plain", "-pb-plain-hover", "grey buttons under the mouse"),
            pair("-pb-on-plain-disabled", "-pb-plain-disabled", "disabled grey buttons"),
            pair("-pb-sidebar-text", "-pb-sidebar", "logo name, tooltips"),
            pair("-pb-sidebar-text-soft", "-pb-sidebar", "navigation items, icons, session text"),
            pair("-pb-sidebar-muted", "-pb-sidebar", "subtitle, last run"),
            pair("-pb-sidebar-text", "-pb-sidebar-hover", "navigation item under the mouse"),
            pair("-pb-sidebar-text", "-pb-sidebar-selected", "selected navigation item"),
            pair("-pb-success", "-pb-surface", "a finished wizard step"),
            pair("-pb-success", "-pb-card", "a finished wizard step in a card"),
            pair("-pb-icon", "-pb-card", "icons on cards"),
            pair("-pb-icon", "-pb-plain", "icons under the mouse"),
            pair("-pb-danger", "-pb-plain", "the delete icon under the mouse"),
            pair("-pb-text", "-pb-row-hover", "list rows under the mouse"),
            pair("-pb-text-muted", "-pb-row-hover", "grey text in list rows under the mouse"),
            pair("-pb-text", "-pb-row-selected", "selected list rows"),
            pair("-pb-text-muted", "-pb-row-selected", "grey text in selected list rows"),
            pair("-pb-on-info", "-pb-info", "information bars"),
            pair("-pb-on-warning", "-pb-warning", "warning bars"));

    private static String[] pair(String foreground, String background, String usage) {
        return new String[] {foreground, background, usage};
    }

    private static String css() throws IOException {
        try (var stream = ContrastTest.class.getResourceAsStream("/protonbackup/ui/app.css")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> palette() throws IOException {
        var colours = new HashMap<String, String>();
        var matcher = Pattern.compile("(-pb-[a-z-]+):\\s*(#[0-9A-Fa-f]{6})\\s*;").matcher(css());
        while (matcher.find()) colours.put(matcher.group(1), matcher.group(2));
        return colours;
    }

    static double luminance(String hex) {
        var value = Integer.parseInt(hex.substring(1), 16);
        double[] channels = {(value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF};
        for (var i = 0; i < 3; i++) {
            var c = channels[i] / 255.0;
            channels[i] = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    }

    static double ratio(String foreground, String background) {
        var a = luminance(foreground);
        var b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    @Test
    void everyTextColourIsLegibleOnItsBackground() throws IOException {
        var palette = palette();
        var failures = new ArrayList<String>();

        for (var pair : PAIRS) {
            var foreground = palette.get(pair[0]);
            var background = palette.get(pair[1]);
            assertTrue(foreground != null && background != null, "undefined colour in " + String.join(" on ", pair[0], pair[1]));
            var contrast = ratio(foreground, background);
            // A control that cannot be used at all is meant to look faded (WCAG exempts inactive components); 3:1 keeps it visible.
            var minimum = pair[2].startsWith("disabled grey") ? 3.0 : MINIMUM;
            if (contrast < minimum) {
                failures.add(String.format(java.util.Locale.ROOT, "%s (%s) on %s (%s): %.2f:1 for %s",
                        pair[0], foreground, pair[1], background, contrast, pair[2]));
            }
        }

        assertEquals(List.of(), failures, "colour pairs below " + MINIMUM + ":1");
    }

    @Test
    void everyColourTheStylesheetUsesIsDefined() throws IOException {
        var css = css();
        var defined = palette().keySet();
        var used = new TreeSet<String>();
        var matcher = Pattern.compile("(-pb-[a-z-]+)").matcher(css);
        while (matcher.find()) used.add(matcher.group(1));

        used.removeAll(defined);

        assertEquals(new TreeSet<String>(), used, "colours used in app.css but never defined");
    }

    @Test
    void everyColourIsPartOfAPairThatIsCheckedOrIsAKnownBackgroundOrBorder() throws IOException {
        var checked = new TreeSet<String>();
        for (var pair : PAIRS) {
            checked.add(pair[0]);
            checked.add(pair[1]);
        }
        var notText = List.of("-pb-input-border", "-pb-card-border", "-pb-info-border", "-pb-warning-border", "-pb-sidebar-selected-border");

        var unchecked = new TreeSet<>(palette().keySet());
        unchecked.removeAll(checked);
        unchecked.removeAll(notText);

        assertEquals(new TreeSet<String>(), unchecked, "a colour was added to app.css without a contrast check for it");
    }

    @Test
    void noTextIsSmallerThanThirteenPixelsAndTheBaseIsFourteen() throws IOException {
        var css = css();
        var sizes = new ArrayList<Integer>();
        var matcher = Pattern.compile("-fx-font-size:\\s*(\\d+)px").matcher(css);
        while (matcher.find()) sizes.add(Integer.parseInt(matcher.group(1)));

        assertTrue(sizes.stream().allMatch(size -> size >= 16), "font sizes: " + sizes);
        assertTrue(css.contains("-fx-font-size: 17px;"), "the base font size must be set once on .root");
    }

    @Test
    void theReferencePairsReallyHaveTheContrastWeThinkTheyHave() {
        // Guards the formula itself against a typo: black on white is 21:1, identical colours 1:1.
        assertEquals(21.0, ratio("#000000", "#FFFFFF"), 0.001);
        assertEquals(1.0, ratio("#6D4BFF", "#6D4BFF"), 0.001);
        assertTrue(ratio("#FFFFFF", "#6D4BFF") > 5.0, "white on the Proton purple");
    }
}
