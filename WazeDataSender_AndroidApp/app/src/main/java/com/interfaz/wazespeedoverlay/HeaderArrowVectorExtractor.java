package com.interfaz.wazespeedoverlay;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

// Samples the real Waze arrow geometry. Lane rows and single maneuvers use
// separate detection passes because single arrows are taller and occupy only
// the left side of the header.
public final class HeaderArrowVectorExtractor {
    private static final int WHITE = 1;
    private static final int GREY = 2;

    public JSONObject extract(Bitmap source, int headerBottom) throws Exception {
        if (source == null || headerBottom <= 0) return null;

        int sw = source.getWidth();
        int sh = source.getHeight();
        headerBottom = Math.min(sh, headerBottom);
        boolean landscape = sw > sh;

        int statusCut = Math.max(1, Math.round(sh * (landscape ? 0.035f : 0.038f)));
        if (headerBottom <= statusCut + 20) return null;

        // First try the wide, shallow row used by Waze lane guidance.
        int laneBottom = Math.min(
            headerBottom,
            statusCut + Math.round((headerBottom - statusCut) * (landscape ? 0.56f : 0.50f))
        );

        JSONObject lanes = extractLaneRow(source, statusCut, laneBottom);
        if (lanes != null) return lanes;

        // Single maneuver arrows are taller. Restrict this pass to the left side
        // so distance and road text cannot become part of the vector.
        int singleRight = Math.min(sw, Math.round(sw * (landscape ? 0.28f : 0.34f)));
        int singleBottom = Math.min(
            headerBottom,
            statusCut + Math.round((headerBottom - statusCut) * (landscape ? 0.80f : 0.72f))
        );

        return extractSingleArrow(source, statusCut, singleBottom, singleRight);
    }

    private JSONObject extractLaneRow(Bitmap source, int top, int bottom) throws Exception {
        if (bottom <= top + 12) return null;

        Sample sample = sample(source, 0, top, source.getWidth(), bottom);
        List<Component> components = components(sample.mask, sample.width, sample.height);
        List<Component> accepted = new ArrayList<Component>();

        int minHeight = Math.max(7, Math.round(sample.height * 0.16f));
        int minArea = Math.max(18, sample.width * sample.height / 18000);

        for (Component c : components) {
            if (c.count < minArea) continue;
            if (c.minY > sample.height * 0.92f) continue;

            boolean tall = c.height() >= minHeight;
            boolean wide = c.width() >= minHeight * 1.20f;
            if (!tall && !wide) continue;

            float fill = c.count / (float) Math.max(1, c.width() * c.height());
            boolean elongated = c.height() >= c.width() * 1.10f
                || c.width() >= c.height() * 1.25f;

            if (!elongated && c.height() < sample.height * 0.26f && fill > 0.55f) continue;
            accepted.add(c);
        }

        if (accepted.size() < 2) return null;

        Bounds bounds = bounds(accepted);
        float span = bounds.width() / (float) Math.max(1, sample.width);

        // A real lane row spans a substantial part of the header. This prevents
        // one primary arrow from being mistaken for lane guidance.
        if (span < 0.42f) return null;

        return buildVector(sample, accepted, bounds, true);
    }

    private JSONObject extractSingleArrow(
        Bitmap source,
        int top,
        int bottom,
        int right
    ) throws Exception {
        if (bottom <= top + 16 || right < 30) return null;

        Sample sample = sample(source, 0, top, right, bottom);
        List<Component> all = components(sample.mask, sample.width, sample.height);
        Component anchor = null;
        double bestScore = -Double.MAX_VALUE;

        for (Component c : all) {
            if (c.count < 10) continue;
            if (c.height() < Math.max(9, sample.height * 0.10f)) continue;
            if (c.minY > sample.height * 0.78f) continue;

            float fill = c.count / (float) Math.max(1, c.width() * c.height());
            boolean arrowLike = c.height() >= c.width() * 0.75f || c.width() >= c.height() * 1.15f;
            if (!arrowLike && fill > 0.62f) continue;

            // Prefer tall geometry near the upper-left. Digits and labels are
            // lower, denser, and usually split into several small components.
            double score =
                c.count
                + c.height() * 7.0
                + c.width() * 1.4
                - c.minY * 1.8
                - c.minX * 0.25;

            if (score > bestScore) {
                bestScore = score;
                anchor = c;
            }
        }

        if (anchor == null) return null;

        List<Component> accepted = new ArrayList<Component>();
        int join = Math.max(5, Math.round(Math.max(anchor.width(), anchor.height()) * 0.30f));

        for (Component c : all) {
            if (c.count < 4) continue;
            if (c == anchor || distanceBetween(anchor, c) <= join || overlapsExpanded(anchor, c, join)) {
                // Do not join a second low text glyph merely because it is nearby.
                if (c != anchor && c.minY > anchor.maxY + join / 2) continue;
                accepted.add(c);
            }
        }

        if (accepted.isEmpty()) accepted.add(anchor);

        Bounds b = bounds(accepted);
        if (b.width() < 7 || b.height() < 12) return null;

        return buildVector(sample, accepted, b, false);
    }

