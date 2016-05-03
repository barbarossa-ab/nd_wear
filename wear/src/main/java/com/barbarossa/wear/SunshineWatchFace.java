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

package com.barbarossa.wear;

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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.view.animation.PathInterpolatorCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

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

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;

        int mImageDimen;
        Bitmap mIcon;
        int mMaxTemp = 19;
        int mMinTemp = 16;

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

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

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

            mIcon = getImage(R.drawable.art_clear, mImageDimen, mImageDimen);
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

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
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

        public Bitmap getImage (int id, int width, int height) {
            Bitmap bmp = BitmapFactory.decodeResource( getResources(), id );
            Bitmap img = Bitmap.createScaledBitmap( bmp, width, height, true );
            bmp.recycle();
            return img;
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
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

            String maxTempText = String.format("%d°", mMaxTemp);
            String minTempText = String.format("%d°", mMinTemp);

            int weatherSpacing = getResources().getDimensionPixelSize(R.dimen.weather_spacing);
            int weatherTextSpacing = getResources().getDimensionPixelSize(R.dimen.weather_text_spacing);

            float tempZoneWidth = mMaxTempTextPaint.measureText(maxTempText)
                    + mMinTempTextPaint.measureText(minTempText)
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

                minTempXOffset = maxTempXOffset + mMaxTempTextPaint.measureText(maxTempText) + weatherTextSpacing;
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
                minTempXOffset = maxTempXOffset + mMaxTempTextPaint.measureText(maxTempText) + weatherTextSpacing;
                minTempYOffset = maxTempYOffset;
            }

            canvas.drawText(maxTempText, maxTempXOffset, maxTempYOffset, mMaxTempTextPaint);
            canvas.drawText(minTempText, minTempXOffset, minTempYOffset, mMinTempTextPaint);
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

    }
}
