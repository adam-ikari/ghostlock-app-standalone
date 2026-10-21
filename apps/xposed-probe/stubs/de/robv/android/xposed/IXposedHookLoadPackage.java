/*
 * 编译期用的 Xposed 传统 API 桩（compile only，不进 APK）。
 * 真机上这些类由框架（Vector/LSPosed）注入到 app 的 classloader 提供。
 */
package de.robv.android.xposed;

public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable;
}
