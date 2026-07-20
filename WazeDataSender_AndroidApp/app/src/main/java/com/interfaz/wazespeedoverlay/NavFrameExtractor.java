package com.interfaz.wazespeedoverlay;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class NavFrameExtractor {
    private int sequence = 0;

    private static class RowSample {
        int y;
        int centerX;
        int minX;
        int maxX;
        int count;
    }

    private static class Extraction {
        List<float[]> route = new ArrayList<float[]>();
        List<List<float[]>> roads = new ArrayList<List<float[]>>();
        float[] maneuverPoint = null;
        String maneuver = "unknown";
        int sourceWidth;
        int sourceHeight;
        int cyanPixels;
    }

    public JSONObject extract(Bitmap bitmap) throws Exception {
        Extraction extraction = extractScene(bitmap);

        JSONObject viewport = new JSONObject();
        viewport.put("vehicle_x", 0.50);
        viewport.put("vehicle_y", 0.88);
        viewport.put("source_width", extraction.sourceWidth);
        viewport.put("source_height", extraction.sourceHeight);
        viewport.put("heading_up", true);

        JSONObject frame = new JSONObject();
        frame.put("type", "nav_frame");
        frame.put("protocol", 4);
        frame.put("sequence", ++sequence);
        frame.put("timestamp", System.currentTimeMillis());
        frame.put("viewport", viewport);
        frame.put("route", pointsToJson(extraction.route));
        frame.put("roads", linesToJson(extraction.roads));
        frame.put("maneuver_point", extraction.maneuverPoint == null
            ? JSONObject.NULL
            : point(extraction.maneuverPoint[0], extraction.maneuverPoint[1]));
        frame.put("maneuver", chooseManeuver(extraction.maneuver));
        frame.put("distance_m", TelemetryState.getDistanceM());
        frame.put("road_name", TelemetryState.getRoadName());
        frame.put("speed_limit", TelemetryState.getSpeedLimit());
        frame.put("current_speed", TelemetryState.getCurrentSpeed());
        frame.put("route_quality", extraction.route.size());
        frame.put("cyan_pixels", extraction.cyanPixels);
        return frame;
    }

    private Extraction extractScene(Bitmap bitmap) {
        Extraction out = new Extraction();
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        out.sourceWidth = w;
        out.sourceHeight = h;

        int cropTop = Math.max(0, (int) (h * 0.15f));
        int cropBottom = Math.min(h - 1, (int) (h * 0.87f));
        int rowStep = Math.max(2, h / 220);

        List<RowSample> allRows = scanRows(bitmap, cropTop, cropBottom, rowStep, out);
        if (allRows.size() < 4) {
            out.route = fallbackRoute();
            return out;
        }

        RowSample anchor = findAnchor(allRows, w, h);
        if (anchor == null) {
            out.route = fallbackRoute();
            return out;
        }

        List<RowSample> trace = traceFromAnchor(allRows, anchor, w);
        if (trace.size() < 4) {
            out.route = fallbackRoute();
            return out;
        }

        trace = smoothTrace(trace);
        buildNormalizedScene(trace, anchor, w, h, out);
        return out;
    }

    private List<RowSample> scanRows(Bitmap bitmap, int top, int bottom, int rowStep, Extraction extraction) {
        int w = bitmap.getWidth();
        List<RowSample> rows = new ArrayList<RowSample>();

        for (int y = bottom; y >= top; y -= rowStep) {
            RowSample sample = new RowSample();
            sample.y = y;
            sample.minX = w;
            sample.maxX = -1;
            long sum = 0;

            for (int x = 0; x < w; x += 2) {
                if (isRouteCyan(bitmap.getPixel(x, y))) {
                    sample.count++;
                    extraction.cyanPixels++;
                    sample.minX = Math.min(sample.minX, x);
                    sample.maxX = Math.max(sample.maxX, x);
                    sum += x;
                }
            }

            if (sample.count >= 2) {
                sample.centerX = (int) (sum / sample.count);
                rows.add(sample);
            }
        }

        return rows;
    }

    private RowSample findAnchor(List<RowSample> rows, int w, int h) {
        RowSample best = null;
        double bestScore = Double.MAX_VALUE;
        int minY = (int) (h * 0.58f);
        int maxY = (int) (h * 0.90f);

        for (RowSample row : rows) {
            if (row.y < minY || row.y > maxY) {
                continue;
            }

            int span = row.maxX - row.minX;
            if (span > w * 0.30f) {
                continue;
            }

            double centerPenalty = Math.abs(row.centerX - w * 0.50) / w;
            double bottomPenalty = Math.abs(row.y - h * 0.80) / (double) h;
            double widthPenalty = span / (double) w;
            double score = centerPenalty * 3.0 + bottomPenalty + widthPenalty;

            if (score < bestScore) {
                bestScore = score;
                best = row;
            }
        }

        if (best == null) {
            for (RowSample row : rows) {
                if (best == null || row.y > best.y) {
                    best = row;
                }
            }
        }

        return best;
    }

    private List<RowSample> traceFromAnchor(List<RowSample> rows, RowSample anchor, int w) {
        List<RowSample> trace = new ArrayList<RowSample>();
        int previousX = anchor.centerX;
        int maxJump = Math.max(36, w / 7);
        boolean started = false;
        int misses = 0;

        for (RowSample row : rows) {
            if (!started) {
                if (row == anchor) {
                    started = true;
                    trace.add(row);
                }
                continue;
            }

            int span = row.maxX - row.minX;
            boolean overlapsPrevious = previousX >= row.minX - maxJump && previousX <= row.maxX + maxJump;
            boolean centerNear = Math.abs(row.centerX - previousX) <= maxJump;

            if (!overlapsPrevious && !centerNear) {
                misses++;
                if (misses > 4) {
                    break;
                }
                continue;
            }

            misses = 0;
            trace.add(row);

            if (span > w * 0.16f) {
                // A broad cyan run usually represents the approaching junction/turn.
                previousX = clamp(previousX, row.minX, row.maxX);
                break;
            }

            previousX = (previousX * 3 + row.centerX) / 4;
        }

        return trace;
    }

    private List<RowSample> smoothTrace(List<RowSample> input) {
        List<RowSample> output = new ArrayList<RowSample>();

        for (int i = 0; i < input.size(); i++) {
            List<Integer> window = new ArrayList<Integer>();
            int from = Math.max(0, i - 2);
            int to = Math.min(input.size() - 1, i + 2);

            for (int j = from; j <= to; j++) {
                window.add(input.get(j).centerX);
            }

            Collections.sort(window);
            RowSample source = input.get(i);
            RowSample copy = new RowSample();
            copy.y = source.y;
            copy.centerX = window.get(window.size() / 2);
            copy.minX = source.minX;
            copy.maxX = source.maxX;
            copy.count = source.count;
            output.add(copy);
        }

        return output;
    }

    private void buildNormalizedScene(List<RowSample> trace, RowSample anchor, int w, int h, Extraction out) {
        float anchorX = anchor.centerX;
        float anchorY = anchor.y;
        float verticalRange = Math.max(h * 0.32f, anchorY - trace.get(trace.size() - 1).y);
        float horizontalScale = Math.max(w * 0.62f, verticalRange);

        List<float[]> route = new ArrayList<float[]>();
        route.add(new float[]{0.50f, 0.94f});
        route.add(new float[]{0.50f, 0.88f});

        RowSample broadRow = null;
        for (RowSample row : trace) {
            float nx = 0.50f + (row.centerX - anchorX) / horizontalScale;
            float ny = 0.88f - (anchorY - row.y) / verticalRange * 0.68f;
            route.add(new float[]{clamp(nx, 0.06f, 0.94f), clamp(ny, 0.10f, 0.88f)});

            if (row.maxX - row.minX > w * 0.16f) {
                broadRow = row;
            }
        }

        route = removeNearDuplicatePoints(route, 0.025f);

        if (broadRow != null) {
            float jy = 0.88f - (anchorY - broadRow.y) / verticalRange * 0.68f;
            float leftDistance = broadRow.centerX - broadRow.minX;
            float rightDistance = broadRow.maxX - broadRow.centerX;
            boolean rightTurn = rightDistance >= leftDistance;
            float targetX = rightTurn ? 0.91f : 0.09f;

            float[] junction = new float[]{0.50f + (broadRow.centerX - anchorX) / horizontalScale, clamp(jy, 0.14f, 0.78f)};
            junction[0] = clamp(junction[0], 0.16f, 0.84f);

            trimRouteAfterJunction(route, junction[1]);
            appendIfFar(route, junction, 0.025f);
            appendIfFar(route, new float[]{targetX, junction[1]}, 0.025f);

            out.maneuverPoint = junction;
            out.maneuver = rightTurn ? "right" : "left";

            List<float[]> crossRoad = new ArrayList<float[]>();
            crossRoad.add(new float[]{0.05f, junction[1]});
            crossRoad.add(new float[]{0.95f, junction[1]});
            out.roads.add(crossRoad);

            List<float[]> straightContinuation = new ArrayList<float[]>();
            straightContinuation.add(junction);
            straightContinuation.add(new float[]{junction[0], 0.08f});
            out.roads.add(straightContinuation);
        } else {
            out.maneuverPoint = route.size() > 3 ? route.get(route.size() - 1) : null;
            out.maneuver = detectDirection(route);
        }

        out.route = route;
    }

    private void trimRouteAfterJunction(List<float[]> route, float junctionY) {
        for (int i = route.size() - 1; i >= 0; i--) {
            if (route.get(i)[1] < junctionY - 0.025f) {
                route.remove(i);
            }
        }
    }

    private String detectDirection(List<float[]> route) {
        if (route.size() < 4) {
            return "straight";
        }

        float[] start = route.get(Math.max(0, route.size() - 4));
        float[] end = route.get(route.size() - 1);
        float dx = end[0] - start[0];

        if (dx > 0.10f) return "right";
        if (dx < -0.10f) return "left";
        return "straight";
    }

    private String chooseManeuver(String extracted) {
        String accessibility = TelemetryState.getManeuver();
        if (accessibility != null && !"unknown".equals(accessibility) && accessibility.length() > 0) {
            return accessibility;
        }
        return extracted;
    }

    private List<float[]> fallbackRoute() {
        List<float[]> route = new ArrayList<float[]>();
        route.add(new float[]{0.50f, 0.94f});
        route.add(new float[]{0.50f, 0.88f});
        route.add(new float[]{0.50f, 0.62f});
        route.add(new float[]{0.50f, 0.30f});
        return route;
    }

    private boolean isRouteCyan(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        boolean cyanHue = hsv[0] >= 170f && hsv[0] <= 205f && hsv[1] >= 0.45f && hsv[2] >= 0.45f;
        boolean rawCyan = g > 125 && b > 135 && r < 115 && Math.abs(g - b) < 105;
        return cyanHue || rawCyan;
    }

    private List<float[]> removeNearDuplicatePoints(List<float[]> input, float minDistance) {
        List<float[]> output = new ArrayList<float[]>();
        for (float[] point : input) {
            appendIfFar(output, point, minDistance);
        }
        return output;
    }

    private void appendIfFar(List<float[]> points, float[] point, float minDistance) {
        if (points.size() == 0 || distance(points.get(points.size() - 1), point) >= minDistance) {
            points.add(point);
        }
    }

    private float distance(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private JSONArray pointsToJson(List<float[]> points) throws Exception {
        JSONArray array = new JSONArray();
        for (float[] point : points) {
            array.put(point(point[0], point[1]));
        }
        return array;
    }

    private JSONArray linesToJson(List<List<float[]>> lines) throws Exception {
        JSONArray result = new JSONArray();
        for (List<float[]> line : lines) {
            result.put(pointsToJson(line));
        }
        return result;
    }

    private JSONArray point(float x, float y) throws Exception {
        JSONArray point = new JSONArray();
        point.put(x);
        point.put(y);
        return point;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
