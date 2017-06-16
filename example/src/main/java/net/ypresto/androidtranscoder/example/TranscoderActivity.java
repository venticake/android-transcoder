package net.ypresto.androidtranscoder.example;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.graphics.BitmapFactory;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.support.v4.content.FileProvider;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import net.ypresto.androidtranscoder.MediaTranscoder;
import net.ypresto.androidtranscoder.example.databinding.ActivityTranscoderBinding;
import net.ypresto.androidtranscoder.format.MediaFormatStrategy;
import net.ypresto.androidtranscoder.format.MediaFormatStrategyPresets;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.Future;


public class TranscoderActivity extends Activity implements TranscodeInputs {
  private static final String TAG = "TranscoderActivity";
  private static final String FILE_PROVIDER_AUTHORITY = "net.ypresto.androidtranscoder.example.fileprovider";
  private static final int REQUEST_CODE_PICK = 1;
  private static final int PROGRESS_BAR_MAX = 1000;
  private Future<Void> mFuture;

  private ActivityTranscoderBinding binding;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    binding = DataBindingUtil.setContentView(this, R.layout.activity_transcoder);
    binding.setInputs(this);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    switch (requestCode) {
      case REQUEST_CODE_PICK: {
        final File file;
        if (resultCode == RESULT_OK) {
          try {
            File outputDir = new File(getExternalFilesDir(null), "outputs");
            //noinspection ResultOfMethodCallIgnored
            outputDir.mkdir();
            file = File.createTempFile("transcode_test", ".mp4", outputDir);
          } catch (IOException e) {
            Log.e(TAG, "Failed to create temporary file.", e);
            Toast.makeText(this, "Failed to create temporary file.", Toast.LENGTH_LONG).show();
            return;
          }
          ContentResolver resolver = getContentResolver();
          final ParcelFileDescriptor parcelFileDescriptor;
          try {
            parcelFileDescriptor = resolver.openFileDescriptor(data.getData(), "r");
          } catch (FileNotFoundException e) {
            Log.w("Could not open '" + data.getDataString() + "'", e);
            Toast.makeText(TranscoderActivity.this, "File not found.", Toast.LENGTH_LONG).show();
            return;
          }
          Log.i(TAG, "original size=" + parcelFileDescriptor.getStatSize());
          final FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
          binding.progressBar.setMax(PROGRESS_BAR_MAX);
          final long startTime = SystemClock.uptimeMillis();
          MediaTranscoder.Listener listener = new MediaTranscoder.Listener() {
            @Override
            public void onTranscodeProgress(double progress) {
              updateProgress((int) Math.round(progress * PROGRESS_BAR_MAX));
            }

            @Override
            public void onTranscodeCompleted() {
              Log.d(TAG, "transcoding took " + (SystemClock.uptimeMillis() - startTime) + "ms");
              printResult(file.length());
              onTranscodeFinished(true, "transcoded file placed on " + file, parcelFileDescriptor);
              Uri uri = FileProvider.getUriForFile(TranscoderActivity.this, FILE_PROVIDER_AUTHORITY, file);
              startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4")
                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            }

            @Override
            public void onTranscodeCanceled() {
              onTranscodeFinished(false, "Transcoder canceled.", parcelFileDescriptor);
            }

            @Override
            public void onTranscodeFailed(Exception exception) {
              onTranscodeFinished(false, "Transcoder error occurred.", parcelFileDescriptor);
            }
          };
          Log.d(TAG, "transcoding into " + file);

          final MediaFormatStrategy mediaFormatStrategy = !isBitrateInputEmpty() ?
            new MediaFormatStrategy() {
              @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
              @Override
              public MediaFormat createVideoOutputFormat(MediaFormat inputFormat) {
                final int width = inputFormat.getInteger(MediaFormat.KEY_WIDTH);
                final int height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT);
                final int originalFrameRate = inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE);
                final int bitRate = Integer.parseInt(getBitrateInputValue());

                MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, originalFrameRate);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, -1000);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                return format;
              }

              @Override
              public MediaFormat createAudioOutputFormat(MediaFormat inputFormat) {
                return null;
              }
            } :
            MediaFormatStrategyPresets.createExportPreset960x540Strategy();

          mFuture = MediaTranscoder.getInstance().transcodeVideo(fileDescriptor, file.getAbsolutePath(), mediaFormatStrategy, listener);
          switchButtonEnabled(true);
        }
        break;
      }
      default:
        super.onActivityResult(requestCode, resultCode, data);
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    getMenuInflater().inflate(R.menu.transcoder, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    int id = item.getItemId();
    if (id == R.id.action_settings) {
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private void onTranscodeFinished(boolean isSuccess, String toastMessage, ParcelFileDescriptor parcelFileDescriptor) {
    binding.progressBar.setIndeterminate(false);
    binding.progressBar.setProgress(isSuccess ? PROGRESS_BAR_MAX : 0);
    switchButtonEnabled(false);
    Toast.makeText(TranscoderActivity.this, toastMessage, Toast.LENGTH_LONG).show();
    try {
      parcelFileDescriptor.close();
    } catch (IOException e) {
      Log.w("Error while closing", e);
    }
  }

  private void printResult(long fileSize) {
    final String currentResults = binding.transcodeResult.getText().toString();
    binding.transcodeResult.setText(String.format(currentResults + "size=%d\n", fileSize));
  }

  private void switchButtonEnabled(boolean isProgress) {
    findViewById(R.id.select_video_button).setEnabled(!isProgress);
    findViewById(R.id.cancel_button).setEnabled(isProgress);
  }

  private void updateProgress(final int progress) {
    if (progress < 0) {
      binding.progressBar.setIndeterminate(true);
    } else {
      binding.progressBar.setIndeterminate(false);
      binding.progressBar.setProgress(progress);
    }
  }

  private boolean isBitrateInputEmpty() {
    return TextUtils.isEmpty(getBitrateInputValue());
  }

  private String getBitrateInputValue() {
    return binding.bitrateInput.getText().toString();
  }

  @Override public void onSelectVideoButtonClick() {
    startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("video/*"), REQUEST_CODE_PICK);
  }

  @Override public void onCancelTranscodeButtonClick() {
    mFuture.cancel(true);
  }
}
