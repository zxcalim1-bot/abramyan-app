package ru.abramyan.watch;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int BG = Color.BLACK;
    private static final int INK = Color.rgb(242, 239, 230);
    private static final int MUTED = Color.rgb(154, 151, 143);
    private static final int KEY_BG = Color.rgb(30, 30, 30);
    private static final int CODE_BG = Color.rgb(20, 20, 20);
    private static final int ACCENT = Color.rgb(255, 212, 59);
    private static final int STR = Color.rgb(168, 216, 160);
    private static final int NUM = Color.rgb(156, 199, 255);

    private static final Pattern TOKENS = Pattern.compile(
            "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')"
            + "|(#[^\\n]*)"
            + "|\\b(\\d+(?:\\.\\d+)?)\\b"
            + "|\\b(def|return|for|in|if|elif|else|while|import|and|or|not|match|case|break|continue"
            + "|True|False|None|try|except|with|as|is|print|input|int|float|range|len)\\b");
    private static final Pattern TASK_ID = Pattern.compile("^([a-z]+)(\\d+)$");

    /** Одна запись для поиска: задача Абрамяна или тема справочника. */
    private static class Item {
        boolean isTask;
        int g, t, r;
        String title, sub, id;
        String[] words;
        int hits, score;
    }

    private JSONArray data;   // задачи по группам
    private JSONArray refs;   // справочник Python
    private final List<Item> index = new ArrayList<>();

    private ScrollView scroll;
    private LinearLayout box;
    private LinearLayout results;
    private SharedPreferences prefs;
    private float codeSize;
    private String query = "";

    // 0 — главная, 1 — номера группы, 2 — решение, 3 — список справочника, 4 — тема справочника
    private int screen = 0;
    private int gIdx = -1;
    private boolean fromSearch = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        codeSize = prefs.getFloat("codeSize", 12f);
        data = loadJson("tasks.json");
        refs = loadJson("ref.json");
        buildIndex();

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);

        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int w = getResources().getDisplayMetrics().widthPixels;
        box.setPadding(w * 12 / 100, w * 10 / 100, w * 12 / 100, w * 20 / 100);

        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        showHome();
    }

    private JSONArray loadJson(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // ---------- поиск: русский, латиница, транслит ----------

    private static final String CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] LAT = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "i", "k", "l", "m",
            "n", "o", "p", "r", "s", "t", "u", "f", "h", "c", "ch", "sh", "sh", "", "i", "", "e", "iu", "ia"};

    /** Приводит и русский текст, и транслит к одному виду, чтобы они совпадали. */
    static String skel(String s) {
        s = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int k = CYR.indexOf(ch);
            if (k >= 0) {
                sb.append(LAT[k]);
            } else {
                sb.append(ch);
            }
        }
        String t = sb.toString()
                .replace("shch", "sh").replace("sch", "sh").replace("tch", "ch")
                .replace("kh", "h").replace("ck", "k").replace("x", "ks")
                .replace("tz", "c").replace("ts", "c")
                .replace("yo", "e").replace("jo", "e").replace("ye", "e").replace("je", "e")
                .replace("ya", "ia").replace("ja", "ia").replace("yu", "iu").replace("ju", "iu")
                .replace("y", "i").replace("j", "i").replace("w", "v").replace("q", "k");
        return t.replaceAll("([a-z])\\1+", "$1");
    }

    static String[] words(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split("[^a-z0-9]+")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out.toArray(new String[0]);
    }

    static String stem(String q) {
        if (q.matches("\\d+")) {
            return q;
        }
        if (q.length() > 5) {
            return q.substring(0, q.length() - 2);
        }
        if (q.length() > 3) {
            return q.substring(0, q.length() - 1);
        }
        return q;
    }

    private void buildIndex() {
        for (int gi = 0; gi < data.length(); gi++) {
            JSONObject g = data.optJSONObject(gi);
            JSONArray items = g.optJSONArray("items");
            for (int ti = 0; ti < items.length(); ti++) {
                JSONObject t = items.optJSONObject(ti);
                Item it = new Item();
                it.isTask = true;
                it.g = gi;
                it.t = ti;
                it.id = t.optString("id");
                it.title = it.id;
                it.sub = t.optString("d");
                it.words = words(skel(g.optString("g") + " " + it.id + " " + t.optInt("n") + " " + it.sub));
                index.add(it);
            }
        }
        for (int ri = 0; ri < refs.length(); ri++) {
            JSONObject r = refs.optJSONObject(ri);
            Item it = new Item();
            it.isTask = false;
            it.r = ri;
            it.id = "";
            it.title = r.optString("t");
            it.sub = r.optString("a");
            it.words = words(skel(it.title + " " + r.optString("k") + " " + it.sub));
            index.add(it);
        }
    }

    private List<Item> search(String q) {
        List<Item> found = new ArrayList<>();
        String compact = q.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        Item exact = null;
        if (TASK_ID.matcher(compact).matches()) {
            for (Item it : index) {
                if (it.isTask && it.id.equals(compact)) {
                    exact = it;
                    break;
                }
            }
        }
        String[] qw = words(skel(q));
        for (Item it : index) {
            if (it == exact) {
                continue;
            }
            it.hits = 0;
            it.score = 0;
            for (String w : qw) {
                String st = stem(w);
                boolean exactHit = false;
                boolean prefixHit = false;
                for (String iw : it.words) {
                    if (iw.equals(w)) {
                        exactHit = true;
                        break;
                    }
                    if (st.length() >= 2 && iw.startsWith(st)) {
                        prefixHit = true;
                    }
                }
                if (exactHit) {
                    it.hits++;
                    it.score += 3;
                } else if (prefixHit) {
                    it.hits++;
                    it.score += 2;
                }
            }
            if (it.hits > 0) {
                found.add(it);
            }
        }
        Collections.sort(found, (a, b) -> {
            if (a.hits != b.hits) {
                return b.hits - a.hits;
            }
            if (a.score != b.score) {
                return b.score - a.score;
            }
            if (a.isTask != b.isTask) {
                return a.isTask ? 1 : -1; // справка чуть выше задач
            }
            return 0;
        });
        if (exact != null) {
            found.add(0, exact);
        }
        if (found.size() > 30) {
            return new ArrayList<>(found.subList(0, 30));
        }
        return found;
    }

    // ---------- элементы интерфейса ----------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView label(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
    }

    private TextView button(CharSequence s, View.OnClickListener l) {
        TextView b = label(s, 14, INK, true);
        b.setBackground(rounded(KEY_BG, 22));
        b.setMinHeight(dp(44));
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private TextView resultButton(String title, String sub, View.OnClickListener l) {
        String shortSub = sub.length() > 70 ? sub.substring(0, 70) + "…" : sub;
        SpannableStringBuilder sb = new SpannableStringBuilder(title);
        sb.setSpan(new ForegroundColorSpan(ACCENT), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!shortSub.isEmpty()) {
            sb.append("\n").append(shortSub);
        }
        TextView b = label(sb, 12, INK, false);
        b.setBackground(rounded(KEY_BG, 16));
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams full(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.leftMargin = dp(3);
        p.rightMargin = dp(3);
        return p;
    }

    private TextView codeView(String src) {
        TextView code = new TextView(this);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, codeSize);
        code.setTextColor(INK);
        code.setText(highlight(src));
        code.setBackground(rounded(CODE_BG, 12));
        code.setPadding(dp(8), dp(8), dp(8), dp(8));
        return code;
    }

    private void finishRender() {
        scroll.scrollTo(0, 0);
        scroll.requestFocus(); // чтобы прокрутка безелем работала
    }

    private void header(String back, View.OnClickListener onBack, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        TextView b = label("‹ " + back, 13, ACCENT, false);
        b.setPadding(dp(6), dp(10), dp(10), dp(10));
        b.setClickable(true);
        b.setOnClickListener(onBack);
        row.addView(b);
        row.addView(label(title, 15, INK, true));
        box.addView(row, full(0));
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    // ---------- экраны ----------

    private void showHome() {
        screen = 0;
        box.removeAllViews();
        box.addView(label("Помощник: Абрамян и Python", 14, ACCENT, true), full(0));

        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Поиск: summa cifr, array87");
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        field.setBackground(rounded(KEY_BG, 22));
        field.setPadding(dp(12), dp(8), dp(12), dp(8));
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setText(query);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable e) {
                query = e.toString();
                fillResults();
            }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            hideKeyboard(v);
            return true;
        });
        box.addView(field, full(8));

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        box.addView(results, full(4));
        fillResults();
        finishRender();
    }

    private void fillResults() {
        results.removeAllViews();
        if (query.trim().isEmpty()) {
            results.addView(button("Справочник Python", v -> showRefList()), full(6));
            for (int i = 0; i < data.length(); i++) {
                JSONObject g = data.optJSONObject(i);
                JSONArray items = g.optJSONArray("items");
                int count = 0;
                for (int k = 0; k < items.length(); k++) {
                    if (items.optJSONObject(k).optInt("n") > 0) {
                        count++;
                    }
                }
                final int gi = i;
                results.addView(button(g.optString("g") + "  " + count, v -> showGroup(gi)), full(6));
            }
            return;
        }
        List<Item> found = search(query);
        if (found.isEmpty()) {
            results.addView(label("Ничего не нашлось. Попробуй другое слово или номер задачи, например begin12.",
                    12, MUTED, false), full(10));
            return;
        }
        for (final Item it : found) {
            if (it.isTask) {
                results.addView(resultButton(it.title, it.sub, v -> showTask(it.g, it.t, true)), full(6));
            } else {
                results.addView(resultButton("Python: " + it.title, it.sub, v -> showRef(it.r, true)), full(6));
            }
        }
    }

    private void showGroup(int gi) {
        screen = 1;
        gIdx = gi;
        box.removeAllViews();
        JSONObject g = data.optJSONObject(gi);
        header("Назад", v -> showHome(), g.optString("g"));
        if ("File".equals(g.optString("g"))) {
            box.addView(label("Сначала вставь функции из задачи 0", 11, MUTED, false), full(2));
        }
        JSONArray items = g.optJSONArray("items");
        LinearLayout row = null;
        for (int k = 0; k < items.length(); k++) {
            if (k % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row, full(6));
            }
            final int ti = k;
            String num = String.valueOf(items.optJSONObject(k).optInt("n"));
            row.addView(button(num, v -> showTask(gi, ti, false)), cell());
        }
        int rem = items.length() % 3;
        if (rem != 0 && row != null) {
            for (int k = rem; k < 3; k++) {
                row.addView(new View(this), cell());
            }
        }
        finishRender();
    }

    private void showTask(int gi, int ti, boolean viaSearch) {
        screen = 2;
        gIdx = gi;
        fromSearch = viaSearch;
        box.removeAllViews();
        JSONObject g = data.optJSONObject(gi);
        JSONArray items = g.optJSONArray("items");
        JSONObject t = items.optJSONObject(ti);

        header(viaSearch ? "Поиск" : g.optString("g"), v -> goBack(), t.optString("id"));
        box.addView(label(t.optString("d"), 12, INK, false), full(2));
        final TextView code = codeView(t.optString("c"));
        box.addView(code, full(8));

        final boolean hasPrev = ti > 0;
        final boolean hasNext = ti < items.length() - 1;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        TextView prev = button("‹", v -> {
            if (hasPrev) {
                showTask(gi, ti - 1, viaSearch);
            }
        });
        TextView minus = button("A−", v -> {
            setCodeSize(codeSize - 1);
            code.setTextSize(TypedValue.COMPLEX_UNIT_SP, codeSize);
        });
        TextView plus = button("A+", v -> {
            setCodeSize(codeSize + 1);
            code.setTextSize(TypedValue.COMPLEX_UNIT_SP, codeSize);
        });
        TextView next = button("›", v -> {
            if (hasNext) {
                showTask(gi, ti + 1, viaSearch);
            }
        });
        if (!hasPrev) {
            prev.setAlpha(0.35f);
        }
        if (!hasNext) {
            next.setAlpha(0.35f);
        }
        bar.addView(prev, cell());
        bar.addView(minus, cell());
        bar.addView(plus, cell());
        bar.addView(next, cell());
        box.addView(bar, full(10));
        finishRender();
    }

    private void showRefList() {
        screen = 3;
        box.removeAllViews();
        header("Назад", v -> showHome(), "Справочник");
        for (int i = 0; i < refs.length(); i++) {
            final int ri = i;
            TextView b = button(refs.optJSONObject(i).optString("t"), v -> showRef(ri, false));
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            box.addView(b, full(6));
        }
        finishRender();
    }

    private void showRef(int ri, boolean viaSearch) {
        screen = 4;
        fromSearch = viaSearch;
        box.removeAllViews();
        JSONObject r = refs.optJSONObject(ri);
        header(viaSearch ? "Поиск" : "Справочник", v -> goBack(), "");
        box.addView(label(r.optString("t"), 14, ACCENT, true), full(2));
        String a = r.optString("a");
        if (!a.isEmpty()) {
            box.addView(label(a, 12, INK, false), full(6));
        }
        String c = r.optString("c");
        if (!c.isEmpty()) {
            box.addView(codeView(c), full(8));
        }
        finishRender();
    }

    private void goBack() {
        if (screen == 2) {
            if (fromSearch) {
                showHome();
            } else {
                showGroup(gIdx);
            }
        } else if (screen == 4) {
            if (fromSearch) {
                showHome();
            } else {
                showRefList();
            }
        } else {
            showHome();
        }
    }

    private void setCodeSize(float s) {
        codeSize = Math.max(8f, Math.min(22f, s));
        prefs.edit().putFloat("codeSize", codeSize).apply();
    }

    private CharSequence highlight(String src) {
        SpannableStringBuilder sb = new SpannableStringBuilder(src);
        Matcher m = TOKENS.matcher(src);
        while (m.find()) {
            int color;
            if (m.group(1) != null) {
                color = STR;
            } else if (m.group(2) != null) {
                color = MUTED;
            } else if (m.group(3) != null) {
                color = NUM;
            } else {
                color = ACCENT;
            }
            sb.setSpan(new ForegroundColorSpan(color), m.start(), m.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    @Override
    public void onBackPressed() {
        if (screen == 0) {
            super.onBackPressed();
        } else {
            goBack();
        }
    }
}
