package com.tvboxrevive.home;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The extras a design can turn on: a welcome screen that slides away on OK, a renewals panel at the top right
 * and a For You row under the dock. All three are off unless config.json asks for them, and this class builds
 * its own views, so a design without them runs the launcher exactly as before. The launcher still has no
 * internet permission: the temperature comes from the optional weather helper app, read as a content provider.
 */
final class HomeExtras {
    private static final String WELCOME_IMAGE = "welcome.jpg";
    private static final String RENEWALS_FILE = "renewals.json";
    private static final Uri WEATHER = Uri.parse("content://com.tvboxrevive.helper/now");
    private static final Uri SHOWS = Uri.parse("content://com.tvboxrevive.helper/foryou");
    private static final long WELCOME_AGAIN_MS = 30 * 60_000L;
    private static final int RENEWALS_SHOWN = 3;
    private static final int FOR_YOU_SHOWN = 4;
    private static final int POSTER_WIDTH_DP = 58;
    private static final int POSTER_HEIGHT_DP = 84;
    /** Streaming services, with their app on a TV and on a phone-style build, in the order to look for them. */
    private static final String[][] SERVICES = {
            {"Netflix", "com.netflix.ninja", "com.netflix.mediaclient"},
            {"Prime Video", "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"},
            {"JioHotstar", "in.startv.hotstar", "in.startv.hotstartv"},
            {"Hotstar", "in.startv.hotstar", "in.startv.hotstartv"},
            {"Sony LIV", "com.sonyliv", "com.sonyliv.tv"},
            {"SonyLIV", "com.sonyliv", "com.sonyliv.tv"},
            {"ZEE5", "com.graymatrix.did", "com.zee5.tv"},
            {"Apple TV", "com.apple.atve.androidtv.appletv"},
            {"Disney+", "com.disney.disneyplus"},
            {"YouTube", "com.google.android.youtube.tv", "com.google.android.youtube"},
    };
    private static final int GLASS_FILL = 0x26FFFFFF;
    private static final int GLASS_RIM = 0x59FFFFFF;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> dockApps;
    private boolean welcomeOn;
    private boolean welcomeLogo;
    private long showsReadAt;
    private String place = "";
    private String name = "";
    private String latitude = "";
    private String longitude = "";
    private String unit = "c";
    private View welcome;
    private ImageView welcomeImage;
    private TextView welcomeGreeting;
    private TextView welcomeDate;
    private TextView welcomeTime;
    private TextView welcomeWeather;
    private boolean welcomeVisible;
    private long leftAt = -1;
    private LinearLayout renewalsPanel;
    private LinearLayout forYouRow;
    private TextView forYouNote;
    private View lastGridFocus;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!welcomeVisible) return;
            updateWelcomeText();
            handler.postDelayed(this, 20_000L);
        }
    };

    private HomeExtras(Activity activity, List<String> dockApps) {
        this.activity = activity;
        this.dockApps = dockApps;
    }

    /** Null when the design turns nothing on, which is every design before this class existed. */
    static HomeExtras attach(Activity activity, List<String> dockApps) {
        JSONObject json = readConfig(activity);
        if (json == null) return null;
        boolean wantsWelcome = json.optBoolean("welcome_screen", false);
        boolean wantsRenewals = json.optBoolean("renewals_panel", false);
        boolean wantsForYou = json.optBoolean("for_you_row", false);
        if (!wantsWelcome && !wantsRenewals && !wantsForYou) return null;
        HomeExtras extras = new HomeExtras(activity, dockApps);
        extras.place = json.optString("location_name", "");
        extras.name = json.optString("greeting_name", "");
        extras.latitude = json.optString("latitude", "");
        extras.longitude = json.optString("longitude", "");
        extras.unit = json.optString("temperature_unit", "c");
        extras.welcomeOn = wantsWelcome;
        extras.welcomeLogo = json.optBoolean("welcome_logo", false);
        if (wantsRenewals) extras.buildRenewals();
        if (wantsForYou) {
            ForYou.enabled = true;
            extras.buildForYou();
        }
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
        if (welcomeOn && leftAt >= 0 && SystemClock.elapsedRealtime() - leftAt > WELCOME_AGAIN_MS) showWelcome();
        leftAt = -1;
        if (renewalsPanel != null) fillRenewals();
        if (forYouRow != null) fillForYou();
    }

    void onPause() {
        leftAt = SystemClock.elapsedRealtime();
    }

    void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseWelcomeImage();
    }

    /** True when the welcome screen took this key: OK, Back or a direction slides it away. */
    boolean onKey(KeyEvent event) {
        if (!welcomeVisible) return forYouKey(event);
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

    /** Up from the apps reaches the panel under the clock, and Down comes back to the same app. */
    private boolean forYouKey(KeyEvent event) {
        if (forYouRow == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
        View focus = activity.getCurrentFocus();
        if (focus == null) return false;
        ViewGroup grid = activity.findViewById(R.id.grid);
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_UP && isInside(focus, grid) && forYouRow.getChildCount() > 0) {
            lastGridFocus = focus;
            forYouRow.getChildAt(0).requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN && focus.getParent() == forYouRow) {
            if (lastGridFocus != null && lastGridFocus.isAttachedToWindow()) lastGridFocus.requestFocus();
            else grid.requestFocus();
            return true;
        }
        return false;
    }

    private static boolean isInside(View view, View ancestor) {
        for (Object parent = view; parent instanceof View; parent = ((View) parent).getParent()) {
            if (parent == ancestor) return true;
        }
        return false;
    }

    // ---- welcome screen ---------------------------------------------------------------------------

    private void buildWelcome() {
        FrameLayout screen = new FrameLayout(activity);
        screen.setBackgroundColor(0xFF090C14);
        screen.setClickable(true);
        welcomeImage = new ImageView(activity);
        welcomeImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        screen.addView(welcomeImage, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout column = new LinearLayout(activity);
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

    private void showWelcome() {
        welcome.setTranslationY(0);
        welcome.setVisibility(View.VISIBLE);
        welcome.bringToFront();
        welcomeVisible = true;
        loadWelcomeImage();
        updateWelcomeText();
        readWeather(true);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 20_000L);
    }

    private void hideWelcome() {
        welcomeVisible = false;
        handler.removeCallbacks(tick);
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
            return Sun.riseAndSet(today, Double.parseDouble(latitude), Double.parseDouble(longitude), ZoneId.systemDefault());
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

    /** Off the main thread: the helper answers from its cache and refreshes behind it, so ask once more if stale. */
    private void readWeather(boolean mayRetry) {
        showWeatherLine("");
        if (latitude.isEmpty() || longitude.isEmpty()) return;
        ContentResolver resolver = activity.getContentResolver();
        Uri uri = WEATHER.buildUpon().appendQueryParameter("lat", latitude).appendQueryParameter("lon", longitude)
                .appendQueryParameter("unit", unit).build();
        new Thread(() -> {
            String temperature = "";
            boolean stale = false;
            try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    temperature = cursor.getString(0);
                    stale = cursor.getInt(1) != 0;
                }
            } catch (RuntimeException e) {
                temperature = "";   // no helper installed: sunrise and sunset still show
            }
            String shown = temperature;
            boolean again = stale && mayRetry;
            handler.post(() -> {
                if (!welcomeVisible) return;
                showWeatherLine(shown);
                if (again) handler.postDelayed(() -> readWeather(false), 5_000L);
            });
        }, "welcome-weather").start();
    }

    // ---- renewals panel ---------------------------------------------------------------------------

    private void buildRenewals() {
        renewalsPanel = new LinearLayout(activity);
        renewalsPanel.setOrientation(LinearLayout.VERTICAL);
        renewalsPanel.setPadding(dp(12), dp(9), dp(12), dp(10));
        renewalsPanel.setBackground(glass(dp(14)));
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(dp(184), -2, Gravity.TOP | Gravity.END);
        corner.topMargin = dp(108);
        corner.rightMargin = dp(57);
        ((ViewGroup) activity.findViewById(R.id.root)).addView(renewalsPanel, corner);
        renewalsPanel.setVisibility(View.GONE);
    }

    private static String installedApp(PackageManager pm, String... packages) {
        for (String pkg : packages) {
            try {
                pm.getApplicationInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException e) {
                // try the next build of this app
            }
        }
        return null;
    }

    /** The installed app for a service's name, or null. Logos come from the app itself, never from this project. */
    private static String appFor(PackageManager pm, String name) {
        for (String[] service : SERVICES) {
            if (service[0].equalsIgnoreCase(name)) {
                return installedApp(pm, java.util.Arrays.copyOfRange(service, 1, service.length));
            }
        }
        return null;
    }

    private View badge(String name) {
        PackageManager pm = activity.getPackageManager();
        String pkg = appFor(pm, name);
        if (pkg != null) {
            try {
                ImageView icon = new ImageView(activity);
                icon.setImageDrawable(pm.getApplicationIcon(pkg));
                return icon;
            } catch (PackageManager.NameNotFoundException e) {
                // fall through to the letter
            }
        }
        TextView letter = text(9, 0xFFFFFFFF, R.font.outfit_medium);
        letter.setGravity(Gravity.CENTER);
        letter.setText(name.substring(0, 1).toUpperCase(Locale.getDefault()));
        GradientDrawable round = new GradientDrawable();
        round.setCornerRadius(dp(4));
        round.setColor(0x40FFFFFF);
        letter.setBackground(round);
        return letter;
    }

    private List<Renewals.Entry> readRenewals() {
        List<Renewals.Entry> entries = new ArrayList<>();
        File dir = Config.dir(activity);
        File file = dir == null ? null : new File(dir, RENEWALS_FILE);
        if (file == null || !file.isFile()) return entries;
        try {
            JSONArray rows = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                try {
                    entries.add(new Renewals.Entry(row.getString("name"), LocalDate.parse(row.getString("date")),
                            row.optString("cycle", "monthly")));
                } catch (DateTimeParseException e) {
                    // One mistyped date must not empty the whole panel.
                }
            }
        } catch (java.io.IOException | JSONException e) {
            entries.clear();
        }
        return entries;
    }

    private void fillRenewals() {
        LocalDate today = LocalDate.now();
        List<Renewals.Entry> upcoming = Renewals.upcoming(readRenewals(), today, RENEWALS_SHOWN);
        renewalsPanel.removeAllViews();
        renewalsPanel.setVisibility(upcoming.isEmpty() ? View.GONE : View.VISIBLE);
        if (upcoming.isEmpty()) return;
        TextView title = text(9, 0xB3FFFFFF, R.font.outfit_medium);
        title.setText(R.string.renews_title);
        title.setLetterSpacing(0.14f);
        renewalsPanel.addView(title);
        DateTimeFormatter date = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());
        for (Renewals.Entry e : upcoming) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, 0);
            LinearLayout.LayoutParams logo = new LinearLayout.LayoutParams(dp(15), dp(15));
            logo.rightMargin = dp(7);
            row.addView(badge(e.name), logo);
            TextView name = text(11, 0xFFFFFFFF, R.font.outfit_medium);
            name.setText(e.name);
            name.setSingleLine(true);
            long days = Renewals.daysUntil(e.date, today);
            TextView when = text(11, days <= 3 ? 0xFFFFD27A : 0xCCFFFFFF, R.font.outfit_medium);
            when.setText(days == 0 ? activity.getString(R.string.renews_today) : e.date.format(date));
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(when);
            renewalsPanel.addView(row);
        }
    }

    // ---- For You row ------------------------------------------------------------------------------

    /** A slim glass panel under the clock. Apps scroll sideways in one row, so nothing rises up to meet it. */
    private void buildForYou() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.setBackground(glass(dp(14)));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.BOTTOM);
        TextView title = text(11, 0xFFFFFFFF, R.font.outfit_medium);
        title.setText(R.string.for_you_title);
        forYouNote = text(8, 0x99FFFFFF, R.font.outfit_medium);
        forYouNote.setPadding(dp(7), 0, 0, dp(1));
        heading.addView(title);
        heading.addView(forYouNote);
        heading.setPadding(0, 0, 0, dp(6));
        panel.addView(heading);
        forYouRow = new LinearLayout(activity);
        forYouRow.setClipChildren(false);    // a focused tile grows past its row
        panel.addView(forYouRow);
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        corner.leftMargin = dp(57);
        corner.topMargin = dp(108);
        ((ViewGroup) activity.findViewById(R.id.root)).addView(panel, corner);
    }

    private List<String> launchable() {
        PackageManager pm = activity.getPackageManager();
        List<String> apps = new ArrayList<>();
        for (String pkg : dockApps) {
            if (launchIntent(pm, pkg) != null && !apps.contains(pkg)) apps.add(pkg);
        }
        return apps;
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent tv = pm.getLeanbackLaunchIntentForPackage(pkg);
        return tv != null ? tv : pm.getLaunchIntentForPackage(pkg);
    }

    private void open(String pkg) {
        PackageManager pm = activity.getPackageManager();
        Intent start = launchIntent(pm, pkg);
        if (start == null) return;
        ForYou.record(activity, pkg);
        activity.startActivity(start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static final class Show {
        String title;
        String rating;
        String pkg;
        Uri poster;
        Bitmap art;
    }

    private void fillForYou() {
        if (SystemClock.elapsedRealtime() - showsReadAt < 30 * 60_000L && forYouRow.getChildCount() > 0) return;
        showsReadAt = SystemClock.elapsedRealtime();
        if (forYouRow.getChildCount() == 0) showAppTiles();
        PackageManager pm = activity.getPackageManager();
        ContentResolver resolver = activity.getContentResolver();
        List<String> usual = ForYou.rank(activity, launchable(), 99);
        new Thread(() -> {
            List<Show> shows = new ArrayList<>();
            try (Cursor cursor = resolver.query(SHOWS, null, null, null, null)) {
                while (cursor != null && cursor.moveToNext()) {
                    Show show = new Show();
                    show.title = cursor.getString(0);
                    show.rating = cursor.getString(1);
                    show.pkg = appFor(pm, cursor.getString(2));
                    show.poster = Uri.parse(cursor.getString(3));
                    if (show.pkg != null) shows.add(show);
                }
            } catch (RuntimeException e) {
                return;   // no helper: the app tiles stay
            }
            // The apps this viewer opens most come first, and no one service fills the row.
            shows.sort(java.util.Comparator.comparingInt(s -> usual.contains(s.pkg) ? usual.indexOf(s.pkg) : 99));
            List<Show> picked = new ArrayList<>();
            java.util.Map<String, Integer> perApp = new java.util.HashMap<>();
            for (int cap = 2; cap <= FOR_YOU_SHOWN && picked.size() < FOR_YOU_SHOWN; cap++) {
                for (Show show : shows) {
                    int used = perApp.getOrDefault(show.pkg, 0);
                    if (picked.size() < FOR_YOU_SHOWN && used < cap && !picked.contains(show)) {
                        show.art = decodePoster(resolver, show.poster);
                        if (show.art == null) continue;
                        picked.add(show);
                        perApp.put(show.pkg, used + 1);
                    }
                }
            }
            if (picked.size() < FOR_YOU_SHOWN) {
                for (Show show : picked) show.art.recycle();
                return;
            }
            handler.post(() -> showPosters(picked));
        }, "for-you").start();
    }

    private Bitmap decodePoster(ContentResolver resolver, Uri uri) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        try (java.io.InputStream in = resolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, options);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private void addTile(View tile, int width, int height, Runnable open) {
        tile.setFocusable(true);
        tile.setOnFocusChangeListener((view, focused) -> {
            view.animate().scaleX(focused ? 1.1f : 1f).scaleY(focused ? 1.1f : 1f).setDuration(120).start();
            view.setElevation(focused ? dp(6) : 0);
            view.setForeground(focused ? focusRim() : null);
        });
        tile.setOnClickListener(view -> open.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        if (forYouRow.getChildCount() > 0) params.leftMargin = dp(7);
        forYouRow.addView(tile, params);
    }

    /** Before any posters arrive, or with no internet: the most-used apps, so the panel is never empty. */
    private void showAppTiles() {
        PackageManager pm = activity.getPackageManager();
        forYouRow.removeAllViews();
        forYouNote.setText(R.string.for_you_apps_note);
        for (String pkg : ForYou.rank(activity, launchable(), FOR_YOU_SHOWN)) {
            ImageView tile = new ImageView(activity);
            tile.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.setPadding(dp(4), dp(4), dp(4), dp(4));
            tile.setBackground(glass(dp(7)));
            try {
                Drawable art = pm.getApplicationBanner(pkg);
                tile.setImageDrawable(art != null ? art : pm.getApplicationIcon(pkg));
                tile.setContentDescription(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            addTile(tile, dp(POSTER_WIDTH_DP), dp(POSTER_HEIGHT_DP), () -> open(pkg));
        }
    }

    private void showPosters(List<Show> shows) {
        boolean hadFocus = forYouRow.hasFocus();
        forYouRow.removeAllViews();
        forYouNote.setText(R.string.for_you_shows_note);
        for (Show show : shows) {
            FrameLayout tile = new FrameLayout(activity);
            tile.setContentDescription(show.title);
            ImageView poster = new ImageView(activity);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setImageBitmap(show.art);
            poster.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(7));
                }
            });
            poster.setClipToOutline(true);
            tile.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            TextView rating = text(7, 0xFFFFFFFF, R.font.outfit_medium);
            rating.setText(show.rating);
            rating.setPadding(dp(3), 0, dp(3), 0);
            GradientDrawable chip = new GradientDrawable();
            chip.setCornerRadius(dp(8));
            chip.setColor(0xB3000000);
            rating.setBackground(chip);
            FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            corner.setMargins(0, 0, dp(3), dp(3));
            tile.addView(rating, corner);
            addTile(tile, dp(POSTER_WIDTH_DP), dp(POSTER_HEIGHT_DP), () -> open(show.pkg));
        }
        if (hadFocus && forYouRow.getChildCount() > 0) forYouRow.getChildAt(0).requestFocus();
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

    private Drawable focusRim() {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(7));
        d.setStroke(dp(2), 0xFFFFFFFF);
        return d;
    }
}
