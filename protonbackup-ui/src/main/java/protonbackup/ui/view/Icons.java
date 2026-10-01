package protonbackup.ui.view;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** Small Material-style icons for buttons, drawn from path data (24 x 24 units) at any size. */
final class Icons {

    static final String UPLOAD = "M9 16h6v-6h4l-7-7-7 7h4v6zm-4 2h14v2H5v-2z";
    static final String REFRESH = "M17.65 6.35A7.958 7.958 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z";
    static final String DELETE = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z";
    static final String SAVE = "M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zm-5 16c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm3-10H5V5h10v4z";

    private Icons() {}

    static Node of(String pathData, double size) {
        var shape = new SVGPath();
        shape.setContent(pathData);
        shape.getStyleClass().add("icon-shape");
        var scale = size / 24.0;
        shape.setScaleX(scale);
        shape.setScaleY(scale);
        // A scaled node keeps its unscaled layout size; the group around it reports the scaled one.
        var box = new StackPane(new Group(shape));
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        return box;
    }
}
