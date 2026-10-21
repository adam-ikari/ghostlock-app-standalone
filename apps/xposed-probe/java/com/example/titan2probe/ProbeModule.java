package com.example.titan2probe;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

import android.os.Bundle;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 最小 Xposed 模块（新版 libxposed API，API 102）：证明「LSPosed 模块能被装上、能加载、能 hook」。
 *
 * 做法：hook 自己 app 的 Activity.onCreate（用 app 的 classLoader 拿到真正的类），
 * hook 成功后往 app 的 filesDir 写一个标记文件；界面读这个文件显示结果。
 *
 * 注意：Vector v2.2 把**传统** Xposed API（de.robv.android.xposed.*）做了重定位，
 * 按老 API 写的模块会被加载但抛 AbstractMethodError；所以这里用新版
 * io.github.libxposed.api。
 */
public final class ProbeModule extends XposedModule {

    private static final String TARGET = "com.example.titan2probe";
    private static final String MARKER = "hook_marker";
    private static final String TAG = "titan2-probe";

    public ProbeModule() {
        super();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName())) {
            return;
        }
        try {
            Class<?> activity = Class.forName(TARGET + ".ProbeActivity", false, param.getClassLoader());
            Method onCreate = activity.getDeclaredMethod("onCreate", Bundle.class);
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    File dir = (File) self.getClass().getMethod("getFilesDir").invoke(self);
                    File marker = new File(dir, MARKER);
                    try (FileOutputStream out = new FileOutputStream(marker)) {
                        out.write(("hook fired at " + System.currentTimeMillis()).getBytes("UTF-8"));
                    }
                    log(4, TAG, "onCreate hooked -> " + marker);
                } catch (Throwable t) {
                    log(6, TAG, "marker write failed", t);
                }
                return result;
            });
            log(4, TAG, "hook installed on ProbeActivity.onCreate");
        } catch (Throwable t) {
            log(6, TAG, "hook failed", t);
        }
    }
}
