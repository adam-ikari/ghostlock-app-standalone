package de.robv.android.xposed;

public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
        public String processName;
    }
}
