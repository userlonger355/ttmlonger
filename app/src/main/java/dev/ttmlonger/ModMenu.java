package dev.ttmlonger;

import android.app.Activity;
import android.view.View;
import android.widget.ImageView;
import android.view.Gravity;
import android.widget.EditText;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Главная страница «Настройки мода». */
final class ModMenu extends Screen {
    private LinearLayout regionRow;
    private CardGrid grid;
    private LinearLayout interfaceRow;
    private final java.util.Map<View,String> searchWords=new java.util.IdentityHashMap<>();
    private EditText search;
    private TextView empty;
    private LinearLayout streakMenuRow;


    private ModMenu(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new ModMenu(act).open("Настройки мода", null);
    }

    @Override
    void build(LinearLayout page) {
        search = new EditText(act);
        search.setSingleLine(true);
        search.setHint("Поиск по настройкам мода");
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.MUTED);
        search.setBackground(ui.glassCard(0xff8562bd,18));
        search.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        LinearLayout.LayoutParams inset = ui.matchWrap();
        inset.setMargins(ui.dp(16), ui.dp(8), ui.dp(16), 0);
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        page.addView(search, inset);
        if (CrashGuard.tripped) {
            page.addView(ui.menuRow(Icons.FLAG, "Включена защита от сбоев",
                    "После нескольких неудачных запусков функции приостановлены. Нажмите для восстановления",
                    null, v -> recovery()));
        }
        LinearLayout body = section(page, "Основное");
        regionRow = ui.menuRow(Icons.GLOBE, "Регион", null, regionSummary(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RegionSheet.show(act, new Runnable() {
                    @Override
                    public void run() {
                        TextView val = Ui.valueOf(regionRow);
                        if (val != null) val.setText(regionSummary());
                    }
                });
            }
        });
        body.addView(regionRow);
        interfaceRow=ui.menuRow(Icons.SLIDERS, "Интерфейс и конфиденциальность",
                "Ссылки, вкладки и подтверждение действий", null, v -> InterfaceScreen.show(act));
        body.addView(interfaceRow);

        body.addView(ui.menuRow(Icons.PALETTE, "Внешний вид", "Акцент, Amoled-тема, шрифт, значок приложения", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AppearanceScreen.show(act);
                    }
                }));

        body.addView(ui.menuRow(Icons.SLIDERS, "Нижняя панель", "Оригинал или Liquid Glass: стеклянная капсула и линза",
                GlassBarScreen.menuSummary(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        GlassBarScreen.show(act);
                    }
                }));

        body = section(page, "Лента");
        body.addView(ui.menuRow(Icons.CLOCK, "Время и регион видео",
                "Точное время публикации, флаг и код страны над именем автора", VideoInfoScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        VideoInfoScreen.show(act);
                    }
                }));
        body.addView(ui.menuRow(Icons.CHECK, "Возможности",
                "Голосовые в ЛС, длинные репосты, хештеги без лимита, 9 фото, телесуфлёр",
                UnlocksScreen.menuSummary(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        UnlocksScreen.show(act);
                    }
                }));
        body.addView(ui.menuRow(Icons.FLAG, "Бейджики",
                "Картинка после имени аккаунта: в профиле, комментариях, ленте", BadgesScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        BadgesScreen.show(act);
                    }
                }));
        body.addView(ui.menuRow(Icons.EYE_OFF, "Невидимка",
                "Не показывать «печатает…» и «в сети»", InvisibleScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        InvisibleScreen.show(act);
                    }
                }));
        body.addView(ui.menuRow(Icons.EYE_OFF, "Реклама и LIVE",
                "Автоскрытие рекламы в ленте, скрытие кнопки LIVE", AdsScreen.menuSummary(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        AdsScreen.show(act);
                    }
                }));

        body = section(page, "Сохранение");
        body.addView(ui.menuRow(Icons.DOWNLOAD, "Скачивание", "Без водяного знака, качество, обход запрета", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        DownloadScreen.show(act);
                    }
                }));

        body.addView(ui.menuRow(Icons.VIDEO, "Видео и истории", "Сохранить то, что показывал TikTok", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        VideoScreen.show(act);
                    }
                }));

        body.addView(ui.menuRow(Icons.STICKER, "Стикеры", "Сохранить стикеры с экрана", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        StickerScreen.show(act);
                    }
                }));

        body.addView(ui.menuRow(Icons.SLIDERS,"Управление репостами","Просмотр собственных репостов и удаление выбранных",null,v -> RepostManager.show(act)));
        body.addView(ui.menuRow(Icons.EYE_OFF,"Скрытые авторы","Локальный список по UID; без блокировки аккаунта",null,v -> FeedMute.show(act)));
        body = section(page, "Личные сообщения");
        body.addView(ui.toggleRow(Icons.CLOCK,"Время каждого сообщения","Часы, минуты и секунды из модели сообщения",InterfaceHooks.on("message_clock"),on->{
            InterfaceHooks.set("message_clock",on);MessageClock.refresh();
        }));
        streakMenuRow = ui.menuRow(Icons.FLAME, "Огоньки", "Автопродление серии в чатах", StreakScreen.menuSummary(),
                v -> StreakScreen.show(act));
        body.addView(streakMenuRow);
        body.addView(toggleRow(Icons.MIC, "Голосовые и обои без региона",
                "Пробует включить функции чата, которые сервер скрывает по региону аккаунта. Экспериментально. "
                        + "Нужен перезапуск TikTok",
                Prefs.dmGate, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setDmGate(on);
                        Restart.offer(act);
                    }
                }));

        body = section(page, "Функции TikTok");
        body.addView(toggleRow(Icons.IMAGE, "Баннер профиля",
                "Отображение баннеров своего и чужих профилей включено по умолчанию. Анимация зависит от формата баннера и поддержки TikTok. После изменения перезапустите приложение.",
                FlagStore.allOn(Targets.BANNER_FLAGS), new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        FlagStore.setAll(Targets.BANNER_FLAGS, on);
                        Restart.offer(act);
                    }
                }));

        body.addView(toggleRow(Icons.MIC, "Голосовые комментарии",
                "Запись голосового комментария. Нужен перезапуск TikTok",
                FlagStore.allOn(Targets.VOICE_COMMENT_FLAGS), new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        FlagStore.setAll(Targets.VOICE_COMMENT_FLAGS, on);
                        Restart.offer(act);
                    }
                }));

        body.addView(toggleRow(Icons.FLAG, "Дополнительные возможности",
                "Дополнительные функции TikTok. Доступность зависит от аккаунта. Нужен перезапуск TikTok",
                Prefs.refSet, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setRefSet(on);
                        Restart.offer(act);
                    }
                }));

        body.addView(ui.menuRow(Icons.DOWNLOAD,"Резервная копия настроек","Экспорт и импорт JSON без данных аккаунта",null,v -> SettingsBackup.show(act)));
        body.addView(ui.menuRow(Icons.SLIDERS,"Плагины","Локальные JSON-команды · установка и управление",null,v -> CommandPlugins.show(act)));
        body.addView(ui.menuRow(Icons.SLIDERS, "Настройки разработчика TikTok",
                "Флаги A/B · расширенные настройки", null, v -> FlagsScreen.show(act)));

        grid.removeView(streakMenuRow);grid.removeView(interfaceRow);
        grid.addView(streakMenuRow,0);grid.addView(interfaceRow,1);
        for(int j=0;j<grid.getChildCount();j++)makeCard((LinearLayout)grid.getChildAt(j));
        empty=ui.note("По этому запросу настроек нет");page.addView(empty);empty.setVisibility(View.GONE);
        LinearLayout footer=new LinearLayout(act);
        footer.setGravity(Gravity.CENTER);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(ui.dp(12),ui.dp(20),ui.dp(12),ui.dp(16));
        TextView ver=ui.medium(Targets.VERSION,13,Ui.INK);
        TextView author=ui.medium("Автор: @Ueod5",13,Ui.INK);
        author.setPadding(ui.dp(12),ui.dp(8),ui.dp(8),ui.dp(8));
        author.setBackground(ui.ripple());
        Ui.pressMotion(author);
        author.setOnClickListener(v->{
            try{act.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://t.me/Ueod5")));}
            catch(android.content.ActivityNotFoundException e){Toasts.show(act,"Не удалось открыть Telegram");}
        });
        footer.addView(ver);
        footer.addView(author);
        page.addView(footer,ui.matchWrap());
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
        });
        page.getViewTreeObserver().addOnWindowFocusChangeListener(focus -> {
            if (!focus) return;
            TextView status=Ui.valueOf(streakMenuRow);if(status!=null)status.setText(StreakScreen.menuSummary());
            TextView region=Ui.valueOf(regionRow);if(region!=null)region.setText(regionSummary());
        });
        filter();
    }

    /** Reuse the original row and listener. Move its contents to a vertical card. */
    private void makeCard(LinearLayout row){
        TextView title=Ui.titleOf(row),sub=Ui.subtitleOf(row),value=Ui.valueOf(row);
        if(title==null)return;
        String original=title.getText().toString();
        searchWords.put(row,original+" "+(sub==null?"":sub.getText()));
        String[] wording=compact(original);
        title.setText(wording[0]);
        if(sub!=null)sub.setText(wording[1]);
        View icon=null,chevron=null;ModSwitch toggle=null;
        for(int j=0;j<row.getChildCount();j++){
            View child=row.getChildAt(j);
            if(child instanceof ModSwitch)toggle=(ModSwitch)child;
            else if(child instanceof ImageView){
                if(child.getTag() instanceof Integer)icon=child;else chevron=child;
            }
        }
        View col=(View)title.getParent();
        row.removeAllViews();
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.TOP|Gravity.START);
        row.setPadding(ui.dp(14),ui.dp(13),ui.dp(14),ui.dp(12));
        row.setMinimumHeight(ui.dp(124));
        row.setBackground(ui.expressiveSurface(24));
        row.setForeground(ui.softRipple(24));
        row.setClipToOutline(true);
        LinearLayout top=new LinearLayout(act);top.setGravity(Gravity.CENTER_VERTICAL);
        if(icon!=null){top.addView(icon,new LinearLayout.LayoutParams(ui.dp(26),ui.dp(26)));}
        else top.addView(ui.icon(Icons.SLIDERS,Ui.MUTED,26));
        top.addView(new View(act),new LinearLayout.LayoutParams(0,1,1));
        if(toggle!=null)top.addView(toggle,new LinearLayout.LayoutParams(-2,-2));
        else if(chevron!=null){top.addView(chevron,new LinearLayout.LayoutParams(ui.dp(18),ui.dp(18)));}
        row.addView(top,ui.matchWrap());
        title.setTextSize(16f);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);title.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        title.setPadding(0,ui.dp(10),0,ui.dp(3));
        if(sub!=null){sub.setTextSize(12.5f);sub.setMaxLines(2);sub.setEllipsize(android.text.TextUtils.TruncateAt.END);sub.setPadding(0,ui.dp(2),0,0);if(value!=null)sub.setVisibility(View.GONE);}
        row.addView(col,ui.matchWrap());
        row.addView(new View(act),new LinearLayout.LayoutParams(1,0,1));
        if(value!=null){
            value.setSingleLine(false);value.setMaxLines(2);value.setMaxWidth(Integer.MAX_VALUE);
            value.setPadding(0,ui.dp(4),0,0);value.setTextSize(12.5f);value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(value,ui.matchWrap());
        }
        row.setContentDescription(original+(sub==null?"":". "+searchWords.get(row)));
        Ui.pressMotion(row);
    }

    /** Equal-width cards with natural text height. Filtering reflows without empty slots. */
    static final class CardGrid extends LinearLayout {
        final int gap,side,minimum;
        CardGrid(Activity a,Ui ui){super(a);gap=ui.dp(8);side=ui.dp(14);minimum=ui.dp(310);setOrientation(VERTICAL);setClipChildren(false);setClipToPadding(false);}
        private int columns(int width){return width>=minimum&&getResources().getConfiguration().fontScale<=1.3f?2:1;}
        @Override protected void onMeasure(int ws,int hs){
            int width=View.MeasureSpec.getSize(ws);int cols=columns(width);
            int cell=Math.max(1,(width-2*side-(cols-1)*gap)/cols), y=gap;
            int h=0,count=0;
            for(int k=0;k<getChildCount();k++){
                View child=getChildAt(k);if(child.getVisibility()==GONE)continue;
                child.measure(View.MeasureSpec.makeMeasureSpec(cell,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                h=Math.max(h,child.getMeasuredHeight());count++;
            }
            for(int k=0;k<getChildCount();k++){
                View child=getChildAt(k);if(child.getVisibility()==GONE)continue;
                child.measure(View.MeasureSpec.makeMeasureSpec(cell,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));
            }
            y+=((count+cols-1)/cols)*(h+gap);
            setMeasuredDimension(width,resolveSize(y,hs));
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
            int cols=columns(r-l),index=0,y=gap,h=0;
            boolean rtl=getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
            for(int k=0;k<getChildCount();k++){
                View child=getChildAt(k);if(child.getVisibility()==GONE)continue;
                int w=child.getMeasuredWidth(),ch=child.getMeasuredHeight();
                int x=side+(index%cols)*(w+gap);if(rtl)x=(r-l)-x-w;
                child.layout(x,y,x+w,y+ch);h=Math.max(h,ch);index++;
                if(index%cols==0){y+=h+gap;h=0;}
            }
        }
    }

    private void recovery() {
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(act, Ui.dialogTheme())
                .setTitle("Восстановить функции?")
                .setMessage("Защита приостановила функции после нескольких коротких запусков. Если сбои повторятся, защита сработает снова.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Восстановить", (dlg, which) -> {
                    CrashGuard.reenable();
                    Restart.offer(act);
                }).create();
        Screen.markOwn(d);
        d.show();
        Ui.style(d);
    }

    private LinearLayout section(LinearLayout page,String ignored){
        if(grid==null){grid=new CardGrid(act,ui);page.addView(grid,ui.matchWrap());}
        return grid;
    }
    private void filter(){
        String q=VideoScreen.normalize(search.getText().toString());
        int count=0;
        for(int i=0;i<grid.getChildCount();i++){
            View row=grid.getChildAt(i);TextView title=Ui.titleOf(row),value=Ui.valueOf(row);
            String words=searchWords.getOrDefault(row,"")+" "+(title==null?"":title.getText())+" "+(value==null?"":value.getText());
            boolean match=q.isEmpty()||VideoScreen.normalize(words).contains(q);
            boolean changed=row.getVisibility()!=View.VISIBLE&&match;
            row.setVisibility(match?View.VISIBLE:View.GONE);
            if(match){count++;if(changed&&Ui.motionEnabled()){row.animate().cancel();row.setAlpha(.7f);row.setTranslationY(ui.dp(5));row.animate().alpha(1f).translationY(0).setStartDelay(0).setDuration(260).setInterpolator(Ui.motionCurve()).start();}}
        }
        empty.setVisibility(count==0?View.VISIBLE:View.GONE);
    }
    static String[] compact(String title){
        switch(title){
            case "Интерфейс и конфиденциальность": return new String[]{"Интерфейс","Лента и жесты"};
            case "Время и регион видео": return new String[]{"Дата и регион","Время публикации"};
            case "Управление репостами": return new String[]{"Репосты","Управление"};
            case "Время каждого сообщения": return new String[]{"Время сообщений","Часы и секунды"};
            case "Голосовые и обои без региона": return new String[]{"Функции чата","Голосовые и обои"};
            case "Голосовые комментарии": return new String[]{"Голосовые","Комментарии"};
            case "Дополнительные возможности": return new String[]{"Доп. функции","Возможности TikTok"};
            case "Резервная копия настроек": return new String[]{"Резервная копия","Импорт и экспорт"};
            case "Настройки разработчика TikTok": return new String[]{"Разработчик","Флаги A/B"};
            case "Внешний вид": return new String[]{title,"Тема и фон"};
            case "Нижняя панель": return new String[]{title,"Liquid Glass"};
            case "Возможности": return new String[]{title,"Функции TikTok"};
            case "Бейджики": return new String[]{title,"Значки аккаунтов"};
            case "Невидимка": return new String[]{title,"Приватность"};
            case "Реклама и LIVE": return new String[]{title,"Фильтр ленты"};
            case "Скачивание": return new String[]{title,"Видео и фото"};
            case "Видео и истории": return new String[]{title,"Сохранение"};
            case "Стикеры": return new String[]{title,"Сохранение"};
            case "Скрытые авторы": return new String[]{title,"Локальный список"};
            case "Огоньки": return new String[]{title,"Автопродление"};
            case "Баннер профиля": return new String[]{title,"Оформление"};
            case "Плагины": return new String[]{title,"MTP и команды"};
            default:return new String[]{title,""};
        }
    }

    private static String regionSummary() {
        String name = Regions.nameOf(Prefs.region);
        return name == null ? "Не меняется" : name;
    }
}
