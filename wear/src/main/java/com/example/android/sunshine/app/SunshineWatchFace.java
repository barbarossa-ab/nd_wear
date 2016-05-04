/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.view.animation.PathInterpolatorCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
    implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener
    {
        private static final String WEATHER_UPDATE_PATH = "/weather-update";
        private static final String IMAGE_KEY = "image";
        private static final String MAX_TEMP_KEY = "max-temp";
        private static final String MIN_TEMP_KEY = "min-temp";


        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;

        boolean mFirstUpdateReceived;

        int mImageDimen;
        Bitmap mIcon;
        String mMaxTemp;
        String mMinTemp;

        Paint mTimeTextPaint;
        Paint mMaxTempTextPaint;
        Paint mMinTempTextPaint;

        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        GoogleApiClient mGoogleApiClient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            Log.e("wearable-receive", "onCreate() call...");

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimeTextPaint = new Paint();
            mTimeTextPaint = createTextPaint(resources.getDimension(R.dimen.digital_text_size),
                    resources.getColor(R.color.digital_text), Paint.Align.CENTER);

            mMaxTempTextPaint = new Paint();
            mMaxTempTextPaint = createTextPaint(resources.getDimension(R.dimen.weather_text_size),
                    resources.getColor(R.color.digital_text), Paint.Align.LEFT);

            mMinTempTextPaint = new Paint();
            mMinTempTextPaint = createTextPaint(resources.getDimension(R.dimen.weather_text_size),
                    resources.getColor(R.color.digital_text_faded), Paint.Align.LEFT);

            mTime = new Time();

            mImageDimen = getResources().getDimensionPixelSize(R.dimen.weather_icon_size);

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(float size, int textColor, Paint.Align align) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            paint.setTextSize(size);
            paint.setTextAlign(align);
            return paint;
        }

        private Paint createImagePaint() {
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createLinePaint() {
            Paint paint = new Paint();
            paint.setColor(getResources().getColor(R.color.digital_text_faded));
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            Log.e("wearable-receive", "onVisiblityChanged() call, v = " + visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();

                Log.e("wearable-receive", "calling mGoogleApiClient.connect()");
                mGoogleApiClient.connect();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);

                    Log.e("wearable-receive", "calling mGoogleApiClient.disconnect()");
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }


        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }


        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            Log.e("wearable-receive", "onDraw() call...");

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            float timeHeight = getResources().getDimension(R.dimen.digital_text_size);

            float timeXOffset = (bounds.width() / 2f);
            float timeYOffset = (bounds.height() / 4f) + (timeHeight / 2f);

            mTime.setToNow();
            String timeText = String.format("%d:%02d", mTime.hour, mTime.minute);
            canvas.drawText(timeText, timeXOffset, timeYOffset, mTimeTextPaint);

