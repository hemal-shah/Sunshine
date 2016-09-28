package hemal.mukesh.shah.sunshine;

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
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
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
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Class to draw a custom watch-face, with time, day, high-low temperature, and
 * corresponding weather icon.
 */
public class MyWatchFace extends CanvasWatchFaceService {

    //Typeface we would use.
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC);


    String HIGH_TEMP = "Null", LOW_TEMP = "Null";
    Bitmap bitmap = null;

    private static final String TAG = MyWatchFace.class.getSimpleName();

    @Override
    public void onCreate() {
        super.onCreate();
    }

    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }


    /**
     * Worker thread to update the time on the watch-face.
     */
    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    /**
     * The actual class that would update our watch-face and is responsible
     * for drawing on the canvas.
     */

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        boolean mRegisteredTimeZoneReceiver = false;

        GoogleApiClient apiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        //All the brushes.
        Paint mBackgroundPaint, mTextPaint, mTextPaintDate, temperaturePaint;

        boolean mAmbient;
        Calendar mCalendar;
        Context context;

        float xOffset, yOffset, yOffsetDate, yOffsetTemperature;

        //Receiver that's notified of the time zone changes.
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            context = getBaseContext();

            //specifying what kind of watchface you are building.
            //equivalent to setContentView in normal activities.
            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
//                    .setAcceptsTapEvents(true)
                    .build());

            //getting the resources.
            Resources resources = MyWatchFace.this.getResources();

            yOffset = resources.getDimension(R.dimen.digital_y_offset);
            yOffsetDate = resources.getDimension(R.dimen.digital_y_offset_date);
            yOffsetTemperature = resources.getDimension(R.dimen.digital_y_offset_temp);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint.setColor(resources.getColor(R.color.digital_text));
            mTextPaint.setTypeface(NORMAL_TYPEFACE);
            mTextPaint.setAntiAlias(true);

            mTextPaintDate = new Paint();
            mTextPaintDate.setColor(resources.getColor(R.color.date_text_color));
            mTextPaintDate.setTypeface(NORMAL_TYPEFACE);
            mTextPaintDate.setAntiAlias(true);

            temperaturePaint = new Paint();
            temperaturePaint.setTextSize(35);
            temperaturePaint.setTypeface(BOLD_TYPEFACE);
            temperaturePaint.setColor(resources.getColor(R.color.digital_text));
            temperaturePaint.setAntiAlias(true);


            mCalendar = Calendar.getInstance();
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
                apiClient.connect();
                registerReceiver();
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                apiClient.disconnect();
                unregisterReceiver();
            }

            updateTimer();
        }

        /**
         * Method for registering {$mTimeZoneReceiver} based on the value of
         * {$mRegisteredTimeZoneReceiver}.
         */
        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        /**
         * Method for unregistering {$mTimeZoneReceiver} so that it can no longer receive
         * updates on the change of time-zone.
         */
        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * A method which is called when the window insets are fixed, meaning
         * it can identify whether the watch is round or square faced.
         *
         * @param insets Passed on by the system itself, containing data about the face.
         */
        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            xOffset = resources.getDimension(
                    isRound ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);


            mTextPaintDate.setTextSize(textSize - 15);
            mTextPaint.setTextSize(textSize);
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
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    temperaturePaint.setAntiAlias(!inAmbientMode);
                    mTextPaintDate.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            updateTimer();
        }

        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            //No current requirements to do anything.
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            int width = bounds.width(), height = bounds.height();

            if (isInAmbientMode())
                canvas.drawColor(Color.BLACK);
            else
                canvas.drawRect(0, 0, width, height, mBackgroundPaint);


            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String text = String.format(Locale.US, "%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
                    mCalendar.get(Calendar.MINUTE));

            SimpleDateFormat format = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.US);
            String date = format.format(now);

            canvas.drawText(text, xOffset, yOffset, mTextPaint);
            canvas.drawText(date, xOffset, yOffsetDate, mTextPaintDate);

            if(HIGH_TEMP.compareTo("Null") != 0 || LOW_TEMP.compareTo("Null") != 0){
                canvas.drawText((HIGH_TEMP + "|" + LOW_TEMP), xOffset, yOffsetTemperature, temperaturePaint);
            }

            if(bitmap != null){
                canvas.drawBitmap(bitmap, width - 2 * xOffset, yOffset, mTextPaint);
            }
        }

        /**
         * Updates the timer.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Based on the visibility, and ambient mode returns true or false,
         * whether the timer should be running or not. Generally applicable when seconds are ticking.
         *
         * @return Boolean stating seconds should be visible or not.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Method which handles the process of updating the watchface every 1 second,
         * or 1000 milliseconds.
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
            Log.i(TAG, "onConnected: ");
            Wearable.DataApi.addListener(apiClient, new DataApi.DataListener() {
                @Override
                public void onDataChanged(DataEventBuffer dataEventBuffer) {
                    Log.i(TAG, "onDataChanged: " + getString(R.string.WEAR_KEY_HIGH));
                    for (DataEvent event : dataEventBuffer) {
                        if (event.getType() == DataEvent.TYPE_CHANGED) {
                            Log.i(TAG, "onDataChanged: getting the data");
                            DataItem item = event.getDataItem();
                            if (item.getUri().getPath().compareTo(getString(R.string.WEAR_PATH)) == 0) {
                                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                                HIGH_TEMP = dataMap.getString(getString(R.string.WEAR_KEY_HIGH));
                                LOW_TEMP = dataMap.getString(getString(R.string.WEAR_KEY_LOW));
                                bitmap = loadBitmapFromAsset(dataMap.getAsset(getString(R.string.WEAR_KEY_ICON)));
                            }
                        }
                    }
                    //Weather is received, invalidate() so that the watch face is updated!
                    invalidate();
                }
            });
        }

        /**
         * Helper method to convert the asset to Bitmap to display.
         * @param asset Asset file received from the Api Client.
         * @return Bitmap Derived from the asset file.
         */
        private Bitmap loadBitmapFromAsset(Asset asset){
            if(asset == null)
                throw new IllegalArgumentException("Asset must not be null!");

            ConnectionResult result =
                    apiClient.blockingConnect(10000 , TimeUnit.MILLISECONDS);

            if (!result.isSuccess()) {
                return null;
            }
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    apiClient, asset).await().getInputStream();
            apiClient.disconnect();

            if (assetInputStream == null) {
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        }
    }
}
