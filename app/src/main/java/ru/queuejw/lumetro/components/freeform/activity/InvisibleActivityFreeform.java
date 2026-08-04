package ru.queuejw.lumetro.components.freeform.activity;

import android.app.Activity;
import android.app.ActivityOptions;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

import ru.queuejw.lumetro.components.freeform.helper.FreeformHackHelper;

public class InvisibleActivityFreeform extends Activity {

    private boolean shouldFinish = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FreeformHackHelper helper = FreeformHackHelper.getInstance();

        // 如果已激活，立即无动画关闭
        if (helper.isFreeformHackActive()) {
            shouldFinish = true;
            overridePendingTransition(0, 0);
            super.finish();
            return;
        }

        // 窗口完全透明，不可触摸，不可聚焦
        Window window = getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.alpha = 0f;
        params.dimAmount = 0f;
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        params.x = -9999;
        params.y = -9999;
        params.width = 1;
        params.height = 1;
        window.setAttributes(params);

        // 激活自由窗口状态
        helper.setFreeformHackActive(true);
        helper.setInFreeformWorkspace(true);

        // 无动画
        overridePendingTransition(0, 0);

        // 立即 finish，不留在界面上
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) {
                super.finish();
            }
        }, 50);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!shouldFinish) {
            FreeformHackHelper.getInstance().setInFreeformWorkspace(true);
        }
    }

    @Override
    public void finish() {
        // 重写，避免被意外调用
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (shouldFinish) return;
        // 不在 onDestroy 中重置状态
    }
}
