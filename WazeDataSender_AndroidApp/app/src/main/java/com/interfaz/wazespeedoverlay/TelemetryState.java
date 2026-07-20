package com.interfaz.wazespeedoverlay;

import org.json.JSONArray;
import org.json.JSONObject;

public final class TelemetryState {
    private static volatile String speedLimit = "";
    private static volatile boolean speedLimitValid = false;
    private static volatile int currentSpeed = -1;
    private static volatile boolean currentSpeedValid = false;
    private static volatile String maneuver = "unknown";
    private static volatile int distanceM = -1;
    private static volatile String roadName = "";
    private static volatile boolean navigationValid = false;
    private static volatile String laneGuidance = "";
    private static volatile int roundaboutExit = 0;
    private static volatile String alertType = "";
    private static volatile JSONArray lanes = new JSONArray();
    private static volatile JSONObject arrowVector = null;
    private static volatile boolean rawHeaderLaneMode = false;
    private static volatile android.graphics.Rect rawDistanceBounds = null;
    private static volatile android.graphics.Rect rawRoadBounds = null;
    private static volatile JSONObject rawHeaderLayout = null;

    private TelemetryState() {}

    public static void setSpeedLimit(String value){
        String clean=value==null?"":value.trim();
        speedLimitValid=clean.matches("[0-9]{1,3}");
        speedLimit=speedLimitValid?clean:"";
    }
    public static String getSpeedLimit(){ return speedLimit; }
    public static boolean isSpeedLimitValid(){ return speedLimitValid; }

    public static void setCurrentSpeed(int value){
        currentSpeedValid=value>=0&&value<=250;
        currentSpeed=currentSpeedValid?value:-1;
    }
    public static int getCurrentSpeed(){ return currentSpeed; }
    public static boolean isCurrentSpeedValid(){ return currentSpeedValid; }

    public static void setManeuver(String value){ maneuver=value==null?"unknown":value; }
    public static String getManeuver(){ return maneuver; }
    public static void setDistanceM(int value){ distanceM=value; }
    public static int getDistanceM(){ return distanceM; }
    public static void setRoadName(String value){ roadName=value==null?"":value; }
    public static String getRoadName(){ return roadName; }
    public static void setNavigationValid(boolean value){ navigationValid=value; }
    public static boolean isNavigationValid(){ return navigationValid; }

    public static void clearNavigation(){
        navigationValid=false;
        maneuver="unknown";
        distanceM=-1;
        roadName="";
        laneGuidance="";
        roundaboutExit=0;
        lanes=new JSONArray();
        arrowVector=null;
        rawHeaderLaneMode=false;
        rawDistanceBounds=null;
        rawRoadBounds=null;
        rawHeaderLayout=null;
    }

    public static void setLaneGuidance(String value){ laneGuidance=value==null?"":value; }
    public static String getLaneGuidance(){ return laneGuidance; }
    public static void setRoundaboutExit(int value){ roundaboutExit=Math.max(0,value); }
    public static int getRoundaboutExit(){ return roundaboutExit; }
    public static void setAlertType(String value){ alertType=value==null?"":value; }
    public static String getAlertType(){ return alertType; }
    public static synchronized void setLanes(JSONArray value){ lanes=value==null?new JSONArray():value; }
    public static synchronized JSONArray getLanes(){ try { return new JSONArray(lanes.toString()); } catch(Exception e){ return new JSONArray(); } }
    public static synchronized void setArrowVector(JSONObject value){ try { arrowVector=value==null?null:new JSONObject(value.toString()); } catch(Exception e){ arrowVector=null; } }
    public static synchronized JSONObject getArrowVector(){ try { return arrowVector==null?null:new JSONObject(arrowVector.toString()); } catch(Exception e){ return null; } }
    public static void setRawHeaderLaneMode(boolean value){ rawHeaderLaneMode=value; }
    public static boolean isRawHeaderLaneMode(){ return rawHeaderLaneMode; }
    public static synchronized void setRawTextBounds(android.graphics.Rect distance, android.graphics.Rect road){
        rawDistanceBounds=distance==null?null:new android.graphics.Rect(distance);
        rawRoadBounds=road==null?null:new android.graphics.Rect(road);
    }
    public static synchronized android.graphics.Rect getRawDistanceBounds(){
        return rawDistanceBounds==null?null:new android.graphics.Rect(rawDistanceBounds);
    }
    public static synchronized android.graphics.Rect getRawRoadBounds(){
        return rawRoadBounds==null?null:new android.graphics.Rect(rawRoadBounds);
    }
    public static synchronized void setRawHeaderLayout(JSONObject value){
        try { rawHeaderLayout=value==null?null:new JSONObject(value.toString()); }
        catch(Exception e){ rawHeaderLayout=null; }
    }
    public static synchronized JSONObject getRawHeaderLayout(){
        try { return rawHeaderLayout==null?null:new JSONObject(rawHeaderLayout.toString()); }
        catch(Exception e){ return null; }
    }
}
