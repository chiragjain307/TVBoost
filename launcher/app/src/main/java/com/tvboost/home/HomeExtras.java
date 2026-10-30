package com.tvboost.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The extras a design can turn on: a welcome screen that slides away on OK, and a tile beside Wi-Fi with RAM and
 * storage use. Both are off unless config.json asks for them, and this class builds its own views, so a design
 * without them runs the launcher exactly as before. The launcher still has no internet permission: the
 * temperature comes from the optional helper app, read as a content provider.
 */
final class HomeExtras {
    private static final String WELCOME_IMAGE = "welcome.jpg";
    private static final Uri WEATHER = Uri.parse("content://com.tvboost.helper/now");
    private static final long WELCOME_AGAIN_MS = 30 * 60_000L;
    private static final long TILE_REFRESH_MS = 15_000L;
    private static final long TICK_MS = 60_000L;
    private static final long WEATHER_AGAIN_MS = 30 * 60_000L;
    private static final int GLASS_FILL = 0x26FFFFFF;
    private static final int GLASS_RIM = 0x59FFFFFF;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean welcomeOn;
    private boolean welcomeLogo;
    private String place = "";
    private String name = "";
    private String latitude = "";
    private String longitude = "";
    private String foundLatitude = "";
    private String foundLongitude = "";
    private String unit = "c";
    private View welcome;
    private ImageView welcomeImage;
    private TextView welcomeGreeting;
    private TextView welcomeDate;
    private TextView welcomeTime;
    private TextView welcomeWeather;
    private boolean welcomeVisible;
    private long idleMs;
    private long weatherReadAt;
    private LinearLayout welcomeColumn;
    private final java.util.Random random = new java.util.Random();
    private long leftAt = -1;
    private View systemTile;
    private TextView ramValue;
    private TextView storageValue;
    private View ramBar;
    private View ramRest;
    private View storageBar;
    private View storageRest;
    private boolean resumed;
    private final Runnable idle = () -> {
        if (resumed && !welcomeVisible) showWelcome();
    };
    private final Runnable tileTick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            refreshTile();
            handler.postDelayed(this, TILE_REFRESH_MS);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!welcomeVisible) return;
            updateWelcomeText();
            drift();
            if (SystemClock.elapsedRealtime() - weatherReadAt > WEATHER_AGAIN_MS) readWeather(true);
            handler.postDelayed(this, TICK_MS);
        }
    };

    private HomeExtras(Activity activity) {
        this.activity = activity;
    }

    /** Null when the design turns nothing on, which is every design before this class existed. */
    static HomeExtras attach(Activity activity) {
        JSONObject json = readConfig(activity);
        if (json == null) return null;
        boolean wantsWelcome = json.optBoolean("welcome_screen", false);
        boolean wantsTile = json.optBoolean("system_tile", false);
        if (!wantsWelcome && !wantsTile) return null;
        HomeExtras extras = new HomeExtras(activity);
        extras.place = json.optString("location_name", "");
        extras.name = json.optString("greeting_name", "");
        extras.latitude = json.optString("latitude", "");
        extras.longitude = json.optString("longitude", "");
        extras.unit = json.optString("temperature_unit", "c");
        extras.welcomeOn = wantsWelcome;
        extras.welcomeLogo = json.optBoolean("welcome_logo", false);
        extras.idleMs = Math.max(0, json.optInt("welcome_idle_minutes", 0)) * 60_000L;
        if (wantsTile) extras.buildSystemTile();
        if (wantsWelcome) extras.buildWelcome();
        return extras;
    }

    private static JSONObject readConfig(Activity activity) {
        File dir = Config.dir(activity);
        if (dir == null) return null;
        File file = new File(dir, Config.FILE_NAME);
        if (!file.isFile()) return null;
        try {
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (java.io.IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    void onResume() {
        resumed = true;
        if (welcomeOn && leftAt >= 0 && SystemClock.elapsedRealtime() - leftAt > WELCOME_AGAIN_MS) showWelcome();
        leftAt = -1;
        restartIdleTimer();
        if (systemTile != null) {
            handler.removeCallbacks(tileTick);
            handler.post(tileTick);
        }
    }

    void onPause() {
        resumed = false;
        leftAt = SystemClock.elapsedRealtime();
        handler.removeCallbacks(tileTick);   // nothing is read while Home is hidden
        handler.removeCallbacks(idle);
    }

    void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseWelcomeImage();
    }

    /** True when the welcome screen took this key: OK, Back or a direction slides it away. */
    boolean onKey(KeyEvent event) {
        if (!welcomeVisible) {
            restartIdleTimer();
            return false;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) hideWelcome();
                return true;
            default:
                return false;
        }
    }

    // ---- welcome screen ---------------------------------------------------------------------------

    private void buildWelcome() {
        FrameLayout screen = new FrameLayout(activity);
        screen.setBackgroundColor(0xFF090C14);
        screen.setClickable(true);
        welcomeImage = new ImageView(activity);
        welcomeImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        welcomeImage.setScaleX(1.02f);    // room for drift() without showing an edge
        welcomeImage.setScaleY(1.02f);
        screen.addView(welcomeImage, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(activity);
        welcomeColumn = column;
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        // With a logo the picture carries it as the hero, and these lines sit small in the glass below it.
        welcomeGreeting = text(welcomeLogo ? 24 : 28, 0xFFFFFFFF, R.font.outfit_medium);
        welcomeDate = text(welcomeLogo ? 15 : 18, 0xCCFFFFFF, R.font.outfit_medium);
        welcomeTime = text(96, 0xFFFFFFFF, R.font.outfit_light);
        welcomeWeather = text(welcomeLogo ? 15 : 19, 0xE6FFFFFF, R.font.outfit_medium);
        welcomeDate.setPadding(0, dp(welcomeLogo ? 7 : 8), 0, 0);
        welcomeTime.setIncludeFontPadding(false);
        welcomeTime.setPadding(0, dp(6), 0, dp(6));
        welcomeWeather.setPadding(0, dp(welcomeLogo ? 7 : 0), 0, 0);
        for (TextView line : new TextView[]{welcomeGreeting, welcomeDate, welcomeTime, welcomeWeather}) {
            line.setGravity(Gravity.CENTER_HORIZONTAL);
            column.addView(line);
        }
        welcomeTime.setVisibility(welcomeLogo ? View.GONE : View.VISIBLE);
        FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(-1, -2,
                welcomeLogo ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER);
        if (welcomeLogo) center.topMargin = dp(327);
        screen.addView(column, center);

        TextView hint = text(14, 0x99FFFFFF, R.font.outfit_medium);
        hint.setText(R.string.welcome_hint);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bottom.bottomMargin = dp(34);
        screen.addView(hint, bottom);

        welcome = screen;
        ((ViewGroup) activity.findViewById(R.id.root)).addView(welcome, new FrameLayout.LayoutParams(-1, -1));
        showWelcome();
    }

    /** Home left alone goes back to the welcome screen, which takes the place of a screensaver in this design. */
    private void restartIdleTimer() {
        handler.removeCallbacks(idle);
        if (welcomeOn && idleMs > 0 && resumed) handler.postDelayed(idle, idleMs);
    }

    /** Moves the screen a few pixels now and then, so a logo left up for hours cannot mark a panel. */
    private void drift() {
        float x = (random.nextInt(7) - 3) * dp(1);
        float y = (random.nextInt(7) - 3) * dp(1);
        welcomeImage.setTranslationX(x);
        welcomeImage.setTranslationY(y);
        welcomeColumn.setTranslationX(x);
        welcomeColumn.setTranslationY(y);
    }

    private void showWelcome() {
        welcome.setTranslationY(0);
        welcome.setVisibility(View.VISIBLE);
        welcome.bringToFront();
        welcomeVisible = true;
        loadWelcomeImage();
        updateWelcomeText();
        readWeather(true);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    private void hideWelcome() {
        welcomeVisible = false;
        handler.removeCallbacks(tick);
        restartIdleTimer();
        int height = welcome.getHeight();
        welcome.animate().translationY(-height).setDuration(420).withLayer().withEndAction(() -> {
            welcome.setVisibility(View.GONE);
            releaseWelcomeImage();
        }).start();
    }

    private void loadWelcomeImage() {
        File dir = Config.dir(activity);
        File file = dir == null ? null : new File(dir, WELCOME_IMAGE);
        if (file == null || !file.isFile()) return;
        new Thread(() -> {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null) return;
            handler.post(() -> {
                if (welcomeVisible) welcomeImage.setImageBitmap(bitmap);
                else bitmap.recycle();
            });
        }, "welcome-image").start();
    }

    private void releaseWelcomeImage() {
        // The picture is only needed while it is on screen, and 4 MB matters on a 1 GB box.
        if (welcomeImage != null) welcomeImage.setImageDrawable(new ColorDrawable(0));
    }

    private void updateWelcomeText() {
        Date now = new Date();
        welcomeGreeting.setText(greetingText());
        String date = new java.text.SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now);
        String time = DateFormat.getTimeFormat(activity).format(now);
        welcomeDate.setText(welcomeLogo ? date + "     " + time : date);
        welcomeTime.setText(time);
    }

    private String greetingText() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int part = hour < 5 ? R.string.good_night : hour < 12 ? R.string.good_morning
                : hour < 17 ? R.string.good_afternoon : hour < 22 ? R.string.good_evening : R.string.good_night;
        String greeting = activity.getString(part);
        return name.isEmpty() ? greeting : activity.getString(R.string.greeting_with_name, greeting, name);
    }

    private int[] sun() {
        try {
            LocalDate today = LocalDate.now();
            return Sun.riseAndSet(today, Double.parseDouble(latitude.isEmpty() ? foundLatitude : latitude),
                    Double.parseDouble(longitude.isEmpty() ? foundLongitude : longitude), ZoneId.systemDefault());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String minutes(int minuteOfDay) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        c.set(Calendar.MINUTE, minuteOfDay % 60);
        return DateFormat.getTimeFormat(activity).format(c.getTime());
    }

    private void showWeatherLine(String temperature) {
        List<String> parts = new ArrayList<>();
        if (!temperature.isEmpty()) parts.add(place.isEmpty() ? temperature : temperature + " in " + place);
        int[] sun = sun();
        if (sun != null) {
            parts.add(activity.getString(R.string.sunrise_at, minutes(sun[0])));
            parts.add(activity.getString(R.string.sunset_at, minutes(sun[1])));
        }
        welcomeWeather.setText(String.join("     ", parts));
    }

    /** Off the main thread, as the helper may need the network. If it could not reach it, ask once more. */
    private void readWeather(boolean mayRetry) {
        weatherReadAt = SystemClock.elapsedRealtime();
        showWeatherLine("");
        ContentResolver resolver = activity.getContentResolver();
        Uri.Builder builder = WEATHER.buildUpon().appendQueryParameter("unit", unit);
        // With no place in the design, the helper works out where this device is.
        if (!latitude.isEmpty() && !longitude.isEmpty()) {
            builder.appendQueryParameter("lat", latitude).appendQueryParameter("lon", longitude);
        }
        Uri uri = builder.build();
        new Thread(() -> {
            String temperature = "";
            String city = "";
            String lat = "";
            String lon = "";
            boolean stale = false;
            try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    temperature = cursor.getString(0);
                    stale = cursor.getInt(1) != 0;
                    city = cursor.getString(2);
                    lat = cursor.getString(3);
                    lon = cursor.getString(4);
                }
            } catch (RuntimeException e) {
                temperature = "";   // no helper installed: sunrise and sunset still show if the design set a place
            }
            String shown = temperature;
            String foundCity = city;
            String foundLat = lat;
            String foundLon = lon;
            boolean again = stale && mayRetry;
            handler.post(() -> {
                if (!welcomeVisible) return;
                if (!foundCity.isEmpty()) place = foundCity;
                foundLatitude = foundLat;
                foundLongitude = foundLon;
                showWeatherLine(shown);
                if (again) handler.postDelayed(() -> readWeather(false), 5_000L);
            });
        }, "welcome-weather").start();
    }

    // ---- system tile ------------------------------------------------------------------------------

    /** Sits in the top bar just before Wi-Fi, so on a TV with inputs the Inputs button stays to its left. */
    private void buildSystemTile() {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(12), dp(5), dp(12), dp(5));
        tile.setBackground(glass(dp(21)));
        ramValue = text(9, 0xFFFFFFFF, R.font.outfit_medium);
        storageValue = text(9, 0xFFFFFFFF, R.font.outfit_medium);
        ramBar = new View(activity);
        ramRest = new View(activity);
        storageBar = new View(activity);
        storageRest = new View(activity);
        tile.addView(meter(R.string.tile_ram, ramValue, ramBar, ramRest));
        LinearLayout second = meter(R.string.tile_storage, storageValue, storageBar, storageRest);
        second.setPadding(0, dp(3), 0, 0);
        tile.addView(second);
        ViewGroup bar = activity.findViewById(R.id.topButtons);
        View wifi = activity.findViewById(R.id.btnWifi);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(136), dp(42));
        params.gravity = Gravity.CENTER_VERTICAL;
        params.rightMargin = dp(10);
        bar.addView(tile, bar.indexOfChild(wifi), params);
        systemTile = tile;
    }

    private LinearLayout meter(int label, TextView value, View used, View rest) {
        LinearLayout meter = new LinearLayout(activity);
        meter.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = new LinearLayout(activity);
        TextView name = text(9, 0xCCFFFFFF, R.font.outfit_medium);
        name.setText(label);
        line.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        line.addView(value);
        meter.addView(line);
        LinearLayout track = new LinearLayout(activity);
        track.setPadding(0, dp(1), 0, 0);
        used.setBackgroundColor(0xE6FFFFFF);
        rest.setBackgroundColor(0x33FFFFFF);
        track.addView(used, new LinearLayout.LayoutParams(0, dp(2), 0f));
        track.addView(rest, new LinearLayout.LayoutParams(0, dp(2), 100f));
        meter.addView(track);
        return meter;
    }

    private static void fill(View used, View rest, int percent) {
        LinearLayout.LayoutParams done = (LinearLayout.LayoutParams) used.getLayoutParams();
        LinearLayout.LayoutParams left = (LinearLayout.LayoutParams) rest.getLayoutParams();
        done.weight = percent;
        left.weight = 100 - percent;
        used.requestLayout();
        rest.requestLayout();
    }

    /** Off the main thread, and only while Home is showing. */
    private void refreshTile() {
        new Thread(() -> {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            ((ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
            long[] disk = readStorage();
            long diskTotal = disk[0];
            long diskFree = disk[1];
            int ramPercent = SystemStats.usedPercent(memory.totalMem, memory.availMem);
            int diskPercent = SystemStats.usedPercent(diskTotal, diskFree);
            String ram = SystemStats.size(memory.totalMem - memory.availMem) + " / " + SystemStats.size(memory.totalMem);
            String storage = SystemStats.size(diskTotal - diskFree) + " / " + SystemStats.size(diskTotal);
            handler.post(() -> {
                ramValue.setText(ram);
                storageValue.setText(storage);
                fill(ramBar, ramRest, ramPercent);
                fill(storageBar, storageRest, diskPercent);
            });
        }, "system-tile").start();
    }

    /**
     * {total, free} in bytes. df first: budget firmware can inflate what Android reports, and one verified box claims
     * exactly 8 GB of a real 4 GB. The Android call is only for devices with no usable df, and failure reads as zero.
     */
    private static long[] readStorage() {
        String data = Environment.getDataDirectory().getPath();
        try {
            Process df = new ProcessBuilder("df", "-k", data).redirectErrorStream(true).start();
            try (java.io.InputStream in = df.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                long[] parsed = SystemStats.parseDf(out.toString("UTF-8"));
                if (parsed != null) return parsed;
            } finally {
                df.destroy();
            }
        } catch (java.io.IOException e) {
            // fall through to the Android call
        }
        try {
            StructStatVfs disk = Os.statvfs(data);
            return new long[]{disk.f_blocks * disk.f_frsize, disk.f_bavail * disk.f_frsize};
        } catch (ErrnoException e) {
            return new long[]{0, 0};
        }
    }

    // ---- shared -----------------------------------------------------------------------------------

    private TextView text(float sp, int color, int font) {
        TextView view = new TextView(activity);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(activity.getResources().getFont(font));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private Drawable glass(int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(GLASS_FILL);
        d.setStroke(Math.max(1, dp(1)), GLASS_RIM);
        return d;
    }
}
