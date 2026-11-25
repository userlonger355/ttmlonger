package dev.ttmlonger;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Side-effect-free index of actual controls. Opening a hit navigates, never changes a preference. */
final class SettingsSearch {
    interface Open {void show(Activity a);}
    static final class Entry {
        final String page,path,title,words;final Open open;
        Entry(String p,String route,String t,String alias,Open o){page=p;path=route;title=t;words=normalize(t+" "+alias+" "+route);open=o;}
    }
    private final List<Entry> entries=new ArrayList<>();
    private void group(String page,String path,Open open,String... rows){
        for(String row:rows){int i=row.indexOf('|');String t=i<0?row:row.substring(0,i),alias=i<0?"":row.substring(i+1);entries.add(new Entry(page,path,t,alias,open));}
    }
    SettingsSearch(){
        group("Интерфейс и конфиденциальность","Интерфейс",InterfaceScreen::show,
          "Очищать ссылки TikTok|удалить метки отслеживания чистые ссылки",
          "Внешние ссылки в системном браузере","Ограниченный встроенный браузер","Открывать ссылки TikTok в приложении","Контакты и геопозиция",
          "Порядок верхних вкладок","Порядок нижних вкладок","Скрывать названия вкладок","Скрыть «Сообщество»","Скрыть «Друзья»","Скрыть «Для вас»|рекомендации",
          "Автор|скрыть кнопку аватар автора","Лайк|скрыть кнопку нравится","Комментарии|скрыть кнопку комментариев в ленте","Избранное|скрыть кнопку закладки","Поделиться|скрыть кнопку отправки",
          "Двойное нажатие|жест видео","Долгое нажатие|жест удержание настройки мода","Свайп влево|жест видео",
          "Двойное нажатие для лайка|подтверждение","Двойное нажатие для подписки","Двойное нажатие для репоста","Двойное нажатие для «Поделиться»","Сведения по нажатию на флаг");
        for(int i=0;i<CommentTools.KEYS.length;i++)group("Комментарии","Интерфейс › Комментарии",CommentToolsScreen::show,CommentTools.TITLES[i]+"|"+(i==0?"полный длинный текст еще":i==1?"копировать без ника юзернейма":i==2?"username никнейм":i==3?"дата секунды":i==4?"лайк двойной тап":"полный экран"));

        group("Фон комментариев","Внешний вид › Фон комментариев",CommentWallpaperScreen::show,"Выбрать картинку|фон комментариев обои фото","Свой фон|обои комментарии","Размытие|blur фон комментариев","Приглушение фона|затемнение яркость");
        group("Профиль","Интерфейс › Профиль",ProfileStyleScreen::show,
          "Точные счётчики|подписчики лайки полные числа","Показывать статус подписки|подписан на вас","Дата создания аккаунта|регистрация uid","Время последнего репоста|дата репоста профиль",
          "Копировать данные профиля|имя ник ссылка id описание","Перейти к дате|год месяц публикации архив","Значки TikTok You|бейджики","Управление бейджиками","Порядок видео|сортировка по лайкам просмотрам дате",
          "Просмотр заблокированного профиля","Прятать блокировку везде");
        for(String t:ProfileStyle.TITLES)group("Профиль","Интерфейс › Профиль",ProfileStyleScreen::show,"Скрыть: "+t);
        group("Внешний вид","Внешний вид",AppearanceScreen::show,
          "Форма аватарок|маски контуры звезда своя форма конструктор","Вращение аватарок|скорость вращения","Системная яркость","Эмодзи|emoji ttf otf","Цвет акцента|палитра цвет интерфейса","Менять цвет и в рисунках","Amoled-тема|черная тёмная тема","Шрифт|font","Значок приложения|иконка");
        group("Время и регион видео","Дата и регион",VideoInfoScreen::show,
          "Время публикации|дата видео","Точное время репоста|репост дата секунды","Вид времени","Секунды","Регион видео|страна флаг","Вид региона","Оформление","Жирность шрифта|толщина","Подложка|фон даты региона","Положение","Непрозрачность|прозрачность");
        group("Скачивание","Скачивание",DownloadScreen::show,
          "Без водяного знака|watermark чистое видео","Скачивать всегда|скачать истории фото","Кнопка «Сохранить через мод»|скачать музыку звук аудио видео","Качество видео|разрешение 1080p 720p","Расширенный лимит до 9000|офлайн видео","Сохранение стикеров|скачать стикер");
        group("Огоньки","Огоньки",StreakScreen::show,
          "Автопродление|серия streak огонек","Время отправки|расписание часы минуты вибрация ручное время","Стикер для отправки|обычные избранное лайк анимация","Снять выбор стикера","Кому продлевать|персональное индивидуальное время собеседник","Состояние серий|статистика ближайшая проверка расписание пауза","Предпросмотр отправки|видео стикер","Проверить огоньки","Отправить сейчас");
        group("Невидимка","Невидимка",InvisibleScreen::show,"Не отправлять статус прочтения|анонимно прочитано","Отмечать прочитанным при ответе","Галочки прочтения","Не показывать «печатает…»|набор текста","Не сообщать, что я в сети|онлайн");
        group("Реклама и LIVE","Реклама и LIVE",AdsScreen::show,"Пропускать рекламу|блокировать реклама adblock","Пропускать размеченные отрезки|спонсор sponsorblock","Рекламные видео","Рекламные карточки","AI-реклама","Скрыть кнопку LIVE|эфир","Убирать эфиры из ленты");
        group("Нижняя панель","Нижняя панель",GlassBarScreen::show,"Вид панели|жидкое стекло liquid glass","Стекло|пресеты","Мои профили|сохранить оформление","Непрозрачность","Насыщенность","Яркость","Преломление при касании","Блик по кромке","Хроматическая аберрация","Увеличение под линзой","Смещение значков","Фон под капсулой","Размытие фона","Жёсткость пружины","Затухание","Желейность","Надувание при касании","Линза","Сжатие панели","Кнопка «+»");
        group("Бейджики","Бейджики",BadgesScreen::show,"Бейджики от других","Импорт бейджиков","Выбрать аккаунт","Добавить по номеру");
        for(Unlocks.Group g:Unlocks.ALL)group("Группы флагов TikTok","Разработчик › Группы флагов",FlagGroupsScreen::show,g.title+"|"+g.id);
        group("Группы флагов TikTok","Разработчик › Группы флагов",FlagGroupsScreen::show,"Баннер профиля","Голосовые комментарии|аудио запись голос","Региональные функции чата");
    }
    static String normalize(String s){return s==null?"":s.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    static boolean matches(String words,String query){
        String q=normalize(query),w=normalize(words);if(q.isEmpty())return true;
        for(String token:q.split(" +"))if(!w.contains(token))return false;return true;
    }
    private static boolean near(String a,String b){
        if(a.length()<5||Math.abs(a.length()-b.length())>1)return false;
        int i=0,j=0,errors=0;while(i<a.length()&&j<b.length()){if(a.charAt(i)==b.charAt(j)){i++;j++;continue;}if(++errors>1)return false;if(a.length()==b.length()){if(i+1<a.length()&&j+1<b.length()&&a.charAt(i)==b.charAt(j+1)&&a.charAt(i+1)==b.charAt(j)){i+=2;j+=2;}else{i++;j++;}}else if(a.length()>b.length())i++;else j++;}return errors+(a.length()-i)+(b.length()-j)<=1;
    }
    static boolean fuzzy(String words,String query){String[] terms=normalize(words).split(" +");for(String token:normalize(query).split(" +")){boolean hit=false;for(String term:terms)if(term.contains(token)||near(token,term)){hit=true;break;}if(!hit)return false;}return true;}
    List<Entry> find(String q){
        String query=normalize(q);List<Entry> out=new ArrayList<>();if(query.isEmpty())return out;
        for(Entry e:entries)if((DeveloperScreen.unlocked()||!e.path.startsWith("Разработчик"))&&(matches(e.words,query)||fuzzy(e.words,query)))out.add(e);
        out.sort((a,b)->Integer.compare(rank(b,query),rank(a,query)));return out;
    }
    private static int rank(Entry e,String q){String title=normalize(e.title);return title.equals(q)?100:title.startsWith(q)?80:title.contains(q)?60:matches(title,q)?40:matches(e.words,q)?20:5;}
    private static final ThreadLocal<Entry> pending=new ThreadLocal<>();
    static void open(Activity a,Entry e){pending.set(e);try{e.open.show(a);}finally{pending.remove();}}
    static Entry take(String page){Entry e=pending.get();if(e==null||!e.page.equals(page))return null;pending.remove();return e;}
    private static TextView findTitle(View v,String target){
        if(v.getVisibility()!=View.VISIBLE)return null;
        if(v instanceof TextView){String s=normalize(((TextView)v).getText().toString());if(s.equals(target)||s.startsWith(target+" ")&&s.substring(target.length()).trim().matches("[0-9 ]+"))return (TextView)v;}
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView t=findTitle(g.getChildAt(i),target);if(t!=null)return t;}}return null;
    }
    static void reveal(Activity a,ScrollView scroll,ViewGroup body,Entry e){
        if(e==null)return;
        scroll.post(()->{
            if(!scroll.isAttachedToWindow())return;
            TextView t=findTitle(body,normalize(e.title));
            if(t==null){Toasts.show(a,"Пункт сейчас недоступен или находится в дополнительном разделе");return;}
            View target=t;
            for(ViewParent q=t.getParent();q instanceof ViewGroup&&q!=body;q=q.getParent()){ViewGroup g=(ViewGroup)q;if(g instanceof LinearLayout&&g.getWidth()>=body.getWidth()*.65f&&g.getHeight()<=Math.round(100*a.getResources().getDisplayMetrics().density)){target=g;break;}}
            Rect r=new Rect();target.getDrawingRect(r);scroll.offsetDescendantRectToMyCoords(target,r);scroll.smoothScrollTo(0,Math.max(0,r.top-scroll.getPaddingTop()-Math.round(16*a.getResources().getDisplayMetrics().density)));
            final View hit=target;GradientDrawable mark=new GradientDrawable();mark.setColor(Ui.alpha(Theme.accent(),0x24));mark.setCornerRadius(12*a.getResources().getDisplayMetrics().density);mark.setBounds(0,0,hit.getWidth(),hit.getHeight());hit.getOverlay().add(mark);
            hit.postDelayed(()->hit.getOverlay().remove(mark),1800);
        });
    }
}
