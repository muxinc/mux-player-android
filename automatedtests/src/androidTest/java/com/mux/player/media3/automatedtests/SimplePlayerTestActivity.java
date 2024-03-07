package com.mux.player.media3.automatedtests;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.support.v4.media.session.MediaSessionCompat;
import android.view.View;
import android.view.WindowManager;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.ui.PlayerNotificationManager;
import androidx.media3.ui.PlayerView;
import com.mux.player.MuxPlayer;
import com.mux.player.media.MuxMediaSourceFactory;
import com.mux.player.media3.R;
import com.mux.player.media3.automatedtests.mockup.MockNetworkRequest;
import com.mux.player.media3.test.BuildConfig;
import com.mux.stats.sdk.core.model.CustomerData;
import com.mux.stats.sdk.core.model.CustomerPlayerData;
import com.mux.stats.sdk.core.model.CustomerVideoData;
import com.mux.stats.sdk.core.model.CustomerViewerData;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SimplePlayerTestActivity extends AppCompatActivity implements AnalyticsListener {

  public static final String TAG = "SimplePlayerActivity";

  protected static final String PLAYBACK_CHANNEL_ID = "playback_channel";
  protected static final int PLAYBACK_NOTIFICATION_ID = 1;
  protected static final String ARG_URI = "uri_string";
  protected static final String ARG_TITLE = "title";
  protected static final String ARG_START_POSITION = "start_position";

  MuxMediaSourceFactory mediaSourceFactory;

  public String videoTitle = "Test Video";
  public String urlToPlay;
  public PlayerView playerView;
  public MuxPlayer player;
  public DefaultTrackSelector trackSelector;
  public MediaSource testMediaSource;
  public AdsLoader adsLoader;
  public Uri loadedAdTagUri;
  public boolean playWhenReady = true;
  public MockNetworkRequest mockNetwork;
  public AtomicBoolean onResumedCalled = new AtomicBoolean(false);
  public PlayerNotificationManager notificationManager;
  public MediaSessionCompat mediaSessionCompat;
  //  public MediaSessionConnector mediaSessionConnector;
  public long playbackStartPosition = 0;

  TestEventListener eventListener;
  public Lock activityLock = new ReentrantLock();
  public Condition playbackEnded = activityLock.newCondition();
  public Condition playbackStopped = activityLock.newCondition();
  public Condition seekEnded = activityLock.newCondition();
  public Condition playbackStarted = activityLock.newCondition();
  public Condition playbackBuffering = activityLock.newCondition();
  public Condition activityClosed = activityLock.newCondition();
  public Condition activityInitialized = activityLock.newCondition();
  public ArrayList<String> addAllowedHeaders = new ArrayList<>();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Enter fullscreen
    hideSystemUI();
    setContentView(R.layout.activity_simple_player_test);
    disableUserActions();

    playerView = findViewById(R.id.player_view);

    initExoPlayer();
    playerView.setPlayer(player);

    // Do not hide controlls
    playerView.setControllerShowTimeoutMs(0);
    playerView.setControllerHideOnTouch(false);

    // Setup notification and media session.
    initAudioSession();
  }

  public void allowHeaderToBeSentToBackend(String headerName) {
    addAllowedHeaders.add(headerName);
  }

  @Override
  protected void onResume() {
    super.onResume();
    onResumedCalled.set(true);
  }

  @Override
  public void onStop() {
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    signalActivityClosed();
    if (player != null) {
      player.release();
    }
  }

  public void initExoPlayer() {
    // Hopfully this will not channge the track selection set programmatically
    ExoTrackSelection.Factory trackSelectionFactory = new AdaptiveTrackSelection.Factory(
        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS * 10,
        AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS * 10,
        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
        AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION
    );
    DefaultTrackSelector.ParametersBuilder builder =
        new DefaultTrackSelector.ParametersBuilder(/* context= */ this);
    DefaultTrackSelector.Parameters trackSelectorParameters = builder
        .build();

    mediaSourceFactory = new MuxMediaSourceFactory(this);
    trackSelector = new DefaultTrackSelector(/* context= */ this, trackSelectionFactory);
    trackSelector.setParameters(trackSelectorParameters);
    RenderersFactory renderersFactory = new DefaultRenderersFactory(/* context= */ this);
    eventListener = new TestEventListener(this);
    AutomatedtestsExoPlayerBinding pBinding = new AutomatedtestsExoPlayerBinding(eventListener);
    mockNetwork = new MockNetworkRequest();
    // TODO init TestEventListener, send it in lambda
    player = new MuxPlayer.Builder(this)
        .plusExoConfig((ExoPlayer.Builder exoBuilder) -> {
          exoBuilder.setRenderersFactory(renderersFactory);
          exoBuilder.setMediaSourceFactory(mediaSourceFactory);
          exoBuilder.setTrackSelector(trackSelector);
          return null;
        })
        .addMonitoringData(initMuxSats())
        .addExoPlayerBinding(pBinding)
        .addNetwork(mockNetwork)
        .build();

    player.addAnalyticsListener(this);
    playerView.setPlayer(player);
  }

  public void initAudioSession() {

  }

  public void startPlayback() {
    MediaItem mediaItem = new MediaItem.Builder()
        .setUri(Uri.parse(urlToPlay))
        .setMediaMetadata(
            new MediaMetadata.Builder()
                .setTitle("Basic MuxPlayer Example")
                .build()
        )
        .build();
    player.setMediaItem(mediaItem);
    player.prepare();
    player.setPlayWhenReady(playWhenReady);
  }

  public void setPlayWhenReady(boolean playWhenReady) {
    this.playWhenReady = playWhenReady;
  }

  public void setVideoTitle(String title) {
    videoTitle = title;
  }

  public void setAdTag(String tag) {
    loadedAdTagUri = Uri.parse(tag);
  }

  public void setUrlToPlay(String url) {
    urlToPlay = url;
  }

  public DefaultTrackSelector getTrackSelector() {
    return trackSelector;
  }

  public void setPlaybackStartPosition(long position) {
    playbackStartPosition = position;
  }

  public void hideSystemUI() {
    // Enables regular immersive mode.
    // For "lean back" mode, remove SYSTEM_UI_FLAG_IMMERSIVE.
    // Or for "sticky immersive," replace it with SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    if (VERSION.SDK_INT >= VERSION_CODES.KITKAT) {
      View decorView = getWindow().getDecorView();
      decorView.setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
              // Set the content to appear under the system bars so that the
              // content doesn't resize when the system bars hide and show.
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              // Hide the nav bar and status bar
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }
  }

  // Shows the system bars by removing all the flags
  // except for the ones that make the content appear under the system bars.
  public void showSystemUI() {
    View decorView = getWindow().getDecorView();
    decorView.setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
  }

  public MuxPlayer getPlayer() {
    return player;
  }

  private CustomerData initMuxSats() {


    // Mux details
    CustomerPlayerData customerPlayerData = new CustomerPlayerData();
    if (BuildConfig.SHOULD_REPORT_INSTRUMENTATION_TEST_EVENTS_TO_SERVER) {
      customerPlayerData.setEnvironmentKey(BuildConfig.INSTRUMENTATION_TEST_ENVIRONMENT_KEY);
    } else {
      customerPlayerData.setEnvironmentKey("YOUR_ENVIRONMENT_KEY");
    }
    CustomerVideoData customerVideoData = new CustomerVideoData();
    customerVideoData.setVideoTitle(videoTitle);
    CustomerData customerData = new CustomerData(customerPlayerData, customerVideoData, null);
    CustomerViewerData customerViewerData = new CustomerViewerData();
    customerViewerData.setMuxViewerDeviceCategory(PlaybackTests.DEVICE_CATEGORY_OVERRIDE);
    customerViewerData.setMuxViewerDeviceManufacturer(PlaybackTests.DEVICE_MANUFACTURER_OVERRIDE);
    customerViewerData.setMuxViewerDeviceModel(PlaybackTests.DEVICE_MODEL_OVERRIDE);
    customerViewerData.setMuxViewerDeviceName(PlaybackTests.DEVICE_NAME_OVERRIDE);
    customerViewerData.setMuxViewerOsFamily(PlaybackTests.DEVICE_OS_FAMILY_OVERRIDE);
    customerViewerData.setMuxViewerOsVersion(PlaybackTests.DEVICE_OS_VERSION_OVERRIDE);
    customerData.setCustomerViewerData(customerViewerData);

//    for (String headerName : addAllowedHeaders) {
//      MuxStatsHelper.allowHeaderToBeSentToBackend(muxStats, headerName);
//    }
    return customerData;
  }

  public MediaSource getTestMediaSource() {
    return testMediaSource;
  }

  public PlayerView getPlayerView() {
    return playerView;
  }

  public MockNetworkRequest getMockNetwork() {
    return mockNetwork;
  }

  public boolean waitForPlaybackToStop(long timeoutInMs) {
    try {
      activityLock.lock();
      return playbackStopped.await(timeoutInMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
      return false;
    } finally {
      activityLock.unlock();
    }
  }

//  public boolean waitForSeekEnd(long timeoutInMs) {
//    try {
//      activityLock.lock();
//      return seekEnded.await(timeoutInMs, TimeUnit.MILLISECONDS);
//    } catch (InterruptedException e) {
//      e.printStackTrace();
//      return false;
//    } finally {
//      activityLock.unlock();
//    }
//  }

  public boolean waitForPlaybackToFinish(long timeoutInMs) {
    try {
      activityLock.lock();
      return playbackEnded.await(timeoutInMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
      return false;
    } finally {
      activityLock.unlock();
    }
  }

  public void waitForActivityToInitialize() {
    if (!onResumedCalled.get()) {
      try {
        activityLock.lock();
        activityInitialized.await();
        activityLock.unlock();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  public boolean waitForPlaybackToStart(long timeoutInMs) {
    try {
      activityLock.lock();
      return playbackStarted.await(timeoutInMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
      return false;
    } finally {
      activityLock.unlock();
    }
  }

  public void waitForPlaybackToStartBuffering() {
//    if (player.isPlaying()) {
      try {
        activityLock.lock();
        playbackBuffering.await();
      } catch (InterruptedException e) {
        e.printStackTrace();
      } finally {
        activityLock.unlock();
      }
//    }
  }

  public void waitForActivityToClose() {
    try {
      activityLock.lock();
      activityClosed.await();
    } catch (InterruptedException e) {
      e.printStackTrace();
    } finally {
      activityLock.unlock();
    }
  }

  public void signalPlaybackStarted() {
    activityLock.lock();
    playbackStarted.signalAll();
    activityLock.unlock();
  }

  public void signalPlaybackStopped() {
    activityLock.lock();
    playbackStopped.signalAll();
    activityLock.unlock();
  }

  public void signalSeekEnded() {
    activityLock.lock();
    seekEnded.signalAll();
    activityLock.unlock();
  }

  public void signalPlaybackBuffering() {
    activityLock.lock();
    playbackBuffering.signalAll();
    activityLock.unlock();
  }

  public void signalPlaybackEnded() {
    activityLock.lock();
    playbackEnded.signalAll();
    activityLock.unlock();
  }

  public void signalActivityClosed() {
    activityLock.lock();
    activityClosed.signalAll();
    activityLock.unlock();
  }

  private void disableUserActions() {
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
  }

  private void enableUserActions() {
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
  }

  public class MDAdapter implements PlayerNotificationManager.MediaDescriptionAdapter {
    @Nullable
    @Override
    public PendingIntent createCurrentContentIntent(Player player) {
      return PendingIntent.getActivity(
          getApplicationContext(),
          0,
          new Intent(getApplicationContext(), SimplePlayerTestActivity.class),
          PendingIntent.FLAG_UPDATE_CURRENT
      );
    }

    @Nullable
    @Override
    public String getCurrentContentText(Player player) {
      return "Automated test playback";
    }

    @Nullable
    @Override
    public Bitmap getCurrentLargeIcon(Player player,
        PlayerNotificationManager.BitmapCallback callback) {
      return getBitmapFromVectorDrawable(R.drawable.ic_launcher_foreground);
    }

    @MainThread
    private Bitmap getBitmapFromVectorDrawable(int drawableId) {
      Drawable drawable = ContextCompat.getDrawable(getApplicationContext(), drawableId);
      DrawableCompat.wrap(drawable).mutate();
      Bitmap bmp = Bitmap.createBitmap(
          drawable.getIntrinsicWidth(),
          drawable.getIntrinsicHeight(),
          Bitmap.Config.ARGB_8888);
      Canvas cnvs = new Canvas(bmp);
      drawable.setBounds(0, 0, cnvs.getWidth(), cnvs.getHeight());
      drawable.draw(cnvs);
      return bmp;
    }

    @Override
    public CharSequence getCurrentContentTitle(Player player) {
      return null;
    }
  }
}