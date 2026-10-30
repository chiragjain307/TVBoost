package com.tvboxrevive.helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The temperature for the launcher, which has no internet permission of its own. It answers from its cache
 * straight away and refreshes behind the answer, so a slow network never holds up the launcher, and it only fetches
 * when asked, so nothing polls while the TV is idle. Open-Meteo receives only the place.
 */
public class HelperProvider extends ContentProvider {
    private static final long WEATHER_AGE_MS = 30 * 60_000L;
    private static final String[] WEATHER_COLUMNS = {"temperature", "stale"};
    private volatile boolean fetchingWeather;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return weather(uri);
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("helper", 0);
    }

    private static String read(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", "TVBoxRevive/1.0");
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) body.write(buffer, 0, n);
            return new String(body.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    // ---- weather ----------------------------------------------------------------------------------

    private Cursor weather(Uri uri) {
        String lat = uri.getQueryParameter("lat");
        String lon = uri.getQueryParameter("lon");
        String unit = "f".equals(uri.getQueryParameter("unit")) ? "f" : "c";
        MatrixCursor cursor = new MatrixCursor(WEATHER_COLUMNS);
        double latitude;
        double longitude;
        try {
            latitude = Double.parseDouble(lat);
            longitude = Double.parseDouble(lon);
        } catch (NullPointerException | NumberFormatException e) {
            return cursor;
        }
        SharedPreferences prefs = prefs();
        String place = lat + "," + lon + "," + unit;
        boolean samePlace = place.equals(prefs.getString("place", ""));
        boolean fresh = samePlace && System.currentTimeMillis() - prefs.getLong("weather_at", 0) < WEATHER_AGE_MS;
        if (!fresh) refreshWeather(prefs, place, latitude, longitude, unit);
        cursor.addRow(new Object[]{samePlace ? prefs.getString("temperature", "") : "", fresh ? 0 : 1});
        return cursor;
    }

    private void refreshWeather(SharedPreferences prefs, String place, double latitude, double longitude, String unit) {
        if (fetchingWeather) return;
        fetchingWeather = true;
        new Thread(() -> {
            try {
                double value = new JSONObject(read(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m&temperature_unit=%s",
                        latitude, longitude, "f".equals(unit) ? "fahrenheit" : "celsius")))
                        .getJSONObject("current").getDouble("temperature_2m");
                prefs.edit().putString("place", place).putString("temperature", Math.round(value) + "°")
                        .putLong("weather_at", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                // No network or a bad answer: keep the last value and try again on the next query.
            } finally {
                fetchingWeather = false;
            }
        }, "weather").start();
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
