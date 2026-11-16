package com.codex.autobrightness;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lightweight native landscape UI; no WebView, browser engine, network library or UI polling. */
public final class MainActivity extends Activity implements StatusBus.Listener {
    /** Main app surface, chosen for restrained night-time contrast. */
    private static final int BACKGROUND = Color.rgb(16, 23, 34);
    /** Raised configuration surface color. */
    private static final int PANEL = Color.rgb(28, 39, 56);
    /** Primary readable text color on the fixed dark car display. */
    private static final int TEXT = Color.rgb(232, 239, 249);
    /** Secondary text remains visible without lighting the entire screen. */
    private static final int MUTED = Color.rgb(166, 184, 207);
    /** Selected controls and emphasized actions use one consistent accent. */
    private static final int ACCENT = Color.rgb(131, 189, 255);
    /** Main content container rebuilt only when switching between the three pages. */
    private LinearLayout content;
    /** Saved app configuration; references only application context. */
    private Settings settings;
    /** Visible-only connection to the independently installed brightness engine. */
    private EngineClient engine;
    /** Selected page: zero controller, one rules, two settings. */
    private int page;
    /** Current raw light value label on the controller page. */
    private TextView lightView;
    /** Current native feedback value label on the controller page. */
    private TextView actualView;
    /** Current automatic target value label on the controller page. */
    private TextView targetView;
    /** Controller status/error label, refreshed only while the Activity is visible. */
    private TextView statusView;
    /** Pause deadline and recovery guidance on the controller page. */
    private TextView detailView;
    /** Manual brightness selection label. */
    private TextView manualValue;
    /** Native ten-point manual brightness slider; writes only after user release. */
    private SeekBar manualSlider;
    /** Prevent incoming feedback from moving the slider under the user's finger. */
    private boolean dragging;
    /** Sidebar tab buttons for styling the selected page. */
    private final List<Button> tabs = new ArrayList<>();

    /** Create the launcher screen and start only the saved enabled controller. */
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        engine = new EngineClient(this, settings, () -> showPage(page));
        if (state != null) page = state.getInt("page", 0);
        LinearLayout shell = vertical(); shell.setBackgroundColor(BACKGROUND);
        LinearLayout header = horizontal(); header.setPadding(dp(24), dp(12), dp(24), dp(12));
        TextView name = label("随光亮度", 25, TEXT); name.setTypeface(null, Typeface.BOLD);
        header.addView(name, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView version = label("1.1.0  ·  独立后台", 16, MUTED); header.addView(version);
        Button home = button("返回桌面");
        home.setOnClickListener(view -> returnToDesktop());
        LinearLayout.LayoutParams homeParams = new LinearLayout.LayoutParams(-2, dp(52));
        homeParams.leftMargin = dp(20); header.addView(home, homeParams);
        shell.addView(header);
        LinearLayout body = horizontal(); body.setGravity(Gravity.TOP);
        LinearLayout nav = vertical(); nav.setPadding(dp(12), dp(12), dp(12), dp(12));
        String[] names = {"自动控制", "亮度规则", "设置"};
        for (int i = 0; i < names.length; i++) {
            final int selected = i;
            Button tab = button(names[i]); tab.setOnClickListener(view -> showPage(selected));
            tabs.add(tab); add(nav, tab, 8);
        }
        body.addView(nav, new LinearLayout.LayoutParams(dp(166), ViewGroup.LayoutParams.MATCH_PARENT));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = vertical(); content.setPadding(dp(18), dp(10), dp(24), dp(24));
        scroll.addView(content); body.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        shell.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(shell); showPage(page);
    }

    /** Listen only while visible so background operation never refreshes a hidden view tree. */
    @Override protected void onStart() { super.onStart(); StatusBus.add(this); engine.open(); }
    /** Drop the Activity reference immediately when moving to the vehicle launcher or another application. */
    @Override protected void onStop() { engine.close(); StatusBus.remove(this); super.onStop(); }
    /** Retain the selected page across configuration changes without retaining Activity instances. */
    @Override public void onSaveInstanceState(Bundle state) { state.putInt("page", page); super.onSaveInstanceState(state); }

    /** Treat the ordinary Android Back key as leaving the settings UI, preserving the independent foreground service. */
    @Override public void onBackPressed() { returnToDesktop(); }

