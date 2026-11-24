package dev.ttmlonger;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.EditText;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.ArrayList;
import java.util.List;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Главная страница «Настройки мода». */
final class ModMenu extends Screen {
    private LinearLayout regionRow;
    private LinearLayout tabs;
    private LinearLayout shortcuts;
    private EditText search;
    private TextView empty;
    private TextView streakValue;
    private LinearLayout streakMenuRow;
    private HorizontalScrollView tabScroll;
    private final List<LinearLayout> sections = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private int selected;
    private final java.util.Map<TextView,android.animation.ValueAnimator> chipAnimations=new java.util.WeakHashMap<>();

    private ModMenu(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new ModMenu(act).open("Настройки мода", null);
    }

    @Override
    void build(LinearLayout page) {
        shortcuts = new LinearLayout(act);
        shortcuts.setBaselineAligned(false);
        shortcuts.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(6));
        page.addView(shortcuts, ui.matchWrap());
        streakValue = tile(Icons.FLAME, "Огоньки", StreakScreen.menuSummary(), true, () -> StreakScreen.show(act));
        tile(Icons.SLIDERS, "Интерфейс", "Лента и жесты", false, () -> InterfaceScreen.show(act));

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
        tabScroll = new HorizontalScrollView(act);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(act);
        tabs.setPadding(ui.dp(14), ui.dp(12), ui.dp(8), ui.dp(6));
        tabScroll.addView(tabs);
        page.addView(tabScroll, ui.matchWrap());
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
        body.addView(ui.menuRow(Icons.SLIDERS, "Интерфейс и конфиденциальность",
                "Ссылки, вкладки и подтверждение действий", null, v -> InterfaceScreen.show(act)));

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
                "Установка баннера в «Изменить профиль». Нужен перезапуск TikTok",
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

