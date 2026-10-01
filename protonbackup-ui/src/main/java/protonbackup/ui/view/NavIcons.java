package protonbackup.ui.view;

import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

/**
 * The icons of the sidebar's bottom row. Built from plain geometry rather than a hand-copied path
 * string, so the shape can be reasoned about without being able to see it: a gear is a ring (two
 * circles filled with the even-odd rule, which leaves the middle hollow) with evenly spaced teeth
 * touching its outer edge; the log icon is three dots, each with a line.
 */
public final class NavIcons {

    private NavIcons() {}

    public static SVGPath gear(double size) {
        var cx = size / 2;
        var cy = size / 2;
        var outer = size * 0.28;
        var inner = size * 0.16;
        var toothLength = size * 0.12;
        var toothHalfWidth = size * 0.075;
        var teeth = 8;

        var path = new StringBuilder();
        circle(path, cx, cy, outer);
        circle(path, cx, cy, inner);
        for (var i = 0; i < teeth; i++) {
            var angle = 2 * Math.PI / teeth * i;
            var cos = Math.cos(angle);
            var sin = Math.sin(angle);
            var px = -sin; // perpendicular, for the tooth's two long edges
            var py = cos;
            var baseX = cx + cos * outer;
            var baseY = cy + sin * outer;
            var tipX = cx + cos * (outer + toothLength);
            var tipY = cy + sin * (outer + toothLength);
            path.append(String.format(java.util.Locale.ROOT, "M%.3f,%.3f L%.3f,%.3f L%.3f,%.3f L%.3f,%.3f Z ",
                    baseX + px * toothHalfWidth, baseY + py * toothHalfWidth,
                    tipX + px * toothHalfWidth, tipY + py * toothHalfWidth,
                    tipX - px * toothHalfWidth, tipY - py * toothHalfWidth,
                    baseX - px * toothHalfWidth, baseY - py * toothHalfWidth));
        }
        return shape(path, FillRule.EVEN_ODD);
    }

    public static SVGPath log(double size) {
        var dotRadius = size * 0.06;
        var lineHeight = size * 0.09;
        var dotX = size * 0.16;
        var lineLeft = size * 0.34;
        var lineRight = size * 0.86;

        var path = new StringBuilder();
        for (var y : new double[] {size * 0.28, size * 0.5, size * 0.72}) {
            circle(path, dotX, y, dotRadius);
            path.append(String.format(java.util.Locale.ROOT, "M%.3f,%.3f H%.3f V%.3f H%.3f Z ",
                    lineLeft, y - lineHeight / 2, lineRight, y + lineHeight / 2, lineLeft));
        }
        return shape(path, FillRule.NON_ZERO);
    }

    private static void circle(StringBuilder path, double cx, double cy, double radius) {
        path.append(String.format(java.util.Locale.ROOT, "M%.3f,%.3f A%.3f,%.3f 0 1,0 %.3f,%.3f A%.3f,%.3f 0 1,0 %.3f,%.3f Z ",
                cx - radius, cy, radius, radius, cx + radius, cy, radius, radius, cx - radius, cy));
    }

    private static SVGPath shape(StringBuilder path, FillRule rule) {
        var shape = new SVGPath();
        shape.setContent(path.toString());
        shape.setFillRule(rule);
        shape.getStyleClass().add("nav-icon-shape");
        return shape;
    }
}
