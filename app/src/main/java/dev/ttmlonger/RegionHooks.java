package dev.ttmlonger;

import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.util.Locale;

/**
 * Подмена региона. Приложение узнаёт регион по SIM-карте и сети, поэтому подменяются:
 *
 * - код страны SIM и сети (getSimCountryIso, getNetworkCountryIso, SubscriptionInfo.getCountryIso);
 * - код и название оператора (getSimOperator, getNetworkOperator, ...Name) из {@link Carriers}:
 *   код сети должен совпадать со страной, иначе подмена видна по расхождению;
 * - роуминг выключается: SIM и сеть «из одной страны»;
 * - если в телефоне нет SIM, показывается карта в рабочем состоянии (состояние SIM, число слотов
 *   и подписок), иначе приложение, узнав «SIM нет», не спрашивает про страну вовсе. При настоящей
 *   SIM состояние не трогается: два ответа об одной и той же карте не должны расходиться.
 */
final class RegionHooks {
    private static boolean localeHooked;

    private RegionHooks() {
    }

    private static boolean active() {
        return !Prefs.region.isEmpty() && !CrashGuard.tripped;
    }

    /** Ставится рано, значения читаются из Prefs на каждом вызове. */
    static void installSim() {
        XC_MethodHook country = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!active()) return;
                param.setResult(Prefs.region.toLowerCase(Locale.ROOT));
            }
        };
        hookAll(TelephonyManager.class, "getSimCountryIso", country);
        hookAll(TelephonyManager.class, "getNetworkCountryIso", country);
        hookAll(SubscriptionInfo.class, "getCountryIso", country);

        // Код и название оператора: только для стран из таблицы, иначе остаются настоящие.
        XC_MethodHook code = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!active()) return;
                String v = Carriers.mccMnc(Prefs.region);
                if (v != null) param.setResult(v);
            }
        };
        hookAll(TelephonyManager.class, "getSimOperator", code);
        hookAll(TelephonyManager.class, "getNetworkOperator", code);
        XC_MethodHook name = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!active()) return;
                String v = Carriers.name(Prefs.region);
                if (v != null) param.setResult(v);
            }
        };
        hookAll(TelephonyManager.class, "getSimOperatorName", name);
        hookAll(TelephonyManager.class, "getNetworkOperatorName", name);

        // Роуминга нет: страна SIM и сети совпадают.
        hookAll(TelephonyManager.class, "isNetworkRoaming", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (active()) param.setResult(Boolean.FALSE);
            }
        });

        // Id оператора из списка Google для выдуманного оператора честно не подобрать:
        // «неизвестен» (-1), как у телефона, которому список ничего не ответил.
        XC_MethodHook carrierId = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (active() && Carriers.mccMnc(Prefs.region) != null) param.setResult(-1);
            }
        };
        hookAll(TelephonyManager.class, "getSimCarrierId", carrierId);
        hookAll(TelephonyManager.class, "getSimSpecificCarrierId", carrierId);

        installCard();
    }

    /** «Карта есть»: только когда настоящей нет (результат настоящего вызова уже известен). */
    private static void installCard() {
        // getSimState() и getSimState(slot): состояние первой карты.
        hookAll(TelephonyManager.class, "getSimState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!active() || !firstSlot(param)) return;
                Object r = param.getResult();
                if (r instanceof Integer && (Integer) r != TelephonyManager.SIM_STATE_READY) {
                    param.setResult(TelephonyManager.SIM_STATE_READY);
                }
            }
        });
        hookAll(TelephonyManager.class, "hasIccCard", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!active() || !firstSlot(param)) return;
                if (Boolean.FALSE.equals(param.getResult())) param.setResult(Boolean.TRUE);
            }
        });
        // Сколько карт: если приложению сказать «ноль», остальных вопросов оно не задаст.
        XC_MethodHook one = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!active()) return;
                Object r = param.getResult();
                if (r instanceof Integer && (Integer) r == 0) param.setResult(1);
            }
        };
        hookAll(TelephonyManager.class, "getPhoneCount", one);
        hookAll(TelephonyManager.class, "getActiveModemCount", one);
        hookAll(SubscriptionManager.class, "getActiveSubscriptionInfoCount", one);
        // Без карты телефон отвечает PHONE_TYPE_NONE; сети из таблицы все GSM.
        hookAll(TelephonyManager.class, "getPhoneType", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!active()) return;
                Object r = param.getResult();
                if (r instanceof Integer && (Integer) r == TelephonyManager.PHONE_TYPE_NONE) {
                    param.setResult(TelephonyManager.PHONE_TYPE_GSM);
                }
            }
        });
        // Подписка по умолчанию: без карты это «неверный id», и код, который его получил, обычно сдаётся.
        XC_MethodHook sub = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!active()) return;
                Object r = param.getResult();
                if (r instanceof Integer && (Integer) r < 0) param.setResult(1);
            }
        };
        hookAll(SubscriptionManager.class, "getDefaultSubscriptionId", sub);
        hookAll(SubscriptionManager.class, "getDefaultDataSubscriptionId", sub);
        hookAll(SubscriptionManager.class, "getDefaultVoiceSubscriptionId", sub);
        hookAll(SubscriptionManager.class, "getDefaultSmsSubscriptionId", sub);
        hookAll(SubscriptionManager.class, "getActiveDataSubscriptionId", sub);
    }

    /** Вызов без номера слота или про слот 0: вторую и дальнейшие карты не выдумываем. */
    private static boolean firstSlot(XC_MethodHook.MethodHookParam p) {
        return p.args == null || p.args.length == 0
                || !(p.args[0] instanceof Integer) || (Integer) p.args[0] == 0;
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
