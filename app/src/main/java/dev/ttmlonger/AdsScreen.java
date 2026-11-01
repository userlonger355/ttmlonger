package dev.ttmlonger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «Реклама и LIVE»: автоскрытие рекламы в ленте и скрытие кнопки LIVE. Работает сразу, перезапуск
 * TikTok не нужен: реклама убирается из тех страниц ленты, что приходят после включения.
 */
final class AdsScreen extends Screen {
    private TextView state;

    private AdsScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new AdsScreen(act).open("Реклама и LIVE",
                "Мод убирает рекламные ролики из ленты ещё до показа и прячет кнопку LIVE в левом верхнем "
                        + "углу главной.");
    }

    static String menuSummary() {
        return Prefs.hideAds || Prefs.hideLiveButton || Prefs.hideLiveRooms ? "Вкл" : "Выкл";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("Реклама"));
        body.addView(toggleRow(Icons.EYE_OFF, "Скрывать рекламу в ленте",
                "Рекламные ролики не попадают в ленту. Действует на страницы ленты, которые придут после "
                        + "включения: обновите ленту, чтобы увидеть результат",
                Prefs.hideAds, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideAds(on);
                        changed();
                    }
                }));

        body.addView(ui.header("LIVE"));
        body.addView(toggleRow(Icons.EYE_OFF, "Скрыть кнопку LIVE",
                "Кнопка в левом верхнем углу главной прячется, остальная шапка не сдвигается",
                Prefs.hideLiveButton, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideLiveButton(on);
                        LiveButton.kick();
                        changed();
                    }
                }));
        body.addView(toggleRow(-1, "Убирать эфиры из ленты",
                "Прямые эфиры, которые TikTok подмешивает среди видео. Ваши собственные посты не трогаются",
                Prefs.hideLiveRooms, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideLiveRooms(on);
                        changed();
                    }
                }));

        body.addView(ui.header("Состояние"));
        state = ui.text("", 14f, Ui.INK, false);
        state.setLineSpacing(0f, 1.15f);
        state.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        body.addView(state, ui.matchWrap());

        body.addView(ui.row(Icons.REFRESH, "Обновить состояние", "Кнопку LIVE мод ищет раз в секунду, пока открыт "
                + "TikTok", null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LiveButton.kick();
                refresh();
                refreshLater();
            }
        }));
        body.addView(ui.row(Icons.COPY, "Скопировать сведения", "Если реклама или кнопка не пропали, пришлите это "
                + "разработчику (имён и текстов с экрана в сведениях нет)", null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        }));
        body.addView(ui.note("Часть рекламы отключает и «Набор флагов мода» (нужен перезапуск TikTok). "
                + "Эта настройка работает поверх него и перезапуска не требует."));
        refresh();
    }

    private void changed() {
        refresh();
    }

    private void refresh() {
        state.setText(AdHooks.status() + "\n\n" + LiveButton.status());
    }

    private void refreshLater() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (dialog != null && dialog.isShowing()) refresh();
            }
        }, 900L);
    }

    private void copy() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            String text = "TTMLonger " + Targets.VERSION + " · Android " + android.os.Build.VERSION.SDK_INT + "\n"
                    + AdHooks.details() + "\n\n" + LiveButton.details();
            cm.setPrimaryClip(ClipData.newPlainText("TTMLonger", text));
            Toasts.show(act, "Скопировано. Вставьте в сообщение разработчику");
        } catch (Throwable t) {
            Toasts.show(act, "Не удалось скопировать");
        }
    }
}
