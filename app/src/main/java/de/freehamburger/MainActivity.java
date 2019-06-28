package de.freehamburger;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.SearchManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.provider.SearchRecentSuggestions;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresPermission;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.content.FileProvider;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.JsonReader;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;

import de.freehamburger.adapters.NewsRecyclerAdapter;
import de.freehamburger.model.BlobParser;
import de.freehamburger.model.Content;
import de.freehamburger.model.Filter;
import de.freehamburger.model.News;
import de.freehamburger.model.Region;
import de.freehamburger.model.Source;
import de.freehamburger.model.StreamQuality;
import de.freehamburger.model.TeaserImage;
import de.freehamburger.model.TextFilter;
import de.freehamburger.model.Video;
import de.freehamburger.supp.SearchSuggestionsProvider;
import de.freehamburger.util.Downloader;
import de.freehamburger.util.FileDeleter;
import de.freehamburger.util.Intro;
import de.freehamburger.util.Log;
import de.freehamburger.util.SpaceBetween;
import de.freehamburger.util.TtfInfo;
import de.freehamburger.util.Util;
import de.freehamburger.views.ClockView;

import static android.os.AsyncTask.THREAD_POOL_EXECUTOR;

public class MainActivity extends HamburgerActivity implements NewsRecyclerAdapter.NewsAdapterController, SwipeRefreshLayout.OnRefreshListener {

    private static final String TAG = "MainActivity";

    private static final String STATE_SOURCE = "de.freehamburger.state.source";
    private static final String STATE_RECENT_SOURCES = "de.freehamburger.state.recentsources";
    private static final String STATE_LIST_POS = "de.freehamburger.state.list.pos";
    private static final String ACTION_CLEAR_SEARCH_HISTORY = "de.freehamburger.action_search_clear";
    private static final String ACTION_IMPORT_FONT = "de.freehamburger.action_font_import";
    private static final String ACTION_DELETE_FONT = "de.freehamburger.action_font_delete";
    /** used when the user has picked a font file to import */
    private static final int REQUEST_CODE_FONT_IMPORT = 815;

    /** maximum number of recent sources/categories to keep */
    private static final int MAX_RECENT_SOURCES = 10;

    /** remembers Sources used recently to provide a 'back' navigation */
    private final Stack<Source> recentSources = new Stack<>();
    private final SparseArray<Source> sourceForMenuItem = new SparseArray<>();
    private CoordinatorLayout coordinatorLayout;
    private SwipeRefreshLayout swipeRefreshLayout;
    private FloatingActionButton fab;
    private RecyclerView recyclerView;
    private NewsRecyclerAdapter newsAdapter;
    private DrawerLayout drawerLayout;
    private ClockView clockView;
    private boolean searchIsLaunched = false;
    private Filter searchFilter = null;
    private int listPositionToRestore = -1;
    @NonNull private Source currentSource = Source.HOME;
    private Snackbar snackbarMaybeQuit;
    private Intro intro;
    /** displays the article picture when the corresponding menu item is invoked */
    private ImageView quickView;
    private boolean quickViewRequestCancelled;
    /** point of time when the user paused this Activity most recently */
    private long pausedAt = -1L;
    private AlertDialog infoDialog;

    /**
     * Switches to another {@link Source}.<br>
     * Does not do anything if the given Source is the current one.
     * @param newSource Source
     * @param addToRecent {@code true} to add the Source to the Stack of recent Sources
     */
    private void changeSource(@NonNull Source newSource, boolean addToRecent) {
        if (newSource == this.currentSource) return;
        //
        if (addToRecent) {
            this.recentSources.push(this.currentSource);
        }
        while (this.recentSources.size() > MAX_RECENT_SOURCES) {
            this.recentSources.remove(0);
        }
        //
        this.currentSource = newSource;
        // scroll to the top because the list will have new contents
        this.listPositionToRestore = 0;
        //
        updateTitle();
        //
        updateMenu();
        //
        onRefreshUseCache();
    }

    /**
     * Removes the temporary search filter.
     */
    private void clearSearch() {
        this.searchFilter = null;
        if (this.newsAdapter != null) this.newsAdapter.clearTemporaryFilters();
        this.clockView.setTint(0);
    }

