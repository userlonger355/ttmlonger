package dev.ttmlonger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «Бейджики»: картинка после имени аккаунта в профиле, комментариях и ленте. Они видны только на этом
 * телефоне: у мода нет сервера, который рассказал бы о них другим людям.
 */
final class BadgesScreen extends Screen {
    private TextView status;
    private TextView remoteStatus;
    private LinearLayout rulesBox;

    private BadgesScreen(Activity act) {
        super(act);
    }

    static void show(Activity act) {
        new BadgesScreen(act).open("Бейджики",
                "Картинка после имени аккаунта: в профиле, комментариях и под видео. Видна только у вас: другие "
                        + "люди её не увидят, у мода нет сервера.");
    }

    static String menuSummary() {
        return Prefs.badgesOn ? (BadgeStore.count() + BadgeRemote.count()) + " акк." : "Выкл";
    }

    @Override
    void build(LinearLayout body) {
        body.addView(toggleRow(Icons.CHECK, "Бейджики",
                "Показывать выбранные бейджики после имён. Хуки ставятся при запуске TikTok: после смены нужен перезапуск",
                Prefs.badgesOn, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setBadgesOn(on);
                        refresh();
                        Restart.offer(act);
                    }
                }));
        status = ui.text("", 14f, Ui.INK, false);
        status.setLineSpacing(0f, 1.15f);
        status.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        body.addView(status, ui.matchWrap());

        body.addView(ui.header("Общий список"));
        body.addView(toggleRow(Icons.GLOBE, "Бейджики от других",
                "Раз в 10 минут читать файл badges.json из репозитория мода на GitHub: бейджики, которые в нём выданы, "
                        + "видны всем, у кого стоит мод",
                Prefs.badgesRemote, new ModSwitch.Listener() {
                    @Override
                    public void onChanged(boolean on) {
                        Prefs.setBadgesRemote(on);
                        refresh();
                        if (on) BadgeRemote.refreshNow(new Runnable() {
                            @Override
                            public void run() {
                                refresh();
                            }
                        });
                    }
                }));
        remoteStatus = ui.text("", 14f, Ui.INK, false);
        remoteStatus.setLineSpacing(0f, 1.15f);
        remoteStatus.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        body.addView(remoteStatus, ui.matchWrap());
        body.addView(ui.row(Icons.REFRESH, "Обновить сейчас", "Скачать список заново", null, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!Prefs.badgesOn || !BadgeHooks.running()) {
                            Toasts.show(act, "Сначала включите бейджики и перезапустите TikTok");
                            return;
                        }
                        remoteStatus.setText("Загружаю…");
                        BadgeRemote.refreshNow(new Runnable() {
                            @Override
                            public void run() {
                                refresh();
                            }
                        });
                    }
                }));
        body.addView(ui.row(Icons.COPY, "Скопировать файл для GitHub",
                "Общий список вместе с вашими бейджиками: вставьте целиком в badges.json, и их увидят все", null, false,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copyFile();
                    }
                }));
        body.addView(ui.row(Icons.GLOBE, "Открыть badges.json на GitHub", "Страница правки файла в браузере или "
                + "приложении GitHub (нужен вход под владельцем репозитория)", null, true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor();
            }
        }));

        body.addView(ui.header("Аккаунты с бейджиками"));
        rulesBox = new LinearLayout(act);
        rulesBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(rulesBox, ui.matchWrap());

        body.addView(ui.menuRow(Icons.SEARCH, "Выбрать аккаунт", "Недавно виденные имена и друзья", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        BadgeAccountsScreen.show(act, new Runnable() {
                            @Override
                            public void run() {
                                refresh();
                            }
                        });
                    }
                }));
        body.addView(ui.menuRow(Icons.FLAG, "Добавить по номеру", "Если знаете номер аккаунта (только цифры)", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        askNumber();
                    }
                }));

        body.addView(ui.header("Какие бывают"));
        body.addView(previews(0, 5));
        body.addView(previews(5, BadgeSet.ALL.length));

        body.addView(ui.row(Icons.COPY, "Скопировать сведения", "Для разработчика: что мод нашёл и сколько раз "
                + "сработал (имён и номеров там нет)", null, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copy();
            }
        }));
        body.addView(ui.note("Метка бейджика ставится на имя, которое TikTok запрашивает у модели аккаунта. Где имя "
                + "рисуется не обычным текстом (часть экранов на Compose) или берётся из другого места, бейджика "
                + "может не быть. В поля ввода метка не попадает."));
        refresh();
    }

    private View previews(int from, int to) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6));
        for (int i = from; i < to; i++) {
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView pic = new ImageView(act);
            pic.setImageDrawable(new BadgeArt(BadgeSet.ALL[i]));
            cell.addView(pic, new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)));
            row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private void refresh() {
        if (status != null) status.setText(BadgeHooks.status());
        if (remoteStatus != null) remoteStatus.setText(BadgeRemote.status(System.currentTimeMillis()));
        if (rulesBox == null) return;
        rulesBox.removeAllViews();
        java.util.List<BadgeStore.Rule> all = BadgeStore.rules();
        if (all.isEmpty()) {
            rulesBox.addView(ui.note("Пока никого. Нажмите «Выбрать аккаунт»."));
            return;
        }
        for (final BadgeStore.Rule r : all) {
            rulesBox.addView(ui.row(-1, r.name, BadgeSet.titles(r.ids), null, true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    BadgePickScreen.show(act, r.uid, r.name, new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                }
            }));
        }
    }

    private void askNumber() {
        final EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Номер аккаунта");
        input.setTextColor(Ui.INK);
        input.setHintTextColor(Ui.MUTED);
        input.setSingleLine(true);
        LinearLayout box = new LinearLayout(act);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dlg = new AlertDialog.Builder(act, Ui.dialogTheme())
                .setTitle("Добавить по номеру")
                .setView(box)
                .setPositiveButton("Дальше", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String uid = input.getText().toString().trim();
                        if (!StreakLogic.isUid(uid)) {
                            Toasts.show(act, "Номер: только цифры, до 24 знаков");
                            return;
                        }
                        String name = BadgeStore.recentName(uid);
                        BadgeStore.Rule have = BadgeStore.rule(uid);
                        if (name == null && have != null) name = have.name;
                        BadgePickScreen.show(act, uid, StreakLogic.displayName(name, uid), new Runnable() {
                            @Override
                            public void run() {
                                refresh();
                            }
                        });
                    }
                })
                .setNegativeButton("Отмена", null)
                .create();
        markOwn(dlg);
        dlg.show();
        Ui.style(dlg);
    }

    private void copyFile() {
        try {
            String text = BadgeRemote.export(BadgeRemote.shared(), BadgeStore.rules());
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("badges.json", text));
            Toasts.show(act, "Скопировано. Откройте badges.json на GitHub и вставьте вместо всего содержимого");
        } catch (Throwable t) {
            Toasts.show(act, "Не удалось скопировать");
        }
    }

    private void openEditor() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(BadgeRemote.EDIT_URL));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable t) {
            Toasts.show(act, "Не удалось открыть: откройте github.com/" + BadgeRemote.OWNER + "/" + BadgeRemote.REPO);
        }
    }

    private void copy() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("TTMLonger", "TTMLonger " + Targets.VERSION + " · Android "
                    + android.os.Build.VERSION.SDK_INT + "\n" + BadgeHooks.details() + "\n" + BadgeRemote.details()));
            Toasts.show(act, "Скопировано. Вставьте в сообщение разработчику");
        } catch (Throwable t) {
            Toasts.show(act, "Не удалось скопировать");
        }
    }
}
