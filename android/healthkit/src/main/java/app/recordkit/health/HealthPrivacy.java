package app.recordkit.health;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

// Health Connect가 「이 앱이 데이터를 어떻게 쓰나」를 보여 줄 화면(이 화면이 있어야 허락 창이 열린다).
public class HealthPrivacy extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView t = new TextView(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        t.setPadding(pad, pad * 2, pad, pad);
        t.setTextSize(17);
        t.setTextColor(Color.parseColor("#1f1f1d"));
        t.setText("건강 기록이 Health Connect에서 읽는 것\n\n"
                + "• 운동 기록(종류·시작·끝 시각)만 읽어요. 걸음 수·심박·몸무게는 읽지 않아요.\n"
                + "• 새 운동이 보이면 「걷기 20분, 운동으로 적을까요?」 알림만 띄워요. 저절로 저장하지 않아요.\n"
                + "• 「적기」를 누른 운동만 「운동 종류 + 분」 한 줄로 건강 기록에 올라가요. 우리 조 사람들이 같이 봐요.\n"
                + "• Health Connect에 쓰지 않아요.\n"
                + "• 끄려면: 건강 기록 ☰ → 설정 → 앱이 먼저 묻기, 또는 Health Connect 앱 권한에서 끄기.");
        ScrollView s = new ScrollView(this);
        s.setBackgroundColor(Color.parseColor("#f6f5f1"));
        s.addView(t);
        setContentView(s);
    }
}
