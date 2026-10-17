package com.anbernic.control.underclock;

import android.content.Context;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import com.anbernic.control.underclock.shared.CustomProfileEditor;
import com.anbernic.control.underclock.shared.CustomProfileValues;

/** Native Control Center UI attachment; backend owns all hardware work. */
public final class UnderclockCard {
    private static final String TAG = "UnderclockCard";
    private static final String MARKER = "rg477v_underclock_card_v1";
    private static final int PERF_CARD_ID = 0x7f090096;
    private static final int PERF_TITLE_ID = 0x7f09017a;
    private static final int PERF_MODES_ID = 0x7f0901e2;
    private static final String WIDGET = "com.anbernic.control.ui.widget.ModeButtonGroupView";
    private static final String LISTENER = WIDGET + "$OnModeSelectedListener";

    /**
     * Getters must read a local cache: never block the UI thread for PowerHAL IPC.
     * Index order is default, small, medium, large (native profile IDs 0,1,3,2).
     * requestProfile must complete asynchronously after application succeeds/fails.
     */
    public interface Controller {
        int getDisplayIndex();
        String statusText();
        void requestProfile(int displayIndex, Result callback);
        CustomProfileValues getCustomProfile(int slot);
        void requestCustomProfile(int slot,CustomProfileValues values,CustomProfileEditor.Completion callback);
    }

    public interface Result {
        void complete();
    }

    private UnderclockCard() {}

