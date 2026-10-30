package com.tvboxrevive.helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * The temperature for the launcher, which has no internet permission of its own. It answers from its cache, and
 * fetches only when that is old and only when asked, so nothing polls while the TV is idle. The launcher asks off its
 * main thread, so a slow network never holds it up. Open-Meteo receives only the place. With no place set, the
 * device finds its own, from its location service if it has one, or else from ipapi.co.
 */
public class HelperProvider extends ContentProvider {
    private static final long WEATHER_AGE_MS = 30 * 60_000L;
    private static final long PLACE_AGE_MS = 24 * 60 * 60_000L;
    private static final String[] COLUMNS = {"temperature", "stale", "city", "latitude", "longitude"};

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
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        SharedPreferences prefs = prefs();
        String city = "";
        if (lat == null || lon == null) {
            // No place set for this device: it finds its own, and keeps it for a day.
            if (System.currentTimeMillis() - prefs.getLong("found_at", 0) > PLACE_AGE_MS) findPlace(prefs);
            lat = prefs.getString("found_lat", "");
            lon = prefs.getString("found_lon", "");
            city = prefs.getString("found_city", "");
            if (lat.isEmpty() || lon.isEmpty()) {
                cursor.addRow(new Object[]{"", 1, "", "", ""});
                return cursor;
            }
        }
        double latitude;
        double longitude;
        try {
            latitude = Double.parseDouble(lat);
            longitude = Double.parseDouble(lon);
        } catch (NumberFormatException e) {
            return cursor;
        }
        String place = lat + "," + lon + "," + unit;
        if (!isFresh(prefs, place)) refreshWeather(prefs, place, latitude, longitude, unit);
        boolean samePlace = place.equals(prefs.getString("place", ""));
        cursor.addRow(new Object[]{samePlace ? prefs.getString("temperature", "") : "",
                isFresh(prefs, place) ? 0 : 1, city, lat, lon});
        return cursor;
    }

    private static boolean isFresh(SharedPreferences prefs, String place) {
        return place.equals(prefs.getString("place", ""))
                && System.currentTimeMillis() - prefs.getLong("weather_at", 0) < WEATHER_AGE_MS;
    }

    /**
     * The device's own location if it has one, which most TVs do not, or else its internet connection's. Runs on
     * the caller's binder thread like the weather below: Android kills an idle helper within seconds, so work left
     * on a thread of its own after the query returns may never finish.
     */
    private void findPlace(SharedPreferences prefs) {
        try {
            Location fix = lastFix();
            double latitude;
            double longitude;
            String city;
            if (fix != null) {
                latitude = fix.getLatitude();
                longitude = fix.getLongitude();
                city = cityAt(fix);
            } else {
                JSONObject seen = new JSONObject(read("https://ipapi.co/json/"));
                latitude = seen.getDouble("latitude");
                longitude = seen.getDouble("longitude");
                city = seen.optString("city");
            }
            prefs.edit().putString("found_lat", String.format(Locale.US, "%.3f", latitude))
                    .putString("found_lon", String.format(Locale.US, "%.3f", longitude))
                    .putString("found_city", city).putLong("found_at", System.currentTimeMillis()).commit();
        } catch (IOException | JSONException e) {
            // No answer: the temperature stays blank, and the next query tries again.
        }
    }

    private Location lastFix() {
        LocationManager manager = getContext().getSystemService(LocationManager.class);
        if (manager == null) return null;
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location fix = manager.getLastKnownLocation(provider);
                if (fix != null) return fix;
            } catch (RuntimeException e) {
                // That provider is missing or not allowed here: try the next.
            }
        }
        return null;
    }

    private String cityAt(Location fix) {
        try {
            List<Address> found = new Geocoder(getContext()).getFromLocation(fix.getLatitude(), fix.getLongitude(), 1);
            if (found != null && !found.isEmpty() && found.get(0).getLocality() != null) return found.get(0).getLocality();
        } catch (IOException | RuntimeException e) {
            // Many TVs have no geocoder: the temperature still shows, with no city beside it.
        }
        return "";
    }

    private void refreshWeather(SharedPreferences prefs, String place, double latitude, double longitude, String unit) {
        try {
            double value = new JSONObject(read(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m&temperature_unit=%s",
                    latitude, longitude, "f".equals(unit) ? "fahrenheit" : "celsius")))
                    .getJSONObject("current").getDouble("temperature_2m");
            prefs.edit().putString("place", place).putString("temperature", Math.round(value) + "°")
                    .putLong("weather_at", System.currentTimeMillis()).commit();
        } catch (IOException | JSONException e) {
            // No network or a bad answer: keep the last value and try again on the next query.
        }
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
