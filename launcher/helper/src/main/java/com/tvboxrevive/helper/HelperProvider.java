package com.tvboxrevive.helper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Live data for the launcher, which has no internet permission of its own. It answers from its cache straight
 * away and refreshes behind the answer, so a slow network never holds up Home, and it only fetches when asked, so
 * nothing polls while the TV is idle. The temperature comes from Open-Meteo, which receives only the place. Shows
 * come from TVmaze, which receives nothing about the viewer. Data from TVmaze is licensed CC BY-SA.
 */
public class HelperProvider extends ContentProvider {
    private static final long WEATHER_AGE_MS = 30 * 60_000L;
    private static final long SHOWS_AGE_MS = 12 * 60 * 60_000L;
    private static final int DAYS = 5;
    private static final int POSTERS = 16;
    private static final String[] WEATHER_COLUMNS = {"temperature", "stale"};
    private static final String[] SHOW_COLUMNS = {"title", "rating", "network", "poster"};
    private static final Set<String> NETWORKS = new HashSet<>(Arrays.asList("Netflix", "Prime Video", "Apple TV",
            "Apple TV+", "HBO Max", "Max", "Disney+", "Hotstar", "JioHotstar", "SonyLIV", "ZEE5", "Paramount+", "Hulu"));
    private volatile boolean fetchingWeather;
    private volatile boolean fetchingShows;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return "/foryou".equals(uri.getPath()) ? shows() : weather(uri);
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

    // ---- shows ------------------------------------------------------------------------------------

    private Cursor shows() {
        SharedPreferences prefs = prefs();
        if (System.currentTimeMillis() - prefs.getLong("shows_at", 0) > SHOWS_AGE_MS) refreshShows(prefs);
        MatrixCursor cursor = new MatrixCursor(SHOW_COLUMNS);
        try {
            JSONArray rows = new JSONArray(prefs.getString("shows", "[]"));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                cursor.addRow(new Object[]{row.getString("title"), row.getString("rating"), row.getString("network"),
                        "content://com.tvboxrevive.helper/poster/" + row.getLong("id")});
            }
        } catch (org.json.JSONException e) {
            return cursor;
        }
        return cursor;
    }

    private void refreshShows(SharedPreferences prefs) {
        if (fetchingShows) return;
        fetchingShows = true;
        new Thread(() -> {
            try {
                List<JSONObject> found = new ArrayList<>();
                Set<Long> seen = new HashSet<>();
                LocalDate day = LocalDate.now();
                for (int i = 0; i < DAYS; i++) {
                    JSONArray episodes = new JSONArray(read("https://api.tvmaze.com/schedule/web?date=" + day.minusDays(i)));
                    for (int e = 0; e < episodes.length(); e++) {
                        JSONObject embedded = episodes.getJSONObject(e).optJSONObject("_embedded");
                        JSONObject show = embedded == null ? null : embedded.optJSONObject("show");
                        JSONObject row = show == null ? null : row(show);
                        if (row != null && seen.add(row.getLong("id"))) found.add(row);
                    }
                }
                found.sort(Comparator.comparingDouble((JSONObject r) -> -r.optDouble("score")));
                JSONArray kept = new JSONArray();
                for (JSONObject row : found) {
                    if (kept.length() >= POSTERS) break;
                    if (savePoster(row)) kept.put(row);
                }
                if (kept.length() > 0) {
                    prefs.edit().putString("shows", kept.toString()).putLong("shows_at", System.currentTimeMillis()).apply();
                }
            } catch (Exception e) {
                // Keep the last list and try again on the next query.
            } finally {
                fetchingShows = false;
            }
        }, "shows").start();
    }

    private static JSONObject row(JSONObject show) throws org.json.JSONException {
        JSONObject channel = show.optJSONObject("webChannel");
        JSONObject image = show.optJSONObject("image");
        JSONObject rating = show.optJSONObject("rating");
        if (channel == null || image == null || rating == null) return null;
        double score = rating.optDouble("average", 0);
        String network = channel.optString("name");
        if (score <= 0 || !NETWORKS.contains(network) || image.optString("medium").isEmpty()) return null;
        return new JSONObject().put("id", show.getLong("id")).put("title", show.getString("name"))
                .put("network", network).put("score", score).put("rating", String.format(Locale.US, "%.1f", score))
                .put("image", image.getString("medium"));
    }

    private File poster(long id) {
        return new File(getContext().getCacheDir(), "poster-" + id + ".jpg");
    }

    private boolean savePoster(JSONObject row) {
        try {
            File file = poster(row.getLong("id"));
            if (file.isFile()) return true;
            HttpURLConnection connection = (HttpURLConnection) new URL(row.getString("image")).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            } finally {
                connection.disconnect();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            File file = poster(Long.parseLong(uri.getLastPathSegment()));
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (NumberFormatException e) {
            throw new FileNotFoundException(uri.toString());
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