    /** Return through the normal HOME intent and finish only this settings Activity.
     * F202 SystemUI's separate side-bar close button calls forceStopPackage,
     * which can now stop only this settings package. The controller is an
     * independently installed package with its own UID and started service.
     * This path merely releases UI observations and removes settings views.
     */
    private void returnToDesktop() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
            finish();
        } catch (ActivityNotFoundException error) {
            if (!moveTaskToBack(true)) toast("暂时无法返回桌面，请使用车机的桌面按钮");
        }
    }

    /** Build only one selected screen; sensor updates modify labels without reconstructing the layout. */
    private void showPage(int selected) {
        page = selected; content.removeAllViews();
        lightView = null; actualView = null; targetView = null; statusView = null; detailView = null;
        manualSlider = null; manualValue = null; dragging = false;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).setBackground(background(i == page ? Color.rgb(43, 66, 96) : BACKGROUND, 10));
            tabs.get(i).setTextColor(i == page ? ACCENT : TEXT);
        }
        if (page == 0) controllerPage(); else if (page == 1) rulesPage(); else settingsPage();
        changed(StatusBus.latest);
    }

    /** Present live native values, the main switch, sensitivity, dark brightness and manual takeover. */
    private void controllerPage() {
        add(content, toggle("自动调节亮度", settings.enabled(), checked -> {
            settings.putBoolean("enabled", checked);
            if (checked) engine.resume();
            else changed(StatusBus.latest);
        }), 10);
        statusView = label("等待车辆接口", 18, ACCENT); add(content, statusView, 10);
        add(content, label("设置界面与后台已独立安装。返回桌面或点车机侧边的 × 关闭设置时，后台继续运行。", 16, MUTED), 10);
        LinearLayout metrics = horizontal();
        lightView = metric(metrics, "光照原始值"); actualView = metric(metrics, "当前亮度"); targetView = metric(metrics, "目标亮度");
        add(content, metrics, 14);
        detailView = label("", 16, MUTED); add(content, detailView, 10);
        LinearLayout controls = horizontal(); controls.setGravity(Gravity.TOP);
        LinearLayout sensitivity = vertical(); sensitivity.setPadding(0, 0, dp(16), 0);
        add(sensitivity, label("变化灵敏度", 18, TEXT), 4);
        add(sensitivity, choices(new String[]{"平稳：更能忽略短暂变化", "均衡：兼顾响应与稳定", "灵敏：更快跟随光照"},
                settings.sensitivity(), index -> settings.putInt("sensitivity", index)), 0);
        controls.addView(sensitivity, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout dark = vertical();
        add(dark, label("光照为 0 时的亮度", 18, TEXT), 4);
        Spinner darkChoice = choices(new String[]{"10%", "20%", "30%", "40%", "50%", "60%", "70%", "80%", "90%", "100%"},
                settings.points().get(0).brightness / 10 - 1, index -> setDark((index + 1) * 10));
        add(dark, darkChoice, 0); controls.addView(dark, new LinearLayout.LayoutParams(0, -2, 1)); add(content, controls, 14);
        LinearLayout actions = horizontal();
        Button resume = button("立即恢复自动"); resume.setOnClickListener(view -> {
            settings.putBoolean("enabled", true); engine.resume(); showPage(0);
        });
        actions.addView(resume, new LinearLayout.LayoutParams(0, -2, 1));
        Button rules = button("编辑全部亮度规则"); rules.setOnClickListener(view -> showPage(1));
        actions.addView(rules, new LinearLayout.LayoutParams(0, -2, 1)); add(content, actions, 14);
        manualValue = label("手动亮度", 18, TEXT); add(content, manualValue, 2);
        manualSlider = new SeekBar(this); manualSlider.setMax(9); manualSlider.setMinimumHeight(dp(48));
        manualSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            /** Update only the visible preview while dragging; do not flood the vehicle with writes. */
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) manualValue.setText("手动亮度  " + ((progress + 1) * 10) + "%");
            }
            /** Keep real feedback from moving a slider under the user's finger. */
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            /** Send one explicit native write on release and let the configured takeover policy pause automation. */
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                engine.manual((bar.getProgress() + 1) * 10);
            }
        });
        add(content, manualSlider, 4);
        add(content, label("光照值是车辆原始读数；亮度百分比沿用原厂刻度。", 15, MUTED), 0);
    }

    /** Render the saved ordered curve with explicit add/edit/delete actions. */
    private void rulesPage() {
        add(content, label("光照越强，屏幕越亮", 23, TEXT), 6);
        add(content, label("两个对应点之间平滑计算，按原厂整十档位输出。初始规则可根据白天体验修改。", 16, MUTED), 16);
        List<Settings.Point> points = settings.points();
        for (int i = 0; i < points.size(); i++) {
            final int index = i;
            Settings.Point point = points.get(i);
            LinearLayout row = horizontal(); row.setPadding(dp(16), dp(8), dp(12), dp(8)); row.setBackground(background(PANEL, 10));
            TextView value = label("光照 " + point.light + "    →    亮度 " + point.brightness + "%", 20, TEXT);
            row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            Button edit = button("编辑"); edit.setOnClickListener(view -> editPoint(index)); row.addView(edit);
            if (index > 0 && points.size() > 2) {
                Button remove = button("删除"); remove.setOnClickListener(view -> {
                    List<Settings.Point> next = settings.points(); next.remove(index); settings.savePoints(next); showPage(1);
                }); row.addView(remove);
            }
            add(content, row, 8);
        }
        Button addPoint = button("添加对应点"); addPoint.setOnClickListener(view -> editPoint(-1)); add(content, addPoint, 10);
        add(content, label("光照为 0 的对应点必须保留。超出最后一个光照点时，使用最后一档亮度。", 16, MUTED), 0);
    }

    /** Configure boot/wake behavior and manual takeover without exposing technical implementation details. */
    private void settingsPage() {
        add(content, label("运行设置", 23, TEXT), 12);
        add(content, toggle("随车机启动", settings.autostart(), value -> settings.putBoolean("autostart", value)), 8);
        add(content, toggle("休眠唤醒后恢复自动控制", settings.resumeOnWake(), value -> settings.putBoolean("resume_on_wake", value)), 16);
        add(content, label("手动调节原厂滑块或本工具后的处理", 18, TEXT), 4);
        add(content, choices(new String[]{"暂停自动控制 10 分钟", "暂停到下次启动或唤醒", "持续自动控制"},
                settings.manualPolicy(), index -> settings.putInt("manual_policy", index)), 20);
        add(content, label("轻量后台", 20, TEXT), 8);
        add(content, label("按车辆光照变化处理；退出页面后停止界面刷新；休眠时停止读取；不联网、不持有唤醒锁，不持续保存光照日志。", 17, MUTED), 16);
        add(content, label("升级后第一次打开会迁移已有设置并初始化独立后台。若车机另有自启动管理，请允许“随光亮度后台服务”运行。", 17, MUTED), 16);
        add(content, label("桌面只有一个设置图标。点车机侧边的 × 只关闭设置页面；如需停止自动亮度，请关闭自动调节开关。", 17, MUTED), 16);
        Button stop = button("关闭自动控制并停止后台"); stop.setOnClickListener(view -> {
            settings.putBoolean("enabled", false); showPage(0);
        }); add(content, stop, 10);
        add(content, label("版本 1.1.0 · 适配 F202_MCA / Android 9", 15, MUTED), 0);
    }

    /** Update existing values without rebuilding layout or dispatching vehicle reads from the UI. */
    @Override public void changed(StatusBus.Snapshot state) {
        if (lightView == null) return;
        lightView.setText(state.light < 0 ? "—" : String.valueOf(state.light));
        actualView.setText(state.actual < 0 ? "—" : state.actual + "%");
        targetView.setText(state.target < 0 ? "—" : state.target + "%");
        statusView.setText(settings.enabled() ? state.message : "自动控制已关闭，后台已停止");
        String detail;
        if (state.pauseUntil == Long.MAX_VALUE) detail = "已手动接管：下次车机启动或唤醒后恢复，也可点立即恢复。";
        else if (state.pauseUntil > System.currentTimeMillis()) detail = "已手动接管：预计 "
                + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(state.pauseUntil)) + " 恢复自动。";
        else detail = "车辆接口：" + (state.connected ? "已连接" : "等待连接") + "  ·  本次收到光照变化 " + state.samples + " 次";
        detailView.setText(detail);
        manualSlider.setEnabled(settings.enabled() && state.connected);
        if (!dragging && state.actual >= 0) {
            manualSlider.setProgress(Math.max(0, Math.min(9, state.actual / 10 - 1)));
            manualValue.setText("手动亮度  " + state.actual + "%");
        }
    }

    /** Increase later points if needed so the quick dark-setting control cannot create a decreasing curve. */
    private void setDark(int brightness) {
        List<Settings.Point> points = settings.points(), updated = new ArrayList<>();
        for (Settings.Point point : points) updated.add(new Settings.Point(point.light, Math.max(brightness, point.brightness)));
        updated.set(0, new Settings.Point(0, brightness)); settings.savePoints(updated);
    }

    /** Edit one curve point in a focused native dialog and validate the entire curve before saving. */
    private void editPoint(int index) {
        List<Settings.Point> points = settings.points();
        if (index < 0 && points.size() >= 12) { toast("最多设置 12 个对应点"); return; }
        Settings.Point old = index >= 0 ? points.get(index) : new Settings.Point(
                Math.min(1000000, points.get(points.size() - 1).light * 2 + 1), 100);
        LinearLayout form = vertical(); form.setPadding(dp(24), dp(8), dp(24), dp(8));
        add(form, label("光照原始值", 18, TEXT), 4);
        EditText lightInput = numberInput(old.light); lightInput.setEnabled(index != 0); add(form, lightInput, 12);
        add(form, label("亮度百分比（10～100，填整十数）", 18, TEXT), 4);
        EditText brightnessInput = numberInput(old.brightness); add(form, brightnessInput, 8);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(index < 0 ? "添加对应点" : "编辑对应点")
                .setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                Settings.Point point = new Settings.Point(Integer.parseInt(lightInput.getText().toString().trim()),
                        Integer.parseInt(brightnessInput.getText().toString().trim()));
                List<Settings.Point> next = settings.points();
                if (index < 0) next.add(point); else next.set(index, point);
                settings.savePoints(next); dialog.dismiss(); showPage(1);
            } catch (NumberFormatException error) { toast("请输入有效的整数"); }
            catch (IllegalArgumentException error) { toast(error.getMessage()); }
        }));
        dialog.show();
    }

    /** Construct one numeric input with touch-friendly dimensions and no external input dependencies. */
    private EditText numberInput(int value) {
        EditText input = new EditText(this); input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setTextColor(TEXT); input.setTextSize(22); input.setText(String.valueOf(value)); input.setMinHeight(dp(52)); return input;
    }
    /** Boolean callback for persisted native switches. */
    private interface ToggleChange { void set(boolean checked); }
    /** Selection callback for small native choice lists. */
    private interface ChoiceChange { void set(int index); }
    /** Create a switch whose initial checked state does not accidentally rewrite preferences. */
    private Switch toggle(String title, boolean value, ToggleChange listener) {
        Switch control = new Switch(this); control.setText(title); control.setTextColor(TEXT); control.setTextSize(20);
        control.setPadding(dp(8), dp(4), dp(8), dp(4)); control.setMinimumHeight(dp(52)); control.setChecked(value);
        control.setOnCheckedChangeListener((button, checked) -> listener.set(checked)); return control;
    }
    /** Create a spinner that suppresses its initial selection callback and saves only actual changes. */
    private Spinner choices(String[] values, int selection, ChoiceChange listener) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setMinimumHeight(dp(52)); spinner.setSelection(selection);
        final int[] previous = {selection};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            /** Persist only a changed selection, leaving render-only callbacks inert. */
            @Override public void onItemSelected(AdapterView<?> parent, View view, int index, long id) {
                if (view instanceof TextView) { ((TextView) view).setTextColor(TEXT); ((TextView) view).setTextSize(18); }
                if (index != previous[0]) { previous[0] = index; listener.set(index); }
            }
            /** Native spinner requires this callback; no empty-selection state is persisted. */
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        return spinner;
    }
    /** Add a compact value card using ordinary Android views rather than a UI framework. */
    private TextView metric(LinearLayout row, String title) {
        LinearLayout card = vertical(); card.setPadding(dp(16), dp(12), dp(16), dp(12)); card.setBackground(background(PANEL, 12));
        add(card, label(title, 16, MUTED), 4); TextView value = label("—", 30, TEXT); card.addView(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(0, 0, dp(10), 0); row.addView(card, params); return value;
    }
    /** Create one readable native label with consistent spacing. */
    private TextView label(String text, int size, int color) {
        TextView label = new TextView(this); label.setText(text); label.setTextSize(size); label.setTextColor(color);
        label.setGravity(Gravity.CENTER_VERTICAL); return label;
    }
    /** Create a touch-friendly button without adding a support library. */
    private Button button(String title) {
        Button button = new Button(this); button.setText(title); button.setTextSize(18); button.setAllCaps(false);
        button.setTextColor(TEXT); button.setMinHeight(dp(52)); return button;
    }
    /** Create a vertical stack; screen ownership remains with the Activity. */
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    /** Create a horizontal row aligned for touch controls and native labels. */
    private LinearLayout horizontal() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    /** Add one full-width content block with a single bottom gap. */
    private void add(LinearLayout parent, View view, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(bottom); parent.addView(view, params);
    }
    /** Create the small native rounded surface used by the fixed car theme. */
    private GradientDrawable background(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    /** Convert density-independent touch spacing to the connected vehicle's display density. */
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    /** Present a short validation explanation without an additional screen or background task. */
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
