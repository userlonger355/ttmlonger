package dev.ttmlonger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «Невидимка»: не показывать другим, что вы печатаете, и не сообщать серверу, что вы в сети. Просмотры
 * профиля отдельным запросом не уходят, поэтому спрятать их модом нельзя: экран говорит об этом прямо.
 */
final class InvisibleScreen extends Screen {
    private TextView status, preview;

    private InvisibleScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new InvisibleScreen(act).open("Невидимка",
                "Что TikTok сообщает другим про вас. Хуки ставятся при запуске, поэтому после обновления мода "
                        + "TikTok надо запустить заново; сами переключатели действуют сразу.");
    }

    static String menuSummary() {
        return Invisible.enabledCount() + " вкл";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(ui.header("Предпросмотр чата"));
        preview=ui.text("",15,Ui.INK,false);
        preview.setPadding(ui.dp(18),ui.dp(16),ui.dp(18),ui.dp(16));
        preview.setBackground(ui.round(Ui.FIELD,18));
        LinearLayout.LayoutParams pp=ui.matchWrap();pp.setMargins(ui.dp(16),0,ui.dp(16),ui.dp(8));body.addView(preview,pp);
        body.addView(ui.header("Чаты"));
        if(ReadPrivacy.ready()) {
            body.addView(toggleRow(Icons.EYE_OFF,"Не отправлять статус прочтения",
                    "Экспериментально: пропускает распознанное тело запроса отметки прочтения. Возможны отличия синхронизации непрочитанных.",
                    ReadPrivacy.enabled(),on->{InterfaceHooks.set("read_private",on);refresh();}));
            if(ReadPrivacy.replyReady()) body.addView(toggleRow(-1,"Отмечать прочитанным при ответе",
                    "Разрешает следующий запрос прочтения того же чата после распознанной ручной отправки. Не воспроизводит старые запросы. Автоогоньки исключены на две минуты.",
                    InterfaceHooks.on("read_reply"),on->{InterfaceHooks.set("read_reply",on);refresh();}));
            else body.addView(ui.note("Разрешение прочтения при ответе: тело отправки сообщения не распознано."));
        } else body.addView(ui.note("Статус прочтения: классы запроса не распознаны в этой версии TikTok. Блокировка не включается."));
        body.addView(toggleRow(-1,"Галочки прочтения","В распознанном чате заменяет «Прочитано» на ✔️✔️ и «Отправлено» на ✔️. Статус сообщения не меняется.",
                InterfaceHooks.on("chat_ticks"),on->{InterfaceHooks.set("chat_ticks",on);refresh();}));
        body.addView(toggleRow(Icons.EYE_OFF, "Не показывать «печатает…»",
                "Собеседник не увидит, что вы набираете сообщение. Мод убирает тело запроса «печатает», и сервер "
                        + "получает пустой запрос",
                Prefs.hideTyping, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideTyping(on);
                        refresh();
                    }
                }));
        body.addView(toggleRow(Icons.EYE_OFF, "Не сообщать, что я в сети",
                "Мод пропускает отчёт TikTok об активности, и «в сети» у друзей может перестать обновляться. "
                        + "Сервер видит и само подключение к сообщениям: его мод не трогает, иначе сообщения "
                        + "перестали бы приходить",
                Prefs.hideOnline, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setHideOnline(on);
                        refresh();
                    }
                }));

        body.addView(ui.header("Состояние"));
        status = ui.text("", 14f, Ui.INK, false);
        status.setLineSpacing(0f, 1.15f);
        status.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        body.addView(status, ui.matchWrap());
        refresh();
        body.addView(ui.row(Icons.COPY, "Скопировать сведения", "Для разработчика: что мод нашёл и сколько раз сработал "
                + "(имён и сообщений там нет)", null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        }));

        body.addView(ui.header("Просмотры и истории"));
        body.addView(ui.note("Спрятать их модом нельзя: в коде TikTok нет отдельного отчёта о просмотре профиля, "
                + "сервер узнаёт о нём из самого запроса страницы. Это настройка самого TikTok («Просмотры профиля»: "
                + "по описанию TikTok, если выключить её, вас не покажут в чужих списках гостей, а свой список "
                + "вы тоже не увидите)."));
        body.addView(ui.note("Для историй, подборок, LIVE и просмотров подписок подтверждённого отдельного запроса пока нет. Не считаются анонимными."));
        body.addView(ui.header("Огоньки"));
        body.addView(ui.menuRow(Icons.FLAME,"Автопродление огоньков","Расписание, стикер и список чатов",StreakScreen.menuSummary(),v -> StreakScreen.show(act)));
    }

    private void refresh() {
        if (status != null) status.setText(Invisible.status()+"\n"+ReadPrivacy.status());
        if(preview!=null)preview.setText("Пример переписки\n\nСмотри, что я нашёл сегодня!\n21:14:08\n\n                     Ого, это очень красиво!\n                     21:14:32  "+(InterfaceHooks.on("chat_ticks")?"✔️✔️":"Прочитано")+"\n\n"+(Prefs.hideTyping?"Набор текста скрыт":"Набор текста обычный")+" · "+(ReadPrivacy.ready()&&ReadPrivacy.enabled()?"Блокировка прочтения включена":"Обычное прочтение"));
    }

    private void copy() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("TTMLonger", "TTMLonger " + Targets.VERSION + " · Android "
                    + android.os.Build.VERSION.SDK_INT + "\n" + Invisible.details()));
            Toasts.show(act, "Скопировано. Вставьте в сообщение разработчику");
        } catch (Throwable t) {
            Toasts.show(act, "Не удалось скопировать");
        }
    }
}
