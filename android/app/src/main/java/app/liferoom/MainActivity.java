package app.liferoom;

import android.Manifest;
import android.app.Activity;
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
// 사이트를 고치면 앱도 바로 바뀐다. 앱은 사진 고르기·찍기, 아침·밤 알림, 취침 잠금(접근성 서비스 LockService)만 맡는다.
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
        askAccessibility(false);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    // 취침 잠금(2026-11-06 룡 결정: 접근성 서비스로 「확실하게」). 실제 막기는 LockService가 한다.
    // 여기서는 잠금 켜기·끄기와, 접근성이 꺼져 있을 때 설정으로 보내는 일만.
    private boolean accessibilityOn() {
        try {
            String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return on != null && on.contains(getPackageName() + "/");
        } catch (Exception e) { return false; }
    }

    private void askAccessibility(boolean force) {
        if (accessibilityOn()) return;
        if (!force && getSharedPreferences(LockService.PREFS, MODE_PRIVATE).getBoolean("acc_asked", false)) return;
        getSharedPreferences(LockService.PREFS, MODE_PRIVATE).edit().putBoolean("acc_asked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("취침 잠금을 켜 주세요 (한 번만)")
                .setMessage("「잘게요」 뒤 일어날 때까지 다른 앱을 못 쓰게 하려면 폰 설정 두 곳을 켜야 해요.\n\n① 앱 정보 → 오른쪽 위 ⋮ → 「제한된 설정 허용」\n② 접근성 → 설치된 앱 → 건강 기록 → 켜기\n\n①을 먼저 하지 않으면 ②가 흐리게 막혀 있어요.")
                .setPositiveButton("① 앱 정보", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
                })
                .setNeutralButton("② 접근성", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    private void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    private class Bridge {
        @JavascriptInterface
        public void lock() {
            runOnUiThread(() -> {
                if (!accessibilityOn()) {
                    js("window.lifeLockFailed && lifeLockFailed()");
                    askAccessibility(true);
                    return;
                }
                LockService.setLocked(MainActivity.this, true);
            });
        }

        @JavascriptInterface
        public void unlock() { LockService.setLocked(MainActivity.this, false); }

        @JavascriptInterface
        public boolean isLocked() { return accessibilityOn() && LockService.isLocked(MainActivity.this); }

        @JavascriptInterface
        public void openPinSettings() { runOnUiThread(() -> askAccessibility(true)); }
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
    public void onBackPressed() {
        if (LockService.isLocked(this)) return;
        super.onBackPressed();
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
