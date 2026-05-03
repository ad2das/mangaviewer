package ml.melun.mangaview.adapter;

import android.content.Context;
import android.os.Parcelable;

import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ml.melun.mangaview.fragment.ViewerPageFragment;
import ml.melun.mangaview.interfaces.PageInterface;
import ml.melun.mangaview.mangaview.Decoder;
import ml.melun.mangaview.mangaview.Manga;

import static ml.melun.mangaview.MainApplication.p;

public class ViewerPagerAdapter extends FragmentStatePagerAdapter
{
    FragmentManager fm;
    int width;
    Context context;
    PageInterface itf;
    List<String> imgs;
    int seed;
    int mangaId;
    public ViewerPagerAdapter(FragmentManager fm, int width, Context context, PageInterface i) {
        super(fm);
        this.fm = fm;
        this.width = width;
        this.context = context;
        this.itf = i;
        imgs = new ArrayList<>();
    }

    public void setManga(Manga m){
        List<String> source = m.getImgs(context);
        imgs = source == null ? new ArrayList<>() : new ArrayList<>(source);
        if (p.getPageRtl()) Collections.reverse(imgs);
        seed = m.getSeed();
        mangaId = m.getId();
        notifyDataSetChanged();
    }
    @Override
    public int getItemPosition(Object object) {
        return POSITION_NONE;
    }
    @Override
    public androidx.fragment.app.Fragment getItem(int position)
    {
        return ViewerPageFragment.create(imgs.get(position), new Decoder(seed, mangaId), width, context, () -> itf.onPageClick());
    }
    @Override
    public int getCount()
    {
        return imgs.size();
    }

    @Override
    public Parcelable saveState()
    {
        return null;
    }

}
