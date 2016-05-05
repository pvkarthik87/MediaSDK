package com.kar.mediaservice;

import android.content.Context;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.widget.MediaController;

import com.google.android.exoplayer.CodecCounters;
import com.google.android.exoplayer.DummyTrackRenderer;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.SingleSampleSource;
import com.google.android.exoplayer.TimeRange;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioTrack;
import com.google.android.exoplayer.chunk.ChunkSampleSource;
import com.google.android.exoplayer.chunk.Format;
import com.google.android.exoplayer.dash.DashChunkSource;
import com.google.android.exoplayer.drm.MediaDrmCallback;
import com.google.android.exoplayer.drm.StreamingDrmSessionManager;
import com.google.android.exoplayer.extractor.ExtractorSampleSource;
import com.google.android.exoplayer.hls.HlsSampleSource;
import com.google.android.exoplayer.metadata.MetadataTrackRenderer;
import com.google.android.exoplayer.metadata.id3.Id3Frame;
import com.google.android.exoplayer.text.Cue;
import com.google.android.exoplayer.text.TextRenderer;
import com.google.android.exoplayer.upstream.BandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.util.DebugTextViewHelper;
import com.google.android.exoplayer.util.PlayerControl;
import com.google.android.exoplayer.util.Util;
import com.kar.mediaservice.renderers.DashRendererBuilder;
import com.kar.mediaservice.renderers.ExtractorRendererBuilder;
import com.kar.mediaservice.renderers.HlsRendererBuilder;
import com.kar.mediaservice.renderers.SmoothStreamingRendererBuilder;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by Karthik on 5/3/2016.
 */


/**
 * A wrapper around {@link ExoPlayer} that provides a higher level interface. It can be prepared
 * with one of a number of {@link RendererBuilder} classes to suit different use cases (e.g. DASH,
 * SmoothStreaming and so on).
 */
