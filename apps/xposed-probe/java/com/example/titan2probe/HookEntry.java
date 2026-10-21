package com.example.titan2probe;

import java.io.File;
import java.io.FileOutputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_LoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 最小 Xposed 模块：证明「LSPosed 模块能被装上、能加载、能 hook」。
 *
 * 做法：hook 自己 app 的 Activity.onCreate（用 app 的 classLoader 拿到真正的类），
 * hook 成功后往 app 的 filesDir 写一个标记文件；界面读这个文件显示结果。
 */
public final class HookEntry implements IXposedHookLoadPackage {

    private static final String TARGET = "com.example.titan2probe";
    private static final String MARKER = "hook_marker";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!TARGET.equals(lp.packageName)) {
            return;
        }
        XposedBridge.log("[titan2-probe] handleLoadPackage " + lp.packageName);

        final Class<?> activity;
        try {
            activity = Class.forName(TARGET + ".ProbeActivity", false, lp.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("[titan2-probe] cannot resolve ProbeActivity: " + t);
            return;
        }

        XposedBridge.hookMethod(activity, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object self = param.thisObject;
                    File dir = (File) self.getClass().getMethod("getFilesDir").invoke(self);
                    File marker = new File(dir, MARKER);
                    FileOutputStream out = new FileOutputStream(marker);
                    out.write(("hook fired at " + System.currentTimeMillis()).getBytes("UTF-8"));
                    out.close();
                    XposedBridge.log("[titan2-probe] onCreate hooked -> " + marker);
                } catch (Throwable t) {
                    XposedBridge.log("[titan2-probe] hook body failed: " + t);
                }
            }
        });
    }
}
