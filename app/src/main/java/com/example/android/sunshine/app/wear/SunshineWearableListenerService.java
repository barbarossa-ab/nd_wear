package com.example.android.sunshine.app.wear;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.R;
import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;

public class SunshineWearableListenerService extends WearableListenerService
        implements GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks{
    private static final String TAG = "handheld-to-wear";

    public static final String SEND_UPDATE_PATH = "/send-updates";
    public static final String SEND_UPDATE_MSG = "/send-updates";

    private static final String WEATHER_UPDATE_PATH = "/weather-update";
    private static final String IMAGE_KEY = "image";
    private static final String MAX_TEMP_KEY = "max-temp";
    private static final String MIN_TEMP_KEY = "min-temp";
    private static final String TIMESTAMP_KEY = "timestamp";

    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
    };

    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    GoogleApiClient mGoogleApiClient;

//    public SunshineWearableListenerService() {
//        super.onCreate();
//        mGoogleApiClient = new GoogleApiClient.Builder(this)
//                .addApi(Wearable.API)
//                .build();
//        mGoogleApiClient.connect();
//    }

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }


    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
//        LOGD(TAG, "onMessageReceived: " + messageEvent);
        Log.e(TAG, "Received message...");

        // Check to see if the message is to start an activity
        if (messageEvent.getPath().equals(SEND_UPDATE_PATH)) {
            Log.e(TAG, "Message requests update...");
            sendUpdateToWear();
        }
    }

    private void sendUpdateToWear() {
        String locationQuery = Utility.getPreferredLocation(this);
        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

        Cursor cursor = getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);

        if (cursor.moveToFirst()) {
            int weatherId = cursor.getInt(INDEX_WEATHER_ID);
            double high = cursor.getDouble(INDEX_MAX_TEMP);
            double low = cursor.getDouble(INDEX_MIN_TEMP);

            Log.e(TAG, "Sending update: "
                    + "weather-id = " + weatherId
                    + ", high = " + high
                    + ", low = " + low
                    + "...");

            PutDataMapRequest dataMap = PutDataMapRequest.create(WEATHER_UPDATE_PATH);
            dataMap.getDataMap().putString(MAX_TEMP_KEY, Utility.formatTemperature(this, high));
            dataMap.getDataMap().putString(MIN_TEMP_KEY, Utility.formatTemperature(this, low));

            int imgId = Utility.getIconResourceForWeatherCondition(weatherId);
            Bitmap image = BitmapFactory.decodeResource( getResources(), imgId );

            Asset imageAsset = createAssetFromBitmap(image);
            dataMap.getDataMap().putAsset(IMAGE_KEY, imageAsset);

            dataMap.getDataMap().putLong(TIMESTAMP_KEY, System.currentTimeMillis());

            PutDataRequest request = dataMap.asPutDataRequest();
            request.setUrgent();

            Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(DataApi.DataItemResult dataItemResult) {
                            Log.e(TAG, "Sending update was successful: " + dataItemResult.getStatus()
                                    .isSuccess());
                        }
                    });


        }
    }

//    public Bitmap getImage (int id, int width, int height) {
//        Bitmap bmp = BitmapFactory.decodeResource( getResources(), id );
//        Bitmap img = Bitmap.createScaledBitmap( bmp, width, height, true );
////        bmp.recycle();
//        return img;
//    }

    private static Asset createAssetFromBitmap(Bitmap bitmap) {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }
    @Override
    public void onConnected(@Nullable Bundle bundle) {
    }

    @Override
    public void onConnectionSuspended(int i) {
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
    }
}
