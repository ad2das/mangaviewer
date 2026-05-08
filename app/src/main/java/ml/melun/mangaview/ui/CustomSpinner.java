package ml.melun.mangaview.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ListView;

import androidx.appcompat.widget.ListPopupWindow;

import java.lang.reflect.Field;

import ml.melun.mangaview.adapter.CustomSpinnerAdapter;
import ml.melun.mangaview.mangaview.Manga;

public class CustomSpinner extends androidx.appcompat.widget.AppCompatSpinner {

    public CustomSpinner(Context context) {
        super(context);
    }

    public CustomSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomSpinner(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        post(this::centerSelectedDropDownItem);
        return handled;
    }

    @Override
    public void setSelection(int position) {
        super.setSelection(position);
    }

    public void setSelection(Manga m) {
        CustomSpinnerAdapter adapter = (CustomSpinnerAdapter) getAdapter();
        for(int i=0; i<adapter.getCount(); i++){
            if(m.equals((Manga)adapter.getItem(i))) {
                setSelection(i, true);
            }
        }
    }

    private void centerSelectedDropDownItem() {
        try {
            ListView listView = getDropDownListView();
            if(listView == null || listView.getHeight() <= 0) {
                postDelayed(this::centerSelectedDropDownItem, 50);
                return;
            }
            int selected = getSelectedItemPosition();
            if(selected < 0)
                return;
            int rowHeight = estimateRowHeight(listView);
            int offset = Math.max(0, (listView.getHeight() - rowHeight) / 2);
            listView.setSelectionFromTop(selected, offset);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private ListView getDropDownListView() throws Exception {
        Field popupField = androidx.appcompat.widget.AppCompatSpinner.class.getDeclaredField("mPopup");
        popupField.setAccessible(true);
        Object popup = popupField.get(this);
        if(popup instanceof ListPopupWindow)
            return ((ListPopupWindow) popup).getListView();
        return null;
    }

    private int estimateRowHeight(ListView listView) {
        if(listView.getChildCount() > 0 && listView.getChildAt(0).getHeight() > 0)
            return listView.getChildAt(0).getHeight();
        return (int) (50 * getResources().getDisplayMetrics().density + 0.5f);
    }

}
