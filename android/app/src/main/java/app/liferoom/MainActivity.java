package app.liferoom;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.app.AlertDialog;
import android.webkit.JavascriptInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;

// 생활개선방 앱(2026-11-01 사장님: 가족 안에서 앱으로). 앱 화면 = 사이트를 그대로 띄운다.
// 사이트를 고치면 앱도 바로 바뀐다. 앱은 사진 고르기·찍기, 아침·밤 알림, 취침 잠금(화면 고정)만 맡는다.
public class MainActivity extends Activity {
    static final String BASE = "https://ggorotls-prog.github.io/life-room/";
    private static final int PICK = 7;

    private WebView web;
    private ValueCallback<Uri[]> pickCallback;
    private File cameraFile;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#f7f4ee"));
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

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (Uri.parse(BASE).getHost().equals(req.getUrl().getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); } catch (Exception ignored) {}
                return true;
            }

            // 폰이 메모리가 모자라 화면을 끊어도 앱이 같이 꺼지지 않게 다시 연다
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                root.removeView(view);
                view.destroy();
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
        Reminder.schedule(this);
        askPinSetting(false);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    // 취침 잠금(2026-11-06 룡 결정): 「잘게요」를 누르면 앱을 화면에 고정해 「일어났어요」만 보인다.
    // 뒤로+최근 버튼을 길게 눌러 풀면 막지 않고 사이트에 알려 사유만 적는다. 설정에서 「앱 고정」을 켜 둬야 한다.
    private boolean wantLock = false, wasLocked = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (!wantLock) return;
            if (isPinned()) {
                wasLocked = true;
            } else if (wasLocked) {
                wantLock = false; wasLocked = false;
                js("window.lifeUnpinned && lifeUnpinned()");
                return;
            }
            handler.postDelayed(this, 1500);
        }
    };
    private final Runnable lockCheck = () -> {
        if (wantLock && !wasLocked && !isPinned()) {
            wantLock = false;
            js("window.lifeLockFailed && lifeLockFailed()");
        }
    };

    // 앱은 폰의 「앱 고정」 설정을 스스로 켤 수 없다(안드로이드가 막음). 대신 꺼져 있으면 앱을 열 때 설정 화면으로 보낸다.
    private boolean pinSettingOn() {
        try { return Settings.Secure.getInt(getContentResolver(), "lock_to_app_enabled", 0) == 1; }
        catch (Exception e) { return true; } // 읽지 못하는 폰이면 켜진 것으로 보고 넘어간다
    }

    private void askPinSetting(boolean force) {
        if (pinSettingOn()) return;
        if (!force && getSharedPreferences("life", MODE_PRIVATE).getBoolean("pin_asked", false)) return;
        getSharedPreferences("life", MODE_PRIVATE).edit().putBoolean("pin_asked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("앱 고정을 켜 주세요")
                .setMessage("「잘게요」를 누르면 일어날 때까지 폰이 이 앱에 고정됩니다. 그러려면 폰 설정에서 「앱 고정」을 한 번 켜야 해요.\n\n설정 > 보안 및 개인정보 보호 > 기타 보안 설정 > 앱 고정 (안 보이면 설정 검색창에 「앱 고정」)")
                .setPositiveButton("설정 열기", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (Exception ignored) {}
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    private boolean isPinned() {
        try {
            return getSystemService(ActivityManager.class).getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } catch (Exception e) { return false; }
    }

    private void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    private class Bridge {
        @JavascriptInterface
        public void lock() {
            runOnUiThread(() -> {
                if (!pinSettingOn()) { // 설정이 꺼져 있으면 고정을 시도하지 않고 바로 안내한다
                    js("window.lifeLockFailed && lifeLockFailed()");
                    askPinSetting(true);
                    return;
                }
                wantLock = true; wasLocked = false;
                try { startLockTask(); } catch (Exception ignored) {}
                handler.removeCallbacks(watch); handler.removeCallbacks(lockCheck);
                handler.postDelayed(watch, 1500);
                handler.postDelayed(lockCheck, 8000);
            });
        }

        @JavascriptInterface
        public void unlock() {
            runOnUiThread(() -> {
                wantLock = false; wasLocked = false;
                handler.removeCallbacks(watch); handler.removeCallbacks(lockCheck);
                try { stopLockTask(); } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public boolean isLocked() { return isPinned(); }

        @JavascriptInterface
        public void openPinSettings() {
            runOnUiThread(() -> {
                if (!pinSettingOn()) askPinSetting(true);
                else try { startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (Exception ignored) {}
            });
        }
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
