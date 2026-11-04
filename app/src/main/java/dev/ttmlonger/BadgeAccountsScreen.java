package dev.ttmlonger;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Выбор аккаунта для бейджика: «недавно виденные» (имена, которые TikTok показал, пока бейджики
 * включены) и друзья из списка, который мод видит через огоньки. Сверху поиск по имени.
 */
final class BadgeAccountsScreen extends Screen {
    private static final int SHOW = 60;

    private final Runnable onChanged;
    private LinearLayout list;
    private EditText search;

    private BadgeAccountsScreen(Activity act, Runnable onChanged) {
        super(act);
        this.onChanged = onChanged;
    }

    static void show(Activity act, Runnable onChanged) {
        new BadgeAccountsScreen(act, onChanged).open("Выбрать аккаунт",
                "Имена, которые TikTok показывал недавно, и ваши друзья. Нажмите на аккаунт, чтобы выбрать бейджики.");
    }

    @Override
    void build(LinearLayout body) {
        search = new EditText(act);
        search.setHint("Поиск по имени");
        search.setHintTextColor(Ui.MUTED);
        search.setTextColor(Ui.INK);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setBackground(ui.round(Ui.FIELD, 22));
        search.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        Drawable lens = Icons.of(Icons.SEARCH, Ui.MUTED);
        lens.setBounds(0, 0, ui.dp(20), ui.dp(20));
        search.setCompoundDrawablesRelative(lens, null, null, null);
        search.setCompoundDrawablePadding(ui.dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                fill();
            }
        });
        LinearLayout.LayoutParams sp = ui.matchWrap();
        sp.setMargins(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        body.addView(search, sp);

        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list, ui.matchWrap());
        fill();
    }

    private void changed() {
        fill();
        if (onChanged != null) onChanged.run();
    }

    /** Фильтр по имени без учёта регистра; пустой фильтр пропускает всех. */
    static boolean matches(String name, String uid, String filter) {
        if (filter == null || filter.trim().isEmpty()) return true;
        String f = filter.trim().toLowerCase(Locale.ROOT);
        return (name != null && name.toLowerCase(Locale.ROOT).contains(f)) || (uid != null && uid.contains(f));
    }

    private void fill() {
        list.removeAllViews();
        String filter = search == null ? "" : search.getText().toString();
        List<BadgeStore.Account> recent = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BadgeStore.Account a : BadgeStore.recent()) {
            if (matches(a.name, a.uid, filter) && seen.add(a.uid)) recent.add(a);
        }
        List<BadgeStore.Account> friends = new ArrayList<>();
        for (Map.Entry<String, String> e : StreakStore.names().entrySet()) {
            if (matches(e.getValue(), e.getKey(), filter) && seen.add(e.getKey())) {
                friends.add(new BadgeStore.Account(e.getKey(), e.getValue()));
            }
        }
        Collections.sort(friends, new Comparator<BadgeStore.Account>() {
            @Override
            public int compare(BadgeStore.Account a, BadgeStore.Account b) {
                return a.name.toLowerCase(Locale.ROOT).compareTo(b.name.toLowerCase(Locale.ROOT));
            }
        });
        if (recent.isEmpty() && friends.isEmpty()) {
            list.addView(ui.note(filter.trim().isEmpty() ? empty() : "Никого не нашлось"));
            return;
        }
        add("Недавно виденные", recent, "Недавно виден");
        add("Друзья", friends, "Друг");
    }

    private String empty() {
        return BadgeHooks.running()
                ? "Пока никого не видно: полистайте ленту, комментарии или профили, потом вернитесь сюда."
                : "Список наполняется, когда бейджики включены и TikTok перезапущен. Включите их на предыдущем "
                + "экране, перезапустите TikTok и немного полистайте. Ещё можно добавить аккаунт по номеру.";
    }

    private void add(String title, List<BadgeStore.Account> items, String what) {
        if (items.isEmpty()) return;
        list.addView(ui.header(title + " · " + items.size()));
        int n = 0;
        for (final BadgeStore.Account a : items) {
            if (n++ >= SHOW) break;
            BadgeStore.Rule rule = BadgeStore.rule(a.uid);
            String sub = rule == null ? what : "Бейджики: " + BadgeSet.titles(rule.ids);
            list.addView(ui.row(-1, a.name, sub, null, true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    BadgePickScreen.show(act, a.uid, a.name, new Runnable() {
                        @Override
                        public void run() {
                            changed();
                        }
                    });
                }
            }));
        }
        if (items.size() > SHOW) list.addView(ui.note("Показано " + SHOW + " из " + items.size() + ": уточните поиск"));
    }
}
