package com.interfaz.wazespeedoverlay;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.json.JSONArray;

public class WazeSpeedAccessibilityService extends AccessibilityService {
    private Handler handler = new Handler(Looper.getMainLooper());
    private long lastScanTime = 0;
    private String lastShownSpeed = "--";
    private String pendingSpeed = "";
    private int pendingCount = 0;
    private String lastPackage = "";
    private String lastSentSpeed = "";
    private long lastSentTime = 0;

    private static final String WAZE_PACKAGE = "com.waze";

    private static final Set<String> VALID_SPEEDS = new HashSet<String>(Arrays.asList(
        "5", "10", "15", "20", "25", "30", "35", "40", "45", "50", "60", "70", "80", "90", "100", "110", "120", "130", "140"
    ));

    private static final Set<String> COMMON_LIMITS = new HashSet<String>(Arrays.asList(
        "20", "30", "40", "50", "60", "70", "80", "90", "100", "110", "120", "130"
    ));

    public void onServiceConnected() {
        WazeSpeedOverlayManager.get(this).show();
        FlirCamUdpTransport.get(this).start();
        HudUdpTransport.get(this).start();
        WazeSpeedOverlayManager.get(this).setSpeed("--", "V3 accessibility connected. Open Waze. " + FlirCamUdpTransport.get(this).getStatus());
    }

    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }

        lastPackage = event.getPackageName().toString();

        long now = System.currentTimeMillis();
        if (now - lastScanTime < 220) {
            return;
        }
        lastScanTime = now;

        handler.post(new Runnable() {
            public void run() {
                scanScreen();
            }
        });
    }

    public void onInterrupt() {}

    private void scanScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            clearUncertainTelemetry();
            sendHudHeader();
            WazeSpeedOverlayManager.get(this).setSpeed("--", "No active window root");
            return;
        }

        CharSequence pkg = root.getPackageName();
        if (pkg == null || !WAZE_PACKAGE.equals(pkg.toString())) {
            clearUncertainTelemetry();
            sendHudHeader();
            WazeSpeedOverlayManager.get(this).setSpeed("--", "Waiting for Waze. Active package: " + lastPackage);
            return;
        }

        List<NodeItem> allNodes = new ArrayList<NodeItem>();
        collectNodes(root, allNodes, 0, -1);

        List<NodeCandidate> candidates = buildCandidates(allNodes);
        scoreCandidates(candidates, allNodes);
        Collections.sort(candidates, new Comparator<NodeCandidate>() {
            public int compare(NodeCandidate a, NodeCandidate b) {
                return b.score - a.score;
            }
        });

        NodeCandidate best = chooseBestCandidate(candidates);

        if (best != null) {
            String stableSpeed = applyStability(best.value);
            maybeSendSpeed(stableSpeed);
            updateNavigationTelemetry(allNodes);
            sendHudHeader();
            WazeSpeedOverlayManager.get(this).setSpeed(stableSpeed, buildDebug(best, candidates, allNodes));
        } else {
            pendingSpeed = "";
            pendingCount = 0;
            maybeSendSpeed("--");
            updateNavigationTelemetry(allNodes);
            sendHudHeader();
            WazeSpeedOverlayManager.get(this).setSpeed("--", buildNoMatchDebug(candidates, allNodes));
        }
    }

    private void collectNodes(AccessibilityNodeInfo node, List<NodeItem> out, int depth, int parentIndex) {
        if (node == null || depth > 50) {
            return;
        }

        NodeItem item = new NodeItem();
        item.index = out.size();
        item.parentIndex = parentIndex;
        item.depth = depth;
        item.text = node.getText() == null ? "" : node.getText().toString().trim();
        item.desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
        item.className = node.getClassName() == null ? "" : node.getClassName().toString();
        item.viewId = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName();
        item.childCount = node.getChildCount();
        item.bounds = new Rect();
        node.getBoundsInScreen(item.bounds);
        out.add(item);

        for (int i = 0; i < node.getChildCount(); i++) {
            collectNodes(node.getChild(i), out, depth + 1, item.index);
        }
    }

    private List<NodeCandidate> buildCandidates(List<NodeItem> nodes) {
        List<NodeCandidate> candidates = new ArrayList<NodeCandidate>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeItem n = nodes.get(i);
            addCandidateFromString(candidates, n, n.text, "text");
            addCandidateFromString(candidates, n, n.desc, "description");
        }
        return candidates;
    }

    private void addCandidateFromString(List<NodeCandidate> out, NodeItem node, String raw, String source) {
        if (raw == null || raw.length() == 0) {
            return;
        }

        String normalized = normalizeSpeedText(raw);
        if (normalized == null) {
            return;
        }

        NodeCandidate c = new NodeCandidate();
        c.value = normalized;
        c.raw = raw;
        c.source = source;
        c.node = node;
        c.score = 0;
        c.scoreLog = new ArrayList<String>();
        out.add(c);
    }

    private String normalizeSpeedText(String text) {
        if (text == null) {
            return null;
        }

        String t = text.trim();
        if (t.length() == 0) {
            return null;
        }

        if (looksLikeTime(t)) {
            return null;
        }

        String digits = "";
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits += ch;
            } else if (digits.length() > 0) {
                break;
            }
        }

        if (digits.length() == 0) {
            return null;
        }

        if (!VALID_SPEEDS.contains(digits)) {
            return null;
        }

        return digits;
    }

    private boolean looksLikeTime(String t) {
        return t.matches(".*[0-9]{1,2}:[0-9]{2}.*");
    }

    private void scoreCandidates(List<NodeCandidate> candidates, List<NodeItem> allNodes) {
        for (int i = 0; i < candidates.size(); i++) {
            NodeCandidate c = candidates.get(i);
            c.score = 0;

            addScore(c, 500, "valid speed-like value");

            if (COMMON_LIMITS.contains(c.value)) {
                addScore(c, 70, "common speed limit value");
            }

            scoreShape(c);
            scoreTextContext(c, allNodes);
            scoreParentSiblingContext(c, allNodes);
            scoreCurrentSpeedCluster(c, allNodes);
            scoreStability(c);
            scoreScreenHeuristics(c);
        }
    }

    private void scoreShape(NodeCandidate c) {
        Rect r = c.node.bounds;
        int w = r.width();
        int h = r.height();

        if (w <= 0 || h <= 0) {
            addScore(c, -600, "empty bounds");
            return;
        }

        if (w >= 18 && h >= 18 && w <= 260 && h <= 260) {
            addScore(c, 80, "reasonable widget size");
        }

        float ratio = w > h ? (float) w / (float) h : (float) h / (float) w;
        if (ratio <= 1.7f) {
            addScore(c, 110, "square/circle-like bounds");
        }

        if (w > 300 || h > 180) {
            addScore(c, -150, "too large for speed sign text node");
        }
    }

    private void scoreTextContext(NodeCandidate c, List<NodeItem> allNodes) {
        String rawLower = c.raw.toLowerCase();
        String around = getNearbyText(c.node, allNodes, 130).toLowerCase();

        if (c.raw.equals(c.value)) {
            addScore(c, 70, "raw is pure number");
        }

        if (c.source.equals("text")) {
            addScore(c, 50, "text node");
        }

        if (rawLower.contains("km/h") || rawLower.contains("mph")) {
            addScore(c, -120, "current speed text, not speed limit");
        }

        if (around.contains("km/h") || around.contains("mph")) {
            addScore(c, 180, "near current speed cluster");
        }

        if (rawLower.contains("m") || around.contains(" m") || around.contains(" metri")) {
            addScore(c, -260, "near distance text");
        }

        if (around.contains("eta") || around.contains(" h") || around.contains(" min")) {
            addScore(c, -260, "near ETA/time panel");
        }

        if (around.contains("km") && !around.contains("km/h")) {
            addScore(c, -220, "near trip distance text");
        }
    }

    private void scoreParentSiblingContext(NodeCandidate c, List<NodeItem> allNodes) {
        NodeItem parent = getParent(c.node, allNodes);
        if (parent != null) {
            String parentText = (parent.text + " " + parent.desc + " " + parent.viewId + " " + parent.className).toLowerCase();
            if (parentText.contains("speed") || parentText.contains("limit")) {
                addScore(c, 260, "parent contains speed/limit hint");
            }

            if (parent.childCount <= 4) {
                addScore(c, 50, "small parent group");
            }
        }

        String siblingText = getSiblingText(c.node, allNodes).toLowerCase();
        if (siblingText.contains("km/h") || siblingText.contains("mph")) {
            addScore(c, 240, "sibling near current speed unit");
        }
        if (siblingText.contains("eta") || siblingText.contains(" h") || siblingText.contains(" km")) {
            addScore(c, -260, "sibling looks like ETA/distance");
        }
    }

    private void scoreCurrentSpeedCluster(NodeCandidate c, List<NodeItem> allNodes) {
        List<NodeItem> unitNodes = findUnitNodes(allNodes);
        if (unitNodes.size() == 0) {
            return;
        }

        int bestDistance = 999999;
        for (int i = 0; i < unitNodes.size(); i++) {
            int d = distanceBetween(c.node.bounds, unitNodes.get(i).bounds);
            if (d < bestDistance) {
                bestDistance = d;
            }
        }

        if (bestDistance < 90) {
            addScore(c, -120, "too close to current speed value/unit");
        } else if (bestDistance < 360) {
            addScore(c, 310, "near but separate from current speed cluster");
        } else if (bestDistance < 650) {
            addScore(c, 120, "same area as speed cluster");
        }
    }

    private void scoreStability(NodeCandidate c) {
        if (c.value.equals(lastShownSpeed)) {
            addScore(c, 260, "same as last shown speed");
        }
        if (c.value.equals(pendingSpeed)) {
            addScore(c, 90 * pendingCount, "pending stable candidate");
        }
    }

    private void scoreScreenHeuristics(NodeCandidate c) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int cx = c.node.bounds.centerX();
        int cy = c.node.bounds.centerY();

        if (cy < sh * 0.22f) {
            addScore(c, -300, "top navigation banner area");
        }

        if (cy > sh * 0.72f) {
            addScore(c, 80, "lower driving UI area");
        }

        if (cx > sw * 0.78f && cy > sh * 0.72f) {
            addScore(c, -280, "bottom-right action/ETA area");
        }
    }

    private NodeCandidate chooseBestCandidate(List<NodeCandidate> candidates) {
        if (candidates.size() == 0) {
            return null;
        }

        NodeCandidate best = candidates.get(0);
        if (best.score < 520) {
            return null;
        }

        return best;
    }

    private String applyStability(String detected) {
        if (detected == null || detected.length() == 0) {
            return lastShownSpeed;
        }

        if (detected.equals(lastShownSpeed)) {
            pendingSpeed = detected;
            pendingCount = 0;
            return lastShownSpeed;
        }

        if (detected.equals(pendingSpeed)) {
            pendingCount++;
        } else {
            pendingSpeed = detected;
            pendingCount = 1;
        }

        if (pendingCount >= 2 || lastShownSpeed.equals("--")) {
            lastShownSpeed = detected;
            pendingSpeed = detected;
            pendingCount = 0;
        }

        return lastShownSpeed;
    }


    private void maybeSendSpeed(String speed) {
        long now = System.currentTimeMillis();
        if (speed == null) speed = "--";

        if (!speed.equals(lastSentSpeed) || now - lastSentTime > 1200) {
            lastSentSpeed = speed;
            lastSentTime = now;
            FlirCamUdpTransport.get(this).sendSpeed(speed, speed.equals("--") ? 0 : 100, "waze_accessibility");
            TelemetryState.setSpeedLimit(speed);
        }
    }


    private void clearUncertainTelemetry() {
        TelemetryState.setCurrentSpeed(-1);
        TelemetryState.setSpeedLimit("");
        TelemetryState.clearNavigation();
        TelemetryState.setAlertType("");
    }

    private void updateNavigationTelemetry(List<NodeItem> nodes) {
        int currentSpeed = findCurrentSpeed(nodes);
        TelemetryState.setCurrentSpeed(currentSpeed);

        NodeItem distanceNode = findManeuverDistanceNode(nodes);
        NodeItem roadNode = findRoadNameNode(nodes);
        int distance = distanceNode == null ? -1 : parseManeuverDistance(distanceNode);
        String road = roadNode == null ? "" : roadNode.text.trim();
        String accessibilityManeuver = findManeuver(nodes);
        JSONArray lanes = findStructuredLanes(nodes);

        boolean navigationValid = distance >= 0
            && road.length() >= 2
            && !looksLikeClockOrEta(road);

        if (navigationValid) {
            TelemetryState.setDistanceM(distance);
            TelemetryState.setRoadName(road);
            TelemetryState.setRawTextBounds(distanceNode.bounds, roadNode.bounds);
            if (!"unknown".equals(accessibilityManeuver)) {
                TelemetryState.setManeuver(accessibilityManeuver);
            }
            TelemetryState.setLaneGuidance(findLaneGuidance(nodes));
            TelemetryState.setRoundaboutExit(findRoundaboutExit(nodes));
            TelemetryState.setLanes(lanes);
            TelemetryState.setNavigationValid(true);
        } else {
            TelemetryState.clearNavigation();
        }

        TelemetryState.setAlertType(findAlertType(nodes));
    }

    private int findCurrentSpeed(List<NodeItem> nodes) {
        List<NodeItem> units = findUnitNodes(nodes);
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (NodeItem unit : units) {
            for (NodeItem node : nodes) {
                String raw = (node.text + " " + node.desc).trim();
                if (!raw.matches("^[0-9]{1,3}$")) continue;
                int value;
                try { value = Integer.parseInt(raw); } catch (Exception ignored) { continue; }
                if (value < 0 || value > 250) continue;
                int distance = distanceBetween(unit.bounds, node.bounds);
                if (distance < bestDistance && distance < 260) {
                    bestDistance = distance;
                    best = value;
                }
            }
        }
        return best;
    }

    private NodeItem findManeuverDistanceNode(List<NodeItem> nodes) {
        Pattern pattern = Pattern.compile("([0-9]+(?:[\\.,][0-9]+)?)\\s*(m|km)", Pattern.CASE_INSENSITIVE);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int bestY = Integer.MAX_VALUE;
        NodeItem best = null;

        for (NodeItem node : nodes) {
            if (node.bounds.centerY() > screenHeight * 0.38f) continue;
            String raw = (node.text + " " + node.desc).trim();
            Matcher matcher = pattern.matcher(raw);
            if (!matcher.find()) continue;

            try {
                float value = Float.parseFloat(matcher.group(1).replace(',', '.'));
                int meters = "km".equalsIgnoreCase(matcher.group(2))
                    ? Math.round(value * 1000f)
                    : Math.round(value);

                if (meters >= 0 && meters <= 100000 && node.bounds.centerY() < bestY) {
                    bestY = node.bounds.centerY();
                    best = node;
                }
            } catch (Exception ignored) {
            }
        }

        return best;
    }

    private int parseManeuverDistance(NodeItem node) {
        if (node == null) return -1;

        Pattern pattern = Pattern.compile("([0-9]+(?:[\\.,][0-9]+)?)\\s*(m|km)", Pattern.CASE_INSENSITIVE);
        String raw = (node.text + " " + node.desc).trim();
        Matcher matcher = pattern.matcher(raw);
        if (!matcher.find()) return -1;

        try {
            float value = Float.parseFloat(matcher.group(1).replace(',', '.'));
            return "km".equalsIgnoreCase(matcher.group(2))
                ? Math.round(value * 1000f)
                : Math.round(value);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String findManeuver(List<NodeItem> nodes) {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        String all = "";
        for (NodeItem node : nodes) {
            if (node.bounds.centerY() <= screenHeight * 0.42f) all += " " + node.text + " " + node.desc + " " + node.viewId;
        }
        String value = all.toLowerCase();
        if (containsAny(value,"roundabout","sens giratoriu")) return "roundabout";
        if (containsAny(value,"u-turn","uturn","întoarc","intoarc")) return "uturn_left";
        if (containsAny(value,"sharp left","stânga brusc","stanga brusc")) return "sharp_left";
        if (containsAny(value,"sharp right","dreapta brusc")) return "sharp_right";
        if (containsAny(value,"slight left","ușor stânga","usor stanga")) return "slight_left";
        if (containsAny(value,"slight right","ușor dreapta","usor dreapta")) return "slight_right";
        if (containsAny(value,"keep left","ține stânga","tine stanga")) return "keep_left";
        if (containsAny(value,"keep right","ține dreapta","tine dreapta")) return "keep_right";
        if (containsAny(value,"exit left","ieșire stânga","iesire stanga")) return "exit_left";
        if (containsAny(value,"exit right","ieșire dreapta","iesire dreapta")) return "exit_right";
        if (containsAny(value,"turn left","left turn","virați la stânga","virati la stanga")) return "turn_left";
        if (containsAny(value,"turn right","right turn","virați la dreapta","virati la dreapta")) return "turn_right";
        if (containsAny(value,"continue","straight","înainte","inainte")) return "continue_straight";
        return "unknown";
    }

    private NodeItem findRoadNameNode(List<NodeItem> nodes) {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        NodeItem best = null;
        int bestScore = Integer.MIN_VALUE;

        for (NodeItem node : nodes) {
            if (node.bounds.centerY() > screenHeight * 0.32f) continue;

            String raw = node.text == null ? "" : node.text.trim();
            String lower = raw.toLowerCase(java.util.Locale.ROOT);

            if (raw.length() < 3 || raw.length() > 90) continue;
            if (looksLikeClockOrEta(raw)) continue;
            if (raw.matches(".*[0-9]+\\s*(m|km).*")) continue;
            if (containsAny(
                lower,
                "no gps",
                "approximate location",
                "showing your approximate",
                "gps signal",
                "searching for gps",
                "waiting for gps",
                "then",
                "and then"
            )) continue;

            int score = raw.length();
            int centerY = node.bounds.centerY();

            if (centerY > screenHeight * 0.075f) score += 35;
            if (centerY > screenHeight * 0.11f) score += 25;
            if (node.bounds.width() > 120) score += 20;
            if (raw.indexOf(' ') >= 0) score += 10;

            if (score > bestScore) {
                best = node;
                bestScore = score;
            }
        }

        return best;
    }

    private String findLaneGuidance(List<NodeItem> nodes) {
        String result = "";
        for (NodeItem node : nodes) {
            String raw = (node.text + " " + node.desc).trim();
            String lower = raw.toLowerCase();
            if (lower.contains("lane") || lower.contains("bandă") || lower.contains("banda")) {
                result = raw;
                break;
            }
        }
        return result;
    }

    private int findRoundaboutExit(List<NodeItem> nodes) {
        Pattern p = Pattern.compile("(?:exit|ieșirea|iesirea)\\s*(?:number|nr\\.)?\\s*([1-9][0-9]?)", Pattern.CASE_INSENSITIVE);
        for(NodeItem node:nodes){
            String raw=(node.text+" "+node.desc).trim();
            Matcher matcher=p.matcher(raw);
            if(matcher.find()) try { return Integer.parseInt(matcher.group(1)); } catch(Exception ignored){}
        }
        return 0;
    }

    private String findAlertType(List<NodeItem> nodes) {
        String all="";
        int screenHeight=getResources().getDisplayMetrics().heightPixels;
        for(NodeItem node:nodes){
            if(node.bounds.centerY() < screenHeight*0.92f) all += " " + node.text + " " + node.desc + " " + node.viewId;
        }
        String v=all.toLowerCase();
        if(containsAny(v,"police","poliție","politie")) return "police";
        if(containsAny(v,"speed camera","radar","camera de viteză","camera de viteza")) return "speed_camera";
        if(containsAny(v,"red light camera","camera semafor")) return "red_light_camera";
        if(containsAny(v,"accident","crash")) return "accident";
        if(containsAny(v,"traffic jam","heavy traffic","trafic intens","ambuteiaj")) return "traffic";
        if(containsAny(v,"construction","road work","lucrări","lucrari")) return "construction";
        if(containsAny(v,"pothole","groapă","groapa")) return "pothole";
        if(containsAny(v,"road closed","closure","drum închis","drum inchis")) return "closure";
        if(containsAny(v,"hazard","pericol","object on road","obiect pe drum")) return "hazard";
        return "";
    }

    private JSONArray findStructuredLanes(List<NodeItem> nodes) {
        JSONArray lanes=new JSONArray();
        Pattern lanePattern=Pattern.compile("(?:lane|band[aă])\\s*([1-8])?[^:]*:?\\s*(.*)",Pattern.CASE_INSENSITIVE);
        for(NodeItem node:nodes){
            String raw=(node.text+" "+node.desc).trim();
            Matcher matcher=lanePattern.matcher(raw);
            if(!matcher.find()) continue;
            String lower=raw.toLowerCase();
            JSONArray directions=new JSONArray();
            if(containsAny(lower,"u-turn","uturn","întoarc","intoarc")) directions.put("uturn_left");
            if(containsAny(lower,"left","stânga","stanga")) directions.put("turn_left");
            if(containsAny(lower,"straight","forward","înainte","inainte")) directions.put("continue_straight");
            if(containsAny(lower,"right","dreapta")) directions.put("turn_right");
            if(directions.length()==0) continue;
            try {
                JSONObject lane=new JSONObject();
                lane.put("directions",directions);
                JSONArray selected=new JSONArray();
                if(containsAny(lower,"recommended","selected","use","highlighted","recomandat")){
                    for(int i=0;i<directions.length();i++) selected.put(directions.getString(i));
                }
                lane.put("selected",selected);
                lanes.put(lane);
            } catch(Exception ignored){}
        }
        return lanes;
    }


    private boolean looksLikeClockOrEta(String value) {
        if (value == null) return true;
        String clean = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (clean.matches("^[0-9]{1,2}:[0-9]{2}$")) return true;
        if (clean.matches("^[0-9]{1,2}:[0-9]{2}\\s*(am|pm)?$")) return true;
        if (clean.matches("^[0-9]+\\s*(min|mins|minute|minutes|h|hr|hrs)$")) return true;
        if (containsAny(clean, "eta", "arrival time", "estimated arrival")) return true;
        return false;
    }

    private boolean containsAny(String source, String... values) {
        for (String value : values) if (source.contains(value)) return true;
        return false;
    }

    private void sendHudHeader() {
        try {
            JSONObject object = new JSONObject();
            object.put("type", "hud_semantic");
            object.put("timestamp", System.currentTimeMillis());
            object.put("current_speed", TelemetryState.getCurrentSpeed());
            object.put("current_speed_valid", TelemetryState.isCurrentSpeedValid());
            object.put("speed_limit", TelemetryState.getSpeedLimit());
            object.put("speed_limit_valid", TelemetryState.isSpeedLimitValid());
            object.put("navigation_valid", TelemetryState.isNavigationValid());
            object.put("arrow_vector_supported", false);
            object.put("raw_header_supported", true);
            object.put("raw_header_lane_mode", TelemetryState.isRawHeaderLaneMode());
            JSONObject rawLayout = TelemetryState.getRawHeaderLayout();
            if (rawLayout != null) object.put("raw_header_layout", rawLayout);
            object.put("maneuver", TelemetryState.getManeuver());
            object.put("distance_m", TelemetryState.getDistanceM());
            object.put("road_name", TelemetryState.getRoadName());
            object.put("roundabout_exit", TelemetryState.getRoundaboutExit());
            object.put("roundabout_exits", Math.max(TelemetryState.getRoundaboutExit(), 0));
            object.put("traffic_side", "right");
            object.put("lanes", new JSONArray());
            JSONObject vector = TelemetryState.getArrowVector();
            if (vector != null) {
                String vectorJson = vector.toString();
                object.put("arrow_vector_bytes", vectorJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                if (vectorJson.length() <= 42000) {
                    object.put("arrow_vector", vector);
                } else {
                    object.put("arrow_vector_error", "too_large");
                }
            } else {
                object.put("arrow_vector_error", "none");
            }
            object.put("alert", TelemetryState.getAlertType());
            HudUdpTransport.get(this).sendFrame(object);
        } catch (Exception ignored) { }
    }

    private List<NodeItem> findUnitNodes(List<NodeItem> allNodes) {
        List<NodeItem> nodes = new ArrayList<NodeItem>();
        for (int i = 0; i < allNodes.size(); i++) {
            NodeItem n = allNodes.get(i);
            String t = (n.text + " " + n.desc).toLowerCase();
            if (t.contains("km/h") || t.contains("mph")) {
                nodes.add(n);
            }
        }
        return nodes;
    }

    private String getNearbyText(NodeItem item, List<NodeItem> allNodes, int px) {
        String s = "";
        for (int i = 0; i < allNodes.size(); i++) {
            NodeItem n = allNodes.get(i);
            if (n.index == item.index) {
                continue;
            }
            if (distanceBetween(item.bounds, n.bounds) <= px) {
                s += " " + n.text + " " + n.desc;
            }
        }
        return s;
    }

    private String getSiblingText(NodeItem item, List<NodeItem> allNodes) {
        String s = "";
        for (int i = 0; i < allNodes.size(); i++) {
            NodeItem n = allNodes.get(i);
            if (n.index != item.index && n.parentIndex == item.parentIndex && item.parentIndex >= 0) {
                s += " " + n.text + " " + n.desc;
            }
        }
        return s;
    }

    private NodeItem getParent(NodeItem item, List<NodeItem> allNodes) {
        if (item.parentIndex < 0 || item.parentIndex >= allNodes.size()) {
            return null;
        }
        return allNodes.get(item.parentIndex);
    }

    private int distanceBetween(Rect a, Rect b) {
        int ax = a.centerX();
        int ay = a.centerY();
        int bx = b.centerX();
        int by = b.centerY();
        int dx = ax - bx;
        int dy = ay - by;
        return (int) Math.sqrt((dx * dx) + (dy * dy));
    }

    private void addScore(NodeCandidate c, int value, String reason) {
        c.score += value;
        if (c.scoreLog != null) {
            c.scoreLog.add((value >= 0 ? "+" : "") + value + " " + reason);
        }
    }

    private String buildDebug(NodeCandidate best, List<NodeCandidate> candidates, List<NodeItem> allNodes) {
        String s = "Selected: " + best.value + "  score=" + best.score + "\n";
        s += "Raw: " + best.raw + "\n";
        s += "Bounds: " + best.node.bounds.toShortString() + "\n";
        s += "Class: " + best.node.className + "\n";
        s += "Id: " + best.node.viewId + "\n";
        s += "Nodes: " + allNodes.size() + "  Candidates: " + candidates.size() + "\n";
        s += "Stable pending: " + pendingSpeed + " x" + pendingCount + "\n";
        s += FlirCamUdpTransport.get(this).getStatus() + "\n\n";
        s += "Top candidates:\n";

        int max = Math.min(8, candidates.size());
        for (int i = 0; i < max; i++) {
            NodeCandidate c = candidates.get(i);
            s += (i + 1) + ". " + c.value + " score=" + c.score + " raw=" + shortText(c.raw) + " b=" + c.node.bounds.toShortString() + "\n";
        }

        s += "\nSelected score details:\n";
        int logMax = Math.min(12, best.scoreLog.size());
        for (int i = 0; i < logMax; i++) {
            s += best.scoreLog.get(i) + "\n";
        }
        return s;
    }

    private String buildNoMatchDebug(List<NodeCandidate> candidates, List<NodeItem> allNodes) {
        String s = "No reliable speed-limit candidate.\n";
        s += "Package: " + lastPackage + "\n";
        s += "Nodes: " + allNodes.size() + "  Candidates: " + candidates.size() + "\n";
        s += "Last shown: " + lastShownSpeed + "\n";
        s += FlirCamUdpTransport.get(this).getStatus() + "\n\n";
        Collections.sort(candidates, new Comparator<NodeCandidate>() {
            public int compare(NodeCandidate a, NodeCandidate b) {
                return b.score - a.score;
            }
        });
        int max = Math.min(10, candidates.size());
        for (int i = 0; i < max; i++) {
            NodeCandidate c = candidates.get(i);
            s += (i + 1) + ". " + c.value + " score=" + c.score + " raw=" + shortText(c.raw) + " b=" + c.node.bounds.toShortString() + "\n";
        }
        return s;
    }

    private String shortText(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace("\n", " ").trim();
        if (s.length() > 26) {
            return s.substring(0, 26) + "...";
        }
        return s;
    }

    private static class NodeItem {
        int index;
        int parentIndex;
        int depth;
        int childCount;
        String text;
        String desc;
        String className;
        String viewId;
        Rect bounds;
    }

    private static class NodeCandidate {
        String value;
        String raw;
        String source;
        NodeItem node;
        int score;
        List<String> scoreLog;
    }
}