    /** {@inheritDoc} */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            //if (BuildConfig.DEBUG) Log.i(TAG, "dispatchKeyEvent(" + KeyEvent.keyCodeToString(event.getKeyCode()) + ")");
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MOVE_HOME:
                    this.recyclerView.scrollToPosition(0);
                    break;
                case KeyEvent.KEYCODE_MOVE_END:
                    RecyclerView.LayoutManager lm = this.recyclerView.getLayoutManager();
                    if (lm != null) this.recyclerView.scrollToPosition(lm.getChildCount() - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    this.recyclerView.scrollBy(0, 20);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    this.recyclerView.scrollBy(0, -20);
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** {@inheritDoc} */
    @Override
    public NewsRecyclerAdapter getAdapter() {
        return this.newsAdapter;
    }

    /** {@inheritDoc} */
    @Override
    int getMainLayout() {
        return R.layout.activity_main;
    }

    /**
     * Deals with the Intent that the Acticity has received.
     * @param intent Intent
     */
    private void handleIntent(@Nullable Intent intent) {
        if (intent == null) intent = getIntent();
        if (intent.hasExtra(App.EXTRA_CRASH)) {
            Toast.makeText(this, R.string.msg_sorry, Toast.LENGTH_SHORT).show();
            intent.removeExtra(App.EXTRA_CRASH);
        }

        final String action = intent.getAction();

        if (UpdateJobService.ACTION_NOTIFICATION.equals(action)) {
            final String newsExternalId = intent.getStringExtra(UpdateJobService.EXTRA_FROM_NOTIFICATION);
            if (newsExternalId != null) {
                intent.removeExtra(UpdateJobService.EXTRA_FROM_NOTIFICATION);
                // scroll to matching article
                this.handler.postDelayed(() -> {
                    int index = MainActivity.this.newsAdapter.findNews(newsExternalId);
                    if (index >= 0) {
                        if (BuildConfig.DEBUG) Log.i(TAG, "News with externalId '" + newsExternalId + "' found at pos. " + index);
                        MainActivity.this.recyclerView.smoothScrollToPosition(index);
                    } else if (BuildConfig.DEBUG) Log.e(TAG, "News with externalId '" + newsExternalId + "' not found!");
                }, 1_000L);
            }
            this.currentSource = UpdateJobService.SOURCE;
            this.listPositionToRestore = 0;
            updateTitle();
            return;
        }

        if (ACTION_DELETE_FONT.equals(action)) {
            File fontFile = new File(getFilesDir(), App.FONT_FILE);
            if (fontFile.isFile()) {
                Util.deleteFile(fontFile);
                if (this.newsAdapter != null) {
                    this.newsAdapter.setTypeface(null);
                }
                Toast.makeText(getApplicationContext(), R.string.msg_font_deleted, Toast.LENGTH_SHORT).show();
            }
            Intent settingsActivityIntent = new Intent(this, SettingsActivity.class);
            settingsActivityIntent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, SettingsActivity.AppearancePreferenceFragment.class.getName());
            startActivity(settingsActivityIntent);
            return;
        }
        if (ACTION_IMPORT_FONT.equals(action)) {
            Intent pickIntent = new Intent(Intent.ACTION_GET_CONTENT);
            //pickIntent.setType("application/x-font-ttf");
            pickIntent.setType("*/*");
            pickIntent.addCategory(Intent.CATEGORY_OPENABLE);
            pickIntent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            startActivityForResult(pickIntent, REQUEST_CODE_FONT_IMPORT);
            return;
        }
        if (ACTION_CLEAR_SEARCH_HISTORY.equals(action)) {
            SearchRecentSuggestions suggestions = new SearchRecentSuggestions(this, SearchSuggestionsProvider.AUTHORITY, SearchSuggestionsProvider.MODE);
            suggestions.clearHistory();
            Toast.makeText(getApplicationContext(), R.string.msg_search_cleared, Toast.LENGTH_SHORT).show();
            Intent settingsActivityIntent = new Intent(this, SettingsActivity.class);
            settingsActivityIntent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, SettingsActivity.StoragePreferenceFragment.class.getName());
            startActivity(settingsActivityIntent);
            return;
        }
        if (Intent.ACTION_SEARCH.equals(action)) {
            String query = intent.getStringExtra(SearchManager.QUERY);
            if (BuildConfig.DEBUG) Log.i(TAG, "Search query is '" + query + "'");
            this.searchFilter = new TextFilter(query.toLowerCase(Locale.GERMAN).trim(), true, true);
            SearchRecentSuggestions suggestions = new SearchRecentSuggestions(this, SearchSuggestionsProvider.AUTHORITY, SearchSuggestionsProvider.MODE);
            suggestions.saveRecentQuery(query, null);
            onRefreshUseCache();
        } else if (Intent.ACTION_VIEW.equals(action)) {
            handleViewAction(intent);
        } else if ("action_section_news".equals(action)) {
            this.currentSource = Source.NEWS;
            updateTitle();
        } else if ("action_section_sport".equals(action)) {
            this.currentSource = Source.SPORT;
            updateTitle();
        } else if ("action_section_ausland".equals(action)) {
            this.currentSource = Source.AUSLAND;
            updateTitle();
        } else if ("action_section_inland".equals(action)) {
            this.currentSource = Source.INLAND;
            updateTitle();
        } else if ("action_section_wirtschaft".equals(action)) {
            this.currentSource = Source.WIRTSCHAFT;
            updateTitle();
        } else if ("action_section_regional".equals(action)) {
            this.currentSource = Source.REGIONAL;
            updateTitle();
        } else if (BuildConfig.DEBUG && action != null && !Intent.ACTION_MAIN.equals(action)) {
            Log.w(TAG, "Unhandled action: " + action);
        }
    }

    /**
     * Handles {@link Intent#ACTION_VIEW}.
     * @param intent Intent
     */
    private void handleViewAction(@NonNull Intent intent) {
        Uri data = intent.getData();
        String scheme = intent.getScheme();
        if (BuildConfig.DEBUG) Log.i(TAG, "ACTION_VIEW with data = \"" + data + "\", scheme=" + scheme + ", type=" + intent.getType());
        if (data == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "No url received!");
            finish();
            return;
        }
        // check whether it is a ttf file
        if (data.toString().toLowerCase(java.util.Locale.US).endsWith(".ttf")) {
            // it is a ttf file -> import it, and then proceed normally
            importTtf(data, true);
            if (this.newsAdapter != null) this.newsAdapter.setTypeface(Util.loadFont(this));
            return;
        } else if ("content".equals(intent.getScheme()) && "*/*".equals(intent.getType())) {
            // some stoopid doodle siftware has passed meaningless nonsense to this app
            // (data like "content://com.android.providers.downloads.documents/document/476", type="*/*")
            // let's try to find out whether it is a ttf file
            if (importTtf(data, false)) {
                // apparently if was indeed a ttf file -> proceed normally
                if (this.newsAdapter != null) this.newsAdapter.setTypeface(Util.loadFont(this));
                return;
            }
        }
        String host = data.getHost();
        if (("https".equals(scheme) || "http".equals(scheme)) && !"www.tagesschau.de".equalsIgnoreCase(host)) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Invalid host \"" + host + "\"");
            finish();
        }
        // if we are here, we did not find anything we could do with that VIEW intent, so proceed normally
    }

    /** {@inheritDoc} */
    @Override
    boolean hasMenuOverflowButton() {
        return false;
    }

    /**
     * Imports a ttf font file.<br>
     * Upon success, a {@link Snackbar} will be displayed.
     * @param uri Uri to read from
     * @param showMsgUponFail {@code true} to display a Snackbar upon failure
     * @return true / false
     */
    private boolean importTtf(@RequiresPermission @NonNull Uri uri, final boolean showMsgUponFail) {
        InputStream in = null;
        boolean ok = true;
        //
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                if (showMsgUponFail) {
                    Snackbar.make(MainActivity.this.coordinatorLayout, R.string.msg_font_import_failed, Snackbar.LENGTH_LONG).show();
                }
                return false;
            }
            // copy data to a temporary file
            File tempFile = File.createTempFile("tempfont", ".ttf");
            // set a safety barrier of 4 MB; assumed that a truetype font file will not be bigger
            Util.copyFile(in, tempFile, -1, 1_048_576L << 2);
            Util.close(in);
            in = null;
            // check whether the temporary file contains indeed a ttf file
            TtfInfo ttfInfo = TtfInfo.getTtfInfo(tempFile);
            String fontName = ttfInfo.getFontFullName();
            if (TextUtils.isEmpty(fontName)) {
                if (BuildConfig.DEBUG) Log.w(TAG, "The data apparently does not belong to a ttf file!");
                if (showMsgUponFail) {
                    Snackbar.make(MainActivity.this.coordinatorLayout, R.string.msg_font_import_failed, Snackbar.LENGTH_LONG).show();
                }
                Util.deleteFile(tempFile);
                return false;
            }
            // rename temp file to the font file
            File fontFile = new File(getFilesDir(), App.FONT_FILE);
            if (!tempFile.renameTo(fontFile)) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Could not rename temp file to \"" + fontFile.getName() + "\"!");
                if (showMsgUponFail) {
                    Snackbar.make(MainActivity.this.coordinatorLayout, R.string.msg_font_import_failed, Snackbar.LENGTH_LONG).show();
                }
                Util.deleteFile(tempFile);
                return false;
            }
            Typeface tf = Util.loadFont(this);
            if (tf != null) {
                this.newsAdapter.setTypeface(tf);
                Snackbar sb;
                if (!TextUtils.isEmpty(fontName)) {
                    sb = Snackbar.make(MainActivity.this.coordinatorLayout, getString(R.string.msg_font_import_done_ext, fontName), Snackbar.LENGTH_LONG);
                } else {
                    sb = Snackbar.make(MainActivity.this.coordinatorLayout, R.string.msg_font_import_done, Snackbar.LENGTH_LONG);
                }
                sb.show();
            } else {
                if (showMsgUponFail) {
                    Snackbar.make(MainActivity.this.coordinatorLayout, R.string.msg_font_import_failed, Snackbar.LENGTH_LONG).show();
                }
                Util.deleteFile(fontFile);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (TextUtils.isEmpty(msg)) msg = e.toString();
            if (BuildConfig.DEBUG) Log.e(TAG, msg);
            if (showMsgUponFail) {
                if (msg.contains("EACCES")) {
                    Snackbar.make(MainActivity.this.coordinatorLayout, R.string.error_permission_denied, Snackbar.LENGTH_LONG).show();
                } else {
                    Snackbar.make(MainActivity.this.coordinatorLayout, R.string.msg_font_import_failed, Snackbar.LENGTH_LONG).show();
                }
            }
            ok = false;
        } finally {
            Util.close(in);
        }
        return ok;
    }

    /**
     * Displays the News' details in a {@link NewsActivity}.
     * @param news News
     */
    private void loadDetails(@NonNull News news) {
        String url = news.getDetails();
        if (url == null) return;
        try {
            File tempFile = File.createTempFile("details", ".json");
            this.service.loadFile(url, tempFile, (completed, result) -> {
                if (!completed || result == null) {
                    return;
                }
                if (result.rc >= 400) {
                    Snackbar.make(MainActivity.this.coordinatorLayout, getString(R.string.error_download_failed, result.toString()), Snackbar.LENGTH_LONG).show();
                    return;
                }
                JsonReader reader = null;
                try {
                    reader = new JsonReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(tempFile)), StandardCharsets.UTF_8));
                    reader.setLenient(true);
                    News parsed = News.parseNews(reader, news.isRegional());
                    Util.close(reader);
                    reader = null;
                    Intent intent = new Intent(MainActivity.this, NewsActivity.class);
                    intent.putExtra(NewsActivity.EXTRA_NEWS, parsed);
                    startActivity(intent, ActivityOptionsCompat.makeCustomAnimation(this, R.anim.fadein, R.anim.fadeout).toBundle());
                } catch (Exception e) {
                    Util.close(reader);
                    if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
                    Snackbar.make(MainActivity.this.coordinatorLayout, R.string.error_parsing, Snackbar.LENGTH_LONG).show();
                } finally {
                    Util.deleteFile(tempFile);
                }
            });
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString(), e);
        }
    }

    /**
     * Quits the app, if the user agrees.
     */
    private void maybeQuit() {
        if (this.snackbarMaybeQuit != null && this.snackbarMaybeQuit.isShown()) {
            this.snackbarMaybeQuit.dismiss();
            finish();
            return;
        }
        boolean ask = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(App.PREF_ASK_BEFORE_FINISH, true);
        if (ask) {
            // close drawer because otherwise the snackbar action is not visible
            this.drawerLayout.closeDrawer(Gravity.END, true);
            // ask user whether (s)he had enough
            this.snackbarMaybeQuit = Snackbar.make(this.coordinatorLayout, R.string.action_quit, 5_000);
            this.snackbarMaybeQuit.setAction(R.string.label_yes, v -> finish());
            Util.fadeSnackbar(this.snackbarMaybeQuit, 4900L);
        } else {
            finish();
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_CODE_FONT_IMPORT) {
            Uri fontUri = data.getData();
            if (fontUri != null) {
                if (importTtf(fontUri, true)) {
                    if (this.newsAdapter != null) this.newsAdapter.setTypeface(Util.loadFont(this));
                }
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * The user has pressed the back button for a veery loong time.
     * @return {@code true} if the action has been dealt with
     */
    private boolean onBackLongPressed() {
        if (this.recentSources.isEmpty()) return false;
        ListAdapter adapter = new BaseAdapter() {

            final int n = MainActivity.this.recentSources.size();

            @Override
            public int getCount() {
                return n;
            }

            @Override
            public Object getItem(int position) {
                return MainActivity.this.recentSources.get(n - position - 1);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv;
                if (convertView instanceof TextView) {
                    tv =(TextView)convertView;
                } else {
                    tv = new TextView(MainActivity.this);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        tv.setTextAppearance(R.style.TextAppearance_AppCompat_Body2_White);
                    } else {
                        tv.setTextAppearance(MainActivity.this, R.style.TextAppearance_AppCompat_Body2_White);
                    }
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16); // standard seems to be 14 which is a wee bit small
                    tv.setMaxLines(1);
                    tv.setPadding(80,8,8,8);
                }
                Source source = (Source)getItem(position);
                tv.setText(getString(source.getLabel()));
                return tv;
            }
        };
        AlertDialog ad = new AlertDialog.Builder(this)
                .setTitle(R.string.label_recent_sources)
                .setAdapter(adapter, (dialog, which) -> {
                    Source selected = (Source)adapter.getItem(which);
                    // remove the sources from recentSources that follow the selected one
                    int n = adapter.getCount();
                    for (int i = which + 1; i < n; i++) {
                        MainActivity.this.recentSources.pop();
                    }
                    //
                    changeSource(selected, false);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel())
                .setPositiveButton(R.string.action_finish, (dialog, which) -> {
                    dialog.dismiss();
                    finish();
                })
                .create();
        Window dw = ad.getWindow();
        if (dw != null) {
            dw.setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        ad.supportRequestWindowFeature(Window.FEATURE_SWIPE_TO_DISMISS);
        ad.show();
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void onBackPressed() {
        // if a search filter had been applied, accept the back button as command to remove it
        if (this.newsAdapter != null && this.newsAdapter.hasTemporaryFilter()) {
            clearSearch();
            return;
        }
        // if the quickView is shown, accept the back button as command to hide it
        if (this.quickView.getVisibility() == View.VISIBLE) {
            onQuickViewClicked(this.quickView);
            return;
        }
        @App.BackButtonBehaviour int useBack = PreferenceManager.getDefaultSharedPreferences(this).getInt(App.PREF_USE_BACK_IN_APP, App.USE_BACK_FINISH);
        switch (useBack) {
                case App.USE_BACK_FINISH:
                    maybeQuit();
                    return;
                case App.USE_BACK_HOME:
                    if (this.currentSource != Source.HOME) {
                        this.listPositionToRestore = 0;
                        this.recentSources.clear();
                        changeSource(Source.HOME, false);
                    } else {
                        maybeQuit();
                    }
                    return;
                case App.USE_BACK_BACK:
                    if (!this.recentSources.isEmpty()) {
                        this.currentSource = this.recentSources.pop();
                        this.listPositionToRestore = 0;
                        updateTitle();
                        updateMenu();
                        onRefreshUseCache();
                    } else {
                        maybeQuit();
                    }
                    return;
        }

        super.onBackPressed();
    }

    /** {@inheritDoc} <br><br>
     * For preparation of the context menu, see {@link NewsRecyclerAdapter.ViewHolder#onCreateContextMenu(ContextMenu, View, ContextMenu.ContextMenuInfo)}
     */
    @Override
    public boolean onContextItemSelected(MenuItem menuItem) {
        int id = menuItem.getItemId();
        if (id == R.id.action_share_news) {
            News news = this.newsAdapter.getItem(this.newsAdapter.getContextMenuIndex());
            // prefer title over topline because it's usually more informative
            String title = news.getTitle();
            if (TextUtils.isEmpty(title)) title = news.getTopline();
            Intent intent = new Intent(Intent.ACTION_SEND);
            // shares the news' detailsWeb URL
            intent.putExtra(Intent.EXTRA_TEXT, news.getDetailsWeb());
            if (!TextUtils.isEmpty(title)) {
                intent.putExtra(Intent.EXTRA_SUBJECT, title);
            }
            intent.setType("text/plain");
            if (getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                startActivity(intent);
            } else {
                Snackbar.make(this.coordinatorLayout, R.string.error_no_app, Snackbar.LENGTH_LONG).show();
            }
            return true;
        }
        if (id == R.id.action_share_video) {
            News news = this.newsAdapter.getItem(this.newsAdapter.getContextMenuIndex());
            if (news.getContent() == null || !news.getContent().hasVideo()) return true;
            List<Video> videoList = news.getContent().getVideoList();
            Video video = videoList.get(0);
            //TODO handle more than 1 video per Content
            if (BuildConfig.DEBUG && videoList.size() > 1) {
                Log.w(TAG, "Has more than one video: " + news);
            }
            //
            final Map<StreamQuality, String> streams = video.getStreams();
            if (streams.size() > 1) {
                CharSequence[] items = new CharSequence[streams.size()];
                int i = 0;
                final List<StreamQuality> qualities = new ArrayList<>(streams.keySet());
                Collections.sort(qualities, (o1, o2) -> {
                    int w1 = o1.getWidth();
                    int w2 = o2.getWidth();
                    if (w1 == w2) return o1.name().compareToIgnoreCase(o2.name());
                    if (w1 == -1) return 1; else if (w2 == -1) return -1;
                    return Integer.compare(w1, w2);
                });
                for (StreamQuality q : qualities) {
                    int labelRes = q.getLabel();
                    int width = q.getWidth();
                    items[i++] = labelRes > -1 ? (width > 0 ? getString(labelRes, width) : getString(labelRes)) : q.name();
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(this)
                        .setTitle(R.string.action_quality_select)
                        .setItems(items, (dialog, which) -> {
                            StreamQuality selectedQuality = qualities.get(which);
                            String source = streams.get(selectedQuality);
                            if (source != null) Util.sendUrl(this, source, news.getTitle());
                            dialog.dismiss();
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel());
                if ("de".equals(Locale.getDefault().getLanguage()) && qualities.contains(StreamQuality.ADAPTIVESTREAMING)) {
                    builder.setNeutralButton(getString(R.string.label_streamquality_adaptive) + "? (Wikipedia)", (dialog, which) -> {
                        Intent wotswotwot = new Intent(Intent.ACTION_VIEW);
                        wotswotwot.setDataAndType(Uri.parse("https://de.wikipedia.org/wiki/HTTP-Streaming"), "text/html");
                        startActivity(wotswotwot);
                        dialog.cancel();
                    });
                }
                AlertDialog d = builder.create();
                Window dw = d.getWindow();
                if (dw != null) dw.setBackgroundDrawableResource(R.drawable.bg_dialog);
                d.show();
            } else {
                Util.sendUrl(this, streams.values().iterator().next(), news.getTitle());
            }
            return true;
        }
        if (id == R.id.action_share_image) {
            News news = this.newsAdapter.getItem(this.newsAdapter.getContextMenuIndex());
            TeaserImage image = news.getTeaserImage();
            if (image == null) return true;
            String url = image.getBestImage();
            if (url == null) return true;
            String title = news.getTitle();
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_TEXT, url);
            if (!TextUtils.isEmpty(title)) {
                intent.putExtra(Intent.EXTRA_SUBJECT, title);
            }
            intent.setType("text/plain");
            if (getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                startActivity(intent);
            } else {
                Snackbar.make(this.coordinatorLayout, R.string.error_no_app, Snackbar.LENGTH_LONG).show();
            }
            return true;
        }
        if (id == R.id.action_view_picture) {
            News news = this.newsAdapter.getItem(this.newsAdapter.getContextMenuIndex());
            TeaserImage image = news.getTeaserImage();
            if (image == null) return true;
            String url = image.getBestImage();
            if (url == null) return true;
            String newsid = news.getExternalId();
            if (newsid == null) newsid = "temp";
            final File temp = new File(getCacheDir(), "quik_" + newsid.replace('/', '_').replace('>', '_') + ".jpg");
            findViewById(R.id.plane).setVisibility(View.VISIBLE);
            this.service.loadFile(url, temp, temp.lastModified(), (completed, result) -> {
                if (MainActivity.this.quickViewRequestCancelled) {
                    MainActivity.this.quickViewRequestCancelled = false;
                    FileDeleter.add(temp);
                    findViewById(R.id.plane).setVisibility(View.GONE);
                    return;
                }
                if (!completed || result == null || result.rc >= 400) {
                    FileDeleter.add(temp);
                    findViewById(R.id.plane).setVisibility(View.GONE);
                    if (result != null && !TextUtils.isEmpty(result.msg)) {
                        Snackbar.make(MainActivity.this.coordinatorLayout, getString(R.string.error_download_failed, result.msg), Snackbar.LENGTH_SHORT).show();
                    } else {
                        Snackbar.make(MainActivity.this.coordinatorLayout, R.string.error_download_failed2, Snackbar.LENGTH_SHORT).show();
                    }
                    return;
                }
                Bitmap bm = BitmapFactory.decodeFile(temp.getAbsolutePath());
                if (bm != null) {
                    MainActivity.this.quickView.setVisibility(View.VISIBLE);
                    MainActivity.this.quickView.setImageBitmap(bm);
                } else {
                    findViewById(R.id.plane).setVisibility(View.GONE);
                    if (!TextUtils.isEmpty(result.msg)) {
                        Snackbar.make(MainActivity.this.coordinatorLayout, getString(R.string.error_download_failed, result.msg), Snackbar.LENGTH_SHORT).show();
                    } else {
                        Snackbar.make(MainActivity.this.coordinatorLayout, R.string.error_download_failed2, Snackbar.LENGTH_SHORT).show();
                    }
                }
                FileDeleter.add(temp);
            });
            return true;
        }
        return super.onContextItemSelected(menuItem);
    }

    /** {@inheritDoc} */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            if (BuildConfig.DEBUG) Log.i(TAG, "onCreate(): savedInstanceState is non-null");
            this.currentSource = Source.valueOf(savedInstanceState.getString(STATE_SOURCE, Source.HOME.name()));
            updateTitle();
            this.recentSources.clear();
            String[] recentSources = savedInstanceState.getStringArray(STATE_RECENT_SOURCES);
            if (recentSources != null) {
                for (String rs : recentSources) {
                    try {
                        Source source = Source.valueOf(rs);
                        this.recentSources.add(source);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            this.listPositionToRestore = savedInstanceState.getInt(STATE_LIST_POS, -1);
        }
        super.onCreate(savedInstanceState);

        setVolumeControlStream(App.STREAM_TYPE);

        this.drawerLayout = findViewById(R.id.drawerLayout);
        NavigationView navigationView = findViewById(R.id.navigationView);
        // add some space between the menu items
        try {
            RecyclerView navMenuView = (RecyclerView)navigationView.getChildAt(0);
            navMenuView.addItemDecoration(new SpaceBetween(this, getResources().getDimensionPixelSize(R.dimen.space_between_menu_items)));
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, e.toString());
        }
        // prepare SparseArray to match menu item id to Source
        this.sourceForMenuItem.put(R.id.action_section_homepage, Source.HOME);
        this.sourceForMenuItem.put(R.id.action_section_news, Source.NEWS);
        this.sourceForMenuItem.put(R.id.action_section_inland, Source.INLAND);
        this.sourceForMenuItem.put(R.id.action_section_ausland, Source.AUSLAND);
        this.sourceForMenuItem.put(R.id.action_section_wirtschaft, Source.WIRTSCHAFT);
        this.sourceForMenuItem.put(R.id.action_section_sport, Source.SPORT);
        this.sourceForMenuItem.put(R.id.action_section_regional, Source.REGIONAL);
        this.sourceForMenuItem.put(R.id.action_section_video, Source.VIDEO);
        this.sourceForMenuItem.put(R.id.action_section_channels, Source.CHANNELS);
        // check menuItem matching currentSource on initialisation
        updateMenu();
        // react to selections being made in the navigation menu
        navigationView.setNavigationItemSelectedListener(
                menuItem -> {
                    // ignore if intro is playing
                    if (MainActivity.this.intro != null && MainActivity.this.intro.isPlaying()) return true;
                    // get selected menu item
                    int id = menuItem.getItemId();
                    // when item is tapped, close drawer (after a brief pause to let the user see the new selection)
                    MainActivity.this.handler.postDelayed(() -> MainActivity.this.drawerLayout.closeDrawer(Gravity.END, true), 500L);
                    // select source that matches the menu item
                    changeSource(MainActivity.this.sourceForMenuItem.get(id), true);
                    return true;
                });
        // refresh via top-bottom swipe
        this.swipeRefreshLayout = findViewById(R.id.swiperefresh);
        if (this.swipeRefreshLayout != null) {
            this.swipeRefreshLayout.setOnRefreshListener(this);
            this.swipeRefreshLayout.setNestedScrollingEnabled(false);
        }
        //
        this.coordinatorLayout = findViewById(R.id.coordinator_layout);
        this.fab = findViewById(R.id.fab);
        this.quickView = findViewById(R.id.quickView);
        this.recyclerView = findViewById(R.id.recyclerView);
        RecyclerView.LayoutManager lm;
        // one column for phones, more columns for tablets
        if (Util.isXLargeTablet(this)) {
            int numColumns;
            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                numColumns = PreferenceManager.getDefaultSharedPreferences(this).getInt(App.PREF_MAIN_COLS_TABLET_LANDSCAPE, App.PREF_MAIN_COLS_TABLET_LANDSCAPE_DEFAULT);
            } else {
                numColumns = PreferenceManager.getDefaultSharedPreferences(this).getInt(App.PREF_MAIN_COLS_TABLET_PORTRAIT, App.PREF_MAIN_COLS_TABLET_PORTRAIT_DEFAULT);
            }
            lm = new GridLayoutManager(this, numColumns);
        } else {
            lm = new LinearLayoutManager(this);
            lm.setItemPrefetchEnabled(true);
        }
        this.recyclerView.setLayoutManager(lm);
        // enable context menus for news items
        this.recyclerView.setOnCreateContextMenuListener(this);
        //
        this.newsAdapter = new NewsRecyclerAdapter(this, Util.loadFont(this));
        this.recyclerView.setAdapter(this.newsAdapter);
        this.clockView = findViewById(R.id.clockView);
        updateTitle();
        this.clockView.setOnClickListener(v -> openOptionsMenu());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            this.fab.setOnClickListener(v -> {
                MainActivity.this.recyclerView.smoothScrollToPosition(0);
                ((AppBarLayout)findViewById(R.id.appbar_layout)).setExpanded(true);
            });
            this.recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {

                private final int displayHeight = Util.getDisplayHeight(MainActivity.this);
                private final Runnable fabHider = () -> MainActivity.this.fab.hide(null);
                private int verticalScrollPosition = 0;

                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    MainActivity.this.handler.removeCallbacks(this.fabHider);
                    this.verticalScrollPosition += dy;
                    if (this.verticalScrollPosition > this.displayHeight) {
                        MainActivity.this.fab.show(null);
                        //TODO set elevation because for some reason it is beneath other views as defined in the xmls
                        MainActivity.this.fab.setElevation(48f);
                        MainActivity.this.handler.postDelayed(this.fabHider, HIDE_FAB_AFTER);
                    } else {
                        MainActivity.this.fab.hide(null);
                    }
                }
             });

        } else {
            this.fab.hide(null);
        }

        handleIntent(null);
    }

    /** {@inheritDoc} */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.clock_menu, menu);
        // if there is a qwert? keyboard connected, display menu shortcuts (btw, they are activated via the ctrl modifier)
        if (getResources().getConfiguration().keyboard == Configuration.KEYBOARD_QWERTY) {
            Util.decorateMenuWithShortcuts(menu);
        }
        //
        if (!BuildConfig.DEBUG) {
            MenuItem getLog = menu.findItem(R.id.action_get_log);
            if (getLog != null) getLog.setVisible(false);
            MenuItem delLog = menu.findItem(R.id.action_delete_log);
            if (delLog != null) delLog.setVisible(false);
            MenuItem showUpdates = menu.findItem(R.id.action_show_updates);
            if (showUpdates != null) showUpdates.setVisible(false);
        }
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (onBackLongPressed()) return true;
            maybeQuit();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    /**
     * Handles the user's menu item selection.
     * @param item MenuItem
     * @return {@code true} if the menu item selection has been handled
     * @throws NullPointerException if {@code item} is {@code null}
     */
    private boolean onMenuItemSelected(@NonNull MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.action_search) {
            onSearchRequested();
            return true;
        }
        if (id == R.id.action_filter) {
            startActivity(new Intent(this, FilterActivity.class));
            return true;
        }
        if (id == R.id.action_select_regions) {
            AlertDialog ad = Region.selectRegions(this);
            ad.setOnDismissListener(dialog -> onRefreshUseCache());
            return true;
        }
        if (id == R.id.action_teletext) {
            Intent intent = new Intent(this, WebViewActivity.class);
            intent.putExtra(WebViewActivity.EXTRA_URL, App.URL_TELETEXT);
            startActivity(intent);
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_show_updates) {
            List<Long> tss = UpdateJobService.getAllRequests(this);
            int n = tss.size();
            if (n < 1) return true;
            if (n > 150) tss = tss.subList(n - 150, n);
            final DateFormat df = DateFormat.getDateTimeInstance();
            final StringBuilder sb = new StringBuilder(512);
            int i = 1;
            long latest = tss.get(0);
            sb.append(i++).append(": ").append(df.format(new Date(latest))).append('\n');
            for (Long ts : tss) {
                sb.append(i++).append(": ").append(df.format(new Date(ts))).append(" (").append(Math.round((ts - latest) / 60000f)).append(" Min.)\n");
                latest = ts;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("All past background updates");
            builder.setMessage(sb);
            builder.setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss());
            builder.show();
            return true;
        }
        if (id == R.id.action_delete_log) {
            File logFile = Log.getFile();
            if (logFile == null || !logFile.isFile()) return true;
            boolean deleted = Log.deleteFile();
            Snackbar.make(this.coordinatorLayout, "Log file deleted: " + deleted, Snackbar.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.action_get_log) {
            if (!BuildConfig.DEBUG) return true;
            File logFile = Log.getFile();
            if (logFile == null || !logFile.isFile()) return true;
            File subdir = new File(getCacheDir(), App.EXPORTS_DIR);
            if (!subdir.isDirectory()) {
                if (!subdir.mkdirs()) return true;
            }
            Log.sync();
            String now = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date());

            File tempFile = new File(subdir, "log_" + now.replace(' ', '-').replace(':', '_').replace('.', '_').replace('/', '_') + ".txt");
            synchronized (Log.FILE_LOCK) {
                if (!Util.copyFile(logFile, tempFile, 0L)) {
                    Log.e(TAG, "Failed to copy " + logFile + " to " + tempFile);
                    return true;
                }
                if (tempFile.length() != logFile.length()) Log.e(TAG, "Exported log file differs from actual log file!");
            }
            Log.i(TAG, "Sharing log file \"" + tempFile + "\" of " + tempFile.length() + " bytes...");
            final Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this, App.getFileProvider(), tempFile));
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Log " + now);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (getPackageManager().resolveActivity(shareIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                startActivity(shareIntent);
            } else {
                Snackbar.make(this.coordinatorLayout, R.string.error_no_app, Snackbar.LENGTH_LONG).show();
            }
            return true;
        }
        if (id == R.id.action_info) {
            final SpannableStringBuilder info = new SpannableStringBuilder().append('\n');
            SpannableString title = new SpannableString(getString(R.string.app_name));
            title.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            SpannableString version = new SpannableString(BuildConfig.VERSION_NAME);
            version.setSpan(new RelativeSizeSpan(0.75f), 0, version.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            info.append(title).append(' ').append(version);
            info.append("\n\n").append(getString(R.string.app_build_date, DateFormat.getDateTimeInstance().format(new Date(BuildConfig.BUILD_TIME))));
            info.append("\n\n").append(getString(R.string.app_license));
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppAlertDialogTheme)
                    .setTitle(R.string.action_info)
                    .setMessage(info)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                    .setNeutralButton(R.string.label_license, (dialog, which) -> {
                        dialog.dismiss();
                        List<String> l = Util.loadResourceTextFile(MainActivity.this, R.raw.agpl, 662);
                        StringBuilder sb = new StringBuilder(34523);
                        for (String line : l) sb.append(line).append('\n');
                        @SuppressLint("InflateParams")
                        ScrollView sv = (ScrollView)getLayoutInflater().inflate(R.layout.multi_text_view, null);
                        TextView tv = sv.findViewById(R.id.textView);
                        tv.setTextScaleX(0.75f);
                        tv.setText(sb);
                        tv.setContentDescription(getString(R.string.label_license));
                        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        AlertDialog.Builder lb = new AlertDialog.Builder(MainActivity.this, R.style.AppAlertDialogTheme)
                                .setTitle(R.string.label_license)
                                .setView(sv)
                                .setPositiveButton(android.R.string.ok, (dialog1, which1) -> dialog1.dismiss());
                        if (cm != null) {
                            lb.setNeutralButton(android.R.string.copy, (dialog12, which12) -> {
                                cm.setPrimaryClip(ClipData.newPlainText("AGPL-3.0", sb));
                                Snackbar.make(coordinatorLayout, R.string.msg_text_copied, Snackbar.LENGTH_SHORT).show();
                            });
                        }
                        this.infoDialog = lb.create();
                        Window dw = this.infoDialog.getWindow();
                        if (dw != null) dw.setBackgroundDrawableResource(R.drawable.bg_dialog);
                        this.infoDialog.supportRequestWindowFeature(Window.FEATURE_SWIPE_TO_DISMISS);
                        this.infoDialog.show();
                    })
                    ;
            this.infoDialog = builder.create();
            Window dw = this.infoDialog.getWindow();
            if (dw != null) dw.setBackgroundDrawableResource(R.drawable.bg_dialog);
            this.infoDialog.supportRequestWindowFeature(Window.FEATURE_SWIPE_TO_DISMISS);
            this.infoDialog.show();
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    protected void onNewIntent(Intent intent) {
        handleIntent(intent);
    }

    /** {@inheritDoc} */
    @Override
    public void onNewsClicked(@NonNull News news, View v, float x, float y) {
        if (this.intro != null && this.intro.isPlaying()) return;
        final Intent intent;
        @News.NewsType String type = news.getType();
        String urlToDetailsJson = news.getDetails();
        Content content = news.getContent();
        boolean isStory = News.NEWS_TYPE_STORY.equals(type);
        boolean isWebVw = News.NEWS_TYPE_WEBVIEW.equals(type);
        boolean isVideo = News.NEWS_TYPE_VIDEO.equals(type);
        //
        if (isStory && content != null) {
            intent = new Intent(this, NewsActivity.class);
        } else if (isStory && !TextUtils.isEmpty(urlToDetailsJson)) {
            loadDetails(news);
            intent = null;
        } else if (isWebVw
                || (isStory && TextUtils.isEmpty(urlToDetailsJson) && !TextUtils.isEmpty(news.getDetailsWeb()))) {   // <- in the "news" section the items do not have a "content"
            if (!Util.isNetworkAvailable(this)) {
                showNoNetworkSnackbar();
                intent = null;
            } else {
                intent = new Intent(this, WebViewActivity.class);
            }
        } else if (isVideo) {
            if (!Util.isNetworkAvailable(this)) {
                showNoNetworkSnackbar();
                intent = null;
            } else {
                if (Util.isNetworkMobile(this)) {
                    boolean loadVideos = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(App.PREF_LOAD_VIDEOS_OVER_MOBILE, App.DEFAULT_LOAD_VIDEOS_OVER_MOBILE);
                    if (!loadVideos) {
                        intent = null;
                        Snackbar.make(coordinatorLayout, R.string.pref_title_pref_load_videos_over_mobile_off, Snackbar.LENGTH_SHORT).show();
                    } else {
                        intent = new Intent(this, VideoActivity.class);
                    }
                } else {
                    intent = new Intent(this, VideoActivity.class);
                }
            }
        } else {
            intent = new Intent(this, NewsActivity.class);
        }
        //
        if (intent != null) {
            intent.putExtra(NewsActivity.EXTRA_NEWS, news);
            startActivity(intent, ActivityOptionsCompat.makeScaleUpAnimation(v, (int) x, (int) y, v.getWidth(), v.getHeight()).toBundle());
            clearSearch();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (this.intro != null && this.intro.isPlaying()) return false;
        return onMenuItemSelected(item);
    }

    /** {@inheritDoc} */
    @Override
    protected void onPause() {
        this.pausedAt = System.currentTimeMillis();
        if (this.infoDialog != null && this.infoDialog.isShowing()) this.infoDialog.dismiss();
        if (this.intro != null && this.intro.isPlaying()) {
            this.intro.cancel();
            // play the intro again next time
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(App.PREF_PLAY_INTRO, true);
            ed.apply();
            // we either need to a) start anew or b) know what state the ui is currently in (is the blurry plane visible or not, etc.) to be able to clean up
            // a) is easier
            finish();
        }
        super.onPause();
    }

    /**
     * The user has tapped the semi-transparent plane which, when visible, covers all other elements in the screen.
     * @param ignored ignored View
     */
    public void onPlaneClicked(@Nullable View ignored) {
        if (this.quickView.getVisibility() == View.VISIBLE) {
            onQuickViewClicked(this.quickView);
        } else {
            this.quickViewRequestCancelled = true;
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean network = Util.isNetworkAvailable(this);
        MenuItem itemTeletext = menu.findItem(R.id.action_teletext);
        itemTeletext.setVisible(network);
        return super.onPrepareOptionsMenu(menu);
    }

    /**
     * The user has tapped the 'quick view' which, when visible, displays the article's {@link News#getTeaserImage() teaser image}.
     * @param ignored ignored View
     */
    @SuppressWarnings("unused") // studio considers this unused, but it's referred to in content_main.xml...
    public void onQuickViewClicked(@Nullable View ignored) {
        this.quickView.setVisibility(View.GONE);
        this.quickView.setImageBitmap(null);
        View plane = findViewById(R.id.plane);
        if (plane != null) plane.setVisibility(View.GONE);
    }

    /** {@inheritDoc} */
    @Override
    public void onRefresh() {
        App app = (App) getApplicationContext();
        // check whether loading is possible
        if (!Util.isNetworkAvailable(app)) {
            this.swipeRefreshLayout.setRefreshing(false);
            showNoNetworkSnackbar();
            return;
        }
        if (this.service == null) {
            this.swipeRefreshLayout.setRefreshing(false);
            return;
        }

        // load remote file
        String url = this.currentSource.getUrl();
        if (this.currentSource.needsParams()) {
            if (this.currentSource == Source.REGIONAL) {
                url = url + Source.getParamsForRegional(this);
            } else {
                // unhandled case; source needs parameters but we don't know how to append them => revert to default source
                changeSource(Source.HOME, true);
                url = this.currentSource.getUrl();
            }
        }

        long mostRecentUpdate = app.getMostRecentUpdate(this.currentSource);
        this.service.loadFile(url, app.getLocalFile(this.currentSource), mostRecentUpdate, new Downloader.DownloaderListener() {

            // let's remember the Source that we are loading now - in case the user changes it while we are loading...
            private final Source sourceToSetOnSuccess = MainActivity.this.currentSource;

            /** {@inheritDoc} */
            @Override
            public void downloaded(boolean completed, @Nullable Downloader.Result result) {
                if (!completed || result == null || result.file == null) {
                    MainActivity.this.swipeRefreshLayout.setRefreshing(false);
                    return;
                }
                if (result.rc == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    Snackbar.make(MainActivity.this.coordinatorLayout, R.string.msg_not_modified, Snackbar.LENGTH_SHORT).show();
                    parseLocalFileAsync(result.file);
                    return;
                }
                if (result.rc != HttpURLConnection.HTTP_OK) {
                    MainActivity.this.swipeRefreshLayout.setRefreshing(false);
                    String msg = result.msg != null ? result.msg : "HTTP " + result.rc;
                    if ("Pin verification failed".equals(msg)) {
                        msg = getString(R.string.error_pin_verification);
                    } else if ("timeout".equals(msg)) {
                        msg = getString(R.string.error_timeout);
                    }
                    Snackbar.make(MainActivity.this.coordinatorLayout, msg, BuildConfig.DEBUG ? Snackbar.LENGTH_INDEFINITE : Snackbar.LENGTH_LONG).show();
                    return;
                }

                if (MainActivity.this.currentSource != this.sourceToSetOnSuccess) {
                    MainActivity.this.currentSource = this.sourceToSetOnSuccess;
                    updateTitle();
                }
                ((App)getApplicationContext()).setMostRecentUpdate(MainActivity.this.currentSource, System.currentTimeMillis(), true);
                parseLocalFileAsync(result.file);
            }
        });
    }

    /**
     * Similar to {@link #onRefresh()} but tries the cache first.
     */
    private void onRefreshUseCache() {
        App app = (App)getApplicationContext();
        boolean networkAvailable = Util.isNetworkAvailable(this);
        // first try the local file
        final File localFile = app.getLocalFile(this.currentSource);
        if (localFile.isFile()) {
            /*
            NOTE:
            the last modified timestamp denotes the point in time of the most recent article in that file
            It does not denote the most recent attempt (successful or not) to download the data!
             */
            boolean fileIsQuiteNew = System.currentTimeMillis() - app.getMostRecentUpdate(this.currentSource) < App.LOCAL_FILE_MAXAGE;
            if (fileIsQuiteNew || !networkAvailable) {
                /*
                if (BuildConfig.DEBUG) {
                    if (!networkAvailable) Log.i(TAG, "The local file's \"" +  localFile + "\" content shall be sufficient for now because there's no network connection");
                    else Log.i(TAG, "The local file's \"" +  localFile + "\" content shall be sufficient for now because it's younger than " + Math.round(App.LOCAL_FILE_MAXAGE/60000f) + " mins");
                }
                */
                parseLocalFileAsync(localFile);
                if (!networkAvailable) {
                    showNoNetworkSnackbar();
                }
                return;
            }
        }
        // if there is neither a local file nor a network connection, there is nothing we can do here
        if (!networkAvailable) {
            showNoNetworkSnackbar();
            return;
        }
        if (BuildConfig.DEBUG) {
            if (!localFile.isFile()) Log.i(TAG, "Loading remote file because local file \""+ localFile + "\" does not exist");
            else {
                long lm = app.getMostRecentUpdate(this.currentSource);
                if (System.currentTimeMillis() - lm >= App.LOCAL_FILE_MAXAGE) Log.i(TAG, "Loading remote file because local file \"" + localFile + "\" is older than " + Math.round(App.LOCAL_FILE_MAXAGE/60000f) + " min.: " + new Date(lm)  + " (" + lm + ")");
            }
        }
        // if there is no local file or if it is too old, download the resource
        this.swipeRefreshLayout.setRefreshing(true);
        onRefresh();
    }

    /** {@inheritDoc} */
    @Override
    protected void onRestart() {
        super.onRestart();
        if (this.pausedAt > 0L && System.currentTimeMillis() - this.pausedAt > 300_000L) {
            // scroll to the top if the most recent user interaction was quite a while ago
            this.listPositionToRestore = 0;
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onResume() {
        super.onResume();
        if (BuildConfig.DEBUG) Log.i(TAG, "onResume()");
        this.newsAdapter.setFilters(TextFilter.createTextFiltersFromPreferences(this));
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean(App.PREF_PLAY_INTRO, true)) {
            playIntro();
            SharedPreferences.Editor ed = prefs.edit();
            ed.putBoolean(App.PREF_PLAY_INTRO, false);
            ed.apply();
        }
        if (this.listPositionToRestore >= 0) {
            if (BuildConfig.DEBUG) Log.i(TAG, "Restoring list position " + this.listPositionToRestore);
            RecyclerView.LayoutManager lm = this.recyclerView.getLayoutManager();
            if (lm != null) lm.scrollToPosition(this.listPositionToRestore);
            this.listPositionToRestore = -1;
        }
    }

    /** {@inheritDoc} */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_SOURCE, this.currentSource.name());
        String[] recentSourcesArray = new String[this.recentSources.size()];
        for (int i = 0; i < recentSourcesArray.length; i++) {
            recentSourcesArray[i] = this.recentSources.get(i).name();
        }
        outState.putStringArray(STATE_RECENT_SOURCES, recentSourcesArray);
        //
        RecyclerView.LayoutManager rlm = this.recyclerView.getLayoutManager();
        int top = RecyclerView.NO_POSITION;
        if (rlm instanceof LinearLayoutManager) {
            LinearLayoutManager lm = (LinearLayoutManager) rlm;
            top = lm.findFirstVisibleItemPosition();
        } else if (rlm instanceof StaggeredGridLayoutManager) {
            StaggeredGridLayoutManager slm = (StaggeredGridLayoutManager)rlm;
            int[] position = new int[slm.getSpanCount()];
            slm.findFirstVisibleItemPositions(position);
            top = position[0];
        }
        if (top == RecyclerView.NO_POSITION) {
            super.onSaveInstanceState(outState);
            return;
        }
        //if (BuildConfig.DEBUG) Log.i(TAG, "onSaveInstanceState(): Remembering list pos: " + top);
        outState.putInt(STATE_LIST_POS, top);
        //
        super.onSaveInstanceState(outState);
    }

    /** {@inheritDoc} */
    @Override
    public boolean onSearchRequested() {
        this.searchIsLaunched = super.onSearchRequested();
        if (this.searchIsLaunched) {
            SearchManager sm = (SearchManager) getSystemService(SEARCH_SERVICE);
            if (sm == null) return this.searchIsLaunched;
            sm.setOnDismissListener(() -> {
                if (BuildConfig.DEBUG) Log.i(TAG, "Search dismissed.");
                MainActivity.this.handler.postDelayed(() -> {
                    MainActivity.this.searchIsLaunched = false;
                    SearchManager sm1 = (SearchManager) getSystemService(SEARCH_SERVICE);
                    if (sm1 != null) sm1.setOnDismissListener(null);
                }, 500L);
            });
        }
        return this.searchIsLaunched;
    }

    /** {@inheritDoc} */
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        super.onServiceConnected(name, service);
        // check whether we need to refresh (if the data is too old or if the adapter is empty or if it displays the wrong data)
        boolean adapterDataIsOld = System.currentTimeMillis() - this.newsAdapter.getUpdated() >= App.LOCAL_FILE_MAXAGE;
        if (adapterDataIsOld || this.newsAdapter.getItemCount() == 0 || this.currentSource != this.newsAdapter.getSource()) {
            onRefreshUseCache();
        }
    }

    /**
     * Parses the given json file and updates the {@link #newsAdapter adapter} afterwards.<br>
     * Does not do anything if the file does not exist.
     * @param file File to parse
     */
    private void parseLocalFileAsync(@Nullable File file) {
        if (file == null) {
            return;
        }
        BlobParser blobParser = new BlobParser(this, (blob, ok, oops) -> {
            if (!ok || blob == null || oops != null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Parsing failed: " + oops);
                Snackbar sb = Snackbar.make(MainActivity.this.coordinatorLayout, R.string.error_parsing, Snackbar.LENGTH_INDEFINITE);
                sb.setAction("↻", v -> handler.postDelayed(this::onRefresh, 500L));
                sb.setActionTextColor(getResources().getColor(R.color.colorPrimaryLight));
                Util.setSnackbarActionFont(sb, Typeface.DEFAULT_BOLD, 26f);
                sb.show();
                MainActivity.this.swipeRefreshLayout.setRefreshing(false);
                return;
            }
            List<News> jointList = blob.getAllNews();
            MainActivity.this.newsAdapter.setNewsList(jointList, MainActivity.this.currentSource);
            Date date = blob.getDate();
            if (date != null) {
                if (!file.setLastModified(date.getTime())) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Failed to set last modified date!");
                }
            }
            MainActivity.this.newsAdapter.addFilter(MainActivity.this.searchFilter);
            boolean filtered = MainActivity.this.newsAdapter.hasTemporaryFilter();
            MainActivity.this.clockView.setTime(date != null ? date.getTime() : file.lastModified());
            if (filtered) {
                MainActivity.this.clockView.setTint(Util.getColor(this, R.color.colorFilter));
            } else {
                MainActivity.this.clockView.setTint(0);
            }

            MainActivity.this.swipeRefreshLayout.setRefreshing(false);
            //
            if (filtered) {
                if (MainActivity.this.newsAdapter.getItemCount() == 0) {
                    Snackbar.make(MainActivity.this.coordinatorLayout, getString(R.string.msg_not_found, searchFilter.getText()), Snackbar.LENGTH_LONG).show();
                } else {
                    String msg = getString(R.string.msg_found, searchFilter.getText(), newsAdapter.getItemCount());
                    Snackbar sb = Snackbar.make(MainActivity.this.coordinatorLayout, msg, 5_000);
                    sb.setAction(android.R.string.ok, v -> {
                        sb.dismiss();
                        onBackPressed();
                    });
                    sb.show();
                }
            }
            //
            ((App)getApplicationContext()).trimCacheIfNeeded();

            if (MainActivity.this.listPositionToRestore >= 0) {
                if (BuildConfig.DEBUG) Log.i(TAG, "Restoring list position " + listPositionToRestore);
                RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
                if (lm != null) lm.scrollToPosition(MainActivity.this.listPositionToRestore);
                MainActivity.this.listPositionToRestore = -1;
            }
        });
        blobParser.executeOnExecutor(THREAD_POOL_EXECUTOR, file);
    }

    private void playIntro() {
        final int tint = 0x77ff0000;
        if (this.intro != null && this.intro.isPlaying()) return;
        if (!Util.isNetworkAvailable(this)) {
            // display a hint to the user if (s)he starts the app for the first time and there is no network connection
            Snackbar sb = Snackbar.make(coordinatorLayout, R.string.error_no_network_ext, Snackbar.LENGTH_INDEFINITE);
            Util.setSnackbarFont(sb, Util.CONDENSED, 12f);
            Intent settingsIntent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            if (getPackageManager().resolveActivity(settingsIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                // the unicode wrench symbol 🔧 (0x1f527)
                sb.setAction("\uD83D\uDD27", v -> {
                    sb.dismiss();
                    if (MainActivity.this.intro != null && MainActivity.this.intro.isPlaying()) {
                        MainActivity.this.intro.cancel();
                    }
                    startActivity(settingsIntent);
                });
            } else {
                sb.setAction(android.R.string.ok, v -> sb.dismiss());
                sb.setActionTextColor(Color.RED);
            }
            sb.show();
        }
        this.intro = new Intro(this);
        // show plane and open drawer
        Intro.Step step1 = new Intro.Step(5_000) {
            @Override
            public void run() {
                if (MainActivity.this.isFinishing()) return;
                MainActivity.this.recyclerView.scrollToPosition(0);
                View plane = findViewById(R.id.plane);
                plane.setVisibility(View.VISIBLE);
                MainActivity.this.drawerLayout.openDrawer(Gravity.END);
                Toast.makeText(MainActivity.this, R.string.intro_1, Toast.LENGTH_LONG).show();
            }
        };
        this.intro.addStep(step1);
        // dye drawer reddish
        Intro.Step step1b = new Intro.Step(1_000) {
            @Override
            public void run() {
                android.support.design.widget.NavigationView navigationView = findViewById(R.id.navigationView);
                navigationView.setBackgroundTintList(ColorStateList.valueOf(tint));
            }
        };
        this.intro.addStep(step1b);
        // reset drawer color
        Intro.Step step1c = new Intro.Step(1_000) {
            @Override
            public void run() {
                android.support.design.widget.NavigationView navigationView = findViewById(R.id.navigationView);
                navigationView.setBackgroundTintList(null);
            }
        };
        this.intro.addStep(step1c);
        // close drawer
        Intro.Step step2 = new Intro.Step(5_000) {
            @Override
            public void run() {
                findViewById(R.id.navigationView).setBackgroundTintList(null);
                drawerLayout.closeDrawer(Gravity.END);
            }
        };
        this.intro.addStep(step2);
        // colorise clock and tap it
        Intro.Step step3 = new Intro.Step(1_000) {
            @Override
            public void run() {
                clockView.setTint(tint);
                clockView.performClick();
                Toast.makeText(MainActivity.this, R.string.intro_2, Toast.LENGTH_LONG).show();
            }
        };
        this.intro.addStep(step3);
        // reset clock color and close options menu
        Intro.Step step4 = new Intro.Step(6_000) {
            @Override
            public void run() {
                MainActivity.this.clockView.setTint(0);
                closeOptionsMenu();
            }
        };
        this.intro.addStep(step4);
        final Point ds = Util.getDisplaySize(this);
        // scroll down
        Intro.Step step5 = new Intro.Step(1_000) {
            @Override
            public void run() {
                MainActivity.this.recyclerView.smoothScrollBy(0, ds.y >> 1);
                Toast.makeText(MainActivity.this, R.string.intro_3, Toast.LENGTH_LONG).show();
                MainActivity.this.recyclerView.smoothScrollBy(0, ds.y >> 1);
            }
        };
        this.intro.addStep(step5);
        // scroll up
        Intro.Step step6 = new Intro.Step(1_000) {
            @Override
            public void run() {
                MainActivity.this.recyclerView.smoothScrollToPosition(0);
            }
        };
        this.intro.addStep(step6);
        //
        Intro.Step step7 = new Intro.Step(2_000) {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, R.string.intro_4, Toast.LENGTH_LONG).show();
            }
        };
        this.intro.addStep(step7);
        //
        Intro.Step step7b = new Intro.Step(1_000) {
            @Override
            public void run() {
                RecyclerView.ViewHolder vh = MainActivity.this.recyclerView.findViewHolderForAdapterPosition(0);
                if (vh == null) return;
                View plane = findViewById(R.id.plane);
                plane.setTranslationY(vh.itemView.getHeight());
                ColorStateList originalTint = vh.itemView.getBackgroundTintList();
                vh.itemView.setTag(originalTint);
                vh.itemView.setBackgroundTintList(ColorStateList.valueOf(tint));
                vh.itemView.setPressed(true);
                vh.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS | HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING | HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            }
        };
        this.intro.addStep(step7b);
        //
        Intro.Step step7c = new Intro.Step(2_000) {
            @Override
            public void run() {
                RecyclerView.ViewHolder vh = MainActivity.this.recyclerView.findViewHolderForAdapterPosition(0);
                if (vh == null) return;
                ColorStateList originalTint = (ColorStateList)vh.itemView.getTag();
                vh.itemView.setPressed(false);
                vh.itemView.setBackgroundTintList(originalTint);
                vh.itemView.setTag(null);
                View plane = findViewById(R.id.plane);
                plane.setTranslationY(0);
            }
        };
        this.intro.addStep(step7c);
        //  hide plane
        Intro.Step lastStep = new Intro.Step(4_000) {
            @Override
            public void run() {
                View plane = findViewById(R.id.plane);
                plane.setVisibility(View.GONE);
                Toast.makeText(MainActivity.this, R.string.intro_5, Toast.LENGTH_LONG).show();
            }
        };
        this.intro.addStep(lastStep);
        //
        this.handler.post(this.intro);
    }

    /**
     * Selects the menu item in the navigation view that matches the current source.
     */
    private void updateMenu() {
        int index = this.sourceForMenuItem.indexOfValue(this.currentSource);
        final int menuid = index >= 0 ? this.sourceForMenuItem.keyAt(index): Integer.MIN_VALUE;
        final Menu menu = ((NavigationView)findViewById(R.id.navigationView)).getMenu();
        final int n = menu.size();
        for (int i = 0; i < n; i++) {
            MenuItem item = menu.getItem(i);
            item.setChecked(menuid == item.getItemId());
        }
    }

    /**
     * To be called whenever the current source has changed:
     * Updates the title shown in {@link #clockView} and the task description based on {@link #currentSource}.
     */
    private void updateTitle() {
        if (this.clockView == null) {
            this.handler.postDelayed(this::updateTitle, 500L);
            return;
        }
        String s = getString(this.currentSource.getLabel());
        this.clockView.setText(s);
        setTaskDescription(new ActivityManager.TaskDescription(getString(R.string.app_task_description, s), null, Util.getColor(this, R.color.colorPrimary)));
    }

}
