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
    private static final int PICK = 7;

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
                if (Uri.parse(BASE).getHost().equals(req.getUrl().getHost())) return false;
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
        new AlertDialog.Builder(this)
                .setTitle("배터리 「제한 없음」으로 해 주세요")
                .setMessage("배터리 제한이 걸려 있으면 폰이 밤에 취침 잠금을 꺼 버려요.\n\n「켜기」를 누르고 다음 창에서 「허용」을 눌러 주세요.\n(창이 안 뜨면: 앱 정보 → 배터리 → 「제한 없음」)")
                .setPositiveButton("켜기", (d, w) -> {
                    try {
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
