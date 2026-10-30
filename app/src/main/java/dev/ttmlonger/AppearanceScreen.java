package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** «Внешний вид»: цветовой акцент, Amoled-тема и иконка приложения (ярлык). */
final class AppearanceScreen extends Screen {
    /** Готовые цвета (RGB). */
    private static final int[] PALETTE = {
            0xFF2D55, 0xFF2D8A, 0xFF6D00, 0xFFD600, 0x00C853, 0x00B0FF,
            0x2979FF, 0x651FFF, 0xAA00FF, 0x00BFA5, 0x006B2D, 0x00506E,
    };

    private LinearLayout swatchRow;
    private TextView hexLabel;
    private LinearLayout restartCard;
    private LinearLayout iconRow;
    private EditText labelField;

    private AppearanceScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new AppearanceScreen(act).open("Внешний вид", null);
    }

    @Override
    void build(LinearLayout body) {
        buildAccentCard(body);
        buildFontCard(body);
        buildIconCard(body);

        // Подсказка о перезапуске (появляется после изменений акцента и Amoled).
        restartCard = new LinearLayout(act);
        restartCard.setOrientation(LinearLayout.VERTICAL);
        restartCard.setBackground(ui.round(Ui.CARD, 24));
        restartCard.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(18));
        restartCard.setVisibility(View.GONE);
        restartCard.addView(ui.text("Изменения применятся после перезапуска TikTok", 16, Ui.MUTED, true));
        TextView btn = ui.text("Перезапустить сейчас", 19, Theme.accent(), true);
        btn.setPadding(0, ui.dp(10), 0, 0);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Restart.now(act);
            }
        });
        restartCard.addView(btn);
        body.addView(restartCard, sidePadded());

        refreshSwatches();
        refreshIcons();
    }

    // ============================================================ акцент и Amoled

    private void buildAccentCard(LinearLayout body) {
        LinearLayout card = card();

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.text("Цветовой акцент", 20, Ui.WHITE, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hexLabel = ui.text("", 18, Ui.MUTED, true);
        head.addView(hexLabel);
        card.addView(head, ui.matchWrap());
        card.addView(ui.space(14));

        HorizontalScrollView scroll = new HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        swatchRow = new LinearLayout(act);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        swatchRow.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(swatchRow);
        card.addView(scroll, ui.matchWrap());

        TextView hint = ui.text("Заменяет розовый акцент TikTok в лайках, кнопках, вкладках, значках и "
                + "анимациях, в том числе его оттенки (нажатая кнопка, полупрозрачные заливки). "
                + "Нужен перезапуск TikTok", 16, Ui.MUTED, true);
        hint.setPadding(0, ui.dp(14), 0, ui.dp(18));
        card.addView(hint);

        LinearLayout deep = new LinearLayout(act);
        deep.setOrientation(LinearLayout.HORIZONTAL);
        deep.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout deepTexts = new LinearLayout(act);
        deepTexts.setOrientation(LinearLayout.VERTICAL);
        deepTexts.addView(ui.text("Менять цвет и в рисунках", 20, Ui.WHITE, true));
        TextView deepDesc = ui.text("Перехватывает цвета при отрисовке, в темах, градиентах и растровых "
                + "иконках, чтобы поменялись и они. Если TikTok стал подтормаживать, выключите", 16, Ui.MUTED, true);
        deepDesc.setPadding(0, ui.dp(6), ui.dp(12), 0);
        deepTexts.addView(deepDesc);
        deep.addView(deepTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ModSwitch deepSw = new ModSwitch(act, Prefs.accentDeep);
        deepSw.setListener(new ModSwitch.Listener() {
            @Override
            public void onChanged(boolean on) {
                Prefs.setAccentDeep(on);
                restartCard.setVisibility(View.VISIBLE);
            }
        });
        deep.addView(deepSw);
        card.addView(deep, ui.matchWrap());
        card.addView(ui.space(18));

        LinearLayout amoled = new LinearLayout(act);
        amoled.setOrientation(LinearLayout.HORIZONTAL);
        amoled.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(ui.text("Amoled-тема", 20, Ui.WHITE, true));
        TextView ad = ui.text("Заменяет тёмно-серый фон на глубокий чёрный в тёмной теме. "
                + "Не влияет на светлую тему. Нужен перезапуск приложения", 16, Ui.MUTED, true);
        ad.setPadding(0, ui.dp(6), ui.dp(12), 0);
        texts.addView(ad);
        amoled.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ModSwitch sw = new ModSwitch(act, Prefs.amoled);
        sw.setListener(new ModSwitch.Listener() {
            @Override
            public void onChanged(boolean on) {
                Prefs.setAmoled(on);
                restartCard.setVisibility(View.VISIBLE);
            }
        });
        amoled.addView(sw);
        card.addView(amoled, ui.matchWrap());

        body.addView(card, sidePadded());
    }

    private void refreshSwatches() {
        int current = Prefs.accent & 0xFFFFFF;
        boolean isDefault = Prefs.accent == 0;
        boolean inPalette = false;

        swatchRow.removeAllViews();

        Swatch reset = new Swatch(act, Swatch.RESET, 0);
        reset.setSelected(isDefault);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setAccent(0);
                restartCard.setVisibility(View.VISIBLE);
                refreshSwatches();
            }
        });
        swatchRow.addView(reset, swatchParams());

        for (final int c : PALETTE) {
            Swatch s = new Swatch(act, Swatch.COLOR, c);
            boolean sel = !isDefault && current == c;
            inPalette |= sel;
            s.setSelected(sel);
            s.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Prefs.setAccent(c);
                    restartCard.setVisibility(View.VISIBLE);
                    refreshSwatches();
                }
            });
            swatchRow.addView(s, swatchParams());
        }

        Swatch custom = new Swatch(act, Swatch.CUSTOM, current);
        custom.setSelected(!isDefault && !inPalette);
        custom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ColorDialog.show(act, Theme.accent() & 0xFFFFFF, new ColorDialog.OnApply() {
                    @Override
                    public void apply(int rgb) {
                        Prefs.setAccent(rgb == 0 ? 0x000001 : rgb);
                        restartCard.setVisibility(View.VISIBLE);
                        refreshSwatches();
                    }
                });
            }
        });
        swatchRow.addView(custom, swatchParams());

        hexLabel.setText(isDefault ? "Стандартный" : String.format("#%06X", current));
    }

    private LinearLayout.LayoutParams swatchParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = ui.dp(6);
        return lp;
    }

    // ============================================================ шрифт

    private TextView fontName;
    private TextView fontReset;

    private void buildFontCard(LinearLayout body) {
        LinearLayout card = card();

        card.addView(ui.text("Шрифт", 20, Ui.WHITE, true), ui.matchWrap());
        fontName = ui.text("", 17, Ui.MUTED, true);
        fontName.setPadding(0, ui.dp(6), 0, 0);
        card.addView(fontName, ui.matchWrap());

        TextView hint = ui.text("Файл .ttf или .otf из памяти телефона. Заменяет шрифт обычного текста "
                + "в TikTok. Надписи, которые приложение рисует само, остаются прежними. "
                + "Нужен перезапуск TikTok", 15, Ui.MUTED, true);
        hint.setPadding(0, ui.dp(8), 0, ui.dp(14));
        card.addView(hint);

        int accent = Theme.accent();
        TextView pick = ui.text("Выбрать файл шрифта", 18, Theme.onColor(accent), true);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        pick.setBackground(ui.round(accent, 40));
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFont();
            }
        });
        card.addView(pick, ui.matchWrap());

        fontReset = ui.text("Вернуть шрифт TikTok", 17, Ui.MUTED, true);
        fontReset.setGravity(Gravity.CENTER);
        fontReset.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(4));
        fontReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FontHooks.reset();
                restartCard.setVisibility(View.VISIBLE);
                refreshFont();
            }
        });
        card.addView(fontReset, ui.matchWrap());

        body.addView(card, sidePadded());
        refreshFont();
    }

    private void refreshFont() {
        boolean set = !Prefs.fontName.isEmpty();
        fontName.setText(set ? Prefs.fontName : "Стандартный шрифт TikTok");
        fontReset.setVisibility(set ? View.VISIBLE : View.GONE);
    }

    private void pickFont() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "font/ttf", "font/otf", "font/sfnt", "font/collection",
                "application/x-font-ttf", "application/x-font-opentype", "application/x-font-otf",
                "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream"});
        boolean ok = ActivityResults.start(act, i, new ActivityResults.Callback() {
            @Override
            public void onResult(int resultCode, Intent data) {
                if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
                final Uri uri = data.getData();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            FontHooks.importFont(act, uri);
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    restartCard.setVisibility(View.VISIBLE);
                                    refreshFont();
                                    toast("Шрифт сохранён. Перезапустите TikTok");
                                }
                            });
                        } catch (final Throwable t) {
                            FlagStore.log(t);
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    toast("Не удалось подключить шрифт: " + t.getMessage());
                                }
                            });
                        }
                    }
                }, "ttmlonger-font").start();
            }
        });
        if (!ok) toast("Не удалось открыть выбор файла");
    }

    // ============================================================ иконка приложения

    private void buildIconCard(LinearLayout body) {
        LinearLayout card = card();

        card.addView(ui.text("Иконка приложения", 20, Ui.WHITE, true), ui.matchWrap());
        card.addView(ui.space(14));

        HorizontalScrollView scroll = new HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        iconRow = new LinearLayout(act);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(iconRow);
        card.addView(scroll, ui.matchWrap());

        TextView hint = ui.text("Android не даёт сменить значок установленного приложения, поэтому "
                + "мод добавляет на рабочий стол ярлык с выбранной иконкой и названием. "
                + "Исходный значок TikTok остаётся в меню приложений. "
                + "Чтобы удалить свою иконку, зажмите её", 15, Ui.MUTED, true);
        hint.setPadding(0, ui.dp(8), 0, ui.dp(14));
        card.addView(hint);

        labelField = new EditText(act);
        labelField.setSingleLine(true);
        labelField.setHint("Название ярлыка");
        labelField.setHintTextColor(Ui.MUTED);
        labelField.setTextColor(Ui.WHITE);
        labelField.setTextSize(18);
        labelField.setText(Prefs.iconLabel);
        labelField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        labelField.setBackground(ui.round(Ui.FIELD, 20));
        labelField.setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14));
        card.addView(labelField, ui.matchWrap());
        card.addView(ui.space(14));

        int accent = Theme.accent();
        TextView apply = ui.text("Добавить ярлык на рабочий стол", 18, Theme.onColor(accent), true);
        apply.setGravity(Gravity.CENTER);
        apply.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        apply.setBackground(ui.round(accent, 40));
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setIconLabel(labelField.getText().toString());
                toast(IconStore.pin(act, Prefs.iconId, Prefs.iconLabel));
            }
        });
        card.addView(apply, ui.matchWrap());

        body.addView(card, sidePadded());
    }

    private void refreshIcons() {
        iconRow.removeAllViews();
        String current = Prefs.iconId;

        Drawable standard = null;
        try {
            standard = act.getPackageManager().getApplicationIcon(act.getApplicationInfo());
        } catch (Throwable t) {
            FlagStore.log(t);
        }
        iconRow.addView(tile(standard, "Стандартная", current.isEmpty(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setIconId("");
                refreshIcons();
            }
        }, null));

        List<String> ids = IconStore.ids();
        int n = 0;
        for (final String id : ids) {
            n++;
            Bitmap b = IconStore.load(id);
            if (b == null) continue;
            iconRow.addView(tile(new BitmapDrawable(act.getResources(), b), "Своя " + n,
                    id.equals(current), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            Prefs.setIconId(id);
                            refreshIcons();
                        }
                    }, new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            confirmDelete(id);
                            return true;
                        }
                    }));
        }

        iconRow.addView(plusTile());
    }

    /** Плитка иконки: скруглённая картинка в рамке выбранного цвета и подпись под ней. */
    private View tile(Drawable icon, String caption, boolean selected, View.OnClickListener click,
                      View.OnLongClickListener longClick) {
        int accent = Theme.accent();
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(ui.dp(4), 0, ui.dp(4), 0);

        FrameLayout box = new FrameLayout(act);
        if (selected) {
            android.graphics.drawable.GradientDrawable ring = ui.round(0x00000000, 24);
            ring.setStroke(ui.dp(3), accent);
            box.setBackground(ring);
        }
        ImageView iv = roundedImage(icon, 62, 18);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(ui.dp(62), ui.dp(62), Gravity.CENTER);
        box.addView(iv, ip);
        col.addView(box, new LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)));

        TextView t = ui.text(caption, 14, selected ? accent : Ui.MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ui.dp(6), 0, 0);
        t.setSingleLine(true);
        col.addView(t, new LinearLayout.LayoutParams(ui.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));

        col.setOnClickListener(click);
        if (longClick != null) col.setOnLongClickListener(longClick);
        return col;
    }

    private View plusTile() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(ui.dp(4), 0, ui.dp(4), 0);

        TextView plus = ui.text("+", 34, Ui.WHITE, false);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(ui.round(Ui.FIELD, 18));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ui.dp(62), ui.dp(62));
        pp.setMargins(ui.dp(7), ui.dp(7), ui.dp(7), ui.dp(7));
        col.addView(plus, pp);

        TextView t = ui.text("Загрузить", 14, Ui.MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, ui.dp(6), 0, 0);
        t.setSingleLine(true);
        col.addView(t, new LinearLayout.LayoutParams(ui.dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));

        col.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickImage();
            }
        });
        return col;
    }

    private ImageView roundedImage(Drawable d, int sizeDp, final int radiusDp) {
        ImageView iv = new ImageView(act);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (d != null) iv.setImageDrawable(d);
        final float r = ui.dp(radiusDp);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        iv.setClipToOutline(true);
        return iv;
    }

    private void pickImage() {
        if (IconStore.ids().size() >= IconStore.MAX) {
            toast("Максимум " + IconStore.MAX + " иконок. Зажмите ненужную, чтобы удалить");
            return;
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        boolean ok = ActivityResults.start(act, i, new ActivityResults.Callback() {
            @Override
            public void onResult(int resultCode, Intent data) {
                if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
                final Uri uri = data.getData();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Bitmap square = IconStore.decodeSquare(act, uri);
                            final String id = IconStore.add(square);
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Prefs.setIconId(id);
                                    refreshIcons();
                                }
                            });
                        } catch (Throwable t) {
                            FlagStore.log(t);
                            act.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    toast("Не удалось прочитать изображение");
                                }
                            });
                        }
                    }
                }, "ttmlonger-icon").start();
            }
        });
        if (!ok) toast("Не удалось открыть выбор изображения");
    }

    private void confirmDelete(final String id) {
        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Удалить иконку?")
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        IconStore.delete(id);
                        if (id.equals(Prefs.iconId)) Prefs.setIconId("");
                        refreshIcons();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ============================================================ общее

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(Ui.CARD, 24));
        card.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(22));
        return card;
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
    }
}
