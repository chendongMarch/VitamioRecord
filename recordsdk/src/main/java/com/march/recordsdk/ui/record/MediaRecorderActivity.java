package com.march.recordsdk.ui.record;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.march.recordsdk.R;
import com.march.recordsdk.RecordSDK;
import com.march.recordsdk.common.RecordCommonHelper;
import com.march.recordsdk.common.Logger;
import com.march.recordsdk.common.RecordConstant;
import com.march.recordsdk.ui.BaseRecordActivityWrap;
import com.march.recordsdk.ui.widget.ProgressView;
import com.march.recordsdk.camera.MediaRecorderBase;
import com.march.recordsdk.camera.MediaRecorderNative;
import com.march.recordsdk.camera.VCamera;
import com.march.recordsdk.camera.model.MediaObject;
import com.march.recordsdk.camera.util.DeviceUtils;
import com.march.recordsdk.camera.util.FileUtils;
import com.yixia.videoeditor.adapter.UtilityAdapter;

import java.io.File;
import java.util.ArrayList;

/**
 * 视频录制
 *
 * @author chendong
 */
public class MediaRecorderActivity extends BaseRecordActivityWrap implements MediaRecorderBase.OnErrorListener, OnClickListener, MediaRecorderBase.OnPreparedListener, MediaRecorderBase.OnEncodeListener {
    /**
     * 录制最长时间
     */
    public final static int RECORD_TIME_MAX = RecordSDK.getConfig().getMaxDuration();
    /**
     * 录制最小时间
     */
    public final static int RECORD_TIME_MIN = RecordSDK.getConfig().getMinDuration();
    /**
     * 刷新进度条
     */
    private static final int HANDLE_INVALIDATE_PROGRESS = 0;
    /**
     * 延迟拍摄停止
     */
    private static final int HANDLE_STOP_RECORD = 1;
    /**
     * 对焦
     */
    private static final int HANDLE_HIDE_RECORD_FOCUS = 2;

    /**
     * 下一步
     */
    private ImageView mTitleNext;
    /**
     * 对焦图标-带动画效果
     */
    private ImageView mFocusImage;
    /**
     * 前后摄像头切换
     */
    private CheckBox mCameraSwitch;
    /**
     * 回删按钮、延时按钮、滤镜按钮
     */
    private CheckedTextView mRecordDelete;
    /**
     * 闪光灯
     */
    private CheckBox mRecordLed;
    /**
     * 拍摄按钮
     */
    private ImageView mRecordController;

    /**
     * 底部条
     */
    private RelativeLayout mBottomLayout;
    /**
     * 摄像头数据显示画布
     */
    private SurfaceView mSurfaceView;
    /**
     * 录制进度
     */
    private ProgressView mProgressView;
    /**
     * 对焦动画
     */
    private Animation mFocusAnimation;

    /**
     * SDK视频录制对象
     */
    private MediaRecorderBase mMediaRecorder;
    /**
     * 视频信息
     */
    private MediaObject mMediaObject;