//            String maxTempText = String.format("%d°", mMaxTemp);
//            String minTempText = String.format("%d°", mMinTemp);

            if(!mFirstUpdateReceived) {
                return;
            }

            int weatherSpacing = getResources().getDimensionPixelSize(R.dimen.weather_spacing);
            int weatherTextSpacing = getResources().getDimensionPixelSize(R.dimen.weather_text_spacing);

            float tempZoneWidth = mMaxTempTextPaint.measureText(mMaxTemp)
                    + mMinTempTextPaint.measureText(mMinTemp)
                    + (isInAmbientMode() ? 0 : (mImageDimen + weatherSpacing))
                    + weatherTextSpacing;

            float maxTempXOffset;
            float maxTempYOffset;
            float minTempXOffset;
            float minTempYOffset;

            int textSize = getResources().getDimensionPixelSize(R.dimen.weather_text_size);

            if (!isInAmbientMode()) {
                // Draw the weather icon
                float iconXOffset = (0.5f * bounds.width()) - (0.5f * tempZoneWidth);
                float iconYOffset = (0.6f * bounds.height()) - (0.5f * mImageDimen);
                canvas.drawBitmap(mIcon, iconXOffset, iconYOffset, createImagePaint());

                // Calculate offsets for weather text
                maxTempXOffset = iconXOffset + mImageDimen + weatherSpacing;
                maxTempYOffset = iconYOffset
                        + (0.5f * mImageDimen)
                        + (0.3f * textSize);

                minTempXOffset = maxTempXOffset + mMaxTempTextPaint.measureText(mMaxTemp) + weatherTextSpacing;
                minTempYOffset = maxTempYOffset;

                // Draw line between texts
//                int lineSize = getResources().getDimensionPixelSize(R.dimen.line_size);
//                canvas.drawLine(
//                        bounds.centerX() - (lineSize / 2),
//                        ((maxTempYOffset - textSize) + timeYOffset) / 2,
//                        bounds.centerX() + (lineSize / 2),
//                        ((maxTempYOffset - textSize) + timeYOffset) / 2,
//                        createLinePaint()
//                );
            } else {
                // Compute offsets
                maxTempXOffset = (0.5f * bounds.width()) - (0.5f * tempZoneWidth);
                maxTempYOffset = 0.6f * bounds.height();
                minTempXOffset = maxTempXOffset + mMaxTempTextPaint.measureText(mMaxTemp) + weatherTextSpacing;
                minTempYOffset = maxTempYOffset;
            }

            canvas.drawText(mMaxTemp, maxTempXOffset, maxTempYOffset, mMaxTempTextPaint);
            canvas.drawText(mMinTemp, minTempXOffset, minTempYOffset, mMinTempTextPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.e("wearable-receive", "mGoogleApiClient connected...");

            Wearable.DataApi.addListener(mGoogleApiClient, this);
            if(!mFirstUpdateReceived) {
                requestUpdate();
            }
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.e("wearable-receive", "mGoogleApiClient connection suspended...");
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.e("wearable-receive", "onDataChanged() call...");

            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(WEATHER_UPDATE_PATH) == 0) {
                        Log.e("wearable-receive", "Found data with path = " + WEATHER_UPDATE_PATH);

                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();

                        mMaxTemp = dataMap.getString(MAX_TEMP_KEY);
                        mMinTemp = dataMap.getString(MIN_TEMP_KEY);

                        Asset imageAsset = dataMap.getAsset(IMAGE_KEY);
//                        mIcon = loadBitmapFromAsset(profileAsset);
                        new LoadBitmapAsyncTask().execute(imageAsset);

                        mFirstUpdateReceived = true;
                        invalidate();
//                        updateCount(dataMap.getInt(COUNT_KEY));
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }
        }

        private void requestUpdate() {
            final String SEND_UPDATE_PATH = "/send-updates";
            final String SEND_UPDATE_MSG = "/send-updates";

            Log.e("wearable-receive", "Requesting updates...");

            new Thread() {
                @Override
                public void run() {
                    if(mGoogleApiClient.isConnected()) {
                        NodeApi.GetConnectedNodesResult nodesList =
                                Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();

                        for(Node node : nodesList.getNodes()) {
                            Log.e("wearable-receive", "Requesting update from " + node.getId());

                            Wearable.MessageApi.sendMessage(
                                    mGoogleApiClient,
                                    node.getId(),
                                    SEND_UPDATE_PATH,
                                    SEND_UPDATE_MSG.getBytes()).await();
                        }
                    }
                }
            }.start();
        }

        private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {
            @Override
            protected Bitmap doInBackground(Asset... params) {

                if (params.length > 0) {

                    Asset asset = params[0];

                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, asset).await().getInputStream();

                    if (assetInputStream == null) {
                        Log.e("wearable-receive", "Requested an unknown Asset.");
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);

                } else {
                    Log.e("wearable-receive", "Asset must be non-null");
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null) {
                    Log.e("wearable-receive", "Setting forecast image..");
                    mIcon = Bitmap.createScaledBitmap( bitmap, mImageDimen, mImageDimen, true);
                    bitmap.recycle();
                    invalidate();
                }
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.e("wearable-receive", "mGoogleApiClient connection failed...");
            Wearable.DataApi.removeListener(mGoogleApiClient, this);
        }
    }
}
