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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;


public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final String TAG = "WatchFace";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    private static final int MSG_UPDATE_TIME = 0;
    public static String PATH_WEAR_WEATHER = "/weather";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }


    private class Engine extends CanvasWatchFaceService.Engine implements
            MessageApi.MessageListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        Calendar mCalendar;
        boolean mLowBitAmbient;
        GoogleApiClient mGoogleApiClient;

        int BACKGROUND_COLOR = ContextCompat.getColor(getApplicationContext(), R.color.background);
        int TEXT_HOURS_MIN_COLOR = Color.WHITE;
        int TEXT_SECONDS_COLOR = Color.GRAY;
        int TEXT_COLON_COLOR = Color.GRAY;
        int TEXT_MAX_TEMP_COLOR = Color.WHITE;
        int TEXT_MIN_TEMP_COLOR = Color.GRAY;
        int TEXT_AM_PM_COLOR = Color.GRAY;
        int TEXT_DATE_COLOR = Color.WHITE;
        String COLON_STRING = ":";

        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mSecondPaint;
        Paint mColonPaint;
        Paint mTempMaxPaint;
        Paint mTempMinPaint;
        Paint mAmPmPaint;
        Paint mDatePaint;

        float mColonWidth;

        float mXOffset;
        float mYOffset;
        float mXDateOffset;
        float mLineHeight;
        float mTempLineHeight;
        float mWeatherIconOffset;

        String mAmString;
        String mPmString;
        String mDateStr;

        int mWeatherID;
        double mWeatherMax;
        double mWeatherMin;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            mGoogleApiClient.connect();

            Resources resources = SunshineWatchFaceService.this.getResources();
            mCalendar = Calendar.getInstance();

            mYOffset = resources.getDimension(R.dimen.sunshine_y_offset);
            mLineHeight = resources.getDimension(R.dimen.sunshine_date_line_height);

            mAmString = resources.getString(R.string.sunshine_am);
            mPmString = resources.getString(R.string.sunshine_pm);

            mHourPaint = createTextPaint(TEXT_HOURS_MIN_COLOR, BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(TEXT_HOURS_MIN_COLOR);
            mSecondPaint = createTextPaint(TEXT_SECONDS_COLOR);
            mColonPaint = createTextPaint(TEXT_COLON_COLOR);
            mTempMaxPaint = createTextPaint(TEXT_MAX_TEMP_COLOR, BOLD_TYPEFACE);
            mTempMinPaint = createTextPaint(TEXT_MIN_TEMP_COLOR);
            mAmPmPaint = createTextPaint(TEXT_AM_PM_COLOR);
            mDatePaint = createTextPaint(TEXT_DATE_COLOR);
            mTempMaxPaint = createTextPaint(TEXT_MAX_TEMP_COLOR);
            mTempMinPaint = createTextPaint(TEXT_MIN_TEMP_COLOR);

            mWeatherID = 800;
            mWeatherMax = 32;
            mWeatherMin = 20;

            Date mDate = new Date();
            mDateStr = java.text.DateFormat.getDateInstance().format(mDate);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            releaseGoogleApiClient();
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                mGoogleApiClient.connect();
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                releaseGoogleApiClient();
            }

            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {

            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();

            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.sunshine_x_offset_round : R.dimen.sunshine_x_offset);
            mTempLineHeight = resources.getDimension(isRound
                    ? R.dimen.sunshine_temp_line_height_round : R.dimen.sunshine_temp_line_height);
            mXDateOffset =  resources.getDimension(isRound
                    ? R.dimen.sunshine_date_x_offset_round : R.dimen.sunshine_date_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.sunshine_text_size_round : R.dimen.sunshine_text_size);
            float amPmSize = resources.getDimension(isRound
                    ? R.dimen.sunshine_am_pm_size_round : R.dimen.sunshine_am_pm_size);
            float dateSize = resources.getDimension(isRound
                    ? R.dimen.sunshine_date_size_round : R.dimen.sunshine_date_size);
            float tempSize = resources.getDimension(isRound
                    ? R.dimen.sunshine_max_temp_size_round : R.dimen.sunshine_max_temp_size);
            mWeatherIconOffset = isRound ? 45 : 35;

            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mSecondPaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mTempMaxPaint.setTextSize(tempSize);
            mTempMinPaint.setTextSize(tempSize);
            mAmPmPaint.setTextSize(amPmSize);
            mDatePaint.setTextSize(dateSize);

            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

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
            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mSecondPaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
                mAmPmPaint.setAntiAlias(antiAlias);
                mTempMaxPaint.setAntiAlias(antiAlias);
                mTempMinPaint.setAntiAlias(antiAlias);
                mDatePaint.setAntiAlias(antiAlias);
            }
            invalidate();

            updateTimer();
        }

        private String formatTwoDigitNumber(int number) {
            return String.format(Locale.US, "%02d", number);
        }

        private String getAmPmString(int amPm) {
            return amPm == Calendar.AM ? mAmString : mPmString;
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);

            // Draw the background.
            if (!isInAmbientMode())
                canvas.drawColor(BACKGROUND_COLOR);
            else
                canvas.drawColor(Color.BLACK);

            // Draw the hours.
            float x = mXOffset;
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            canvas.drawText(hourString, x, mYOffset, mHourPaint);
            x += mHourPaint.measureText(hourString);

            // Draw first colon (between hour and minute).
            canvas.drawText(COLON_STRING, x, mYOffset, mColonPaint);

            x += mColonWidth;

            // Draw the minutes.
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, x, mYOffset, mMinutePaint);
            x += mMinutePaint.measureText(minuteString);

            // In interactive mode, draw a second colon followed by the seconds.
            // Otherwise, if we're in 12-hour mode, draw AM/PM
            if (!isInAmbientMode()) {
                canvas.drawText(COLON_STRING, x, mYOffset, mColonPaint);

                x += mColonWidth;
                canvas.drawText(formatTwoDigitNumber(
                        mCalendar.get(Calendar.SECOND)), x, mYOffset, mSecondPaint);

            } else if (!is24Hour) {
                x += mColonWidth;
                canvas.drawText(getAmPmString(
                        mCalendar.get(Calendar.AM_PM)), x, mYOffset, mAmPmPaint);
            }

            canvas.drawText(
                    mDateStr,
                    mXDateOffset,
                    mYOffset + mLineHeight,
                    mDatePaint);

            // Only render temperature if there is no peek card, so they do not bleed into each other
            // in ambient mode.
            if (getPeekCardPosition().isEmpty()) {
                if (!isInAmbientMode()) {
                    int drawableID = getIconResourceForWeatherCondition(mWeatherID);

                    if (drawableID != -1) {
                        Bitmap weatherIcon = BitmapFactory.decodeResource(getApplicationContext().getResources(),
                                drawableID);
                        canvas.drawBitmap(
                                Bitmap.createScaledBitmap(weatherIcon, 70, 70, false),
                                mWeatherIconOffset,
                                mYOffset + mLineHeight + 10,
                                null);

                        canvas.drawText(
                                String.valueOf(Math.round(mWeatherMax)).concat(getString(R.string.degree_symbol)),
                                mWeatherIconOffset + 80,
                                mYOffset + mLineHeight + mTempLineHeight,
                                mTempMaxPaint);

                        canvas.drawText(
                                String.valueOf(Math.round(mWeatherMin)).concat(getString(R.string.degree_symbol)),
                                mWeatherIconOffset + 80 + mTempMaxPaint.measureText("999"),
                                mYOffset + mLineHeight + mTempLineHeight,
                                mTempMinPaint);
                    }
                }
            }
        }


        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }


        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }


        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void releaseGoogleApiClient() {
            if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                mGoogleApiClient.disconnect();
            }
        }

        @Override
        public void onMessageReceived(MessageEvent messageEvent) {
            if (messageEvent.getPath().equals(PATH_WEAR_WEATHER)){
                byte[] byteArray = messageEvent.getData();
                DataMap configDataMap = DataMap.fromByteArray(byteArray);

                mWeatherID = configDataMap.getInt("0");
                mWeatherMax = configDataMap.getDouble("1");
                mWeatherMin = configDataMap.getDouble("2");
            }
            invalidate();
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "GoogleAPI onConnected");
            Wearable.MessageApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "GoogleAPI onConnectionSuspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "GoogleAPI onConnectionFailed " + connectionResult);
        }

    }

    public int getIconResourceForWeatherCondition(int weatherId) {
        // Based on weather code data found at:
        // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
        if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.art_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.art_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.art_rain;
        } else if (weatherId == 511) {
            return R.drawable.art_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.art_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.art_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.art_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            return R.drawable.art_storm;
        } else if (weatherId == 800) {
            return R.drawable.art_clear;
        } else if (weatherId == 801) {
            return R.drawable.art_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.art_clouds;
        }
        return -1;
    }
}
