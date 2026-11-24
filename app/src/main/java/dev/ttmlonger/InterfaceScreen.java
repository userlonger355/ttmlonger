package dev.ttmlonger;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;

final class InterfaceScreen extends Screen {
    private android.widget.TextView linkExample;
    private final java.util.EnumMap<FeedButtons.Button, android.view.View> samples =
            new java.util.EnumMap<>(FeedButtons.Button.class);

    InterfaceScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new InterfaceScreen(act).open("Интерфейс и конфиденциальность", null);
    }

    @Override void build(LinearLayout body) {
        body.addView(ui.header("Конфиденциальность"));
        option(body, "clean", "Очищать ссылки TikTok", "Убирать метки отслеживания при копировании и системной отправке");
        linkExample = ui.note("");
        body.addView(linkExample);
        updateLinkExample();
        option(body, "browser", "Внешние ссылки в системном браузере", "При нажатии на ссылку внутри WebView TikTok");
        option(body, "reader", "Ограниченный встроенный браузер", "Открывать внешние страницы без JavaScript и доступа к файлам. Системный браузер имеет приоритет");
        body.addView(ui.menuRow(Icons.GLOBE, "Открывать ссылки TikTok в приложении", "Настройки ссылок Android", null,
                v -> settings(true)));
        body.addView(ui.menuRow(Icons.EYE_OFF, "Контакты и геопозиция", "Изменить разрешения TikTok в Android", null,
                v -> settings(false)));
        body.addView(ui.header("Навигация"));
        body.addView(ui.menuRow(Icons.SLIDERS, "Порядок верхних вкладок", "Рекомендации остаются на месте", null,
                v -> TabOrder.editor(act, true)));
        body.addView(ui.menuRow(Icons.SLIDERS, "Порядок нижних вкладок", "Главная остаётся на месте", null,
                v -> TabOrder.editor(act, false)));
        option(body, "labels", "Скрывать названия вкладок", "Оставить значки и счётчик входящих");
        body.addView(ui.header("Скрыть верхние вкладки"));
        body.addView(ui.note("Скрываются только кнопки верхней ленты. Сами разделы и свайпы TikTok сохраняются."));
        option(body,"top_hide_community","Скрыть «Сообщество»",null);
        option(body,"top_hide_friends","Скрыть «Друзья»",null);
        option(body,"top_hide_recommend","Скрыть «Для вас»",null);
        body.addView(ui.header("Скрыть кнопки в ленте"));
        LinearLayout preview = new LinearLayout(act);
        preview.setOrientation(LinearLayout.HORIZONTAL);
        preview.setGravity(android.view.Gravity.BOTTOM);
        preview.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF16334A, 0xFF303052, 0xFF53394D}));
        android.graphics.drawable.GradientDrawable gradient =
                (android.graphics.drawable.GradientDrawable) preview.getBackground();
        gradient.setCornerRadius(ui.dp(18));
        preview.setPadding(ui.dp(18), ui.dp(14), ui.dp(12), ui.dp(14));
        LinearLayout details=new LinearLayout(act);details.setOrientation(LinearLayout.VERTICAL);
        details.addView(ui.medium("@tiktok",14,0xffffffff));details.addView(ui.text("Новые видео каждый день",12,0xffeeeeee,false));details.addView(ui.text("♫  original sound",11,0xffdddddd,false));
        preview.addView(details,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout rail=new LinearLayout(act);rail.setOrientation(LinearLayout.VERTICAL);rail.setGravity(android.view.Gravity.CENTER_HORIZONTAL);preview.addView(rail);
        String[] counts={"","12,4K","1 209","854","326"};
        for(FeedButtons.Button button:FeedButtons.Button.values()){
            LinearLayout item=new LinearLayout(act);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            int n=button.ordinal();PreviewGlyph glyph=new PreviewGlyph(act,n,0xffffffff);
            item.addView(glyph,new LinearLayout.LayoutParams(ui.dp(25),ui.dp(25)));
            if(!counts[n].isEmpty()){android.widget.TextView number=ui.medium(counts[n],10,0xffffffff);number.setGravity(android.view.Gravity.CENTER);item.addView(number);}
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(44),-2);lp.bottomMargin=ui.dp(5);rail.addView(item,lp);item.setContentDescription(button.label);samples.put(button,item);
        }
        LinearLayout.LayoutParams box = ui.matchWrap();
        box.height = ui.dp(258);
        box.setMargins(ui.dp(14), 0, ui.dp(14), ui.dp(8));
        body.addView(preview, box);
        refreshPreview();
        for (FeedButtons.Button button : FeedButtons.Button.values()) {
            option(body, button.key(), button.label, FeedButtons.supported(button) ? null
                    : "Может быть недоступно в этой версии TikTok");
        }
        body.addView(ui.header("Комментарии"));
        body.addView(ui.menuRow(Icons.SLIDERS,"Настройки комментариев","Копирование, выделение и оформление",null,v->CommentToolsScreen.show(act)));
        body.addView(ui.header("Жесты на видео"));
        FeedGestures.settings(this, body);
        body.addView(ui.header("Подтверждение действий"));
        option(body, "like", "Двойное нажатие для лайка", null);
        option(body, "follow", "Двойное нажатие для подписки", null);
        option(body, "repost", "Двойное нажатие для репоста", null);
        option(body, "share", "Двойное нажатие для «Поделиться»", null);
        body.addView(ui.note("Повторите нажатие на ту же кнопку в течение двух секунд."));
        body.addView(ui.header("Профиль"));
        body.addView(ui.menuRow(Icons.SLIDERS,"Настройки профиля","Предпросмотр, счётчики, дата, кнопки и сортировка",null,v -> ProfileStyleScreen.show(act)));
        body.addView(ui.header("Публикация"));
        option(body, "details", "Сведения по нажатию на флаг", "Нажмите на строку сведений, чтобы скопировать её");
    }

    private void option(LinearLayout body, String key, String title, String note) {
        body.addView(toggleRow(-1, title, note, InterfaceHooks.on(key), on -> {
            InterfaceHooks.set(key, on);
            if (key.equals("clean")) updateLinkExample();
            if (key.startsWith("hide_")) {
                FeedButtons.refresh();
                refreshPreview();
            }
            if (key.equals("labels") || key.startsWith("top_hide_")) InterfaceHooks.labels(act);
        }));
    }

    private void updateLinkExample() {
        String example = "https://vm.tiktok.com/пример/?_r=1&utm_source=chat";
        linkExample.setText("Пример ссылки при отправке\n" +
                (InterfaceHooks.on("clean") ? ShareLinks.cleanText(example) : example));
    }

    private void refreshPreview() {
        for (FeedButtons.Button b : samples.keySet()) samples.get(b).setVisibility(
                InterfaceHooks.on(b.key()) ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void settings(boolean links) {
        Uri pkg = Uri.fromParts("package", act.getPackageName(), null);
        if (links && Build.VERSION.SDK_INT >= 31) {
            try {
                act.startActivity(new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg));
                return;
            } catch (ActivityNotFoundException ignored) { }
        }
        try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
        } catch (ActivityNotFoundException ignored) {
            Toasts.show(act, "Откройте настройки Android → Приложения → TikTok");
        }
    }
}