    /** Call from the stock fragment after initView, with its binding root View. */
    public static void install(View root, Controller controller) {
        if (root == null || controller == null || root.findViewWithTag(MARKER) != null) return;
        try {
            View perf = root.findViewById(PERF_CARD_ID);
            if (perf == null || !(perf.getParent() instanceof LinearLayout)) {
                Log.w(TAG, "Unexpected stock layout; attachment skipped");
                return;
            }
            LinearLayout parent = (LinearLayout) perf.getParent();
            Context context = root.getContext();
            LinearLayout card = new LinearLayout(context);
            card.setTag(MARKER);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(perf.getPaddingLeft(), perf.getPaddingTop(),
                perf.getPaddingRight(), perf.getPaddingBottom());
            card.setLayoutParams(copyLayout(perf.getLayoutParams()));

            TextView title = new TextView(context);
            title.setText("Under-clock");
            TextView sourceTitle = root.findViewById(PERF_TITLE_ID);
            if (sourceTitle == null) throw new IllegalStateException("Stock Perf mode title missing");
            copyTitleStyle(sourceTitle, title);
            card.addView(PowerOverlay.titleRow(title, copyLayout(sourceTitle.getLayoutParams())));

            ClassLoader loader = context.getClassLoader();
            Class<?> widgetClass = Class.forName(WIDGET, true, loader);
            Class<?> listenerClass = Class.forName(LISTENER, true, loader);
            View modes = (View) widgetClass.getConstructor(Context.class).newInstance(context);
            Method setOptions = widgetClass.getMethod("setOptions", String[].class);
            Method setSelected = widgetClass.getMethod("setSelectedIndex", int.class);
            Method setListener = widgetClass.getMethod("setOnModeSelectedListener", listenerClass);
            Field selectedField = widgetClass.getDeclaredField("selectedIndex");selectedField.setAccessible(true);
            Method redraw = widgetClass.getDeclaredMethod("updateButtonStates");redraw.setAccessible(true);
            setOptions.invoke(modes, (Object) new String[] {"Default", "Small", "Medium", "Large"});
            fitSingleLineButtons(modes);
            View sourceModes = root.findViewById(PERF_MODES_ID);
            if (sourceModes == null) throw new IllegalStateException("Stock Perf mode selector missing");
            card.addView(modes, copyLayout(sourceModes.getLayoutParams()));
            modes.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> mirrorButtonHeight(sourceModes, modes));
            View customModes=(View)widgetClass.getConstructor(Context.class).newInstance(context);
            setOptions.invoke(customModes,(Object)new String[]{"Custom 1","Custom 2","Custom 3","Custom 4"});
            fitSingleLineButtons(customModes);card.addView(customModes,copyLayout(sourceModes.getLayoutParams()));
            customModes.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->mirrorButtonHeight(sourceModes,customModes));
            Attachment attachment = new Attachment(root, card, perf, title, modes,customModes,
                setSelected,selectedField,redraw,controller, loader);
            Object listener = Proxy.newProxyInstance(loader, new Class<?>[] {listenerClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("onModeSelected")) {
                        attachment.select((Integer) args[0]);
                        return null;
                    }
                    return objectMethod(proxy, method, args);
                });
            setListener.invoke(modes, listener);
            Object customListener=Proxy.newProxyInstance(loader,new Class<?>[]{listenerClass},(proxy,method,args)->{
                if(method.getName().equals("onModeSelected")){attachment.edit((Integer)args[0]);return null;}
                return objectMethod(proxy,method,args);
            });setListener.invoke(customModes,customListener);
            card.addOnAttachStateChangeListener(attachment);
            parent.addView(card, parent.indexOfChild(perf) + 1);
            attachment.refresh();
        } catch (Exception | LinkageError error) {
            // A failed attachment must not prevent the original OS controls opening.
            Log.e(TAG, "Control Center attachment failed", error);
        }
    }

    private static final class Attachment implements View.OnAttachStateChangeListener,
        ViewTreeObserver.OnWindowFocusChangeListener {
        private final View root, perf, modes,customModes;
        private final LinearLayout card;
        private final TextView title;
        private final Method setSelected;
        private final Field selectedField;
        private final Method redraw;
        private AlertDialog editor;
        private final Controller controller;
        private final Handler ui = new Handler(Looper.getMainLooper());
        private final ClassLoader loader;
        private Object themeListener;
        private Class<?> themeInterface;
        private Method themeUnregister;
        private boolean attached, applying;
        private long requestGeneration;
        private Drawable lastSourceBackground;
        private String cachedStatus = "";
        private final Runnable refreshTick = new Runnable() {
            @Override public void run() {
                if (!attached || !card.hasWindowFocus()) return;
                refresh();
                ui.postDelayed(this, 1000);
            }
        };

        Attachment(View root, LinearLayout card, View perf, TextView title,
                   View modes,View customModes, Method setSelected,Field selectedField,Method redraw, Controller controller, ClassLoader loader) {
            this.root = root; this.card = card; this.perf = perf; this.title = title;
            this.modes = modes; this.setSelected = setSelected;
            this.customModes=customModes;this.selectedField=selectedField;this.redraw=redraw;
            this.controller = controller; this.loader = loader;
        }

        void refresh() {
            try {
                Drawable background = perf.getBackground();
                if (background != lastSourceBackground && background != null
                    && background.getConstantState() != null) {
                    lastSourceBackground = background;
                    card.setBackground(background.getConstantState()
                        .newDrawable(card.getResources()).mutate());
                }
                TextView sourceTitle = root.findViewById(PERF_TITLE_ID);
                if (sourceTitle != null) {
                    copyTitleStyle(sourceTitle, title);
                }
                int active=valid(controller.getDisplayIndex());
                selection(modes,active<4?active:-1);selection(customModes,active>=4?active-4:-1);
                cachedStatus = controller.statusText();
                PowerOverlay.refresh(card);
            } catch (Exception error) {
                Log.w(TAG, "UI refresh failed", error);
            }
        }

        private void selection(View row,int index) throws Exception {
            if(index>=0)setSelected.invoke(row,index);
            else {selectedField.setInt(row,-1);redraw.invoke(row);}
        }

        void edit(int slot) {
            if(applying||slot<0||slot>3||(editor!=null&&editor.isShowing())){refresh();return;}
            refresh();
            try {
                editor=CustomProfileEditor.show(card.getContext(),slot,controller.getCustomProfile(slot),(values,result)->{
                    applying=true;long generation=++requestGeneration;
                    try {controller.requestCustomProfile(slot,values,error->ui.post(()->{
                        if(generation==requestGeneration){applying=false;if(attached)refresh();}
                        result.complete(error);
                    }));}catch(RuntimeException failure){applying=false;result.complete(failure.getMessage()==null?"Custom 적용 실패":failure.getMessage());}
                },()->{editor=null;if(attached)refresh();});
            }catch(RuntimeException failure){Log.w(TAG,"Custom editor unavailable",failure);refresh();}
        }

        void select(int index) {
            if (applying || index < 0 || index > 3) { refresh(); return; }
            applying = true;
            long generation = ++requestGeneration;
            refresh(); // Show the confirmed setting until backend acknowledges selection.
            try {
                controller.requestProfile(index, () -> ui.post(() -> {
                    if (generation != requestGeneration) return;
                    applying = false;
                    refresh();
                }));
            } catch (RuntimeException error) {
                applying = false;
                refresh();
                Log.w(TAG, "Profile request failed", error);
            }
        }

        @Override public void onViewAttachedToWindow(View view) {
            attached = true;
            card.getViewTreeObserver().addOnWindowFocusChangeListener(this);
            ui.removeCallbacks(refreshTick);
            ui.post(refreshTick);
            try {
                themeInterface = Class.forName("q0.c", true, loader);
                Class<?> themeRegistry = Class.forName("q0.b", true, loader);
                themeUnregister = themeRegistry.getMethod("c", themeInterface);
                themeListener = Proxy.newProxyInstance(loader, new Class<?>[] {themeInterface},
                    (proxy, method, args) -> {
                        if (method.getName().equals("onThemeChanged")) {
                            // Original fragment theme listener runs in the same dispatch.
                            ui.post(this::refresh);
                            return null;
                        }
                        return objectMethod(proxy, method, args);
                    });
                themeRegistry.getMethod("a", themeInterface).invoke(null, themeListener);
            } catch (Exception | LinkageError error) {
                Log.w(TAG, "Theme observer unavailable", error);
            }
        }

        @Override public void onViewDetachedFromWindow(View view) {
            attached = false;
            if(editor!=null){editor.dismiss();editor=null;}
            ui.removeCallbacks(refreshTick);
            if (card.getViewTreeObserver().isAlive()) {
                card.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
            }
            if (themeUnregister != null && themeListener != null) {
                try { themeUnregister.invoke(null, themeListener); }
                catch (Exception error) { Log.w(TAG, "Theme detach failed", error); }
            }
            themeUnregister = null; themeListener = null;
        }

        @Override public void onWindowFocusChanged(boolean focused) {
            ui.removeCallbacks(refreshTick);
            if (attached && focused) ui.post(refreshTick);
        }
    }

    private static int valid(int value) { return value >= 0 && value <= 7 ? value : 0; }
    private static void fitSingleLineButtons(View view) {
        if (view instanceof TextView) {
            TextView button = (TextView) view;
            int maxSp = Math.max(10, (int) Math.ceil(button.getTextSize()
                / button.getResources().getDisplayMetrics().scaledDensity));
            button.setSingleLine(true);
            button.setMaxLines(1);
            button.setHorizontallyScrolling(false);
            button.setEllipsize(null);
            button.setAutoSizeTextTypeUniformWithConfiguration(10, maxSp, 1,
                TypedValue.COMPLEX_UNIT_SP);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                fitSingleLineButtons(group.getChildAt(index));
        }
    }
    private static TextView firstButton(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = firstButton(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }
    private static void mirrorButtonHeight(View sourceModes, View modes) {
        TextView source = firstButton(sourceModes);
        if (source != null && source.getMeasuredHeight() > 0)
            minimumButtonHeight(modes, source.getMeasuredHeight());
    }
    private static void minimumButtonHeight(View view, int height) {
        if (view instanceof TextView) {
            TextView button = (TextView) view;
            if (button.getMinHeight() != height) button.setMinHeight(height);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++)
                minimumButtonHeight(group.getChildAt(index), height);
        }
    }
    private static LinearLayout.LayoutParams copyLayout(ViewGroup.LayoutParams source) {
        if (source instanceof LinearLayout.LayoutParams)
            return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) source);
        if (source instanceof ViewGroup.MarginLayoutParams)
            return new LinearLayout.LayoutParams((ViewGroup.MarginLayoutParams) source);
        return new LinearLayout.LayoutParams(source);
    }
    private static void copyTitleStyle(TextView source, TextView target) {
        target.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
        target.setTypeface(source.getTypeface());
        target.setTextColor(source.getTextColors());
        target.setGravity(source.getGravity());
        target.setIncludeFontPadding(source.getIncludeFontPadding());
        target.setLetterSpacing(source.getLetterSpacing());
        target.setTextScaleX(source.getTextScaleX());
        target.setLineSpacing(source.getLineSpacingExtra(), source.getLineSpacingMultiplier());
        target.setPaintFlags(source.getPaintFlags());
        target.setPadding(source.getPaddingLeft(), source.getPaddingTop(),
            source.getPaddingRight(), source.getPaddingBottom());
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals": return args != null && args.length == 1 && proxy == args[0];
            case "hashCode": return System.identityHashCode(proxy);
            case "toString": return "UnderclockControlCenterListener";
            default: return null;
        }
    }
}
