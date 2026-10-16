package com.daniel.presentation.view.window;

import javafx.geometry.Rectangle2D;

import java.util.List;

/**
 * Pure geometry for restoring a remembered window onto the monitors that exist now. No Stage, no
 * Screen lookup: callers pass the visual bounds so this stays testable and deterministic.
 */
public final class WindowGeometry {

    private WindowGeometry() {}

    /**
     * @param saved    remembered normal rectangle, or null / non-finite when nothing usable
     * @param screens  visual bounds of the screens available now (first one is the primary)
     * @param minW     minimum window width the stage will enforce
     * @param minH     minimum window height the stage will enforce
     * @param fallback size to use when nothing is saved (its x/y are ignored: it gets centered)
     * @return a rectangle fully inside one of the screens, or {@code fallback} when no screen is known
     */
    public static WindowRect fit(WindowRect saved, List<Rectangle2D> screens,
                                 double minW, double minH, WindowRect fallback) {
        if (screens == null || screens.isEmpty()) return fallback;
        Rectangle2D primary = screens.get(0);

        if (saved == null || !saved.isFinite()) {
            return centeredOn(primary, fallback.w(), fallback.h(), minW, minH);
        }

        Rectangle2D host = bestHost(saved, screens);
        if (host == null) {
            // every monitor it touched is gone (or it was fully off-screen): same size, primary screen
            return centeredOn(primary, saved.w(), saved.h(), minW, minH);
        }
        return clampInto(host, saved.x(), saved.y(), saved.w(), saved.h(), minW, minH);
    }

    /** The screen sharing the largest area with the rectangle; null when it touches none. */
    private static Rectangle2D bestHost(WindowRect r, List<Rectangle2D> screens) {
        Rectangle2D best = null;
        double bestArea = 0;
        for (Rectangle2D s : screens) {
            double ix = Math.max(0, Math.min(r.x() + r.w(), s.getMaxX()) - Math.max(r.x(), s.getMinX()));
            double iy = Math.max(0, Math.min(r.y() + r.h(), s.getMaxY()) - Math.max(r.y(), s.getMinY()));
            double area = ix * iy;
            if (area > bestArea) {
                bestArea = area;
                best = s;
            }
        }
        return best;
    }

    private static WindowRect centeredOn(Rectangle2D s, double w, double h, double minW, double minH) {
        double cw = clampSize(w, minW, s.getWidth());
        double ch = clampSize(h, minH, s.getHeight());
        return new WindowRect(s.getMinX() + (s.getWidth() - cw) / 2, s.getMinY() + (s.getHeight() - ch) / 2, cw, ch);
    }

    private static WindowRect clampInto(Rectangle2D s, double x, double y, double w, double h,
                                        double minW, double minH) {
        double cw = clampSize(w, minW, s.getWidth());
        double ch = clampSize(h, minH, s.getHeight());
        double cx = Math.min(Math.max(x, s.getMinX()), s.getMaxX() - cw);
        double cy = Math.min(Math.max(y, s.getMinY()), s.getMaxY() - ch);
        return new WindowRect(cx, cy, cw, ch);
    }

    /** At least the minimum, but never more than the screen allows (a tiny screen wins over the minimum). */
    private static double clampSize(double v, double min, double max) {
        return Math.min(Math.max(v, min), max);
    }
}
