package app.liferoom;

// 취침 잠금. 내용은 원본 한 벌 healthkit의 SleepLock(라이프 RPG와 같이 씀, 2026-11-15 룡 「하나로 모으자」).
// 이 클래스 이름(app.liferoom.LockService)은 바꾸지 않는다: 폰 접근성 설정이 이 이름으로 켜져 있다.
public class LockService extends app.recordkit.health.SleepLock {
    static final String PREFS = app.recordkit.health.SleepLock.PREFS;

    @Override
    protected Class<? extends android.app.Activity> screen() { return MainActivity.class; }
}