    private JSONObject buildVector(
        Sample sample,
        List<Component> accepted,
        Bounds rawBounds,
        boolean laneMode
    ) throws Exception {
        int pad = Math.max(2, Math.round(rawBounds.height() * 0.08f));
        int minX = Math.max(0, rawBounds.minX - pad);
        int minY = Math.max(0, rawBounds.minY - pad);
        int maxX = Math.min(sample.width - 1, rawBounds.maxX + pad);
        int maxY = Math.min(sample.height - 1, rawBounds.maxY + pad);
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;

        if (bw < 7 || bh < 10) return null;

        byte[] crop = new byte[bw * bh];

        for (Component c : accepted) {
            for (int idx : c.indices) {
                int x = idx % sample.width;
                int y = idx / sample.width;

                if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                    crop[(y - minY) * bw + (x - minX)] = sample.mask[idx];
                }
            }
        }

        int targetW = Math.min(laneMode ? 260 : 180, bw);
        float vectorScale = targetW / (float) bw;
        int vw = targetW;
        int vh = Math.max(1, Math.round(bh * vectorScale));
        byte[] vectorMask = resampleMajority(crop, bw, bh, vw, vh);
        cleanup(vectorMask, vw, vh);

        JSONArray white = rowRuns(vectorMask, vw, vh, WHITE);
        JSONArray grey = rowRuns(vectorMask, vw, vh, GREY);
        if (white.length() == 0 && grey.length() == 0) return null;

