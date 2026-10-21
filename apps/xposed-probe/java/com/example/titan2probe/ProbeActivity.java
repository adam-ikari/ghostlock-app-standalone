package com.example.titan2probe;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** 探针界面：显示 Xposed hook 有没有生效。 */
public class ProbeActivity extends Activity {

    private TextView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new TextView(this);
        view.setPadding(32, 48, 32, 32);
        view.setTextSize(18f);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.START | Gravity.TOP);
        view.setBackgroundColor(0xFF101418);
        setContentView(view);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        File marker = new File(getFilesDir(), "hook_marker");
        String status;
        if (!marker.exists()) {
            status = "状态：hook 未触发\n\n（模块没被加载，或本 app 不在模块作用域里）";
        } else {
            try {
                status = "状态：hook 生效\n\n" + new String(Files.readAllBytes(marker.toPath()),
                        StandardCharsets.UTF_8);
            } catch (Throwable t) {
                status = "状态：读到标记但读不出来：" + t;
            }
        }
        view.setText("Titan 2 Xposed 探针\n\n" + status
                + "\n\n内核：" + android.os.Build.VERSION.RELEASE);
    }
}
