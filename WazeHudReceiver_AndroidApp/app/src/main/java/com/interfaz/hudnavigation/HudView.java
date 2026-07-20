package com.interfaz.hudnavigation;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class HudView extends View {
    private JSONObject frame;
    private boolean mirrored;
    private String diagnostics="Waiting for Waze sender";
    private String connectedSender="";
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thin=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float dotX,dotY,dotR;
    private static final long STABILITY_MS=1000L;
    private static final long FADE_MS=1000L;

    private static final int HEADER_EMPTY=0;
    private static final int HEADER_STABLE=1;
    private static final int HEADER_FADE_OUT=2;
    private static final int HEADER_FADE_IN=3;

    private Bitmap displayedHeaderBitmap;
    private Bitmap pendingHeaderBitmap;
    private Bitmap nextHeaderBitmap;

    private byte[] displayedHeaderSignature;
    private byte[] pendingHeaderSignature;

    private JSONObject displayedHeaderFrame;
    private JSONObject pendingHeaderFrame;
    private JSONObject nextHeaderFrame;

    private long pendingHeaderSince;
    private long headerAnimationStarted;
    private int headerState=HEADER_EMPTY;
    private float headerAlpha=0f;

    private float speedAlpha=0f;
    private float speedAlphaFrom=0f;
    private float speedAlphaTarget=0f;
    private long speedAlphaStarted;

    private float speedLimitAlpha=0f;
    private float speedLimitAlphaFrom=0f;
    private float speedLimitAlphaTarget=0f;
    private long speedLimitAlphaStarted;

    private float waitingAlpha=1f;
    private float waitingAlphaFrom=1f;
    private float waitingAlphaTarget=1f;
    private long waitingAlphaStarted;

    public HudView(Context context){
        super(context); setBackgroundColor(Color.BLACK);
        text.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
        thin.setStyle(Paint.Style.STROKE); thin.setStrokeCap(Paint.Cap.ROUND); thin.setStrokeJoin(Paint.Join.ROUND);
    }
    public void toggleMirror(){ mirrored=!mirrored; invalidate(); }
    public void postFrame(final JSONObject value){
        post(new Runnable(){
            public void run(){
                frame=value;

                boolean navigationValid=value!=null&&value.optBoolean("navigation_valid",false);
                boolean currentSpeedValid=value!=null&&value.optBoolean(
                    "current_speed_valid",
                    value.optInt("current_speed",-1)>=0
                );
                boolean speedLimitValid=value!=null&&value.optBoolean(
                    "speed_limit_valid",
                    isNumericLimit(value.optString("speed_limit",""))
                );
                boolean alertValid=value!=null&&value.optString("alert","").length()>0;

                animateSpeedVisibility(currentSpeedValid);
                animateSpeedLimitVisibility(speedLimitValid||alertValid);
                animateWaitingVisibility(
                    !navigationValid&&!currentSpeedValid&&!speedLimitValid&&!alertValid
                );

                if(!navigationValid&&displayedHeaderBitmap!=null&&headerState==HEADER_STABLE){
                    nextHeaderBitmap=null;
                    nextHeaderFrame=null;
                    headerState=HEADER_FADE_OUT;
                    headerAnimationStarted=System.currentTimeMillis();
                }

                invalidate();
            }
        });
    }
    public void postHeaderBitmap(final Bitmap value){
        if(value==null||value.isRecycled())return;

        post(new Runnable(){
            public void run(){
                long now=System.currentTimeMillis();
                JSONObject candidateFrame=copyFrame(frame);
                byte[] signature=buildHeaderSignature(value);

                if(
                    displayedHeaderBitmap!=null
                    &&headersEquivalent(
                        signature,
                        candidateFrame,
                        displayedHeaderSignature,
                        displayedHeaderFrame
                    )
                ){
                    recycleBitmap(value);
                    clearPendingHeader();

                    // Distance is intentionally read from the latest live frame,
                    // but keep other metadata synchronized when the same header
                    // is still displayed.
                    if(displayedHeaderFrame!=null&&candidateFrame!=null){
                        try{
                            displayedHeaderFrame.put(
                                "raw_header_layout",
                                candidateFrame.optJSONObject("raw_header_layout")
                            );
                        }catch(Exception ignored){
                        }
                    }

                    invalidate();
                    return;
                }

                if(
                    pendingHeaderBitmap==null
                    ||!headersEquivalent(
                        signature,
                        candidateFrame,
                        pendingHeaderSignature,
                        pendingHeaderFrame
                    )
                ){
                    clearPendingHeader();
                    pendingHeaderBitmap=value;
                    pendingHeaderSignature=signature;
                    pendingHeaderFrame=candidateFrame;
                    pendingHeaderSince=now;
                    invalidate();
                    return;
                }

                Bitmap previousPending=pendingHeaderBitmap;
                pendingHeaderBitmap=value;
                pendingHeaderSignature=signature;
                pendingHeaderFrame=candidateFrame;
                recycleBitmap(previousPending);

                if(now-pendingHeaderSince>=STABILITY_MS){
                    if(displayedHeaderBitmap==null||headerState==HEADER_EMPTY){
                        displayedHeaderBitmap=pendingHeaderBitmap;
                        displayedHeaderSignature=pendingHeaderSignature;
                        displayedHeaderFrame=pendingHeaderFrame;

                        pendingHeaderBitmap=null;
                        pendingHeaderSignature=null;
                        pendingHeaderFrame=null;
                        pendingHeaderSince=0L;

                        headerState=HEADER_FADE_IN;
                        headerAnimationStarted=now;
                        headerAlpha=0f;
                    }else if(headerState==HEADER_STABLE){
                        recycleBitmap(nextHeaderBitmap);
                        nextHeaderBitmap=pendingHeaderBitmap;
                        nextHeaderFrame=pendingHeaderFrame;

                        pendingHeaderBitmap=null;
                        pendingHeaderSignature=null;
                        pendingHeaderFrame=null;
                        pendingHeaderSince=0L;

                        headerState=HEADER_FADE_OUT;
                        headerAnimationStarted=now;
                    }else if(headerState==HEADER_FADE_OUT){
                        recycleBitmap(nextHeaderBitmap);
                        nextHeaderBitmap=pendingHeaderBitmap;
                        nextHeaderFrame=pendingHeaderFrame;

                        pendingHeaderBitmap=null;
                        pendingHeaderSignature=null;
                        pendingHeaderFrame=null;
                        pendingHeaderSince=0L;
                    }
                }

                invalidate();
            }
        });
    }

    public void postDiagnostics(final String value){ post(new Runnable(){ public void run(){ diagnostics=value; invalidate(); }}); }
    public void postConnected(final String value){ post(new Runnable(){ public void run(){ connectedSender=value; invalidate(); }}); }

    protected void onDraw(Canvas c){
        super.onDraw(c);
        c.drawColor(Color.BLACK);

        int w=getWidth();
        int h=getHeight();
        long now=System.currentTimeMillis();

        updateHeaderAnimation(now);
        updateVisibilityAnimations(now);

        c.save();
        if(mirrored)c.scale(-1,1,w/2f,h/2f);

        if(frame!=null){
            drawSemantic(c,w,h);
        }

        if(waitingAlpha>0.001f){
            int layer=c.saveLayerAlpha(0,0,w,h,Math.round(waitingAlpha*255f));
            drawNoData(c,w,h);
            c.restoreToCount(layer);
        }

        drawMirrorDot(c,w,h);
        c.restore();

        if(
            headerState==HEADER_FADE_OUT
            ||headerState==HEADER_FADE_IN
            ||speedAlpha!=speedAlphaTarget
            ||speedLimitAlpha!=speedLimitAlphaTarget
            ||waitingAlpha!=waitingAlphaTarget
        ){
            postInvalidateDelayed(16L);
        }
    }

    private void drawSemantic(Canvas c,int w,int h){
        boolean navigationValid=frame.optBoolean("navigation_valid",false);
        boolean currentSpeedValid=frame.optBoolean("current_speed_valid",frame.optInt("current_speed",-1)>=0);
        boolean speedLimitValid=frame.optBoolean("speed_limit_valid",isNumericLimit(frame.optString("speed_limit","")));
        String alert=frame.optString("alert","");

        if(navigationValid){
            boolean rawHeaderSupported=frame.optBoolean("raw_header_supported",false);

            if(rawHeaderSupported&&displayedHeaderBitmap!=null&&headerAlpha>0.001f){
                int layer=c.saveLayerAlpha(0,0,w,h,Math.round(headerAlpha*255f));
                drawRawHeader(c,w,h,displayedHeaderBitmap);

                JSONObject roadFrame=displayedHeaderFrame==null?frame:displayedHeaderFrame;
                drawRawHeaderText(c,w,h,frame,roadFrame);

                c.restoreToCount(layer);
            }

            String maneuver=frame.optString("maneuver","unknown");
            int distance=frame.optInt("distance_m",-1);
            String road=frame.optString("road_name","");
            JSONObject arrowVector=frame.optJSONObject("arrow_vector");
            boolean hasVector=arrowVector!=null&&arrowVector.optInt("width",0)>0;
            boolean vectorLanes=hasVector&&arrowVector.optBoolean("lane_mode",false);
            float headerBottom=vectorLanes?h*0.43f:h*0.60f;

            boolean vectorSupported=frame.optBoolean("arrow_vector_supported",false);

            if(rawHeaderSupported&&displayedHeaderBitmap!=null){
                // Raw cleaned header already contains every Waze arrow and lane separator.
            }else if(hasVector){
                drawArrowVector(c,w,h,arrowVector);
            }else if(!vectorSupported&&"roundabout".equals(maneuver)){
                drawRoundabout(c,w*0.18f,h*0.27f,Math.min(w,h)*0.16f);
            }else if(!vectorSupported&&!"unknown".equals(maneuver)&&maneuver.length()>0){
                drawManeuver(c,maneuver,w*0.18f,h*0.32f,1.72f,Color.WHITE);
            }

            if(!rawHeaderSupported||displayedHeaderBitmap==null){
                text.setColor(Color.WHITE);
                text.setTextAlign(Paint.Align.LEFT);
                text.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
                text.setTextSize(Math.max(30,h*0.091f));
                c.drawText(formatDistance(distance),vectorLanes?w*0.06f:w*0.34f,vectorLanes?headerBottom+h*0.10f:h*0.23f,text);

                text.setColor(Color.rgb(46,185,255));
                float roadX=vectorLanes?w*0.06f:w*0.34f;
                float roadY=vectorLanes?headerBottom+h*0.21f:h*0.42f;
                float roadSize=Math.max(27,h*0.078f);
                float maxRoadWidth=w-roadX-Math.max(30f,w*0.05f);
                drawFittedText(c,road,roadX,roadY,maxRoadWidth,roadSize,Math.max(17f,h*0.040f));
            }
        }

        if(currentSpeedValid&&speedAlpha>0.001f){
            int layer=c.saveLayerAlpha(0,0,w,h,Math.round(speedAlpha*255f));
            drawCurrentSpeed(c,w,h,frame.optInt("current_speed",-1));
            c.restoreToCount(layer);
        }

        if((speedLimitValid||alert.length()>0)&&speedLimitAlpha>0.001f){
            int layer=c.saveLayerAlpha(0,0,w,h,Math.round(speedLimitAlpha*255f));
            drawSpeedAndAlert(
                c,
                w,
                h,
                speedLimitValid?frame.optString("speed_limit",""):"",
                alert,
                speedLimitValid
            );
            c.restoreToCount(layer);
        }
    }

    private void animateSpeedVisibility(boolean visible){
        float target=visible?1f:0f;
        if(speedAlphaTarget==target)return;

        speedAlphaFrom=speedAlpha;
        speedAlphaTarget=target;
        speedAlphaStarted=System.currentTimeMillis();
    }

    private void animateSpeedLimitVisibility(boolean visible){
        float target=visible?1f:0f;
        if(speedLimitAlphaTarget==target)return;

        speedLimitAlphaFrom=speedLimitAlpha;
        speedLimitAlphaTarget=target;
        speedLimitAlphaStarted=System.currentTimeMillis();
    }

    private void animateWaitingVisibility(boolean visible){
        float target=visible?1f:0f;
        if(waitingAlphaTarget==target)return;

        waitingAlphaFrom=waitingAlpha;
        waitingAlphaTarget=target;
        waitingAlphaStarted=System.currentTimeMillis();
    }

    private void updateVisibilityAnimations(long now){
        speedAlpha=interpolateAlpha(
            speedAlphaFrom,
            speedAlphaTarget,
            speedAlphaStarted,
            now
        );

        speedLimitAlpha=interpolateAlpha(
            speedLimitAlphaFrom,
            speedLimitAlphaTarget,
            speedLimitAlphaStarted,
            now
        );

        waitingAlpha=interpolateAlpha(
            waitingAlphaFrom,
            waitingAlphaTarget,
            waitingAlphaStarted,
            now
        );
    }

    private float interpolateAlpha(
        float from,
        float target,
        long started,
        long now
    ){
        if(from==target)return target;
        if(started<=0L)return target;

        float progress=Math.min(1f,(now-started)/(float)FADE_MS);
        return from+(target-from)*progress;
    }

    private void updateHeaderAnimation(long now){
        if(headerState==HEADER_FADE_OUT){
            float progress=Math.min(1f,(now-headerAnimationStarted)/(float)FADE_MS);
            headerAlpha=1f-progress;

            if(progress>=1f){
                recycleBitmap(displayedHeaderBitmap);
                displayedHeaderBitmap=null;
                displayedHeaderSignature=null;
                displayedHeaderFrame=null;

                if(nextHeaderBitmap!=null){
                    displayedHeaderBitmap=nextHeaderBitmap;
                    nextHeaderBitmap=null;
                    displayedHeaderSignature=buildHeaderSignature(displayedHeaderBitmap);
                    displayedHeaderFrame=nextHeaderFrame;
                    nextHeaderFrame=null;
                    headerState=HEADER_FADE_IN;
                    headerAnimationStarted=now;
                    headerAlpha=0f;
                }else{
                    headerState=HEADER_EMPTY;
                    headerAlpha=0f;
                }
            }
        }else if(headerState==HEADER_FADE_IN){
            float progress=Math.min(1f,(now-headerAnimationStarted)/(float)FADE_MS);
            headerAlpha=progress;

            if(progress>=1f){
                headerState=HEADER_STABLE;
                headerAlpha=1f;
            }
        }else if(headerState==HEADER_STABLE){
            headerAlpha=1f;
        }else{
            headerAlpha=0f;
        }
    }

    private JSONObject copyFrame(JSONObject source){
        if(source==null)return null;

        try{
            return new JSONObject(source.toString());
        }catch(Exception ignored){
            return source;
        }
    }

    private void clearPendingHeader(){
        recycleBitmap(pendingHeaderBitmap);
        pendingHeaderBitmap=null;
        pendingHeaderSignature=null;
        pendingHeaderFrame=null;
        pendingHeaderSince=0L;
    }

    private void recycleBitmap(Bitmap bitmap){
        if(bitmap!=null&&!bitmap.isRecycled()){
            bitmap.recycle();
        }
    }

    private byte[] buildHeaderSignature(Bitmap bitmap){
        if(bitmap==null||bitmap.isRecycled())return null;

        final int sampleWidth=48;
        final int sampleHeight=18;
        byte[] result=new byte[sampleWidth*sampleHeight];

        for(int sy=0;sy<sampleHeight;sy++){
            int y=Math.min(
                bitmap.getHeight()-1,
                Math.round((sy+.5f)*bitmap.getHeight()/sampleHeight)
            );

            for(int sx=0;sx<sampleWidth;sx++){
                int x=Math.min(
                    bitmap.getWidth()-1,
                    Math.round((sx+.5f)*bitmap.getWidth()/sampleWidth)
                );

                int color=bitmap.getPixel(x,y);
                int r=Color.red(color);
                int g=Color.green(color);
                int b=Color.blue(color);
                int luminance=(r*30+g*59+b*11)/100;

                result[sy*sampleWidth+sx]=(byte)luminance;
            }
        }

        return result;
    }

    private boolean headersEquivalent(
        byte[] firstSignature,
        JSONObject firstFrame,
        byte[] secondSignature,
        JSONObject secondFrame
    ){
        if(firstSignature==null||secondSignature==null)return false;
        if(firstSignature.length!=secondSignature.length)return false;

        String firstRoad=stableRoadName(firstFrame);
        String secondRoad=stableRoadName(secondFrame);

        // The original Waze road text is masked from the transmitted bitmap.
        // Therefore a changed road name must explicitly count as a new header.
        if(!firstRoad.equals(secondRoad))return false;

        long totalDifference=0L;
        int stronglyChanged=0;

        for(int i=0;i<firstSignature.length;i++){
            int a=firstSignature[i]&0xff;
            int b=secondSignature[i]&0xff;
            int difference=Math.abs(a-b);

            totalDifference+=difference;
            if(difference>=42)stronglyChanged++;
        }

        float meanDifference=totalDifference/(float)firstSignature.length;
        float changedRatio=stronglyChanged/(float)firstSignature.length;

        // Tolerate small MediaProjection/JPEG/PNG edge fluctuations while still
        // detecting a changed arrow, roundabout, or lane arrangement.
        return meanDifference<=5.5f&&changedRatio<=0.025f;
    }

    private String stableRoadName(JSONObject source){
        if(source==null)return "";

        String value=source.optString("road_name","");
        return value==null?"":value.trim();
    }

    private void drawRawHeader(Canvas canvas,int width,int height,Bitmap bitmap){
        if(bitmap==null)return;

        float maxWidth=width;
        float maxHeight=height*.48f;
        float scale=Math.min(maxWidth/bitmap.getWidth(),maxHeight/bitmap.getHeight());
        float drawWidth=bitmap.getWidth()*scale;
        float drawHeight=bitmap.getHeight()*scale;
        float left=(width-drawWidth)/2f;
        float top=0f;

        RectF destination=new RectF(left,top,left+drawWidth,top+drawHeight);
        p.setFilterBitmap(true);
        canvas.drawBitmap(bitmap,null,destination,p);
    }

    private void drawRawHeaderText(Canvas canvas,int width,int height,JSONObject latestFrame,JSONObject roadFrame){
        if(displayedHeaderBitmap==null)return;

        JSONObject layout=roadFrame.optJSONObject("raw_header_layout");
        if(layout==null)return;

        float maxWidth=width;
        float maxHeight=height*.48f;
        float scale=Math.min(maxWidth/displayedHeaderBitmap.getWidth(),maxHeight/displayedHeaderBitmap.getHeight());
        float drawWidth=displayedHeaderBitmap.getWidth()*scale;
        float drawHeight=displayedHeaderBitmap.getHeight()*scale;
        float imageLeft=(width-drawWidth)/2f;
        float imageTop=0f;

        text.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
        text.setTextAlign(Paint.Align.LEFT);

        JSONObject distanceRect=layout.optJSONObject("distance");
        if(distanceRect!=null){
            RectF box=mapNormalizedRect(distanceRect,imageLeft,imageTop,drawWidth,drawHeight);
            String distance=formatDistance(latestFrame.optInt("distance_m",-1));

            text.setColor(Color.WHITE);
            drawTextInsideBox(
                canvas,
                distance,
                box,
                Math.max(24f,box.height()*.92f),
                Math.max(16f,box.height()*.55f)
            );
        }

        JSONObject roadRect=layout.optJSONObject("road");
        if(roadRect!=null){
            RectF box=mapNormalizedRect(roadRect,imageLeft,imageTop,drawWidth,drawHeight);
            String road=roadFrame.optString("road_name","");

            text.setColor(Color.rgb(46,185,255));
            drawTextInsideBox(
                canvas,
                road,
                box,
                Math.max(23f,box.height()*.90f),
                Math.max(16f,box.height()*.50f)
            );
        }
    }

    private RectF mapNormalizedRect(
        JSONObject rect,
        float left,
        float top,
        float width,
        float height
    ){
        return new RectF(
            left+(float)rect.optDouble("left",0.0)*width,
            top+(float)rect.optDouble("top",0.0)*height,
            left+(float)rect.optDouble("right",0.0)*width,
            top+(float)rect.optDouble("bottom",0.0)*height
        );
    }

    private void drawTextInsideBox(
        Canvas canvas,
        String value,
        RectF box,
        float preferredSize,
        float minimumSize
    ){
        if(value==null||value.length()==0)return;

        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(preferredSize);

        float available=Math.max(1f,box.width());
        while(text.getTextSize()>minimumSize&&text.measureText(value)>available){
            text.setTextSize(text.getTextSize()-1f);
        }

        Paint.FontMetrics metrics=text.getFontMetrics();
        float baseline=box.centerY()-(metrics.ascent+metrics.descent)/2f;
        canvas.drawText(value,box.left,baseline,text);
    }

    private void drawArrowVector(Canvas c,int w,int h,JSONObject vector){
        int vw=vector.optInt("width",0),vh=vector.optInt("height",0);
        if(vw<=0||vh<=0)return;

        boolean lanes=vector.optBoolean("lane_mode",false);
        float maxW=lanes?w*.90f:w*.34f;
        float maxH=lanes?h*.27f:h*.34f;
        float scale=Math.min(maxW/vw,maxH/vh);
        float dw=vw*scale,dh=vh*scale;
        float left=lanes?(w-dw)/2f:w*.18f-dw/2f;
        float top=Math.max(h*.025f,lanes?h*.035f:h*.050f);

        String encoding=vector.optString("encoding","");
        if("row_runs".equals(encoding)){
            drawVectorRuns(c,vector.optJSONArray("grey_runs"),left,top,scale,Color.rgb(105,108,114));
            drawVectorRuns(c,vector.optJSONArray("white_runs"),left,top,scale,Color.WHITE);
        }else{
            drawVectorRects(c,vector.optJSONArray("grey"),left,top,scale,Color.rgb(105,108,114));
            drawVectorRects(c,vector.optJSONArray("white"),left,top,scale,Color.WHITE);
        }
    }

    private void drawVectorRuns(Canvas c,JSONArray data,float left,float top,float scale,int color){
        if(data==null)return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setAntiAlias(false);

        for(int i=0;i+2<data.length();i+=3){
            float y=top+data.optInt(i)*scale;
            float x=left+data.optInt(i+1)*scale;
            float rw=Math.max(1f,data.optInt(i+2)*scale);
            float rh=Math.max(1f,scale);
            c.drawRect(x-.30f,y-.30f,x+rw+.30f,y+rh+.30f,p);
        }

        p.setAntiAlias(true);
    }

    private void drawVectorRects(Canvas c,JSONArray data,float left,float top,float scale,int color){
        if(data==null)return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        p.setAntiAlias(false);

        for(int i=0;i+3<data.length();i+=4){
            float x=left+data.optInt(i)*scale,y=top+data.optInt(i+1)*scale;
            float rw=Math.max(1f,data.optInt(i+2)*scale),rh=Math.max(1f,data.optInt(i+3)*scale);
            c.drawRect(x-.35f,y-.35f,x+rw+.35f,y+rh+.35f,p);
        }

        p.setAntiAlias(true);
    }

    private void drawFittedText(Canvas c,String value,float x,float y,float maxWidth,float preferredSize,float minimumSize){
        if(value==null)value="";
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(preferredSize);

        while(text.getTextSize()>minimumSize&&text.measureText(value)>maxWidth){
            text.setTextSize(text.getTextSize()-1f);
        }

        if(text.measureText(value)<=maxWidth){
            c.drawText(value,x,y,text);
            return;
        }

        String ellipsis="…";
        String fitted=value;
        while(fitted.length()>0&&text.measureText(fitted+ellipsis)>maxWidth){
            fitted=fitted.substring(0,fitted.length()-1);
        }
        c.drawText(fitted+ellipsis,x,y,text);
    }

    private String formatDistance(int m){ if(m<0)return ""; if(m>=1000)return String.format(java.util.Locale.US,"%.1f km",m/1000f); return m+" m"; }

    private void drawManeuver(Canvas c,String type,float x,float y,float scale,int color){
        float sw=Math.max(4.5f,getHeight()*0.009f)*scale;
        float hs=Math.max(20f,getHeight()*0.032f)*scale;
        thin.setColor(color); thin.setStrokeWidth(sw);
        Path path=new Path(); float tx=x,ty=y-hs*3;

        if("turn_right".equals(type)||"sharp_right".equals(type)||"exit_right".equals(type)){
            path.moveTo(x,y+78*scale); path.lineTo(x,y+22*scale);
            path.cubicTo(x,y-18*scale,x+27*scale,y-42*scale,x+62*scale,y-42*scale);
            tx=x+105*scale;ty=y-42*scale; path.lineTo(tx-hs*.72f,ty);
            drawPathArrow(c,path,tx,ty,0,hs,color);
        }else if("turn_left".equals(type)||"sharp_left".equals(type)||"exit_left".equals(type)){
            path.moveTo(x,y+78*scale); path.lineTo(x,y+22*scale);
            path.cubicTo(x,y-18*scale,x-27*scale,y-42*scale,x-62*scale,y-42*scale);
            tx=x-105*scale;ty=y-42*scale; path.lineTo(tx+hs*.72f,ty);
            drawPathArrow(c,path,tx,ty,180,hs,color);
        }else if(type.startsWith("uturn")){
            path.moveTo(x+12*scale,y+78*scale);path.lineTo(x+12*scale,y-14*scale);
            path.cubicTo(x+12*scale,y-55*scale,x-45*scale,y-55*scale,x-45*scale,y-8*scale-hs*.72f);
            tx=x-45*scale;ty=y-8*scale;drawPathArrow(c,path,tx,ty,90,hs,color);
        }else if("continue_straight".equals(type)){
            path.moveTo(x,y+78*scale);path.lineTo(x,y-78*scale+hs*.72f);
            drawPathArrow(c,path,x,y-78*scale,-90,hs,color);
        }else if("keep_left".equals(type)||"slight_left".equals(type)){
            path.moveTo(x,y+55*scale);path.lineTo(x,y);tx=x-50*scale;ty=y-52*scale;
            path.lineTo(tx+hs*.51f,ty+hs*.51f);drawPathArrow(c,path,tx,ty,-135,hs,color);
        }else if("keep_right".equals(type)||"slight_right".equals(type)){
            path.moveTo(x,y+55*scale);path.lineTo(x,y);tx=x+50*scale;ty=y-52*scale;
            path.lineTo(tx-hs*.51f,ty+hs*.51f);drawPathArrow(c,path,tx,ty,-45,hs,color);
        }
    }

    private void drawPathArrow(Canvas c,Path path,float tx,float ty,float angle,float size,int color){ thin.setColor(color);c.drawPath(path,thin); p.setColor(color);p.setStyle(Paint.Style.FILL);Path head=new Path();double r=Math.toRadians(angle);float bx=(float)Math.cos(r),by=(float)Math.sin(r),px=-by,py=bx;head.moveTo(tx,ty);head.lineTo(tx-bx*size+px*size*.62f,ty-by*size+py*size*.62f);head.lineTo(tx-bx*size-px*size*.62f,ty-by*size-py*size*.62f);head.close();c.drawPath(head,p); }

    private void drawLanes(Canvas c,int w,float bottom,JSONArray lanes){ int n=Math.min(8,lanes.length());float lw=w/(float)n;for(int i=0;i<n;i++){if(i>0){p.setColor(Color.DKGRAY);p.setStrokeWidth(2);c.drawLine(i*lw,10,i*lw,bottom-8,p);}JSONObject lane=lanes.optJSONObject(i);JSONArray dirs=lane==null?null:lane.optJSONArray("directions");JSONArray selected=lane==null?null:lane.optJSONArray("selected");if(dirs==null)continue;float x=i*lw+lw/2,y=bottom*.56f;for(int j=0;j<dirs.length();j++){String d=dirs.optString(j);int col=contains(selected,d)?Color.WHITE:Color.rgb(105,108,114);drawManeuver(c,d,x,y,.48f,col);}} }
    private boolean contains(JSONArray a,String v){if(a==null)return false;for(int i=0;i<a.length();i++)if(v.equals(a.optString(i)))return true;return false;}

    private void drawRoundabout(Canvas c,float cx,float cy,float r){ int exits=Math.max(1,frame.optInt("roundabout_exits",frame.optInt("roundabout_exit",3)));int sel=Math.max(1,frame.optInt("roundabout_exit",1));boolean cw="left".equals(frame.optString("traffic_side","right"));float dir=cw?1:-1,sw=Math.max(7,getHeight()*.017f);thin.setStrokeWidth(sw);thin.setColor(Color.rgb(105,108,114));c.drawCircle(cx,cy,r,thin);for(int i=1;i<=exits;i++){float a=90+dir*(360f/exits)*i;if(i==sel)continue;drawRadial(c,cx,cy,r,a,Color.rgb(105,108,114),sw*.7f,true);} thin.setColor(Color.WHITE);c.drawLine(cx,cy+r+55,cx,cy+r,thin);RectF oval=new RectF(cx-r,cy-r,cx+r,cy+r);float sweep=dir*(360f/exits)*sel;c.drawArc(oval,90,sweep,false,thin);float a=90+dir*(360f/exits)*sel;drawRadial(c,cx,cy,r,a,Color.WHITE,sw,true); }
    private void drawRadial(Canvas c,float cx,float cy,float r,float angle,int color,float sw,boolean head){double a=Math.toRadians(angle);float x1=cx+(float)Math.cos(a)*r,y1=cy+(float)Math.sin(a)*r,x2=cx+(float)Math.cos(a)*(r+55),y2=cy+(float)Math.sin(a)*(r+55);thin.setColor(color);thin.setStrokeWidth(sw);c.drawLine(x1,y1,x2,y2,thin);if(head){Path pth=new Path();float hs=sw*2;float bx=(float)Math.cos(a),by=(float)Math.sin(a),px=-by,py=bx;pth.moveTo(x2,y2);pth.lineTo(x2-bx*hs+px*hs*.62f,y2-by*hs+py*hs*.62f);pth.lineTo(x2-bx*hs-px*hs*.62f,y2-by*hs-py*hs*.62f);pth.close();p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawPath(pth,p);}}

    private void drawCurrentSpeed(Canvas c,int w,int h,int speed){
        if(speed<0)return;
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
        text.setTextAlign(Paint.Align.LEFT);

        float x=w*.07f,b=h*.88f;
        String s=String.valueOf(speed);
        float numberSize=Math.max(38,h*.18f);
        text.setTextSize(numberSize);
        float numberWidth=text.measureText(s);
        c.drawText(s,x,b,text);

        text.setTextSize(Math.max(22,h*.068f));
        float gap=Math.max(24f,w*.027f);
        c.drawText("KM/h",x+numberWidth+gap,b,text);
    }
    private void drawSpeedAndAlert(Canvas c,int w,int h,String limit,String alert,boolean showLimit){
        float r=Math.min(w,h)*.088f;
        boolean hasAlert=alert!=null&&alert.length()>0;
        float cy=h*.80f;

        if(showLimit){
            float cx=hasAlert?w*.72f:w*.82f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r*.14f);
            p.setColor(Color.RED);
            c.drawCircle(cx,cy,r,p);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(r*.85f);
            Paint.FontMetrics fm=text.getFontMetrics();
            c.drawText(limit,cx,cy-(fm.ascent+fm.descent)/2,text);
        }

        if(hasAlert){
            float ax=showLimit?w*.88f:w*.82f;
            float ar=r*.72f;
            p.setColor(Color.WHITE);
            p.setStrokeWidth(ar*.08f);
            p.setStyle(Paint.Style.STROKE);
            c.drawCircle(ax,cy,ar,p);
            Drawable d=alertDrawable(alert);
            if(d!=null){
                int rr=(int)(ar*.72f);
                d.setBounds((int)ax-rr,(int)cy-rr,(int)ax+rr,(int)cy+rr);
                d.draw(c);
            }
        }
        p.setStyle(Paint.Style.FILL);
    }
    private boolean isNumericLimit(String value){ return value!=null&&value.matches("[0-9]{1,3}"); }
    private void drawNoData(Canvas c,int w,int h){
        text.setColor(Color.rgb(175,175,175));
        text.setTypeface(Typeface.create("sans-serif-light",Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Math.max(15f,h*.032f));
        c.drawText("Waiting for Waze data",w/2f,h*.94f,text);
    }
    private Drawable alertDrawable(String a){int id=0;if("police".equals(a))id=R.drawable.alert_police;else if("speed_camera".equals(a))id=R.drawable.alert_speed_camera;else if("red_light_camera".equals(a))id=R.drawable.alert_red_light_camera;else if("accident".equals(a))id=R.drawable.alert_accident;else if("traffic".equals(a))id=R.drawable.alert_traffic;else if("construction".equals(a))id=R.drawable.alert_construction;else if("pothole".equals(a))id=R.drawable.alert_pothole;else if("closure".equals(a))id=R.drawable.alert_closure;else if("hazard".equals(a))id=R.drawable.alert_hazard;return id==0?null:getResources().getDrawable(id);}
    private void drawWaiting(Canvas c,int w,int h){ drawNoData(c,w,h); }
    private void drawMirrorDot(Canvas c,int w,int h){float xdpi=getResources().getDisplayMetrics().xdpi;dotR=Math.max(9f,((.5f/2.54f)*xdpi)/2f);dotX=w-dotR*1.8f;dotY=dotR*1.8f;p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);c.drawCircle(dotX,dotY,dotR,p);}
    public boolean onTouchEvent(MotionEvent e){if(e.getAction()!=MotionEvent.ACTION_UP)return true;float ax=mirrored?getWidth()-dotX:dotX,dx=e.getX()-ax,dy=e.getY()-dotY;if(dx*dx+dy*dy<=dotR*dotR*4)toggleMirror();return true;}
}