        JSONObject out = new JSONObject();
        out.put("version", 3);
        out.put("encoding", "row_runs");
        out.put("width", vw);
        out.put("height", vh);
        out.put("white_runs", white);
        out.put("grey_runs", grey);
        out.put("lane_mode", laneMode);
        out.put("source_left", sample.sourceLeft + minX * sample.step);
        out.put("source_top", sample.sourceTop + minY * sample.step);
        out.put("source_right", Math.min(
            sample.sourceRight,
            sample.sourceLeft + (maxX + 1) * sample.step
        ));
        out.put("source_bottom", Math.min(
            sample.sourceBottom,
            sample.sourceTop + (maxY + 1) * sample.step
        ));
        return out;
    }

    private Sample sample(Bitmap source, int left, int top, int right, int bottom) {
        int sw = source.getWidth();
        int sh = source.getHeight();

        left = Math.max(0, Math.min(left, sw - 1));
        top = Math.max(0, Math.min(top, sh - 1));
        right = Math.max(left + 1, Math.min(right, sw));
        bottom = Math.max(top + 1, Math.min(bottom, sh));

        int step = Math.max(1, Math.round(Math.max(sw, sh) / 1100f));
        int gw = Math.max(1, (right - left + step - 1) / step);
        int gh = Math.max(1, (bottom - top + step - 1) / step);
        byte[] mask = new byte[gw * gh];

        for (int gy = 0; gy < gh; gy++) {
            int y = Math.min(bottom - 1, top + gy * step);

            for (int gx = 0; gx < gw; gx++) {
                int x = Math.min(right - 1, left + gx * step);
                mask[gy * gw + gx] = classify(source.getPixel(x, y));
            }
        }

        return new Sample(mask, gw, gh, step, left, top, right, bottom);
    }

    private byte classify(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int lum = (r * 30 + g * 59 + b * 11) / 100;

        if (max - min > 62) return 0;
        if (lum >= 172 && min >= 138) return WHITE;
        if (lum >= 56 && lum < 172) return GREY;
        return 0;
    }

    private List<Component> components(byte[] mask, int w, int h) {
        List<Component> out = new ArrayList<Component>();
        boolean[] seen = new boolean[mask.length];
        int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dy = {0, 0, 1, -1, 1, -1, 1, -1};

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int start = y * w + x;
                if (mask[start] == 0 || seen[start]) continue;

                Component c = new Component();
                ArrayDeque<Integer> q = new ArrayDeque<Integer>();
                q.add(start);
                seen[start] = true;

                while (!q.isEmpty()) {
                    int value = q.removeFirst();
                    int px = value % w;
                    int py = value / w;
                    c.add(value, px, py);

                    for (int i = 0; i < 8; i++) {
                        int nx = px + dx[i];
                        int ny = py + dy[i];

                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;

                        int next = ny * w + nx;
                        if (mask[next] == 0 || seen[next]) continue;

                        seen[next] = true;
                        q.add(next);
                    }
                }

                out.add(c);
            }
        }

        return out;
    }

    private Bounds bounds(List<Component> components) {
        Bounds b = new Bounds();

        for (Component c : components) {
            b.minX = Math.min(b.minX, c.minX);
            b.minY = Math.min(b.minY, c.minY);
            b.maxX = Math.max(b.maxX, c.maxX);
            b.maxY = Math.max(b.maxY, c.maxY);
        }

        return b;
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

    private boolean overlapsExpanded(Component a, Component b, int distance) {
        return b.maxX >= a.minX - distance
            && b.minX <= a.maxX + distance
            && b.maxY >= a.minY - distance
            && b.minY <= a.maxY + distance;
    }

    private byte[] resampleMajority(byte[] src, int sw, int sh, int dw, int dh) {
        byte[] dst = new byte[dw * dh];

        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int x0 = x * sw / dw;
                int x1 = Math.max(x0 + 1, (x + 1) * sw / dw);
                int y0 = y * sh / dh;
                int y1 = Math.max(y0 + 1, (y + 1) * sh / dh);
                int wc = 0;
                int gc = 0;

                for (int yy = y0; yy < y1 && yy < sh; yy++) {
                    for (int xx = x0; xx < x1 && xx < sw; xx++) {
                        byte value = src[yy * sw + xx];
                        if (value == WHITE) wc++;
                        else if (value == GREY) gc++;
                    }
                }

                dst[y * dw + x] = (byte) (
                    wc >= gc && wc > 0
                        ? WHITE
                        : (gc > 0 ? GREY : 0)
                );
            }
        }

        return dst;
    }

    private void cleanup(byte[] mask, int w, int h) {
        byte[] copy = mask.clone();

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int index = y * w + x;
                if (copy[index] != 0) continue;

                int wc = 0;
                int gc = 0;

                for (int yy = -1; yy <= 1; yy++) {
                    for (int xx = -1; xx <= 1; xx++) {
                        byte value = copy[(y + yy) * w + x + xx];
                        if (value == WHITE) wc++;
                        else if (value == GREY) gc++;
                    }
                }

                if (wc >= 5) mask[index] = WHITE;
                else if (gc >= 5) mask[index] = GREY;
            }
        }
    }

    private JSONArray rowRuns(byte[] mask, int w, int h, int color) {
        JSONArray out = new JSONArray();

        for (int y = 0; y < h; y++) {
            int x = 0;

            while (x < w) {
                while (x < w && mask[y * w + x] != color) x++;
                if (x >= w) break;

                int start = x;
                while (x < w && mask[y * w + x] == color) x++;

                out.put(y);
                out.put(start);
                out.put(x - start);
            }
        }

        return out;
    }

    private static final class Sample {
        final byte[] mask;
        final int width;
        final int height;
        final int step;
        final int sourceLeft;
        final int sourceTop;
        final int sourceRight;
        final int sourceBottom;

        Sample(
            byte[] mask,
            int width,
            int height,
            int step,
            int sourceLeft,
            int sourceTop,
            int sourceRight,
            int sourceBottom
        ) {
            this.mask = mask;
            this.width = width;
            this.height = height;
            this.step = step;
            this.sourceLeft = sourceLeft;
            this.sourceTop = sourceTop;
            this.sourceRight = sourceRight;
            this.sourceBottom = sourceBottom;
        }
    }

    private static final class Bounds {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxY - minY + 1;
        }
    }

    private static final class Component {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int count;
        final List<Integer> indices = new ArrayList<Integer>();

        void add(int index, int x, int y) {
            indices.add(index);
            count++;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxY - minY + 1;
        }
    }
}
