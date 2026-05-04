package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.Intent;
import com.google.android.material.tabs.TabLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import android.os.Bundle;
import android.os.Build;
import android.view.View;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;

import ml.melun.mangaview.R;
import ml.melun.mangaview.adapter.CommentsAdapter;
import ml.melun.mangaview.fragment.CommentsTabFragment;
import ml.melun.mangaview.mangaview.Comment;

import static ml.melun.mangaview.MainApplication.p;

public class CommentsActivity extends AppCompatActivity {

  /** The {@link ViewPager} that will host the section contents. */
  private ViewPager mViewPager;

  ArrayList<Comment> comments, bcomments;
  public CommentsAdapter adapter, badapter;
  Context context;
  TabLayout tab;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    if (p.getDarkTheme()) setTheme(R.style.AppThemeDarkNoTitle);
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_comments);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      getWindow().setStatusBarColor(getResources().getColor(R.color.appSurface));
      getWindow().setNavigationBarColor(getResources().getColor(R.color.appSurface));
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    context = this;
    Intent intent = getIntent();
    tab = this.findViewById(R.id.tab_layout);

    String gsonData = intent.getStringExtra("comments");
    if (gsonData.length() > 0) {
      Gson gson = new Gson();
      comments = gson.fromJson(gsonData, new TypeToken<ArrayList<Comment>>() {}.getType());
      adapter = new CommentsAdapter(context, comments);
      getSupportActionBar().setTitle("댓글 " + comments.size());
    } else {
      getSupportActionBar().setTitle("댓글 없음");
    }

    gsonData = intent.getStringExtra("bestComments");
    if (gsonData.length() > 0) {
      Gson gson = new Gson();
      bcomments = gson.fromJson(gsonData, new TypeToken<ArrayList<Comment>>() {}.getType());
      badapter = new CommentsAdapter(context, bcomments);
      // ((TextView)toolbar.findViewById(R.id.comments_title)).setText("댓글 ["+comments.size()+"]");
    }

    SectionsPagerAdapter mSectionsPagerAdapter =
        new SectionsPagerAdapter(getSupportFragmentManager());

    // Set up the ViewPager with the sections adapter.
    mViewPager = findViewById(R.id.container);
    mViewPager.setAdapter(mSectionsPagerAdapter);
    mViewPager.requestFocus();

    tab.addTab(tab.newTab().setText("전체 댓글"));
    tab.addTab(tab.newTab().setText("베스트 댓글"));

    mViewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tab));
    tab.addOnTabSelectedListener(
        new TabLayout.OnTabSelectedListener() {
          @Override
          public void onTabSelected(TabLayout.Tab tab) {
            mViewPager.setCurrentItem(tab.getPosition());
          }

          @Override
          public void onTabUnselected(TabLayout.Tab tab) {
            //
          }

          @Override
          public void onTabReselected(TabLayout.Tab tab) {
            //
          }
        });

    this.findViewById(R.id.comment_input).setVisibility(View.GONE);
  }

  public class SectionsPagerAdapter extends FragmentPagerAdapter {

    public SectionsPagerAdapter(FragmentManager fm) {
      super(fm);
    }

    @Override
    @NonNull
    public Fragment getItem(int position) {
      CommentsTabFragment tab = new CommentsTabFragment();
      switch (position) {
        case 1:
          // best
          tab.setAdapter(badapter);
          return tab;
        default:
          // comments
          tab.setAdapter(adapter);
          return tab;
      }
    }

    @Override
    public int getCount() {
      // Show 3 total pages.
      return 2;
    }
  }

}
