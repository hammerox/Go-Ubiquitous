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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WatchFaceService extends CanvasWatchFaceService {
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
        private final WeakReference<WatchFaceService.Engine> mWeakReference;

        public EngineHandler(WatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchFaceService.Engine engine = mWeakReference.get();
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
            GoogleApiClient.OnConnectionFailedListener {

        public static final String TAG = "WatchFaceService.Engine";

        public static final String WEARABLE_UPDATE_PATH = "/wearable_update";
        public static final String WEARABLE_REQUEST_PATH = "/wearable_request";
        public static final String WEARABLE_WEATHER_ID = "wearable_weather_id";
        public static final String WEARABLE_HIGH = "wearable_high";
        public static final String WEARABLE_LOW = "wearable_low";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Bitmap mWeatherIcon;
        Paint mHighPaint;
        Paint mLowPaint;

        String mHighText;
        String mLowText;

        boolean mAmbient;
        Time mTime;
        Calendar calendar;
        SimpleDateFormat mTimeAmbientFormat = new SimpleDateFormat("hh:mm");
        SimpleDateFormat mTimeFormat = new SimpleDateFormat("hh:mm:ss");
        SimpleDateFormat mDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(WatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        float mXOffset;
        float mYTimeOffset;
        float mYDateOffset;
        float mYInfoOffset;


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            calendar = Calendar.getInstance();
            calendar.setTimeZone(TimeZone.getDefault());

            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = WatchFaceService.this.getResources();
            mYTimeOffset = resources.getDimension(R.dimen.digital_y_time_offset);
            mYDateOffset = resources.getDimension(R.dimen.digital_y_date_offset);
            mYInfoOffset = resources.getDimension(R.dimen.digital_y_info_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimePaint = new Paint();
            mTimePaint.setColor(resources.getColor(R.color.digital_text));
            mTimePaint.setTypeface(NORMAL_TYPEFACE);
            mTimePaint.setAntiAlias(true);

            mDatePaint = new Paint();
            mDatePaint.setColor(resources.getColor(R.color.digital_text_light));
            mDatePaint.setTypeface(NORMAL_TYPEFACE);
            mDatePaint.setAntiAlias(true);

            mHighPaint = new Paint();
            mHighPaint.setColor(resources.getColor(R.color.digital_text));
            mHighPaint.setTypeface(NORMAL_TYPEFACE);
            mHighPaint.setAntiAlias(true);

            mLowPaint = new Paint();
            mLowPaint.setColor(resources.getColor(R.color.digital_text_light));
            mLowPaint.setTypeface(NORMAL_TYPEFACE);
            mLowPaint.setAntiAlias(true);

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
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
            WatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);

            mGoogleApiClient.connect();
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);

            if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTimePaint.setTextSize(textSize);
            mDatePaint.setTextSize(textSize/3);
            mHighPaint.setTextSize(textSize/1.3f);
            mLowPaint.setTextSize(textSize/1.3f);
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
                    mTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            calendar.setTimeInMillis(mTime.toMillis(true));

            String text = mAmbient
                    ? mTimeAmbientFormat.format(calendar.getTime())
                    : mTimeFormat.format(calendar.getTime());
            canvas.drawText(text, mXOffset, mYTimeOffset, mTimePaint);

            if (!mAmbient) {
                String dateText = mDateFormat.format(calendar.getTime()).toUpperCase();
                canvas.drawText(dateText, bounds.centerX() - (mDatePaint.measureText(dateText))/2,
                        mYDateOffset, mDatePaint);

                if (mHighText != null || mLowText != null || mWeatherIcon != null) {
                    canvas.drawText(mHighText, bounds.centerX() - (mHighPaint.measureText(mHighText)) / 2,
                            mYInfoOffset, mHighPaint);

                    canvas.drawText(mLowText, bounds.centerX() + (mHighPaint.measureText(mHighText))/2 + 20,
                            mYInfoOffset, mLowPaint);

                    float iconXOffset = bounds.centerX() - (mHighPaint.measureText(mHighText))/2 - (mWeatherIcon.getWidth() + 30);
                    canvas.drawBitmap(mWeatherIcon, iconXOffset, mYInfoOffset - mWeatherIcon.getHeight() + 10, null);

                }
            }
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
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            requestWeatherInfo();
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    Log.d(TAG, path);
                    if (path.equals(WEARABLE_UPDATE_PATH)) {
                        mHighText = dataMap.getString(WEARABLE_HIGH);
                        mLowText = dataMap.getString(WEARABLE_LOW);

                        int weatherId = dataMap.getInt(WEARABLE_WEATHER_ID);
                        Drawable b = getResources().getDrawable(getIcon(weatherId));
                        Bitmap icon = ((BitmapDrawable) b).getBitmap();
                        float scaledWidth = (mHighPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
                        mWeatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) mHighPaint.getTextSize(), true);

                        invalidate();
                    }
                }
            }
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }

        public void requestWeatherInfo() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEARABLE_REQUEST_PATH);
            putDataMapRequest.getDataMap().putString("uuid", UUID.randomUUID().toString());
            PutDataRequest request = putDataMapRequest.asPutDataRequest();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            if (!dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, "Failed asking phone for weather data");
                            } else {
                                Log.d(TAG, "Successfully asked for weather data");
                            }
                        }
                    });
        }

        public int getIcon(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            }
            return -1;
        }
    }
}
