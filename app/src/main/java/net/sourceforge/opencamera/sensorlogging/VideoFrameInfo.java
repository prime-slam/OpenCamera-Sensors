package net.sourceforge.opencamera.sensorlogging;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;

import net.sourceforge.opencamera.ExtendedAppInterface;
import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.MyDebug;
import net.sourceforge.opencamera.StorageUtils;
import net.sourceforge.opencamera.StorageUtilsWrapper;
import net.sourceforge.opencamera.cameracontroller.YuvImageUtils;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles frame images and timestamps saving during video recording,
 * sequential Executor is used to queue saving tasks in the background thread.
 * Images get saved every EVERY_N_FRAME-th time if shouldSaveFrame is true
 */
public class VideoFrameInfo implements Closeable {
    private final static String TAG = "FrameInfo";
    private final static String TIMESTAMP_FILE_SUFFIX = "_timestamps";
    /*
    Value used to save frames for debugging and matching frames with video
    TODO: in future versions make sure this value is big enough not to cause frame rate drop / buffer allocation problems on devices other than already tested
     */
    private final static int EVERY_N_FRAME = 60;
    private final static int PHASE_CALC_N_FRAMES = 60;

    //Sequential executor for frame and timestamps saving queue
    private final ExecutorService frameProcessor = Executors.newSingleThreadExecutor();
    private final Date mVideoDate;
    private final StorageUtilsWrapper mStorageUtils;
    private final ExtendedAppInterface mAppInterface;
    private final BufferedWriter mFrameBufferedWriter;
    private final boolean mShouldSaveFrames;
    private final Context mContext;
    private final YuvImageUtils mYuvUtils;
    private final BlockingQueue<VideoPhaseInfo> mPhaseInfoReporter;
    private final List<Long> durationsNs;
    private long mLastTimestamp = 0;

    private int mFrameNumber = 0;

    public BlockingQueue<VideoPhaseInfo> getPhaseInfoReporter() {
        return mPhaseInfoReporter;
    }

    public VideoFrameInfo(
            Date videoDate,
            MainActivity context,
            boolean shouldSaveFrames,
            BlockingQueue<VideoPhaseInfo> videoPhaseInfoReporter
    ) throws IOException {
        mVideoDate = videoDate;
        mStorageUtils = context.getStorageUtils();
        mAppInterface = context.getApplicationInterface();
        mShouldSaveFrames = shouldSaveFrames;
        mContext = context;
        mYuvUtils = mAppInterface.getYuvUtils();
        mPhaseInfoReporter = videoPhaseInfoReporter;
        mPhaseInfoReporter.clear();
        durationsNs = new ArrayList<>();


        File frameTimestampFile = mStorageUtils.createOutputCaptureInfo(
                StorageUtils.MEDIA_TYPE_RAW_SENSOR_INFO, "csv", TIMESTAMP_FILE_SUFFIX, mVideoDate
        );
        mFrameBufferedWriter = new BufferedWriter(
                new PrintWriter(frameTimestampFile)
        );
    }

    public void submitProcessFrame(long timestamp) {
        if (!frameProcessor.isShutdown()) {
            frameProcessor.execute(
                    () -> {
                        // TODO: here we assume that video has more frames than PHASE_CALC_N_FRAMES
                        if (mFrameNumber < PHASE_CALC_N_FRAMES) {
                            // Should calculate phase
                            if (mLastTimestamp == 0) {
                                // skip first frame
                            } else {
                                long duration = timestamp - mLastTimestamp;
                                // add frame duration
                                if (MyDebug.LOG) {
                                    Log.d(TAG, "new frame duration, value: " + duration);
                                }
                                durationsNs.add(duration);
                            }
                            mLastTimestamp = timestamp;
                        } else if (mFrameNumber == PHASE_CALC_N_FRAMES) {
                            // Should report phase
                            mPhaseInfoReporter.add(
                                    new VideoPhaseInfo(timestamp, durationsNs)
                            );
                        }

                        writeFrameTimestamp(timestamp);
                        mFrameNumber++;
                    }
            );
        } else {
            Log.e(TAG, "Received new frame after frameProcessor executor shutdown");
        }
    }

    public void submitProcessFrame(long timestamp, byte[] imageData, int width, int height, int rotation) {
        // Submit image data (only if needed)
        if (!frameProcessor.isShutdown()) {
            frameProcessor.execute(
                    () -> {
                        try {
                            if (mShouldSaveFrames && mFrameNumber % EVERY_N_FRAME == 0) {
                                Bitmap bitmap = mYuvUtils.yuv420ToBitmap(imageData, width, height, mContext);

                                if (MyDebug.LOG) {
                                    Log.d(TAG, "Should save frame, timestamp: " + timestamp);
                                }
                                File frameFile = mStorageUtils.createOutputCaptureInfo(
                                        StorageUtils.MEDIA_TYPE_VIDEO_FRAME, "jpg", String.valueOf(timestamp), mVideoDate
                                );
                                writeFrameJpeg(bitmap, frameFile, rotation);
                            }
                        } catch (IOException e) {
                            mAppInterface.onFrameInfoRecordingFailed();
                            Log.e(TAG, "Failed to write frame info, timestamp: " + timestamp);
                            e.printStackTrace();
                            this.close();
                        }
                    }
            );
        } else {
            Log.e(TAG, "Received new frame after frameProcessor executor shutdown");
        }

        // Submit timestamp info (should be used for every frame)
        submitProcessFrame(timestamp);
    }

    private void writeFrameTimestamp(long timestamp) {
        try {
            mFrameBufferedWriter
                    .append(Long.toString(timestamp))
                    .append("\n");
        } catch (IOException e) {
            mAppInterface.onFrameInfoRecordingFailed();
            Log.e(TAG, "Failed to write frame info, timestamp: " + timestamp);
            e.printStackTrace();
            this.close();
        }
    }

    private void writeFrameJpeg(Bitmap bitmap, File frameFile, int rotation) throws IOException {
        FileOutputStream fos = new FileOutputStream(frameFile);
        // Apply rotation
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
        fos.close();
    }

    @Override
    public void close() {
        if (frameProcessor != null) {
            if (MyDebug.LOG) {
                Log.d(TAG, "Attempting to shutdown frame processor");
            }
            // should let all assigned tasks finish execution
            frameProcessor.shutdown();
        }
        if (MyDebug.LOG) {
            Log.d(TAG, "Closing frame info, frame number: " + mFrameNumber);
        }

        try {
            if (mFrameBufferedWriter != null) {
                mFrameBufferedWriter.close();
            }
        } catch (IOException e) {
            Log.d(TAG, "Exception occurred when attempting to close mFrameBufferedWriter");
            e.printStackTrace();
        }
    }
}