    /**
     * 需要重新编译（拍摄新的或者回删）
     */
    private boolean mRebuild;
    /**
     * on
     */
    private boolean mCreated;
    /**
     * 是否是点击状态
     */
    private volatile boolean mPressedStatus;
    /**
     * 是否已经释放
     */
    private volatile boolean mReleased;
    /**
     * 对焦图片宽度
     */
    private int mFocusWidth;
    /**
     * 底部背景色
     */
    private int mBackgroundColorNormal, mBackgroundColorPress;
    /**
     * 屏幕宽度
     */
    private int mWindowWidth;
    //文件夹hiu
    private String videoFileKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mCreated = false;
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // 防止锁屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN); // 防止锁屏
        loadIntent();
        loadViews();
        mCreated = true;
    }

    /**
     * 加载传入的参数
     */
    private void loadIntent() {
        mWindowWidth = DeviceUtils.getScreenWidth(this);
        mFocusWidth = RecordCommonHelper.dipToPX(this, 64);
        mBackgroundColorNormal = RecordCommonHelper.getColor(mContext, R.color.black);//camera_bottom_bg
        mBackgroundColorPress = RecordCommonHelper.getColor(mContext, R.color.camera_bottom_press_bg);
    }

    /**
     * 加载视图
     */
    private void loadViews() {
        setContentView(R.layout.activity_media_recorder);
        // ~~~ 绑定控件
        mSurfaceView = (SurfaceView) findViewById(R.id.record_preview);
        mCameraSwitch = (CheckBox) findViewById(R.id.record_camera_switcher);
        mTitleNext = (ImageView) findViewById(R.id.title_next);
        mFocusImage = (ImageView) findViewById(R.id.record_focusing);
        mProgressView = (ProgressView) findViewById(R.id.record_progress);
        mRecordDelete = (CheckedTextView) findViewById(R.id.record_delete);
        mRecordController = (ImageView) findViewById(R.id.record_controller);
        mBottomLayout = (RelativeLayout) findViewById(R.id.bottom_layout);
        mRecordLed = (CheckBox) findViewById(R.id.record_camera_led);

        // ~~~ 绑定事件
        if (DeviceUtils.hasICS())
            mSurfaceView.setOnTouchListener(mOnSurfaceViewTouchListener);

        mTitleNext.setOnClickListener(this);
        setClick(R.id.title_back, this);
        setClick(R.id.record_test, this);
        mRecordDelete.setOnClickListener(this);
        mBottomLayout.setOnTouchListener(mOnVideoControllerTouchListener);

        // ~~~ 设置数据

        //是否支持前置摄像头
        if (MediaRecorderBase.isSupportFrontCamera()) {
            mCameraSwitch.setOnClickListener(this);
        } else {
            mCameraSwitch.setVisibility(View.GONE);
        }
        //是否支持闪光灯
        if (DeviceUtils.isSupportCameraLedFlash(getPackageManager())) {
            mRecordLed.setOnClickListener(this);
        } else {
            mRecordLed.setVisibility(View.GONE);
        }

        try {
            mFocusImage.setImageResource(R.drawable.video_focus);
        } catch (OutOfMemoryError e) {
            Logger.e(e);
        }

        mProgressView.setMaxDuration(RECORD_TIME_MAX);
        initSurfaceView();
    }

    /**
     * 初始化画布
     */
    private void initSurfaceView() {
        final int size = DeviceUtils.getScreenWidth(this);
        ((RelativeLayout.LayoutParams) mBottomLayout.getLayoutParams()).topMargin = size;
        mSurfaceView.getLayoutParams().width = size;
        mSurfaceView.getLayoutParams().height = (int) (size * 4.0f / 3);
        mBottomLayout.setBackgroundColor(mBackgroundColorNormal);
    }

    /**
     * 初始化拍摄SDK
     */
    private void initMediaRecorder() {
        mMediaRecorder = new MediaRecorderNative();
        mMediaRecorder.setOutputSize(480, 480);
        mRebuild = true;

        mMediaRecorder.setOnErrorListener(this);
        mMediaRecorder.setOnEncodeListener(this);
        File f = new File(VCamera.getVideoCachePath());
        if (!FileUtils.checkFile(f)) {
            f.mkdirs();
        }
        if (videoFileKey == null || videoFileKey.length() <= 0)
            videoFileKey = String.valueOf(System.currentTimeMillis());
        //构建返回MediaObject,key 是文件夹，path是路径
        mMediaObject = mMediaRecorder.setOutputDirectory(videoFileKey, VCamera.getVideoCachePath() + videoFileKey);
        mMediaRecorder.setSurfaceHolder(mSurfaceView.getHolder());
        mMediaRecorder.prepare();
    }

    /**
     * 点击屏幕聚焦
     */
    private View.OnTouchListener mOnSurfaceViewTouchListener = new View.OnTouchListener() {

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (mMediaRecorder == null || !mCreated) {
                return false;
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    //检测是否手动对焦
                    if (checkCameraFocus(event))
                        return true;
                    break;
            }
            return true;
        }

    };

    /**
     * 点击屏幕录制
     */
    private View.OnTouchListener mOnVideoControllerTouchListener = new View.OnTouchListener() {

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (mMediaRecorder == null) {
                return false;
            }

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    //检测是否手动对焦
                    //判断是否已经超时
                    if (mMediaObject.getDuration() >= RECORD_TIME_MAX) {
                        return true;
                    }
                    //取消回删
                    if (cancelDelete())
                        return true;
                    startRecord();
                    break;

                case MotionEvent.ACTION_UP:
                    // 暂停
                    if (mPressedStatus) {
                        stopRecord();

                        //检测是否已经完成
                        if (mMediaObject.getDuration() >= RECORD_TIME_MAX) {
                            mTitleNext.performClick();
                        }
                    }
                    break;
            }
            return true;
        }

    };

    @Override
    public void onResume() {
        super.onResume();
        UtilityAdapter.freeFilterParser();
        UtilityAdapter.initFilterParser();

        if (mMediaRecorder == null) {
            initMediaRecorder();
        } else {
            mRecordLed.setChecked(false);
            mMediaRecorder.prepare();
            mProgressView.setData(mMediaObject);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopRecord();
        UtilityAdapter.freeFilterParser();
        if (!mReleased) {
            if (mMediaRecorder != null)
                mMediaRecorder.release();
        }
        mReleased = false;
    }

    /**
     * 手动对焦
     */
    @SuppressLint("NewApi")
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private boolean checkCameraFocus(MotionEvent event) {
        mFocusImage.setVisibility(View.GONE);
        float x = event.getX();
        float y = event.getY();
        float touchMajor = event.getTouchMajor();
        float touchMinor = event.getTouchMinor();

        Rect touchRect = new Rect((int) (x - touchMajor / 2), (int) (y - touchMinor / 2), (int) (x + touchMajor / 2), (int) (y + touchMinor / 2));
        //The direction is relative to the sensor orientation, that is, what the sensor sees. The direction is not affected by the rotation or mirroring of setDisplayOrientation(int). Coordinates of the rectangle range from -1000 to 1000. (-1000, -1000) is the upper left point. (1000, 1000) is the lower right point. The width and height of focus areas cannot be 0 or negative.
        //No matter what the zoom level is, (-1000,-1000) represents the top of the currently visible camera frame
        if (touchRect.right > 1000)
            touchRect.right = 1000;
        if (touchRect.bottom > 1000)
            touchRect.bottom = 1000;
        if (touchRect.left < 0)
            touchRect.left = 0;
        if (touchRect.right < 0)
            touchRect.right = 0;

        if (touchRect.left >= touchRect.right || touchRect.top >= touchRect.bottom)
            return false;

        ArrayList<Camera.Area> focusAreas = new ArrayList<Camera.Area>();
        focusAreas.add(new Camera.Area(touchRect, 1000));
        if (!mMediaRecorder.manualFocus(new Camera.AutoFocusCallback() {

            @Override
            public void onAutoFocus(boolean success, Camera camera) {
                mFocusImage.setVisibility(View.GONE);
            }
        }, focusAreas)) {
            mFocusImage.setVisibility(View.GONE);
        }

        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) mFocusImage.getLayoutParams();
        int left = touchRect.left - (mFocusWidth / 2);//(int) x - (focusingImage.getWidth() / 2);
        int top = touchRect.top - (mFocusWidth / 2);//(int) y - (focusingImage.getHeight() / 2);
        if (left < 0)
            left = 0;
        else if (left + mFocusWidth > mWindowWidth)
            left = mWindowWidth - mFocusWidth;
        if (top + mFocusWidth > mWindowWidth)
            top = mWindowWidth - mFocusWidth;

        lp.leftMargin = left;
        lp.topMargin = top;
        mFocusImage.setLayoutParams(lp);
        mFocusImage.setVisibility(View.VISIBLE);

        if (mFocusAnimation == null)
            mFocusAnimation = AnimationUtils.loadAnimation(this, R.anim.record_focus);

        mFocusImage.startAnimation(mFocusAnimation);

        mHandler.sendEmptyMessageDelayed(HANDLE_HIDE_RECORD_FOCUS, 3500);//最多3.5秒也要消失
        return true;
    }

    /**
     * 开始录制
     */
    private void startRecord() {
        if (mMediaRecorder != null) {
            MediaObject.MediaPart part = mMediaRecorder.startRecord();
            if (part == null) {
                return;
            }
            mProgressView.setData(mMediaObject);
        }

        mRebuild = true;
        mPressedStatus = true;
        mRecordController.setImageResource(R.drawable.record_controller_press);
        mBottomLayout.setBackgroundColor(mBackgroundColorPress);

        if (mHandler != null) {
            mHandler.removeMessages(HANDLE_INVALIDATE_PROGRESS);
            mHandler.sendEmptyMessage(HANDLE_INVALIDATE_PROGRESS);

            mHandler.removeMessages(HANDLE_STOP_RECORD);
            mHandler.sendEmptyMessageDelayed(HANDLE_STOP_RECORD, RECORD_TIME_MAX - mMediaObject.getDuration());
        }
        mRecordDelete.setVisibility(View.GONE);
        mCameraSwitch.setEnabled(false);
        mRecordLed.setEnabled(false);
    }

    @Override
    public void onBackPressed() {
        if (mRecordDelete != null && mRecordDelete.isChecked()) {
            cancelDelete();
            return;
        }

        if (mMediaObject != null && mMediaObject.getDuration() > 1) {
            //未转码
            new AlertDialog.Builder(this).setTitle(R.string.hint).setMessage(R.string.record_camera_exit_dialog_message).setNegativeButton(R.string.record_camera_cancel_dialog_yes, new DialogInterface.OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int which) {
                    mMediaObject.delete();
                    finish();
                }

            }).setPositiveButton(R.string.record_camera_cancel_dialog_no, null).setCancelable(false).show();
            return;
        }

        if (mMediaObject != null)
            mMediaObject.delete();

        finish();
    }

    /**
     * 停止录制
     */
    private void stopRecord() {
        mPressedStatus = false;
        mRecordController.setImageResource(R.drawable.record_controller_normal);
        mBottomLayout.setBackgroundColor(mBackgroundColorNormal);

        if (mMediaRecorder != null) {
            mMediaRecorder.stopRecord();
        }

        mRecordDelete.setVisibility(View.VISIBLE);
        mCameraSwitch.setEnabled(true);
        mRecordLed.setEnabled(true);

        mHandler.removeMessages(HANDLE_STOP_RECORD);
        checkStatus();
    }

    @Override
    public void onClick(View v) {
        final int id = v.getId();
        if (mMediaRecorder == null || mMediaObject == null) return;
        if (mHandler.hasMessages(HANDLE_STOP_RECORD)) {
            mHandler.removeMessages(HANDLE_STOP_RECORD);
        }

        //处理开启回删后其他点击操作
        if (id != R.id.record_delete) {
            MediaObject.MediaPart part = mMediaObject.getCurrentPart();
            if (part != null) {
                if (part.remove) {
                    part.remove = false;
                    mRecordDelete.setChecked(false);
                    if (mProgressView != null)
                        mProgressView.invalidate();
                }
            }
        }
        if (id == R.id.title_back) {
            onBackPressed();
        } else if (id == R.id.record_camera_switcher) {
            if (mRecordLed.isChecked()) {
                if (mMediaRecorder != null) {
                    mMediaRecorder.toggleFlashMode();
                }
                mRecordLed.setChecked(false);
            }
            mMediaRecorder.switchCamera();

            if (mMediaRecorder.isFrontCamera()) {
                mRecordLed.setEnabled(false);
            } else {
                mRecordLed.setEnabled(true);
            }
        } else if (id == R.id.record_camera_led) {
            //开启前置摄像头以后不支持开启闪光灯
            if (mMediaRecorder.isFrontCamera()) {
                return;
            }
            mMediaRecorder.toggleFlashMode();
        } else if (id == R.id.title_next) {
            mMediaRecorder.startEncoding();
        } else if (id == R.id.record_delete) {
            //取消回删
            MediaObject.MediaPart part = mMediaObject.getCurrentPart();
            if (part != null) {
                if (part.remove) {
                    mRebuild = true;
                    part.remove = false;
                    mMediaObject.removePart(part, true);
                    mRecordDelete.setChecked(false);
                } else {
                    part.remove = true;
                    mRecordDelete.setChecked(true);
                }
            }
            if (mProgressView != null)
                mProgressView.invalidate();
            //检测按钮状态
            checkStatus();
        }
    }


    /**
     * 取消回删
     */
    private boolean cancelDelete() {
        if (mMediaObject != null) {
            MediaObject.MediaPart part = mMediaObject.getCurrentPart();
            if (part != null && part.remove) {
                part.remove = false;
                mRecordDelete.setChecked(false);

                if (mProgressView != null)
                    mProgressView.invalidate();

                return true;
            }
        }
        return false;
    }

    /**
     * 检查录制时间，显示/隐藏下一步按钮
     */
    private int checkStatus() {
        int duration = 0;
        if (!isFinishing() && mMediaObject != null) {
            duration = mMediaObject.getDuration();
            if (duration < RECORD_TIME_MIN) {
                if (duration == 0) {
                    mCameraSwitch.setVisibility(View.VISIBLE);
                    mRecordDelete.setVisibility(View.GONE);
                }
                //视频必须大于3秒
                if (mTitleNext.getVisibility() != View.INVISIBLE)
                    mTitleNext.setVisibility(View.INVISIBLE);
            } else {
                //下一步
                if (mTitleNext.getVisibility() != View.VISIBLE) {
                    mTitleNext.setVisibility(View.VISIBLE);
                }
            }
        }
        return duration;
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case HANDLE_INVALIDATE_PROGRESS:
                    if (mMediaRecorder != null && !isFinishing()) {
                        if (mProgressView != null)
                            mProgressView.invalidate();
                        //					if (mPressedStatus)
                        //						titleText.setText(String.format("%.1f", mMediaRecorder.getDuration() / 1000F));
                        if (mPressedStatus)
                            sendEmptyMessageDelayed(0, 30);
                    }
                    break;
            }
        }
    };

    @Override
    public void onEncodeStart() {
        showProgress("", getString(R.string.record_camera_progress_message));
    }

    @Override
    public void onEncodeProgress(int progress) {
        Logger.e("[MediaRecorderActivity]onEncodeProgress..." + progress);
    }

    /**
     * 转码完成
     */
    @Override
    public void onEncodeComplete() {
        hideProgress();
        Intent intent = new Intent(this, NewMediaPreviewActivity.class);
        Bundle bundle = getIntent().getExtras();
        if (bundle == null)
            bundle = new Bundle();
        bundle.putSerializable(RecordConstant.EXTRA_MEDIA_OBJECT, mMediaObject);
        bundle.putString("output", mMediaObject.getOutputTempVideoPath());
        bundle.putBoolean("Rebuild", mRebuild);
        intent.putExtras(bundle);
        startActivity(intent);
        mRebuild = false;
    }

    /**
     * 转码失败
     * 检查sdcard是否可用，检查分块是否存在
     */
    @Override
    public void onEncodeError() {
        hideProgress();
        Toast.makeText(this, R.string.record_video_transcoding_faild, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onVideoError(int what, int extra) {

    }

    @Override
    public void onAudioError(int what, String message) {

    }

    @Override
    public void onPrepared() {

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.e("chendong", "onDestroy");
        if (mMediaObject != null) {
            mMediaObject.delete();
        }
        if (mMediaRecorder != null) {
            mMediaRecorder.release();
        }
    }
}
