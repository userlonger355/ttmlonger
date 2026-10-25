package dev.ttmlonger;

import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.util.Locale;

/**
 * Подмена региона. Приложение узнаёт регион по SIM-карте и сети, поэтому подменяем
 * TelephonyManager.getSimCountryIso, getNetworkCountryIso и SubscriptionInfo.getCountryIso.
 *
 * Не подменяется MCC/MNC оператора (getSimOperator, getNetworkOperator): если TikTok сверяет их
 * с кодом страны, расхождение возможно.
 */
final class RegionHooks {
    private static boolean localeHooked;

    private RegionHooks() {
    }

    /** Ставится рано, значения читаются из Prefs на каждом вызове. */
    static void installSim() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String r = Prefs.region;
                if (r.isEmpty() || CrashGuard.tripped) return;
                param.setResult(r.toLowerCase(Locale.ROOT));
            }
        };
        hookAll(TelephonyManager.class, "getSimCountryIso", hook);
        hookAll(TelephonyManager.class, "getNetworkCountryIso", hook);
        hookAll(SubscriptionInfo.class, "getCountryIso", hook);
    }

    /**
     * Если «Сохранять язык рекомендаций» выключено, регион подставляется и в страну локали
     * (язык остаётся прежним). Ставится при старте и только когда это нужно, чтобы не трогать
     * горячий метод Locale.getCountry без причины.
     */
    static synchronized void installLocale() {
        if (localeHooked || Prefs.keepLanguage || Prefs.region.isEmpty()) return;
        localeHooked = true;
        try {
            XposedBridge.hookAllMethods(Locale.class, "getCountry", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String r = Prefs.region;
                    if (r.isEmpty() || Prefs.keepLanguage || CrashGuard.tripped) return;
                    if (param.thisObject == Locale.getDefault()) param.setResult(r);
                }
            });
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }

    private static void hookAll(Class<?> c, String method, XC_MethodHook hook) {
        try {
            XposedBridge.hookAllMethods(c, method, hook);
        } catch (Throwable t) {
            FlagStore.log(t);
        }
    }
}