public class MediaSDKService implements ExoPlayer.Listener, ChunkSampleSource.EventListener,
        HlsSampleSource.EventListener, ExtractorSampleSource.EventListener,
        SingleSampleSource.EventListener, DefaultBandwidthMeter.EventListener,
        MediaCodecVideoTrackRenderer.EventListener, MediaCodecAudioTrackRenderer.EventListener,
        StreamingDrmSessionManager.EventListener, DashChunkSource.EventListener, TextRenderer,
        MetadataTrackRenderer.MetadataRenderer<List<Id3Frame>>, DebugTextViewHelper.Provider {

    /**
     * Builds renderers for the mExoPlayer.
     */
    public interface RendererBuilder {
        /**
         * Builds renderers for playback.
         *
         * @param player The mExoPlayer for which renderers are being built. {@link MediaSDKService#onRenderers}
         *               should be invoked once the renderers have been built. If building fails,
         *               {@link MediaSDKService#onRenderersError} should be invoked.
         */
        void buildRenderers(MediaSDKService player);

        /**
         * Cancels the current build operation, if there is one. Else does nothing.
         * <p/>
         * A canceled build operation must not invoke {@link MediaSDKService#onRenderers} or
         * {@link MediaSDKService#onRenderersError} on the mExoPlayer, which may have been released.
         */
        void cancel();
    }

    /**
     * A listener for core events.
     */
    public interface Listener {
        void onStateChanged(boolean playWhenReady, int playbackState);

        void onError(Exception e);

        void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                                float pixelWidthHeightRatio);
    }

    /**
     * A listener for internal errors.
     * <p/>
     * These errors are not visible to the user, and hence this listener is provided for
     * informational purposes only. Note however that an internal error may cause a fatal
     * error if the mExoPlayer fails to recover. If this happens, {@link Listener#onError(Exception)}
     * will be invoked.
     */
    public interface InternalErrorListener {
        void onRendererInitializationError(Exception e);

        void onAudioTrackInitializationError(AudioTrack.InitializationException e);

        void onAudioTrackWriteError(AudioTrack.WriteException e);

        void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs);

        void onDecoderInitializationError(MediaCodecTrackRenderer.DecoderInitializationException e);

        void onCryptoError(MediaCodec.CryptoException e);

        void onLoadError(int sourceId, IOException e);

        void onDrmSessionManagerError(Exception e);
    }

    /**
     * A listener for debugging information.
     */
    public interface InfoListener {
        void onVideoFormatEnabled(Format format, int trigger, long mediaTimeMs);

        void onAudioFormatEnabled(Format format, int trigger, long mediaTimeMs);

        void onDroppedFrames(int count, long elapsed);

        void onBandwidthSample(int elapsedMs, long bytes, long bitrateEstimate);

        void onLoadStarted(int sourceId, long length, int type, int trigger, Format format,
                           long mediaStartTimeMs, long mediaEndTimeMs);

        void onLoadCompleted(int sourceId, long bytesLoaded, int type, int trigger, Format format,
                             long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs);

        void onDecoderInitialized(String decoderName, long elapsedRealtimeMs,
                                  long initializationDurationMs);

        void onAvailableRangeChanged(int sourceId, TimeRange availableRange);
    }

    /**
     * A listener for receiving notifications of timed text.
     */
    public interface CaptionListener {
        void onCues(List<Cue> cues);
    }

    /**
     * A listener for receiving ID3 metadata parsed from the media stream.
     */
    public interface Id3MetadataListener {
        void onId3Metadata(List<Id3Frame> id3Frames);
    }

    // Constants pulled into this class for convenience.
    public static final int STATE_IDLE = ExoPlayer.STATE_IDLE;
    public static final int STATE_PREPARING = ExoPlayer.STATE_PREPARING;
    public static final int STATE_BUFFERING = ExoPlayer.STATE_BUFFERING;
    public static final int STATE_READY = ExoPlayer.STATE_READY;
    public static final int STATE_ENDED = ExoPlayer.STATE_ENDED;
    public static final int TRACK_DISABLED = ExoPlayer.TRACK_DISABLED;
    public static final int TRACK_DEFAULT = ExoPlayer.TRACK_DEFAULT;

    public static final int RENDERER_COUNT = 4;
    public static final int TYPE_VIDEO = 0;
    public static final int TYPE_AUDIO = 1;
    public static final int TYPE_TEXT = 2;
    public static final int TYPE_METADATA = 3;

    private static final int RENDERER_BUILDING_STATE_IDLE = 1;
    private static final int RENDERER_BUILDING_STATE_BUILDING = 2;
    private static final int RENDERER_BUILDING_STATE_BUILT = 3;

    private static final CookieManager defaultCookieManager;

    static {
        defaultCookieManager = new CookieManager();
        defaultCookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    }

    private RendererBuilder mCurrRendererBuilder;
    private final ExoPlayer mExoPlayer;
    private final PlayerControl mPlayerControl;
    private final Handler mMainHandler;
    private final CopyOnWriteArrayList<Listener> mListeners;

    private int mCurrRendererBuildingState;
    private int mLastReportedPlaybackState;
    private boolean mLastReportedPlayWhenReady;

    private Surface mSurface;
    private TrackRenderer mVideoRenderer;
    private CodecCounters mCodecCounters;
    private Format VvideoFormat;
    private int mVideoTrackToRestore;

    private BandwidthMeter mBandwidthMeter;
    private boolean mBackgrounded;

    private CaptionListener mCaptionListener;
    private Id3MetadataListener mId3MetadataListener;
    private InternalErrorListener mInternalErrorListener;
    private InfoListener mInfoListener;
    private MediaController mMediaController;
    private Context mCtx;
    private boolean mIsAdaptable = true;
    private long mMaxBitrate;

    public MediaSDKService(Context ctx) {
        mCtx = ctx;
        mExoPlayer = ExoPlayer.Factory.newInstance(RENDERER_COUNT, 1000, 5000);
        mExoPlayer.addListener(this);
        mPlayerControl = new PlayerControl(mExoPlayer);
        mMainHandler = new Handler();
        mListeners = new CopyOnWriteArrayList<>();
        mLastReportedPlaybackState = STATE_IDLE;
        mCurrRendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        // Disable text initially.
        mExoPlayer.setSelectedTrack(TYPE_TEXT, TRACK_DISABLED);

        CookieHandler currentHandler = CookieHandler.getDefault();
        if (currentHandler != defaultCookieManager) {
            CookieHandler.setDefault(defaultCookieManager);
        }

        mMediaController = new KeyCompatibleMediaController(ctx);
        mMediaController.setMediaPlayer(mPlayerControl);
        mMediaController.setEnabled(true);
    }

    /*public MediaSDKService(Context ctx, RendererBuilder rendererBuilder) {
        this.mCurrRendererBuilder = rendererBuilder;
        mExoPlayer = ExoPlayer.Factory.newInstance(RENDERER_COUNT, 1000, 5000);
        mExoPlayer.addListener(this);
        mPlayerControl = new PlayerControl(mExoPlayer);
        mMainHandler = new Handler();
        mListeners = new CopyOnWriteArrayList<>();
        mLastReportedPlaybackState = STATE_IDLE;
        mCurrRendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        // Disable text initially.
        mExoPlayer.setSelectedTrack(TYPE_TEXT, TRACK_DISABLED);

        CookieHandler currentHandler = CookieHandler.getDefault();
        if (currentHandler != defaultCookieManager) {
            CookieHandler.setDefault(defaultCookieManager);
        }

        mMediaController = new KeyCompatibleMediaController(ctx);
        mMediaController.setMediaPlayer(mPlayerControl);
        mMediaController.setEnabled(true);
    }*/

    public PlayerControl getPlayerControl() {
        return mPlayerControl;
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public void setInternalErrorListener(InternalErrorListener listener) {
        mInternalErrorListener = listener;
    }

    public void setInfoListener(InfoListener listener) {
        mInfoListener = listener;
    }

    public void setCaptionListener(CaptionListener listener) {
        mCaptionListener = listener;
    }

    public void setMetadataListener(Id3MetadataListener listener) {
        mId3MetadataListener = listener;
    }

    public void setVideoSurface(Surface surface) {
        this.mSurface = surface;
        pushSurface(false);
    }

    public Surface getSurface() {
        return mSurface;
    }

    public void blockingClearSurface() {
        mSurface = null;
        pushSurface(true);
    }

    public int getTrackCount(int type) {
        return mExoPlayer.getTrackCount(type);
    }

    public MediaFormat getTrackFormat(int type, int index) {
        return mExoPlayer.getTrackFormat(type, index);
    }

    public int getSelectedTrack(int type) {
        return mExoPlayer.getSelectedTrack(type);
    }

    public void setSelectedTrack(int type, int index) {
        mExoPlayer.setSelectedTrack(type, index);
        if (type == TYPE_TEXT && index < 0 && mCaptionListener != null) {
            mCaptionListener.onCues(Collections.<Cue>emptyList());
        }
    }

    public boolean getBackgrounded() {
        return mBackgrounded;
    }

    public void setBackgrounded(boolean backgrounded) {
        if (this.mBackgrounded == backgrounded) {
            return;
        }
        this.mBackgrounded = backgrounded;
        if (backgrounded) {
            mVideoTrackToRestore = getSelectedTrack(TYPE_VIDEO);
            setSelectedTrack(TYPE_VIDEO, TRACK_DISABLED);
            blockingClearSurface();
        } else {
            setSelectedTrack(TYPE_VIDEO, mVideoTrackToRestore);
        }
    }

    public void prepare() {
        if (mCurrRendererBuildingState == RENDERER_BUILDING_STATE_BUILT) {
            mExoPlayer.stop();
        }
        mCurrRendererBuilder.cancel();
        VvideoFormat = null;
        mVideoRenderer = null;
        mCurrRendererBuildingState = RENDERER_BUILDING_STATE_BUILDING;
        maybeReportPlayerState();
        mCurrRendererBuilder.buildRenderers(this);
    }

    /**
     * Invoked with the results from a {@link RendererBuilder}.
     *
     * @param renderers      Renderers indexed by {@link MediaSDKService} TYPE_* constants. An individual
     *                       element may be null if there do not exist tracks of the corresponding type.
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth. May be null.
     */
    public void onRenderers(TrackRenderer[] renderers, BandwidthMeter bandwidthMeter) {
        for (int i = 0; i < RENDERER_COUNT; i++) {
            if (renderers[i] == null) {
                // Convert a null renderer to a dummy renderer.
                renderers[i] = new DummyTrackRenderer();
            }
        }
        // Complete preparation.
        this.mVideoRenderer = renderers[TYPE_VIDEO];
        this.mCodecCounters = mVideoRenderer instanceof MediaCodecTrackRenderer
                ? ((MediaCodecTrackRenderer) mVideoRenderer).codecCounters
                : renderers[TYPE_AUDIO] instanceof MediaCodecTrackRenderer
                ? ((MediaCodecTrackRenderer) renderers[TYPE_AUDIO]).codecCounters : null;
        this.mBandwidthMeter = bandwidthMeter;
        pushSurface(false);
        mExoPlayer.prepare(renderers);
        mCurrRendererBuildingState = RENDERER_BUILDING_STATE_BUILT;
    }

    /**
     * Invoked if a {@link RendererBuilder} encounters an error.
     *
     * @param e Describes the error.
     */
    public  void onRenderersError(Exception e) {
        if (mInternalErrorListener != null) {
            mInternalErrorListener.onRendererInitializationError(e);
        }
        for (Listener listener : mListeners) {
            listener.onError(e);
        }
        mCurrRendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        maybeReportPlayerState();
    }

    public void setPlayWhenReady(boolean playWhenReady) {
        mExoPlayer.setPlayWhenReady(playWhenReady);
    }

    public void seekTo(long positionMs) {
        mExoPlayer.seekTo(positionMs);
    }

    public void release() {
        mCurrRendererBuilder.cancel();
        mCurrRendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        mSurface = null;
        mExoPlayer.release();
    }

    public int getPlaybackState() {
        if (mCurrRendererBuildingState == RENDERER_BUILDING_STATE_BUILDING) {
            return STATE_PREPARING;
        }
        int playerState = mExoPlayer.getPlaybackState();
        if (mCurrRendererBuildingState == RENDERER_BUILDING_STATE_BUILT && playerState == STATE_IDLE) {
            // This is an edge case where the renderers are built, but are still being passed to the
            // mExoPlayer's playback thread.
            return STATE_PREPARING;
        }
        return playerState;
    }

    @Override
    public Format getFormat() {
        return VvideoFormat;
    }

    @Override
    public BandwidthMeter getBandwidthMeter() {
        return mBandwidthMeter;
    }

    @Override
    public CodecCounters getCodecCounters() {
        return mCodecCounters;
    }

    @Override
    public long getCurrentPosition() {
        return mExoPlayer.getCurrentPosition();
    }

    public long getDuration() {
        return mExoPlayer.getDuration();
    }

    public int getBufferedPercentage() {
        return mExoPlayer.getBufferedPercentage();
    }

    public boolean getPlayWhenReady() {
        return mExoPlayer.getPlayWhenReady();
    }

    public  Looper getPlaybackLooper() {
        return mExoPlayer.getPlaybackLooper();
    }

    public  Handler getMainHandler() {
        return mMainHandler;
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int state) {
        maybeReportPlayerState();
    }

    @Override
    public void onPlayerError(ExoPlaybackException exception) {
        mCurrRendererBuildingState = RENDERER_BUILDING_STATE_IDLE;
        for (Listener listener : mListeners) {
            listener.onError(exception);
        }
    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                                   float pixelWidthHeightRatio) {
        for (Listener listener : mListeners) {
            listener.onVideoSizeChanged(width, height, unappliedRotationDegrees, pixelWidthHeightRatio);
        }
    }

    @Override
    public void onDroppedFrames(int count, long elapsed) {
        if (mInfoListener != null) {
            mInfoListener.onDroppedFrames(count, elapsed);
        }
    }

    @Override
    public void onBandwidthSample(int elapsedMs, long bytes, long bitrateEstimate) {
        if (mInfoListener != null) {
            mInfoListener.onBandwidthSample(elapsedMs, bytes, bitrateEstimate);
        }
    }

    @Override
    public void onDownstreamFormatChanged(int sourceId, Format format, int trigger,
                                          long mediaTimeMs) {
        if (mInfoListener == null) {
            return;
        }
        if (sourceId == TYPE_VIDEO) {
            VvideoFormat = format;
            mInfoListener.onVideoFormatEnabled(format, trigger, mediaTimeMs);
        } else if (sourceId == TYPE_AUDIO) {
            mInfoListener.onAudioFormatEnabled(format, trigger, mediaTimeMs);
        }
    }

    @Override
    public void onDrmKeysLoaded() {
        // Do nothing.
    }

    @Override
    public void onDrmSessionManagerError(Exception e) {
        if (mInternalErrorListener != null) {
            mInternalErrorListener.onDrmSessionManagerError(e);
        }
    }

    @Override
    public void onDecoderInitializationError(MediaCodecTrackRenderer.DecoderInitializationException e) {
        if (mInternalErrorListener != null) {
            mInternalErrorListener.onDecoderInitializationError(e);
        }
    }

    @Override
    public void onAudioTrackInitializationError(AudioTrack.InitializationException e) {
        if (mInternalErrorListener != null) {
            mInternalErrorListener.onAudioTrackInitializationError(e);
        }
    }

    @Override
    public void onAudioTrackWriteError(AudioTrack.WriteException e) {
        if (mInternalErrorListener != null) {
            mInternalErrorListener.onAudioTrackWriteError(e);
        }
    }

    @Override
    public void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
        if (mInternalErrorListener != null) {
            mInternalErrorListener.onAudioTrackUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs);
        }
    }

    @Override
    public void onCryptoError(MediaCodec.CryptoException e) {
        if (mInternalErrorListener != null) {
            mInternalErrorListener.onCryptoError(e);
        }
    }

    @Override
    public void onDecoderInitialized(String decoderName, long elapsedRealtimeMs,
                                     long initializationDurationMs) {
        if (mInfoListener != null) {
            mInfoListener.onDecoderInitialized(decoderName, elapsedRealtimeMs, initializationDurationMs);
        }
    }

    @Override
    public void onLoadError(int sourceId, IOException e) {
        if (mInternalErrorListener != null) {
            mInternalErrorListener.onLoadError(sourceId, e);
        }
    }

    @Override
    public void onCues(List<Cue> cues) {
        if (mCaptionListener != null && getSelectedTrack(TYPE_TEXT) != TRACK_DISABLED) {
            mCaptionListener.onCues(cues);
        }
    }

    @Override
    public void onMetadata(List<Id3Frame> id3Frames) {
        if (mId3MetadataListener != null && getSelectedTrack(TYPE_METADATA) != TRACK_DISABLED) {
            mId3MetadataListener.onId3Metadata(id3Frames);
        }
    }

    @Override
    public void onAvailableRangeChanged(int sourceId, TimeRange availableRange) {
        if (mInfoListener != null) {
            mInfoListener.onAvailableRangeChanged(sourceId, availableRange);
        }
    }

    @Override
    public void onPlayWhenReadyCommitted() {
        // Do nothing.
    }

    @Override
    public void onDrawnToSurface(Surface surface) {
        // Do nothing.
    }

    @Override
    public void onLoadStarted(int sourceId, long length, int type, int trigger, Format format,
                              long mediaStartTimeMs, long mediaEndTimeMs) {
        if (mInfoListener != null) {
            mInfoListener.onLoadStarted(sourceId, length, type, trigger, format, mediaStartTimeMs,
                    mediaEndTimeMs);
        }
    }

    @Override
    public void onLoadCompleted(int sourceId, long bytesLoaded, int type, int trigger, Format format,
                                long mediaStartTimeMs, long mediaEndTimeMs, long elapsedRealtimeMs, long loadDurationMs) {
        if (mInfoListener != null) {
            mInfoListener.onLoadCompleted(sourceId, bytesLoaded, type, trigger, format, mediaStartTimeMs,
                    mediaEndTimeMs, elapsedRealtimeMs, loadDurationMs);
        }
    }

    @Override
    public void onLoadCanceled(int sourceId, long bytesLoaded) {
        // Do nothing.
    }

    @Override
    public void onUpstreamDiscarded(int sourceId, long mediaStartTimeMs, long mediaEndTimeMs) {
        // Do nothing.
    }

    private void maybeReportPlayerState() {
        boolean playWhenReady = mExoPlayer.getPlayWhenReady();
        int playbackState = getPlaybackState();
        if (mLastReportedPlayWhenReady != playWhenReady || mLastReportedPlaybackState != playbackState) {
            for (Listener listener : mListeners) {
                listener.onStateChanged(playWhenReady, playbackState);
            }
            mLastReportedPlayWhenReady = playWhenReady;
            mLastReportedPlaybackState = playbackState;
        }
    }

    private void pushSurface(boolean blockForSurfacePush) {
        if (mVideoRenderer == null) {
            return;
        }

        if (blockForSurfacePush) {
            mExoPlayer.blockingSendMessage(
                    mVideoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE, mSurface);
        } else {
            mExoPlayer.sendMessage(
                    mVideoRenderer, MediaCodecVideoTrackRenderer.MSG_SET_SURFACE, mSurface);
        }
    }

    // Internal methods
    private RendererBuilder getRendererBuilder(Context ctx, int contentType, Uri contentUri, MediaDrmCallback mediaDrmCallback) {
        String userAgent = Util.getUserAgent(ctx, "MediaSDKService");
        switch (contentType) {
            case Util.TYPE_SS:
                return new SmoothStreamingRendererBuilder(ctx, userAgent, contentUri.toString(),
                        mediaDrmCallback);
            case Util.TYPE_DASH:
                if(mIsAdaptable) {
                    return new DashRendererBuilder(ctx, userAgent, contentUri.toString(),
                            mediaDrmCallback);
                }
                else {
                    return new DashRendererBuilder(ctx, userAgent, contentUri.toString(),
                            mediaDrmCallback, mMaxBitrate);
                }
            case Util.TYPE_HLS:
                return new HlsRendererBuilder(ctx, userAgent, contentUri.toString());
            case Util.TYPE_OTHER:
                return new ExtractorRendererBuilder(ctx, userAgent, contentUri);
            default:
                throw new IllegalStateException("Unsupported type: " + contentType);
        }
    }

    public boolean onKeyEvent(KeyEvent event) {
        if(event == null) return false;
        return mMediaController.dispatchKeyEvent(event);
    }

    public int setAnchorView(View view) {
        if(view == null) return Constants.ErrorCodes.INVALID_PARAM;
        if(mMediaController == null) return Constants.ErrorCodes.UNKNOWN_ERROR;
        mMediaController.setAnchorView(view);
        return Constants.ErrorCodes.SUCCESS;
    }

    public int showControls() {
        if(mMediaController == null) return Constants.ErrorCodes.UNKNOWN_ERROR;
        mMediaController.show(0);
        return Constants.ErrorCodes.SUCCESS;
    }

    public int hideControls() {
        if(mMediaController == null) return Constants.ErrorCodes.UNKNOWN_ERROR;
        mMediaController.hide();
        return Constants.ErrorCodes.SUCCESS;
    }

    public boolean isControlsVisble() {
        if(mMediaController == null) return false;
        return mMediaController.isShowing();
    }

    public int setDataSource(int contentType, Uri contentUri, MediaDrmCallback mediaDrmCallback) {
        mCurrRendererBuilder = getRendererBuilder(mCtx, contentType, contentUri, mediaDrmCallback);
        return Constants.ErrorCodes.SUCCESS;
    }

    public int setAdaptiveEnabled(boolean enabled) {
        mIsAdaptable = enabled;
        return Constants.ErrorCodes.SUCCESS;
    }

    public int setBitrate(long bitrate) {
        mMaxBitrate = bitrate;
        return Constants.ErrorCodes.SUCCESS;
    }

    private static final class KeyCompatibleMediaController extends MediaController {

        private MediaController.MediaPlayerControl playerControl;

        public KeyCompatibleMediaController(Context context) {
            super(context);
        }

        @Override
        public void setMediaPlayer(MediaController.MediaPlayerControl playerControl) {
            super.setMediaPlayer(playerControl);
            this.playerControl = playerControl;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            int keyCode = event.getKeyCode();
            if (playerControl.canSeekForward() && keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    playerControl.seekTo(playerControl.getCurrentPosition() + 15000); // milliseconds
                    show();
                }
                return true;
            } else if (playerControl.canSeekBackward() && keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    playerControl.seekTo(playerControl.getCurrentPosition() - 5000); // milliseconds
                    show();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }
}