        LinearLayout footer=new LinearLayout(act);
        footer.setGravity(Gravity.CENTER);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(ui.dp(12),ui.dp(20),ui.dp(12),ui.dp(16));
        TextView ver=ui.medium("0.0.1a (dev buld)",13,Ui.INK);
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
        empty = ui.note("По этому запросу настроек нет");
        page.addView(empty);
        empty.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable s) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
        });
        page.getViewTreeObserver().addOnWindowFocusChangeListener(focus -> {
            if (!focus) return;
            streakValue.setText(StreakScreen.menuSummary());
            Ui.valueOf(streakMenuRow).setText(StreakScreen.menuSummary());
        });
        for(LinearLayout group:sections)for(int j=0;j<group.getChildCount();j++)makeCard((LinearLayout)group.getChildAt(j));
        filter();
    }

    /** Reuse the original row and listener. Move its contents to a vertical card. */
    private void makeCard(LinearLayout row){
        TextView title=Ui.titleOf(row),sub=Ui.subtitleOf(row),value=Ui.valueOf(row);
        if(title==null)return;
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
        row.setPadding(ui.dp(14),ui.dp(14),ui.dp(14),ui.dp(15));
        row.setMinimumHeight(ui.dp(136));
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
        title.setTextSize(16.5f);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        title.setPadding(0,ui.dp(12),0,ui.dp(3));
        if(sub!=null){sub.setTextSize(12.5f);sub.setPadding(0,ui.dp(2),0,0);}
        row.addView(col,ui.matchWrap());
        if(value!=null){
            value.setSingleLine(false);value.setMaxLines(2);value.setMaxWidth(Integer.MAX_VALUE);
            value.setPadding(0,ui.dp(7),0,0);value.setTextSize(13);
            row.addView(value,ui.matchWrap());
        }
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
            ArrayList<View> row=new ArrayList<>(cols);
            int h=0;
            for(int k=0;k<getChildCount();k++){
                View child=getChildAt(k);if(child.getVisibility()==GONE)continue;
                child.measure(View.MeasureSpec.makeMeasureSpec(cell,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                h=Math.max(h,child.getMeasuredHeight());row.add(child);
                if(row.size()==cols){for(View one:row)one.measure(View.MeasureSpec.makeMeasureSpec(cell,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));y+=h+gap;row.clear();h=0;}
            }
            if(!row.isEmpty()){for(View one:row)one.measure(View.MeasureSpec.makeMeasureSpec(cell,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));y+=h+gap;}
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

    private TextView tile(int icon, String title, String value, boolean warm, Runnable open) {
        int ink = Ui.INK;
        LinearLayout tile = new LinearLayout(act);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(ui.dp(16), ui.dp(15), ui.dp(12), ui.dp(16));
        tile.setBackground(ui.expressiveSurface(24));
        tile.setClipToOutline(true);
        tile.setForeground(ui.softRipple(24));
        tile.setFocusable(true);
        Ui.pressMotion(tile);
        tile.setOnClickListener(v -> open.run());
        int tint = Ui.MUTED;
        tile.addView(ui.icon(icon, tint, 27));
        TextView heading = ui.text(title, 19, ink, true);
        heading.setPadding(0, ui.dp(12), 0, ui.dp(2));
        tile.addView(heading);
        TextView detail = ui.text(value, 13, tint, false);
        tile.addView(detail);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (!warm) lp.setMarginStart(ui.dp(8));
        shortcuts.addView(tile, lp);
        return detail;
    }

    private LinearLayout section(LinearLayout page, String title) {
        int index = sections.size();
        TextView chip = ui.medium(title, 14, Ui.INK);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        chip.setMinimumHeight(ui.dp(44));
        chip.setFocusable(true);
        Ui.pressMotion(chip);
        chip.setOnClickListener(v -> {
            if(selected==index)return;
            int direction=index>selected?1:-1;
            selected=index;
            filter();
            animateSection(sections.get(index),direction);
            tabScroll.smoothScrollTo(chip.getLeft() - ui.dp(14), 0);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(ui.dp(6));
        tabs.addView(chip, lp);
        labels.add(chip);
        LinearLayout card = new CardGrid(act,ui);
        page.addView(card,ui.matchWrap());
        sections.add(card);
        return card;
    }

    private void filter() {
        String q = VideoScreen.normalize(search.getText().toString());
        boolean browsing = q.isEmpty();
        shortcuts.setVisibility(browsing ? View.VISIBLE : View.GONE);
        tabScroll.setVisibility(browsing ? View.VISIBLE : View.GONE);
        boolean any = false;
        for (int i = 0; i < sections.size(); i++) {
            LinearLayout card = sections.get(i);
            TextView chip = labels.get(i);
            boolean current = i == selected;
            updateChip(chip,current);
            boolean matchGroup = VideoScreen.normalize(chip.getText().toString()).contains(q);
            boolean visible = false;
            for (int j = 0; j < card.getChildCount(); j++) {
                View row = card.getChildAt(j);
                TextView title = Ui.titleOf(row);
                TextView sub = Ui.subtitleOf(row);
                String words = (title == null ? "" : title.getText()) + " " + (sub == null ? "" : sub.getText());
                boolean match = browsing ? current : matchGroup || VideoScreen.normalize(words).contains(q);
                row.setVisibility(match ? View.VISIBLE : View.GONE);
                visible |= match;
            }
            if(!visible)resetSection(card);
            card.setVisibility(visible ? View.VISIBLE : View.GONE);
            any |= visible;
        }
        empty.setVisibility(any ? View.GONE : View.VISIBLE);
    }

    private void updateChip(TextView chip,boolean selectedNow){
        Object saved=chip.getTag();
        float target=selectedNow?1f:0f;
        float initial=saved instanceof Float?(Float)saved:target;
        boolean changed=chip.isSelected()!=selectedNow;
        chip.setSelected(selectedNow);
        if(!changed&&saved instanceof Float)return;
        android.animation.ValueAnimator old=chipAnimations.remove(chip);if(old!=null)old.cancel();
        android.graphics.drawable.GradientDrawable shape=ui.round(Ui.CARD,24);
        chip.setBackground(shape);
        java.util.function.Consumer<Float> render=progress->{
            chip.setTag(progress);
            shape.setColor(Ui.blend(Ui.CARD,Ui.INK,progress));
            shape.setCornerRadius(ui.dp(24-9*progress));
            chip.setTextColor(Ui.blend(Ui.INK,Ui.BG,progress));
        };
        render.accept(initial);
        if(!Ui.motionEnabled()||initial==target){render.accept(target);return;}
        android.animation.ValueAnimator a=android.animation.ValueAnimator.ofFloat(initial,target);
        a.setDuration(330);a.setInterpolator(Ui.motionCurve());
        a.addUpdateListener(anim->render.accept((Float)anim.getAnimatedValue()));
        chipAnimations.put(chip,a);a.start();
    }
    private void resetSection(LinearLayout grid){
        grid.animate().cancel();grid.setAlpha(1f);grid.setTranslationX(0);grid.setTranslationY(0);
        for(int i=0;i<grid.getChildCount();i++){
            View card=grid.getChildAt(i);card.animate().cancel();card.setAlpha(1f);card.setTranslationX(0);card.setTranslationY(0);
        }
    }
    private void animateSection(LinearLayout grid,int direction){
        resetSection(grid);
        if(!Ui.motionEnabled())return;
        int visible=0;
        for(int i=0;i<grid.getChildCount();i++){
            View card=grid.getChildAt(i);if(card.getVisibility()!=View.VISIBLE)continue;
            card.setAlpha(.68f);card.setTranslationX(direction*ui.dp(7));card.setTranslationY(ui.dp(8));
            card.animate().alpha(1f).translationX(0).translationY(0)
                .setStartDelay(Math.min(visible++/2,4)*24L)
                .setDuration(360).setInterpolator(Ui.motionCurve()).start();
        }
    }

    private static String regionSummary() {
        String name = Regions.nameOf(Prefs.region);
        return name == null ? "Не меняется" : name;
    }
}
