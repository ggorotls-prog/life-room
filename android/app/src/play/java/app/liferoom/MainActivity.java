package app.liferoom;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;

// 건강 기록 앱의 구글 플레이용 판(2026-11-19). 화면·잠금·알람은 원본 한 벌 healthkit 그대로.
// 직접 설치용 판(src/direct)과 다른 점: 스스로 업데이트가 없다(플레이스토어가 업데이트한다).
public class MainActivity extends app.recordkit.health.HealthScreen {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Reminder.schedule(this);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 자는 중(잠금)에는 묻지 않는다: 설정 화면이 잠금에 막힌다
        if (LockService.isLocked(this)) return;
        askBattery();
    }
}
