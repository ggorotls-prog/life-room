package app.recordkit.health;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;

// 건강 기록 화면 원본 한 벌(2026-11-15 룡 「기록 앱은 동일, 라이프 RPG는 비추는 창」·「하나로 모으자」).
// 건강 기록 앱 MainActivity와 라이프 RPG 🏠 집(HealthActivity)이 이 클래스를 이어받는다.
// 화면 = 건강 기록 사이트를 그대로 띄운다(사이트를 고치면 두 앱이 바로 바뀐다). 앱은 사진 고르기·찍기, 취침 잠금 다리(LifeRoom),
// 접근성·배터리 안내, 뒤로 가기만 맡는다. 새 버전 확인(건강 기록 앱)·자동 로그인(라이프 RPG)처럼 앱마다 다른 것은 이어받는 쪽에서.
public abstract class HealthScreen extends Activity {
    public static final String BASE = "https://ggorotls-prog.github.io/life-room/";
    static final String ADMIN_BASE = "https://ggorotls-prog.github.io/life-changer/";
    private static final int PICK = 7, PHOTO_PERM = 8, HC_REQ = 9, NOTIF_PERM = 10;

    protected WebView web;
    private ValueCallback<Uri[]> pickCallback;
    private File cameraFile;

    /** 사이트가 다 열렸을 때(라이프 RPG는 여기서 자동 로그인) */
    protected void onPageReady() {}

    /** 이 앱 이름(접근성 목록에 보이는 이름): 건강 기록 / 라이프 RPG */
    protected String appLabel() {
        try { return String.valueOf(getApplicationInfo().loadLabel(getPackageManager())); } catch (Exception e) { return "건강 기록"; }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#f6f5f1"));
        web = new WebView(this);
        // 폰의 자동 완성(삼성 패스 등)을 끈다. 다른 앱에서 로그인 칸의 키보드가 안 뜬 적이 있다(2026-10-30)
        web.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        root.addView(web);
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getInsetsController().setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        }

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                // 앱 기능(LifeRoom)을 쓸 수 있는 곳은 건강 기록과 관리자 화면(라이프 체인저)만. 같은 github.io의 다른 저장소는 폰 브라우저로 (2026-11-25 보안 점검)
                String u = req.getUrl().toString();
                if (u.startsWith(BASE) || u.startsWith(ADMIN_BASE)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) { onPageReady(); }

