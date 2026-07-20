package com.interfaz.wazespeedoverlay;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class HeaderManeuverExtractor {
    public static class Result {
        public String maneuver = "unknown";
        public float confidence = 0f;
        public int minX;
        public int minY;
        public int maxX;
        public int maxY;
    }

    public Result extract(Bitmap bitmap) {
        Result result = new Result();
        if (bitmap == null) return result;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        boolean landscape = width > height;

        // Only inspect Waze's primary maneuver-icon area. The previous crop was
        // too tall and could select map controls or other white UI elements.
        int cropLeft = 0;
        int cropTop = Math.max(0, Math.round(height * (landscape ? 0.025f : 0.018f)));
        int cropRight = Math.max(1, Math.round(width * (landscape ? 0.18f : 0.20f)));
        int cropBottom = Math.max(cropTop + 1, Math.round(height * (landscape ? 0.31f : 0.145f)));
        cropBottom = Math.min(height, cropBottom);

        int step = Math.max(1, Math.min(width, height) / 520);
        int gridW = Math.max(1, (cropRight - cropLeft + step - 1) / step);
        int gridH = Math.max(1, (cropBottom - cropTop + step - 1) / step);
        boolean[] mask = new boolean[gridW * gridH];

        for (int gy = 0; gy < gridH; gy++) {
            int y = Math.min(cropBottom - 1, cropTop + gy * step);
            for (int gx = 0; gx < gridW; gx++) {
                int x = Math.min(cropRight - 1, cropLeft + gx * step);
                mask[gy * gridW + gx] = isWhite(bitmap.getPixel(x, y));
            }
        }

        List<Component> components = findComponents(mask, gridW, gridH);
        if (components.isEmpty()) return result;

        // Keep components belonging to the same maneuver icon. Waze's shaft and
        // triangular head can become separate components after antialiasing.
        Component anchor = chooseAnchor(components, gridW, gridH);
        if (anchor == null) return result;

        List<Point> points = new ArrayList<Point>();
        int joinDistance = Math.max(7, Math.round(Math.max(anchor.width(), anchor.height()) * 0.42f));

        for (Component component : components) {
            if (component.count < 5) continue;
            if (component.minY > gridH * 0.96f) continue;
            if (distanceBetween(anchor, component) <= joinDistance || overlapsExpanded(anchor, component, joinDistance)) {
                points.addAll(component.points);
            }
        }

        if (points.size() < 24) return result;

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (Point point : points) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }

        int iconW = maxX - minX + 1;
        int iconH = maxY - minY + 1;
        if (iconW < 7 || iconH < 10) return result;

        // Estimate the vertical shaft from the lower part of the icon.
        List<Integer> lowerXs = new ArrayList<Integer>();
        float lowerStart = minY + iconH * 0.55f;
        for (Point point : points) {
            if (point.y >= lowerStart) lowerXs.add(point.x);
        }
        if (lowerXs.size() < 5) return result;
        Collections.sort(lowerXs);
        float shaftX = lowerXs.get(lowerXs.size() / 2);

        // Measure where the upper arrow geometry extends relative to the shaft.
        float upperEnd = minY + iconH * 0.58f;
        int upperMinX = Integer.MAX_VALUE;
        int upperMaxX = Integer.MIN_VALUE;
        int upperCount = 0;
        double upperSumX = 0;

        for (Point point : points) {
            if (point.y <= upperEnd) {
                upperMinX = Math.min(upperMinX, point.x);
                upperMaxX = Math.max(upperMaxX, point.x);
                upperSumX += point.x;
                upperCount++;
            }
        }

        if (upperCount < 8) return result;

        float leftReach = shaftX - upperMinX;
        float rightReach = upperMaxX - shaftX;
        float upperCx = (float) (upperSumX / upperCount);
        float centroidDelta = upperCx - shaftX;
        float reachThreshold = Math.max(3.5f, iconW * 0.18f);
        float dominanceThreshold = Math.max(2.5f, iconW * 0.12f);

        if (leftReach > reachThreshold && leftReach - rightReach > dominanceThreshold) {
            result.maneuver = "turn_left";
            result.confidence = clamp(0.62f + (leftReach - rightReach) / Math.max(1f, iconW));
        } else if (rightReach > reachThreshold && rightReach - leftReach > dominanceThreshold) {
            result.maneuver = "turn_right";
            result.confidence = clamp(0.62f + (rightReach - leftReach) / Math.max(1f, iconW));
        } else if (centroidDelta < -iconW * 0.10f) {
            result.maneuver = "turn_left";
            result.confidence = clamp(0.58f + Math.abs(centroidDelta) / Math.max(1f, iconW));
        } else if (centroidDelta > iconW * 0.10f) {
            result.maneuver = "turn_right";
            result.confidence = clamp(0.58f + Math.abs(centroidDelta) / Math.max(1f, iconW));
        } else {
            // Only call it straight when the icon is tall and the upper geometry
            // stays centered around the shaft.
            float aspect = iconW / Math.max(1f, (float) iconH);
            if (aspect <= 0.72f && Math.abs(centroidDelta) <= iconW * 0.09f) {
                result.maneuver = "continue_straight";
                result.confidence = 0.76f;
            }
        }

        result.minX = cropLeft + minX * step;
        result.minY = cropTop + minY * step;
        result.maxX = Math.min(cropRight, cropLeft + (maxX + 1) * step);
        result.maxY = Math.min(cropBottom, cropTop + (maxY + 1) * step);
        return result;
    }

    private List<Component> findComponents(boolean[] mask, int gridW, int gridH) {
        List<Component> output = new ArrayList<Component>();
        boolean[] visited = new boolean[mask.length];
        int[] dx = new int[]{1, -1, 0, 0, 1, 1, -1, -1};
        int[] dy = new int[]{0, 0, 1, -1, 1, -1, 1, -1};

        for (int gy = 0; gy < gridH; gy++) {
            for (int gx = 0; gx < gridW; gx++) {
                int index = gy * gridW + gx;
                if (!mask[index] || visited[index]) continue;

                Component component = new Component();
                ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
                queue.add(index);
                visited[index] = true;

                while (!queue.isEmpty()) {
                    int value = queue.removeFirst();
                    int px = value % gridW;
                    int py = value / gridW;
                    component.add(px, py);

                    for (int i = 0; i < dx.length; i++) {
                        int nx = px + dx[i];
                        int ny = py + dy[i];
                        if (nx < 0 || ny < 0 || nx >= gridW || ny >= gridH) continue;
                        int next = ny * gridW + nx;
                        if (!mask[next] || visited[next]) continue;
                        visited[next] = true;
                        queue.add(next);
                    }
                }

                if (component.count >= 5) output.add(component);
            }
        }

        return output;
    }

    private Component chooseAnchor(List<Component> components, int gridW, int gridH) {
        List<Component> sorted = new ArrayList<Component>(components);
        Collections.sort(sorted, new Comparator<Component>() {
            public int compare(Component a, Component b) {
                return Double.compare(b.score(), a.score());
            }
        });

        for (Component component : sorted) {
            if (component.height() < 9) continue;
            if (component.minX > gridW * 0.82f) continue;
            if (component.minY > gridH * 0.82f) continue;
            return component;
        }
        return null;
    }

    private boolean overlapsExpanded(Component a, Component b, int distance) {
        return b.maxX >= a.minX - distance
            && b.minX <= a.maxX + distance
            && b.maxY >= a.minY - distance
            && b.minY <= a.maxY + distance;
    }

    private int distanceBetween(Component a, Component b) {
        int dx = 0;
        if (a.maxX < b.minX) dx = b.minX - a.maxX;
        else if (b.maxX < a.minX) dx = a.minX - b.maxX;

        int dy = 0;
        if (a.maxY < b.minY) dy = b.minY - a.maxY;
        else if (b.maxY < a.minY) dy = a.minY - b.maxY;

        return Math.max(dx, dy);
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private boolean isWhite(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return max >= 190 && min >= 168 && (max - min) <= 48;
    }

    private static class Component {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int count;
        List<Point> points = new ArrayList<Point>();

        void add(int x, int y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            count++;
            points.add(new Point(x, y));
        }

        int width() { return maxX - minX + 1; }
        int height() { return maxY - minY + 1; }
        double score() { return count + height() * 2.8 + width() * 0.5; }
    }

    private static class Point {
        final int x;
        final int y;
        Point(int x, int y) { this.x = x; this.y = y; }
    }
}
