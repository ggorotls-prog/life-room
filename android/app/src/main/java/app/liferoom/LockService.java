package app.liferoom;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

// 취침 잠금(2026-11-06 룡 결정: 「확실하게」 접근성 서비스로).
// 「잘게요」 뒤에는 다른 앱이 화면에 올라오는 순간 이 앱을 다시 앞으로 끌어온다. 「일어났어요」나 「해제하기」(사유 기록)로만 풀린다.
// 전화 받기·알람·시스템 화면은 그대로 둔다. 혹시 앱이 잘못돼도 12시간이 지나면 저절로 풀린다.
public class LockService extends AccessibilityService {
    static final String PREFS = "life";
    static final long MAX_LOCK_MS = 12L * 60 * 60 * 1000;

    private static final String[] ALLOW = {
            "com.android.systemui",                 // 잠금 화면·알림창·전원 메뉴
            "com.samsung.android.incallui",         // 삼성 통화 화면
            "com.android.incallui",                 // 통화 화면
            "com.google.android.dialer",            // 구글 전화(통화 화면 포함)
            "com.samsung.android.dialer",           // 삼성 전화
            "com.android.server.telecom",           // 통화 연결
            "com.sec.android.app.clockpackage",     // 삼성 시계(알람)
            "com.google.android.deskclock",         // 구글 시계(알람)
            "com.android.deskclock",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "android",
    };

    static boolean isLocked(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long since = p.getLong("locked_at", 0);
        if (since == 0) return false;
        if (System.currentTimeMillis() - since > MAX_LOCK_MS) { setLocked(c, false); return false; }
        return true;
    }

    static void setLocked(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("locked_at", on ? System.currentTimeMillis() : 0).apply();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        if (!isLocked(this)) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName())) return;
        for (String a : ALLOW) if (pkg.equals(a)) return;
        if (isKeyboard(pkg)) return; // 긴급 해제 사유를 적을 때 키보드가 뜬다(2026-11-07)
        // 다른 앱이 올라왔다: 이 앱을 다시 앞으로
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private boolean isKeyboard(String pkg) {
        try {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            for (InputMethodInfo i : imm.getEnabledInputMethodList()) if (pkg.equals(i.getPackageName())) return true;
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public void onInterrupt() {}
}
