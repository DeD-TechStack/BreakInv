package com.daniel.presentation.view.util;

import javafx.scene.Cursor;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

public final class WindowResize {

    private static final double EDGE = 6;

    private WindowResize() {}

    public static void install(Stage stage, Region root) {
        double[] startX  = {0}, startY  = {0};
        double[] startW  = {0}, startH  = {0};
        double[] startSX = {0}, startSY = {0};
        boolean[] n = {false}, s = {false}, e = {false}, w = {false};

        root.setOnMouseMoved(ev -> {
            if (stage.isMaximized()) { root.setCursor(Cursor.DEFAULT); return; }
            double mx = ev.getX(), my = ev.getY();
            double rw = root.getWidth(), rh = root.getHeight();
            boolean north = my < EDGE, south = my > rh - EDGE;
            boolean west  = mx < EDGE, east  = mx > rw - EDGE;
            n[0] = north; s[0] = south; e[0] = east; w[0] = west;
            if      (north && west) root.setCursor(Cursor.NW_RESIZE);
            else if (north && east) root.setCursor(Cursor.NE_RESIZE);
            else if (south && west) root.setCursor(Cursor.SW_RESIZE);
            else if (south && east) root.setCursor(Cursor.SE_RESIZE);
            else if (north)         root.setCursor(Cursor.N_RESIZE);
            else if (south)         root.setCursor(Cursor.S_RESIZE);
            else if (west)          root.setCursor(Cursor.W_RESIZE);
            else if (east)          root.setCursor(Cursor.E_RESIZE);
            else                    root.setCursor(Cursor.DEFAULT);
        });

        root.setOnMousePressed(ev -> {
            startX[0]  = ev.getScreenX(); startY[0]  = ev.getScreenY();
            startW[0]  = stage.getWidth(); startH[0]  = stage.getHeight();
            startSX[0] = stage.getX();    startSY[0] = stage.getY();
        });

        root.setOnMouseDragged(ev -> {
            if (stage.isMaximized()) return;
            if (!n[0] && !s[0] && !e[0] && !w[0]) return;
            double dx = ev.getScreenX() - startX[0];
            double dy = ev.getScreenY() - startY[0];
            if (e[0]) stage.setWidth(Math.max(stage.getMinWidth(), startW[0] + dx));
            if (s[0]) stage.setHeight(Math.max(stage.getMinHeight(), startH[0] + dy));
            if (w[0]) {
                double nw = startW[0] - dx;
                if (nw > stage.getMinWidth()) { stage.setX(startSX[0] + dx); stage.setWidth(nw); }
            }
            if (n[0]) {
                double nh = startH[0] - dy;
                if (nh > stage.getMinHeight()) { stage.setY(startSY[0] + dy); stage.setHeight(nh); }
            }
        });

        root.setOnMouseExited(ev -> root.setCursor(Cursor.DEFAULT));
    }
}