            // 폰이 메모리가 모자라 화면을 끊어도 앱이 같이 꺼지지 않게 다시 연다
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                root.removeView(view);
                view.destroy();
                web = null;
                recreate();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // 사진 버튼: 갤러리에서 고르거나 카메라로 찍는다
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (pickCallback != null) pickCallback.onReceiveValue(null);
                pickCallback = cb;
                Intent chooser = Intent.createChooser(
                        new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), "사진 올리기");
                Intent camera = cameraIntent();
                if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                try {
                    startActivityForResult(chooser, PICK);
                } catch (Exception e) {
                    pickCallback.onReceiveValue(null);
                    pickCallback = null;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "LifeRoom");
        web.loadUrl(BASE);
        askAccessibility(false);
        // 「그 순간에 먼저 묻기」(Moments)·기상 알람이 알림을 누르면 이 화면을 연다(건강 기록 앱 / 라이프 RPG 🏠 집)
        getSharedPreferences(SleepLock.PREFS, MODE_PRIVATE).edit().putString("m_screen", getClass().getName()).apply();
        Moments.schedule(this);
        alarmWindow(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        alarmWindow(i);
    }

    private boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** 기상 알람이 울리며 열었으면 잠금 화면 위에 켜서 보인다 */
    private void alarmWindow(Intent i) {
        if (i == null || !i.getBooleanExtra("wake_alarm", false)) return;
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        js("window.lifeAlarm && lifeAlarm()");
    }

    /** 사이트에 지금 상태를 알린다: 사진 묻기·운동 묻기·끼니 시간·알람(Health Connect 확인이 느려서 따로 줄에서) */
    private void pushMoments() {
        new Thread(() -> {
            // "none" = 이 판에는 그 기능이 없다(구글 플레이용 판, 2026-11-19). 사이트가 그 줄을 숨긴다
            String photo = !Moments.declared(this, Moments.photoPerm()) ? "none" : !Moments.photoOn(this) ? "off" : Moments.photoAllowed(this) ? "on" : "need";
            String ex;
            if (!Moments.declared(this, "android.permission.health.READ_EXERCISE")) ex = "none";
            else if (!HcReader.available(this)) ex = "no_hc";
            else if (!Moments.exOn(this)) ex = "off";
            else if (!HcReader.granted(this)) ex = HcReader.backgroundSupported(this) ? "need" : "no_bg";
            else ex = "on";
            boolean exact = Build.VERSION.SDK_INT < 31 || getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms();
            boolean fsi = Build.VERSION.SDK_INT < 34 || getSystemService(android.app.NotificationManager.class).canUseFullScreenIntent();
            // 꺼진 것 안내(2026-11-16 룡 「꺼 둔 것도 안내하고 켜지도록」): 알림·배터리·취침 잠금
            boolean notif = getSystemService(android.app.NotificationManager.class).areNotificationsEnabled();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            boolean battery = pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
            boolean acc = lockReady();
            // 건강 기록 앱인데 라이프 RPG도 깔려 있으면 묻기는 라이프 RPG가 맡는다(두 번 묻지 않게)
            boolean twin = "app.liferoom".equals(getPackageName()) && installed("app.liferpg");
            String json = "{\"photo\":\"" + photo + "\",\"ex\":\"" + ex + "\",\"times\":\"" + Moments.times(this)
                    + "\",\"alarm\":\"" + WakeAlarm.time(this) + "\",\"lastAlarm\":\"" + WakeAlarm.lastTime(this)
                    + "\",\"fsi\":" + fsi + ",\"exact\":" + exact + ",\"ringing\":" + WakeAlarm.ringing(this)
                    + ",\"notif\":" + notif + ",\"battery\":" + battery + ",\"acc\":" + acc + ",\"twin\":" + twin + "}";
            js("window.lifeMoments && lifeMoments(" + json + ")");
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == NOTIF_PERM) { pushMoments(); return; }
        if (req != PHOTO_PERM) return;
        if (Moments.photoAllowed(this)) Moments.setPhotoOn(this, true);
        pushMoments();
    }

    // 취침 잠금(2026-11-06 룡 결정: 접근성 서비스로 「확실하게」). 실제 막기는 SleepLock이 한다.
    // 여기서는 잠금 켜기·끄기와, 접근성이 꺼져 있을 때 설정으로 보내는 일만.
    private boolean accessibilityOn() {
        try {
            String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return on != null && on.contains(getPackageName() + "/");
        } catch (Exception e) { return false; }
    }

    // 설정에 켜져 있고, 서비스가 실제로 폰과 이어져 있어야 잠긴다(2026-11-08: 켜짐으로 보이는데 안 잠긴 일)
    protected boolean lockReady() { return accessibilityOn() && SleepLock.running(); }

    private AlertDialog accDialog;

    protected void askAccessibility(boolean force) {
        if (lockReady()) return;
        if (accDialog != null && accDialog.isShowing()) return;
        if (!force && getSharedPreferences(SleepLock.PREFS, MODE_PRIVATE).getBoolean("acc_asked", false)) return;
        getSharedPreferences(SleepLock.PREFS, MODE_PRIVATE).edit().putBoolean("acc_asked", true).apply();
        String a = appLabel();
        accDialog = new AlertDialog.Builder(this)
                .setTitle("취침 잠금을 켜 주세요 (한 번만)")
                .setMessage("「잘게요」 뒤 일어날 때까지 다른 앱을 못 쓰게 하려면 이 순서로 한 번만 해 주세요.\n\n① 접근성 → 설치된 앱 → " + a + " 눌러 보기 (「제한된 설정」 창이 뜨면 확인)\n② 앱 정보 → 오른쪽 위 ⋮ → 「제한된 설정 허용」 (①을 해야 ⋮에 나와요)\n③ 다시 접근성 → " + a + " → 켜기\n\n이미 켜져 있는데 안 잠기면: 접근성 → " + a + " 항목을 껐다가 다시 켜 주세요.")
                .setPositiveButton("② 앱 정보", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                })
                .setNeutralButton("①③ 접근성", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    protected void js(String code) { runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    private class Bridge {
        @JavascriptInterface
        public void lock() {
            // 취침을 누른 날은 밤 10시 「오늘 하루 수고하셨어요」를 울리지 않는다(2026-11-25 룡 「취침수면은 알람 안 남도록」)
            Moments.p(HealthScreen.this).edit().putString("m_bed_day", Moments.today()).apply();
            runOnUiThread(() -> {
                if (!lockReady()) {
                    js("window.lifeLockFailed && lifeLockFailed()");
                    askAccessibility(true);
                    return;
                }
                SleepLock.setLocked(HealthScreen.this, true);
            });
        }

        @JavascriptInterface
        public void unlock() { SleepLock.setLocked(HealthScreen.this, false); }

        @JavascriptInterface
        public boolean isLocked() { return lockReady() && SleepLock.isLocked(HealthScreen.this); }

        @JavascriptInterface
        public void openPinSettings() { runOnUiThread(() -> askAccessibility(true)); }

        // 사이트 안내 글에 쓸 앱 이름(건강 기록 / 라이프 RPG)
        @JavascriptInterface
        public String appName() { return appLabel(); }

        // ---- 운동 인증 카톡방에 올리기(2026-11-24 룡): 폰 공유 창만 연다. 방 고르기·보내기는 본인 손 ----
        /** 사진 한 장(사진 + 흰 띠, JPEG base64)을 공유 창으로. 파일은 캐시 cam/ 폴더(두 앱 FileProvider 모두 허용) */
        @JavascriptInterface
        public void shareImage(String b64, String text) {
            runOnUiThread(() -> {
                try {
                    byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                    if (bytes.length > 8_000_000) { js("window.toast && toast('사진이 너무 커서 올리지 못했어요.')"); return; }
                    File dir = new File(getCacheDir(), "cam");
                    dir.mkdirs();
                    File f = new File(dir, "share.jpg");
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { out.write(bytes); }
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(HealthScreen.this, getPackageName() + ".files", f);
                    Intent send = new Intent(Intent.ACTION_SEND).setType("image/jpeg")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Intent chooser = Intent.createChooser(send, "카톡방에 올리기");
                    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(chooser);
                } catch (Exception e) {
                    js("window.toast && toast('올리지 못했어요. 다시 눌러 주세요.')");
                }
            });
        }

        /** 글만(사진 없을 때) 공유 창으로 */
        @JavascriptInterface
        public void shareText(String text) {
            runOnUiThread(() -> {
                try {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(send, "카톡방에 올리기"));
                } catch (Exception e) {
                    js("window.toast && toast('올리지 못했어요. 다시 눌러 주세요.')");
                }
            });
        }

        // ---- 그 순간에 먼저 묻기(2026-11-16 룡) ----
        /** 로그인 열쇠: 알림 버튼이 앱을 열지 않고 저장할 때 쓴다 */
        @JavascriptInterface
        public void setToken(String t) { Moments.setToken(HealthScreen.this, t); }

        /** 오늘 적은 끼니("breakfast,lunch"): 적은 끼니는 안 묻는다 */
        @JavascriptInterface
        public void setMeals(String day, String csv) { Moments.setMeals(HealthScreen.this, day, csv); }

        /** 지금 상태를 lifeMoments(json)으로 돌려준다 */
        @JavascriptInterface
        public void moments() { pushMoments(); }

        @JavascriptInterface
        public void setPhoto(boolean on) {
            runOnUiThread(() -> {
                if (on && !Moments.declared(HealthScreen.this, Moments.photoPerm())) { pushMoments(); return; }
                if (on && !Moments.photoAllowed(HealthScreen.this)) {
                    requestPermissions(new String[]{Moments.photoPerm()}, PHOTO_PERM);
                    return;
                }
                Moments.setPhotoOn(HealthScreen.this, on);
                pushMoments();
            });
        }

        @JavascriptInterface
        public void setTimes(String v) { Moments.setTimes(HealthScreen.this, v); pushMoments(); }

        @JavascriptInterface
        public void setEx(boolean on) {
            if (!on) { Moments.setExOn(HealthScreen.this, false); pushMoments(); return; }
            if (!Moments.declared(HealthScreen.this, "android.permission.health.READ_EXERCISE")) { pushMoments(); return; }
            new Thread(() -> {
                if (!HcReader.available(HealthScreen.this)) { pushMoments(); return; }
                // 켜기를 누르면 먼저 켠다(2026-11-18). 허락이 다 와야 켜던 때는 「백그라운드」 하나만 빠져도
                // 「꺼짐」으로만 보여, 무엇이 모자란지(허락 필요·Health Connect 업데이트) 화면에 안 나왔다
                Moments.setExOn(HealthScreen.this, true);
                if (HcReader.granted(HealthScreen.this)) { pushMoments(); return; }
                runOnUiThread(() -> {
                    try { hcAskedAt = System.currentTimeMillis(); startActivityForResult(HcReader.permissionIntent(HealthScreen.this), HC_REQ); }
                    catch (Exception e) { openHcSettings(); }
                });
            }).start();
        }

        /** 「운동」·「백그라운드 액세스」를 직접 켜는 Health Connect 화면 */
        @JavascriptInterface
        public void openHcSettings() { runOnUiThread(HealthScreen.this::openHcSettings); }

        /** Health Connect가 없을 때: 플레이 스토어에서 받기 */
        @JavascriptInterface
        public void getHealthConnect() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))); }
                catch (Exception ignored) {}
            });
        }

        // ---- 기상 알람 ----
        @JavascriptInterface
        public void setAlarm(String hm) { WakeAlarm.set(HealthScreen.this, hm); }

        @JavascriptInterface
        public void cancelAlarm() { WakeAlarm.cancel(HealthScreen.this); }

        /** 「일어났어요」: 울리는 알람을 멈추고, 아직 안 울린 그날 알람도 지운다 */
        @JavascriptInterface
        public void wakeUp() { WakeAlarm.stop(HealthScreen.this); WakeAlarm.cancel(HealthScreen.this); }

        /** 알림 켜기: 처음이면 허락 창, 이미 거절했으면 이 앱의 알림 설정 */
        @JavascriptInterface
        public void askNotif() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
                        && !getSharedPreferences("moments", MODE_PRIVATE).getBoolean("notif_asked", false)) {
                    getSharedPreferences("moments", MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply();
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIF_PERM);
                    return;
                }
                if (Build.VERSION.SDK_INT >= 33 && shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIF_PERM);
                    return;
                }
                try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
                catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                }
            });
        }

        /** 배터리 「제한 없음」 창 */
        @JavascriptInterface
        public void openBattery() {
            runOnUiThread(() -> {
                try {
                    // 구글 플레이용 판은 바로 묻는 창을 못 쓴다 → 앱 정보 화면으로 간다(2026-11-19)
                    if (!Moments.declared(HealthScreen.this, "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) throw new IllegalStateException();
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                }
            });
        }

        /** 안드로이드 12+: 기상 알람을 제시간에 울리는 허락(구글 플레이용 판은 사용자가 직접 켠다, 2026-11-19) */
        @JavascriptInterface
        public void openExactAlarm() {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT < 31) return;
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                }
            });
        }

        /** 안드로이드 14+: 잠금 화면 위에 알람 화면을 띄우는 허락 */
        @JavascriptInterface
        public void openAlarmSettings() {
            runOnUiThread(() -> {
                String act = Build.VERSION.SDK_INT >= 34 ? Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT : Settings.ACTION_APPLICATION_DETAILS_SETTINGS;
                try { startActivity(new Intent(act, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                }
            });
        }
    }

    private long hcAskedAt = 0;

    /** Health Connect의 이 앱 권한 화면(안 열리면 다음 길로) */
    private void openHcSettings() {
        for (Intent i : HcReader.settingsIntents(this)) {
            try { startActivity(i); return; } catch (Exception ignored) {}
        }
        getHealthConnectStore();
    }

    private void getHealthConnectStore() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))); }
        catch (Exception ignored) {}
    }

    private Intent cameraIntent() {
        try {
            File dir = new File(getCacheDir(), "cam");
            dir.mkdirs();
            cameraFile = new File(dir, "photo.jpg");
            if (cameraFile.exists()) cameraFile.delete();
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", cameraFile);
            return new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            return null;
        }
    }

    private long pausedAt = 0;

    @Override
    protected void onPause() { super.onPause(); pausedAt = System.currentTimeMillis(); }

    @Override
    protected void onResume() {
        super.onResume();
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > 10 * 60 * 1000 && web != null) web.reload();
        else if (pausedAt > 0) pushMoments(); // 설정에서 켜고 돌아오면 안내 칸을 바로 고친다
        // 「알람 및 리마인더」를 켜고 돌아오면, 이미 맞춘 알람을 정확한 알람으로 다시 건다(2026-11-19)
        if (pausedAt > 0) WakeAlarm.rearm(this);
    }

    // 배터리 「제한 없음」(2026-11-08 룡: 늘 떠 있어야 하는 앱은 업데이트 흐름에 넣는다).
    // 배터리 제한이 걸리면 폰이 잠금 서비스를 재워 「잘게요」가 풀린다. 앱을 열 때마다 한 번 묻는다
    private static boolean batteryAsked = false;

    protected void askBattery() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (isFinishing() || batteryAsked || SleepLock.isLocked(this)) return;
        if (accDialog != null && accDialog.isShowing()) return;
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        batteryAsked = true;
        // 구글 플레이용 판은 바로 묻는 창을 못 쓴다 → 앱 정보 화면으로 안내한다(2026-11-19)
        boolean direct = Moments.declared(this, "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
        new AlertDialog.Builder(this)
                .setTitle("배터리 「제한 없음」으로 해 주세요")
                .setMessage(direct
                        ? "배터리 제한이 걸려 있으면 폰이 밤에 취침 잠금을 꺼 버려요.\n\n「켜기」를 누르고 다음 창에서 「허용」을 눌러 주세요.\n(창이 안 뜨면: 앱 정보 → 배터리 → 「제한 없음」)"
                        : "배터리 제한이 걸려 있으면 폰이 밤에 취침 잠금을 꺼 버려요.\n\n「켜기」를 누르면 앱 정보 화면이 열려요. 「배터리」에서 「제한 없음」을 골라 주세요.")
                .setPositiveButton("켜기", (d, w) -> {
                    try {
                        if (!direct) throw new IllegalStateException();
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                    }
                })
                .setNegativeButton("나중에", null)
                .show();
        android.util.Log.i("LifeRoom", "battery dialog shown"); // 가상 폰 시험이 이 줄을 본다
    }

    // 뒤로 가기: 사이트의 appBack(열린 창 → 직전 화면)을 먼저. 더 갈 데가 없으면 닫기. 자는 중에는 안 나간다.
    // (2026-11-11 룡 「만든 앱들 뒤로 가기 하면 꺼지는 게 아니라 전 단계로」)
    @Override
    public void onBackPressed() {
        if (SleepLock.isLocked(this)) return;
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("(function(){try{return window.appBack?appBack():false}catch(e){return false}})()", r -> {
            if ("true".equals(r)) return;
            HealthScreen.super.onBackPressed();
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == HC_REQ) {
            // 허락 창이 2.5초 안에 그냥 닫혔으면 창이 막힌 것(두 번 거절한 폰). 권한 화면을 바로 연다(2026-11-18 룡 「운동 묻기 버튼이 작동 안 해」)
            boolean blocked = System.currentTimeMillis() - hcAskedAt < 2500;
            new Thread(() -> {
                boolean ok = HcReader.granted(this);
                if (ok) Moments.setExOn(this, true);
                if (!ok && blocked) runOnUiThread(this::openHcSettings);
                pushMoments();
            }).start();
            return;
        }
        if (req != PICK || pickCallback == null) return;
        Uri[] result = null;
        if (res == RESULT_OK) {
            if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (cameraFile != null && cameraFile.length() > 0) {
                result = new Uri[]{androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", cameraFile)};
            }
        }
        pickCallback.onReceiveValue(result);
        pickCallback = null;
    }
}
