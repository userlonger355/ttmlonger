package dev.ttmlonger;

import android.app.Activity;
import android.content.*;
import android.os.Build;
import android.view.View;
import android.widget.*;
import java.util.Locale;

/** Session-only developer entry. Reports contain counts, not account or message data. */
final class DeveloperScreen extends Screen {
    private static volatile boolean unlocked;
    private LinearLayout info;
    private TextView tests;
    static boolean unlocked(){return unlocked;}
    static void show(Activity a){unlocked=true;new DeveloperScreen(a).open("Для разработчиков","Диагностика и экспериментальные настройки TTMLonger");}
    private DeveloperScreen(Activity a){super(a);}
    @Override void build(LinearLayout body){
        LinearLayout hero=ui.panel(body);hero.setBackground(ui.expressiveSurface(24));hero.setPadding(ui.dp(18),ui.dp(16),ui.dp(18),ui.dp(16));
        hero.addView(ui.medium("TTMLonger · Developer",19,Ui.INK));
        hero.addView(ui.text(Targets.VERSION+" · Android "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT,13,Ui.MUTED,false));
        hero.addView(ui.text("Доступ открыт до перезапуска приложения",12,Ui.MUTED,false));
        body.addView(ui.header("Диагностика функций"));
        info=ui.panel(body);refresh();
        body.addView(ui.row(Icons.REFRESH,"Обновить показатели","Только чтение текущего состояния",null,false,v->refresh()));
        body.addView(ui.row(Icons.COPY,"Скопировать отчёт","Без имён, ID, сообщений и ссылок",null,false,v->copy()));
        body.addView(ui.header("Самопроверка"));
        LinearLayout checks=ui.panel(body);
        checks.addView(ui.row(Icons.CHECK,"Проверить логику мода","Время, поиск, контуры и расписание; без сетевых запросов",null,false,v->runChecks()));
        tests=ui.note("Тесты ещё не запускались");checks.addView(tests);
        body.addView(ui.header("Инструменты"));
        body.addView(ui.menuRow(Icons.FLAG,"Флаги A/B TikTok","Каталог, ручные переопределения и экспорт",null,v->FlagsScreen.show(act)));
        body.addView(ui.menuRow(Icons.SLIDERS,"Группы флагов","Экспериментальные возможности клиента",null,v->FlagGroupsScreen.show(act)));
        body.addView(ui.menuRow(Icons.DOWNLOAD,"Резервная копия","Сохранить или восстановить настройки",null,v->SettingsBackup.show(act)));
        body.addView(ui.row(Icons.REFRESH,"Проверить огоньки без отправки","Прочитать состояние известных чатов",null,false,v->{tests.setText("Проверка огоньков…");StreakRunner.check(()->{if(dialog.isShowing()){tests.setText("Проверка завершена. Откройте статистику огоньков для результата.");refresh();}});}));
        body.addView(ui.header("Восстановление"));
        body.addView(ui.row(Icons.FLAG,"Защита от сбоев",CrashGuard.tripped?"Активна · функции приостановлены":"Функции разрешены",null,true,v->guard()));
        body.addView(ui.row(Icons.REFRESH,"Перезапустить TikTok","Настройки и данные сохраняются",null,true,v->Restart.offer(act)));
        body.addView(ui.row(Icons.CLOSE,"Закрыть доступ разработчика","Снова потребуется восемь нажатий",null,false,v->{unlocked=false;dialog.dismiss();}));
        body.getViewTreeObserver().addOnWindowFocusChangeListener(f->{if(f)refresh();});
    }
    private String[][] snapshot(){
        StreakRunner.Overview o=StreakRunner.overview(StreakHooks.liveOwner());
        return new String[][]{
            {"Защита",CrashGuard.tripped?"Активна":"Не сработала"},
            {"Комментарии",CommentTools.diagnostics()},
            {"Репосты",RepostTimes.diagnostics()},
            {"Профиль",ProfileFacts.diagnostics()},
            {"Аватарки",AvatarContours.diagnostics()},
            {"Флаги","Всего: "+FlagStore.total()+" · прочитано: "+FlagStore.readThisSession()+" · изменено: "+FlagStore.overrideCount()},
            {"Огоньки",o==null?"Нет результата проверки":("Активные: "+o.active+" · серые: "+o.grey+" · к отправке: "+o.due+" · неизвестно: "+o.unknown)}
        };
    }
    private void refresh(){if(info==null)return;info.removeAllViews();String[][] rows=snapshot();for(int i=0;i<rows.length;i++){if(i>0)info.addView(ui.divider());info.addView(ui.row(-1,rows[i][0],rows[i][1],null,false,null));}}
    private void copy(){StringBuilder b=new StringBuilder("TTMLonger "+Targets.VERSION+"\nAndroid "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT+"\n");for(String[] row:snapshot())b.append(row[0]).append(": ").append(row[1]).append('\n');ClipboardManager cb=(ClipboardManager)act.getSystemService(Context.CLIPBOARD_SERVICE);if(cb!=null){cb.setPrimaryClip(ClipData.newPlainText("TTMLonger diagnostics",b.toString()));Toasts.show(act,"Отчёт скопирован");}}
    private void runChecks(){
        int total=0,passed=0;StringBuilder b=new StringBuilder();
        String[] names={"Ручное время","Недопустимое время","Unix-время в миллисекундах","Поиск с опечаткой","Форма: сохранение"};
        for(int i=0;i<names.length;i++){
            boolean ok=false;try{switch(i){
                case 0:ok=StreakTimeSheet.parseTime("23","59")==1439;break;
                case 1:ok=StreakTimeSheet.parseTime("24","00")<0;break;
                case 2:ok=VideoInfo.parseEventTime("1700000000000")==1700000000L;break;
                case 3:ok=SettingsSearch.fuzzy("точное время репоста","репсота");break;
                case 4:AvatarShape shape=AvatarShape.initial();ok=AvatarShape.decode(shape.encode()).encode().equals(shape.encode());break;
            }}catch(Throwable ignored){}total++;if(ok)passed++;b.append(ok?"✓ ":"✗ ").append(names[i]).append('\n');
        }
        tests.setText("Локальные тесты: "+passed+"/"+total+"\n"+b+"Тесты не подтверждают работу хуков на сервере TikTok.");
    }
    private void guard(){
        StreakSheet s=new StreakSheet(act,"Защита от сбоев");
        s.root.addView(s.note("Защита автоматически приостанавливает функции после нескольких неудачных запусков. Она остаётся включённой и после восстановления."));
        if(CrashGuard.tripped)s.button("Восстановить функции",true,()->{s.dialog.dismiss();CrashGuard.reenable();Restart.offer(act);});
        s.button("Закрыть",false,()->s.dialog.dismiss());s.show();
    }
}
