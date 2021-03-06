package de.freehamburger.model;

import android.content.Context;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.Map;

import de.freehamburger.R;
import de.freehamburger.util.Util;

/**
 *
 */
public enum StreamQuality {
    /** 320 x 180 */
    H264S(R.string.label_streamquality_s, 320),
    /** 480 x 270 */
    PODCASTVIDEOM(-1, 480),
    /** 512 x 288 */
    H264M(R.string.label_streamquality_m, 512),
    /** 960 x 540 */
    H264L(R.string.label_streamquality_l, 960),
    /** 1280 x 720 */
    H264XL(R.string.label_streamquality_xl, 1280),
    /** unknown dimensions */
    PODCASTVIDEOM_IAS(-1, -1),
    /** unknown dimensions;<br><em>NOTE: as of March to June 2020, these URLs do not work as they point to hls.….de which only works with http: but NOT with https:!</em> */
    ADAPTIVESTREAMING(R.string.label_streamquality_adaptive, -1);

    /** StreamQuality array where XL is preferred; after that ordered by descending quality */
    private static final StreamQuality[] PREF_XL = new StreamQuality[] {StreamQuality.H264XL, StreamQuality.H264L, StreamQuality.H264M, StreamQuality.H264S};
    /** StreamQuality array where L is preferred */
    private static final StreamQuality[] PREF_L = new StreamQuality[] {StreamQuality.H264L, StreamQuality.H264XL, StreamQuality.H264M, StreamQuality.H264S};
    /** StreamQuality array where M is preferred */
    private static final StreamQuality[] PREF_M = new StreamQuality[] {StreamQuality.H264M, StreamQuality.H264L, StreamQuality.H264XL, StreamQuality.H264S};
    /** StreamQuality array where S is preferred; after that ordered by ascending quality */
    private static final StreamQuality[] PREF_S = new StreamQuality[] {StreamQuality.H264S, StreamQuality.H264M, StreamQuality.H264L, StreamQuality.H264XL};
    /** video width */
    private final int width;
    @StringRes private final int label;

    /**
     * Attempts to return a video stream url, based on the given Map.<br>
     * If the network connection is a mobile connection, one quality level lower will be picked.
     * @param ctx Context (allows picking a quality based on screen size and network connection)
     * @param streams Map
     * @return video stream url
     */
    @Nullable
    public static String getStreamsUrl(@Nullable Context ctx, @Nullable final Map<StreamQuality, String> streams) {
        if (streams == null || streams.isEmpty()) return null;

        final StreamQuality[] pref;
        if (ctx != null) {
            final boolean mobile = Util.isNetworkMobile(ctx);
            final int screenWidth = Util.getDisplaySize(ctx).x;
            // if network is mobile, pick one quality level below
            if (screenWidth >= StreamQuality.H264XL.getWidth()) pref = mobile ? PREF_L : PREF_XL;
            else if (screenWidth >= StreamQuality.H264L.getWidth()) pref = mobile ? PREF_M : PREF_L;
            else if (screenWidth >= StreamQuality.H264M.getWidth()) pref = mobile ? PREF_S : PREF_M;
            else pref = PREF_S;
        } else {
            pref = PREF_S;
        }
        for (StreamQuality sq : pref) {
            if (streams.containsKey(sq)) {
                return streams.get(sq);
            }
        }
        // sometimes (live programs) there is only "adaptivestreaming"
        String adaptive = streams.get(StreamQuality.ADAPTIVESTREAMING);
        if (adaptive != null) return adaptive;
        // anything?!
        if (!streams.isEmpty()) {
            return streams.values().iterator().next();
        }
        // apparently not
        return null;
    }

    /**
     * Constructor.
     * @param label string resource to use as label
     * @param width video width
     */
    StreamQuality(@StringRes int label, int width) {
        this.label = label;
        this.width = width;
    }

    /**
     * Returns the string resource id of the label.
     * @return string resource id of the label
     */
    @StringRes
    public int getLabel() {
        return label;
    }

    /**
     * Returns the width of the video stream.
     * @return width of the video stream or -1, if not known (which is the case for {@link #ADAPTIVESTREAMING})
     */
    @IntRange(from = -1)
    public int getWidth() {
        return width;
    }
}
